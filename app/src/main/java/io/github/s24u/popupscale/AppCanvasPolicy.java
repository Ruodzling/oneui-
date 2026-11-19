package io.github.s24u.popupscale;

/** Exceptions that require app-side layout compatibility alongside system-wide scaling. */
public final class AppCanvasPolicy {
    private AppCanvasPolicy() {}
    public static boolean isTencentCanvas(String name) {
        return "com.tencent.mobileqq".equals(name) || "com.tencent.tim".equals(name);
    }

    public static boolean requiresConsistentDensity(String name) {
        return "com.sina.weibo".equals(name) || isTencentCanvas(name);
    }

    public static boolean usesCanvas(String name, boolean unified) {
        return WechatScalePolicy.usesCanvas(name, unified) || requiresConsistentDensity(name);
    }

    public static boolean rewriteActivityResources(String name, boolean unified) {
        return !unified || requiresConsistentDensity(name);
    }

    public static boolean ownsCanvas(String name, boolean embedded, boolean outerCanvas) {
        return !requiresConsistentDensity(name) || (!embedded && !outerCanvas);
    }

    public static int processDensity(String name, boolean enabled, boolean diagnostic,
            boolean selected, int user, int supplied, int canvas) {
        if (!requiresConsistentDensity(name) || !enabled || diagnostic || !selected || user != 0
                || supplied < 120 || supplied > 1000 || canvas < 120 || canvas > 1000
                || supplied == canvas) return 0;
        return canvas;
    }
}
