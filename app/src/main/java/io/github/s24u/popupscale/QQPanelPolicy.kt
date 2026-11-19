package io.github.s24u.popupscale

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Expand QQ's compact freeform panel in virtual layout units, leaving message space above. */
object QQPanelPolicy {
    fun supportedVersion(version: Long): Boolean = version == 16368L || version == 16750L
    fun height(enabled: Boolean, version: Long, user: Int, display: Int, mode: Int,
               scale: Float, density: Float, viewport: Int, original: Int): Int {
        if (!enabled || !supportedVersion(version) || user != 0 || display != 0 || mode != 5 ||
            !scale.isFinite() || scale !in 0.05f..0.999f ||
            !density.isFinite() || density !in 0.5f..10f || viewport <= 0 || original <= 0) return original
        val desired = (360f * density).roundToInt()
        val limit = (viewport * 0.5f).roundToInt()
        return max(original, min(desired, limit))
    }
    fun layoutWidth(physical: Int, scale: Float): Int =
        if (physical > 0 && scale.isFinite() && scale in 0.05f..0.999f)
            (physical / scale).roundToInt() else physical
}
