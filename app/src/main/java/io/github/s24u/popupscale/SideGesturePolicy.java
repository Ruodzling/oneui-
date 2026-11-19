package io.github.s24u.popupscale;

import android.content.ComponentName;
import android.content.Intent;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pure policy rules for Good Lock One Hand Operation + FastAction popup redirection.
 * No dependency on Xposed runtime classes to allow unit testing on host JVM.
 */
public final class SideGesturePolicy {
    public static final String PACKAGE_NAME = AppScopePolicy.SIDE_GESTURE_PACKAGE;
    public static final String PREF_KEY_FAST_ACTION_POPUP = AppScopePolicy.FAST_ACTION_POPUP;

    private SideGesturePolicy() {}

    public static boolean isCalledFromFastAction(StackTraceElement[] stack, Class<?> callbackClass) {
        if (stack == null) return false;
        String callbackName = callbackClass != null ? callbackClass.getName() : null;
        for (StackTraceElement elem : stack) {
            String name = elem.getClassName();
            String method = elem.getMethodName();
            if (callbackName != null && name.equals(callbackName)) return true;
            if (name.contains("FastAction") || name.contains("fastaction") || name.contains("Fast_Action")) return true;
            if (name.startsWith("v5.") || name.startsWith("u5.") || name.startsWith("w5.") || name.startsWith("x5.")) return true;
            if (name.startsWith("com.samsung.android.sidegesturepad.fastaction.")) return true;
            if ("onItemClicked".equals(method)) return true;
        }
        return false;
    }

    public static String extractTargetPackage(Intent intent) {
        if (intent == null) return null;
        if (intent.getPackage() != null && !intent.getPackage().isEmpty()) {
            return intent.getPackage();
        }
        ComponentName cn = intent.getComponent();
        if (cn != null && cn.getPackageName() != null && !cn.getPackageName().isEmpty()) {
            return cn.getPackageName();
        }
        return null;
    }

    public static boolean shouldRedirectTarget(String targetPkg) {
        if (targetPkg == null || targetPkg.isEmpty()) return false;
        if (PACKAGE_NAME.equals(targetPkg)) return false;
        if ("android".equals(targetPkg)) return false;
        if ("com.android.systemui".equals(targetPkg)) return false;
        if ("com.android.permissioncontroller".equals(targetPkg) || "com.google.android.permissioncontroller".equals(targetPkg)) return false;
        return true;
    }

    /** Defensive package list parser for cross-process settings without validation throws. */
    public static Set<String> parsePackageNames(String input) {
        if (input == null || input.isEmpty()) return Collections.emptySet();
        Set<String> packages = new LinkedHashSet<>();
        for (String part : input.split("[\\s,;，；]+")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                packages.add(value);
            }
        }
        return packages;
    }

    public static boolean shouldRedirectTarget(
            String targetPkg,
            boolean settingsAvailable,
            boolean masterEnabled,
            boolean featureEnabled,
            boolean allApps,
            boolean demo,
            Set<String> selected,
            Set<String> excluded) {
        if (!settingsAvailable || !masterEnabled || !featureEnabled) return false;
        if (!shouldRedirectTarget(targetPkg)) return false;
        return AppScopePolicy.includes(
                targetPkg,
                allApps,
                demo,
                selected == null ? Collections.emptySet() : selected,
                excluded == null ? Collections.emptySet() : excluded);
    }

    public static String launchDecisionReason(
            String targetPkg,
            boolean settingsAvailable,
            boolean masterEnabled,
            boolean featureEnabled,
            boolean allApps,
            boolean demo,
            Set<String> selected,
            Set<String> excluded,
            boolean accepted) {
        if (!settingsAvailable) return "settings-unavailable";
        if (!masterEnabled || !featureEnabled) return "disabled";
        if (!shouldRedirectTarget(targetPkg)
                || !AppScopePolicy.includes(
                        targetPkg,
                        allApps,
                        demo,
                        selected == null ? Collections.emptySet() : selected,
                        excluded == null ? Collections.emptySet() : excluded)) {
            return "excluded";
        }
        return accepted ? "fast-action" : "not-fast-action";
    }
}
