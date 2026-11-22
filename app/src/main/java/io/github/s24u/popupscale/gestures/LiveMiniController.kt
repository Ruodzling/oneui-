package io.github.s24u.popupscale.gestures

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import de.robv.android.xposed.XposedHelpers as X

/** Live task surface, never a screenshot loop. Native mode changes finish before scaling. */
class LiveMiniController(private val context:Context,private val backend:TaskBackend,
                         private val frame:TransitionFrameOverlay, private val released:(Int)->Unit,
                         private val journalKey:String="task",private val changed:(Int)->Unit={},
                         private val stackTouch:(Int,MotionEvent)->Boolean={_,_->false},
                         private val expanded:(Int)->Unit={}) {
    private enum class Phase { IDLE, PREPARING, MINI, DRAGGING, SETTLING, DOCKED, CLOSING, RESTORING }
    private data class SurfacePin(val taskId:Int,val leash:Any,val scale:Float,val x:Float,val y:Float,
                                  val alpha:Float?,val crop:Rect,val layer:Int?=null,
                                  val cornerRadius:Float?=null)
    private val main=Handler(Looper.getMainLooper())
    private val lease=MiniTaskLease(context,{onLeaseAck(it)}){selected,fromLauncher->restoreSelected(selected,fromLauncher)}
    private var activeTopCrop=0
    private var selecting=false
    private var reflowPending=false
    private var reflowRetry=false
    private var reflowDeadline=0L
    private var reflowTick:Runnable?=null
    private var protected=false
    private val playbackIntent=MiniPlaybackIntent(context)
    private var playbackFocusable=false
    private var playbackAttempted=false
    private var playbackTicket=0
    private var playbackStackFront=true
    private var handoff=""
    private val wm=context.getSystemService(WindowManager::class.java)
    private val journal=context.createDeviceProtectedStorageContext()
        .getSharedPreferences("popupscale_live_recovery",Context.MODE_PRIVATE)
    private var pending=journal.getString(journalKey,"").orEmpty()
    private var stackAlpha=1f
    private var stackControlled=false
    private var stackTouchLocked=false
    @Volatile private var stackLayer:Int?=null
    private var stackNativeLayer:Int?=null
    private var recovering=false
    private var id=-1
    private var phase=Phase.IDLE
    private var independentDock=false
    @Volatile private var generation=0
    private var started=0L
    private var base=Rect()
    private var parent=Point()
    private var target=Rect()
    private var current=Rect()
    private var surface:Any?=null
    private var touch:FrameLayout?=null
    private var params:WindowManager.LayoutParams?=null
    private var animator:ValueAnimator?=null
    private val density=context.resources.displayMetrics.density
    private val pad=(4*density).toInt()
    private val closeHint=MiniCloseHint(context)
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var badge:ImageView?=null
    private var miniSize=Point()
    private var dockLeft=true
    private var dockCenter=0
    private var dockedFromStandard=false
    private var downX=0f;private var downY=0f
    private var dragStart=Rect()
    private var beforeDrag=Rect()
    private var downDocked=false
    private var downDockedFromStandard=false
    private var downDockLeft=true
    private var downActive=false
    private var shadeObscured=false
    private var requestedTouchable=true
    private var dragFrame=false
    private var pendingDrag:Rect?=null
    private var velocity:VelocityTracker?=null
    private var badgeTicket=0
    @Volatile private var pin:SurfacePin?=null
    private val displayMetrics=GestureDisplay.metrics(context)
    private val sw get()=displayMetrics.widthPixels
    private val sh get()=displayMetrics.heightPixels
    private fun refreshDisplay(){displayMetrics.setTo(GestureDisplay.metrics(context))}
    fun busy(): Boolean {
        if (pending.isNotEmpty()) {
            val fields = pending.split(',').mapNotNull { it.toIntOrNull() }
            if (fields.isEmpty() || backend.info(fields[0]) == null) {
                journal.edit().remove(journalKey).commit()
                pending = ""
            }
        }
        return id >= 0 || pending.isNotEmpty() || recovering
    }
    fun isMini(taskId:Int)=id==taskId
    fun activeTaskId()=id
    fun status()="livePhase=$phase liveTask=$id liveProtected=$protected liveStandardDock=$dockedFromStandard liveRect=${target.toShortString()}"
    fun diagnosticRect(taskId:Int):Rect?=if(taskId==id&&phase in setOf(Phase.MINI,Phase.DOCKED,Phase.DRAGGING,Phase.SETTLING))Rect(current) else null
    fun stackReady()=id>=0&&phase in setOf(Phase.MINI,Phase.DOCKED)&&!selecting&&!reflowPending
    fun stackMember()=MiniStackPolicy.participates(id,independentDock,
        phase in setOf(Phase.IDLE,Phase.RESTORING,Phase.CLOSING))
    fun hasPreparedPin()=id<0&&pin!=null
    fun miniSize()=MiniStackPolicy.Size(miniSize.x,miniSize.y)
    fun nativeStackLayer()=stackNativeLayer ?: lease.nativeLayer
    fun miniRect()=Rect(current)
    fun defaultMiniRect():Rect?=if(base.isEmpty)null else backend.miniBounds(base,false)
    fun canHomeToDefault()=id>=0&&!stackControlled&&!independentDock&&phase==Phase.MINI&&!selecting
    fun returnToDefaultCorner(){
        if(!canHomeToDefault())return
        refreshDisplay()
        val dest=defaultMiniRect() ?: return
        if(dest==current)return
        Guard.info("live mini homing to default corner task=$id from=${current.toShortString()} to=${dest.toShortString()}")
        settle(dest,false,220)
    }
    fun expandStack(){restore(true)}
    fun lockStackTouch(locked:Boolean){
        if(stackTouchLocked==locked)return
        stackTouchLocked=locked
        if(!locked)updateTouch()
    }
    fun stackFrame(bounds:Rect,front:Boolean,alpha:Float,layer:Int?,transaction:Any){
        if(!stackReady()||!stackMember())return
        if(stackNativeLayer==null)stackNativeLayer=lease.nativeLayer
        stackLayer=layer
        val entering=!stackControlled
        stackControlled=true;stackAlpha=alpha
        val playbackFrontChanged=playbackStackFront!=front
        playbackStackFront=front
        phase=Phase.MINI
        if(playbackFrontChanged)updatePlaybackState()
        if(entering)showBadge(false)
        target=Rect(bounds)
        touchable(MiniStackPolicy.overlayTouchable(front,stackControlled))
        render(bounds,transaction=transaction)
    }
    fun releaseStack(returnToDefault:Boolean=false){
        if(!stackControlled)return
        stackControlled=false;stackAlpha=1f;touchable(true)
        playbackStackFront=true
        updatePlaybackState()
        restoreStackLayer()
        if(stackReady()&&stackMember()){
            if(returnToDefault){
                refreshDisplay()
                val dest=defaultMiniRect()
                if(dest!=null&&dest!=current){
                    Guard.info("live mini homing after stack expand task=$id from=${current.toShortString()} to=${dest.toShortString()}")
                    settle(dest,false,220)
                    return
                }
            }
            target=Rect(current.left,current.top,current.left+miniSize.x,current.top+miniSize.y)
            target.offsetTo(target.left.coerceIn(0,(sw-target.width()).coerceAtLeast(0)),
                target.top.coerceIn(0,(sh-target.height()).coerceAtLeast(0)))
            render(target)
        }
    }
    private fun restoreStackLayer(){
        if(stackLayer==null)return
        val original=stackNativeLayer
        stackLayer=null
        val leash=surface ?: return
        if(original!=null&&X.callMethod(leash,"isValid")==true)backend.surfaceTransaction{tx->
            X.callMethod(tx,"setLayer",leash,original)
            render(current,transaction=tx)
        }
    }
    fun inputTouchable()=params?.let{it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE==0} ?: false
    fun setShadeObscured(obscured:Boolean){
        if(shadeObscured==obscured)return
        shadeObscured=obscured
        if(obscured)cancelDrag()
        touchable(requestedTouchable)
    }
    private fun touchable(requested:Boolean){
        requestedTouchable=requested
        val value=ShadeInteractionState.touchable(requested,shadeObscured)
        val p=params ?: return
        val flags=if(value)p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if(flags!=p.flags){
            touch?.importantForAccessibility=if(value)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            p.flags=flags
            touch?.let{wm.updateViewLayout(it,p)}
        }
    }

    fun pinFreeformStash(task:TaskRecord,startRect:Rect) {
        if(busy()||backend.locked()||!backend.canRestore()||task.mode!=5||!task.visible||task.bounds.isEmpty||startRect.isEmpty)return
        val leash=Guard.call("live-surface"){backend.surface(task.id)} ?: return
        val scale=startRect.width().toFloat()/task.bounds.width()
        val x=task.position.x+(startRect.left-task.bounds.left).toFloat()
        val y=task.position.y+(startRect.top-task.bounds.top).toFloat()
        val crop=Rect(0,0,task.bounds.width(),task.bounds.height())
        val radius=LivePlacement.scaledCornerRadius(backend.nativeCornerRadius(task.id),scale,crop.width(),crop.height())
        pin=SurfacePin(task.id,leash,scale,x,y,null,crop,cornerRadius=radius)
        backend.transform(task.id,leash,scale,x,y,crop=crop,cornerRadius=radius)
    }

    fun reassertSurface(taskId:Int) {
        val p=pin ?: return
        if(p.taskId!=taskId)return
        Guard.call("live-reassert"){backend.transform(p.taskId,p.leash,p.scale,p.x,p.y,p.alpha,crop=p.crop,layer=p.layer,cornerRadius=p.cornerRadius)}
    }

    fun dockFromFreeform(task:TaskRecord,startRect:Rect,left:Boolean):Boolean {
        refreshDisplay()
        if(busy()||backend.locked()||!backend.canRestore()||task.mode!=5||!task.visible||task.bounds.isEmpty||startRect.isEmpty){
            if(!busy()){pin=null;resetSurface(task.id)}
            return false
        }
        if(task.bounds.left<0||task.bounds.top<0||task.bounds.right>sw||task.bounds.bottom>sh){
            pin=null;resetSurface(task.id);return false
        }
        val leash=Guard.call("live-surface"){backend.surface(task.id)}?:run{pin=null;resetSurface(task.id);return false}
        val miniRect=backend.miniBounds(task.bounds)?:run{pin=null;resetSurface(task.id);return false}
        if(!journal.edit().putString(journalKey,encode(task.id,task.bounds)).commit()){pin=null;resetSurface(task.id);return false}
        id=task.id;base=Rect(task.bounds);parent=Point(task.position);surface=leash
        miniSize=Point(miniRect.width(),miniRect.height())
        dockLeft=left;dockCenter=startRect.centerY();dockedFromStandard=true;independentDock=true
        started=SystemClock.uptimeMillis();handoff=""
        val g=++generation
        try {
            phase=Phase.SETTLING
            render(startRect)
            val view=attachOverlay(task,startRect)
            view.isClickable=true
            val dockBox=MiniDragPolicy.dock(left,dockCenter,miniSize.x,miniSize.y,sw,sh,density)
            Guard.info("live freeform stash intercepted task=$id left=$left from=${startRect.toShortString()} to=${rect(dockBox).toShortString()}")
            settle(rect(dockBox),true,260)
            lease.acquire(task.id,task.userId) acquired@{ok->
                if(g!=generation)return@acquired
                if(!ok){
                    Guard.info("live freeform dock skipped: system framework protection acknowledgement unavailable")
                    restoreImmediately();return@acquired
                }
                protected=true;changed(id)
            }
            return true
        } catch(error:Throwable){Guard.failure("live-freeform-dock",error);restoreImmediately();return false}
    }

    fun shrinkFromFreeformResize(task:TaskRecord,left:Boolean,startRect:Rect=task.bounds,allowOffscreen:Boolean=false,
                                 destination:Rect?=null):Boolean {
        refreshDisplay()
        if(busy()||backend.locked()||!backend.canRestore()||task.mode!=5||!task.visible||task.bounds.isEmpty){
            if(!busy()){pin=null;resetSurface(task.id)}
            return false
        }
        if(!allowOffscreen&&(task.bounds.left<0||task.bounds.top<0||task.bounds.right>sw||task.bounds.bottom>sh)){
            pin=null;resetSurface(task.id);return false
        }
        val leash=Guard.call("live-surface"){backend.surface(task.id)}?:run{pin=null;resetSurface(task.id);return false}
        val miniRect=destination?.takeUnless{it.isEmpty}?.let{Rect(it)}
            ?: backend.miniBounds(task.bounds,left)?:run{pin=null;resetSurface(task.id);return false}
        if(!journal.edit().putString(journalKey,encode(task.id,task.bounds)).commit()){pin=null;resetSurface(task.id);return false}
        id=task.id;base=Rect(task.bounds);parent=Point(task.position);surface=leash
        miniSize=Point(miniRect.width(),miniRect.height());dockedFromStandard=false;independentDock=false
        val initial=if(startRect.isEmpty)task.bounds else startRect
        target=Rect(miniRect);started=SystemClock.uptimeMillis();handoff=""
        val g=++generation
        try {
            phase=Phase.SETTLING
            render(initial)
            val view=attachOverlay(task,initial)
            view.isClickable=true
            Guard.info("live freeform resize-to-mini intercepted task=$id left=$left from=${initial.toShortString()} to=${miniRect.toShortString()}")
            settle(miniRect,false,240)
            val keepPreHangFocus=!backend.locked()&&(!stackControlled||playbackStackFront)&&
                backend.miniKeepPlaybackSelected(task.packageName)
            if(keepPreHangFocus){
                playbackFocusable=true
            }else{
                Guard.call("live-unfocus"){backend.setFocusable(id,false)}
            }
            lease.acquire(task.id,task.userId) acquired@{ok->
                if(g!=generation)return@acquired
                if(!ok){
                    Guard.info("live freeform resize-to-mini skipped: system framework protection acknowledgement unavailable")
                    restoreImmediately();return@acquired
                }
                protected=true;changed(id)
            }
            return true
        } catch(error:Throwable){Guard.failure("live-freeform-resize",error);restoreImmediately();return false}
    }

    fun hang(task:TaskRecord,nonce:String="") {
        if(busy()||backend.locked()||!backend.canRestore())return
        refreshDisplay()
        base=backend.liveBounds(task.id)
        check(journal.edit().putString(journalKey,encode(task.id,base)).commit()) { "Live recovery journal unavailable" }
        id=task.id;phase=Phase.PREPARING;independentDock=false;started=SystemClock.uptimeMillis()
        handoff=if(frame.matches(id,nonce))nonce else ""
        val g=++generation
        lease.acquire(task.id,task.userId,preparingFullscreen=true) acquired@{ok->
            if(g!=generation)return@acquired
            if(!ok){
                Guard.info("live hang skipped: protection acknowledgement timed out or task rejected")
                journal.edit().remove(journalKey).commit();clear();return@acquired
            }
            protected=true;changed(id)
            if(handoff.isNotEmpty()&&Guard.call("transition-mask"){frame.maskTask(id,handoff)}!=true){
                frame.clearForTask(id);handoff=""
                Guard.info("transition frame mask unavailable; using legacy live animation")
            }
            enterHome(g,20)
        }
    }

    private fun enterHome(g:Int,retries:Int) {
        try {
            // Launcher already confirmed HOME Normal. Avoid starting another HOME transition
            // when focus is already there; preserve the old bounded fallback otherwise.
            if(backend.homeFocused()){awaitHome(g,retries);return}
            backend.returnHome()
            main.postDelayed({awaitHome(g,retries)},240)
        } catch(error:Throwable){Guard.failure("live-prepare",error);restoreImmediately()}
    }

    private fun awaitHome(g:Int,remaining:Int) {
        if(g!=generation)return
        if(backend.locked()){restoreImmediately();return}
        if(!backend.homeFocused()) {
            if(remaining<=0){Guard.info("live hang stopped: HOME did not become focused");restoreImmediately();return}
            if(remaining==12)Guard.call("live-home-retry"){backend.returnHome()}
            main.postDelayed({awaitHome(g,remaining-1)},80);return
        }
        Guard.info("live HOME focused before native freeform transition task=$id")
        backend.whenIdle home@{settled->
            if(g!=generation)return@home
            if(!settled){restoreImmediately();return@home}
            // A landscape app may return to a portrait-locked HOME. Resolve the
            // native freeform bounds after that display rotation, not before HOME.
            refreshDisplay()
            base=backend.liveBounds(id)
            if(!journal.edit().putString(journalKey,encode(id,base)).commit()){restoreImmediately();return@home}
            backend.openLive(id,base,handoff.isNotEmpty()) callback@{ok->
                if(g!=generation)return@callback
                if(!ok){restoreImmediately();return@callback}
                backend.whenIdle idle@{ready->
                    if(g!=generation)return@idle
                    if(ready)prepare(g,20) else restoreImmediately()
                }
            }
        }
    }

    private fun prepare(g:Int,remaining:Int) {
        if(g!=generation)return
        refreshDisplay()
        val task=Guard.call("live-info"){backend.info(id)?.let{backend.record(it)}}
        val leash=Guard.call("live-surface"){backend.surface(id)}
        if(task==null||task.mode!=5||!task.visible||task.focused||leash==null){
            if(remaining>0)main.postDelayed({prepare(g,remaining-1)},60) else restoreImmediately()
            return
        }
        if(backend.locked()){restoreImmediately();return}
        if(task.bounds.isEmpty){restoreImmediately();return}
        base=Rect(task.bounds);parent=Point(task.position);surface=leash
        if(!journal.edit().putString(journalKey,encode(id,base)).commit()){restoreImmediately();return}
        target=backend.miniBounds(base)?:run{restoreImmediately();return}
        miniSize=Point(target.width(),target.height());dockedFromStandard=false
        if(lease.framelessCaption<=0)lease.refresh()
        try {
            val view=attachOverlay(task,base)
            val ready={
                phase=Phase.MINI
                updatePlaybackState()
                view.isClickable=true
                Guard.info("live mini ready task=$id visible=${task.visible} focused=${task.focused} rect=${target.toShortString()}")
                changed(id)
            }
            if(frame.matches(id,handoff)&&!frame.targets(target)){
                // Rotation changed the destination. Release the old-orientation
                // bridge and let the real task animate to its current-screen corner.
                Guard.info("live hang display changed; using current-screen target task=$id rect=${target.toShortString()}")
                frame.clearForTask(id);handoff=""
            }
            if(frame.matches(id,handoff)) {
                view.isClickable=false
                frame.maskTask(id,handoff)
                render(target)
                frame.awaitTarget(id,handoff,target) callback@{ok->
                    if(g!=generation)return@callback
                    if(!ok){restoreImmediately();return@callback}
                    if(Guard.call("live-frame-handoff"){
                        render(target);frame.reveal(id,handoff);ready();true
                    }!=true)restoreImmediately()
                }
            }else animate(base,target,g,end=ready)
        } catch(error:Throwable){Guard.failure("live-prepare",error);restoreImmediately()}
    }

    private fun attachOverlay(task:TaskRecord,initial:Rect):FrameLayout {
        val view=FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription="实时迷你小窗，点击恢复"
            setOnClickListener{
                if(shadeObscured)return@setOnClickListener
                if(phase==Phase.DOCKED){
                    if(MiniDragPolicy.dockTapRestoresStandard(dockedFromStandard))restore(true,"dock-tap") else undock()
                } else if(phase==Phase.MINI)restore(true,"mini-tap")
            }
            setOnTouchListener{v,event->
                val handled=Guard.call("live-mini-touch"){onTouch(v,event)}
                if(handled==null)Guard.call("live-mini-touch-recover"){restoreImmediately()}
                true
            }
        }
        badge=ImageView(context).apply{
            val icon=context.packageManager.getApplicationIcon(task.packageName)
            val badged=runCatching{
                val user=X.callStaticMethod(android.os.UserHandle::class.java,"of",task.userId) as android.os.UserHandle
                context.packageManager.getUserBadgedIcon(icon,user)
            }.getOrDefault(icon)
            setImageDrawable(badged)
            scaleType=ImageView.ScaleType.CENTER_INSIDE
            setPadding((6*density).toInt(),(6*density).toInt(),(6*density).toInt(),(6*density).toInt())
            background=GradientDrawable().apply{setColor(0xdd30343b.toInt());cornerRadius=12*density}
            visibility=View.GONE
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        view.addView(badge,FrameLayout.LayoutParams((48*density).toInt(),(48*density).toInt(),Gravity.CENTER_VERTICAL))
        val p=WindowManager.LayoutParams(initial.width()+2*pad,initial.height()+2*pad,2024,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
        p.gravity=Gravity.TOP or Gravity.LEFT;p.x=initial.left-pad;p.y=initial.top-pad
        p.title="PopupScaleLiveMiniTouch"
        p.windowAnimations=0
        if(!ShadeInteractionState.touchable(requestedTouchable,shadeObscured))
            p.flags=p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // Geometry uses physical display coordinates. The default cutout-safe frame
        // shifts an x=0 dock tab to x=128 in landscape, separating its icon/hit area
        // from the edge. Fit neither cutout nor bar insets on this small overlay.
        if(android.os.Build.VERSION.SDK_INT>=30){
            p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            p.setFitInsetsTypes(0)
        }
        X.callMethod(p,"setCanPlayMoveAnimation",false)
        X.callMethod(p,"setTrustedOverlay")
        wm.addView(view,p);touch=view;params=p;current=Rect(initial)
        return view
    }

    private fun topCrop():Int=if(phase==Phase.RESTORING)activeTopCrop else lease.framelessCaption
    private fun onLeaseAck(taskId:Int) {
        if(taskId!=id||phase !in setOf(Phase.MINI,Phase.DOCKED,Phase.DRAGGING,Phase.SETTLING))return
        if(surface!=null&&!current.isEmpty)Guard.call("live-caption-rerender"){render(current)}
    }
    private fun restoreTopInsetWeight(rect:Rect):Float {
        if(phase!=Phase.RESTORING)return 1f
        val span=base.width()-miniSize.x
        if(span<=0)return 0f
        return (1f-(rect.width()-miniSize.x).toFloat()/span).coerceIn(0f,1f)
    }
    private fun render(rect:Rect,alpha:Float?=null,transaction:Any?=null) {
        val leash=surface ?: error("Live surface missing")
        val geometry=LivePlacement.miniTransform(
            LivePlacement.Bounds(base.left,base.top,base.width(),base.height()),parent.x,parent.y,
            LivePlacement.Bounds(rect.left,rect.top,rect.width(),rect.height()),topCrop(),restoreTopInsetWeight(rect))
        val a=(alpha ?: MiniDragPolicy.dockedSurfaceAlpha(phase==Phase.DOCKED))*stackAlpha
        val crop=Rect(geometry.cropLeft,geometry.cropTop,geometry.cropRight,geometry.cropBottom)
        val radius=LivePlacement.scaledCornerRadius(backend.nativeCornerRadius(id),geometry.scale,crop.width(),crop.height())
        pin=SurfacePin(id,leash,geometry.scale,geometry.x,geometry.y,a,crop,stackLayer,radius)
        backend.transform(id,leash,geometry.scale,geometry.x,geometry.y,a,crop=crop,transaction=transaction,layer=stackLayer,cornerRadius=radius)
        current=Rect(rect)
        val visualHeight=(geometry.cropHeight*geometry.scale+0.5f).toInt().coerceIn(1,rect.height())
        // The original touch window owns the pointer stream until UP/CANCEL, even outside it.
        // Move the live surface each frame, but only relocate the hit area when settling finishes.
        if(!stackTouchLocked&&(phase==Phase.PREPARING||phase==Phase.MINI||phase==Phase.DOCKED))updateTouch(visualHeight)
    }
    private fun updateTouch(visualHeight:Int=current.height()) {
        val p=params;val view=touch
        if(p!=null&&view!=null){
            val box=if(phase==Phase.DOCKED)MiniDragPolicy.tab(dockLeft,dockCenter,sw,sh,density)
                else MiniDragPolicy.Box((current.left-pad).coerceAtLeast(0),(current.top-pad).coerceAtLeast(0),
                    current.width()+2*pad,visualHeight+2*pad)
            val changed=p.x!=box.x||p.y!=box.y||p.width!=box.width||p.height!=box.height
            p.x=box.x;p.y=box.y;p.width=box.width;p.height=box.height
            if(changed)wm.updateViewLayout(view,p)
        }
    }

    private fun box(rect:Rect)=MiniDragPolicy.Box(rect.left,rect.top,rect.width(),rect.height())
    private fun rect(box:MiniDragPolicy.Box)=Rect(box.x,box.y,box.right,box.bottom)
    private fun onTouch(view:View,event:MotionEvent):Boolean {
        if(shadeObscured){cancelDrag();return true}
        if(stackTouch(id,event))return true
        if(event.actionMasked!=MotionEvent.ACTION_DOWN&&downActive)velocity?.addMovement(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                if(phase !in setOf(Phase.MINI,Phase.DOCKED)||selecting||backend.locked())return true
                recycleVelocity();velocity=VelocityTracker.obtain().also{it.addMovement(event)}
                view.requestUnbufferedDispatch(event)
                downActive=true;downX=event.rawX;downY=event.rawY
                downDocked=phase==Phase.DOCKED;beforeDrag=Rect(current)
                downDockedFromStandard=downDocked&&dockedFromStandard;downDockLeft=dockLeft
                if(downDocked){
                    wakeBadge()
                    Guard.info("live dock touch task=$id fromStandard=$downDockedFromStandard left=$downDockLeft " +
                        "hit=${params?.x},${params?.y},${params?.width},${params?.height}")
                }
                dragStart=if(downDocked)Rect((downX-miniSize.x/2).toInt(),(downY-miniSize.y/2).toInt(),
                    (downX-miniSize.x/2).toInt()+miniSize.x,(downY-miniSize.y/2).toInt()+miniSize.y) else Rect(current)
            }
            MotionEvent.ACTION_MOVE->{
                if(event.pointerCount!=1){cancelDrag();return true}
                moveDrag(event.rawX,event.rawY)
            }
            MotionEvent.ACTION_UP->{
                if(!downActive)return true
                moveDrag(event.rawX,event.rawY)
                downActive=false
                if(phase==Phase.DRAGGING){
                    flushDrag();closeHint.hide()
                    velocity?.computeCurrentVelocity(1000,6000*density)
                    val vx=velocity?.xVelocity?:0f;val vy=velocity?.yVelocity?:0f
                    val motion=MiniDragPolicy.motion(box(current),sw,sh,density,vx,vy,
                        event.rawX-downX,event.rawY-downY)
                    val dockAction=MiniDragPolicy.dockRelease(downDocked,downDockedFromStandard,downDockLeft,
                        event.rawX-downX,event.rawY-downY,density,motion.action)
                    Guard.info("live mini release task=$id vxDp=${(vx/density).toInt()} vyDp=${(vy/density).toInt()} " +
                        "dxDp=${((event.rawX-downX)/density).toInt()} dyDp=${((event.rawY-downY)/density).toInt()} " +
                        "fromDock=$downDocked fromStandard=$downDockedFromStandard dockAction=$dockAction " +
                        "action=${motion.action} bounce=${motion.impact!=null} duration=${motion.durationMs}")
                    if(dockAction==MiniDragPolicy.DockRelease.RESTORE_STANDARD){
                        restore(true,"dock-inward-drag");return true
                    }
                    if(dockAction==MiniDragPolicy.DockRelease.RETURN_TO_EDGE){
                        settle(Rect(beforeDrag),true);recycleVelocity();return true
                    }
                    when(motion.action){
                        MiniDragPolicy.Action.CLOSE_TOP->closeDragged(true)
                        MiniDragPolicy.Action.CLOSE_BOTTOM->closeDragged(false)
                        MiniDragPolicy.Action.DOCK_LEFT,MiniDragPolicy.Action.DOCK_RIGHT->{
                            dockLeft=motion.action==MiniDragPolicy.Action.DOCK_LEFT
                            dockCenter=motion.destination.y+motion.destination.height/2
                            settle(rect(motion.destination),true,motion.durationMs)
                        }
                        MiniDragPolicy.Action.FLOAT->settle(rect(motion.destination),false,motion.durationMs,motion)
                    }
                }else if(phase in setOf(Phase.MINI,Phase.DOCKED)){
                    view.performClick()
                    if(phase==Phase.DOCKED)scheduleBadgeDim(generation,++badgeTicket)
                }
                recycleVelocity()
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->cancelDrag()
        }
        return true
    }
    private fun moveDrag(x:Float,y:Float) {
        if(!downActive||!x.isFinite()||!y.isFinite())return
        val dx=x-downX;val dy=y-downY
        if(phase!=Phase.DRAGGING){
            if(kotlin.math.abs(dx)<=slop&&kotlin.math.abs(dy)<=slop)return
            if(phase !in setOf(Phase.MINI,Phase.DOCKED))return
            phase=Phase.DRAGGING;showBadge(false)
            Guard.info("live mini drag started task=$id fromDock=$downDocked")
        }
        val width=dragStart.width();val height=dragStart.height()
        val left=(dragStart.left+dx).toInt().coerceIn((-width*.8f).toInt(),(sw-width*.2f).toInt())
        val top=(dragStart.top+dy).toInt().coerceIn((-height*.8f).toInt(),(sh-height*.2f).toInt())
        pendingDrag=Rect(left,top,left+width,top+height)
        closeHint.show(MiniDragPolicy.release(box(pendingDrag!!),sw,sh,density))
        if(!dragFrame){
            dragFrame=true;val g=generation
            touch?.postOnAnimation{
                if(g!=generation)return@postOnAnimation
                dragFrame=false
                if(phase==Phase.DRAGGING&&Guard.call("live-drag-frame"){flushDrag();true}!=true)
                    restoreImmediately()
            }
        }
    }
    private fun flushDrag(){pendingDrag?.let{pendingDrag=null;render(it)}}
    private fun recycleVelocity(){velocity?.recycle();velocity=null}
    private fun cancelDrag(){
        val active=downActive;downActive=false;pendingDrag=null;closeHint.hide();recycleVelocity()
        if(active&&phase==Phase.DRAGGING)settle(Rect(beforeDrag),downDocked)
        else if(active&&phase==Phase.DOCKED)scheduleBadgeDim(generation,++badgeTicket)
    }
    private fun settle(destination:Rect,docked:Boolean,durationMs:Long=240,motion:MiniDragPolicy.Motion?=null) {
        independentDock=docked
        phase=Phase.SETTLING;target=Rect(destination)
        dockedFromStandard=MiniDragPolicy.keepStandardDockOrigin(dockedFromStandard,docked)
        val g=generation
        animate(Rect(current),target,g,durationMs=durationMs,lateShrink=docked,motion=motion){
            phase=if(docked)Phase.DOCKED else Phase.MINI
            // Relocate the invisible input host before revealing the side icon.
            render(target)
            if(docked)Guard.call("live-unfocus"){backend.setFocusable(id,false)}
            updatePlaybackState()
            showBadge(docked)
            Guard.info("live mini settled task=$id docked=$docked fromStandard=$dockedFromStandard rect=${target.toShortString()}")
            changed(id)
        }
    }
    private fun showBadge(show:Boolean){
        val image=badge?:return
        val ticket=++badgeTicket;val g=generation
        image.animate().cancel()
        image.visibility=View.INVISIBLE;image.alpha=0f
        touch?.contentDescription=if(!show)"实时迷你小窗，拖动移动，点击展开"
            else if(dockedFromStandard)"侧边收纳，点击或向内拖动展开小窗" else "侧边收纳，点击呼出迷你小窗"
        if(show){
            val p=image.layoutParams as FrameLayout.LayoutParams
            p.leftMargin=MiniDragPolicy.tabBadgeMargin(dockLeft,
                MiniDragPolicy.tab(dockLeft,dockCenter,sw,sh,density).width,density)
            image.layoutParams=p
            // Two traversals let WindowManager commit the new edge frame while the icon is hidden.
            touch?.postOnAnimation { touch?.postOnAnimation {
                if(g==generation&&ticket==badgeTicket&&phase==Phase.DOCKED){
                    image.visibility=View.VISIBLE
                    image.animate().alpha(MiniDragPolicy.DOCK_ACTIVE_ALPHA).setDuration(90).start()
                    scheduleBadgeDim(g,ticket)
                }
            } }
        }
    }
    private fun wakeBadge(){
        val image=badge?:return
        val ticket=++badgeTicket
        image.animate().cancel()
        if(phase==Phase.DOCKED&&image.visibility==View.VISIBLE)image.alpha=MiniDragPolicy.DOCK_ACTIVE_ALPHA
        scheduleBadgeDim(generation,ticket)
    }
    private fun scheduleBadgeDim(g:Int,ticket:Int){
        main.postDelayed({
            val image=badge?:return@postDelayed
            if(g==generation&&ticket==badgeTicket&&phase==Phase.DOCKED&&!downActive&&image.visibility==View.VISIBLE){
                image.animate().cancel()
                image.animate().alpha(MiniDragPolicy.dockBadgeAlpha(MiniDragPolicy.DOCK_IDLE_DELAY_MS)).setDuration(220).start()
            }
        },MiniDragPolicy.DOCK_IDLE_DELAY_MS)
    }
    private fun undock(){
        if(phase!=Phase.DOCKED)return
        showBadge(false)
        settle(rect(MiniDragPolicy.reveal(dockLeft,dockCenter,miniSize.x,miniSize.y,sw,sh,density)),false)
    }
    private fun closeDragged(top:Boolean){
        val closeId=id;val g=++generation
        phase=Phase.CLOSING;started=SystemClock.uptimeMillis();lease.release(closeId);protected=false
        val end=Rect(current).apply{offsetTo(left,if(top)-height()-pad else sh+pad)}
        animate(Rect(current),end,g,true){
            val ok=Guard.call("live-drag-close"){
                val task=backend.info(closeId)?.let{backend.record(it)}
                if(task!=null){
                    check(task.mode==5){"Task mode changed during close"}
                    backend.close(task)
                }
                journal.edit().remove(journalKey).commit()
                Guard.info("live mini closed by vertical drag task=$closeId top=$top")
                clear();true
            }==true
            if(!ok)restoreImmediately()
        }
    }

    private fun animate(from:Rect,to:Rect,g:Int,fade:Boolean=false,durationMs:Long=240,
                        lateShrink:Boolean=false,motion:MiniDragPolicy.Motion?=null,end:()->Unit) {
        animator?.cancel()
        var cancelled=false
        animator=ValueAnimator.ofFloat(0f,1f).apply{
            duration=durationMs;interpolator=if(motion!=null)LinearInterpolator() else DecelerateInterpolator()
            addUpdateListener{
                if(g!=generation){cancel();return@addUpdateListener}
                val t=it.animatedValue as Float
                val shrink=((t-.55f)/.45f).coerceIn(0f,1f)
                val sizeT=if(lateShrink)shrink*shrink*(3-2*shrink) else t
                val width=(from.width()+(to.width()-from.width())*sizeT).toInt()
                val height=(from.height()+(to.height()-from.height())*sizeT).toInt()
                val cx=from.exactCenterX()+(to.exactCenterX()-from.exactCenterX())*t
                val cy=from.exactCenterY()+(to.exactCenterY()-from.exactCenterY())*t
                val left=(cx-width/2f).toInt();val top=(cy-height/2f).toInt()
                val rect=if(motion!=null)rect(MiniDragPolicy.sample(box(from),motion,t))
                    else if(t>=1f)Rect(to) else Rect(left,top,left+width,top+height)
                if(Guard.call("live-animation"){render(rect,if(fade)1-t else null);true}!=true){cancel();restoreImmediately()}
            }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){if(!cancelled&&g==generation)Guard.call("live-end"){end()}}
            })
            start()
        }
    }

    fun restoreImmediately()=restore(false,"recovery")
    private fun restoreSelected(selected:Int,fromLauncher:Boolean) {
        if(selected!=id||phase !in setOf(Phase.MINI,Phase.DOCKED)||selecting||backend.locked())return
        if(fromLauncher){
            val task=Guard.call("live-icon-restore-check"){backend.info(id)?.let{backend.record(it)}} ?: return
            if(task.mode!=5||!task.visible||task.bounds!=base){tick();return}
            // The server has already completed the trusted icon launch. Waiting for
            // Shell idle here stalls on its unfinished non-focusable-task transition.
            Guard.info("expanding live mini from Launcher immediately task=$id")
            restore(true,"launcher-icon")
            return
        }
        selecting=true
        val g=generation
        // Let the native card/icon launch finish before using the same expansion as a mini tap.
        val expand={
            if(g==generation&&selecting&&phase in setOf(Phase.MINI,Phase.DOCKED)){
                selecting=false
                if(!backend.locked()){
                    Guard.info("expanding live mini selected from native launch task=$id")
                    restore(true,"recents-selection")
                }
            }
        }
        backend.whenIdle{ready->if(ready)expand()}
        main.postDelayed({expand()},1200)
    }
    private fun restore(animated:Boolean,source:String="internal") {
        if(id<0){recoverIfNeeded();return}
        if(phase==Phase.RESTORING)return
        if(MiniStackPolicy.restoreLayerBeforeExpand(animated))restoreStackLayer()
        stackTouchLocked=false;stackControlled=false;stackAlpha=1f;touchable(true)
        downActive=false;pendingDrag=null;closeHint.hide();showBadge(false);recycleVelocity()
        val keepVisual=animated&&surface!=null&&touch!=null
        activeTopCrop=if(keepVisual)lease.framelessCaption else 0
        lease.release(if(keepVisual)id else -1);protected=false
        val g=++generation;val restoreId=id
        phase=Phase.RESTORING;started=SystemClock.uptimeMillis();animator?.cancel()
        if(animated)Guard.call("live-expand-home"){expanded(restoreId)}
        val finish={
            resetSurface(restoreId)
            if(animated&&Guard.call("live-restore-direct"){backend.restoreLiveFocus(restoreId,base)}==true){
                stackLayer=null;stackNativeLayer=null
                lease.releaseVisual(restoreId,resyncLayers=true)
                journal.edit().remove(journalKey).commit()
                Guard.info("live mini restored task=$restoreId direct=true source=$source")
                clear()
            }else{
                if(keepVisual)lease.releaseVisual(restoreId,resyncLayers=false)
                backend.restore(restoreId,base) callback@{ ok ->
                    if(g!=generation)return@callback
                    if(ok){
                        stackLayer=null;stackNativeLayer=null
                        journal.edit().remove(journalKey).commit()
                        Guard.info("live mini restored task=$restoreId direct=false source=$source")
                        clear()
                    }else{
                        pending=journal.getString(journalKey,"").orEmpty()
                        clear()
                    }
                }
            }
        }
        if(animated&&surface!=null&&touch!=null)animate(Rect(current),Rect(base),g,end=finish) else finish()
    }

    private fun resetSurface(taskId:Int) {
        if(pin?.taskId==taskId)pin=null
        backend.releaseMiniTransform(taskId)
        Guard.call("live-reset") {
            val leash=backend.surface(taskId) ?: surface ?: return@call
            val position=backend.position(taskId) ?: return@call
            val bounds=backend.info(taskId)?.let{backend.record(it)?.bounds} ?: base
            val crop=if(!bounds.isEmpty)Rect(0,0,bounds.width(),bounds.height()) else null
            backend.transform(taskId,leash,1f,position.x.toFloat(),position.y.toFloat(),
                retain=false,crop=crop,cornerRadius=backend.nativeCornerRadius(taskId))
            backend.alpha(leash,1f)
        }
    }

    fun recoverIfNeeded() {
        if(pending.isEmpty()||id>=0||recovering)return
        val fields=pending.split(',').mapNotNull{it.toIntOrNull()}
        if(fields.size!=5){journal.edit().remove(journalKey).commit();pending="";return}
        if(backend.info(fields[0])==null){
            journal.edit().remove(journalKey).commit()
            pending=""
            Guard.info("live recovery cleared dead task=${fields[0]}")
            return
        }
        if(backend.surface(fields[0])==null||!backend.canRestore())return
        recovering=true
        val marker=pending
        resetSurface(fields[0])
        backend.restore(fields[0],Rect(fields[1],fields[2],fields[3],fields[4])) callback@{ ok ->
            if(marker!=pending)return@callback
            recovering=false
            if(ok){journal.edit().remove(journalKey).commit();pending="";Guard.info("live recovery accepted task=${fields[0]}")}
        }
    }

    fun tick() {
        if(id<0)return
        val oldWidth=sw;val oldHeight=sh
        refreshDisplay()
        if(oldWidth!=sw||oldHeight!=sh)reflowDeadline=SystemClock.uptimeMillis()+4500
        if(backend.locked()){restoreImmediately();return}
        if(protected&&!lease.refresh()){
            Guard.info("live protection expired; restoring standard window");restoreImmediately();return
        }
        if((phase==Phase.PREPARING||!protected)&&SystemClock.uptimeMillis()-started>6000){restoreImmediately();return}
        if(reflowPending)return
        if(phase !in setOf(Phase.MINI,Phase.DOCKED,Phase.DRAGGING,Phase.SETTLING))return
        val task=Guard.call("live-info"){backend.info(id)?.let{backend.record(it)}}
        if((task==null||task.mode==5&&!task.visible)&&SystemClock.uptimeMillis()<reflowDeadline){
            val g=generation
            scheduleReflowTick(g)
            return
        }
        if(task!=null&&task.mode==5&&task.visible&&(reflowRetry||oldWidth!=sw||oldHeight!=sh||task.bounds!=base)){
            reflow(task);return
        }
        if(task==null||task.mode!=5||!task.visible||task.bounds!=base){
            resetSurface(id)
            if(Guard.call("live-focus-reset"){backend.restoreFocus(id);true}==true)
                journal.edit().remove(journalKey).commit()
            else pending=journal.getString(journalKey,"").orEmpty()
            clear()
            Guard.info("live mini ended after native task visibility/geometry change")
        }else if(Guard.call("live-animation"){
            updatePlaybackState()
            render(if(phase in setOf(Phase.DRAGGING,Phase.SETTLING))current else target);true
        }!=true)restoreImmediately()
    }
    private fun updatePlaybackState() {
        if(id<0)return
        val task=backend.info(id)?.let{backend.record(it)} ?: return
        val active=phase in setOf(Phase.MINI,Phase.DRAGGING,Phase.SETTLING)
        val observerSelected=backend.miniPlaybackSelected(task.packageName)
        val keepPlayback=backend.miniKeepPlaybackSelected(task.packageName)
        val eligible=active&&(!stackControlled||playbackStackFront)&&MiniPlaybackPolicy.eligible(
            task.packageName,observerSelected,keepPlayback,protected,task.visible,task.mode,independentDock,backend.locked())

        if(!eligible){
            playbackTicket++;playbackAttempted=false
            if(playbackFocusable&&Guard.call("mini-playback-pause"){backend.setFocusable(id,false);true}==true)
                playbackFocusable=false
            return
        }
        if(playbackAttempted||phase!=Phase.MINI)return
        playbackAttempted=true
        if(!MiniPlaybackPolicy.needsAppObserver(task.packageName)){
            if(!playbackFocusable&&Guard.call("mini-playback-resume"){backend.setFocusable(id,true);true}==true){
                playbackFocusable=true
                Guard.info("visible mini multi-resume enabled task=$id pkg=${task.packageName}; system-side keep-playback")
            }
            return
        }
        val ticket=++playbackTicket;val g=generation;val taskId=id
        playbackIntent.request(id){allowed,reason->
            if(ticket!=playbackTicket||g!=generation||taskId!=id||!protected||independentDock||backend.locked()||
                (stackControlled&&!playbackStackFront)||
                phase !in setOf(Phase.MINI,Phase.DRAGGING,Phase.SETTLING))return@request
            val current=backend.info(id)?.let{backend.record(it)} ?: return@request
            val curObserver=backend.miniPlaybackSelected(current.packageName)
            val curKeep=backend.miniKeepPlaybackSelected(current.packageName)
            if(!MiniPlaybackPolicy.eligible(current.packageName,curObserver,curKeep,
                    protected,current.visible,current.mode,independentDock,backend.locked()))return@request
            val unavailable=MiniPlaybackPolicy.isObserverUnavailableReason(reason)
            if(allowed||MiniPlaybackPolicy.allowWithoutObserver(curKeep,unavailable)){
                if(!playbackFocusable&&Guard.call("mini-playback-resume"){backend.setFocusable(id,true);true}==true)
                    playbackFocusable=true
                Guard.info(if(allowed)"visible mini multi-resume enabled task=$id; native player lifecycle retained"
                    else "visible mini multi-resume fallback enabled task=$id; observer unavailable reason=$reason")
            }else{
                if(playbackFocusable&&Guard.call("mini-playback-pause"){backend.setFocusable(id,false);true}==true)
                    playbackFocusable=false
                Guard.info("visible mini multi-resume not enabled task=$id; observer reason recorded separately")
            }
    }
        }
    fun onTaskChanged(taskId:Int){if(taskId==id)tick()}
    private fun scheduleReflowTick(g:Int){
        if(reflowTick!=null)return
        reflowTick=Runnable{reflowTick=null;if(g==generation)tick()}.also{main.postDelayed(it,32)}
    }
    private fun reflow(task:TaskRecord){
        playbackTicket++
        if(!playbackFocusable)playbackAttempted=false
        val now=SystemClock.uptimeMillis()
        if(reflowDeadline==0L)reflowDeadline=now+4500
        val destination=backend.portraitBounds(task)
        val layout=GestureDisplay.metrics(context)
        val layoutWidth=layout.widthPixels;val layoutHeight=layout.heightPixels
        val g=++generation
        val reflowId=id
        reflowPending=true;reflowRetry=false;selecting=false;animator?.cancel();downActive=false;pendingDrag=null;recycleVelocity()
        val wasDocked=independentDock
        val wasLeft=dockLeft;val previousCenter=dockCenter
        val done:(Boolean)->Unit=done@{ok->
            if(g!=generation)return@done
            reflowPending=false
            val current=backend.info(id)?.let{backend.record(it)}
            val display=GestureDisplay.metrics(context)
            if(!ok||current==null||current.mode!=5||!current.visible||
                display.widthPixels!=layoutWidth||display.heightPixels!=layoutHeight){
                if((current==null||current.mode==5)&&SystemClock.uptimeMillis()<reflowDeadline){
                    reflowRetry=true
                    Guard.info("live mini rotation retry task=$id; retaining mini ownership")
                    scheduleReflowTick(g)
                }else restoreImmediately()
                return@done
            }
            base=Rect(current.bounds);parent=Point(current.position);surface=backend.surface(id)
            if(surface==null||!journal.edit().putString(journalKey,encode(id,base)).commit()){restoreImmediately();return@done}
            val mini=backend.miniBounds(base,wasDocked&&dockLeft) ?: run{restoreImmediately();return@done}
            miniSize=Point(mini.width(),mini.height())
            phase=if(wasDocked)Phase.DOCKED else Phase.MINI
            target=if(wasDocked)rect(MiniDragPolicy.dock(dockLeft,dockCenter,mini.width(),mini.height(),sw,sh,density)) else mini
            if(Guard.call("live-rotation-layout"){render(target);true}!=true){restoreImmediately();return@done}
            Guard.info("live mini portrait reflow task=$id bounds=${base.toShortString()}")
            reflowDeadline=0L
            changed(id)
        }
        val safeDone:(Boolean)->Unit={ok->
            if(Guard.call("live-rotation-callback"){done(ok);true}!=true){
                reflowPending=false;Guard.call("live-rotation-recover"){restoreImmediately()}
            }
        }
        if(destination==task.bounds)safeDone(true)
        else if(!backend.resizeFreeformInSync(task,destination,{tx,leash,actual,position->
            check(g==generation&&reflowId==id){"Mini changed during rotation resize"}
            val metrics=GestureDisplay.metrics(context)
            val placement=LivePlacement.miniPlace(metrics.widthPixels,metrics.heightPixels,
                actual.width(),actual.height(),density,wasDocked&&wasLeft)
                ?: error("Mini display geometry unavailable")
            val next=if(wasDocked)MiniDragPolicy.dock(wasLeft,previousCenter,placement.width,placement.height,
                metrics.widthPixels,metrics.heightPixels,density)
                else MiniDragPolicy.Box(placement.x,placement.y,placement.width,placement.height)
            val geometry=LivePlacement.miniTransform(
                LivePlacement.Bounds(actual.left,actual.top,actual.width(),actual.height()),position.x,position.y,
                LivePlacement.Bounds(next.x,next.y,next.width,next.height),topCrop())
            val crop=Rect(geometry.cropLeft,geometry.cropTop,geometry.cropRight,geometry.cropBottom)
            val alpha=MiniDragPolicy.dockedSurfaceAlpha(wasDocked)*stackAlpha
            // Publish only immutable surface state here; UI/journal updates stay in done on main.
            val radius=LivePlacement.scaledCornerRadius(backend.nativeCornerRadius(reflowId),geometry.scale,crop.width(),crop.height())
            pin=SurfacePin(reflowId,leash,geometry.scale,geometry.x,geometry.y,alpha,crop,stackLayer,radius)
            backend.transform(reflowId,leash,geometry.scale,geometry.x,geometry.y,alpha,crop=crop,transaction=tx,layer=stackLayer,cornerRadius=radius)
            Guard.info("live mini rotation committed in sync task=$reflowId crop=${actual.width()}x${actual.height()}")
        },{
            val metrics=GestureDisplay.metrics(context)
            g==generation&&reflowId==id&&metrics.widthPixels==layoutWidth&&metrics.heightPixels==layoutHeight
        },safeDone))safeDone(false)
    }
    fun onTaskVanished(taskId:Int){
        if(taskId==id){journal.edit().remove(journalKey).commit();clear()}
    }
    private fun clear() {
        playbackTicket++;playbackAttempted=false;playbackFocusable=false;playbackStackFront=true
        Guard.call("stack-layer-clear"){restoreStackLayer()}
        stackLayer=null;stackNativeLayer=null
        stackTouchLocked=false
        stackControlled=false;stackAlpha=1f
        reflowPending=false
        reflowRetry=false
        reflowDeadline=0L
        reflowTick?.let{main.removeCallbacks(it)};reflowTick=null
        if(id>=0)backend.releaseMiniTransform(id)
        pin=null
        downActive=false;pendingDrag=null;dragFrame=false;closeHint.hide();badgeTicket++;recycleVelocity();badge=null
        selecting=false;dockedFromStandard=false;activeTopCrop=0
        requestedTouchable=true
        frame.clearForTask(id);handoff=""
        lease.release();protected=false
        generation++;animator?.cancel();animator=null
        touch?.let{Guard.call("live-overlay-release"){wm.removeViewImmediate(it)}}
        val finishedId=id
        touch=null;params=null;surface=null;id=-1;phase=Phase.IDLE;independentDock=false
        if(finishedId>=0)released(finishedId)
    }
    private fun encode(taskId:Int,rect:Rect)="$taskId,${rect.left},${rect.top},${rect.right},${rect.bottom}"
}
