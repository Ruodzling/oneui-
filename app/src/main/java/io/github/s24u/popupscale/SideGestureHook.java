package io.github.s24u.popupscale;

import android.app.ActivityOptions;
import android.app.AndroidAppHelper;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Good Lock One Hand Operation + (com.samsung.android.sidegesturepad) hook.
 * Launches apps directly in popup / freeform window mode (WINDOWING_MODE_FREEFORM = 5)
 * when tapped from the FastAction (快速启动器) floating menu, removing the need to
 * long-press and manually select "弹出窗口".
 */
public final class SideGestureHook {
    private static final String TAG = "PopupScale-SideGesture";
    public static final String PACKAGE_NAME = SideGesturePolicy.PACKAGE_NAME;
    public static final String PREF_KEY_FAST_ACTION_POPUP = SideGesturePolicy.PREF_KEY_FAST_ACTION_POPUP;

    public static final ThreadLocal<Boolean> fastActionLaunching = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private static volatile boolean installed = false;
    private static Class<?> fastActionCallbackClass = null;
    private static final java.util.Set<Method> dispatchers = new java.util.HashSet<>();
    private static volatile Context cachedAppContext = null;
    private static final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private static final Object settingsLock = new Object();
    private static volatile Map<String, Object> providerCache = Collections.emptyMap();
    private static volatile long lastProviderReadMs = -5000L;
    private static volatile boolean providerDirty = true;

    private static final class OhoSettingsSnapshot {
        final String source;
        final boolean readable;
        final boolean providerLoaded;
        final boolean available;
        final boolean masterEnabled;
        final boolean featureEnabled;
        final boolean allApps;
        final boolean demo;
        final Set<String> selected;
        final Set<String> excluded;

        OhoSettingsSnapshot(String source, boolean readable, boolean providerLoaded, boolean available,
                            boolean masterEnabled, boolean featureEnabled, boolean allApps, boolean demo,
                            Set<String> selected, Set<String> excluded) {
            this.source = source;
            this.readable = readable;
            this.providerLoaded = providerLoaded;
            this.available = available;
            this.masterEnabled = masterEnabled;
            this.featureEnabled = featureEnabled;
            this.allApps = allApps;
            this.demo = demo;
            this.selected = selected;
            this.excluded = excluded;
        }
    }

    private SideGestureHook() {}

    public static synchronized boolean install(ClassLoader classLoader) {
        if (installed) return true;
        boolean hookedAny = false;

        // 1. Hook FastAction click dispatcher methods (sets fastActionLaunching thread flag)
        hookFastActionDispatchers(classLoader);

        // 2. Hook SGP launch helpers (e.g. n6.y.H1 and n6.y.P1)
        hookSgpLaunchHelpers(classLoader);

        // 3. Hook ContextImpl.startActivityAsUser (the primary mechanism used by H1 and P1)
        try {
            Class<?> contextImplClass = XposedHelpers.findClassIfExists("android.app.ContextImpl", classLoader);
            if (contextImplClass == null) {
                contextImplClass = XposedHelpers.findClassIfExists("android.app.ContextImpl", Context.class.getClassLoader());
            }
            if (contextImplClass != null) {
                for (Method m : contextImplClass.getDeclaredMethods()) {
                    if ("startActivityAsUser".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args == null || param.args.length < 2) return;
                                if (!(param.args[0] instanceof Intent)) return;
                                Intent intent = (Intent) param.args[0];
                                String targetPkg = SideGesturePolicy.extractTargetPackage(intent);
                                Context ctx = param.thisObject instanceof Context ? (Context) param.thisObject : null;
                                if (!shouldRedirectLaunch(ctx, targetPkg, "ContextImpl.startActivityAsUser")) return;

                                if (param.args.length >= 3) {
                                    param.args[1] = mergePopupOptions((Bundle) param.args[1]);
                                    log("redirected ContextImpl.startActivityAsUser to popup: " + targetPkg);
                                }
                            }
                        });
                        hookedAny = true;
                    }
                }
            }
        } catch (Throwable t) {
            log("failed to hook ContextImpl.startActivityAsUser: " + t.getClass().getName());
        }

        // 4. Hook ContextImpl and ContextWrapper startActivity
        try {
            Class<?> contextImplClass = XposedHelpers.findClassIfExists("android.app.ContextImpl", classLoader);
            if (contextImplClass == null) {
                contextImplClass = XposedHelpers.findClassIfExists("android.app.ContextImpl", Context.class.getClassLoader());
            }
            Class<?>[] contextClasses = contextImplClass != null
                    ? new Class<?>[]{ContextWrapper.class, contextImplClass}
                    : new Class<?>[]{ContextWrapper.class};

            for (Class<?> clazz : contextClasses) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, "startActivity",
                            Intent.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args == null || param.args.length < 2) return;
                            if (param.args[0] == null) return;
                            Intent intent = (Intent) param.args[0];
                            String targetPkg = SideGesturePolicy.extractTargetPackage(intent);
                            Context ctx = param.thisObject instanceof Context ? (Context) param.thisObject : null;
                            if (!shouldRedirectLaunch(ctx, targetPkg, "Context.startActivityWithOptions")) return;

                            param.args[1] = mergePopupOptions((Bundle) param.args[1]);
                            log("redirected " + param.thisObject.getClass().getSimpleName()
                                    + ".startActivity(Intent, Bundle) to popup: " + targetPkg);
                        }
                    });
                    hookedAny = true;
                } catch (Throwable ignored) {}

                try {
                    XposedHelpers.findAndHookMethod(clazz, "startActivity",
                            Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                            Intent intent = (Intent) param.args[0];
                            String targetPkg = SideGesturePolicy.extractTargetPackage(intent);
                            Context ctx = param.thisObject instanceof Context ? (Context) param.thisObject : null;
                            if (!shouldRedirectLaunch(ctx, targetPkg, "Context.startActivity")) return;

                            Context callCtx = (Context) param.thisObject;
                            callCtx.startActivity(intent, mergePopupOptions(null));
                            param.setResult(null);
                            log("redirected " + param.thisObject.getClass().getSimpleName()
                                    + ".startActivity(Intent) to popup: " + targetPkg);
                        }
                    });
                    hookedAny = true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log("failed to hook startActivity: " + t.getClass().getName());
        }

        // 5. Universal safety net: Hook Instrumentation.execStartActivity
        try {
            for (Method m : Instrumentation.class.getDeclaredMethods()) {
                if ("execStartActivity".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args == null || param.args.length < 6) return;
                            // In execStartActivity overloads, find the Intent and Bundle parameters
                            Intent intent = null;
                            int bundleIdx = -1;
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof Intent) {
                                    intent = (Intent) param.args[i];
                                } else if (param.args[i] == null || param.args[i] instanceof Bundle) {
                                    Class<?> pType = param.method instanceof Method
                                            ? ((Method) param.method).getParameterTypes()[i]
                                            : null;
                                    if (pType == Bundle.class) {
                                        bundleIdx = i;
                                    }
                                }
                            }

                            if (intent == null) return;
                            String targetPkg = SideGesturePolicy.extractTargetPackage(intent);
                            Context ctx = param.args[0] instanceof Context ? (Context) param.args[0] : null;
                            if (!shouldRedirectLaunch(ctx, targetPkg, "Instrumentation.execStartActivity")) return;

                            if (bundleIdx >= 0) {
                                param.args[bundleIdx] = mergePopupOptions((Bundle) param.args[bundleIdx]);
                                log("redirected Instrumentation.execStartActivity to popup: " + targetPkg);
                            }
                        }
                    });
                    hookedAny = true;
                }
            }
        } catch (Throwable t) {
            log("failed to hook Instrumentation.execStartActivity: " + t.getClass().getName());
        }

        if (hookedAny) {
            installed = true;
        }
        return installed;
    }

    private static void hookFastActionDispatchers(ClassLoader classLoader) {
        // 9.4.23 moves the callback from v5.v to a6.f0. Resolve it through
        // SGPFastActionView ownership and the verified click/menu/item contracts.
        try {
            Class<?> view=XposedHelpers.findClassIfExists("com.samsung.android.sidegesturepad.fastaction.SGPFastActionView",classLoader);
            java.util.List<Method> clicks=view==null?java.util.Collections.emptyList():SideGestureDispatchDiscovery.find(view);
            if(clicks.size()==1){installDispatcher(clicks.get(0));}
            else log("FastAction callback discovery count="+clicks.size()+"; legacy dispatcher retained");
        }catch(Throwable error){log("FastAction callback discovery failed type="+error.getClass().getName());}
        // Hook v5.v.c(v5.h) or similar item click handler
        try {
            Class<?> vClass = XposedHelpers.findClassIfExists("v5.v", classLoader);
            if (vClass != null) {
                for (Method m : vClass.getDeclaredMethods()) {
                    if ("c".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        installDispatcher(m);
                        log("hooked FastAction item click dispatcher: v5.v.c");
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
    private static void installDispatcher(Method method){
        if(!dispatchers.add(method))return;
        XposedBridge.hookMethod(method,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(p.thisObject instanceof View)resolveContext(((View)p.thisObject).getContext());
                p.setObjectExtra("popupscale.oho.previous",fastActionLaunching.get());fastActionLaunching.set(Boolean.TRUE);
            }
            @Override protected void afterHookedMethod(MethodHookParam p){fastActionLaunching.set(Boolean.TRUE.equals(p.getObjectExtra("popupscale.oho.previous")));}
        });
        log("FastAction click dispatcher bound "+method.getDeclaringClass().getName()+"."+method.getName());
    }

    private static void hookSgpLaunchHelpers(ClassLoader classLoader) {
        // Known SGPUtils class names across versions
        for (String known : new String[]{"n6.y", "m6.y", "o6.y", "p6.y", "n7.y", "com.samsung.android.sidegesturepad.SGPUtils"}) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(known, classLoader);
                if (clazz == null) continue;

                // Hook H1(ComponentName, ActivityOptions, int)
                for (Method m : clazz.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 3 && pts[0] == ComponentName.class && pts[1] == ActivityOptions.class && pts[2] == int.class) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args == null || param.args.length < 3) return;
                                if (!(param.args[0] instanceof ComponentName)) return;
                                ComponentName cn = (ComponentName) param.args[0];
                                if (!shouldRedirectLaunch(null, cn.getPackageName(), "Sgp.popupLaunchHelper")) return;

                                ActivityOptions options = (ActivityOptions) param.args[1];
                                if (options == null) {
                                    options = ActivityOptions.makeBasic();
                                }
                                applyPopupMode(options);
                                param.args[1] = options;
                                log("redirected H1 launch to popup: " + cn);
                            }
                        });
                        log("hooked launchMethod (H1): " + clazz.getName() + "." + m.getName());
                    } else if (pts.length == 2 && pts[0] == String.class && pts[1] == int.class && "P1".equals(m.getName())) {
                        // Hook P1(String, int) which launches over target task
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setObjectExtra("popupscale.oho.helper.previous",fastActionLaunching.get());
                                fastActionLaunching.set(Boolean.TRUE);
                            }
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                fastActionLaunching.set(Boolean.TRUE.equals(param.getObjectExtra("popupscale.oho.helper.previous")));
                            }
                        });
                        log("hooked launchMethod (P1): " + clazz.getName() + "." + m.getName());
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void applyPopupMode(ActivityOptions options) {
        if (options == null) return;
        try {
            Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            m.invoke(options, 5);
        } catch (Throwable t) {
            try {
                XposedHelpers.callMethod(options, "setLaunchWindowingMode", 5);
            } catch (Throwable ignored) {}
        }
    }

    public static Bundle mergePopupOptions(Bundle existing) {
        ActivityOptions options = ActivityOptions.makeBasic();
        applyPopupMode(options);
        Bundle optBundle = options.toBundle();
        if (existing != null) {
            existing.putAll(optBundle);
            return existing;
        }
        return optBundle;
    }

    private static void log(String message) {
        android.util.Log.i("PopupScale", "[OHO] " + message);
        XposedBridge.log(TAG + ": " + message);
    }

    private static Context resolveContext(Context candidate) {
        Context ctx = candidate != null ? candidate : cachedAppContext;
        if (ctx == null) {
            try { ctx = AndroidAppHelper.currentApplication(); } catch (Throwable ignored) {}
        }
        if (ctx == null) return null;
        Context appCtx = ctx.getApplicationContext();
        Context resolved = appCtx != null ? appCtx : ctx;
        cachedAppContext = resolved;
        ensureReceiver(resolved);
        return resolved;
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void ensureReceiver(Context appCtx) {
        if (!receiverRegistered.compareAndSet(false, true)) return;
        try {
            BroadcastReceiver invalidator = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Broadcast is an invalidation signal only. Never trust supplied extras.
                    providerDirty = true;
                }
            };
            IntentFilter changedFilter = new IntentFilter(ModuleSettings.ACTION_SETTINGS_CHANGED);
            IntentFilter unlockFilter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(invalidator, changedFilter, Context.RECEIVER_EXPORTED);
                appCtx.registerReceiver(invalidator, unlockFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appCtx.registerReceiver(invalidator, changedFilter);
                appCtx.registerReceiver(invalidator, unlockFilter);
            }
        } catch (Throwable ignored) {
            receiverRegistered.set(false);
        }
    }

    private static boolean provFlag(Map<String, Object> values, String key, boolean fallback) {
        Object val = values.get(key);
        return val instanceof Boolean ? (Boolean) val : SettingsDefaults.flag(key, fallback);
    }

    private static String provString(Map<String, Object> values, String key, String fallback) {
        Object val = values.get(key);
        return val instanceof String ? (String) val : fallback;
    }

    private static OhoSettingsSnapshot loadSettings(Context candidate) {
        synchronized (settingsLock) {
            XSharedPreferences prefs = new XSharedPreferences(ModuleSettings.PACKAGE, ModuleSettings.PREFS);
            try { prefs.reload(); } catch (Throwable ignored) {}
            boolean fileReadable = false;
            boolean hasData = false;
            try { fileReadable = prefs.getFile().canRead(); } catch (Throwable ignored) {}
            try { hasData = prefs.getAll() != null && !prefs.getAll().isEmpty(); } catch (Throwable ignored) {}

            OhoSettingsSnapshot snapshot;
            if (fileReadable || hasData) {
                boolean masterEnabled = prefs.getBoolean("enabled", SettingsDefaults.flag("enabled", false));
                boolean featureEnabled = prefs.getBoolean(PREF_KEY_FAST_ACTION_POPUP, SettingsDefaults.flag(PREF_KEY_FAST_ACTION_POPUP, true));
                boolean allApps = prefs.getBoolean("all_apps", SettingsDefaults.flag("all_apps", true));
                boolean demo = prefs.getBoolean("demo", SettingsDefaults.flag("demo", true));
                Set<String> selected = SideGesturePolicy.parsePackageNames(prefs.getString("packages", ModuleSettings.DEFAULT_PACKAGES));
                Set<String> excluded = SideGesturePolicy.parsePackageNames(prefs.getString("excluded_packages", ""));
                snapshot = new OhoSettingsSnapshot("xshared", true, false, true,
                        masterEnabled, featureEnabled, allApps, demo, selected, excluded);
            } else {
                Context ctx = resolveContext(candidate);
                long now = SystemClock.uptimeMillis();
                String source;
                if (ctx != null && (providerDirty || providerCache.isEmpty() || now - lastProviderReadMs >= 750L)) {
                    Map<String, Object> fresh = ModuleSettings.queryProvider(ctx);
                    if (fresh != null && fresh.get("enabled") instanceof Boolean) {
                        providerCache = new HashMap<>(fresh);
                        lastProviderReadMs = now;
                        providerDirty = false;
                        source = "provider";
                    } else if (!providerDirty && !providerCache.isEmpty()) {
                        source = "provider-cache";
                    } else {
                        source = "unavailable";
                    }
                } else if (!providerCache.isEmpty()) {
                    source = "provider-cache";
                } else {
                    source = "unavailable";
                }

                if ("provider".equals(source) || "provider-cache".equals(source)) {
                    Map<String, Object> values = providerCache;
                    boolean masterEnabled = provFlag(values, "enabled", false);
                    boolean featureEnabled = provFlag(values, PREF_KEY_FAST_ACTION_POPUP, true);
                    boolean allApps = provFlag(values, "all_apps", true);
                    boolean demo = provFlag(values, "demo", true);
                    Set<String> selected = SideGesturePolicy.parsePackageNames(provString(values, "packages", ModuleSettings.DEFAULT_PACKAGES));
                    Set<String> excluded = SideGesturePolicy.parsePackageNames(provString(values, "excluded_packages", ""));
                    snapshot = new OhoSettingsSnapshot(source, false, true, true,
                            masterEnabled, featureEnabled, allApps, demo, selected, excluded);
                } else {
                    snapshot = new OhoSettingsSnapshot("unavailable", false, false, false,
                            false, false, false, false, Collections.emptySet(), Collections.emptySet());
                }
            }

            return snapshot;
        }
    }

    public static boolean isCalledFromFastAction() {
        return Boolean.TRUE.equals(fastActionLaunching.get()) ||
            SideGesturePolicy.isCalledFromFastAction(Thread.currentThread().getStackTrace(), fastActionCallbackClass);
    }

    private static boolean shouldRedirectLaunch(Context context, String target, String entry) {
        if (!SideGesturePolicy.shouldRedirectTarget(target)) return false;
        OhoSettingsSnapshot settings = loadSettings(context);
        boolean scopeAllowed = SideGesturePolicy.shouldRedirectTarget(
                target,
                settings.available,
                settings.masterEnabled,
                settings.featureEnabled,
                settings.allApps,
                settings.demo,
                settings.selected,
                settings.excluded);
        return scopeAllowed && isCalledFromFastAction();
    }
}
