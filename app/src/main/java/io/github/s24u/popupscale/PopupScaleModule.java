package io.github.s24u.popupscale;

import android.app.Activity;
import android.content.Context;
import android.app.Dialog;
import android.widget.PopupWindow;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** App-process experiment; no system_server or zygote hook. */
public final class PopupScaleModule implements IXposedHookLoadPackage {
    private static final String TAG = "PopupScale";
    private final Set<Member> installed = new HashSet<>();
    private final Set<String> playerBypassLogged = new HashSet<>();
    private final java.util.IdentityHashMap<Activity, Integer> maskedPlayerActivities = new java.util.IdentityHashMap<>();
    private java.lang.reflect.Field activityMultiWindowField;
    private String packageName;
    private Object resourcesManager;
    private volatile Context appContext;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private XSharedPreferences preferences;
    private volatile Options options = Options.OFF;
    private long lastReload = -5000;
    private long lastLog = -5000;
    private boolean failureLogged;
    private int changes;
    private int baseDensity = DisplayMetrics.DENSITY_DEVICE_STABLE;
    private final CanvasController canvasController = new CanvasController();
    private final WechatDialogController dialogController = new WechatDialogController();
    private final PermissionCanvasController permissionController = new PermissionCanvasController();
    private final PopupCanvasController popupController = new PopupCanvasController();
    private final DouyinCommentController douyinComments = new DouyinCommentController();
    private final java.util.WeakHashMap<Activity, Boolean> pendingRoots = new java.util.WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (SideGestureHook.PACKAGE_NAME.equals(param.packageName)) {
            SideGestureHook.install(param.classLoader);
            if(param.isFirstApplication&&param.packageName.equals(param.processName))
                io.github.s24u.popupscale.gestures.CompatibilityReporter.INSTANCE.install(
                    param.packageName,param.classLoader,"OHO小窗重定向",java.util.Collections.emptyList());
            return;
        }
        if (io.github.s24u.popupscale.gestures.MiniPlaybackPolicy.PACKAGE.equals(param.packageName)) {
            Log.i(TAG,"[mini-playback] scope-entry process="+param.processName+" first="+param.isFirstApplication+
                " uid="+android.os.Process.myUid()+" module="+BuildConfig.VERSION_CODE);
        }
        if (!param.isFirstApplication) return;
        packageName = param.packageName;
        if (ModuleSettings.isBlocked(packageName) || param.appInfo == null
                || !AppScopePolicy.hasApplicationUid(param.appInfo.uid)) return;
        if (ExternalScalePolicy.isTarget(packageName)) {
            // Keep modifying canvas/resource/player hooks disabled. Read-only lifecycle
            // playback observations and diagnostics must still run in the external path.
            if (DouyinShareGeometry.target(packageName,
                    "com.ss.android.ugc.aweme.sharer.panelmodel.base.DialogPanel")
                    && packageName.equals(param.processName) && AppHookProcessGuard.claim(System.getProperties())) {
                try {
                    preferences = new XSharedPreferences(ModuleSettings.PACKAGE, ModuleSettings.PREFS);
                    reload();
                    try { DouyinPlaybackController.install(param.classLoader,packageName); }
                    catch(Throwable error){logFailure(error);}
                    new DouyinSharePanelController(packageName, () -> {
                        reload();
                        Options current = options;
                        return current.enabled && !current.diagnostic && current.selects(packageName);
                    }).install();
                } catch (Throwable error) { logFailure(error); }
            }
            log("external window scaling; modifying canvas/resource/player hooks skipped; optional read-only playback observer retained");
            return;
        }
        if (!AppHookProcessGuard.claim(System.getProperties())) {
            log("duplicate application hook entry skipped");
            return;
        }
        if (ModuleSettings.PACKAGE.equals(packageName)) {
            XposedHelpers.findAndHookMethod(HookStatus.class.getName(), param.classLoader,
                    "isActive", XC_MethodReplacement.returnConstant(true));
        }
        try {
            reload();
            preferences = new XSharedPreferences(ModuleSettings.PACKAGE, ModuleSettings.PREFS);
            reload();
            if (!isPrefsAccessible()) installApplicationSettingsReload();
            installProductHeaderCompatibility();
            DouyinPlaybackController.install(param.classLoader, packageName);
            QQDensityController.install(param.classLoader, packageName, param.processName, () -> {
                reload();
                Options current = options;
                return current.systemScale && current.enabled && !current.diagnostic && current.selects(packageName);
            });
            QQPanelController.install(param.classLoader, packageName, param.processName, () -> {
                reload();
                Options current = options;
                return current.enabled && !current.diagnostic && current.selects(packageName);
            });
            if (PermissionCanvasController.PACKAGE.equals(packageName)) {
                installPermissionLifecycle();
                log("loaded permission UI only; sharedPrefs=" + isPrefsAccessible());
                return;
            }
            if (ExternalScalePolicy.usesExternal(packageName, options.systemScale)
                    && !AppCanvasPolicy.requiresConsistentDensity(packageName)) {
                if (AppCanvasPolicy.usesCanvas(packageName, options.systemScale)) {
                    // WeChat overwrites the framework density (observed 350 -> 576 DPI).
                    // Measure its canvas using the actual resource density; when it honors
                    // framework density this becomes scale=1 rather than double scaling.
                    Class<?> managerClass = XposedHelpers.findClass("android.app.ResourcesManager", null);
                    resourcesManager = XposedHelpers.callStaticMethod(managerClass, "getInstance");
                    Configuration base = (Configuration) XposedHelpers.callMethod(resourcesManager, "getConfiguration");
                    if (base.densityDpi > 0) baseDensity = base.densityDpi;
                    installCanvasLifecycle();
                    installCanvasRootTracking();
                    hookConfigurationMethods(managerClass,
                            "createBaseTokenResources", "createBaseActivityResources",
                            "updateResourcesForActivity", "getResources");
                    Class<?> thread = XposedHelpers.findClass("android.app.ActivityThread", null);
                    hookConfigurationMethods(thread,
                            "performActivityConfigurationChanged",
                            "handleActivityConfigurationChanged",
                            "updatePendingActivityConfiguration");
                    log("adapted-density canvas compatibility installed package=" + packageName + "; framework configuration unchanged");
                }
                // Keep the verified player-only gate when its optional app scope already exists.
                // Other applications keep the scope-independent framework density path.
                installPlayerCompatibility(param.classLoader);
                log(AppCanvasPolicy.usesCanvas(packageName, options.systemScale)
                        ? "system-side unified mode; adapted-density canvas compatibility active; resource configuration hooks skipped"
                        : "system-side unified mode; application canvas hooks not installed");
                return;
            }
            installCanvasLifecycle();
            if ("com.tencent.mm".equals(packageName) || AppCanvasPolicy.isTencentCanvas(packageName)) installCanvasRootTracking();
            if (DouyinCommentController.isTarget(packageName)) installDouyinComments(param.classLoader);
            installPlayerCompatibility(param.classLoader);
            // LSPosed scope is the outer allow-list; saved packages are the inner one.
            Class<?> managerClass = XposedHelpers.findClass("android.app.ResourcesManager", null);
            resourcesManager = XposedHelpers.callStaticMethod(managerClass, "getInstance");
            Configuration base = (Configuration) XposedHelpers.callMethod(resourcesManager, "getConfiguration");
            if (base.densityDpi > 0) baseDensity = base.densityDpi;
            if (AppCanvasPolicy.requiresConsistentDensity(packageName)) {
                try {
                    Object global = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                            "android.hardware.display.DisplayManagerGlobal", null), "getInstance");
                    android.view.Display display = (android.view.Display) XposedHelpers.callMethod(global, "getRealDisplay", 0);
                    DisplayMetrics physical = new DisplayMetrics();
                    if (display != null) {
                        display.getRealMetrics(physical);
                        if (physical.densityDpi >= 120 && physical.densityDpi <= 1000)
                            baseDensity = physical.densityDpi;
                    }
                } catch (Throwable error) { logFailure(error); }
            }
            int resourceHooks = hookConfigurationMethods(managerClass,
                    "createBaseTokenResources", "createBaseActivityResources",
                    "updateResourcesForActivity", "getResources");
            Class<?> threadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            int activityHooks = hookConfigurationMethods(threadClass,
                    "performActivityConfigurationChanged",
                    "handleActivityConfigurationChanged",
                    "updatePendingActivityConfiguration");
            if (AppCanvasPolicy.requiresConsistentDensity(packageName)) {
                resourceHooks += hookConfigurationMethods(managerClass, "applyConfigurationToResources");
                try {
                    Configuration process = consistentProcessConfiguration(base, options);
                    if (process != null) {
                        Method apply = XposedHelpers.findMethodExactIfExists(managerClass,
                                "applyConfigurationToResources", Configuration.class,
                                XposedHelpers.findClass("android.content.res.CompatibilityInfo", null));
                        if (apply != null) apply.invoke(resourcesManager, process, null);
                        else log("process density initialization unavailable: ResourcesManager ABI");
                    }
                } catch (Throwable error) { logFailure(error); }
                log("consistent-density app canvas installed package=" + packageName + "; process and Activity resources share canvas density");
            }
            // Deferred Application reload is already installed early via installApplicationSettingsReload().
            log("loaded resourceHooks=" + resourceHooks + " activityHooks=" + activityHooks
                    + " sharedPrefs=" + isPrefsAccessible()
                    + " enabled=" + options.enabled + " baseDpi=" + baseDensity);
            if (resourceHooks == 0)
                log("No supported resource hook on this firmware; scaling unavailable.");
        } catch (Throwable error) {
            logFailure(error);
        }
    }

    private void installProductHeaderCompatibility() {
        if (!"com.ss.android.ugc.aweme".equals(packageName)) return;
        DouyinProductHeaderController controller = new DouyinProductHeaderController(() -> {
            reload();
            Options current = options;
            return current.systemScale && current.enabled && !current.diagnostic && current.selects(packageName);
        });
        DouyinMainHeaderController mainHeaders = new DouyinMainHeaderController(() -> {
            reload();
            Options current = options;
            return current.systemScale && current.enabled && !current.diagnostic && current.selects(packageName);
        });
        Class<?> instrumentation = XposedHelpers.findClass("android.app.Instrumentation", null);
        XposedBridge.hookAllMethods(instrumentation, "callActivityOnResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof Activity) {
                    try { controller.watch((Activity) param.args[0]); } catch (Throwable error) { logFailure(error); }
                    try { mainHeaders.watch((Activity) param.args[0]); } catch (Throwable error) { logFailure(error); }
                }
            }
        });
        XposedBridge.hookAllMethods(instrumentation, "callActivityOnDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof Activity) {
                    try { controller.forget((Activity) param.args[0]); } catch (Throwable error) { logFailure(error); }
                    try { mainHeaders.forget((Activity) param.args[0]); } catch (Throwable error) { logFailure(error); }
                }
            }
        });
    }
    private boolean canvasRootTrackingInstalled;
    private void installCanvasRootTracking() {
        if (canvasRootTrackingInstalled) return;
        canvasRootTrackingInstalled = true;
        XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, int.class,
                ViewGroup.LayoutParams.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam event) {
                try {
                    ViewGroup host = (ViewGroup) event.thisObject;
                    if (event.args[0] instanceof ZoomFrameLayout) return;
                    Activity owner = canvasController.ownerOfHost(host);
                    if (owner == null || pendingRoots.containsKey(owner)) return;
                    pendingRoots.put(owner, true);
                    // Let FragmentManager/DataBinding finish attaching its root first.
                    host.postOnAnimation(() -> {
                        pendingRoots.remove(owner);
                        try { updateCanvas(owner); } catch (Throwable error) { logFailure(error); }
                    });
                } catch (Throwable error) { logFailure(error); }
            }
        });
    }

    private void installDouyinComments(ClassLoader loader) {
        installCanvasRootTracking();
        Class<?> panel = XposedHelpers.findClassIfExists(DouyinCommentController.LAYOUT, loader);
        if (panel == null) { log("Douyin comment class unavailable; root tracking only"); return; }
        XposedHelpers.findAndHookMethod(panel, "onMeasure", int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam event) {
                try {
                    reload();
                    Options current = options;
                    douyinComments.beforeMeasure((ViewGroup) event.thisObject, (Integer) event.args[1],
                            current.enabled && !current.systemScale && !current.diagnostic && current.selects(packageName), current.logging);
                } catch (Throwable error) { logFailure(error); }
            }
        });
        log("Douyin inline comment tracking and height compatibility installed");
    }

    private static Activity findHostActivity(Context context) {
        Context current = context;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof android.content.ContextWrapper)) return null;
            current = ((android.content.ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private boolean enterPlayerMultiWindowMask(Activity activity) {
        if (activityMultiWindowField == null || activity == null
                || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false;
        if (!PlayerCompatPolicy.playerActivity(packageName, activity.getClass().getName())) return false;
        Integer existing = maskedPlayerActivities.get(activity);
        if (existing != null) {
            maskedPlayerActivities.put(activity, existing + 1);
            return true;
        }
        reload();
        Options current = options;
        int mode = canvasController.windowMode(activity);
        boolean selected = AppScopePolicy.includesInteraction(packageName, current.demo);
        if (!PlayerCompatPolicy.eligible(packageName, current.enabled, current.diagnostic, selected, mode)) return false;
        try {
            if (!activityMultiWindowField.getBoolean(activity)) return false;
            activityMultiWindowField.setBoolean(activity, false);
            maskedPlayerActivities.put(activity, 1);
            return true;
        } catch (Throwable error) {
            logFailure(error);
            return false;
        }
    }

    private void exitPlayerMultiWindowMask(Activity activity) {
        if (activityMultiWindowField == null || activity == null
                || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
        Integer existing = maskedPlayerActivities.get(activity);
        if (existing == null) return;
        if (existing > 1) {
            maskedPlayerActivities.put(activity, existing - 1);
            return;
        }
        maskedPlayerActivities.remove(activity);
        try {
            // Restore native field only while the Activity is still in freeform mode.
            if (canvasController.windowMode(activity) == 5) {
                activityMultiWindowField.setBoolean(activity, true);
            }
        } catch (Throwable error) {
            logFailure(error);
        }
    }

    private void installPlayerCompatibility(ClassLoader loader) {
        if (!PlayerCompatPolicy.PACKAGE.equals(packageName)) return;
        java.lang.reflect.Field field = XposedHelpers.findFieldIfExists(Activity.class, "mIsInMultiWindowMode");
        if (field != null && field.getType() == boolean.class) {
            field.setAccessible(true);
            activityMultiWindowField = field;
        }
        XposedHelpers.findAndHookMethod(Activity.class, "isInMultiWindowMode", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.hasThrowable() || !Boolean.TRUE.equals(param.getResult())) return;
                    if (!PlayerCompatPolicy.playerActivity(packageName, param.thisObject.getClass().getName())) return;
                    StackTraceElement[] frames = Thread.currentThread().getStackTrace();
                    if (!PlayerCompatPolicy.shouldBypassStack(frames)) return;
                    reload();
                    Options current = options;
                    int mode = canvasController.windowMode((Activity) param.thisObject);
                    boolean selected = AppScopePolicy.includesInteraction(packageName, current.demo);
                    boolean eligible = PlayerCompatPolicy.eligible(packageName, current.enabled,
                            current.diagnostic, selected, mode);
                    String source = "stack";
                    for (StackTraceElement caller : frames) {
                        if (PlayerCompatPolicy.playerCaller(caller.getClassName(), caller.getMethodName())) {
                            source = caller.getClassName() + "." + caller.getMethodName();
                            break;
                        }
                    }
                    if (!eligible) {
                        String decision = "player gate kept: " + source + " mode=" + mode
                                + " enabled=" + current.enabled + " diagnostic=" + current.diagnostic
                                + " selected=" + selected + " prefsReadable=" + preferences.getFile().canRead();
                        synchronized (playerBypassLogged) {
                            if (playerBypassLogged.size() < 32 && playerBypassLogged.add(decision)) log(decision);
                        }
                        return;
                    }
                    param.setResult(false);
                    synchronized (playerBypassLogged) {
                        if (playerBypassLogged.size() < 32 && playerBypassLogged.add(source))
                            log("player freeform gate bypass: " + source);
                    }
                } catch (Throwable error) { logFailure(error); }
            }
        });
        if (activityMultiWindowField != null) {
            // Defeat ART AOT inlining of Activity.isInMultiWindowMode into unknown obfuscated
            // helpers across Bilibili versions by masking the backing field only during
            // synchronous player click/touch-up/configuration callbacks in freeform mode.
            XposedHelpers.findAndHookMethod(View.class, "performClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = findHostActivity(((View) param.thisObject).getContext());
                        if (enterPlayerMultiWindowMask(activity)) {
                            param.setObjectExtra("popupscale.playerMask", activity);
                        }
                    } catch (Throwable error) { logFailure(error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object masked = param.getObjectExtra("popupscale.playerMask");
                        if (masked instanceof Activity) exitPlayerMultiWindowMask((Activity) masked);
                    } catch (Throwable error) { logFailure(error); }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                        if (ev == null || ev.getActionMasked() != android.view.MotionEvent.ACTION_UP) return;
                        Activity activity = (Activity) param.thisObject;
                        if (enterPlayerMultiWindowMask(activity)) {
                            param.setObjectExtra("popupscale.playerMask", activity);
                        }
                    } catch (Throwable error) { logFailure(error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object masked = param.getObjectExtra("popupscale.playerMask");
                        if (masked instanceof Activity) exitPlayerMultiWindowMask((Activity) masked);
                    } catch (Throwable error) { logFailure(error); }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onConfigurationChanged",
                    Configuration.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (enterPlayerMultiWindowMask(activity)) {
                            param.setObjectExtra("popupscale.playerMask", activity);
                        }
                    } catch (Throwable error) { logFailure(error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object masked = param.getObjectExtra("popupscale.playerMask");
                        if (masked instanceof Activity) exitPlayerMultiWindowMask((Activity) masked);
                    } catch (Throwable error) { logFailure(error); }
                }
            });
        }
        deoptimizePlayerCallers(loader);
        log("player freeform compatibility installed (general player-stack + scoped field mask="
                + (activityMultiWindowField != null) + ")");
    }

    private void deoptimizePlayerCallers(ClassLoader loader) {
        final Method deoptimize;
        try {
            // The bundled legacy API82 stub lacks this LSPosed extension. Resolve it at runtime.
            deoptimize = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
        } catch (ReflectiveOperationException absent) {
            log("player caller deoptimization unavailable; retained existing scoped hook");
            return;
        }
        for (String name : PlayerCompatPolicy.playerCallerTypes()) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                int count = 0;
                for (Method method : type.getDeclaredMethods()) {
                    int modifiers = method.getModifiers();
                    if (java.lang.reflect.Modifier.isAbstract(modifiers)
                            || java.lang.reflect.Modifier.isNative(modifiers)) continue;
                    if (!PlayerCompatPolicy.playerCaller(name, method.getName())) continue;
                    // Activity.isInMultiWindowMode is a one-field getter on this firmware.
                    // Deoptimize player callers so inlined checks reach our hook.
                    deoptimize.invoke(null, method);
                    count++;
                }
                if (count > 0) log("player caller deoptimized: " + name + " (" + count + " methods)");
            } catch (Throwable error) {
                log("player caller deoptimization skipped " + name + ": " + error.getClass().getSimpleName());
            }
        }
    }

    private int hookConfigurationMethods(Class<?> type, String... names) {
        Set<String> wanted = new HashSet<>();
        Collections.addAll(wanted, names);
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!wanted.contains(method.getName()) || installed.contains(method)) continue;
            boolean hasConfiguration = false;
            for (Class<?> parameter : method.getParameterTypes())
                if (parameter == Configuration.class) hasConfiguration = true;
            if (!hasConfiguration) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            reload();
                            Options current = options;
                            if (!current.enabled || !AppCanvasPolicy.rewriteActivityResources(packageName, current.systemScale)
                                    || !current.selects(packageName))
                                return;
                            // Token-less getResources is process-global, not Activity-specific.
                            if (method.getName().equals("getResources")) {
                                boolean hasToken = false;
                                for (Object argument : param.args)
                                    if (argument instanceof IBinder) hasToken = true;
                                if (!hasToken) return;
                            }
                            for (int index = 0; index < param.args.length; index++) {
                                if (param.args[index] instanceof Configuration) {
                                    Configuration source = (Configuration) param.args[index];
                                    Configuration replacement = method.getName().equals("applyConfigurationToResources")
                                            ? consistentProcessConfiguration(source, current)
                                            : adjust(source, current, method.getName());
                                    if (replacement != null) param.args[index] = replacement;
                                }
                            }
                        } catch (Throwable error) {
                            // No exception escapes into the host app.
                            logFailure(error);
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (method.getDeclaringClass().getName().equals("android.app.ActivityThread")
                                && param.args.length > 0 && param.args[0] != null) {
                            try {
                                Object value = extractActivity(param.args[0]);
                                if (value instanceof Activity) {
                                    Activity configuredActivity = (Activity) value;
                                    int[] configuredModeHint = {0};
                                    for (Object argument : param.args) {
                                        if (argument instanceof Configuration) {
                                            Object configuredWindow = XposedHelpers.getObjectField(argument, "windowConfiguration");
                                            int configuredMode = (Integer) XposedHelpers.callMethod(configuredWindow, "getWindowingMode");
                                            configuredModeHint[0] = configuredMode;
                                        }
                                    }
                                    final int modeHint = configuredModeHint[0];
                                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                                        if (modeHint > 0) canvasController.setMode(configuredActivity, modeHint);
                                        updateCanvas(configuredActivity);
                                    } else {
                                        mainHandler.post(() -> {
                                            try {
                                                if (modeHint > 0) canvasController.setMode(configuredActivity, modeHint);
                                                updateCanvas(configuredActivity);
                                            } catch (Throwable error) { logFailure(error); }
                                        });
                                    }
                                }
                            } catch (Throwable error) { logFailure(error); }
                        }
                    }
                });
                installed.add(method);
                count++;
            } catch (Throwable error) { logFailure(error); }
        }
        return count;
    }

    private Configuration adjust(Configuration source, Options current, String method) {
        Object window = XposedHelpers.getObjectField(source, "windowConfiguration");
        int mode = (Integer) XposedHelpers.callMethod(window, "getWindowingMode");
        if (mode != 5) return null;
        Rect bounds = (Rect) XposedHelpers.callMethod(window, "getAppBounds");
        // Do not guess window bounds or act on partial configurations.
        if (bounds == null || bounds.isEmpty()) return null;
        if (source.densityDpi <= 0 || ScalePolicy.canvas(mode, bounds.width(), bounds.height(),
                baseDensity, current.referenceDp) == null) return null;
        int logicalHeight = Math.max(1, (int) Math.round(
                bounds.height() * (double) current.referenceDp / bounds.width()));
        long now = SystemClock.uptimeMillis();
        if (current.logging && now - lastLog > 500) {
            lastLog = now;
            log((current.diagnostic ? "observe " : "canvas-config ") + method
                    + " bounds=" + bounds.width() + "x" + bounds.height()
                    + " baseDpi=" + source.densityDpi + "->" + baseDensity
                    + " dp=" + source.screenWidthDp + "x" + source.screenHeightDp
                    + "->" + current.referenceDp + "x" + logicalHeight
                    + " count=" + (++changes));
        }
        if (current.diagnostic) return null;
        Configuration adjusted = new Configuration(source);
        adjusted.densityDpi = baseDensity;
        adjusted.screenWidthDp = current.referenceDp;
        adjusted.screenHeightDp = logicalHeight;
        adjusted.smallestScreenWidthDp = Math.min(current.referenceDp, logicalHeight);
        // Preserve physical app bounds and fontScale. Android derives sp sizing from density.
        updateCompatField(adjusted, "compatScreenWidthDp", adjusted.screenWidthDp);
        updateCompatField(adjusted, "compatScreenHeightDp", adjusted.screenHeightDp);
        updateCompatField(adjusted, "compatSmallestScreenWidthDp", adjusted.smallestScreenWidthDp);
        int layout = (Integer) XposedHelpers.callStaticMethod(Configuration.class,
                "resetScreenLayout", adjusted.screenLayout);
        adjusted.screenLayout = (Integer) XposedHelpers.callStaticMethod(Configuration.class,
                "reduceScreenLayout", layout, Math.max(adjusted.screenWidthDp, adjusted.screenHeightDp),
                Math.min(adjusted.screenWidthDp, adjusted.screenHeightDp));
        return adjusted;
    }

    private Configuration consistentProcessConfiguration(Configuration source, Options current) {
        int density = AppCanvasPolicy.processDensity(packageName, current.enabled, current.diagnostic,
                current.selects(packageName), android.os.Process.myUid() / 100000, source.densityDpi, baseDensity);
        if (density == 0) return null;
        Configuration copy = new Configuration(source);
        copy.densityDpi = density;
        log("consistent process resource dpi=" + source.densityDpi + "->" + density);
        return copy;
    }

    private void updateCanvas(Activity activity) {
        reload();
        Options current = options;
        boolean selected = current.selects(packageName);
        canvasController.update(activity, current.enabled && selected && AppCanvasPolicy.usesCanvas(packageName, current.systemScale), current.diagnostic,
                baseDensity, current.referenceDp, current.logging);
        popupController.updateForActivity(activity);
        if ("com.tencent.mm".equals(packageName) || AppCanvasPolicy.isTencentCanvas(packageName) || ModuleSettings.PACKAGE.equals(packageName))
            dialogController.updateForActivity(activity, canvasController, current.enabled && selected && AppCanvasPolicy.usesCanvas(packageName, current.systemScale),
                    current.diagnostic, baseDensity, current.referenceDp, current.logging);
    }

    private void prepareConsistentActivityResources(Activity activity) throws Exception {
        if (!AppCanvasPolicy.requiresConsistentDensity(packageName) || resourcesManager == null) return;
        reload();
        Options current = options;
        if (!current.enabled || current.diagnostic || !current.selects(packageName)) return;
        Configuration source = activity.getResources().getConfiguration();
        if (source.densityDpi == baseDensity) return;
        Configuration replacement = adjust(source, current, "embedded-activity-resources");
        if (replacement == null) return;
        // Embedded ActivityGroup children can inherit the server override without passing a
        // non-null Configuration through createBaseTokenResources. Update only their token's
        // registered resource references before onCreate inflates any views.
        Object token = XposedHelpers.getObjectField(activity, "mToken");
        if (!(token instanceof IBinder)) return;
        Method update = XposedHelpers.findMethodExactIfExists(resourcesManager.getClass(),
                "updateResourcesForActivity", IBinder.class, Configuration.class, int.class);
        if (update == null) {
            log("consistent Activity resources unavailable: updateResourcesForActivity ABI");
            return;
        }
        int oldDpi = source.densityDpi;
        update.invoke(resourcesManager, token, replacement, activity.getWindowManager().getDefaultDisplay().getDisplayId());
        log("consistent Activity resources activity=" + activity.getClass().getName()
                + " embedded=" + (activity.getParent() != null) + " dpi=" + oldDpi
                + "->" + activity.getResources().getDisplayMetrics().densityDpi);
    }

    private void installCanvasLifecycle() {
        try {
            XposedHelpers.findAndHookMethod(PopupWindow.class, "invokePopup", WindowManager.LayoutParams.class,
                    popupHook(true));
        } catch (Throwable error) { logFailure(error); }
        try {
            XposedHelpers.findAndHookMethod(PopupWindow.class, "update", View.class,
                    WindowManager.LayoutParams.class, popupHook(false));
        } catch (Throwable error) { logFailure(error); }
        if ("com.tencent.mm".equals(packageName) || AppCanvasPolicy.isTencentCanvas(packageName) || ModuleSettings.PACKAGE.equals(packageName)) {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Dialog dialog = (Dialog) param.thisObject;
                    if (dialog.getWindow() == null) return;
                    dialog.getWindow().getDecorView().post(() -> {
                        try {
                            reload();
                            Options current = options;
                            dialogController.update(dialog, canvasController,
                                    current.enabled && AppCanvasPolicy.usesCanvas(packageName, current.systemScale) && current.selects(packageName),
                                    current.diagnostic, baseDensity, current.referenceDp, current.logging);
                        } catch (Throwable error) { logFailure(error); }
                    });
                }
            });
        }
        Class<?> instrumentation = XposedHelpers.findClass("android.app.Instrumentation", null);
        for (String name : new String[]{"callActivityOnCreate", "callActivityOnResume"}) {
            XposedBridge.hookAllMethods(instrumentation, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Activity) {
                        Activity activity = (Activity) param.args[0];
                        try {
                            prepareConsistentActivityResources(activity);
                            Method changed = activity.getClass().getMethod("onContentChanged");
                            if (installed.add(changed)) {
                                XposedBridge.hookMethod(changed, new XC_MethodHook() {
                                    @Override protected void afterHookedMethod(MethodHookParam event) {
                                        Activity contentActivity = (Activity) event.thisObject;
                                        // DataBindingUtil inspects the just-added root after this
                                        // callback returns. Defer reparenting until that stack unwinds.
                                        contentActivity.getWindow().getDecorView().post(() -> {
                                            try { updateCanvas(contentActivity); }
                                            catch (Throwable error) { logFailure(error); }
                                        });
                                    }
                                });
                            }
                        } catch (Throwable error) { logFailure(error); }
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Activity) {
                        try { updateCanvas((Activity) param.args[0]); }
                        catch (Throwable error) { logFailure(error); }
                    }
                }
            });
        }
        XposedBridge.hookAllMethods(instrumentation, "callActivityOnDestroy", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof Activity) {
                    canvasController.forget((Activity) param.args[0]);
                    popupController.forget((Activity) param.args[0]);
                }
            }
        });
    }
    private XC_MethodHook popupHook(boolean initial) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam event) {
                try {
                    reload();
                    Options current = options;
                    int index = initial ? 0 : 1;
                    event.args[index] = popupController.prepare((PopupWindow) event.thisObject,
                            (WindowManager.LayoutParams) event.args[index], canvasController,
                            current.enabled && AppCanvasPolicy.usesCanvas(packageName, current.systemScale) && !current.diagnostic && current.selects(packageName),
                            baseDensity, current.referenceDp, current.logging, initial);
                } catch (Throwable error) { logFailure(error); }
            }
        };
    }
    private void updatePermissionCanvas(Activity activity) {
        reload();
        Options current = options;
        permissionController.update(activity, canvasController,
                current.enabled && !current.diagnostic && current.selects(activity.getCallingPackage()),
                current.referenceDp, current.logging);
    }

    private void postPermissionCanvas(Activity activity) {
        if (activity.getWindow() == null) return;
        activity.getWindow().getDecorView().post(() -> {
            try { updatePermissionCanvas(activity); }
            catch (Throwable error) { logFailure(error); }
        });
    }

    private void installPermissionLifecycle() {
        Class<?> instrumentation = XposedHelpers.findClass("android.app.Instrumentation", null);
        for (String name : new String[]{"callActivityOnCreate", "callActivityOnResume"}) {
            XposedBridge.hookAllMethods(instrumentation, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Activity)) return;
                    Activity activity = (Activity) param.args[0];
                    for (String callback : new String[]{"onContentChanged", "onConfigurationChanged"}) {
                        try {
                            Method method = callback.equals("onConfigurationChanged")
                                    ? activity.getClass().getMethod(callback, Configuration.class)
                                    : activity.getClass().getMethod(callback);
                            if (installed.add(method)) XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override protected void afterHookedMethod(MethodHookParam event) {
                                    postPermissionCanvas((Activity) event.thisObject);
                                }
                            });
                        } catch (Throwable error) { logFailure(error); }
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Activity)
                        postPermissionCanvas((Activity) param.args[0]);
                }
            });
        }
        // Firmware ActivityThread only calls onConfigurationChanged (and only then updates
        // Activity.mCurrentConfig) when shouldReportChange() is true. A freeform/fullscreen
        // switch can update resources without that callback, which left the permission
        // canvas scaled with a stale mode. Observe the delivered config without changing it.
        Class<?> threadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
        for (Method method : threadClass.getDeclaredMethods()) {
            if ((!method.getName().equals("performActivityConfigurationChanged")
                    && !method.getName().equals("handleActivityConfigurationChanged"))
                    || installed.contains(method)) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 2 || types[1] != Configuration.class) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args[0] == null || !(param.args[1] instanceof Configuration)) return;
                            Activity activity = extractActivity(param.args[0]);
                            if (activity == null || !PermissionCanvasController.isTarget(activity)) return;
                            Object window = XposedHelpers.getObjectField(param.args[1], "windowConfiguration");
                            canvasController.setMode(activity,
                                    (Integer) XposedHelpers.callMethod(window, "getWindowingMode"));
                            postPermissionCanvas(activity);
                        } catch (Throwable error) { logFailure(error); }
                    }
                });
                installed.add(method);
            } catch (Throwable error) { logFailure(error); }
        }
        XposedBridge.hookAllMethods(instrumentation, "callActivityOnDestroy", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof Activity) {
                    permissionController.forget((Activity) param.args[0]);
                    canvasController.forget((Activity) param.args[0]);
                }
            }
        });
    }

    private static Activity extractActivity(Object recordOrActivity) {
        if (recordOrActivity == null) return null;
        if (recordOrActivity instanceof Activity) return (Activity) recordOrActivity;
        if (recordOrActivity instanceof Configuration) return null;
        if (recordOrActivity instanceof IBinder) {
            try {
                Class<?> threadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object thread = XposedHelpers.callStaticMethod(threadClass, "currentActivityThread");
                if (thread != null) {
                    Object activity = XposedHelpers.callMethod(thread, "getActivity", recordOrActivity);
                    if (activity instanceof Activity) return (Activity) activity;
                    Object client = XposedHelpers.callMethod(thread, "getActivityClient", recordOrActivity);
                    if (client != null) {
                        Object act = XposedHelpers.getObjectField(client, "activity");
                        if (act instanceof Activity) return (Activity) act;
                    }
                }
            } catch (Throwable ignored) { }
            return null;
        }
        java.lang.reflect.Field field = XposedHelpers.findFieldIfExists(recordOrActivity.getClass(), "activity");
        if (field == null) return null;
        try {
            Object value = field.get(recordOrActivity);
            return value instanceof Activity ? (Activity) value : null;
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private void updateCompatField(Configuration target, String field, int value) {
        try { XposedHelpers.setIntField(target, field, value); }
        catch (NoSuchFieldError ignored) { /* Removed on some Android versions. */ }
    }

    private final java.util.Map<String, Object> providerCache = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean applicationReloadInstalled;
    private void installApplicationSettingsReload() {
        if (applicationReloadInstalled) return;
        applicationReloadInstalled = true;
        try {
            final Class<?> resMgrClass = XposedHelpers.findClass("android.app.ResourcesManager", null);
            XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                    android.app.Application.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam appParam) {
                    try {
                        if (appParam.args.length > 0 && appParam.args[0] instanceof Context) {
                            appContext = (Context) appParam.args[0];
                        }
                        lastReload = -5000;
                        reload();
                        if (AppCanvasPolicy.requiresConsistentDensity(packageName) && resourcesManager != null) {
                            Configuration currentCfg = (Configuration) XposedHelpers.callMethod(resourcesManager, "getConfiguration");
                            Configuration process = consistentProcessConfiguration(currentCfg, options);
                            if (process != null) {
                                Method apply = XposedHelpers.findMethodExactIfExists(resMgrClass,
                                        "applyConfigurationToResources", Configuration.class,
                                        XposedHelpers.findClass("android.content.res.CompatibilityInfo", null));
                                if (apply != null) apply.invoke(resourcesManager, process, null);
                            }
                        }
                    } catch (Throwable error) { logFailure(error); }
                }
            });
        } catch (Throwable error) { logFailure(error); }
    }

    private boolean tryReloadFromProvider() {
        try {
            Class<?> threadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            Context app = appContext;
            if (app == null) app = (Context) XposedHelpers.callStaticMethod(threadClass, "currentApplication");
            if (app != null && appContext == null) appContext = app;
            Context ctx = app;
            if (ctx == null) {
                Object thread = XposedHelpers.callStaticMethod(threadClass, "currentActivityThread");
                if (thread != null) {
                    ctx = (Context) XposedHelpers.callMethod(thread, "getSystemContext");
                }
            }
            if (ctx != null) {
                java.util.Map<String, Object> fresh = ModuleSettings.queryProvider(ctx);
                if (!fresh.isEmpty()) {
                    providerCache.putAll(fresh);
                }
            }
            return app != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private synchronized void reload() {
        long now = SystemClock.uptimeMillis();
        if (now - lastReload < 1000 || preferences == null) return;
        try {
            preferences.reload();
        } catch (Throwable ignored) {}

        boolean canRead = false;
        try { canRead = preferences.getFile().canRead(); } catch (Throwable ignored) {}
        boolean hasData = false;
        try { hasData = preferences.getAll() != null && !preferences.getAll().isEmpty(); } catch (Throwable ignored) {}

        boolean providerAttempted = true;
        if (!canRead && !hasData) {
            providerAttempted = tryReloadFromProvider();
        }
        // Only start the 1s cooldown once prefs are readable or an Application Context existed to query SettingsProvider.
        if (canRead || hasData || !providerCache.isEmpty() || providerAttempted) {
            lastReload = now;
        }

        try {
            boolean enabled = preferenceFlag("enabled", true);
            boolean diagnostic = false;
            boolean logging = preferenceFlag("logging", false);
            boolean demo = preferenceFlag("demo", false);
            boolean allApps = preferenceFlag("all_apps", true);
            int refDp = preferenceInt("reference_dp", ScalePolicy.DEFAULT_REFERENCE_DP);
            String pkgs = preferenceString("packages", ModuleSettings.DEFAULT_PACKAGES);
            String excl = preferenceString("excluded_packages", "");
            boolean sysScaleAll = preferenceFlag("system_scale_all", true);

            options = new Options(enabled, diagnostic, logging, demo, allApps, refDp,
                    ModuleSettings.parsePackages(pkgs), ModuleSettings.parsePackages(excl), sysScaleAll);
            pinPrefsStatus(canRead || hasData, !providerCache.isEmpty());
        } catch (RuntimeException invalid) {
            logFailure(invalid);
        }
    }

    private void pinPrefsStatus(boolean fileReadable, boolean providerReadable) {}

    private boolean preferenceFlag(String key, boolean fallback) {
        if (preferences != null) {
            try {
                if (preferences.contains(key)) {
                    return preferences.getBoolean(key, SettingsDefaults.flag(key, fallback));
                }
            } catch (Throwable ignored) {}
        }
        Object prov = providerCache.get(key);
        if (prov instanceof Boolean) return (Boolean) prov;
        return SettingsDefaults.flag(key, fallback);
    }

    private int preferenceInt(String key, int fallback) {
        if (preferences != null) {
            try {
                if (preferences.contains(key)) {
                    return preferences.getInt(key, fallback);
                }
            } catch (Throwable ignored) {}
        }
        Object prov = providerCache.get(key);
        if (prov instanceof Integer) return (Integer) prov;
        return fallback;
    }

    private String preferenceString(String key, String fallback) {
        if (preferences != null) {
            try {
                if (preferences.contains(key)) {
                    return preferences.getString(key, fallback);
                }
            } catch (Throwable ignored) {}
        }
        Object prov = providerCache.get(key);
        if (prov instanceof String) return (String) prov;
        return fallback;
    }

    private boolean isPrefsAccessible() {
        if (preferences != null) {
            try {
                if (preferences.getFile().canRead()) return true;
                if (preferences.getAll() != null && !preferences.getAll().isEmpty()) return true;
            } catch (Throwable ignored) {}
        }
        return !providerCache.isEmpty();
    }

    private void logFailure(Throwable error) {
        if (failureLogged) return;
        failureLogged = true;
        log("Hook failed; kept original app behavior: " + Log.getStackTraceString(error));
    }
    private void log(String message) {
        String line = "[" + packageName + "] " + message;
        Log.i(TAG, line);
        XposedBridge.log(TAG + " " + line);
    }

    private static final class Options {
        static final Options OFF = new Options(false, true, false, false, false,
                ScalePolicy.DEFAULT_REFERENCE_DP, Collections.emptySet(), Collections.emptySet(), false);
        final boolean enabled, diagnostic, logging, demo, allApps, systemScale;
        final int referenceDp;
        final Set<String> packages, excluded;
        Options(boolean enabled, boolean diagnostic, boolean logging, boolean demo, boolean allApps,
                int referenceDp, Set<String> packages, Set<String> excluded, boolean systemScale) {
            this.enabled = enabled;
            this.diagnostic = diagnostic;
            this.logging = logging;
            this.demo = demo;
            this.allApps = allApps;
            this.referenceDp = referenceDp;
            this.packages = packages;
            this.excluded = excluded;
            this.systemScale = systemScale;
        }
        boolean selects(String candidate) {
            return AppScopePolicy.includes(candidate, allApps, demo, packages, excluded);
        }
    }
}
