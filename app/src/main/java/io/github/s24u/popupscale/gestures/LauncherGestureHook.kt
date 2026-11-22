package io.github.s24u.popupscale.gestures

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.util.WeakHashMap

/** Only observes the ROM's existing Recents consumer; normal gestures stay with the ROM. */
class LauncherGestureHook(private val loader:ClassLoader,private val config:FeatureConfig,private val consumer:String){
    private val states=WeakHashMap<Any,State>()
    private data class PendingCards(val gate:TaskListDeferral,val until:Long)
    private val pendingCards=WeakHashMap<Any,PendingCards>()
    private val main=Handler(Looper.getMainLooper())
    private val home=LauncherHomeBridge(loader,config)
    private val frames=LauncherFrameHandoff()
    private var pendingHint:HangTargetOverlay?=null
    private val settingsRegistered=java.util.concurrent.atomic.AtomicBoolean(false)
    private val settingsExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val settingsSyncing=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastProviderAttemptMs=-5000L
    private fun syncSettingsFromProvider(context:Context,force:Boolean=false){
        val now=SystemClock.uptimeMillis()
        if(!force&&(!config.needsProviderSync()||now-lastProviderAttemptMs<3000L))return
        if(!settingsSyncing.compareAndSet(false,true))return
        lastProviderAttemptMs=now
        settingsExecutor.execute{
            try{
                Guard.call("launcher-settings-provider-sync"){
                    val fresh=io.github.s24u.popupscale.ModuleSettings.queryProvider(context)
                    if(config.applyProviderMap(fresh)){
                        Guard.info("launcher settings synced from provider enabled=${config.enabled} hang=${config.hang}")
                    }
                }
            }finally{settingsSyncing.set(false)}
        }
    }
    private fun attachSettings(context:Context){
        val app=context.applicationContext?:context
        if(settingsRegistered.compareAndSet(false,true)){
            Guard.call("launcher-settings-register"){
                app.registerReceiver(object:android.content.BroadcastReceiver(){
                    override fun onReceive(c:Context,intent:Intent){
                        Guard.call("launcher-settings-changed"){
                            intent.getBundleExtra("settings")?.let{config.applyBundle(it)}
                            config.invalidateProvider()
                            syncSettingsFromProvider(app,force=true)
                        }
                    }
                },android.content.IntentFilter(io.github.s24u.popupscale.ModuleSettings.ACTION_SETTINGS_CHANGED),
                    null,main,Context.RECEIVER_EXPORTED)
                app.registerReceiver(object:android.content.BroadcastReceiver(){
                    override fun onReceive(c:Context,intent:Intent){
                        Guard.call("launcher-settings-unlocked"){
                            config.invalidateProvider()
                            syncSettingsFromProvider(app,force=true)
                        }
                    }
                },android.content.IntentFilter(Intent.ACTION_USER_UNLOCKED),null,main,Context.RECEIVER_NOT_EXPORTED)
            }
            syncSettingsFromProvider(app,force=true)
        }else if(config.needsProviderSync()){
            syncSettingsFromProvider(app)
        }
    }
    private data class State(val x:Float,val y:Float,val taskId:Int,val width:Int,val height:Int,var armed:Boolean=false,
        var armedAtUptimeMs:Long=0L,var hint:HangTargetOverlay?=null,val cards:TaskListDeferral=TaskListDeferral(),
        var lastMotion:Long=0,var velocity:android.view.VelocityTracker?=null)
    fun install(){
        Guard.call("launcher-home-install"){home.install()}
        Guard.call("launcher-card-deferral-install"){
            X.findAndHookMethod(consumer,loader,"appearTaskList",Boolean::class.javaPrimitiveType,
                object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){Guard.call("launcher-card-deferral"){
                        config.reload()
                        if(!config.enabled||config.observe||!config.hang)return@call
                        val pending=pendingCards[p.thisObject]
                        if(pending!=null&&SystemClock.uptimeMillis()>=pending.until)pendingCards.remove(p.thisObject)
                        val active=states[p.thisObject]?.takeIf{SystemClock.uptimeMillis()-it.lastMotion<1500}
                        val gate=active?.cards ?: pendingCards[p.thisObject]?.gate
                        val first=gate?.hasDeferred==false
                        if(gate?.intercept(p.args[0] as Boolean)==true){
                            if(first)Guard.info("launcher task-list deferred for hang")
                            p.result=null
                        }
                    }}
                })
        }
        X.findAndHookMethod(consumer,loader,"onMotionEvent",MotionEvent::class.java,
            object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(!Guard.open("launcher-motion"))return
                    Guard.call("launcher-motion"){
                        config.reload()
                        val owner=p.thisObject
                        val context=X.getObjectField(owner,"context") as Context
                        attachSettings(context)
                        val event=p.args[0] as MotionEvent
                        if(!config.enabled||!config.hang||!SystemUiReadiness.ready()){
                            clear(owner,context);return@call
                        }
                        when(event.actionMasked){
                            MotionEvent.ACTION_DOWN->{
                                home.cancelPending()
                                frames.cancelPending()
                                pendingHint?.dismiss(true);pendingHint=null
                                pendingCards.clear()
                                clear(owner,context)
                                if( consumerDisplayId(owner,context)!=0)return@call
                                val info=runningTopTask(owner) ?: return@call
                                io.github.s24u.popupscale.CloneProfileRegistry.refresh(context)
                                val component=X.getObjectField(info,"topActivity") as? android.content.ComponentName
                                val c=X.getObjectField(info,"configuration")
                                val w=X.getObjectField(c,"windowConfiguration")
                                val taskId=X.getIntField(info,"taskId")
                                val rejection=when{
                                    !config.acceptsInteraction(component?.packageName)->"app-not-selected"
                                    X.callMethod(w,"getActivityType")!=1->"non-standard-task"
                                    X.callMethod(w,"getWindowingMode")!=1->"task-not-fullscreen"
                                    !io.github.s24u.popupscale.CloneUserPolicy.visibleToOwner(X.getIntField(info,"userId"),android.os.Process.myUid()/100000)->"unsupported-user"
                                    else->null
                                }
                                if(rejection!=null)return@call
                                val m=GestureDisplay.metrics(context)
                                val vt=android.view.VelocityTracker.obtain().apply{addMovement(event)}
                                states[owner]=State(event.rawX,event.rawY,X.getIntField(info,"taskId"),m.widthPixels,m.heightPixels,velocity=vt)
                                if(config.observe)Guard.info("launcher candidate task=${X.getIntField(info,"taskId")} observe=true")
                            }
                            MotionEvent.ACTION_MOVE->{
                                val state=states[owner] ?: return@call
                                state.lastMotion=SystemClock.uptimeMillis()
                                state.velocity?.addMovement(event)
                                if(event.pointerCount!=1){clear(owner,context);return@call}
                                val metrics=GestureDisplay.metrics(context)
                                if(metrics.widthPixels!=state.width||metrics.heightPixels!=state.height){
                                    state.cards.blocking=false;replayCards(owner,state.cards);clear(owner,context);return@call
                                }
                                val target=HangTargetPolicy.evaluate(state.y,event.rawX,event.rawY,
                                    metrics.widthPixels,metrics.heightPixels,metrics.density,
                                    X.getBooleanField(owner,"isStartTaskMoveAnimation")&&home.moving(state.taskId),state.armed,config.live,state.x)
                                if(target.armed!=state.armed){
                                    state.armed=target.armed
                                    state.armedAtUptimeMs=if(target.armed)event.eventTime else 0L
                                    Guard.info("launcher early-corner armed=${target.armed} task=${state.taskId} y=${event.rawY.toInt()} " +
                                        "travel=${((state.y-event.rawY)/metrics.heightPixels*100).toInt()}% overview=${X.getBooleanField(owner,"appearTaskList")} observe=${config.observe}")
                                }
                                state.cards.blocking=target.armed&&!config.observe
                                if(!state.cards.blocking)replayCards(owner,state.cards)
                                if(!config.observe){
                                    if(target.visible)showHint(context,state,target) else removeHint(state)
                                }
                            }
                            MotionEvent.ACTION_UP->{
                                val state=states.remove(owner) ?: return@call
                                state.velocity?.addMovement(event)
                                state.velocity?.computeCurrentVelocity(1000)
                                val vx=state.velocity?.xVelocity ?: 0f
                                val vy=state.velocity?.yVelocity ?: 0f
                                state.velocity?.recycle();state.velocity=null
                                val m=GestureDisplay.metrics(context)
                                if(config.observe)Guard.info("launcher release task=${state.taskId} armed=${state.armed} " +
                                    "y=${event.rawY.toInt()} height=${m.heightPixels}")
                                val nativeMoving=X.getBooleanField(owner,"isStartTaskMoveAnimation")&&home.moving(state.taskId)
                                val evaluated=HangTargetPolicy.evaluate(state.y,event.rawX,event.rawY,
                                    m.widthPixels,m.heightPixels,m.density,
                                    nativeMoving,state.armed,config.live,state.x)
                                val dwellMs=if(state.armedAtUptimeMs>0L)(event.eventTime-state.armedAtUptimeMs).coerceAtLeast(0L) else 0L
                                val upwardVyDp=-vy/m.density
                                val speedDp=kotlin.math.hypot(vx,vy)/m.density
                                val allowRelease=evaluated.armed&&HangTargetPolicy.allowRelease(dwellMs,upwardVyDp,speedDp)
                                if(evaluated.armed&&!allowRelease){
                                    Guard.info("launcher fast flick ignored for hang task=${state.taskId} dwellMs=$dwellMs vyDp=${upwardVyDp.toInt()} speedDp=${speedDp.toInt()}")
                                }
                                if(event.pointerCount!=1||m.widthPixels!=state.width||m.heightPixels!=state.height||!allowRelease){
                                    state.cards.blocking=false;replayCards(owner,state.cards);removeHint(state);return@call
                                }
                                Guard.info("launcher corner-zone release; task=${state.taskId} observe=${config.observe}")
                                if(config.observe){removeHint(state);return@call}
                                val rect=if(config.live)home.currentRect(state.taskId)?.takeIf{
                                    it.width()>32&&it.height()>32&&it.intersect(0,0,m.widthPixels,m.heightPixels)
                                } else null
                                if(config.live&&rect==null)Guard.info("transition frame skipped: native gesture bounds unavailable task=${state.taskId}")
                                if(config.live&&rect==null&&!home.chooseHome()){
                                    state.cards.blocking=false;replayCards(owner,state.cards)
                                    removeHint(state);Guard.info("live hang skipped: native Recents manager unavailable");return@call
                                }
                                state.cards.blocking=true
                                pendingCards[owner]=PendingCards(state.cards,SystemClock.uptimeMillis()+4000)
                                // Keep the landing guide until the moving frame is committed.
                                pendingHint=state.hint;state.hint=null
                                p.setObjectExtra("popupscale.hint",pendingHint)
                                val cancel=MotionEvent.obtain(event).apply{action=MotionEvent.ACTION_CANCEL}
                                p.args[0]=cancel
                                p.setObjectExtra("popupscale.cancel",cancel)
                                p.setObjectExtra("popupscale.task",state.taskId)
                                p.setObjectExtra("popupscale.live",config.live)
                                p.setObjectExtra("popupscale.homeTicket",home.ticket())
                                if(config.live&&rect!=null){
                                    p.setObjectExtra("popupscale.frameRect",rect)
                                    // Hold only this confirmed UP until SystemUI has displayed its bridging frame.
                                    p.result=null
                                }
                            }
                            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{
                                if(config.observe&&states.containsKey(owner))Guard.info("launcher cancelled action=${event.actionMasked}")
                                clear(owner,context)
                            }
                        }
                    }
                }
                override fun afterHookedMethod(p:MethodHookParam){
                    val cancel=p.getObjectExtra("popupscale.cancel") as? MotionEvent ?: return
                    val hint=p.getObjectExtra("popupscale.hint") as? HangTargetOverlay
                    if(p.hasThrowable()){pendingCards.remove(p.thisObject);hint?.dismiss(true);cancel.recycle();return}
                    val id=p.getObjectExtra("popupscale.task") as Int
                    val context=X.getObjectField(p.thisObject,"context") as Context
                    val live=p.getObjectExtra("popupscale.live")==true
                    val homeTicket=p.getObjectExtra("popupscale.homeTicket") as Int
                    val rect=p.getObjectExtra("popupscale.frameRect") as? Rect
                    if(live&&rect!=null){
                        val original=p.method;val owner=p.thisObject
                        frames.prepare(context,id,rect){nonce->
                            hint?.dismiss()
                            if(pendingHint===hint)pendingHint=null
                            config.reload()
                            val eligible=home.ticket()==homeTicket&&config.enabled&&!config.observe&&config.hang&&config.live&&SystemUiReadiness.ready()
                            val chosen=eligible&&home.chooseHome()
                            val ended=Guard.call("launcher-frame-finish"){
                                XposedBridge.invokeOriginalMethod(original,owner,arrayOf(cancel))
                                X.callMethod(owner,"finishTaskMoveAnimation");true
                            }==true
                            cancel.recycle()
                            if(!chosen||!ended){pendingCards.remove(owner);frames.abort(context,nonce);return@prepare}
                            home.finish(context,homeTicket){ok->
                                pendingCards.remove(owner)
                                if(ok)sendHang(context,id,true,nonce) else frames.abort(context,nonce)
                            }
                        }
                        return
                    }
                    hint?.dismiss()
                    if(pendingHint===hint)pendingHint=null
                    Guard.call("launcher-finish"){X.callMethod(p.thisObject,"finishTaskMoveAnimation")}
                    main.postDelayed({
                        cancel.recycle()
                        config.reload()
                        if(!config.enabled||config.observe||!config.hang||config.live!=live){pendingCards.remove(p.thisObject);return@postDelayed}
                        if(live)home.finish(context,homeTicket){ok->pendingCards.remove(p.thisObject);if(ok)sendHang(context,id,true)}
                        else {pendingCards.remove(p.thisObject);sendHang(context,id,false)}
                    },120)
                }
            })
        Guard.info("One UI Home OtherActivityInputConsumer signature matched")
    }
    private fun sendHang(context:Context,id:Int,homeReady:Boolean,nonce:String=""){Guard.call("launcher-send"){
        if(android.os.Build.VERSION.SDK_INT>=34){
            val intent=Intent(SystemUiHooks.ACTION).setPackage(RomProfile.SYSTEM_UI)
                .putExtra("task",id).putExtra("when",SystemClock.uptimeMillis()).putExtra("homeReady",homeReady)
                .putExtra("frame",nonce)
            context.sendBroadcast(intent,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }}
    private fun consumerDisplayId(owner:Any,context:Context):Int {
        X.findFieldIfExists(owner.javaClass,"displayId")?.let{return it.getInt(owner)}
        Guard.field(owner,"deviceState")?.let{ds->
            X.findFieldIfExists(ds.javaClass,"displayId")?.let{return it.getInt(ds)}
            if(X.findMethodExactIfExists(ds.javaClass,"getDisplayId")!=null)
                (X.callMethod(ds,"getDisplayId") as? Int)?.let{return it}
        }
        return if(X.findMethodExactIfExists(context.javaClass,"getDisplayId")!=null)
            (X.callMethod(context,"getDisplayId") as? Int) ?: 0 else 0
    }
    private fun runningTopTask(owner:Any):Any? {
        fun valid(info:Any?)=info!=null&&(Guard.field(info,"taskId") as? Int ?: 0)>0&&
            Guard.field(info,"topActivity") is android.content.ComponentName&&Guard.field(info,"displayId")==0
        val useCase=Guard.field(owner,"topTaskUseCase")
            ?: owner.javaClass.declaredFields.firstOrNull{it.type.name.endsWith("TopTaskUseCase")}?.apply{isAccessible=true}?.get(owner)
        if(useCase!=null){
            try {
                val info=X.callMethod(useCase,"runningTaskForDisplayId",0)
                if(valid(info))return info
            } catch (_: Throwable) {
                try {
                    val m = useCase.javaClass.methods.firstOrNull {
                        it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.returnType.name.contains("Task")
                    } ?: useCase.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.returnType.name.contains("Task")
                    }
                    if (m != null) {
                        val info=if (m.parameterCount == 1) m.invoke(useCase, 0) else m.invoke(useCase)
                        if(valid(info))return info
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return try {
            val atm=X.callStaticMethod(X.findClass("android.app.ActivityTaskManager",loader),"getInstance") ?: return null
            val info=(X.callMethod(atm,"getTasks",1) as? List<*>)?.firstOrNull()
            if(valid(info)){
                Guard.info("launcher top task resolved through native fallback task=${X.getIntField(info,"taskId")}")
                info
            }else{
                Guard.info("launcher top task metadata unavailable; placeholder rejected")
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
    private fun clear(owner:Any,context:Context){
        pendingCards.remove(owner)
        states.remove(owner)?.let{it.velocity?.recycle();it.velocity=null;it.cards.discard();removeHint(it,true)}
    }
    private fun replayCards(owner:Any,gate:TaskListDeferral){
        val animated=gate.takeDeferred()?:return
        Guard.call("launcher-card-replay"){X.callMethod(owner,"appearTaskList",animated)}
    }
    private fun showHint(context:Context,state:State,target:HangTargetPolicy.State){
        if(state.hint==null){
            state.hint=HangTargetOverlay(context,config.live)
            Guard.info("launcher early guide shown task=${state.taskId}")
        }
        state.hint?.update(target)
    }
    private fun removeHint(state:State,immediate:Boolean=false){
        state.hint?.dismiss(immediate);state.hint=null
    }
}
