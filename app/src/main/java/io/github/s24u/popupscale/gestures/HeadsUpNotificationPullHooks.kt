package io.github.s24u.popupscale.gestures

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.*
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import io.github.s24u.popupscale.ModuleSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/** Detailed (pinned heads-up) notifications only. Brief notifications stay entirely native. */
@android.annotation.TargetApi(34)
class HeadsUpNotificationPullHooks(private val loader:ClassLoader,private val config:FeatureConfig,
                                   private val shadeBlocked:()->Boolean={false}) {
    private inner class Entry(owner:View,host:FrameLayout){
        val source=WeakReference(owner)
        val root=WeakReference(host)
        var bar=WeakReference<FrameLayout>(null)
        var toast=WeakReference<View>(null)
        var pill=WeakReference<View>(null)
        var pending:PendingIntent?=null
        var generation=0;var downGeneration=-1
        var downPending:PendingIntent?=null
        var showing=true;var eligible=false;var launched=false;var dragging=false;var cancelled=false
        val touchSlop=ViewConfiguration.get(host.context).scaledTouchSlop
        var pullCommitted=false
        var pointer=-1;var downX=0f;var downY=0f
        var velocity:VelocityTracker?=null
        val hit=Rect();val toastRect=Rect();val hostLocation=IntArray(2);val rootLocation=IntArray(2)
        var observer:WeakReference<ViewTreeObserver>?=null
        var insets:Any?=null
        val preDraw=ViewTreeObserver.OnPreDrawListener{
            if(Guard.open("notification-pull-frame"))Guard.call("notification-pull-frame"){
                val owner=source.get()
                if(owner!=null&&heads?.visible(owner)==true&&pending(owner)!==pending)bind(this)
                position(this)
            } else hide(this)
            true
        }
    }
    private val main=Handler(Looper.getMainLooper())
    private val entries=java.util.WeakHashMap<View,Entry>()
    private data class BannerDrag(val entry:Entry,val generation:Int,val pointer:Int,val x:Float,val y:Float,
                                  val downTime:Long,var moved:Boolean=false,var consumed:Boolean=false)
    private val bannerDrags=java.util.WeakHashMap<View,BannerDrag>()
    private var observed=0
    private val lifecycle=NotificationSurfaceLifecycle({owner->
        if(Guard.open("notification-surface-bind"))Guard.call("notification-surface-bind"){surface(owner)}
    },{owner->Guard.call("notification-surface-detach"){entries.remove(owner)?.let{dispose(it)}}})
    private var heads:HeadsUpNotificationAdapter?=null
    private var morph:NotificationMorphLaunchBridge?=null
    private var configured=false
    private var context:Context?=null
    private var settings:NotificationSettingsLoader?=null
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor{r->Thread(r,"PopupNotificationSettings").apply{isDaemon=true}}
    private val listenerType=Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    private val addInsets=ViewTreeObserver::class.java.getDeclaredMethod("addOnComputeInternalInsetsListener",listenerType).apply{isAccessible=true}
    private val removeInsets=ViewTreeObserver::class.java.getDeclaredMethod("removeOnComputeInternalInsetsListener",listenerType).apply{isAccessible=true}

    fun install():Boolean {
        if(android.os.Build.VERSION.SDK_INT<34)return false
        val type=X.findClassIfExists("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",loader)?:return false
        heads=HeadsUpNotificationAdapter(type)
        Guard.call("notification-body-install"){installBannerPull()}
        morph=NotificationMorphLaunchBridge(loader).also{Guard.call("notification-morph-install"){it.install()}}
        NotificationLaunchOptions.freeform(android.os.Bundle())
        watchConstructors(type)
        // Current Samsung sets heads-up state on the entry, directly writing row fields;
        // it need not call setEntryAdapter or produce a changed row layout at this point.
        X.findClassIfExists("com.android.systemui.statusbar.notification.collection.NotificationEntry",loader)?.let{entryType->
            XposedBridge.hookAllMethods(entryType,"setHeadsUp",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable()||p.args.firstOrNull()!=true)return
                    Guard.call("notification-headsup-state"){
                        val row=Guard.field(p.thisObject,"row","mRow") as? View?:return@call
                        lifecycle.watch(row)
                        row.post{Guard.call("notification-headsup-shown"){if(row.isAttachedToWindow)surface(row)}}
                    }
                }
            })
        }
        for(name in listOf("onAttachedToWindow","onLayout","setEntryAdapter","setEntry"))
            XposedBridge.hookAllMethods(type,name,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable()||!Guard.open("notification-headsup-bind"))return
                    Guard.call("notification-headsup-bind"){
                        val owner=p.thisObject as View
                        lifecycle.watch(owner)
                        if(owner.isAttachedToWindow)surface(owner)
                    }
                }
            })
        // The override may live in an ancestor; hook exactly once, filter our tracked rows.
        val detach=generateSequence(type){it.superclass}.mapNotNull{
            runCatching{it.getDeclaredMethod("onDetachedFromWindow")}.getOrNull()
        }.first().apply{isAccessible=true}
        XposedBridge.hookMethod(detach,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-headsup-detach"){
                entries.remove(p.thisObject as? View)?.let{dispose(it)}
            }}
        })
        Guard.info("heads-up notification pull hooks ready; original activity PendingIntent and native banner region")
        return true
    }
    private fun watchConstructors(type:Class<*>){
        XposedBridge.hookAllConstructors(type,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable())return
                Guard.call("notification-surface-create"){lifecycle.watch(p.thisObject as View)}
            }
        })
    }
    private fun surface(owner:View){
        if(!owner.isAttachedToWindow)return
        val host=owner.rootView as? FrameLayout
        if(observed<3){
            observed++
            Guard.info("notification surface observed headsUp=true owner=${owner.javaClass.name} root=${owner.rootView.javaClass.name} host=${host!=null}")
        }
        if(host==null||host===owner)return
        attachSettings(host.context)
        val old=entries[owner]
        val entry=if(old!=null&&old.root.get()===host&&old.bar.get()?.parent===host)old else {
            old?.let{dispose(it)}
            create(owner,host).also{entries[owner]=it}
        }
        val current=pending(owner)
        if(current!==entry.pending||old==null)bind(entry)
        entry.showing=true
        attachObserver(entry);position(entry)
    }
    private fun dispose(entry:Entry){
        hide(entry);detachObserver(entry)
        entry.bar.get()?.let{(it.parent as? android.view.ViewGroup)?.removeView(it)}
    }
    private fun snapshotEntries():List<Entry> {
        val out=ArrayList<Entry>()
        val it=entries.values.iterator()
        while(it.hasNext())out.add(it.next())
        return out
    }
    private fun attachSettings(c:Context){
        if(context!=null){if(!configured)fetchSettings();return}
        val app=c.applicationContext?:c;context=app
        app.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(c:Context,i:Intent){
                // Broadcast is an invalidation signal only. Never trust supplied settings.
                configured=false;snapshotEntries().forEach{hide(it)};fetchSettings(true)
            }
        },IntentFilter(ModuleSettings.ACTION_SETTINGS_CHANGED),null,main,Context.RECEIVER_EXPORTED)
        app.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(c:Context,i:Intent){
                snapshotEntries().forEach{bind(it);position(it)}
                if(i.action!=Intent.ACTION_SCREEN_OFF)fetchSettings(true)
            }
        },IntentFilter().apply{addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);
            addAction(Intent.ACTION_USER_PRESENT);addAction(Intent.ACTION_USER_UNLOCKED)},
            null,main,Context.RECEIVER_NOT_EXPORTED)
        settings=NotificationSettingsLoader(main,executor,{ModuleSettings.queryProvider(app)},{fresh->
            val b=android.os.Bundle()
            fresh.forEach{(k,v)->when(v){is Boolean->b.putBoolean(k,v);is Int->b.putInt(k,v);is String->b.putString(k,v)}}
            config.applyBundle(b);configured=true
            Guard.info("notification settings ready headsUp=true enabled=${config.enabled} option=${config.notificationPull}")
            snapshotEntries().forEach{bind(it);position(it)}
            // Do not hold back valid settings while discovering optional clone metadata.
            executor.execute{
                runCatching{io.github.s24u.popupscale.CloneProfileRegistry.refresh(app)}
                main.post{snapshotEntries().forEach{bind(it);position(it)}}
            }
        },{attempt->
            if(attempt<=2)Guard.info("notification settings temporarily unavailable headsUp=true attempt=$attempt; retry or unlock refresh pending")
        })
        fetchSettings()
    }
    private fun fetchSettings(force:Boolean=false){settings?.request(force)}
    private fun allowed(root:FrameLayout,pending:PendingIntent?,owner:View):Boolean {
        if(!configured||pending==null||heads==null)return false
        val identity=heads?.owner(owner) ?: return false
        val c=root.context
        return NotificationPullPolicy.eligible(config.enabled,config.observe,config.notificationPull,
            config.acceptsInteraction(identity.first),pending.isActivity,
            io.github.s24u.popupscale.AppScopePolicy.hasApplicationUid(identity.second),identity.second/100000,
            root.display?.displayId?:-1,c.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            c.getSystemService(PowerManager::class.java).isInteractive,
            true)
    }
    private fun pending(owner:View)=heads?.pending(owner)
    private fun bind(entry:Entry){
        val root=entry.root.get()?:return
        val owner=entry.source.get()?:return
        entry.generation++;cancelTouch(entry)
        entry.pending=pending(owner)
        entry.eligible=allowed(root,entry.pending,owner)
        if(entry.generation<=2)Guard.info("notification surface bound headsUp=true target=${entry.pending!=null} eligible=${entry.eligible} configured=$configured")
        entry.toast=WeakReference(owner)
        entry.launched=false
        if(!entry.eligible)hide(entry)
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun create(owner:View,root:FrameLayout):Entry {
        val entry=Entry(owner,root);val d=root.resources.displayMetrics.density
        fun dp(value:Int)=(value*d+.5f).toInt()
        val bar=FrameLayout(root.context).apply{
            contentDescription="下拉以小窗打开这条通知";visibility=View.GONE
            isClickable=true;isFocusable=false;elevation=dp(2).toFloat()
        }
        val pill=View(root.context).apply{
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable().apply{setColor(Color.rgb(230,235,246));cornerRadius=dp(3).toFloat();setStroke(dp(1),Color.argb(55,0,0,0))}
        }
        bar.addView(pill,FrameLayout.LayoutParams(dp(104),dp(6),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(5)})
        root.addView(bar,FrameLayout.LayoutParams(dp(176),dp(44)))
        entry.bar=WeakReference(bar);entry.pill=WeakReference(pill)
        bar.setOnClickListener{Guard.call("notification-pull-accessibility"){launch(entry)}}
        bar.setOnTouchListener{_,e->Guard.call("notification-pull-touch"){touch(entry,e)}?:true}
        return entry
    }
    private fun installBannerPull(){
        val shade=X.findClass("com.android.systemui.shade.NotificationShadeWindowView",loader)
        val dispatch=shade.getDeclaredMethod("dispatchTouchEvent",MotionEvent::class.java)
        XposedBridge.hookMethod(dispatch,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-body-touch"){
                val root=p.thisObject as FrameLayout
                val event=p.args[0] as MotionEvent
                if(event.actionMasked==MotionEvent.ACTION_DOWN){
                    bannerDrags.remove(root)
                    val entry=entries.values.singleOrNull{candidate->
                        candidate.root.get()===root && candidate.bar.get()?.visibility==View.VISIBLE &&
                            candidate.toastRect.contains((event.rawX-candidate.rootLocation[0]).toInt(),
                                (event.rawY-candidate.rootLocation[1]).toInt())
                    }?:return@call
                    bannerDrags[root]=BannerDrag(entry,entry.generation,event.getPointerId(0),event.rawX,event.rawY,event.downTime)
                    return@call
                }
                val drag=bannerDrags[root]?:return@call
                if(drag.consumed){
                    if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL)bannerDrags.remove(root)
                    p.result=true
                    return@call
                }
                val entry=drag.entry
                if(event.actionMasked!=MotionEvent.ACTION_MOVE || event.pointerCount!=1 ||
                    event.downTime!=drag.downTime || event.getPointerId(0)!=drag.pointer ||
                    entry.generation!=drag.generation || entry.bar.get()?.visibility!=View.VISIBLE ||
                    entry.launched || shadeBlocked()){
                    bannerDrags.remove(root)
                    return@call
                }
                val dx=event.rawX-drag.x
                val dy=event.rawY-drag.y
                val slop=entry.touchSlop
                if(!drag.moved && event.eventTime-event.downTime>=ViewConfiguration.getLongPressTimeout()){
                    bannerDrags.remove(root)
                    return@call
                }
                if(kotlin.math.abs(dx)<slop && kotlin.math.abs(dy)<slop)return@call
                if(!NotificationPullPolicy.shouldStartOnMove(dx,dy,slop)){
                    bannerDrags.remove(root)
                    return@call
                }
                drag.moved=true
                val owner=entry.source.get()?:return@call
                if(!allowed(root,entry.pending,owner)||pending(owner)!==entry.pending||heads?.visible(owner)!=true)return@call
                // Finish the original down sequence through the native dispatcher before opening the app.
                val cancel=MotionEvent.obtain(event).apply{action=MotionEvent.ACTION_CANCEL}
                p.setObjectExtra("body-original",event)
                p.setObjectExtra("body-drag",drag)
                p.args[0]=cancel
                drag.consumed=true
            }}
            override fun afterHookedMethod(p:MethodHookParam){
                val drag=p.getObjectExtra("body-drag") as? BannerDrag?:return
                (p.args[0] as MotionEvent).recycle()
                p.args[0]=p.getObjectExtra("body-original")
                if(!p.hasThrowable()){
                    Guard.call("notification-body-open"){if(drag.entry.generation==drag.generation)launch(drag.entry)}
                    p.result=true
                }
            }
        })
    }
    private fun attachObserver(entry:Entry){
        val root=entry.root.get()?:return
        val vto=root.viewTreeObserver
        if(!root.isAttachedToWindow||entry.observer?.get()===vto)return
        detachObserver(entry)
        val proxy=Proxy.newProxyInstance(listenerType.classLoader,arrayOf(listenerType)){self,method,args->
            when(method.name){
                "hashCode"->System.identityHashCode(self)
                "equals"->self===args?.getOrNull(0)
                "toString"->"PopupNotificationInsets"
                "onComputeInternalInsets"->{
                    Guard.call("notification-pull-insets"){
                        if(entry.bar.get()?.visibility==View.VISIBLE&&!entry.hit.isEmpty){
                            val info=args?.getOrNull(0)?:return@call
                            (Guard.field(info,"touchableRegion") as? Region)?.union(entry.hit)
                            X.callMethod(info,"setTouchableInsets",3)
                        }
                    };null
                }
                else->null
            }
        }
        vto.addOnPreDrawListener(entry.preDraw);addInsets.invoke(vto,proxy)
        entry.insets=proxy;entry.observer=WeakReference(vto)
    }
    private fun detachObserver(entry:Entry){
        entry.observer?.get()?.takeIf{it.isAlive}?.let{vto->
            vto.removeOnPreDrawListener(entry.preDraw);entry.insets?.let{removeInsets.invoke(vto,it)}
        }
        entry.insets=null;entry.observer=null
    }
    private fun position(entry:Entry){
        val root=entry.root.get()?:return;val bar=entry.bar.get()?:return
        val toast=entry.toast.get()
        if(!entry.showing||!entry.eligible||!configured||!config.enabled||config.observe||!config.notificationPull||
            entry.launched||!root.isShown||root.alpha<=0f||toast==null||!toast.isShown||toast.alpha<=0f||
            (shadeBlocked()||heads?.visible(toast)!=true||entry.pending==null)||
            !heads!!.bounds(toast,entry.toastRect)){hide(entry);return}
        // Root coordinates -> physical screen -> shade-local coordinates. No text reads.
        toast.rootView.getLocationOnScreen(entry.rootLocation);root.getLocationOnScreen(entry.hostLocation)
        val d=root.resources.displayMetrics.density
        val width=bar.layoutParams.width;val height=bar.layoutParams.height
        val oldX=entry.hit.left;val oldY=entry.hit.top;val oldWidth=entry.hit.width();val oldHeight=entry.hit.height()
        if(!NotificationHandleGeometry.place(entry.toastRect,entry.rootLocation[0],entry.rootLocation[1],
                entry.hostLocation[0],entry.hostLocation[1],root.width,root.height,width,height,d,entry.hit)){
            hide(entry);return
        }
        val x=entry.hit.left;val y=entry.hit.top
        val changed=oldX!=x||oldY!=y||oldWidth!=width||oldHeight!=height
        if(bar.x!=x.toFloat())bar.x=x.toFloat()
        if(bar.y!=y.toFloat())bar.y=y.toFloat()
        if(bar.visibility!=View.VISIBLE){bar.visibility=View.VISIBLE;Guard.info("notification pull handle shown headsUp=true package=${entry.pending?.creatorPackage}")}
        if(changed)root.requestLayout() // Updates native input region; no frame-by-frame task queries.
    }
    private fun touch(entry:Entry,event:MotionEvent):Boolean {
        if(event.actionMasked!=MotionEvent.ACTION_DOWN && entry.pullCommitted)return true
        val root=entry.root.get()?:return true
        val owner=entry.source.get()?:return true
        val d=root.resources.displayMetrics.density
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                entry.pullCommitted=false
                val pending=pending(owner)
                if(!allowed(root,pending,owner)||pending!==entry.pending||
                    (shadeBlocked()||heads?.visible(owner)!=true)){hide(entry);return true}
                cancelTouch(entry);entry.dragging=true;entry.cancelled=false
                entry.downGeneration=entry.generation;entry.downPending=pending
                entry.pointer=event.getPointerId(0);entry.downX=event.rawX;entry.downY=event.rawY
                entry.velocity=VelocityTracker.obtain();root.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN->entry.cancelled=true
        }
        entry.velocity?.let{tracker->
            val screen=MotionEvent.obtain(event)
            screen.offsetLocation(event.rawX-event.x,event.rawY-event.y);tracker.addMovement(screen);screen.recycle()
        }
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->if(entry.dragging){
                val progress=((event.rawY-entry.downY)/(32*d)).coerceIn(0f,1f)
                entry.pill.get()?.let{pill->
                    pill.translationY=(event.rawY-entry.downY).coerceIn(0f,32*d)*.6f
                    pill.scaleX=1f+.1f*progress
                }
                if(!entry.cancelled && event.pointerCount==1 && event.getPointerId(0)==entry.pointer &&
                    entry.downGeneration==entry.generation && entry.downPending===entry.pending &&
                    NotificationPullPolicy.shouldStartOnMove(event.rawX-entry.downX,event.rawY-entry.downY,entry.touchSlop)){
                    entry.pullCommitted=true
                    cancelTouch(entry)
                    launch(entry)
                    Guard.info("notification pull submitted on move elapsedMs=${event.eventTime-event.downTime}")
                }
            }
            MotionEvent.ACTION_UP->{
                entry.velocity?.computeCurrentVelocity(1000)
                val vy=entry.velocity?.getYVelocity(entry.pointer)?:0f
                val go=entry.dragging&&NotificationPullPolicy.release(event.rawX-entry.downX,event.rawY-entry.downY,
                    vy,d,entry.downGeneration,entry.generation,entry.cancelled)
                val same=entry.downPending===entry.pending
                cancelTouch(entry,!go)
                if(go&&same)launch(entry)
            }
            MotionEvent.ACTION_CANCEL->cancelTouch(entry,true)
        }
        return true
    }
    private fun cancelTouch(entry:Entry,animate:Boolean=false){
        entry.dragging=false;entry.velocity?.recycle();entry.velocity=null;entry.downPending=null
        entry.pill.get()?.let{
            it.animate().cancel()
            if(animate&&entry.bar.get()?.visibility==View.VISIBLE)it.animate().translationY(0f).scaleX(1f)
                .setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            else{it.translationY=0f;it.scaleX=1f}
        }
    }
    private fun hide(entry:Entry){
        entry.bar.get()?.let{if(it.visibility!=View.GONE)it.visibility=View.GONE}
        entry.hit.setEmpty()
        if(entry.dragging||entry.velocity!=null||entry.pill.get()?.translationY?.let{it!=0f}==true)cancelTouch(entry)
    }
    private fun launch(entry:Entry){
        val root=entry.root.get()?:return;val pending=entry.pending?:return
        val owner=entry.source.get()?:return
        if(entry.launched||!entry.showing||!allowed(root,pending,owner)||pending(owner)!==pending||
            (shadeBlocked()||heads?.visible(owner)!=true))return
        entry.launched=true;entry.bar.get()?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val sent=Guard.call("notification-pull-launch"){
            heads?.launch(owner,root,pending,morph,{source,pi->
                val host=source.rootView as? FrameLayout
                host!=null&&allowed(host,pi,source)&&pending(source)===pi
            },{
                entry.launched=false;position(entry)
            })==true
        }==true
        hide(entry)
        Guard.info("notification popup request headsUp=true owner=${heads?.owner(owner)?.first} creator=${pending.creatorPackage} " +
            "user=${heads?.owner(owner)?.second?.div(100000)} dispatched=$sent; original PendingIntent retained")
    }
}
