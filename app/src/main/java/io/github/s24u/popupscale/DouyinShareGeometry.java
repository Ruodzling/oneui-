package io.github.s24u.popupscale;

/** A fixed-height share dialog must fit its current task, not the physical display. */
public final class DouyinShareGeometry {
    private DouyinShareGeometry() {}

    public static boolean target(String pkg, String dialog) {
        return ("com.ss.android.ugc.aweme".equals(pkg) || "com.ss.android.ugc.aweme.mobile".equals(pkg))
                && "com.ss.android.ugc.aweme.sharer.panelmodel.base.DialogPanel".equals(dialog);
    }

    public static int height(int requested, int available, boolean enabled, int mode, int display) {
        if (!enabled || mode != 5 || display != 0 || requested <= 0 || available <= 0) return requested;
        return Math.min(requested, available);
    }
}
