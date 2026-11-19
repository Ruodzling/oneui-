package io.github.s24u.popupscale.gestures

/** These are interface matches, not a claim of successful testing on every Samsung firmware. */
class RomCapabilities(val role:String,loader:ClassLoader) {
    data class Feature(val key:String,val title:String,val available:Boolean,val missing:List<String>)
    private val probe=RuntimeCompatibilityProbe.create(loader)
    val features=mutableListOf<Feature>()
    private fun feature(key:String,title:String,vararg checks:Pair<String,Boolean>):Boolean {
        val missing=checks.filterNot{it.second}.map{it.first}
        features.add(Feature(key,title,missing.isEmpty(),missing));return missing.isEmpty()
    }
    private fun cls(name:String)=probe.type(name)!=null
    private fun method(owner:String,name:String,vararg params:String)=probe.method(owner,name,params.toList())
    private fun field(owner:String,vararg names:String)=probe.field(owner,*names)
    private val ar="com.android.server.wm.ActivityRecord"
    private val task="com.android.server.wm.Task"
    private val atm="com.android.server.wm.ActivityTaskManagerService"
    private val transitions="com.android.wm.shell.transition.Transitions"
    private val queue="com.android.wm.shell.common.SyncTransactionQueue"
    private val wct="android.window.WindowContainerTransaction"
    private val config="android.content.res.Configuration"
    val serverScale:Boolean
    val samsungFramework:Boolean
    val serverLease:Boolean
    val serverCore:String?
    val minimizeAllMethod:java.lang.reflect.Method?
    val serverEntry:Boolean
    val densityMethod:java.lang.reflect.Method?
    val modeMethod:java.lang.reflect.Method?
    val popupChangeMethod:java.lang.reflect.Method?
    val popupChangeBounds:Boolean
    val cornerGesture:Boolean
    val notificationSendMethod:java.lang.reflect.Method?
    val notificationStartMethod:java.lang.reflect.Method?
    val notificationRoute:Boolean
    val systemUi:Boolean
    val decorations:List<String>
    val positioners:List<String>
    val samsungBoundary:Boolean
    val stash:Boolean
    val consumer:String?
    val launcher:Boolean
    val capture:String?
    init {
        samsungFramework=feature("samsung","三星系统框架","CoreRune" to cls("com.samsung.android.rune.CoreRune"))
        densityMethod=listOf(listOf("boolean"),listOf("int","boolean","boolean"),listOf("int","boolean"))
            .firstNotNullOfOrNull{probe.exact(ar,"ensureActivityConfiguration",it,"boolean")}
        modeMethod=listOf(listOf("int","boolean"),listOf("int"))
            .firstNotNullOfOrNull{probe.exact(task,"setWindowingModeInner",it,"void")}
            ?:probe.exact(task,"setWindowingMode",listOf("int"),"void")
        popupChangeMethod=probe.exact("com.android.server.wm.WindowOrganizerController","applyTaskChanges",
            listOf(task,wct+"\$Change"),"int")
            ?:probe.exact("com.android.server.wm.WindowOrganizerController","applyChanges",
                listOf("com.android.server.wm.WindowContainer",wct+"\$Change"),"int")
        popupChangeBounds=popupChangeMethod!=null&&
            method(wct+"\$Change","getWindowingMode")&&method(wct+"\$Change","getConfiguration")&&
            field(wct+"\$Change","mConfigSetMask")&&field(wct+"\$Change","mWindowSetMask")
        notificationSendMethod=NotificationRouteMethods.send(probe.type("com.android.server.am.PendingIntentRecord"))
        notificationStartMethod=NotificationRouteMethods.initial(probe.type("com.android.server.wm.ActivityStarter"))
        val minimizeCore=RomProfile.SERVER_CORES.firstNotNullOfOrNull{owner->
            if(!method(owner,"minimizeTaskLocked","int","int",task,"boolean"))return@firstNotNullOfOrNull null
            listOf(listOf("int","boolean"),listOf("int","int","boolean"))
                .firstNotNullOfOrNull{probe.exact(owner,"minimizeAllTasksLocked",it,"void")}
                ?.let{owner to it}
        }
        serverCore=minimizeCore?.first
        minimizeAllMethod=minimizeCore?.second
        serverScale=role=="android"&&feature("scale","系统统一缩放",
            "ActivityRecord.resolveOverrideConfiguration" to method(ar,"resolveOverrideConfiguration",config),
            "resolved configuration" to method(ar,"getResolvedOverrideConfiguration"),
            "ActivityRecord.info" to field(ar,"info"),"ActivityRecord.getTask" to method(ar,"getTask"),
            "Task.mUserId" to field(task,"mUserId"),"Task.getWindowingMode" to method(task,"getWindowingMode"),
            "framework context startup" to method(atm,"onSystemReady"))
        serverEntry=role=="android"&&feature("entry","标准窗口布局",
            "LaunchParamsController.layoutTask" to probe.method("com.android.server.wm.LaunchParamsController","layoutTask"),
            "Task mode or organizer entry callback" to (modeMethod!=null||popupChangeBounds))
        if(role=="android")feature("entry-transaction","原生手势开窗布局",
            "organizer entry bounds ABI" to popupChangeBounds)
        notificationRoute=role=="android"&&feature("notification-route","通知中转小窗启动",
            "native pending send" to (notificationSendMethod!=null),
            "pre-task source/options" to (notificationStartMethod!=null),
            "pending identity" to (field("com.android.server.am.PendingIntentRecord","uid")&&
                field("com.android.server.am.PendingIntentRecord","key")&&
                field("com.android.server.am.PendingIntentRecord\$Key","packageName")&&
                field("com.android.server.am.PendingIntentRecord\$Key","type")),
            "source activity lifecycle" to listOf("info","mUserId","launchedFromUid","firstWindowDrawn","finishing","mNoDisplay").all{field(ar,it)},
            "native route cookie" to (method("android.app.ActivityOptions","getLaunchCookie")&&
                method("android.app.ActivityOptions","setLaunchCookie","android.os.IBinder")))
        val corner="com.android.server.wm.MultiWindowPointerEventListener"
        cornerGesture=role=="android"&&feature("corner-symmetric","对角手势居中开窗",
            "native pointer/guide" to (method(corner,"onPointerEvent","android.view.MotionEvent")&&
                method("com.samsung.android.multiwindow.FreeformResizeGuide","show","android.graphics.Rect")),
            "native corner commit" to method("com.android.server.wm.FreeformController",
                "setFreeformWindowingModeByCornerGestureLocked","int","int","android.graphics.Rect"),
            "listener identity/minima" to listOf("mTask","mFreeformResizeGuide","mMultiWindowEdgeDetector",
                "mReadyToFreeform","mNotSupport","mTaskResizable","mMinWidth","mMinHeight").all{field(corner,it)},
            "edge flags" to method("com.samsung.android.multiwindow.MultiWindowEdgeDetector","getEdgeFlags"),
            "controller task lookup" to (field("com.android.server.wm.FreeformController","mAtm")&&modeMethod!=null))
        serverLease=role=="android"&&feature("lease","实时挂起与HOME保护",
            "Samsung minimize controller ABI" to (serverCore!=null),
            "Samsung controller owner" to field(atm,"mMultiTaskingCore","mMultiTaskingController"),
            "Task.canMinimize" to method(task,"canMinimize"),
            "ATM.onSystemReady" to method(atm,"onSystemReady"),
            "Task.mLastLayer" to field(task,"mLastLayer"),
            "ATM WM lock/root" to (field(atm,"mGlobalLock")&&field(atm,"mRootWindowContainer")))
        if(role=="android")feature("player-density","播放器配置回调","supported overload" to (densityMethod!=null))
        if(role=="android")feature("frameless-sync","无边框绘制同步",
            "WindowState.finishDrawing" to method("com.android.server.wm.WindowState","finishDrawing",
                "android.view.SurfaceControl\$Transaction","int"),
            "WindowState.finishSync" to method("com.android.server.wm.WindowState","finishSync",
                "android.view.SurfaceControl\$Transaction","com.android.server.wm.BLASTSyncEngine\$SyncGroup","boolean"),
            "matching BLAST group" to method("com.android.server.wm.WindowState","isDifferentSyncGroup",
                "com.android.server.wm.BLASTSyncEngine\$SyncGroup"))
        if(role=="android")feature("frameless-root","无边框独立裁切",
            "Activity container lifecycle" to method(ar,"prepareSurfaces"),
            "Activity surface" to field(ar,"mSurfaceControl"),
            "Activity bounds/parent" to (method(ar,"getBounds")&&method(ar,"getParent")))
        decorations=RomProfile.DECORATIONS.filter{owner->
            cls(owner)&&field(owner,"taskInfo","mTaskInfo")&&field(owner,"taskSurface","mTaskSurface")&&
                field(owner,"decorWindowContext","mDecorWindowContext","userContext","mUserContext","mContext")&&probe.method(owner,"relayout")
        }
        positioners=RomProfile.POSITIONERS.filter{owner->
            field(owner,"windowDecoration","mWindowDecoration","mDesktopWindowDecoration")&&
                CompatibilityPolicy.dragStartSignatures.any{probe.method(owner,"onDragPositioningStart",it,"android.graphics.Rect")}&&
                (probe.method(owner,"onDragPositioningMove",listOf("float","float"),"android.graphics.Rect")||
                    probe.method(owner,"onDragPositioningMove",listOf("float","float","int"),"android.graphics.Rect"))&&
                (probe.method(owner,"onDragPositioningEnd",listOf("float","float"),"android.graphics.Rect")||
                    probe.method(owner,"onDragPositioningEnd",listOf("float","float","int"),"android.graphics.Rect"))
        }
        samsungBoundary=positioners.contains(RomProfile.TASK_POSITIONER)&&
            listOf("stableBounds","ctrlType","repositionTaskBounds","taskBoundsAtDragStart","isResizingOrAnimatingResize").all{field(RomProfile.TASK_POSITIONER,it)}&&
            probe.method(RomProfile.TASK_POSITIONER,"isImeSourceVisible")
        val stashState="com.android.wm.shell.windowdecor.FreeformStashState"
        stash=method(RomProfile.MOTION_CONTROLLER,"moveToTarget","android.graphics.Rect","java.lang.Runnable","boolean")&&
            method(RomProfile.MOTION_CONTROLLER,"setStashDim",wct,"boolean")&&
            listOf("mFreeformStashState","mTaskMotionInfo","mWindowDecoration","mAllowTouches","mFreeformCaptionTouchState").all{field(RomProfile.MOTION_CONTROLLER,it)}&&
            listOf("mLastFreeformBoundsBeforeStash","mAnimating","mScale").all{field(stashState,it)}&&
            method(stashState,"setStashed","int")&&method(stashState,"destroyStashDimOverlay")
        systemUi=role==RomProfile.SYSTEM_UI&&feature("controls","小窗控制条与原生过渡",
            "Samsung framework" to cls("com.samsung.android.rune.CoreRune"),
            "Shell organizer" to (method(RomProfile.ORGANIZER,"getRunningTaskInfo","int")&&
                method(RomProfile.ORGANIZER,"applyTransaction",wct)&&field(RomProfile.ORGANIZER,"mTasks")&&field(RomProfile.ORGANIZER,"mLock")),
            "window decoration" to decorations.isNotEmpty(),
            "Shell sync" to (method(queue,"queue",wct)&&method(queue,"runInSync",queue+"\$TransactionRunnable")&&field(queue,"mInFlight")&&field(queue,"mMainExecutor")),
            "Shell native transition" to (method(transitions,"runOnIdle","java.lang.Runnable")&&
                method(transitions,"startTransition","int",wct,transitions+"\$TransitionHandler")&&field(transitions,"mMainExecutor")),
            "native mode change" to method("com.android.wm.shell.freeform.FreeformTaskTransitionHandler","startWindowingModeTransition",wct,"int"))
        if(role==RomProfile.SYSTEM_UI){
            val outline=probe.type("com.android.wm.shell.windowdecor.widget.OutlineView")
            val outlineOwner=probe.type("com.android.wm.shell.windowdecor.FreeformOutline")
            feature("frameless-outline","无边框顶部轮廓",
                "OutlineView update or native owner callbacks" to (outline!=null&&
                    (OutlineHookMethods.updates(outline).isNotEmpty()||outlineOwner!=null&&
                        OutlineHookMethods.owners(outlineOwner).isNotEmpty())),
                "OutlineView draw callbacks" to (outline!=null&&OutlineHookMethods.draws(outline).isNotEmpty()))
            feature("follow","顶部拖动与边角跟随","matching positioner" to positioners.isNotEmpty())
            feature("boundary","三星拖动边界修正","Samsung positioner ABI" to samsungBoundary)
            feature("stash","普通小窗贴边图标收纳","Samsung stash ABI" to stash)
        }
        consumer=probe.first(RomProfile.CONSUMERS){owner->
            method(owner,"onMotionEvent","android.view.MotionEvent")&&method(owner,"appearTaskList","boolean")&&
                method(owner,"finishTaskMoveAnimation")&&listOf("context","isStartTaskMoveAnimation","appearTaskList").all{field(owner,it)}&&
                field(owner,"displayId","deviceState")&&(field(owner,"topTaskUseCase")||cls("android.app.ActivityTaskManager"))
        }
        val home="com.honeyspace.gesture.recentsanimation.RecentsAnimationManager"
        launcher=role==RomProfile.LAUNCHER&&feature("gesture","上滑到角落挂起",
            "gesture consumer ABI" to (consumer!=null),
            "native home finish" to (probe.method(home,"start")&&method(home,"getDisplayId")&&
                (method(home,"willFinishToHome")||field(home,"cancelToHome"))&&
                method(home,"finish")&&method(home,"finishController","boolean","boolean")&&field(home,"appPlayer")),
            "home state verification" to (cls("com.honeyspace.sdk.HomeScreen\$Normal")&&cls("com.honeyspace.ui.common.ScreenManagerUtil")&&
                field("com.honeyspace.transition.ShellTransitionManager","Companion")))
        capture=probe.first(listOf("android.window.ScreenCaptureInternal","android.window.ScreenCapture")){owner->
            method(owner,"captureLayers",owner+"\$LayerCaptureArgs")&&cls(owner+"\$LayerCaptureArgs\$Builder")
        }
        if(role==RomProfile.SYSTEM_UI)feature("preview","过渡画面取图","capture provider" to (capture!=null))
    }
    fun summary()=features.joinToString("\n"){f->f.title+if(f.available)"：接口匹配（待实测）" else "：未启用 [${f.missing.joinToString()}]"}
}
