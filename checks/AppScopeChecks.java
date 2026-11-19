package io.github.s24u.popupscale;

import java.util.Set;

/** Scope expansion must preserve opt-outs and avoid the dedicated system/self paths. */
public final class AppScopeChecks {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Set<String> old = Set.of("com.tencent.mm", "com.coolapk.market");
        Set<String> none = Set.of();
        String added = "org.example.reader";
        check(AppScopePolicy.includes("com.tencent.mm", false, true, old, none), "Existing selection lost");
        check(!AppScopePolicy.includes(added, false, true, old, none), "Upgrade expanded old settings");
        check(AppScopePolicy.includes(added, true, true, old, none), "All mode requires a manual entry");
        check(!AppScopePolicy.includes(added, true, true, old, Set.of(added)), "All mode ignored opt-out");
        for (boolean all : new boolean[]{false, true}) {
            check(!AppScopePolicy.includes("com.tencent.mm", all, true, old, Set.of("com.tencent.mm")),
                    "Selected mode ignored an exclusion");
            for (String core : new String[]{"android", "com.android.systemui", "com.android.settings",
                    "com.sec.android.app.launcher", "com.samsung.android.honeyboard",
                    "com.google.android.inputmethod.latin", AppScopePolicy.PERMISSION_PACKAGE}) {
                check(!AppScopePolicy.includes(core, all, true, Set.of(core), none),
                        "Dedicated or core process admitted: " + core);
                check(!AppScopePolicy.isOrdinaryPackage(core), "Core shown as ordinary app");
            }
            check(!AppScopePolicy.includes(AppScopePolicy.MODULE_PACKAGE, all, false,
                    Set.of(AppScopePolicy.MODULE_PACKAGE), none), "All mode bypassed disabled demo");
            check(AppScopePolicy.includes(AppScopePolicy.MODULE_PACKAGE, all, true, none, none),
                    "Demo stopped working");
        }
        check(!AppScopePolicy.includes(null, true, true, old, none), "Null package admitted");
        check(!AppScopePolicy.hasApplicationUid(1000), "System UID admitted");
        check(!AppScopePolicy.hasApplicationUid(101000), "Profile system UID admitted");
        check(!AppScopePolicy.hasApplicationUid(-1), "Invalid UID admitted");
        check(AppScopePolicy.hasApplicationUid(10386), "Ordinary user app excluded");
        check(AppScopePolicy.hasApplicationUid(1010386), "Profile app excluded");
        check(!AppScopePolicy.includes(added, false, true, old, none), "All mode leaked after switch back");
        System.out.println("PASS: application scope expansion, opt-out, migration and core UID checks.");
    }
}
