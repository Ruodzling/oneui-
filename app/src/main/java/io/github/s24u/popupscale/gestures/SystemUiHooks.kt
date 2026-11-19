package io.github.s24u.popupscale.gestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X

class SystemUiHooks(private val loader:ClassLoader, private val config:FeatureConfig,private val caps:RomCapabilities) {
    private val main=Handler(Looper.getMainLooper())
    private val nativeTransitions=NativeTransitionBridge(loader)
    private var backend:TaskBackend?=null
    private var mini:MiniCoordinator?=null
    private var frame:TransitionFrameOverlay?=null
    private var opacity:TaskOpacityController?=null
    private val taskSurfaces=ConcurrentHashMap<Int,WeakReference<Any>>()
    private val handles=mutableMapOf<Int,Entry>()
    private var registered=false
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
                Guard.call("systemui-settings-provider-sync"){
                    val fresh=io.github.s24u.popupscale.ModuleSettings.queryProvider(context)
                    if(config.applyProviderMap(fresh)){
                        Guard.info("systemui settings synced from provider enabled=${config.enabled} frameless=${config.frameless}")
                    }
                }
            }finally{settingsSyncing.set(false)}
        }
    }
    private var lastReadiness=-5000L
    private var observedDecor=false
    private val shadeState=ShadeInteractionState()
    private var shadePanel:WeakReference<Any>?=null
    private var shadeNative:NativeShadeState?=null
    private val shadePanelQueued=java.util.concurrent.atomic.AtomicBoolean(false)
    private fun reconcileShadePanel(controller:Any,reason:String){
        shadePanel=WeakReference(controller)
        val native=NativeShadeState.read(controller,{owner,name->Guard.field(owner,name)},
            {owner,name->runCatching{X.callMethod(owner,name)}.getOrNull()}) ?: return
        shadeNative=native
        if(shadeState.reconcile(native)){
            Guard.info("shade interaction blocked=${shadeState.blocked} source=${native.source} " +
                "expanded=${native.expanded} tracking=${native.tracking} reason=$reason " +
                "settledOverride=${shadeState.settledOverride} closedFor=${shadeState.collapsedForMs} animator=${native.runningAnimator}")
            applyShadeState()
        }
    }
    private fun observeShadePanel(controller:Any){
        if(!shadePanelQueued.compareAndSet(false,true))return
        main.post{
            shadePanelQueued.set(false)
            Guard.call("shade-panel-state"){reconcileShadePanel(controller,"height")}
        }
    }
    private fun applyShadeState(){
        handles.values.toList().forEach{Guard.call("handle-shade-state"){it.handle.setShadeObscured(shadeState.blocked)}}
        Guard.call("mini-shade-state"){mini?.setShadeObscured(shadeState.blocked)}
        Guard.call("opacity-shade-state"){opacity?.setShadeObscured(shadeState.blocked)}
    }
    private fun shadeSignal(signal:ShadeInteractionState.Signal,value:Boolean){
        if(shadeState.update(signal,value)){
            Guard.info("shade interaction blocked=${shadeState.blocked} signal=$signal value=$value")
            applyShadeState()
        }
    }
    private val decorationMethods=java.util.Collections.synchronizedSet(mutableSetOf<java.lang.reflect.Method>())
    private val decorationTypes=java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())
    private val positionerTypes=java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())
    private val discoveredTypes=mutableSetOf<String>()
    private data class Entry(val decor:Any?,val task:TaskRecord,val handle:HandleBarInjector)
    // Access these maps only on the SystemUI main thread.
    private val decorations = mutableMapOf<Int, WeakReference<Any>>()
    private val handleFollow=HandleFollowState()
    @Volatile private var handleFocusTask:Int?=null
    private fun handleRecord(task:TaskRecord)=task.copy(focused=HandleFollowState.focused(task.id,task.focused,handleFocusTask))
    private val portraitResizes=mutableSetOf<Int>()
    private val settledLayouts=mutableMapOf<Int,String>()
    private val settlingLayouts=mutableSetOf<Int>()
    private fun layoutKey(task:TaskRecord):String {
        val m=GestureDisplay.metrics(checkNotNull(backend).context)
        return "${m.widthPixels}x${m.heightPixels}:${task.bounds.toShortString()}:${System.identityHashCode(decorations[task.id]?.get())}"
    }
    private fun settleLayout(task:TaskRecord){
        val tasks=backend ?: return
        val id=task.id
        if(settledLayouts[id]==layoutKey(task)||!settlingLayouts.add(id))return
        handles[id]?.handle?.setLayoutPending(true)
        tasks.whenIdle{idle->Guard.call("settled-layout"){
            settlingLayouts.remove(id)
            config.reload()
            val latest=tasks.info(id)?.let{tasks.record(it)}
            if(!idle){handles[id]?.handle?.setLayoutPending(false);return@call}
            if(!config.enabled||config.observe||tasks.locked()||latest==null||latest.mode!=5||
                !latest.visible||!latest.focused||mini?.isMini(id)==true)return@call
            if(tasks.taskTransformActive(id)||handleFollow.contains(id)||id in portraitResizes)return@call
            // Native finish transactions can restore an earlier crop/position even after
            // TaskInfo acknowledged new bounds. Reconcile once AFTER those transactions.
            if(tasks.portraitBounds(latest)!=latest.bounds){refreshHandle(id);return@call}
            // A task-owned handle needs no private decoration ABI. Leave its native surface
            // unchanged if the ROM's decoration has not been discovered.
            if(decorations[id]?.get()==null||tasks.settleFreeformSurface(latest))settledLayouts[id]=layoutKey(latest)
            handles[id]?.handle?.updateTask(latest)
            handles[id]?.handle?.setLayoutPending(false)
        }}
    }
    private val followQueued=java.util.concurrent.atomic.AtomicBoolean(false)
    private val suppressNativeFullscreen=ThreadLocal<Boolean>()
    private data class DragRange(val task:Int,val bounds:android.graphics.Rect,
        val originals:java.util.IdentityHashMap<android.graphics.Rect,android.graphics.Rect> = java.util.IdentityHashMap())
    private val landscapeDragRange=ThreadLocal<DragRange>()
    private fun saveRange(range:DragRange,rect:android.graphics.Rect){
        if(!range.originals.containsKey(rect))range.originals[rect]=android.graphics.Rect(rect)
    }
    private fun alignMotionBounds(controller:Any,range:DragRange){
        val info=Guard.field(controller,"mTaskMotionInfo") ?: return
        val taskInfo=X.getObjectField(info,"mTaskInfo")
        if(X.getIntField(taskInfo,"taskId")!=range.task)return
        val stable=X.getObjectField(info,"mStableBounds") as android.graphics.Rect
        val safe=X.getObjectField(info,"mSafeBounds") as android.graphics.Rect
        val maximum=X.getObjectField(info,"mMaxBounds") as android.graphics.Rect
        saveRange(range,stable);saveRange(range,safe);saveRange(range,maximum)
        fun source(r:android.graphics.Rect)=LivePlacement.Bounds(r.left,r.top,r.width(),r.height())
        val aligned=LivePlacement.edgeMotionBounds(range.bounds.width(),range.bounds.height(),
            LivePlacement.MotionBounds(source(stable),source(safe),source(maximum)))
        fun apply(r:android.graphics.Rect,b:LivePlacement.Bounds){r.set(b.x,b.y,b.x+b.width,b.y+b.height)}
        apply(stable,aligned.stable);apply(safe,aligned.safe);apply(maximum,aligned.maximum)
    }
    private fun alignPositionerBounds(positioner:Any,range:DragRange){
        val stable=X.getObjectField(positioner,"stableBounds") as android.graphics.Rect
        saveRange(range,stable);stable.set(range.bounds)
        Guard.field(positioner,"taskMotionController")?.let{alignMotionBounds(it,range)}
    }
    private fun followBounds(r:android.graphics.Rect)=LivePlacement.Bounds(r.left,r.top,r.width(),r.height())
    private fun followRect(b:LivePlacement.Bounds)=android.graphics.Rect(b.x,b.y,b.x+b.width,b.y+b.height)
    private fun followHandle(session:HandleFollowState.Session,rect:android.graphics.Rect){
        if(!handleFollow.offer(session,followBounds(rect)))return
        if(followQueued.compareAndSet(false,true))main.post{
            android.view.Choreographer.getInstance().postFrameCallback{
                followQueued.set(false)
                Guard.call("handle-follow"){
                    handleFollow.drain().forEach{frame->
                        if(handleFollow.matches(frame.session))handles[frame.session.task]?.handle?.moveTo(followRect(frame.bounds))
                    }
                }
            }
        }
    }
    private fun refreshHandle(id: Int) {
        updateHandle(id,decorations[id]?.get())
    }
    private fun shellValues(value:Any?):List<Any> = when(value){
        is android.util.SparseArray<*>->(0 until minOf(value.size(),64)).mapNotNull{value.valueAt(it)}
        is Map<*,*>->value.values.filterNotNull().take(64)
        else->emptyList()
    }
    private fun organizerTaskIds(organizer:Any):List<Int> = synchronized(X.getObjectField(organizer,"mLock")){
        val items=X.getObjectField(organizer,"mTasks") as android.util.SparseArray<*>
        (0 until minOf(items.size(),64)).map{items.keyAt(it)}
    }
    private fun discoverDecorations(organizer:Any){
        // Old Samsung Shell can supply the real decoration through its listener/ViewModel,
        // with a class loader unavailable to loadPackage. Read only known Shell ownership maps.
        val listeners=synchronized(X.getObjectField(organizer,"mLock")){
            shellValues(Guard.field(organizer,"mTaskListeners"))
        }
        val found=DecorationDiscovery.collect(listeners,{owner,name->Guard.field(owner,name)},::shellValues,
            {it.javaClass.name.startsWith("com.android.wm.shell.")})
        found.decorations.forEach{captureDecoration(it,"organizer-view-model")}
    }
    private fun captureDecoration(decor:Any,source:String){
        if(android.os.Build.VERSION.SDK_INT<29)return
        if(!decor.javaClass.name.startsWith("com.android.wm.shell."))return
        val info=Guard.field(decor,"taskInfo","mTaskInfo") as? android.app.ActivityManager.RunningTaskInfo ?: return
        if(Guard.field(decor,"decorWindowContext","mDecorWindowContext","userContext","mUserContext","mContext") !is Context)return
        val surface=Guard.field(decor,"taskSurface","mTaskSurface") as? android.view.SurfaceControl ?: return
        if(!surface.isValid||info.taskId<0)return
        installDecorationHooks(decor.javaClass)
        val changed=decorations[info.taskId]?.get()!==decor
        decorations[info.taskId]=WeakReference(decor)
        if(discoveredTypes.add(decor.javaClass.name))Guard.info("window decoration discovered source=$source class=${decor.javaClass.name}")
        Guard.field(decor,"mDragPositioningCallback","dragPositioningCallback")?.let{callback->
            val type=callback.javaClass
            if(type in positionerTypes)return@let
            val probe=RuntimeCompatibilityProbe.create(type.classLoader ?: loader)
            if(CompatibilityPolicy.dragStartSignatures.any{probe.method(type.name,"onDragPositioningStart",it,"android.graphics.Rect")}&&
                probe.field(type.name,"windowDecoration","mWindowDecoration","mDesktopWindowDecoration"))
                installPositionerHandleHooks(type)
        }
        if(backend!=null&&(changed||source!="organizer-view-model"))update(decor)
    }
    private fun installDecorationHooks(type:Class<*>){
        if(!decorationTypes.add(type))return
        for(owner in generateSequence(type){it.superclass}.take(6))for(method in owner.declaredMethods){
            if(method.name !in setOf("relayout","close")||java.lang.reflect.Modifier.isStatic(method.modifiers)||java.lang.reflect.Modifier.isAbstract(method.modifiers))continue
            if(method.name=="close"&&(method.parameterCount!=0||method.returnType!=Void.TYPE))continue
            if(method.name=="relayout"&&method.parameterTypes.none{it.name=="android.app.ActivityManager\$RunningTaskInfo"})continue
            if(!decorationMethods.add(method))continue
            try{XposedBridge.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(method.name!="close")return
                    val decor=p.thisObject
                    main.post{Guard.call("decor-close"){
                        decorations.entries.removeAll{it.value.get()===decor}
                        handles.filterValues{it.decor===decor}.keys.toList().forEach{handles.remove(it)?.handle?.release()}
                    }}
                }
                override fun afterHookedMethod(p:MethodHookParam){
                    if(method.name!="relayout"||p.hasThrowable())return
                    val decor=p.thisObject
                    Guard.call("decor-reassert"){
                        val info=Guard.field(decor,"taskInfo","mTaskInfo") ?: return@call
                        mini?.reassertSurface(X.getIntField(info,"taskId"))
                    }
                    main.post{if(Guard.open("decor"))Guard.call("decor"){captureDecoration(decor,"relayout")}}
                }
            })}catch(error:Throwable){decorationMethods.remove(method);Guard.failure("decor-hook",error)}
        }
    }
    fun install() {
        val banner=Guard.call("headsup-notification-install"){HeadsUpNotificationPullHooks(loader,FeatureConfig("headsup-notification")){shadeState.blocked}.install()}
        Guard.info("notification backends build=${io.github.s24u.popupscale.BuildConfig.VERSION_CODE} brief=false headsUp=$banner")
        // Capture concrete subclasses even when they are absent from our package class loader.
        for(name in listOf("com.android.wm.shell.windowdecor.WindowDecoration","com.android.wm.shell.windowdecor.WindowDecoration2")){
            val base=X.findClassIfExists(name,loader) ?: continue
            XposedBridge.hookAllConstructors(base,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val decor=p.thisObject
                    main.post{Guard.call("decor-constructor"){captureDecoration(decor,"base-constructor")}}
                }
            })
        }
        val focusObserver=X.findClassIfExists("com.android.wm.shell.transition.FocusTransitionObserver",loader)
        if(focusObserver!=null)XposedBridge.hookAllMethods(focusObserver,"updateFocusedTaskPerDisplay",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable()||p.args.getOrNull(1)!=0)return
                Guard.call("handle-focus-owner"){
                    val id=p.args.getOrNull(0)?.let{X.getIntField(it,"taskId")} ?: -1
                    handleFocusTask=id
                    main.post{Guard.call("handle-focus-refresh"){
                        if(handleFocusTask!=id)return@call
                        handles.forEach{(task,entry)->if(task!=id)entry.handle.setOwnerFocused(false)}
                        refreshHandle(id)
                    }}
                }
            }
        })
        for(name in listOf("com.android.wm.shell.windowdecor.WindowDecoration2")+caps.decorations){
            val type=X.findClassIfExists(name,loader) ?: continue
            XposedBridge.hookAllMethods(type,"updateTaskSurface",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    Guard.call("task-transform-native-sync"){
                        if(p.args.size<3)return@call
                        val info=Guard.field(p.thisObject,"taskInfo","mTaskInfo") ?: return@call
                        val id=X.getIntField(info,"taskId")
                        backend?.applyTaskTransform(id,p.args[1],p.args[2])
                    }
                }
            })
        }
        val captionController=X.findClassIfExists("com.android.wm.shell.windowdecor.caption.CaptionController",loader)
        if(captionController!=null)XposedBridge.hookAllMethods(captionController,"updateCaptionContainerSurface",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                Guard.call("caption-transform-pin"){
                    backend?.applyCaptionTransform(p.thisObject,p.args[1])
                }
            }
        })
        nativeTransitions.preservePopup={change,reason->
            val tasks=backend
            val info=X.callMethod(change,"getTaskInfo")
            if(tasks==null||info==null)false else {
                config.reload()
                val component=X.getObjectField(info,"topActivity") as? android.content.ComponentName
                val c=X.getObjectField(info,"configuration")
                val w=X.getObjectField(c,"windowConfiguration")
                HomeTransitionPolicy.preservePopup(config.homeMiniEnabled(component?.packageName)&&!tasks.locked(),
                    reason,tasks.miniTransformActive(X.getIntField(info,"taskId")),
                    X.getIntField(info,"userId"),X.getIntField(info,"displayId"),
                    X.callMethod(w,"getActivityType") as Int,X.callMethod(w,"getWindowingMode") as Int,
                    X.callMethod(change,"getMode") as Int,X.callMethod(change,"getForceHidingTransit") as Int,
                    X.callMethod(change,"getStartAbsBounds")==X.callMethod(change,"getEndAbsBounds"),
                    X.callMethod(change,"getStartRotation")==X.callMethod(change,"getEndRotation"))
            }
        }
        nativeTransitions.pinFinishedPopup={id,tx->backend?.pinMiniFinish(id,tx);Unit}
        nativeTransitions.pinPopupFrame={id,start,finish->backend?.pinMiniFrame(id,start,finish)==true}
        Guard.call("native-transition-install"){nativeTransitions.install()}
        val organizer=X.findClass(RomProfile.ORGANIZER,loader)
        XposedBridge.hookAllConstructors(organizer,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val context=p.args.firstOrNull{it is Context} as? Context ?: return
                main.post { Guard.call("system-init") { initialize(context,p.thisObject) } }
            }
        })
        XposedBridge.hookAllMethods(organizer,"onTaskAppeared",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val pair=Guard.call("task-surface-capture"){
                    val arg=p.args.firstOrNull() ?: return@call null
                    val info=if(arg is android.app.ActivityManager.RunningTaskInfo)arg else X.callMethod(arg,"getTaskInfo")
                    val surface=p.args.getOrNull(1) ?: X.callMethod(arg,"getLeash")
                    X.getIntField(info,"taskId") to surface
                }
                main.post{Guard.call("recovery"){
                    val surface=pair?.second
                    if(pair!=null&&surface!=null)taskSurfaces[pair.first]=WeakReference(surface)
                    frame?.recoverIfNeeded()
                    mini?.recoverIfNeeded()
                    Guard.call("decoration-owner-discovery"){discoverDecorations(p.thisObject)}
                    pair?.first?.let{refreshHandle(it)}
                }}
            }
        })
        XposedBridge.hookAllMethods(organizer,"onTaskInfoChanged",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val info=p.args[0]
                val id=Guard.call("task-change"){X.getIntField(info,"taskId")} ?: return
                Guard.call("handle-focus-info"){
                    // Simple caption taps can change focus without starting a Shell transition.
                    // Consume fresh focus events here too, never the watchdog's cached flags.
                    if(X.getIntField(info,"displayId")==0){
                        if(X.getBooleanField(info,"isFocused"))handleFocusTask=id
                        else if(handleFocusTask==id)handleFocusTask=-1
                    }
                }
                mini?.reassertSurface(id)
                main.post{Guard.call("live-task-change"){
                    // A hidden/fullscreen task may have no decoration left to refresh.
                    // Still record this boundary so its next popup gets a fresh layout.
                    backend?.record(info)
                    Guard.call("opacity-task-update"){opacity?.refresh()}
                    Guard.call("decoration-owner-discovery"){discoverDecorations(p.thisObject)}
                    mini?.onTaskChanged(id);refreshHandle(id)
                }}
            }
        })
        XposedBridge.hookAllMethods(organizer,"onTaskVanished",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val info=p.args.firstOrNull() ?: return
                val id=Guard.call("task-vanished"){X.getIntField(info,"taskId")} ?: return
                main.post{Guard.call("task-vanished"){
                    opacity?.forget(id);backend?.forgetTaskTransforms(id);taskSurfaces.remove(id);decorations.remove(id);handleFollow.forget(id);settledLayouts.remove(id);settlingLayouts.remove(id);handles.remove(id)?.handle?.release();mini?.onTaskVanished(id)
                }}
            }
        })
        for(name in caps.decorations){
            val type=X.findClassIfExists(name,loader) ?: continue
            installDecorationHooks(type)
        }
        val motion=X.findClassIfExists(RomProfile.MOTION_CONTROLLER,loader)
        if(motion!=null&&caps.stash){
            XposedBridge.hookAllMethods(motion,"rebuildFlingConfigs",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    val range=landscapeDragRange.get() ?: return
                    Guard.call("landscape-release-range"){alignMotionBounds(p.thisObject,range)}
                }
            })
            XposedBridge.hookAllMethods(motion,"setStashDim",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(p.args.getOrNull(1)!=true)return
                    Guard.call("freeform-stash-dim"){
                        if(eligibleStashTask(p.thisObject)==null)return@call
                        val stash=X.getObjectField(p.thisObject,"mFreeformStashState")
                        X.callMethod(stash,"setStashed",0)
                        X.callMethod(stash,"destroyStashDimOverlay")
                        p.result=null
                    }
                }
            })
            XposedBridge.hookAllMethods(motion,"moveToTarget",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(p.args.getOrNull(2)!=true)return
                    Guard.call("freeform-stash-move"){
                        val record=eligibleStashTask(p.thisObject) ?: return@call
                        val targetBounds=p.args.firstOrNull() as? android.graphics.Rect ?: return@call
                        val draggedBounds=android.graphics.Rect(targetBounds)
                        val stash=X.getObjectField(p.thisObject,"mFreeformStashState")
                        val beforeStash=android.graphics.Rect(X.getObjectField(stash,"mLastFreeformBoundsBeforeStash") as android.graphics.Rect)
                        val touchState=X.getObjectField(p.thisObject,"mFreeformCaptionTouchState")
                        val vx=(touchState?.let{Guard.field(it,"mVelocity")} as? android.graphics.PointF)?.x ?: 0f
                        X.setBooleanField(p.thisObject,"mAllowTouches",true)
                        X.callMethod(stash,"setStashed",0)
                        X.setBooleanField(stash,"mAnimating",false)
                        X.setFloatField(stash,"mScale",1f)
                        X.callMethod(stash,"destroyStashDimOverlay")
                        if(!beforeStash.isEmpty)targetBounds.set(beforeStash)
                        p.result=null
                        mini?.pinFreeformStash(record,draggedBounds)
                        Looper.myLooper()?.let{looper->
                            val h=Handler(looper)
                            h.postAtFrontOfQueue{Guard.call("freeform-stash-reassert"){mini?.reassertSurface(record.id)}}
                            h.post{Guard.call("freeform-stash-reassert"){mini?.reassertSurface(record.id)}}
                        }
                        main.post{Guard.call("freeform-stash-dock"){
                            val tasks=backend ?: return@call
                            val coordinator=mini ?: return@call
                            val sw=GestureDisplay.metrics(tasks.context).widthPixels
                            val left=MiniDragPolicy.stashLeft(draggedBounds.left,draggedBounds.right,sw,vx)
                            handles.remove(record.id)?.handle?.release()
                            val latest=tasks.info(record.id)?.let{tasks.record(it)} ?: record
                            coordinator.dockFromFreeform(latest,draggedBounds,left)
                        }}
                    }
                }
            })
        }
        for(positionerName in caps.positioners){
            val positioner=X.findClass(positionerName,loader)
            installPositionerHandleHooks(positioner)
            if(positionerName!=RomProfile.TASK_POSITIONER||!caps.samsungBoundary)continue
            // The MOVE path mutates both the preview rectangle and the eventual bounds.
            // Suppressing only END leaves a fullscreen guide and a fullscreen-sized task.
            for(method in listOf("onDragPositioningStart","onDragPositioningMove","onDragPositioningEnd","onDragPositioningCancel")){
                XposedBridge.hookAllMethods(positioner,method,object:XC_MethodHook(10000){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        p.setObjectExtra("popupscale.boundary-scope",suppressNativeFullscreen.get())
                        p.setObjectExtra("popupscale.drag-range-scope",landscapeDragRange.get())
                        Guard.call("resize-fullscreen-boundary"){
                            val task=eligibleBoundaryResizeTask(p.thisObject) ?: return@call
                            suppressNativeFullscreen.set(true)
                            val tasks=backend ?: return@call
                            val m=GestureDisplay.metrics(tasks.context)
                            if(method in setOf("onDragPositioningMove","onDragPositioningEnd")&&
                                m.widthPixels>m.heightPixels&&X.callMethod(p.thisObject,"isImeSourceVisible")!=true){
                                val range=DragRange(task.id,android.graphics.Rect(0,0,m.widthPixels,m.heightPixels))
                                landscapeDragRange.set(range)
                                p.setObjectExtra("popupscale.active-drag-range",range)
                                alignPositionerBounds(p.thisObject,range)
                            }
                        }
                    }
                    override fun afterHookedMethod(p:MethodHookParam){
                        // Physics configs copy the limits during END. Restore shared native
                        // rectangles afterward so keyboard/portrait/other tasks retain theirs.
                        (p.getObjectExtra("popupscale.active-drag-range") as? DragRange)?.let{
                            Guard.call("landscape-drag-range-restore"){
                                it.originals.forEach{(target,original)->target.set(original)}
                            }
                        }
                        val previous=p.getObjectExtra("popupscale.boundary-scope") as? Boolean
                        if(previous==null)suppressNativeFullscreen.remove() else suppressNativeFullscreen.set(previous)
                        val oldRange=p.getObjectExtra("popupscale.drag-range-scope") as? DragRange
                        if(oldRange==null)landscapeDragRange.remove() else landscapeDragRange.set(oldRange)
                    }
                })
            }
            val guide=X.findClassIfExists(RomProfile.RESIZE_GUIDE,loader)
            if(guide!=null)for(method in listOf("snapToFullscreenIfNeeded","needToFullscreenTransition")){
                XposedBridge.hookAllMethods(guide,method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        if(suppressNativeFullscreen.get()!=true)return
                        Guard.call("resize-fullscreen-guide"){
                            X.setBooleanField(p.thisObject,"mNeedToFullscreenTransition",false)
                            p.result=false
                        }
                    }
                })
            }
            XposedBridge.hookAllMethods(positioner,"onDragPositioningEnd",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    Guard.call("freeform-resize-mini"){
                        val self=p.thisObject
                        val resizing=if(X.findMethodExactIfExists(self.javaClass,"isResizing")!=null)X.callMethod(self,"isResizing")==true
                        else ((Guard.field(self,"ctrlType","mCtrlType") as? Int ?: 0)!=0)||
                            (Guard.field(self,"isResizingOrAnimatingResize","mIsResizingOrAnimatingResize") as? Boolean==true)
                        if(!resizing)return@call
                        val guide=Guard.field(self,"freeformResizeGuide","mFreeformResizeGuide") ?: return@call
                        if(X.callMethod(guide,"canResizeGesture")!=true||X.callMethod(guide,"readyToMinimize")!=true)return@call
                        val record=eligibleResizeMiniTask(self) ?: return@call
                        val ctrlType=X.getIntField(self,"ctrlType")
                        val gestureBounds=android.graphics.Rect(X.getObjectField(self,"repositionTaskBounds") as android.graphics.Rect)
                        val startBounds=X.getObjectField(self,"taskBoundsAtDragStart") as android.graphics.Rect
                        val targetBounds=X.getObjectField(self,"repositionTaskBounds") as android.graphics.Rect
                        if(!startBounds.isEmpty)targetBounds.set(startBounds)
                        val decor=positionerDecor(self)
                        if(decor!=null&&X.getBooleanField(self,"isResizingOrAnimatingResize")){
                            if(X.findMethodExactIfExists(decor.javaClass,"hideResizeVeil")!=null)X.callMethod(decor,"hideResizeVeil")
                            X.setBooleanField(self,"isResizingOrAnimatingResize",false)
                        }
                        main.post{Guard.call("freeform-resize-mini-shrink"){
                            val tasks=backend ?: return@call
                            val coordinator=mini ?: return@call
                            val sw=GestureDisplay.metrics(tasks.context).widthPixels
                            val box=MiniDragPolicy.Box(gestureBounds.left,gestureBounds.top,
                                gestureBounds.width().coerceAtLeast(1),gestureBounds.height().coerceAtLeast(1))
                            val left=MiniDragPolicy.resizeHangLeft(ctrlType,box,sw)
                            handles.remove(record.id)?.handle?.release()
                            val latest=tasks.info(record.id)?.let{tasks.record(it)} ?: record
                            coordinator.shrinkFromFreeformResize(latest,left)
                        }}
                    }
                }
            })
        }
        for(name in listOf(
            "com.android.systemui.shade.NotificationShadeWindowControllerImpl",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl"
        )){
            val shadeController = X.findClassIfExists(name, loader) ?: continue
            XposedBridge.hookAllMethods(shadeController, "setPanelExpanded", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val expanded = p.args.firstOrNull() as? Boolean ?: return
                    main.post{shadeSignal(ShadeInteractionState.Signal.EXPANDED,expanded)}
                }
            })
            XposedBridge.hookAllMethods(shadeController, "setNotificationShadeFocusable", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val focusable = p.args.firstOrNull() as? Boolean ?: return
                    main.post{shadeSignal(ShadeInteractionState.Signal.FOCUSABLE,focusable)}
                }
            })
        }
        for(name in listOf(
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.NotificationPanelViewController"
        )){
            val panelController = X.findClassIfExists(name, loader) ?: continue
            XposedBridge.hookAllConstructors(panelController,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val controller=p.thisObject
                    main.post{Guard.call("shade-panel-capture"){reconcileShadePanel(controller,"constructor")}}
                }
            })
            val heightMethods=generateSequence(panelController){it.superclass}.take(5).flatMap{it.declaredMethods.asSequence()}
                .filter{it.name in setOf("setExpandedFraction","setExpandedHeight","setExpandedHeightInternal","onHeightUpdated")&&
                    !java.lang.reflect.Modifier.isAbstract(it.modifiers)&&!java.lang.reflect.Modifier.isStatic(it.modifiers)}.distinct().toList()
            for(method in heightMethods)Guard.call("shade-height-hook"){
                XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun afterHookedMethod(p:MethodHookParam){if(!p.hasThrowable())observeShadePanel(p.thisObject)}
                })
            }
            XposedBridge.hookAllMethods(panelController, "notifyExpandingStarted", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val controller=p.thisObject
                    main.post{shadePanel=WeakReference(controller);shadeSignal(ShadeInteractionState.Signal.EXPANDING,true)}
                }
            })
            XposedBridge.hookAllMethods(panelController, "notifyExpandingFinished", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val controller=p.thisObject
                    main.post{Guard.call("shade-panel-finished"){
                        shadeSignal(ShadeInteractionState.Signal.EXPANDING,false)
                        reconcileShadePanel(controller,"finished")
                    }}
                }
            })
        }
        Guard.info("Samsung capability profile installed decorations=${caps.decorations.joinToString()} positioners=${caps.positioners.joinToString()}")
    }
    private fun initialize(context:Context,organizer:Any){
        if(android.os.Build.VERSION.SDK_INT<34)return
        if(registered)return
        val tasks=TaskBackend(context,organizer,loader,config,nativeTransitions){id->taskSurfaces[id]?.get()}.apply {
            decorationProvider={id->decorations[id]?.get()}
        }
        val overlay=TransitionFrameOverlay(context,tasks)
        frame=overlay;backend=tasks;mini=MiniCoordinator(context,tasks,config,overlay){id->main.post{Guard.call("restore-handle"){settledLayouts.remove(id);refreshHandle(id)}}}
        Guard.call("frameless-caption-install"){FramelessCaptionHooks(loader,config,tasks).install(caps.decorations)}
        Guard.call("opacity-install"){
            val controller=TaskOpacityController(context,tasks,config,loader)
            if(controller.install())opacity=controller
        }
        applyShadeState()
        Guard.call("decoration-owner-discovery"){discoverDecorations(organizer)}
        val receiver=object:BroadcastReceiver(){
            override fun onReceive(c:Context,intent:Intent){
                if(intent.action==STATUS){
                    Guard.call("system-status"){
                        config.reload()
                        Guard.info("system status backend=true enabled=${config.enabled} observe=${config.observe} " +
                            "handleCount=${handles.size} nativeTransitions=${nativeTransitions.ready()} miniTask=${mini?.activeTaskId()} ${mini?.status()}")
                    }
                    return
                }
                if(intent.action==MiniTaskLease.HOME_HANG){
                    Guard.call("home-mini-request"){
                        if(sentFromUid!=android.os.Process.SYSTEM_UID)return@call
                        val token=intent.getStringExtra("token")?.takeIf{it.length in 20..80} ?: return@call
                        if(SystemClock.uptimeMillis()-intent.getLongExtra("when",-10000) !in 0..3000)return@call
                        val ids=intent.getIntArrayExtra("tasks")?.filter{it>=0}?.distinct()?.take(64) ?: return@call
                        val deadline=intent.getLongExtra("when",-10000)+2000
                        fun reply(accepted:List<Int>){
                            context.sendBroadcast(Intent(MiniTaskLease.HOME_REPLY).setPackage("android")
                                .putExtra("tasks",accepted.toIntArray()).putExtra("token",token)
                                .putExtra("when",SystemClock.uptimeMillis()),MiniTaskLease.PERMISSION,
                                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                        }
                        tasks.whenHomeTasksReady(ids,deadline){idle->Guard.call("home-mini-idle"){
                            config.reload()
                            if(!idle||SystemClock.uptimeMillis()>deadline||!config.enabled||config.observe||!config.live||!config.hang||tasks.locked()){
                                reply(emptyList());return@call
                            }
                            val accepted=mini?.hangForHome(ids) ?: emptyList()
                            accepted.forEach{id->handles.remove(id)?.handle?.release()}
                            reply(accepted)
                        }}
                    }
                    return
                }
                if(intent.action==MiniTaskLease.CONTENT_CHANGED){
                    Guard.call("player-content-change"){
                        if(sentFromUid!=android.os.Process.SYSTEM_UID)return@call
                        if(SystemClock.uptimeMillis()-intent.getLongExtra("when",-10000) !in 0..3000)return@call
                        config.reload()
                        if(!config.enabled||config.observe||tasks.locked())return@call
                        val id=intent.getIntExtra("task",-1)
                        val task=tasks.info(id)?.let{tasks.record(it)} ?: return@call
                        if(task.mode!=5||!task.visible)return@call
                        tasks.loadContentProfile(task,force=true){
                            main.post{Guard.call("player-content-layout"){refreshHandle(id);mini?.onTaskChanged(id)}}
                        }
                    }
                    return
                }
                Guard.call("hang-request"){
                    if(!Guard.open("hang-request"))return@call
                    config.reload()
                    if(!config.enabled||config.observe||!config.hang||tasks.locked())return@call
                    if(android.os.Build.VERSION.SDK_INT>=34){
                        if(sentFromUid!=context.packageManager.getApplicationInfo(RomProfile.LAUNCHER,0).uid)return@call
                    }else return@call
                    val age=SystemClock.uptimeMillis()-intent.getLongExtra("when",-10000)
                    if(age !in 0..3000)return@call
                    if(intent.action==CANCEL_FRAME){overlay.cancel(intent.getStringExtra("frame").orEmpty());return@call}
                    if(intent.action==PREPARE_FRAME){
                        if(!config.live)return@call
                        val reply=intent.getParcelableExtra("reply",android.os.ResultReceiver::class.java)?:return@call
                        val bounds=intent.getParcelableExtra("rect",android.graphics.Rect::class.java)?:return@call
                        val info=tasks.info(intent.getIntExtra("task",-1))?:return@call
                        val record=tasks.record(info)?:return@call
                        mini?.prepareFrame(record,bounds,intent.getStringExtra("frame").orEmpty(),reply)
                        return@call
                    }
                    if(config.live&&!intent.getBooleanExtra("homeReady",false)){
                        Guard.info("live hang skipped: launcher HOME readiness missing");return@call
                    }
                    val info=tasks.info(intent.getIntExtra("task",-1)) ?: return@call
                    val record=tasks.record(info) ?: return@call
                    mini?.hang(record,intent.getStringExtra("frame").orEmpty())
                }
            }
        }
        context.registerReceiver(receiver,IntentFilter(ACTION).apply{addAction(STATUS);addAction(PREPARE_FRAME);addAction(CANCEL_FRAME);addAction(MiniTaskLease.CONTENT_CHANGED);addAction(MiniTaskLease.HOME_HANG)},"android.permission.MANAGE_ACTIVITY_TASKS",main,
            Context.RECEIVER_EXPORTED)
        context.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(c:Context,intent:Intent){Guard.call("screen-state"){
                if(intent.action==Intent.ACTION_SCREEN_OFF){
                    opacity?.closePanel()
                    mini?.hideForLock();handles.values.toList().forEach{it.handle.release()};handles.clear()
                }else if(intent.action==Intent.ACTION_CLOSE_SYSTEM_DIALOGS){
                    applyShadeState()
                }else{
                    mini?.screenOn()
                    if(intent.action==Intent.ACTION_USER_UNLOCKED){
                        config.invalidateProvider()
                        syncSettingsFromProvider(context,force=true)
                    }
                }
            }}
        },IntentFilter().apply{addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);
            addAction(Intent.ACTION_USER_PRESENT);addAction(Intent.ACTION_USER_UNLOCKED);addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)},Context.RECEIVER_NOT_EXPORTED)
        runCatching {
            syncSettingsFromProvider(context, force = true)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    Guard.call("systemui-settings-changed") {
                        intent.getBundleExtra("settings")?.let { config.applyBundle(it) }
                        config.invalidateProvider(); syncSettingsFromProvider(context, force = true)
                        Guard.info("systemui settings live updated via broadcast enabled=${config.enabled}")
                    }
                }
            }, IntentFilter(io.github.s24u.popupscale.ModuleSettings.ACTION_SETTINGS_CHANGED), null, main, Context.RECEIVER_EXPORTED)
        }
        registered=true
        Guard.call("display-listener-install"){context.getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(
            object:android.hardware.display.DisplayManager.DisplayListener{
                override fun onDisplayAdded(id:Int){}
                override fun onDisplayRemoved(id:Int){}
                override fun onDisplayChanged(id:Int){
                    if(id!=0)return
                    Guard.call("display-handle-layout"){
                        mini?.onDisplayChanged()
                        decorations.keys.toList().forEach{taskId->refreshHandle(taskId)}
                    }
                }
            },main)}
        config.reload()
        Guard.info("system backend ready enabled=${config.enabled} observe=${config.observe}")
        main.post(object:Runnable{
            override fun run(){
                Guard.call("system-watchdog"){
                    val now=SystemClock.uptimeMillis()
                    if(now-lastReadiness>=5000){
                        lastReadiness=now
                        Guard.call("system-readiness"){SystemUiReadiness.announce(context,Guard.open("live-animation")&&Guard.open("native-restore"))}
                    }
                    shadePanel?.get()?.let{Guard.call("shade-panel-reconcile"){reconcileShadePanel(it,"watchdog")}}
                    config.reload()
                    if(config.needsProviderSync())syncSettingsFromProvider(context)
                    frame?.recoverIfNeeded();mini?.recoverIfNeeded()
                    Guard.call("opacity-watchdog"){opacity?.refresh()}
                    if(config.enabled&&config.handles&&!config.observe&&Guard.open("decor")){
                        Guard.call("decoration-owner-discovery"){discoverDecorations(organizer)}
                    }
                    handles.keys.toList().forEach { id ->
                        val entry=handles[id] ?: return@forEach
                        val latest=tasks.info(id)?.let{tasks.record(it)}?.let{handleRecord(it)}
                        if(!config.handles||latest==null||!latest.visible||latest.mode!=5||
                            mini?.isMini(id)==true){
                            handles.remove(id)?.handle?.release()
                        }else if(handleFollow.contains(id)||!latest.focused){
                            entry.handle.updateTask(latest,handleFollow.current(id)?.let{followRect(it)})
                            handles[id]=Entry(entry.decor,latest,entry.handle)
                        }else if(tasks.rotationLayoutPending(id)||settledLayouts[id]!=layoutKey(latest)){
                            refreshHandle(id)
                        }else{
                            entry.handle.updateTask(latest)
                            handles[id]=Entry(entry.decor,latest,entry.handle)
                        }
                    }
                    if(config.enabled && config.handles && !config.observe && !tasks.locked() && Guard.open("decor")){
                        // Task callbacks/leashes are available even on legacy ROMs whose
                        // decoration class/relayout never passes through our static candidates.
                        val missing=organizerTaskIds(organizer).filter{it !in handles&&mini?.isMini(it)!=true}
                        missing.forEach{refreshHandle(it)}
                    }
                    if(tasks.locked())overlay.clear()
                    mini?.recoverIfNeeded();mini?.lockVisibility();mini?.tick()
                    if(!config.enabled||config.observe||!Guard.open("decor")||!Guard.open("handle-touch")||
                        !Guard.open("mini-animation")||!Guard.open("live-animation")||!Guard.open("hang-request")||
                        !Guard.open("freeform-stash-dock")||!Guard.open("freeform-resize-mini-shrink")){
                        handles.values.toList().forEach{it.handle.release()};handles.clear()
                        mini?.restoreImmediately()
                    }else if(tasks.locked()){
                        handles.values.toList().forEach{it.handle.release()};handles.clear()
                    }
                }
                main.postDelayed(this,1000)
            }
        })
    }
    private fun installPositionerHandleHooks(type: Class<*>) {
        if(!positionerTypes.add(type))return
        // Shell-thread gesture state. Never call native MOVE/END for a suppressed START.
        val blocked=java.util.WeakHashMap<Any, android.graphics.Rect>()
        val tracking=java.util.WeakHashMap<Any, HandleFollowState.Session>()
        XposedBridge.hookAllMethods(type,"onDragPositioningStart",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                Guard.call("handle-drag-start"){
                    val self=p.thisObject
                    blocked.remove(self); tracking.remove(self)?.let{handleFollow.discard(it)}
                    val tasks=backend ?: return@call
                    config.reload()
                    if(!config.enabled || config.observe || !config.handles || tasks.locked())return@call
                    val wrapper=positionerDecor(self) ?: return@call
                    val task=tasks.record(decorationInfo(wrapper) ?: return@call) ?: return@call
                    if(task.mode!=5 || !task.visible || mini?.isMini(task.id)==true)return@call
                    val start=CompatibilityPolicy.dragStart(p.args) ?: return@call
                    val mask=start.mask;val x=start.x;val y=start.y
                    // Touching another window's caption can precede its focus acknowledgement.
                    // Track that window immediately; never reuse the previous focused task's drag.
                    if(mask==0){tracking[self]=handleFollow.begin(task.id,followBounds(task.bounds));return@call}
                    if(!task.focused)return@call
                    val b=task.bounds
                    val direction=GesturePolicy.bottomResizeDirection(mask,x-b.left,y-b.top,
                        b.width(),b.height(),tasks.context.resources.displayMetrics.density)
                    if(direction==null){
                        blocked[self]=android.graphics.Rect(b)
                        p.result=android.graphics.Rect(b)
                    }else {p.args[start.maskIndex]=direction;tracking[self]=handleFollow.begin(task.id,followBounds(task.bounds))}
                }
            }
        })
        for(name in listOf("onDragPositioningMove","onDragPositioningEnd","onDragPositioningCancel")){
            XposedBridge.hookAllMethods(type,name,object:XC_MethodHook(100){
                override fun beforeHookedMethod(p:MethodHookParam){
                    val b=blocked[p.thisObject] ?: return
                    if(name!="onDragPositioningMove")blocked.remove(p.thisObject)
                    p.result=if(name=="onDragPositioningCancel")null else android.graphics.Rect(b)
                }
                override fun afterHookedMethod(p:MethodHookParam){
                    val session=tracking[p.thisObject] ?: return
                    if(name=="onDragPositioningMove"){
                        val rect=(p.result as? android.graphics.Rect)?.let{android.graphics.Rect(it)} ?: return
                        if(rect.isEmpty)return
                        Guard.call("handle-follow"){followHandle(session,rect)}
                    }else{
                        tracking.remove(p.thisObject)
                        val finalBounds=(p.result as? android.graphics.Rect)?.let{followBounds(it)}
                        if(!handleFollow.end(session,finalBounds,name=="onDragPositioningCancel"))return
                        handleFollow.stats(session)?.let{Guard.info("handle caption follow task=${session.task} samples=${it.samples} frames=${it.frames}")}
                        main.post { Guard.call("handle-drag-end"){
                            if(!handleFollow.matches(session))return@call
                            refreshHandle(session.task)
                            // Keep the final preview until TaskInfo acknowledges it. A late END
                            // cannot clear a new drag, and timeout only releases this generation.
                            main.postDelayed({Guard.call("handle-drag-final"){
                                if(handleFollow.finish(session))refreshHandle(session.task)
                            }},350)
                        } }
                    }
                }
            })
        }
    }
    private fun eligibleStashTask(controller:Any):TaskRecord? {
        val tasks=backend ?: return null
        val coordinator=mini ?: return null
        if(!Guard.open("freeform-stash-dim")||!Guard.open("freeform-stash-move")||!Guard.open("freeform-stash-dock")||
            !Guard.open("live-animation"))return null
        config.reload()
        if(!config.enabled||config.observe||!config.live||tasks.locked()||!tasks.canRestore()||coordinator.busy())return null
        val stash=X.getObjectField(controller,"mFreeformStashState") ?: return null
        if(X.callMethod(stash,"isStashed")==true)return null
        val decor=X.getObjectField(controller,"mWindowDecoration") ?: return null
        val info=decorationInfo(decor) ?: return null
        val m=GestureDisplay.metrics(tasks.context)
        return tasks.record(info)?.takeIf{
            it.mode==5&&it.visible&&!it.bounds.isEmpty&&
                it.bounds.left>=0&&it.bounds.top>=0&&it.bounds.right<=m.widthPixels&&it.bounds.bottom<=m.heightPixels
        }
    }
    private fun positionerDecor(owner:Any)=Guard.field(owner,"windowDecoration","mWindowDecoration","mDesktopWindowDecoration")
    private fun decorationInfo(decor:Any):Any?=
        (if(X.findMethodExactIfExists(decor.javaClass,"getTaskInfo")!=null)X.callMethod(decor,"getTaskInfo") else null)
            ?: Guard.field(decor,"taskInfo","mTaskInfo")
    private fun eligibleBoundaryResizeTask(positioner:Any):TaskRecord? {
        val tasks=backend ?: return null
        config.reload()
        if(!config.enabled||config.observe||tasks.locked())return null
        val decor=positionerDecor(positioner) ?: return null
        val info=decorationInfo(decor) ?: return null
        return tasks.record(info)?.takeIf{it.mode==5&&it.visible&&mini?.isMini(it.id)!=true}
    }
    private fun eligibleResizeMiniTask(positioner:Any):TaskRecord? {
        val tasks=backend ?: return null
        val coordinator=mini ?: return null
        if(!Guard.open("freeform-resize-mini")||!Guard.open("freeform-resize-mini-shrink")||
            !Guard.open("live-animation"))return null
        config.reload()
        if(!config.enabled||config.observe||!config.live||tasks.locked()||!tasks.canRestore()||coordinator.busy())return null
        val decor=positionerDecor(positioner) ?: return null
        val stash=if(X.findMethodExactIfExists(decor.javaClass,"getFreeformStashState")!=null)X.callMethod(decor,"getFreeformStashState")
            else Guard.field(decor,"freeformStashState","mFreeformStashState")
        if(stash!=null&&X.callMethod(stash,"isStashed")==true)return null
        val info=decorationInfo(decor) ?: return null
        val m=GestureDisplay.metrics(tasks.context)
        return tasks.record(info)?.takeIf{
            it.mode==5&&it.visible&&!it.bounds.isEmpty&&
                it.bounds.left>=0&&it.bounds.top>=0&&it.bounds.right<=m.widthPixels&&it.bounds.bottom<=m.heightPixels
        }
    }
    private fun update(decor:Any){
        val info=Guard.field(decor,"taskInfo","mTaskInfo") ?: return
        val id=X.getIntField(info,"taskId")
        decorations[id]=WeakReference(decor)
        // Do not overwrite organizer leashes with decoration-owned copies: close() releases them.
        mini?.onTaskChanged(id)
        if(!observedDecor){
            observedDecor=true
            Guard.info("decoration observed class=${decor.javaClass.name} observe=${config.observe}")
        }
        updateHandle(id,decor)
    }
    private fun updateHandle(id:Int,decor:Any?){
        val tasks=backend ?: return
        config.reload()
        if(!config.enabled||config.observe||!config.handles){handles.remove(id)?.handle?.release();return}
        // Decoration callbacks can be queued after focus moved to another task. Read current
        // organizer ownership instead of letting a stale decor snapshot show a second handle.
        val task=tasks.info(id)?.let{tasks.record(it)}?.let{handleRecord(it)}
        val ordinaryVisible=task!=null&&task.mode==5&&task.visible&&mini?.isMini(id)!=true&&!tasks.locked()
        if(!ordinaryVisible){handles.remove(id)?.handle?.release();return}
        task!!
        // Drop gesture transforms from the previous display space before normalization.
        val positionOnly=handleFollow.positionOnly(id)
        if(handleFollow.acknowledge(id,followBounds(task.bounds))&&positionOnly&&
            settledLayouts.containsKey(id)&&!tasks.taskTransformActive(id)&&!tasks.rotationLayoutPending(id)){
            // A pure caption move cannot change crop/size. Keep its already-settled host
            // visible instead of hiding it again behind another native idle rendezvous.
            settledLayouts[id]=layoutKey(task)
        }
        handles[id]?.handle?.updateTask(task,handleFollow.current(id)?.let{followRect(it)})
        if(!tasks.contentProfileReady(task)){
            tasks.loadContentProfile(task){main.post{Guard.call("content-profile-decor"){refreshHandle(id)}}}
            return
        }
        val portrait=tasks.portraitBounds(task)
        if(portrait!=task.bounds&&!tasks.taskTransformActive(id)&&!handleFollow.contains(id)){
            if(portraitResizes.add(id)){
                handles[id]?.handle?.setLayoutPending(true)
                tasks.whenIdle{idle->Guard.call("portrait-freeform-idle"){
                    val latest=tasks.info(id)?.let{tasks.record(it)}
                    config.reload()
                    if(!idle||!config.enabled||config.observe||tasks.locked()||latest==null||latest.mode!=5||
                        !latest.visible||mini?.isMini(id)==true||tasks.taskTransformActive(id)||handleFollow.contains(id)){
                        portraitResizes.remove(id);return@call
                    }
                    val destination=tasks.portraitBounds(latest)
                    fun finished(ok:Boolean){
                        portraitResizes.remove(id);settledLayouts.remove(id)
                        Guard.info("portrait freeform relayout task=$id success=$ok afterIdle=true")
                        if(ok)refreshHandle(id) else handles[id]?.handle?.setLayoutPending(false)
                    }
                    if(destination==latest.bounds)finished(true)
                    else if(!tasks.resizeFreeform(latest,destination){ok->Guard.call("portrait-freeform-reflow"){finished(ok)}})finished(false)
                }}
            }
            return
        }
        if(!task.focused)return // Keep this task's hidden host for fast, correctly bound focus return.
        handles.filterKeys{it!=id}.values.forEach{it.handle.setOwnerFocused(false)}
        Guard.call("caption-native-normalize"){tasks.normalizeCaptionIfUnowned(id)}
        val old=handles[id]
        if(old!=null&&old.decor===decor){
            handles[id]=Entry(decor,task,old.handle)
            if(!tasks.taskTransformActive(id)&&!handleFollow.contains(id)&&id !in portraitResizes)settleLayout(task)
            return
        }
        old?.handle?.release();handles.remove(id)
        val context=decor?.let{Guard.field(it,"decorWindowContext","mDecorWindowContext","userContext","mUserContext","mContext") as? Context}
            ?: tasks.context
        val surface=tasks.surface(id) ?: return
        val handle=HandleBarInjector(context,task,surface,tasks,loader){target,startRect,left->
            val coordinator=mini ?: return@HandleBarInjector false
            if(!Guard.open("live-animation")||!Guard.open("freeform-resize-mini-shrink"))return@HandleBarInjector false
            val ok=coordinator.shrinkFromFreeformResize(target,left,startRect)
            if(ok)main.post{Guard.call("handle-hang-release"){handles.remove(target.id)?.handle?.release()}}
            ok
        }
        try{
            handle.setShadeObscured(shadeState.blocked)
            handle.attach();handles[id]=Entry(decor,task,handle)
            handleFollow.current(id)?.let{handle.moveTo(followRect(it))}
            if(!handleFollow.contains(id))settleLayout(task)
        }catch(error:Throwable){handle.release();throw error}
    }
    companion object{
        const val ACTION="io.github.s24u.popupscale.action.HANG_TASK"
        const val STATUS="io.github.s24u.popupscale.action.GESTURE_STATUS"
        const val PREPARE_FRAME="io.github.s24u.popupscale.action.PREPARE_FRAME"
        const val CANCEL_FRAME="io.github.s24u.popupscale.action.CANCEL_FRAME"
    }
}
