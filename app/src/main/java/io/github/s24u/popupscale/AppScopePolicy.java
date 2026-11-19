package io.github.s24u.popupscale;

import java.util.Set;

/** Internal selection only. LSPosed must separately authorize process injection. */
public final class AppScopePolicy {
    public static final String MODULE_PACKAGE = "io.github.s24u.popupscale";
    public static final String PERMISSION_PACKAGE = "com.google.android.permissioncontroller";
    public static final String SIDE_GESTURE_PACKAGE = "com.samsung.android.sidegesturepad";
    public static final String FAST_ACTION_POPUP = "oho_fast_action_popup";
    private AppScopePolicy() {}

    public static boolean hasApplicationUid(int uid) {
        // AOSP UserHandle.getAppId: strip the user/profile part before the guard.
        return uid >= 0 && uid % 100000 >= 10000;
    }

    public static boolean isBlocked(String name) {
        return name == null || name.equals("android") || name.equals("com.android.systemui")
                || name.equals("com.android.settings")
                || name.equals("com.sec.android.app.launcher")
                || name.equals("com.samsung.android.honeyboard")
                || name.equals("com.google.android.inputmethod.latin")
                || name.equals(SIDE_GESTURE_PACKAGE);
    }

    public static boolean isOrdinaryPackage(String name) {
        return !isBlocked(name) && !MODULE_PACKAGE.equals(name) && !PERMISSION_PACKAGE.equals(name);
    }

    public static boolean includes(String name, boolean allApps, boolean demo,
            Set<String> selected, Set<String> excluded) {
        if (isBlocked(name)) return false;
        // The self test and system permission page retain their dedicated controls.
        if (MODULE_PACKAGE.equals(name)) return demo;
        if (!isOrdinaryPackage(name) || excluded.contains(name)) return false;
        return allApps || selected.contains(name);
    }
}
