package io.github.s24u.popupscale.gestures

import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/** Geometry of the visible application buffer on the notification animator's linear clock. */
class NotificationBannerMotion(
    source: Rect,
    destination: RectF,
    root: Point,
    nativeState: Any
) {
    private val start = RectF(source).apply { offset(-root.x.toFloat(), -root.y.toFloat()) }
    private val end = RectF(destination).apply { offset(-root.x.toFloat(), -root.y.toFloat()) }
    private val type = Class.forName("com.android.systemui.animation.TransitionAnimator\$State", false, nativeState.javaClass.classLoader)
    private val drawing = type.getConstructor().newInstance()
    private val left = type.getField("left")
    private val right = type.getField("right")
    private val top = type.getField("top")
    private val bottom = type.getField("bottom")
    private val topRadius = type.getField("topCornerRadius")
    private val bottomRadius = type.getField("bottomCornerRadius")
    val lastBounds = Rect()
    private var sampled = -1

    fun frame(progress: Float, nativeState: Any): Any {
        val t = progress.coerceIn(0f, 1f)
        // Quadratic ease-out starts moving on release and reaches zero speed at the destination.
        val mix = t * (2f - t)
        fun edge(from: Float, to: Float) = (from + (to - from) * mix).roundToInt()
        lastBounds.set(edge(start.left, end.left), edge(start.top, end.top),
            edge(start.right, end.right), edge(start.bottom, end.bottom))
        left.setInt(drawing, lastBounds.left)
        top.setInt(drawing, lastBounds.top)
        right.setInt(drawing, lastBounds.right)
        bottom.setInt(drawing, lastBounds.bottom)
        topRadius.setFloat(drawing, topRadius.getFloat(nativeState))
        bottomRadius.setFloat(drawing, bottomRadius.getFloat(nativeState))
        return drawing
    }

    fun newSample(progress: Float): Boolean {
        val stage = if (progress >= 0.95f) 2 else if (progress >= 0.5f) 1 else 0
        if (stage <= sampled) return false
        sampled = stage
        return true
    }
}
