package io.github.s24u.popupscale.gestures

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Task-leash opacity composes with native animation alpha, instead of replacing it. */
class TaskOpacityController(private val context:Context,private val tasks:TaskBackend,
                            private val config:FeatureConfig,private val loader:ClassLoader) {
    private class Entry(val id:Int,val user:Int,val token:Any,transparency:Int){
        @Volatile var requested=transparency
        @Volatile var nativeAlpha=1f
        @Volatile var appliedAlpha=1f
        @Volatile var lastNativeWrite=0L
        var repairPending=false
        var repairs=0
        var layer=-1
        var surface:WeakReference<Any>?=null
    }
    private val entries=mutableMapOf<Int,Entry>() // main thread only
    private val layers=ConcurrentHashMap<Int,Entry>()
    private val internalWrite=ThreadLocal.withInitial{false}
    private val menuWidths=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any,Int>())
    private val policyRoots=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any,WeakReference<View>>())
    private val policyMenus=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any,WeakReference<Any>>())
    private val main=Handler(Looper.getMainLooper())
    private val wm=context.getSystemService(WindowManager::class.java)
    private var layerMethod:java.lang.reflect.Method?=null
    private var ready=false
    private var panel:View?=null
    private var panelTask=-1
    private var shade=false
    private var lastOpacityLog=0L
    private val tag="PopupScaleOpacityButton"

    private fun token(task:TaskRecord)=X.callMethod(task.token,"asBinder")?:task.token
    private fun admitted(task:TaskRecord):Boolean {
        config.reload()
        return TaskOpacityPolicy.eligible(config.enabled,config.observe,config.acceptsInteraction(task.packageName),
            task.userId,0,1,task.mode,true,false)
    }
    private fun eligible(task:TaskRecord)=admitted(task)&&task.visible&&!tasks.locked()
    private fun record(id:Int)=tasks.info(id)?.let{tasks.record(it)}
    private fun layer(surface:Any)=(layerMethod?.invoke(surface) as? Int)?:-1
    fun value(id:Int)=entries[id]?.requested?:0
    fun applied(id:Int)=entries[id]?.appliedAlpha
    fun snapshot(id:Int)=entries[id]?.let{e->org.json.JSONObject()
        .put("transparency",e.requested).put("layer",e.layer).put("nativeAlpha",e.nativeAlpha)
        .put("submittedAlpha",e.appliedAlpha).put("idleReapplications",e.repairs)
        .put("idlePending",e.repairPending).put("suspended",e.layer<0)}

    fun install():Boolean {
        if(android.os.Build.VERSION.SDK_INT<29)return false
        layerMethod=X.findMethodExactIfExists(SurfaceControl::class.java,"getLayerId")?:return false
        X.findAndHookMethod(SurfaceControl.Transaction::class.java,"setAlpha",SurfaceControl::class.java,
            java.lang.Float.TYPE,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(layers.isEmpty())return
                    Guard.call("opacity-animation-alpha"){
                        val entry=layers[layer(p.args[0])]?:return@call
                        val native=p.args[1] as Float
                        if(!native.isFinite()||native !in 0f..1f)return@call
                        val composed=TaskOpacityPolicy.compose(native,entry.requested)
                        if(internalWrite.get()!=true){
                            entry.nativeAlpha=native
                            entry.lastNativeWrite=android.os.SystemClock.uptimeMillis()
                        }
                        entry.appliedAlpha=composed
                        p.args[1]=composed
                    }
                }
            })
        ready=true
        tasks.opacityPanel={id->show(id)}
        Guard.call("opacity-native-menu-install"){installNativeMenu()}
        Guard.info("task opacity ready: task-leash alpha composition; long-press handle fallback")
        return true
    }
    private fun bind(e:Entry,surface:Any):Boolean {
        val id=layer(surface);if(id<0)return false
        if(e.layer!=id){if(e.layer>=0)layers.remove(e.layer,e);e.layer=id;e.nativeAlpha=1f}
        e.surface=WeakReference(surface);layers[id]=e
        return true
    }
    private fun applyAlpha(e:Entry,surface:Any){
        val previous=internalWrite.get();internalWrite.set(true)
        try{tasks.alpha(surface,e.nativeAlpha)}finally{internalWrite.set(previous)}
    }
    fun set(id:Int,transparency:Int):Boolean {
        if(!ready||shade||transparency !in 0..TaskOpacityPolicy.MAX_TRANSPARENCY)return false
        val task=record(id)?:return false
        if(!eligible(task))return false
        val owner=token(task);val old=entries[id]
        if(old!=null&&(old.user!=task.userId||old.token!=owner))forget(id,false)
        if(transparency==0){forget(id,true,false);return true}
        val surface=tasks.surface(id)?:return false
        if(layer(surface)<0)return false
        val e=entries.getOrPut(id){Entry(id,task.userId,owner,transparency)}
        if(!bind(e,surface))return false
        e.requested=transparency
        applyAlpha(e,surface)
        val now=android.os.SystemClock.uptimeMillis()
        if(now-lastOpacityLog>=750){lastOpacityLog=now;Guard.info("task opacity task=$id user=${task.userId} transparency=$transparency alpha=${e.appliedAlpha}")}
        return true
    }
    fun forget(id:Int,restore:Boolean=false,dismissPanel:Boolean=true){
        if(dismissPanel&&panelTask==id)closePanel()
        val e=entries.remove(id)?:return
        if(e.layer>=0)layers.remove(e.layer,e)
        if(restore)e.surface?.get()?.let{s->Guard.call("opacity-restore"){
            if(X.callMethod(s,"isValid")==true)tasks.alpha(s,e.nativeAlpha)
        }}
    }
    fun refresh(){
        for(e in entries.values.toList())Guard.call("opacity-task-refresh"){
            val task=record(e.id)
            if(task==null||task.userId!=e.user||token(task)!=e.token){forget(e.id,false);return@call}
            when(TaskOpacityPolicy.lifetime(admitted(task),task.visible,tasks.locked())){
                TaskOpacityPolicy.Lifetime.DROP->{forget(e.id,true);return@call}
                TaskOpacityPolicy.Lifetime.SUSPEND->{
                    // Keep requested transparency through transient hidden/focus/lock states,
                    // but detach the alpha hook and restore native state while not displayed.
                    if(e.layer>=0){
                        layers.remove(e.layer,e);e.layer=-1
                        e.surface?.get()?.let{s->if(X.callMethod(s,"isValid")==true)tasks.alpha(s,e.nativeAlpha)}
                        e.surface=null
                        Guard.info("task opacity suspended task=${e.id} transparency=${e.requested}")
                    }
                    return@call
                }
                TaskOpacityPolicy.Lifetime.ACTIVE->{}
            }
            val surface=tasks.surface(e.id)?:return@call
            if(layer(surface)!=e.layer&&bind(e,surface))applyAlpha(e,surface)
            // A native/parcelled finish transaction can reset alpha without a Java setAlpha
            // call in this process. Reapply even when the leash identity stayed unchanged.
            // Queue only one check per task, after native transitions, and leave fades alone.
            if(!e.repairPending&&TaskOpacityPolicy.canReconcile(e.nativeAlpha,e.lastNativeWrite,android.os.SystemClock.uptimeMillis())){
                e.repairPending=true
                tasks.whenIdle{idle->Guard.call("opacity-idle-reconcile") reconcile@{
                    e.repairPending=false
                    if(!idle||entries[e.id]!==e||!TaskOpacityPolicy.canReconcile(e.nativeAlpha,e.lastNativeWrite,android.os.SystemClock.uptimeMillis()))return@reconcile
                    val current=record(e.id)?:return@reconcile
                    if(current.userId!=e.user||token(current)!=e.token||!eligible(current))return@reconcile
                    val latest=tasks.surface(e.id)?:return@reconcile
                    if(bind(e,latest)){applyAlpha(e,latest);e.repairs++}
                }}
            }
        }
        if(panelTask>=0){val task=record(panelTask);if(task==null||!eligible(task)||shade)closePanel()}
    }
    fun setShadeObscured(value:Boolean){shade=value;if(value)closePanel()}
    fun closePanel(){val view=panel;panel=null;panelTask=-1;if(view!=null)runCatching{wm.removeView(view)}}
    fun show(id:Int){
        if(!ready||shade||tasks.locked())return
        val task=record(id)?:return
        if(!eligible(task))return
        closePanel()
        val metrics=GestureDisplay.metrics(context);val density=metrics.density
        fun dp(v:Int)=(v*density).toInt()
        val night=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val root=FrameLayout(context).apply{
            setPadding(dp(14),dp(4),dp(14),dp(4));elevation=dp(8).toFloat()
            background=GradientDrawable().apply{setColor(if(night)Color.rgb(40,40,45) else Color.rgb(248,248,253));cornerRadius=dp(28).toFloat()}
            contentDescription="小窗透明度面板"
        }
        val slider=SeekBar(context).apply{
            min=TaskOpacityPolicy.MIN_OPACITY;max=TaskOpacityPolicy.MAX_OPACITY
            val opacity=TaskOpacityPolicy.opacityPercent(value(id))
            progress=opacity
            layoutDirection=View.LAYOUT_DIRECTION_LTR
            contentDescription="调整小窗不透明度，最右侧恢复完全不透明，点空白处收起"
            if(android.os.Build.VERSION.SDK_INT>=30)stateDescription="不透明度 $opacity%"
        }
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(s:SeekBar){}
            override fun onStopTrackingTouch(s:SeekBar){}
            override fun onProgressChanged(s:SeekBar,progress:Int,fromUser:Boolean){
                if(fromUser)Guard.call("opacity-slider"){
                    val opacity=progress.coerceIn(TaskOpacityPolicy.MIN_OPACITY,TaskOpacityPolicy.MAX_OPACITY)
                    if(set(id,TaskOpacityPolicy.transparencyPercent(opacity))&&android.os.Build.VERSION.SDK_INT>=30)
                        s.stateDescription="不透明度 $opacity%"
                }
            }
        })
        root.addView(slider,FrameLayout.LayoutParams(-1,dp(48)))
        val width=minOf(dp(240),metrics.widthPixels-dp(24));val height=dp(56)
        val p=WindowManager.LayoutParams(width,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
        p.title="PopupScaleOpacity#$id";p.gravity=Gravity.TOP or Gravity.LEFT
        p.x=(task.bounds.centerX()-width/2).coerceIn(dp(12),maxOf(dp(12),metrics.widthPixels-width-dp(12)))
        p.y=(if(task.bounds.top>height+dp(24))task.bounds.top-height-dp(8) else task.bounds.top+dp(24))
            .coerceIn(dp(12),maxOf(dp(12),metrics.heightPixels-height-dp(24)))
        root.setOnTouchListener{_,event->if(event.actionMasked==MotionEvent.ACTION_OUTSIDE){closePanel();true}else false}
        wm.addView(root,p);panel=root;panelTask=id
        Guard.info("opacity panel opened task=$id transparency=${value(id)}")
    }
    private fun menuTask(policy:Any):TaskRecord? {
        if(!ready||shade)return null
        val info=Guard.field(policy,"mTaskInfo","taskInfo")?:return null
        val task=tasks.record(info)?:return null
        return task.takeIf{eligible(it)}
    }
    private fun findButtonRow(root:View?):LinearLayout? {
        if(root !is ViewGroup)return null
        var rowCandidate:LinearLayout?=null
        var fallback:LinearLayout?=null
        val queue=ArrayDeque<Pair<ViewGroup,Boolean>>()
        queue.add(root to (root is HorizontalScrollView))
        while(queue.isNotEmpty()){
            val(vg,inScroll)=queue.removeFirst()
            if(vg is LinearLayout){
                if(fallback==null)fallback=vg
                val hasScrollChild=(0 until vg.childCount).any{vg.getChildAt(it) is HorizontalScrollView}
                if(vg.orientation==LinearLayout.HORIZONTAL&&vg.childCount>=2&&!hasScrollChild){
                    if(inScroll)return vg
                    if(rowCandidate==null)rowCandidate=vg
                }
            }
            val nextInScroll=inScroll||vg is HorizontalScrollView
            for(i in 0 until vg.childCount){
                val child=vg.getChildAt(i)
                if(child is ViewGroup)queue.add(child to nextInScroll)
            }
        }
        return rowCandidate ?: fallback ?: (root as? LinearLayout)
    }
    private fun nightMode(policy:Any,context:Context):Boolean {
        (Guard.field(policy,"mIsNightMode") as? Boolean)?.let{return it}
        Guard.call("opacity-night-check"){
            if(X.findMethodExactIfExists(policy.javaClass,"isNightMode")!=null)
                return@call X.callMethod(policy,"isNightMode") as? Boolean
            null
        }?.let{return it}
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    }
    private fun invokeCloseToken(token:Any?):Boolean {
        if(token==null)return false
        if(token is Runnable){token.run();return true}
        if(X.findMethodExactIfExists(token.javaClass,"invoke")!=null){
            X.callMethod(token,"invoke")
            return true
        }
        return false
    }
    private fun dismissMenu(taskId:Int,policy:Any,menuOwner:Any?,closeCallback:Any?) {
        if(invokeCloseToken(closeCallback))return
        val menu=menuOwner ?: policyMenus[policy]?.get()
        val decor=tasks.decorationProvider?.invoke(taskId)
        val parentDecor=menu?.let{Guard.field(it,"parentDecor","mParentDecor")} ?: decor
        val captionController=decor?.let{Guard.field(it,"captionController")}
        val targetOwner=parentDecor ?: captionController
        if(targetOwner!=null&&X.findMethodExactIfExists(targetOwner.javaClass,"closeHandleMenu")!=null){
            X.callMethod(targetOwner,"closeHandleMenu")
            return
        }
        val storedClose=parentDecor?.let{Guard.field(it,"mCloseHandleMenuFunction")}
            ?: menu?.let{Guard.field(it,"onOutsideTouchListener","mCloseMenu")}
        if(invokeCloseToken(storedClose))return
        val resolvedMenu=menu ?: parentDecor?.let{Guard.field(it,"mHandleMenu","handleMenu")}
            ?: captionController?.let{Guard.field(it,"handleMenu","mHandleMenu")}
        if(resolvedMenu!=null){
            if(X.findMethodExactIfExists(resolvedMenu.javaClass,"close")!=null)X.callMethod(resolvedMenu,"close")
            else if(X.findMethodExactIfExists(resolvedMenu.javaClass,"closeMenuPopupImmediately")!=null)X.callMethod(resolvedMenu,"closeMenuPopupImmediately")
            val holder=targetOwner?.let{Guard.field(it,"mWindowDecorViewHolder","viewHolder")}
            if(holder!=null&&X.findMethodExactIfExists(holder.javaClass,"onHandleMenuClosed")!=null)
                X.callMethod(holder,"onHandleMenuClosed")
            return
        }
        policy.javaClass.methods.firstOrNull{it.name=="animateCloseMenu"&&it.parameterCount==1}
            ?.invoke(policy,null)
    }
    private fun bindMenuButtonClick(button:View,taskId:Int,policy:Any,menuOwner:Any?,closeCallback:Any?) {
        button.isEnabled=true
        button.setOnClickListener{
            Guard.call("opacity-native-menu-open"){
                dismissMenu(taskId,policy,menuOwner,closeCallback)
                main.post{Guard.call("opacity-panel-open"){show(taskId)}}
            }
        }
    }
    private fun findFieldByPredicate(owner:Any,predicate:(Any)->Boolean):Any? {
        var c:Class<*>?=owner.javaClass
        while(c!=null&&c!=Any::class.java){
            for(f in c.declaredFields){
                f.isAccessible=true
                val v=runCatching{f.get(owner)}.getOrNull() ?: continue
                if(predicate(v))return v
            }
            c=c.superclass
        }
        return null
    }
    private fun installNativeMenu(){
        val policy=X.findClassIfExists("com.android.wm.shell.windowdecor.policy.FreeformPopupButtonPolicy",loader)
        val scrollPolicy=X.findClassIfExists("com.android.wm.shell.windowdecor.policy.FreeformScrollPopupButtonPolicy",loader)
        val captionPolicy=X.findClassIfExists("com.android.wm.shell.windowdecor.policy.CaptionPopupButtonPolicy",loader)
        val base=X.findClassIfExists("com.android.wm.shell.windowdecor.policy.PopupButtonPolicy",loader)
        val menu=X.findClassIfExists("com.android.wm.shell.windowdecor.HandleMenu",loader)
        val callbackType=X.findClassIfExists("kotlin.jvm.functions.Function0",loader)
        if((policy==null&&scrollPolicy==null&&captionPolicy==null)||base==null){
            Guard.info("opacity native freeform menu skipped: policy=${policy!=null} scroll=${scrollPolicy!=null} base=${base!=null} menu=${menu!=null}")
            return
        }
        val hasRootField=X.findFieldIfExists(base,"mRootView")!=null||X.findFieldIfExists(base,"rootView")!=null
        if(!hasRootField){
            Guard.info("opacity native freeform menu skipped: rootView=false")
            return
        }
        val showMethods=menu?.declaredMethods?.filter{
            !java.lang.reflect.Modifier.isStatic(it.modifiers)&&
                !java.lang.reflect.Modifier.isAbstract(it.modifiers)&&
                TaskOpacityPolicy.isMenuShowMethod(it.name)
        }.orEmpty()
        val targets=listOfNotNull(base,policy,scrollPolicy,captionPolicy).distinct()
        val widthHooks=mutableSetOf<java.lang.reflect.Method>()
        val rootHooks=mutableSetOf<java.lang.reflect.Method>()
        val menuHooks=mutableSetOf<java.lang.reflect.Method>()
        fun isFreeformPolicy(obj:Any?)=obj!=null&&TaskOpacityPolicy.isMenuPolicyClass(obj.javaClass.name)
        for(targetClass in targets){
            for(m in targetClass.declaredMethods){
                if(java.lang.reflect.Modifier.isStatic(m.modifiers)||java.lang.reflect.Modifier.isAbstract(m.modifiers))continue
                if(m.name=="calculatePopupWidth"&&m.parameterCount==0&&widthHooks.add(m)){
                    XposedBridge.hookMethod(m,object:XC_MethodHook(){
                        override fun afterHookedMethod(p:MethodHookParam){if(p.hasThrowable())return
                            Guard.call("opacity-menu-width"){
                                if(!isFreeformPolicy(p.thisObject))return@call
                                menuWidths.remove(p.thisObject)
                                if(menuTask(p.thisObject)==null)return@call
                                val dm=GestureDisplay.metrics(context);val extra=(44*dm.density).toInt();val width=p.result as? Int ?: return@call
                                if(width+extra<=dm.widthPixels-(24*dm.density).toInt()){
                                    p.result=width+extra;menuWidths[p.thisObject]=extra
                                }
                            }
                        }
                    })
                }else if(m.name=="setupRootView"&&
                    m.parameterTypes.any{View::class.java.isAssignableFrom(it)}&&rootHooks.add(m)){
                    XposedBridge.hookMethod(m,object:XC_MethodHook(){
                        override fun afterHookedMethod(p:MethodHookParam){if(p.hasThrowable())return
                            Guard.call("opacity-menu-entry"){
                                if(!isFreeformPolicy(p.thisObject))return@call
                                val rootView=p.args.firstOrNull{it is View} as? View
                                    ?: Guard.field(p.thisObject,"mRootView","rootView") as? View ?: return@call
                                policyRoots[p.thisObject]=WeakReference(rootView)
                                val row=findButtonRow(rootView)?:return@call
                                val task=menuTask(p.thisObject)
                                if(task==null){row.findViewWithTag<View>(tag)?.visibility=View.GONE;return@call}
                                val existing=row.findViewWithTag<View>(tag)
                                if(existing!=null){
                                    existing.visibility=View.VISIBLE
                                    bindMenuButtonClick(existing,task.id,p.thisObject,null,null)
                                    return@call
                                }
                                val size=(row.resources.displayMetrics.density*44).toInt()
                                val width=menuWidths[p.thisObject]?:size
                                val night=nightMode(p.thisObject,row.context)
                                val button=ImageButton(row.context).apply{
                                    this.tag=this@TaskOpacityController.tag;contentDescription="小窗透明度";tooltipText="透明度"
                                    setImageDrawable(OpacityGlyph(if(night)Color.WHITE else Color.rgb(35,35,40)))
                                    val pad=(row.resources.displayMetrics.density*11).toInt();setPadding(pad,pad,pad,pad)
                                    val a=row.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                                    try{background=a.getDrawable(0)}finally{a.recycle()}
                                }
                                row.addView(button,maxOf(0,row.childCount-1),LinearLayout.LayoutParams(width,size))
                                bindMenuButtonClick(button,task.id,p.thisObject,null,null)
                                Guard.info("opacity native menu entry attached task=${task.id} policy=${p.thisObject.javaClass.simpleName}")
                            }
                        }
                    })
                }
            }
        }
        if(rootHooks.isEmpty()){
            Guard.info("opacity native freeform menu skipped: no setupRootView hooks")
            return
        }
        for(showMenu in showMethods){
            if(!menuHooks.add(showMenu))continue
            XposedBridge.hookMethod(showMenu,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){if(p.hasThrowable())return
                    Guard.call("opacity-menu-close-link"){
                        val owner=p.thisObject ?: return@call
                        val menuPolicy=Guard.field(owner,"buttonPolicy","mButtonPolicy")
                            ?: findFieldByPredicate(owner){isFreeformPolicy(it)} ?: return@call
                        policyMenus[menuPolicy]=WeakReference(owner)
                        val task=menuTask(menuPolicy)?:return@call
                        val root=(Guard.field(menuPolicy,"mRootView","rootView") as? View)
                            ?: policyRoots[menuPolicy]?.get() ?: return@call
                        val button=root.findViewWithTag<View>(tag)?:return@call
                        val closeMenu=p.args.firstOrNull{
                            it!=null&&((callbackType!=null&&callbackType.isInstance(it))||it is Runnable)
                        }
                        bindMenuButtonClick(button,task.id,menuPolicy,owner,closeMenu)
                    }
                }
            })
        }
        val menuMethodNames=menu?.declaredMethods?.map{it.name}?.distinct()?.sorted()?.take(24).orEmpty()
        Guard.info("opacity native freeform menu hook installed widths=${widthHooks.size} roots=${rootHooks.size} shows=${menuHooks.size}")
    }
    private class OpacityGlyph(private val ink:Int):Drawable(){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas:Canvas){
            val r=RectF(bounds).apply{inset(width()*0.1f,height()*0.1f)}
            paint.color=ink;paint.style=Paint.Style.STROKE;paint.strokeWidth=r.width()*0.08f;canvas.drawOval(r,paint)
            paint.style=Paint.Style.FILL;canvas.drawArc(r,90f,180f,true,paint)
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
