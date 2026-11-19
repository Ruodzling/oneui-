package io.github.s24u.popupscale;

/** Activity-only density fallback for hosts that cannot load application hooks. */
public final class ExternalScalePolicy {
    private ExternalScalePolicy() {}
    public static boolean isTarget(String name) {
        return "flar2.devcheck".equals(name)
                || "com.ss.android.ugc.aweme".equals(name)
                || "com.ss.android.ugc.aweme.mobile".equals(name);
    }

    public static boolean usesExternal(String name, boolean unified) {
        return isTarget(name) || (unified && (AppScopePolicy.isOrdinaryPackage(name)
                || AppScopePolicy.MODULE_PACKAGE.equals(name)));
    }

    public static Geometry calculate(String name, boolean enabled, boolean diagnostic,
            boolean selected, int user, int display, int type, int mode,
            int width, int height, int nativeDpi, int referenceDp) {
        return calculate(name, false, enabled, diagnostic, selected, user, display, type, mode,
                width, height, nativeDpi, referenceDp);
    }

    public static Geometry calculate(String name, boolean unified, boolean enabled, boolean diagnostic,
            boolean selected, int user, int display, int type, int mode,
            int width, int height, int nativeDpi, int referenceDp) {
        if (!usesExternal(name, unified) || !enabled || diagnostic || !selected || !CloneUserPolicy.supported(user)
                || display != 0 || type != 1 || mode != 5 || width <= 0 || height <= 0
                || nativeDpi < 120 || nativeDpi > 1000
                || referenceDp < ScalePolicy.MIN_REFERENCE_DP
                || referenceDp > ScalePolicy.MAX_REFERENCE_DP) return null;
        int dpi = Math.min(1000, Math.max(120, (int) Math.round(width * 160.0 / referenceDp)));
        if (dpi == nativeDpi) return null;
        return new Geometry(dpi, (int) Math.round(width * 160.0 / dpi),
                (int) Math.round(height * 160.0 / dpi));
    }

    public static final class Geometry {
        public final int densityDpi, widthDp, heightDp;
        private Geometry(int dpi, int width, int height) {
            densityDpi = dpi; widthDp = width; heightDp = height;
        }
    }
}
