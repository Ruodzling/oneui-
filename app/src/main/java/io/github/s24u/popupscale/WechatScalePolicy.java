package io.github.s24u.popupscale;

/** WeChat can replace an Activity's supplied density with its own startup density. */
public final class WechatScalePolicy {
    private WechatScalePolicy() {}

    public static boolean usesCanvas(String name, boolean unified) {
        return !unified || "com.tencent.mm".equals(name);
    }

    public static boolean isLauncherUI(String activityName) {
        return "com.tencent.mm.ui.LauncherUI".equals(activityName);
    }

    public static boolean shouldScaleDialog(int mode, boolean enabled, boolean diagnostic) {
        return enabled && !diagnostic && mode == 5;
    }

    public static int portraitWidth(int maxWidth, int maxHeight, float density) {
        if (maxWidth <= 0 || maxHeight <= 0 || !Float.isFinite(density)
                || density < .5f || density > 10f) return 0;
        int width = Math.round(Math.min(maxWidth, maxHeight) / density);
        return width >= 280 && width <= 600 ? width : 0;
    }

    public static int logicalHeightDp(int viewportWidth, int viewportHeight,
            int widthDp, int fallbackHeightDp) {
        if (widthDp < ScalePolicy.MIN_REFERENCE_DP || widthDp > ScalePolicy.MAX_REFERENCE_DP)
            return fallbackHeightDp;
        if (viewportWidth <= 0 || viewportHeight <= 0) return fallbackHeightDp;
        return Math.max(1, (int) Math.round(viewportHeight * (double) widthDp / viewportWidth));
    }
}
