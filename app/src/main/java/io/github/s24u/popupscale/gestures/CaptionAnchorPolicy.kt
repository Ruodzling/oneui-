package io.github.s24u.popupscale.gestures

object CaptionAnchorPolicy {
    /** Keep the visible pill just outside the content edge, not in the old header's centre. */
    @JvmStatic fun shift(captionHeight:Float,visualBottom:Float,density:Float):Float {
        if(!captionHeight.isFinite()||captionHeight<=0||!visualBottom.isFinite()||
            !density.isFinite()||density<=0)return 0f
        return (captionHeight-2f*density-visualBottom).coerceIn(0f,captionHeight/2f)
    }
}
