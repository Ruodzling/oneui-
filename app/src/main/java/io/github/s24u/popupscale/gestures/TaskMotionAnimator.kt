package io.github.s24u.popupscale.gestures

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Point
import android.graphics.Rect
import android.view.animation.DecelerateInterpolator
import de.robv.android.xposed.XposedHelpers as X
import kotlin.math.roundToInt

/** Only changes the borrowed task leash. Own transactions are closed; the task leash is never released. */
class TaskMotionAnimator(private val surface: Any, private var task: TaskRecord,
                         private val density: Float,
                         private val caption: TaskBackend.CaptionState? = null,
                         private val updateCaptionPin:(TaskBackend.CaptionTransform?)->Unit = {},
                         private val updateTaskPin:(TaskBackend.SurfaceTransform?)->Unit = {},
                         private val followBounds:((Rect)->Unit)?=null,
                         private val miniScale: Float = 0.3f,
                         private val maxScale: Float = 1.08f,
                         private val screenWidth: Int = 0,
                         private val screenHeight: Int = 0,
                         private val minResizeScale: Float = 0.75f,
                         private val sideHandle:Boolean=false) {
    private var animator: ValueAnimator? = null
    private var dy = 0f
    private var sx = 1f; private var sy = 1f; private var alpha = 1f
    private var x = task.position.x.toFloat(); private var y = task.position.y.toFloat()
    private var restoreX = x; private var restoreY = y
    private var committed = false
    private var keepHandleStationary = false
    fun currentRect(): Rect {
        val w = (task.bounds.width() * sx).roundToInt().coerceAtLeast(1)
        val aspectRatio = task.bounds.height().toFloat() / task.bounds.width().toFloat()
        val h = (w * aspectRatio).roundToInt().coerceAtLeast(1)
        val left = (task.bounds.left + x - restoreX).roundToInt()
        val top = (task.bounds.top + y - restoreY).roundToInt()
        return Rect(left, top, left + w, top + h)
    }
    fun follow(delta: Float) {
        dy = delta
        val scale = if (delta >= 0f) {
            GesturePolicy.followScale(delta, density, miniScale, maxScale, task.bounds.width())
        } else {
            GesturePolicy.shrinkScale(delta,density,miniScale,minResizeScale,sideHandle)
        }
        sx = scale; sy = scale
        alpha = 1f
        val w = task.bounds.width() * scale
        val h = task.bounds.height() * scale
        var rawX = restoreX + (task.bounds.width() - w) / 2f
        var rawY = restoreY + (task.bounds.height() - h) / 2f
        if (screenWidth > 0 && screenHeight > 0) {
            if (rawX < 0f) rawX = 0f
            if (rawX + w > screenWidth) rawX = (screenWidth - w).coerceAtLeast(0f)
            val topInset = if(screenWidth>screenHeight)0f else 24f*density
            if (rawY < topInset) rawY = topInset
            if (rawY + h > screenHeight) rawY = (screenHeight - h).coerceAtLeast(topInset)
        }
        x = rawX
        y = rawY
        keepHandleStationary = true
        render()
        followBounds?.invoke(currentRect())
    }
    fun finish(result: GesturePolicy.Result, targetBounds: Rect? = null, 
               onRestoreEnd: (() -> Unit)? = null, action: () -> Boolean) {
        keepHandleStationary = result!=GesturePolicy.Result.FULLSCREEN && result!=GesturePolicy.Result.HANG_MINI
        if(result==GesturePolicy.Result.FULLSCREEN) {
            // Return our borrowed surface to its native baseline before the ROM takes
            // ownership of crop, caption removal and the actual fullscreen transition.
            animator?.cancel(); reset()
            try {
                if (action()) {
                    committed=true
                    Guard.info("task transition requested task=${task.id} result=FULLSCREEN")
                } else onRestoreEnd?.invoke()
            } catch(error:Throwable) { Guard.failure("task-commit",error); reset(); onRestoreEnd?.invoke() }
            return
        }
        if(result==GesturePolicy.Result.HANG_MINI) {
            animator?.cancel()
            // The mini controller renders and may release this handle reentrantly.
            // Do not let dispose reset a task whose surface ownership just changed.
            committed=true
            try {
                if (action()) {
                    Guard.info("task transition requested task=${task.id} result=HANG_MINI")
                    return
                }
            } catch(error:Throwable) { Guard.failure("task-commit",error) }
            committed=false
        }
        if (result == GesturePolicy.Result.RESIZE && targetBounds != null) {
            animator?.cancel()
            val startX = x; val startY = y; val startSx = sx; val startSy = sy
            val targetSx = targetBounds.width().toFloat() / task.bounds.width().toFloat()
            val targetSy = targetBounds.height().toFloat() / task.bounds.height().toFloat()
            val targetX = targetBounds.left.toFloat()
            val targetY = targetBounds.top.toFloat()
            var cancelled = false
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180; interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    val p = it.animatedValue as Float
                    sx = startSx + (targetSx - startSx) * p
                    sy = startSy + (targetSy - startSy) * p
                    x = startX + (targetX - startX) * p
                    y = startY + (targetY - startY) * p
                    if (Guard.call("surface-animation") { render(); true } != true) cancel()
                    followBounds?.invoke(currentRect())
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true; if (!committed) reset()
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        try {
                            committed = true
                            if (action()) {
                                Guard.info("task transition requested task=${task.id} result=RESIZE")
                                // Resize completion arrives from the Shell synchronized draw callback.
                            } else {
                                committed = false
                                reset()
                                onRestoreEnd?.invoke()
                            }
                        } catch (error: Throwable) {
                            committed = false
                            Guard.failure("task-commit", error)
                            reset()
                            onRestoreEnd?.invoke()
                        }
                    }
                })
                start()
            }
            return
        }
        val effective = if (result == GesturePolicy.Result.HANG_MINI || result == GesturePolicy.Result.RESIZE) GesturePolicy.Result.RESTORE else result
        val startX = x; val startY = y; val startSx = sx; val startSy = sy; val startAlpha = alpha
        val targetSx = when (effective) {
            GesturePolicy.Result.CLOSE -> GesturePolicy.closeTargetScale(startSx)
            else -> 1f
        }
        val targetSy = targetSx
        val targetX = restoreX + task.bounds.width() * (1f - targetSx) / 2f
        val targetY = restoreY + task.bounds.height() * (1f - targetSy) / 2f
        var cancelled = false
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                sx = startSx + (targetSx-startSx)*p; sy = startSy+(targetSy-startSy)*p
                x = startX+(targetX-startX)*p; y = startY+(targetY-startY)*p
                alpha = startAlpha+((if(effective == GesturePolicy.Result.CLOSE) 0f else 1f)-startAlpha)*p
                if (Guard.call("surface-animation") { render(); true } != true) cancel()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true; if(!committed)reset() }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    try {
                        if (effective == GesturePolicy.Result.CLOSE) {
                            // removeTask can synchronously trigger decoration release. Mark
                            // committed first so reentrant dispose cannot reveal the old frame.
                            committed = true
                            try {
                                if (action()) Guard.info("task commit task=${task.id} result=$effective")
                                else committed = false
                            } catch (error: Throwable) {
                                committed = false
                                throw error
                            }
                        }
                    } catch (error: Throwable) { Guard.failure("task-commit", error) }
                    finally {
                        if(!committed){
                            reset()
                            onRestoreEnd?.invoke()
                        }
                        // Successful close lets the removed task disappear. Fullscreen
                        // hands the restored surface to native Shell transitions above.
                    }
                }
            })
            start()
        }
    }
    fun rejectResize() { committed=false; reset() }
    /** Native rotation owns the new crop/position; never reset the old gesture frame. */
    fun abandon() {
        committed=true
        animator?.cancel();animator=null
        updateCaptionPin(null);updateTaskPin(null)
    }
    fun dispose(position: Point? = null) {
        updateCaptionPin(null)
        if(position != null) { restoreX=position.x.toFloat(); restoreY=position.y.toFloat() }
        animator?.cancel(); if (!committed) reset()
        updateTaskPin(null)
    }
    private fun reset() {
        sx = 1f; sy = 1f; alpha = 1f
        x = restoreX; y = restoreY
        keepHandleStationary = false
        Guard.call("surface-reset") { render() }
        updateTaskPin(null)
    }
    private fun render() {
        val isValid = (isValidMethod?.invoke(surface) as? Boolean) ?: (X.callMethod(surface, "isValid") == true)
        if (!isValid) return
        val tx = transactionClass?.getConstructor()?.newInstance() ?: X.newInstance(X.findClass("android.view.SurfaceControl\$Transaction", null))
        try {
            updateTaskPin(TaskBackend.SurfaceTransform(surface,sx,sy,x,y,alpha,
                Rect(0,0,task.bounds.width(),task.bounds.height())))
            if (setMatrixMethod != null) {
                setMatrixMethod?.invoke(tx, surface, sx, 0f, 0f, sy)
                setPositionMethod?.invoke(tx, surface, x, y)
                setAlphaMethod?.invoke(tx, surface, alpha)
                setCropMethod?.invoke(tx, surface, Rect(0, 0, task.bounds.width(), task.bounds.height()))
            } else {
                X.callMethod(tx, "setMatrix", surface, sx, 0f, 0f, sy)
                X.callMethod(tx, "setPosition", surface, x, y)
                X.callMethod(tx, "setAlpha", surface, alpha)
                X.callMethod(tx, "setCrop", surface, Rect(0, 0, task.bounds.width(), task.bounds.height()))
            }
            val captionState=caption
            if(captionState!=null && X.callMethod(captionState.surface,"isValid")==true){
                val child=captionState.surface
                val bounds=captionState.bounds
                if(keepHandleStationary && sx>0f && sy>0f){
                    // Compensate the parent transform so the native handle retains its pixel size.
                    val insetRight=task.bounds.width()-bounds.right
                    val screenX=(task.bounds.width()*sx-bounds.width())/2f+
                        (bounds.left-insetRight)/2f
                    val pin=TaskBackend.CaptionTransform(child,1f/sx,1f/sy,screenX/sx,bounds.top/sy,
                        bounds.left.toFloat(),bounds.top.toFloat(),Rect(task.bounds))
                    updateCaptionPin(pin)
                    pin.apply(tx)
                }else{
                    updateCaptionPin(null)
                    X.callMethod(tx,"setMatrix",child,1f,0f,0f,1f)
                    X.callMethod(tx,"setPosition",child,bounds.left.toFloat(),bounds.top.toFloat())
                }
            }
            if (applyMethod != null) applyMethod?.invoke(tx) else X.callMethod(tx, "apply")
        } finally {
            if (closeMethod != null) closeMethod?.invoke(tx) else X.callMethod(tx, "close")
        }
    }

    companion object {
        private val transactionClass: Class<*>? by lazy {
            try {
                Class.forName("android.view.SurfaceControl\$Transaction")
            } catch (t: Throwable) {
                null
            }
        }
        private val setMatrixMethod by lazy {
            try {
                transactionClass?.getMethod("setMatrix",
                    Class.forName("android.view.SurfaceControl"),
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            } catch (t: Throwable) {
                null
            }
        }
        private val setPositionMethod by lazy {
            try {
                transactionClass?.getMethod("setPosition",
                    Class.forName("android.view.SurfaceControl"),
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            } catch (t: Throwable) {
                null
            }
        }
        private val setAlphaMethod by lazy {
            try {
                transactionClass?.getMethod("setAlpha",
                    Class.forName("android.view.SurfaceControl"),
                    Float::class.javaPrimitiveType)
            } catch (t: Throwable) {
                null
            }
        }
        private val setCropMethod by lazy {
            try {
                transactionClass?.getMethod("setCrop",
                    Class.forName("android.view.SurfaceControl"),
                    Rect::class.java)
            } catch (t: Throwable) {
                null
            }
        }
        private val applyMethod by lazy {
            try {
                transactionClass?.getMethod("apply")
            } catch (t: Throwable) {
                null
            }
        }
        private val closeMethod by lazy {
            try {
                transactionClass?.getMethod("close")
            } catch (t: Throwable) {
                null
            }
        }
        private val isValidMethod by lazy {
            try {
                Class.forName("android.view.SurfaceControl").getMethod("isValid")
            } catch (t: Throwable) {
                null
            }
        }
    }
}
