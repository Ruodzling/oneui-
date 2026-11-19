package io.github.s24u.popupscale;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import de.robv.android.xposed.XposedHelpers;

final class CanvasController {
    private final ChatWindowInsets chatInsets = new ChatWindowInsets();
    private final WeakHashMap<Activity, WeakReference<ZoomFrameLayout>> frames = new WeakHashMap<>();
    private final WeakHashMap<Activity, Integer> modeHints = new WeakHashMap<>();
    private final WeakHashMap<Activity, Integer> reportedModes = new WeakHashMap<>();
    private final WeakHashMap<ViewGroup, WeakReference<Activity>> hosts = new WeakHashMap<>();
    Activity ownerOfHost(ViewGroup host) {
        for (android.view.ViewParent p = host; p instanceof ViewGroup; p = p.getParent()) {
            WeakReference<Activity> ref = hosts.get((ViewGroup) p);
            if (ref != null && ref.get() != null) return ref.get();
        }
        return null;
    }
    private int wechatFullWidthDp;
    void setMode(Activity activity, int mode) { if (mode > 0) modeHints.put(activity, mode); }
    void update(Activity activity, boolean enabled, boolean diagnostic, int density,
            int referenceDp, boolean logging) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
        WeakReference<ZoomFrameLayout> reference = frames.get(activity);
        ZoomFrameLayout frame = reference == null ? null : reference.get();
        View decor = activity.getWindow().getDecorView();
        boolean outerCanvas = false;
        if (AppCanvasPolicy.requiresConsistentDensity(activity.getPackageName())) {
            for (android.view.ViewParent p = decor.getParent(); p != null; p = p.getParent()) {
                if (p instanceof ZoomFrameLayout || p.getClass().getName().equals(ZoomFrameLayout.class.getName())) {
                    outerCanvas = true;
                    break;
                }
            }
        }
        if (!AppCanvasPolicy.ownsCanvas(activity.getPackageName(), activity.getParent() != null, outerCanvas)) {
            // ActivityGroup pages are already measured and transformed by the outer Activity.
            if (frame != null) {
                frame.setCaptionInsetEnabled(false);
                frame.configure(false, density, referenceDp, logging);
            }
            if (logging && !reportedModes.containsKey(activity)) {
                reportedModes.put(activity, 5);
                Log.i("PopupScale", "embedded canvas skipped activity=" + activity.getClass().getName()
                        + " metricsDpi=" + activity.getResources().getDisplayMetrics().densityDpi);
            }
            return;
        }
        View content = decor.findViewById(android.R.id.content);
        boolean isLauncherUI = WechatScalePolicy.isLauncherUI(activity.getClass().getName());
        View home = isLauncherUI ? findHome(decor) : null;
        ViewGroup parent;
        if (isLauncherUI) {
            if (home == null || !(home.getParent() instanceof ViewGroup)) {
                // In LauncherUI, inline chat and HomeUI share FitSystemWindowLayoutView's parent (homeParent).
                // Wait until HomeUI root is attached to avoid prematurely wrapping an inner ActionBarOverlayLayout.
                return;
            }
            ViewGroup homeParent = (ViewGroup) home.getParent();
            parent = frame != null && homeParent == frame.canvas()
                    && frame.getParent() instanceof ViewGroup
                    ? (ViewGroup) frame.getParent() : homeParent;
        } else if (frame != null && frame.getParent() instanceof ViewGroup) {
            // On later callbacks the host is already in our canvas or owns our frame.
            // Keep its original viewport host rather than nesting a new canvas.
            parent = (ViewGroup) frame.getParent();
        } else if ("com.tencent.mm".equals(activity.getPackageName())
                && content != null && content.getParent() instanceof ViewGroup) {
            // WeChat reserves toolbar space inside its content while the toolbar is
            // a sibling in AppCompat's overlay. Both must use the same coordinates.
            // Wrap the intact overlay, preserving AppCompat's own child hierarchy.
            ViewGroup overlay = (ViewGroup) content.getParent();
            if (overlay.getClass().getName().endsWith(".ActionBarOverlayLayout")
                    && overlay.getParent() instanceof FrameLayout) {
                ViewGroup overlayParent = (FrameLayout) overlay.getParent();
                parent = frame != null && overlayParent == frame.canvas()
                        && frame.getParent() instanceof ViewGroup
                        ? (ViewGroup) frame.getParent() : overlayParent;
            } else {
                parent = (ViewGroup) content;
            }
        } else {
            if (!(content instanceof ViewGroup)) return;
            parent = (ViewGroup) content;
        }
        if (decor instanceof ViewGroup) {
            hosts.put((ViewGroup) decor, new WeakReference<>(activity));
        }
        if (content instanceof ViewGroup) {
            hosts.put((ViewGroup) content, new WeakReference<>(activity));
        }
        if (home instanceof ViewGroup) {
            hosts.put((ViewGroup) home, new WeakReference<>(activity));
        }
        hosts.put(parent, new WeakReference<>(activity));
        if ((frame == null || frame.getParent() != parent) && (!enabled || diagnostic)) return;
        if ((frame == null || frame.getParent() != parent) && parent.getChildCount() == 1) {
            View existing = parent.getChildAt(0);
            if (existing instanceof ZoomFrameLayout) {
                frame = (ZoomFrameLayout) existing;
                frames.put(activity, new WeakReference<>(frame));
            } else if (existing.getClass().getName().equals(ZoomFrameLayout.class.getName())) {
                // A different module loader owns it. Never wrap that canvas a second time.
                return;
            }
        }
        if (frame == null || frame.getParent() != parent) {
            if (parent.getChildCount() == 0) return;
            frame = new ZoomFrameLayout(activity);
            while (parent.getChildCount() > 0) {
                View child = parent.getChildAt(0);
                ViewGroup.LayoutParams old = child.getLayoutParams();
                parent.removeViewAt(0);
                frame.canvas().addView(child, canvasParams(child, home, old));
            }
            parent.addView(frame, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frames.put(activity, new WeakReference<>(frame));
        } else {
            boolean beforeFrame = true;
            int beforeIndex = 0;
            for (int i = 0; i < parent.getChildCount();) {
                View child = parent.getChildAt(i);
                if (child != frame) {
                    String childName = child.getClass().getName();
                    if (childName.contains("Caption") || childName.contains("Outline")
                            || childName.contains("Decoration")) {
                        i++;
                        continue;
                    }
                    ViewGroup.LayoutParams old = child.getLayoutParams();
                    parent.removeViewAt(i);
                    FrameLayout.LayoutParams params = canvasParams(child, home, old);
                    if (beforeFrame) frame.canvas().addView(child, beforeIndex++, params);
                    else frame.canvas().addView(child, params);
                } else { beforeFrame = false; i++; }
            }
        }
        Configuration config = activity.getResources().getConfiguration();
        Object window = XposedHelpers.getObjectField(config, "windowConfiguration");
        int resourceMode = (Integer) XposedHelpers.callMethod(window, "getWindowingMode");
        int mode = windowMode(activity);
        chatInsets.update(activity, parent, frame, enabled && !diagnostic, mode, logging);
        int canvasDpi = canvasDensity(activity, density);
        int widthDp = canvasReferenceDp(activity, mode, referenceDp);
        if (logging && (!reportedModes.containsKey(activity) || reportedModes.get(activity) != mode)) {
            reportedModes.put(activity, mode);
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            Log.i("PopupScale", "canvas-controller activity=" + activity.getClass().getName()
                    + " mode=" + mode + " resourceMode=" + resourceMode
                    + " host=" + parent.getClass().getName() + " children=" + frame.canvas().getChildCount()
                    + " configDpi=" + config.densityDpi + " metricsDpi=" + metrics.densityDpi
                    + (AppCanvasPolicy.requiresConsistentDensity(activity.getPackageName())
                            ? " appDpi=" + activity.getApplicationContext().getResources().getDisplayMetrics().densityDpi : "")
                    + " density=" + metrics.density + " metricsPx=" + metrics.widthPixels + "x" + metrics.heightPixels
                    + " canvasDpi=" + canvasDpi + " logicalWidth=" + widthDp);
        }
        boolean activeCanvas = enabled && !diagnostic && canvasMode(activity, mode);
        if (activeCanvas) syncCanvasConfiguration(activity, config, window, decor, mode, widthDp);
        frame.setCaptionInsetEnabled(activeCanvas && AppCanvasPolicy.isTencentCanvas(activity.getPackageName()));
        frame.configure(activeCanvas,
                canvasDpi, widthDp, logging);
        if ("com.tencent.mobileqq".equals(activity.getPackageName())) QQPanelController.watch(activity, frame);
    }
    private void syncCanvasConfiguration(Activity activity, Configuration config,
            Object window, View decor, int mode, int widthDp) {
        if (config == null || widthDp < ScalePolicy.MIN_REFERENCE_DP
                || widthDp > ScalePolicy.MAX_REFERENCE_DP) return;
        int viewportW = decor != null ? decor.getWidth() : 0;
        int viewportH = decor != null ? decor.getHeight() : 0;
        if (viewportW <= 0 || viewportH <= 0) {
            try {
                android.graphics.Rect bounds = window == null ? null
                        : (android.graphics.Rect) XposedHelpers.callMethod(window, "getAppBounds");
                if (bounds != null && !bounds.isEmpty()) {
                    viewportW = bounds.width();
                    viewportH = bounds.height();
                }
            } catch (Throwable ignored) {}
            if ((viewportW <= 0 || viewportH <= 0) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    android.view.WindowManager wm = activity.getWindowManager();
                    if (wm != null) {
                        android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                        if (bounds != null && !bounds.isEmpty()) {
                            viewportW = bounds.width();
                            viewportH = bounds.height();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        int heightDp = WechatScalePolicy.logicalHeightDp(
                viewportW, viewportH, widthDp, config.screenHeightDp);
        if (heightDp <= 0) return;
        int smallestDp = Math.min(widthDp, heightDp);
        applyLogicalDp(config, widthDp, heightDp, smallestDp);
        if ("com.tencent.mm".equals(activity.getPackageName()) && mode == 5) {
            try {
                Configuration appConfig = activity.getApplicationContext().getResources().getConfiguration();
                if (appConfig != null && appConfig != config) {
                    applyLogicalDp(appConfig, widthDp, heightDp, smallestDp);
                }
            } catch (Throwable ignored) {}
        }
    }
    private static void applyLogicalDp(Configuration target, int widthDp, int heightDp, int smallestDp) {
        if (target == null) return;
        if (target.screenWidthDp == widthDp && target.screenHeightDp == heightDp
                && target.smallestScreenWidthDp == smallestDp) return;
        target.screenWidthDp = widthDp;
        target.screenHeightDp = heightDp;
        target.smallestScreenWidthDp = smallestDp;
        try { XposedHelpers.setIntField(target, "compatScreenWidthDp", widthDp); } catch (Throwable ignored) {}
        try { XposedHelpers.setIntField(target, "compatScreenHeightDp", heightDp); } catch (Throwable ignored) {}
        try { XposedHelpers.setIntField(target, "compatSmallestScreenWidthDp", smallestDp); } catch (Throwable ignored) {}
        try {
            int layout = (Integer) XposedHelpers.callStaticMethod(Configuration.class,
                    "resetScreenLayout", target.screenLayout);
            target.screenLayout = (Integer) XposedHelpers.callStaticMethod(Configuration.class,
                    "reduceScreenLayout", layout, Math.max(widthDp, heightDp), smallestDp);
        } catch (Throwable ignored) {}
    }
    int canvasDensity(Activity activity, int fallback) {
        if (!"com.tencent.mm".equals(activity.getPackageName())) return fallback;
        // WeChat adapts its own resources at startup (including in freeform).
        // A dp canvas must use the density that actually created these views,
        // not the physical display density captured before that adaptation.
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float effective = metrics.density;
        if (!Float.isFinite(effective) || effective < 0.5f || effective > 10f) {
            try {
                android.content.Context base = activity.getBaseContext();
                if (base != null) {
                    float baseDensity = base.getResources().getDisplayMetrics().density;
                    if (Float.isFinite(baseDensity) && baseDensity >= 0.5f && baseDensity <= 10f) {
                        effective = baseDensity;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (!Float.isFinite(effective) || effective < 0.5f || effective > 10f) return fallback;
        if (wechatFullWidthDp == 0) {
            // A process first launched in a popup reports the popup's physical pixel width.
            // Fullscreen restoration must use the display's portrait width, never that popup.
            try {
                Object window = XposedHelpers.getObjectField(
                        activity.getResources().getConfiguration(), "windowConfiguration");
                android.graphics.Rect maximum = (android.graphics.Rect)
                        XposedHelpers.callMethod(window, "getMaxBounds");
                if (maximum != null) wechatFullWidthDp = WechatScalePolicy.portraitWidth(
                        maximum.width(), maximum.height(), effective);
            } catch (Throwable ignored) { }
            if (wechatFullWidthDp == 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    android.view.WindowManager wm = activity.getWindowManager();
                    if (wm != null) {
                        android.graphics.Rect maximum = wm.getMaximumWindowMetrics().getBounds();
                        if (maximum != null) wechatFullWidthDp = WechatScalePolicy.portraitWidth(
                                maximum.width(), maximum.height(), effective);
                    }
                } catch (Throwable ignored) { }
            }
            if (wechatFullWidthDp == 0 && windowMode(activity) == 1) {
                int adaptedWidth = Math.round(metrics.widthPixels / effective);
                if (adaptedWidth >= 280 && adaptedWidth <= 600) wechatFullWidthDp = adaptedWidth;
            }
        }
        return Math.round(effective * 160);
    }
    boolean canvasMode(Activity activity, int mode) {
        return mode == 5 || restoreWechatFullscreen(activity, mode);
    }
    int canvasReferenceDp(Activity activity, int mode, int requestedDp) {
        return restoreWechatFullscreen(activity, mode) ? wechatFullWidthDp : requestedDp;
    }
    private boolean restoreWechatFullscreen(Activity activity, int mode) {
        // WeChat retains its startup density on maximize. Restore its observed
        // normal portrait layout width instead of inheriting that small-window DPI.
        return "com.tencent.mm".equals(activity.getPackageName()) && mode == 1
                && wechatFullWidthDp > 0
                && activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
    }
    int windowMode(Activity activity) {
        if (modeHints.containsKey(activity)) return modeHints.get(activity);
        // WeChat overrides Activity.getResources() with its process-wide MMResources, whose
        // windowConfiguration can stay at the launch mode (fullscreen) after the task enters
        // freeform. The base ContextImpl resources are updated per Activity token by
        // ResourcesManager on every configuration change, so read the real mode there first.
        try {
            android.content.Context base = activity.getBaseContext();
            if (base != null) {
                Configuration baseConfig = base.getResources().getConfiguration();
                int baseMode = (Integer) XposedHelpers.callMethod(
                        XposedHelpers.getObjectField(baseConfig, "windowConfiguration"), "getWindowingMode");
                if (baseMode > 0) return baseMode;
            }
        } catch (Throwable ignored) { }
        Configuration config = activity.getResources().getConfiguration();
        Object window = XposedHelpers.getObjectField(config, "windowConfiguration");
        int mode = (Integer) XposedHelpers.callMethod(window, "getWindowingMode");
        try {
            Object nativeConfig = XposedHelpers.getObjectField(activity, "mCurrentConfig");
            if (nativeConfig instanceof Configuration) {
                Object nativeWindow = XposedHelpers.getObjectField(nativeConfig, "windowConfiguration");
                int nativeMode = (Integer) XposedHelpers.callMethod(nativeWindow, "getWindowingMode");
                if (nativeMode > 0) mode = nativeMode;
            }
        } catch (Throwable ignored) { }
        if (mode <= 1 && activity.isInMultiWindowMode()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.view.WindowManager wm = activity.getWindowManager();
                    if (wm != null) {
                        android.graphics.Rect currentBounds = wm.getCurrentWindowMetrics().getBounds();
                        android.graphics.Rect maxBounds = wm.getMaximumWindowMetrics().getBounds();
                        if (currentBounds != null && maxBounds != null) {
                            if (currentBounds.width() < maxBounds.width()
                                    && currentBounds.height() < maxBounds.height()) {
                                return 5;
                            }
                            return 3;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            return 5;
        }
        return mode;
    }
    void forget(Activity activity) {
        chatInsets.forget(activity);
        frames.remove(activity); modeHints.remove(activity); reportedModes.remove(activity);
        hosts.entrySet().removeIf(entry -> entry.getValue().get() == null || entry.getValue().get() == activity);
    }
    private View findHome(View view) {
        if (view == null) return null;
        String name = view.getClass().getName();
        if (name.endsWith(".HomeUI$FitSystemWindowLayoutView")
                || (name.contains("HomeUI") && name.contains("FitSystemWindow"))
                || name.endsWith("$FitSystemWindowLayoutView")
                || name.endsWith(".FitSystemWindowLayoutView")) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child != null) {
                    View found = findHome(child);
                    if (found != null) return found;
                }
            }
        }
        // Fallback: in LauncherUI, resolve top container enclosing android.R.id.content
        View content = view.findViewById(android.R.id.content);
        if (content != null && content != view) {
            View candidate = null;
            for (android.view.ViewParent p = content.getParent(); p instanceof View && p != view; p = p.getParent()) {
                View pv = (View) p;
                if (pv.getParent() == view
                        || (pv.getParent() instanceof View
                            && ((View) pv.getParent()).getParent() == view
                            && pv.getParent().getClass().getName().endsWith("LinearLayout"))) {
                    return pv;
                }
                candidate = pv;
            }
            if (candidate != null && candidate != content) return candidate;
        }
        return null;
    }
    private FrameLayout.LayoutParams canvasParams(View child, View home, ViewGroup.LayoutParams old) {
        FrameLayout.LayoutParams params = asFrameParams(old);
        if (child == home) {
            // HomeUI fills the viewport; do not retain a physical size or a
            // LinearLayout weight when moving it into the virtual FrameLayout.
            params.width = params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        return params;
    }
    private FrameLayout.LayoutParams asFrameParams(ViewGroup.LayoutParams old) {
        if (old instanceof FrameLayout.LayoutParams)
            return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) old);
        if (old instanceof ViewGroup.MarginLayoutParams)
            return new FrameLayout.LayoutParams((ViewGroup.MarginLayoutParams) old);
        return new FrameLayout.LayoutParams(old);
    }
}
