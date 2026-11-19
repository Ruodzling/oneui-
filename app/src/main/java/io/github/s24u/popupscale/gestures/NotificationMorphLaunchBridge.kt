package io.github.s24u.popupscale.gestures

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Reuse Samsung's notification morph, keeping its controller, remote animation and cleanup.
 * Only an explicit module handle gesture registers a source row and its original PendingIntent. */
class NotificationMorphLaunchBridge(private val loader:ClassLoader) {
    private class Request(owner:View,val pending:PendingIntent,val bounds:Rect,val banner:Rect,val identity:NotificationRouteIdentity,
        val validate:(View,PendingIntent)->Boolean,val unsent:()->Unit){
        val source=WeakReference(owner)
        val deadline=SystemClock.uptimeMillis()+3500
        val consumed=AtomicBoolean(false)
        var timeout:Runnable?=null
        var animationCleanup:Runnable?=null
        @Volatile var transitionInfo:Any?=null
        @Volatile var rootOffset:android.graphics.Point?=null
        val motionFrames=java.util.IdentityHashMap<Any, NotificationBannerMotion>()
        val drawingSurfaces=java.util.IdentityHashMap<Any,Any>()
    }
    private val main=Handler(Looper.getMainLooper())
    private val rows=java.util.WeakHashMap<View,Request>()
    private val pending=java.util.IdentityHashMap<PendingIntent,Request>()
    private val controllers=java.util.WeakHashMap<Any,Request>()
    private val animations=java.util.WeakHashMap<View,Request>()
    private val scope=NotificationCallbackScope<Request>()
    private val sending=ThreadLocal<Request?>()
    private val extraction=NotificationMorphExtractionGate()
    private var ready=false
    fun install():Boolean {
        val animator=sequenceOf("com.android.systemui.animation.ActivityTransitionAnimator",
            "com.android.systemui.animation.ActivityLaunchAnimator").mapNotNull{X.findClassIfExists(it,loader)}.firstOrNull()?:return false
        val methods=NotificationMorphContract.starters(animator)
        if(methods.isEmpty())return false
        val gates=sequenceOf("com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter",
            "com.android.systemui.statusbar.phone.ActivityStarterImpl").mapNotNull{X.findClassIfExists(it,loader)}
            .flatMap{NotificationMorphContract.keyguardCallbacks(it).asSequence()}.toList()
        if(gates.isEmpty())return false
        NotificationLaunchOptions.freeform(Bundle())
        installTargetRecovery(animator)
        // Preserve ownership even if keyguard appears after release and native launch is deferred.
        // A late callback still reaches the scoped send check and is cancelled, never fullscreen.
        for(method in gates)XposedBridge.hookMethod(method,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-morph-keyguard"){
                val request=scope.current()?:return@call
                val index=NotificationMorphContract.keyguardIndex(method);val callback=p.args[index]?:return@call
                p.args[index]=scope.wrap(request,method.parameterTypes[index],callback)
            }}
        })
        for(method in methods)XposedBridge.hookMethod(method,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-morph-starter"){
                val request=scope.current()?:p.args.firstNotNullOfOrNull{a->a?.let{controllerRequest(it,0)}}?:return@call
                val index=NotificationMorphContract.callbackIndex(method);val original=p.args[index]?:return@call
                p.args[index]=scope.wrap(request,method.parameterTypes[index],original)
                Guard.info("notification native morph starter attached callback=${method.parameterTypes[index].simpleName}")
            }}
        })
        val signature=arrayOf(Context::class.java,Integer.TYPE,Intent::class.java,PendingIntent.OnFinished::class.java,
            Handler::class.java,String::class.java,Bundle::class.java)
        val sends=listOf("send","sendAndReturnResult").mapNotNull{n->
            runCatching{PendingIntent::class.java.getDeclaredMethod(n,*signature)}.getOrNull()
        }
        if(sends.isEmpty())return false
        for(method in sends)XposedBridge.hookMethod(method,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val pi=p.thisObject as PendingIntent
                val request=scope.current()?.takeIf{it.pending===pi}
                    ?:synchronized(rows){pending[pi]}?:return
                if(sending.get()===request)return // send delegates to sendAndReturnResult with patched options.
                val source=request.source.get()
                try{
                    if(source==null||SystemClock.uptimeMillis()>request.deadline||!request.validate(source,pi)||
                        !request.consumed.compareAndSet(false,true))throw PendingIntent.CanceledException()
                    val original=p.args[6] as? Bundle?:Bundle()
                    p.args[6]=NotificationLaunchOptions.notification(original,request.bounds,request.identity)
                    p.setObjectExtra("notification-morph-previous",sending.get())
                    p.setObjectExtra("notification-morph-request",request)
                    sending.set(request)
                    Guard.info("notification native morph launch options patched remote=${NotificationLaunchOptions.hasRemoteAnimation(original)}; native animation retained")
                }catch(error:Throwable){
                    p.throwable=PendingIntent.CanceledException()
                    remove(request)
                    main.post{request.unsent()}
                    if(error is PendingIntent.CanceledException)Guard.info("notification native morph request cancelled before dispatch")
                    else Guard.failure("notification-morph-options",error)
                }
            }
            override fun afterHookedMethod(p:MethodHookParam){
                val request=p.getObjectExtra("notification-morph-request") as? Request?:return
                val previous=p.getObjectExtra("notification-morph-previous") as? Request
                if(previous==null)sending.remove()else sending.set(previous)
                remove(request)
                if(p.hasThrowable())main.post{request.unsent()}
                Guard.info("notification native morph dispatched=${!p.hasThrowable()} result=${p.result as? Int?:0}")
            }
        })
        ready=true
        Guard.info("notification native morph ready animator=${animator.simpleName} starterMethods=${methods.size}")
        return true
    }
    private fun controllerRequest(controller:Any,depth:Int):Request? {
        if(depth>3)return null
        synchronized(rows){controllers[controller]}?.let{return it}
        (Guard.field(controller,"notification","notificationRow") as? View)?.let{row->
            synchronized(rows){rows[row]?:animations[row]}?.let{return it}
        }
        // Only animator-controller delegates, never arbitrary object graphs or app payloads.
        return sequenceOf("delegate","controller","launchAnimatorController","\$\$delegate_0").mapNotNull{Guard.field(controller,it)}
            .filter{it!==controller&&it.javaClass.name.startsWith("com.android.systemui.")}
            .firstNotNullOfOrNull{controllerRequest(it,depth+1)}
    }
    private fun installTargetRecovery(animator:Class<*>) {
        val adapter=runCatching{NotificationMorphTargetAdapter(loader)}.getOrNull()?:return
        installExtractionGuard(adapter)
        installFrameGeometry(adapter)
        X.findClassIfExists("com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider",loader)?.let{provider->
            XposedBridge.hookAllMethods(provider,"getAnimatorController",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){if(!p.hasThrowable())Guard.call("notification-morph-source-controller"){
                    val controller=p.result?:return@call
                    val request=controllerRequest(controller,0)?:return@call
                    trackController(controller,request)
                }}
            })
        }
        X.findClassIfExists("com.android.systemui.animation.RemoteAnimationRunnerCompat\$1",loader)?.let{compat->
            XposedBridge.hookAllMethods(compat,"startAnimation",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-morph-capture-transition"){
                    val runner=Guard.field(p.thisObject,"val\$runner")?:return@call
                    val controller=Guard.field(runner,"controller")?:return@call
                    val request=controllerRequest(controller,0)?:return@call
                    request.transitionInfo=p.args.getOrNull(1)
                    request.transitionInfo?.let{info->
                        adapter.rootOffset(info,request.identity.packageName,request.identity.uid/100000)?.let{request.rootOffset=it}
                    }
                    Guard.info("notification native morph full transition captured")
                }}
            })
        }
        for(method in animator.declaredMethods.filter{it.name=="createEphemeralRunner"&&it.parameterTypes.size==1})
            XposedBridge.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-morph-track-controller"){
                    val controller=p.args[0]?:return@call
                    val request=controllerRequest(controller,0)?:return@call
                    if(runCatching{X.callMethod(controller,"isLaunching")}.getOrNull()!=true)return@call
                    trackController(controller,request)
                }}
            })
        for((name,isShell) in listOf("LegacyAnimationDelegate" to false,"TransitionAnimationDelegate" to true)){
            val type=X.findClassIfExists("com.android.systemui.animation.ActivityTransitionAnimator\$$name",loader)?:continue
            XposedBridge.hookAllMethods(type,"resolveAnimatedSurface",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable()||p.result!=null)return
                    Guard.call("notification-morph-target-recovery"){
                        val controller=Guard.field(p.thisObject,"controller")?:return@call
                        val request=controllerRequest(controller,0)?:return@call
                        if(!request.consumed.get()||SystemClock.uptimeMillis()>request.deadline+1500)return@call
                        val source=request.source.get()?:return@call
                        if(source.context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)return@call
                        val pkg=request.identity.packageName
                        val selected=if(isShell)adapter.shell(p.args[0],p.args.getOrNull(1) as? Array<*>,pkg,request.identity.uid/100000)
                            else adapter.legacy(p.args.getOrNull(0) as? Array<*>,p.args.getOrNull(1) as? Array<*>,pkg,request.identity.uid/100000)
                                ?:request.transitionInfo?.let{adapter.shell(it,null,pkg,request.identity.uid/100000)}
                        if(selected==null){Guard.info("notification native morph target missing shell=$isShell; native decision retained");return@call}
                        p.result=selected.surface
                        Guard.info("notification native morph target recovered shell=$isShell task=${selected.task} mode=${selected.mode} translucent=${selected.translucent}")
                    }
                }
            })
        }
    }
    /** Samsung removes opening freeforms before invoking the notification runner (1 -> 0).
     * Decline only that task's competing resource animation, before native code removes it.
     * Do not alter CoreRune, task flags, other notifications, or the shared Change list. */
    private fun installExtractionGuard(adapter:NotificationMorphTargetAdapter){
        val handler=X.findClassIfExists("com.android.wm.shell.transition.RemoteTransitionHandler",loader)?:return
        val provider=X.findClassIfExists("com.android.wm.shell.transition.MultiTaskingTransitionProvider",loader)?:return
        val resource=provider.declaredMethods.singleOrNull{it.name=="loadAnimationFromResources"&&
            it.parameterTypes.contentEquals(arrayOf(Integer.TYPE,Rect::class.java))&&
            android.view.animation.Animation::class.java.isAssignableFrom(it.returnType)}?:return
        val starts=handler.declaredMethods.filter{it.name=="startAnimation"&&it.parameterTypes.size==5&&
            it.parameterTypes[1].name=="android.window.TransitionInfo"}
        if(starts.isEmpty())return
        XposedBridge.hookMethod(resource,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(extraction.owns(p.thisObject,p.args.getOrNull(1))){
                    p.result=null
                    Guard.info("notification native morph competing freeform resource skipped; target retained")
                }
            }
        })
        for(method in starts)XposedBridge.hookMethod(method,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-morph-before-extraction"){
                val remotes=Guard.field(p.thisObject,"mRequestedRemotes") as? Map<*,*>?:return@call
                val remote=remotes[p.args[0]]?:return@call
                val impl=X.callMethod(remote,"getRemoteTransition")?:return@call
                val runner=Guard.field(impl,"val\$runner")?:return@call
                val controller=Guard.field(runner,"controller")?:return@call
                val request=controllerRequest(controller,0)?:return@call
                if(!request.consumed.get()||SystemClock.uptimeMillis()>request.deadline+1500)return@call
                val source=request.source.get()?:return@call
                if(source.context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)return@call
                val pkg=request.identity.packageName
                val change=adapter.shellChange(p.args[1],pkg,request.identity.uid/100000)?:return@call
                if((X.callMethod(change,"getMinimizeAnimState") as? Int?:0)!=0||
                    (X.callMethod(change,"getForceHidingTransit") as? Int?:0)!=0)return@call
                val nativeProvider=Guard.field(p.thisObject,"mMultiTaskingTransitions")?:return@call
                val bounds=X.callMethod(change,"getEndAbsBounds")?:return@call
                request.transitionInfo=p.args[1]
                p.setObjectExtra("notification-morph-extraction-lease",extraction.begin(nativeProvider,bounds))
                Guard.info("notification native morph extraction guarded task=${Guard.field(X.callMethod(change,"getTaskInfo"),"taskId")}")
            }}
            override fun afterHookedMethod(p:MethodHookParam){
                val lease=p.getObjectExtra("notification-morph-extraction-lease") as? NotificationMorphExtractionGate.Lease?:return
                Guard.call("notification-morph-extraction-cleanup"){extraction.end(lease)}
            }
        })
        XposedBridge.hookAllMethods(handler,"shouldExcludeFromRemoteTargets",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-morph-keep-remote-target"){
                val nativeProvider=Guard.field(p.thisObject,"mMultiTaskingTransitions")?:return@call
                val bounds=X.callMethod(p.args[0],"getEndAbsBounds")?:return@call
                if(extraction.owns(nativeProvider,bounds))p.result=false
            }}
        })
        Guard.info("notification native morph pre-extraction guard ready")
    }
    private fun trackController(controller:Any,request:Request){
        synchronized(rows){controllers[controller]=request}
        keepAnimation(request)
        Guard.info("notification native morph controller tracked")
    }
    private fun installFrameGeometry(adapter:NotificationMorphTargetAdapter){
        val internal=X.findClassIfExists("com.android.systemui.animation.ActivityTransitionAnimator\$AnimationDelegateInternal",loader)?:return
        XposedBridge.hookAllMethods(internal,"applyStateToWindow",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-morph-local-frame"){
                val controller=Guard.field(p.thisObject,"controller")?:return@call
                val request=controllerRequest(controller,0)?:return@call
                if(!request.consumed.get()||SystemClock.uptimeMillis()>request.deadline+1500)return@call
                val offset=request.rootOffset?:return@call
                val surface=p.args.getOrNull(0)?:return@call
                val state=p.args.getOrNull(1)?:return@call
                val local=synchronized(request.drawingSurfaces){request.drawingSurfaces[surface]?:run{
                    val pkg=request.identity.packageName
                    if(!adapter.ownsSurface(surface,pkg,request.identity.uid/100000))return@call
                    NotificationMorphFrameAdapter.localSurface(surface).also{
                        request.drawingSurfaces[surface]=it
                        Guard.info("notification native morph local frame prepared root=${offset.x},${offset.y}")
                    }
                }}
                val progress=p.args.getOrNull(2) as? Float
                if(progress!=null&&progress.isFinite()){
                    val motion=request.motionFrames.getOrPut(surface){
                        val end=Guard.field(Guard.field(surface,"endState")!!,"bounds") as android.graphics.RectF
                        NotificationBannerMotion(request.banner,end,offset,state)
                    }
                    p.args[1]=motion.frame(progress,state)
                    // This argument controls only the content fade inside applyStateToWindow.
                    // The outer controller keeps the real linear clock and finish callback.
                    p.args[2]=1f
                    if(motion.newSample(progress))Guard.info("notification banner motion frame progress=${(progress*100).toInt()} bounds=${motion.lastBounds.toShortString()}")
                }else p.args[1]=NotificationMorphFrameAdapter.parentState(state,offset)
                p.args[0]=local
            }}
        })
    }
    private fun keepAnimation(request:Request){
        request.animationCleanup?.let{main.removeCallbacks(it)}
        val cleanup=Runnable{
            synchronized(rows){
                controllers.entries.removeAll{it.value===request}
                animations.entries.removeAll{it.value===request}
            }
            request.transitionInfo=null
            request.rootOffset=null
            request.motionFrames.clear()
            synchronized(request.drawingSurfaces){request.drawingSurfaces.clear()}
        }
        request.animationCleanup=cleanup;main.postDelayed(cleanup,5000)
    }
    /** false means native route unavailable before any invocation; only then use the old direct route. */
    fun launch(owner:View,pi:PendingIntent,bounds:Rect,banner:Rect,identity:NotificationRouteIdentity,validate:(View,PendingIntent)->Boolean,
        unsent:()->Unit,submit:()->Unit):Boolean {
        if(!ready)return false
        val request=Request(owner,pi,Rect(bounds),Rect(banner),identity,validate,unsent)
        synchronized(rows){if(rows[owner]!=null)return true;rows[owner]=request;pending[pi]=request;animations[owner]=request}
        keepAnimation(request)
        val timeout=Runnable{
            remove(request)
            if(!request.consumed.get()){
                Guard.info("notification native morph ended without dispatch")
                request.unsent()
            }
        }
        request.timeout=timeout;main.postDelayed(timeout,3500)
        // Never re-send after invoking the native pipeline: it can already have queued its launch.
        Guard.call("notification-morph-submit"){scope.within(request){submit()}}
        return true
    }
    private fun remove(request:Request){
        synchronized(rows){
            request.source.get()?.let{if(rows[it]===request)rows.remove(it)}
            if(pending[request.pending]===request)pending.remove(request.pending)
        }
        request.timeout?.let{main.removeCallbacks(it)};request.timeout=null
    }
}
