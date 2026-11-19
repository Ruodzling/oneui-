package io.github.s24u.popupscale;

/** Pure fixed-canvas geometry, independent of Android. */
public final class ScalePolicy {
    public static final int MIN_REFERENCE_DP = 280;
    public static final int MAX_REFERENCE_DP = 600;
    public static final int DEFAULT_REFERENCE_DP = 500;
    private ScalePolicy() {}

    public static Canvas canvas(int mode, int width, int height, int density, int referenceDp) {
        // 5 = freeform. Other modes retain their normal, unscaled viewport.
        if (mode != 5 || width <= 0 || height <= 0 || density <= 0
                || referenceDp < MIN_REFERENCE_DP || referenceDp > MAX_REFERENCE_DP) return null;
        int virtualWidth = Math.max(1, (int) Math.round(referenceDp * density / 160.0));
        float scale = width / (float) virtualWidth;
        int virtualHeight = Math.max(1, Math.round(height / scale));
        return new Canvas(virtualWidth, virtualHeight, scale);
    }

    public static Canvas dialog(int hostWidth, int width, int heightLimit, int density, int referenceDp) {
        if (width <= 0) return null;
        Canvas host = canvas(5, hostWidth, heightLimit, density, referenceDp);
        if (host == null) return null;
        return new Canvas(Math.max(1, Math.round(width / host.scale)),
                Math.max(1, Math.round(heightLimit / host.scale)), host.scale);
    }

    public static int inputBottomOnScreen(int rootScreenTop, int visibleBottomInRoot,
            int rootHeight, int imeBottomInset) {
        return rootScreenTop + Math.min(visibleBottomInRoot, rootHeight - imeBottomInset);
    }

    /** Compensates unconsumed solid freeform caption bars when an edge-to-edge canvas scales down
     * its internal status-bar spacer; compact pill handles (16dp) keep native zero offset. */
    public static int captionOffset(boolean enabled, int mode, int viewportHeight,
            int density, float scale, int captionTop, int nativeTop, int statusBarPx) {
        if (!enabled || mode != 5 || viewportHeight <= 0 || density < 120 || density > 1000
                || !Float.isFinite(scale) || scale < 0.05f || scale > 2f) return 0;
        int handleLimit = Math.round(24f * density / 160f);
        int unconsumed = captionTop - Math.max(0, nativeTop);
        if (unconsumed <= handleLimit) return 0;
        int virtualStatus = statusBarPx > 0 ? statusBarPx : Math.round(32f * density / 160f);
        int scaledStatus = Math.max(0, Math.round(virtualStatus * scale));
        int offset = unconsumed - scaledStatus;
        return offset <= 0 ? 0 : Math.min(offset, viewportHeight / 3);
    }

    public static final class Canvas {
        public final int widthPx, heightPx;
        public final float scale;
        Canvas(int widthPx, int heightPx, float scale) {
            this.widthPx = widthPx; this.heightPx = heightPx; this.scale = scale;
        }
    }
}
