package io.github.s24u.popupscale.gestures

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the target ROM's existing transition engine; never falls back to a bare mode change. */
class NativeTransitionBridge(private val loader: ClassLoader) {
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var transitions: Any? = null
    @Volatile private var starter: Any? = null
    @Volatile private var syncQueue: Any? = null
    private val closingTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val preservedChanges=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any,Boolean>())
    var preservePopup:(Any,String)->Boolean={_,_->false}
    var pinFinishedPopup:(Int,Any)->Unit={_,_->}
    var pinPopupFrame:(Int,Any,Any)->Boolean={_,_,_->false}
    private data class DefaultFrame(val start:Any,val finish:Any,val changes:List<Any>)
    private val defaultFrame=ThreadLocal<DefaultFrame?>()

    fun markClosing(id: Int) {
        closingTasks.add(id)
        Guard.info("task marked closing id=$id")
    }
    fun unmarkClosing(id: Int) { closingTasks.remove(id) }
    fun isClosing(id: Int): Boolean = closingTasks.contains(id)

    fun install() {
        val queueClass=X.findClass("com.android.wm.shell.common.SyncTransactionQueue",loader)
        XposedBridge.hookAllConstructors(queueClass,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){syncQueue=p.thisObject}
        })
        val transitionsType=X.findClass("com.android.wm.shell.transition.Transitions",loader)
        XposedBridge.hookAllConstructors(transitionsType,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                transitions=p.thisObject
                Guard.info("native Shell Transitions captured")
            }
        })
        XposedBridge.hookAllMethods(transitionsType,"setupStartState",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable())return
                Guard.call("home-start-frame"){
                    val info=p.args.getOrNull(0) ?: return@call
                    val start=p.args.getOrNull(1) ?: return@call
                    val finish=p.args.getOrNull(2) ?: return@call
                    val changes=X.callMethod(info,"getChanges") as List<*>
                    for(change in changes.filterNotNull()){
                        // setupStartState independently writes alpha=0 for force-show EXIT.
                        // Fix the same native transaction, before it is ever submitted.
                        if(!preservePopup(change,"default"))continue
                        val task=X.callMethod(change,"getTaskInfo") ?: continue
                        if(pinPopupFrame(X.getIntField(task,"taskId"),start,finish))preservedChanges[change]=true
                    }
                }
            }
        })
        // Samsung otherwise runs a second, independent force-hide/force-show animator on
        // unchanged freeform leashes during Recents. It competes with the HOME mini animation.
        val provider=X.findClassIfExists("com.android.wm.shell.transition.MultiTaskingTransitionProvider",loader)
        if(provider!=null)XposedBridge.hookAllMethods(provider,"buildForceHideAnimationIfNeeded",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                Guard.call("home-preserve-popup"){
                    val change=p.args.getOrNull(0) ?: return@call
                    val reason=p.args.getOrNull(1) as? String ?: return@call
                    if(!preservePopup(change,reason))return@call
                    preservedChanges[change]=true
                    // true is the native 'handled without another animator' branch. Returning
                    // false would instead pass the popup to the remote Recents animation.
                    p.result=true
                    val info=X.callMethod(change,"getTaskInfo")
                    Guard.info("HOME native force-hide suppressed task=${X.getIntField(info,"taskId")} reason=$reason")
                }
            }
        })
        XposedBridge.hookAllMethods(transitionsType,"onFinish",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                Guard.call("home-finish-pin"){
                    val known=X.getObjectField(p.thisObject,"mKnownTransitions") as Map<*,*>
                    val active=known[p.args.getOrNull(0)] ?: return@call
                    val merged=X.getObjectField(active,"mMerged") as? List<*> ?: emptyList<Any>()
                    for(entry in listOf(active)+merged.filterNotNull()){
                        val info=X.getObjectField(entry,"mInfo") ?: continue
                        val finish=X.getObjectField(entry,"mFinishT") ?: continue
                        val changes=X.callMethod(info,"getChanges") as List<*>
                        for(change in changes.filterNotNull()){
                            if(preservedChanges[change]!=true)continue
                            val task=X.callMethod(change,"getTaskInfo") ?: continue
                            pinFinishedPopup(X.getIntField(task,"taskId"),finish)
                        }
                    }
                }
            }
        })
        XposedBridge.hookAllMethods(transitionsType, "onTransitionReady", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                Guard.call("on-transition-ready-intercept") {
                    val info = p.args.getOrNull(1) ?: return@call
                    val changes = X.callMethod(info, "getChanges") as? List<*> ?: return@call
                    val hasClosing = changes.filterNotNull().any { change ->
                        val taskInfo = X.callMethod(change, "getTaskInfo")
                        val id = taskInfo?.let { X.getIntField(it, "taskId") } ?: -1
                        closingTasks.contains(id)
                    }
                    if (hasClosing) {
                        val t = p.args.getOrNull(2)
                        val finishT = p.args.getOrNull(3)
                        changes.filterNotNull().forEach { change ->
                            val taskInfo = X.callMethod(change, "getTaskInfo")
                            val id = taskInfo?.let { X.getIntField(it, "taskId") } ?: -1
                            if (closingTasks.contains(id)) {
                                val leash = X.callMethod(change, "getLeash")
                                if (t != null) {
                                    X.callMethod(t, "setAlpha", leash, 0f)
                                    X.callMethod(t, "hide", leash)
                                }
                                if (finishT != null) {
                                    X.callMethod(finishT, "setAlpha", leash, 0f)
                                    X.callMethod(finishT, "hide", leash)
                                }
                            }
                        }
                    }
                }
            }
        })
        XposedBridge.hookAllMethods(transitionsType, "dispatchTransition", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                Guard.call("transitions-dispatch-intercept") {
                    val active = p.args.getOrNull(0) ?: return@call
                    val info = Guard.field(active, "info", "mInfo") ?: return@call
                    val changes = X.callMethod(info, "getChanges") as? List<*> ?: return@call
                    val closing = changes.filterNotNull().filter { change ->
                        val taskInfo = X.callMethod(change, "getTaskInfo")
                        val id = taskInfo?.let { X.getIntField(it, "taskId") } ?: -1
                        closingTasks.contains(id)
                    }
                    if (closing.isNotEmpty()) {
                        val startT = Guard.field(active, "startT", "mStartT") ?: return@call
                        val finishT = Guard.field(active, "finishT", "mFinishT") ?: return@call
                        val token = Guard.field(active, "token", "mToken") ?: return@call
                        closing.forEach { change ->
                            val leash = X.callMethod(change, "getLeash")
                            X.callMethod(startT, "setAlpha", leash, 0f)
                            X.callMethod(startT, "hide", leash)
                            X.callMethod(change, "getSnapshot")?.let {
                                X.callMethod(startT, "setAlpha", it, 0f)
                                X.callMethod(startT, "hide", it)
                            }
                            X.callMethod(finishT, "setAlpha", leash, 0f)
                            X.callMethod(finishT, "hide", leash)
                            val id = X.getIntField(X.callMethod(change, "getTaskInfo"), "taskId")
                            closingTasks.remove(id)
                            Guard.info("dispatchTransition intercepted for task=$id: suppressed and hidden")
                        }
                        X.callMethod(startT, "apply")
                        val executor = X.getObjectField(p.thisObject, "mMainExecutor")
                        X.callMethod(executor, "execute", Runnable {
                            Guard.call("dispatch-transition-finish") {
                                X.callMethod(p.thisObject, "onFinish", token, null as Any?)
                                Guard.info("dispatchTransition onFinish called")
                            }
                        })
                        p.result = true
                    }
                }
            }
        })
        val defaultHandlerType = X.findClassIfExists("com.android.wm.shell.transition.DefaultTransitionHandler", loader)
        if (defaultHandlerType != null) {
            // The standalone force-show transition does NOT use buildForceHideAnimationIfNeeded.
            // Its DefaultTransitionHandler.loadAnimation path needs its own per-change guard.
            XposedBridge.hookAllMethods(defaultHandlerType,"startAnimation",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    p.setObjectExtra("popup-old-default-frame",defaultFrame.get())
                    defaultFrame.remove()
                    Guard.call("home-default-frame"){
                        val changes=X.callMethod(p.args[1],"getChanges") as List<*>
                        val own=changes.filterNotNull().filter{preservePopup(it,"default")}
                        if(own.isNotEmpty())defaultFrame.set(DefaultFrame(p.args[2],p.args[3],own))
                    }
                }
                override fun afterHookedMethod(p:MethodHookParam){
                    val old=p.getObjectExtra("popup-old-default-frame") as? DefaultFrame
                    if(old==null)defaultFrame.remove() else defaultFrame.set(old)
                }
            })
            XposedBridge.hookAllMethods(defaultHandlerType,"loadAnimation",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    Guard.call("home-default-no-animation"){
                        val frame=defaultFrame.get() ?: return@call
                        val change=p.args.getOrNull(2) ?: return@call
                        if(frame.changes.none{it===change}||!preservePopup(change,"default"))return@call
                        val task=X.callMethod(change,"getTaskInfo") ?: return@call
                        val id=X.getIntField(task,"taskId")
                        // Native CHANGE preparation can overwrite position/crop after setupStartState.
                        // Reapply a single current owned snapshot to both transactions. A null
                        // animation is the ROM's ordinary NO_ANIMATION result; Shell still finishes
                        // the transition and animates every unrelated change normally.
                        if(!pinPopupFrame(id,frame.start,frame.finish))return@call
                        preservedChanges[change]=true
                        p.result=null
                        Guard.info("HOME standalone force-show skipped task=$id pinned=true")
                    }
                }
            })
            XposedBridge.hookAllMethods(defaultHandlerType, "startAnimation", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    Guard.call("default-transition-intercept") {
                        val info = p.args.getOrNull(1) ?: return@call
                        val changes = X.callMethod(info, "getChanges") as? List<*> ?: return@call
                        val closing = changes.filterNotNull().filter { change ->
                            val taskInfo = X.callMethod(change, "getTaskInfo")
                            val id = taskInfo?.let { X.getIntField(it, "taskId") } ?: -1
                            closingTasks.contains(id)
                        }
                        if (closing.isNotEmpty()) {
                            val startT = p.args[2]
                            val finishT = p.args[3]
                            val callback = p.args[4]
                            closing.forEach { change ->
                                val leash = X.callMethod(change, "getLeash")
                                X.callMethod(startT, "setAlpha", leash, 0f)
                                X.callMethod(startT, "hide", leash)
                                X.callMethod(change, "getSnapshot")?.let {
                                    X.callMethod(startT, "setAlpha", it, 0f)
                                    X.callMethod(startT, "hide", it)
                                }
                                X.callMethod(finishT, "setAlpha", leash, 0f)
                                X.callMethod(finishT, "hide", leash)
                                val id = X.getIntField(X.callMethod(change, "getTaskInfo"), "taskId")
                                closingTasks.remove(id)
                                Guard.info("default transition intercepted for task=$id: suppressed and hidden")
                            }
                            X.callMethod(startT, "apply")
                            val engine = transitions
                            val executor = engine?.let { X.getObjectField(it, "mMainExecutor") }
                            if (executor != null) {
                                X.callMethod(executor, "execute", Runnable {
                                    Guard.call("default-transition-finish") {
                                        X.callMethod(callback, "onTransitionFinished", null as Any?)
                                        Guard.info("default transition finished quietly")
                                    }
                                })
                            } else {
                                X.callMethod(callback, "onTransitionFinished", null as Any?)
                            }
                            p.result = true
                        }
                    }
                }
            })
        }
        val starterType=X.findClass("com.android.wm.shell.freeform.FreeformTaskTransitionHandler",loader)
        XposedBridge.hookAllConstructors(starterType,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                starter=p.thisObject
                Guard.info("native freeform transition starter captured")
            }
        })
    }

    fun resizeInSync(wct:Any,taskId:Int,finishSurface:(Any)->Unit,
                     isCurrent:()->Boolean={true},canSubmit:()->Boolean={true},done:(Boolean)->Unit):Boolean {
        val queue=syncQueue ?: return false
        val request=SyncResizeRequest(SystemClock.uptimeMillis())
        fun complete(ok:Boolean){if(request.finish())main.post{done(ok)}}
        return try {
            val executor=X.getObjectField(queue,"mMainExecutor")
            var waitingLogged=false
            lateinit var attempt:Runnable
            fun schedule(){
                if(Guard.call("resize-sync-dispatch"){X.callMethod(executor,"execute",attempt);true}!=true)complete(false)
            }
            attempt=Runnable {
                val ok=Guard.call("resize-sync-submit"){
                    // runInSync attaches to the currently in-flight draw, not an arbitrary
                    // queued WCT. Wait on Shell's executor before submitting this task's WCT.
                    when(request.next(SystemClock.uptimeMillis(),X.getObjectField(queue,"mInFlight")!=null,isCurrent())){
                        SyncResizeRequest.Step.WAIT->{
                            if(!waitingLogged){waitingLogged=true;Guard.info("resize sync waiting task=$taskId")}
                            main.postDelayed({schedule()},16)
                            return@call true
                        }
                        SyncResizeRequest.Step.CANCEL->{complete(false);return@call true}
                        SyncResizeRequest.Step.TIMEOUT->{
                            Guard.info("resize sync queue wait expired task=$taskId");complete(false);return@call true
                        }
                        SyncResizeRequest.Step.IGNORE->return@call true
                        SyncResizeRequest.Step.SUBMIT->Unit
                    }
                    if(!canSubmit()){complete(false);return@call true}
                    val type=X.findClass("com.android.wm.shell.common.SyncTransactionQueue\$TransactionRunnable",loader)
                    val callback=Proxy.newProxyInstance(loader,arrayOf(type)){proxy,method,args->
                        when(method.name){
                            "runWithTransaction"->{
                                val applied=Guard.call("resize-sync-frame") frame@{
                                    if(!isCurrent())return@frame false
                                    finishSurface(args!![0])
                                    Guard.info("resize synchronized frame task=$taskId")
                                    true
                                }==true
                                complete(applied)
                                null
                            }
                            "toString"->"PopupScaleResize($taskId)"
                            "hashCode"->System.identityHashCode(proxy)
                            "equals"->proxy===args?.firstOrNull()
                            else->null
                        }
                    }
                    X.callMethod(queue,"queue",wct)
                    X.callMethod(queue,"runInSync",callback)
                    true
                }==true
                if(!ok)complete(false)
            }
            schedule()
            true
        }catch(error:Throwable){Guard.failure("resize-sync-submit",error);false}
    }

    fun ready() = transitions != null
    private fun rune(name:String)=runCatching{
        X.getStaticBooleanField(X.findClass("com.samsung.android.rune.CoreRune",loader),name)
    }.getOrDefault(false)

    /** Read Shell-owned state on its executor. Unknown/pending/rotation transitions still wait. */
    fun whenTasksReady(ids:Set<Int>,deadline:Long,done:(Boolean)->Unit) {
        val engine=transitions ?: run{main.post{done(false)};return}
        val started=SystemClock.uptimeMillis()
        val delivered=AtomicBoolean(false)
        var retry:Runnable?=null
        val timeout=Runnable{
            if(delivered.compareAndSet(false,true)){
                retry?.let{main.removeCallbacks(it)}
                Guard.info("HOME task gate expired waitMs=${SystemClock.uptimeMillis()-started}")
                done(false)
            }
        }
        fun complete(ok:Boolean,reason:String){
            main.post{
                if(delivered.compareAndSet(false,true)){
                    main.removeCallbacks(timeout)
                    retry?.let{main.removeCallbacks(it)}
                    val valid=ok&&SystemClock.uptimeMillis()<deadline
                    Guard.info("HOME task gate ready=$valid reason=$reason waitMs=${SystemClock.uptimeMillis()-started}")
                    done(valid)
                }
            }
        }
        main.postDelayed(timeout,(deadline-started).coerceAtLeast(0))
        var lastWait=""
        retry=Runnable{
            if(delivered.get())return@Runnable
            try {
                val executor=X.getObjectField(engine,"mMainExecutor")
                X.callMethod(executor,"execute",Runnable shell@{
                    if(delivered.get())return@shell
                    try {
                        val snapshot=homeTransitions(engine)
                        when(HomeTransitionPolicy.decide(ids,snapshot,SystemClock.uptimeMillis(),deadline)){
                            HomeTransitionPolicy.Decision.START->complete(true,"unrelated=${snapshot.size}")
                            HomeTransitionPolicy.Decision.EXPIRED->complete(false,"deadline")
                            HomeTransitionPolicy.Decision.WAIT->{
                                val reason="tasks=${snapshot.flatMap{it.tasks}.distinct().sorted().joinToString(",")} "+
                                    "unknown=${snapshot.any{it.uncertain}} display=${snapshot.any{it.displayChange}}"
                                if(reason!=lastWait){lastWait=reason;Guard.info("HOME task gate waiting $reason")}
                                retry?.let{main.postDelayed(it,32)}
                            }
                        }
                    }catch(error:Throwable){
                        // Reflection mismatch cannot allow a competing transform. Keep the old barrier.
                        Guard.failure("home-task-gate",error)
                        whenIdle{complete(it,"global-fallback")}
                    }
                })
            }catch(error:Throwable){Guard.failure("home-task-gate-dispatch",error);complete(false,"dispatch")}
        }
        retry?.run()
    }

    private fun homeTransitions(engine:Any):List<HomeTransitionPolicy.Transition> {
        // mKnownTransitions includes pending, ready, active and merged transitions until onFinish.
        // Unlike an observer cache, it also contains transitions that started before our request.
        val known=X.getObjectField(engine,"mKnownTransitions") as Map<*,*>
        val type=X.findClass("android.window.TransitionInfo",loader)
        val displayFlag=X.getStaticIntField(type,"FLAG_IS_DISPLAY")
        val wallpaperFlag=X.getStaticIntField(type,"FLAG_IS_WALLPAPER")
        val overlayFlag=X.getStaticIntField(type,"FLAG_IS_TRANSIENT_LAUNCH_OVERLAY")
        return known.values.map { active->
            checkNotNull(active)
            val info=X.getObjectField(active,"mInfo")
                ?: return@map HomeTransitionPolicy.Transition(emptySet(),uncertain=true)
            val changes=X.callMethod(info,"getChanges") as List<*>
            val tasks=mutableSetOf<Int>()
            var uncertain=changes.isEmpty()
            var displayChange=false
            for(change in changes){
                checkNotNull(change)
                val flags=X.callMethod(change,"getFlags") as Int
                val startRotation=X.callMethod(change,"getStartRotation") as Int
                val endRotation=X.callMethod(change,"getEndRotation") as Int
                if(flags and displayFlag!=0||startRotation!=endRotation)displayChange=true
                // Only bypass target changes whose native competing animator we actually
                // suppressed. An ordinary equal-bounds CHANGE can still own a real animation.
                if(preservedChanges[change]==true)continue
                val task=X.callMethod(change,"getTaskInfo")
                if(task!=null){
                    val id=X.getIntField(task,"taskId")
                    if(id<0)uncertain=true else tasks.add(id)
                }else if(flags and (wallpaperFlag or overlayFlag)==0)uncertain=true
            }
            HomeTransitionPolicy.Transition(tasks,uncertain,displayChange)
        }
    }

    fun whenIdle(done:(Boolean)->Unit) {
        val engine=transitions
        if(engine==null){main.post{done(false)};return}
        try {
            val executor=X.getObjectField(engine,"mMainExecutor")
            X.callMethod(executor,"execute",Runnable {
                val ok=Guard.call("native-idle") {
                    X.callMethod(engine,"runOnIdle",Runnable {
                        main.post{Guard.call("native-idle-callback"){done(true)}}
                    });true
                }==true
                if(!ok)main.post{Guard.call("native-idle-callback"){done(false)}}
            })
        }catch(error:Throwable){Guard.failure("native-idle",error);main.post{done(false)}}
    }

    fun maximize(task: TaskRecord, current: () -> TaskRecord?) {
        check(Guard.open("native-maximize")) { "Native maximize disabled after failures" }
        val engine=transitions ?: error("Native Shell transitions unavailable")
        val executor=X.getObjectField(engine,"mMainExecutor")
        X.callMethod(executor,"execute",Runnable {
            Guard.call("native-maximize") {
                val latest=current() ?: error("Task no longer eligible")
                check(latest.mode==5&&latest.visible&&latest.focused&&latest.bounds==task.bounds) {
                    "Task changed before transition"
                }
                val wct=X.newInstance(X.findClass("android.window.WindowContainerTransaction",loader))
                X.callMethod(wct,"setWindowingMode",latest.token,1)
                X.callMethod(wct,"setBounds",latest.token,null)
                X.callMethod(wct,"reorder",latest.token,true)
                if(rune("MW_FREEFORM_SHELL_TRANSITION"))
                    X.callMethod(wct,"setChangeTransitMode",latest.token,1,"PopupScale maximize(${task.id})")
                submit(engine,wct,1)
                Guard.info("native maximize submitted task=${task.id}")
            }
        })
    }

    fun restore(id:Int, bounds:Rect, token:()->Any?, accepted:(Boolean)->Unit,focusable:Boolean=true,
                quiet:Boolean=false,surface:()->Any?={null}) {
        val target=Rect(bounds)
        fun complete(ok:Boolean){main.post{Guard.call("native-restore-callback"){accepted(ok)}}}
        val engine=transitions
        if(engine==null){complete(false);return}
        try {
            val executor=X.getObjectField(engine,"mMainExecutor")
            X.callMethod(executor,"execute",Runnable {
                val ok=Guard.call("native-restore") {
                    val currentToken=token() ?: return@call true // A vanished task cannot remain hidden.
                    val wct=X.newInstance(X.findClass("android.window.WindowContainerTransaction",loader))
                    X.callMethod(wct,"setWindowingMode",currentToken,5)
                    X.callMethod(wct,"setBounds",currentToken,target)
                    X.callMethod(wct,"setHidden",currentToken,false)
                    X.callMethod(wct,"setFocusable",currentToken,focusable)
                    X.callMethod(wct,"reorder",currentToken,true)
                    if(!quiet&&rune("MW_FREEFORM_SHELL_TRANSITION"))
                        X.callMethod(wct,"setChangeTransitMode",currentToken,1,"PopupScale restore($id)")
                    if(quiet)submitQuiet(engine,wct,id,surface) else submit(engine,wct,5)
                    Guard.info("native restore submitted task=$id focusable=$focusable quiet=$quiet")
                    true
                }==true
                complete(ok)
            })
        } catch(error:Throwable){Guard.failure("native-restore",error);complete(false)}
    }

    private fun submitQuiet(engine:Any,wct:Any,id:Int,surface:()->Any?) {
        val type=X.findClass("com.android.wm.shell.transition.Transitions\$TransitionHandler",loader)
        val consumed=AtomicBoolean(false)
        val handler=Proxy.newProxyInstance(loader,arrayOf(type)){proxy,method,args->
            when(method.name){
                "toString"->"PopupScaleQuietOpen($id)"
                "hashCode"->System.identityHashCode(proxy)
                "equals"->proxy===args?.firstOrNull()
                "startAnimation"->{
                    Guard.call("native-quiet-open"){
                        val a=args?:error("Transition arguments missing")
                        val real=surface()?:error("Task surface unavailable")
                        val start=a[2];val finish=a[3];val callback=a[4]
                        val changes=X.callMethod(a[1],"getChanges") as List<*>
                        val own=changes.filterNotNull().filter { change->
                            val info=X.callMethod(change,"getTaskInfo")
                            info!=null&&X.getIntField(info,"taskId")==id
                        }
                        check(own.isNotEmpty()){ "Requested task not present in transition" }
                        own.forEach { change->
                            X.callMethod(start,"setAlpha",X.callMethod(change,"getLeash"),0f)
                            X.callMethod(change,"getSnapshot")?.let{X.callMethod(start,"setAlpha",it,0f)}
                        }
                        X.callMethod(start,"setAlpha",real,0f)
                        X.callMethod(finish,"setAlpha",real,0f)
                        X.callMethod(start,"apply")
                        // Finish after startAnimation returns; native Shell still owns hierarchy cleanup.
                        main.post {
                            Guard.call("native-quiet-finish"){
                                X.callMethod(X.getObjectField(engine,"mMainExecutor"),"execute",Runnable{
                                    if(!consumed.getAndSet(true))Guard.call("native-quiet-finish"){
                                        X.callMethod(callback,"onTransitionFinished",null as Any?)
                                        Guard.info("quiet native mode change finished task=$id")
                                    }
                                })
                            }
                        }
                        true
                    }==true
                }
                "onTransitionConsumed"->{consumed.set(true);null}
                else->when(method.returnType){java.lang.Boolean.TYPE->false;java.lang.Integer.TYPE->0;else->null}
            }
        }
        val mode=X.getStaticIntField(X.findClass("android.view.WindowManager",loader),"TRANSIT_CHANGE")
        X.callMethod(engine,"startTransition",mode,wct,handler)
    }

    private fun submit(engine:Any,wct:Any,mode:Int) {
        val nativeStarter=starter
        if(nativeStarter!=null) {
            X.callMethod(nativeStarter,"startWindowingModeTransition",wct,mode)
        } else {
            // The exact same Samsung branch used by FreeformTaskTransitionHandler.
            check(rune("MW_SHELL_CHANGE_TRANSITION")) {
                "Native freeform transition starter unavailable"
            }
            val provider=X.getObjectField(engine,"mChangeTransitProvider")
                ?: error("Samsung change transition provider unavailable")
            X.callMethod(provider,"startChangeTransition",wct)
        }
    }
}
