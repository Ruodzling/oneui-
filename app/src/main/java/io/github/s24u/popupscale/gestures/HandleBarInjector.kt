package io.github.s24u.popupscale.gestures

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.roundToInt
import de.robv.android.xposed.XposedHelpers as X

/** Independent input window: bottom in portrait, inner-facing side in landscape. */
class HandleBarInjector(private val context: Context, private var task: TaskRecord,
                        private val leash: Any, private val backend: TaskBackend,
                        private val loader: ClassLoader,
                        private val onHangMini: (TaskRecord, Rect, Boolean) -> Boolean = { _, _, _ -> false }) {
    private val density = context.resources.displayMetrics.density
    private val wm = context.applicationContext.getSystemService(WindowManager::class.java)
    private var rootView: FrameLayout? = null
    private var surface: Any? = null
    private var barView: View? = null
    private var handleLayout:GesturePolicy.HandleLayout?=null
    private var displayMetrics=GestureDisplay.metrics(context)
    private var gestureEdge=0
    private var motion: TaskMotionAnimator? = null
    private var gestureTask: TaskRecord? = null
    private var velocity: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var active = false
    private var resizePending = false
    private var gestureGeneration=0
    private var shadeObscured = false
    private val opacityHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var opacityLongPress:Runnable?=null
    private fun clearOpacityLongPress(){opacityLongPress?.let{opacityHandler.removeCallbacks(it)};opacityLongPress=null}
    private var layoutPending=false
    private var ownerFocused=task.focused
    fun isAttached()=rootView?.isAttachedToWindow==true
    fun setOwnerFocused(focused:Boolean){
        if(ownerFocused==focused)return
        ownerFocused=focused
        if(!focused){
            layoutPending=false
            if(active||resizePending)cancel(restoreHandle=false)
        }
        // Hide immediately; reveal only after updateTask submits the new owner's geometry.
        if(!focused)setHandleVisible(false)
    }
    fun setLayoutPending(pending:Boolean){layoutPending=pending;setHandleVisible(!pending)}
    private fun dp(value: Int) = (value * density).toInt()
    fun setShadeObscured(obscured: Boolean) {
        if (shadeObscured == obscured) return
        shadeObscured = obscured
        if (obscured) {
            if(active)cancel(restoreHandle=false)
            rootView?.visibility = View.GONE
        } else {
            setHandleVisible(true)
        }
    }
    private fun setHandleVisible(visible: Boolean) {
        if(visible&&(layoutPending||shadeObscured||!ownerFocused||active||resizePending))return
        rootView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (visible) barView?.alpha = 1f
    }
    fun attach() {
        displayMetrics=GestureDisplay.metrics(context)
        val layout=layoutFor(task.bounds)
        if (GesturePolicy.handleBarWidth(task.bounds.width()) < dp(48) || task.bounds.height() < dp(26)*3) return
        handleLayout=layout
        val root = FrameLayout(context)
        root.visibility=if(shadeObscured)View.INVISIBLE else View.VISIBLE
        val bar = View(context)
        barView = bar
        bar.background = GradientDrawable().apply {
            setColor(Color.argb(235, 225, 225, 230))
            setStroke(maxOf(1, (1 * density).toInt()), Color.argb(65, 0, 0, 0))
            cornerRadius = dp(3).toFloat()
        }
        root.addView(bar, FrameLayout.LayoutParams(layout.barWidth,layout.barHeight,Gravity.TOP or Gravity.LEFT).apply {
            leftMargin=layout.barX;topMargin=layout.barY
        })
        root.contentDescription = description(layout.edge)
        root.setOnTouchListener { _, event -> touch(event) }
        val params = WindowManager.LayoutParams(layout.width,layout.height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        params.title = "PopupScaleBottomHandle#${task.id}"
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x=layout.x;params.y=layout.y
        params.windowAnimations = 0
        if(android.os.Build.VERSION.SDK_INT>=30){
            params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            params.setFitInsetsTypes(0)
        }
        // Optional Samsung cosmetic API must never prevent the actual overlay from attaching.
        if(X.findMethodExactIfExists(params.javaClass,"setCanPlayMoveAnimation",java.lang.Boolean.TYPE)!=null)
            Guard.call("handle-move-animation"){X.callMethod(params,"setCanPlayMoveAnimation",false)}
        wm.addView(root, params)
        rootView = root
        Guard.info("handle attached task=${task.id} edge=${layout.edge} x=${params.x} y=${params.y} width=${layout.width} height=${layout.height}")
    }
    private fun layoutFor(bounds:Rect)=GesturePolicy.handleLayout(bounds.left,bounds.top,bounds.width(),bounds.height(),
        displayMetrics.widthPixels,displayMetrics.heightPixels,density,handleLayout?.edge ?: 0,active||resizePending)
    private fun description(edge:Int)=if(edge==0)"小窗手势条：上拖缩小挂起，上甩关闭，下拖放大，下甩全屏，长按调整透明度"
        else "小窗侧边手势条：向窗内拖动缩小挂起，向内甩动关闭，向外拖动放大至全屏，长按调整透明度"
    fun moveTo(bounds: Rect) {
        // During our own scale gesture the bar is invisible and this window already owns
        // the pointer stream. Relayout only at completion, not on every hidden MOVE frame.
        if(active||resizePending||!ownerFocused)return
        val root = rootView ?: return
        val p = root.layoutParams as WindowManager.LayoutParams
        val layout=layoutFor(bounds)
        if(layout==handleLayout)return
        handleLayout=layout
        root.contentDescription=description(layout.edge)
        val bar=barView ?: return
        val lp=bar.layoutParams as FrameLayout.LayoutParams
        if(lp.width!=layout.barWidth||lp.height!=layout.barHeight||lp.leftMargin!=layout.barX||lp.topMargin!=layout.barY){
            lp.width=layout.barWidth;lp.height=layout.barHeight;lp.leftMargin=layout.barX;lp.topMargin=layout.barY
            bar.layoutParams=lp
        }
        if(p.x==layout.x&&p.y==layout.y&&p.width==layout.width&&p.height==layout.height)return
        p.x=layout.x;p.y=layout.y;p.width=layout.width;p.height=layout.height
        wm.updateViewLayout(root, p)
    }
    fun updateTask(newTask: TaskRecord,displayBounds:Rect?=null) {
        require(newTask.id==task.id){"Handle cannot change task owner"}
        val metrics=GestureDisplay.metrics(context)
        val displayChanged=metrics.widthPixels!=displayMetrics.widthPixels||metrics.heightPixels!=displayMetrics.heightPixels
        val gestureGeometryChanged=active&&gestureTask?.bounds!=newTask.bounds
        if(displayChanged||gestureGeometryChanged){
            gestureGeneration++
            val hadMotion=motion!=null
            motion?.abandon();motion=null;gestureTask=null;active=false;resizePending=false;gestureEdge=0
            velocity?.recycle();velocity=null
            displayMetrics=metrics;handleLayout=null
            if(hadMotion)Guard.call("handle-rotation-reset"){backend.resetChangedGesture(newTask)}
            barView?.alpha=1f
        }
        val gainedFocus=!ownerFocused&&newTask.focused
        task = newTask
        setOwnerFocused(newTask.focused)
        if(active||resizePending)return
        displayMetrics=metrics
        moveTo(displayBounds ?: newTask.bounds)
        if(gainedFocus)setHandleVisible(true)
    }
    private fun touch(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL||event.actionMasked==MotionEvent.ACTION_POINTER_DOWN)clearOpacityLongPress()
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&
            (kotlin.math.abs(event.rawX-downX)>android.view.ViewConfiguration.get(context).scaledTouchSlop||
             kotlin.math.abs(event.rawY-downY)>android.view.ViewConfiguration.get(context).scaledTouchSlop))clearOpacityLongPress()
        if (backend.locked()) {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)Guard.info("handle blocked by lock state task=${task.id}")
            cancel(); return true
        }
        return try {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if(resizePending)return true
                    cancel()
                    // A decoration can be created while the mode-change transition still
                    // reports the old parent position. Never animate that cached snapshot.
                    val current=backend.info(task.id)?.let { backend.record(it) }
                    if(current==null||current.mode!=5||!current.visible||!current.focused)return true
                    rootView?.requestUnbufferedDispatch(event)
                    gestureTask=current; active = true
                    downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY
                    velocity = VelocityTracker.obtain()?.also { it.addMovement(event) }
                    val miniBounds=backend.miniBounds(current.bounds)
                    val miniScale=miniBounds?.let{minOf(it.width().toFloat()/current.bounds.width(),it.height().toFloat()/current.bounds.height())} ?: 0.3f
                    val metrics = GestureDisplay.metrics(backend.context)
                    displayMetrics=metrics
                    gestureEdge=layoutFor(current.bounds).edge
                    val screenWidth = metrics.widthPixels
                    val screenHeight = metrics.heightPixels
                    val maxScale = if (current.bounds.width() > 0) {
                        (LivePlacement.maxResizeWidth(screenWidth,screenHeight,current.bounds.height().toFloat()/current.bounds.width(),density).toFloat()/current.bounds.width()).coerceAtLeast(1.0f)
                    } else 1.08f
                    val minAllowedWidth = (220f * density).roundToInt()
                    val minResizeScale = if (current.bounds.width() > 0) {
                        (minAllowedWidth.toFloat() / current.bounds.width().toFloat()).coerceIn(0.1f, 1.0f)
                    } else 0.75f
                    val motionOwner=Any()
                    motion = TaskMotionAnimator(
                        backend.surface(task.id) ?: leash,
                        current,
                        density,
                        caption = backend.captionState(task.id),
                        updateCaptionPin = { pin ->
                            backend.pinCaptionTransform(current.id,pin)
                        },
                        updateTaskPin = { pin -> backend.pinTaskTransform(current.id,motionOwner,pin) },
                        followBounds = { bounds -> moveTo(bounds) },
                        miniScale = miniScale,
                        maxScale = maxScale,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        minResizeScale = minResizeScale,
                        sideHandle=gestureEdge!=0
                    )
                    Guard.info("handle down task=${task.id} parent=${current.position.x},${current.position.y} " +
                        "bounds=${current.bounds.toShortString()} maxScale=$maxScale edge=$gestureEdge")
                    if(backend.opacityPanel!=null){
                        val id=current.id
                        opacityLongPress=Runnable{
                            opacityLongPress=null
                            if(active&&!shadeObscured&&!layoutPending){
                                cancel();Guard.call("handle-opacity-panel"){backend.opacityPanel?.invoke(id)}
                            }
                        }.also{opacityHandler.postDelayed(it,android.view.ViewConfiguration.getLongPressTimeout().toLong())}
                    }
                }
                MotionEvent.ACTION_MOVE -> if (active) {
                    velocity?.addMovement(event); lastX = event.rawX; lastY = event.rawY
                    val axis=GesturePolicy.handleMotion(gestureEdge,lastX-downX,lastY-downY)
                    if (kotlin.math.abs(axis.main) > dp(2)) barView?.alpha = 0f
                    motion?.follow(axis.main)
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    Guard.info("handle cancel task=${task.id} action=${event.actionMasked}"); cancel()
                }
                MotionEvent.ACTION_UP -> if(active) {
                    velocity?.addMovement(event); velocity?.computeCurrentVelocity(1000)
                    val axis=GesturePolicy.handleMotion(gestureEdge,event.rawX-downX,event.rawY-downY,
                        velocity?.xVelocity ?: 0f,velocity?.yVelocity ?: 0f)
                    val vy=axis.velocity;val dx=axis.cross;val dy=axis.main
                    val target=gestureTask ?: return true
                    val metrics = GestureDisplay.metrics(backend.context)
                    val screenWidth = metrics.widthPixels
                    val screenHeight = metrics.heightPixels
                    if((gestureEdge!=0)!=(screenWidth>screenHeight)){cancel();return true}
                    motion?.follow(dy)
                    val previewRect=motion?.currentRect() ?: Rect(target.bounds)
                    val miniTarget=backend.miniBounds(target.bounds,gestureEdge==2)
                    val atMiniSize=miniTarget!=null && previewRect.width()<=miniTarget.width()+2 &&
                        previewRect.height()<=miniTarget.height()+2
                    val result = GesturePolicy.handle(
                        dx, dy, vy, density,
                        cancelled = false,
                        windowWidth = target.bounds.width(),
                        screenWidth = screenWidth,
                        atMiniSize = atMiniSize,
                        sideHandle=gestureEdge!=0
                    )

                    Guard.info("handle up task=${task.id} dx=${dx.toInt()} " +
                        "dy=${dy.toInt()} vyDp=${(vy/density).toInt()} edge=$gestureEdge result=$result")
                    active = false
                    val toBack = result == GesturePolicy.Result.CLOSE && backend.closeHandleToBack()
                    if (result == GesturePolicy.Result.CLOSE && !toBack) backend.markClosing(target.id)
                    val animator = motion
                    val generation=gestureGeneration
                    val startRect = animator?.currentRect() ?: Rect(target.bounds)
                    val minAllowedWidth = (220f * density).roundToInt()
                    val aspectRatio = target.bounds.height().toFloat() / target.bounds.width().toFloat()
                    val maxAllowedWidth = LivePlacement.maxResizeWidth(screenWidth,screenHeight,aspectRatio,density).coerceAtLeast(minAllowedWidth)
                    val targetW = startRect.width().coerceIn(minAllowedWidth, maxAllowedWidth)
                    val targetH = (targetW * aspectRatio).roundToInt()
                    val left = (target.bounds.centerX() - targetW / 2).coerceIn(0, (screenWidth - targetW).coerceAtLeast(0))
                    val topInset = if(screenWidth>screenHeight)0 else (24*density).toInt()
                    val top = (target.bounds.centerY() - targetH / 2).coerceIn(topInset, (screenHeight - targetH).coerceAtLeast(topInset))
                    val clampedStartRect = Rect(left, top, left + targetW, top + targetH)
                    if (result == GesturePolicy.Result.RESIZE) {
                        resizePending=true
                        barView?.alpha = 0f
                    } else if (result == GesturePolicy.Result.RESTORE) {
                        barView?.alpha = 1f
                    } else {
                        setHandleVisible(false)
                    }
                    animator?.finish(result, targetBounds = clampedStartRect, onRestoreEnd = restored@{
                        if(generation!=gestureGeneration)return@restored
                        motion=null;gestureTask=null
                        resizePending=false
                        if (result == GesturePolicy.Result.RESIZE) {
                            moveTo(clampedStartRect)
                        } else moveTo(target.bounds)
                        barView?.alpha = 1f
                        setHandleVisible(true)
                    }) action@{
                        if(generation!=gestureGeneration)return@action false
                        val latest=backend.info(task.id)?.let { backend.record(it) }
                        check(!backend.locked()&&latest!=null&&latest.mode==5&&latest.visible&&latest.focused&&latest.bounds==target.bounds) {
                            "Task changed during gesture"
                        }
                        when (result) {
                            GesturePolicy.Result.RESIZE -> backend.resizeFreeform(latest, clampedStartRect) resized@{ ok ->
                                if(generation!=gestureGeneration)return@resized
                                resizePending=false
                                if(ok){
                                    backend.info(target.id)?.let{backend.record(it)}?.let{updateTask(it)}
                                }else{
                                    animator?.rejectResize()
                                }
                                if(motion===animator){motion=null;gestureTask=null}
                                barView?.alpha=1f
                                setHandleVisible(true)
                            }
                            GesturePolicy.Result.CLOSE -> {
                                if (toBack) backend.background(latest, { generation==gestureGeneration }) { ok ->
                                    if (!ok && generation==gestureGeneration) {
                                        animator.rejectResize()
                                        motion=null
                                        gestureTask=null
                                        moveTo(target.bounds)
                                        setHandleVisible(true)
                                    }
                                } else { backend.close(latest); true }
                            }
                            GesturePolicy.Result.FULLSCREEN -> { backend.maximize(target); true }
                            GesturePolicy.Result.HANG_MINI -> onHangMini(latest,startRect,gestureEdge==2)
                            GesturePolicy.Result.RESTORE -> true
                        }
                    }
                    velocity?.recycle(); velocity = null
                }
            }
            true
        } catch(error: Throwable) { Guard.failure("handle-touch",error); cancel(); true }
    }
    private fun cancel(restoreHandle:Boolean=true) {
        clearOpacityLongPress()
        gestureGeneration++
        active = false
        val position=if(motion!=null)Guard.call("handle-reset-position"){backend.position(task.id)} else null
        motion?.dispose(position); motion = null; gestureTask=null;gestureEdge=0
        if(restoreHandle&&rootView!=null)Guard.call("handle-cancel-layout"){moveTo(task.bounds)}
        barView?.alpha = 1f
        if(restoreHandle)setHandleVisible(true)
        velocity?.recycle(); velocity = null
    }
    fun release() {
        cancel(restoreHandle=false)
        barView = null
        rootView?.let { view -> Guard.call("handle-release") { wm.removeViewImmediate(view) } }
        rootView = null
    }
}
