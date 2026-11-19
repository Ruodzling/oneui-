package io.github.s24u.popupscale;

import android.app.Dialog;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Only the share DialogPanel's fixed window height; no resources, canvas or player hooks. */
final class DouyinSharePanelController {
    private final String pkg;
    private final BooleanSupplier enabled;
    private final WeakHashMap<Window, State> windows = new WeakHashMap<>();
    private boolean failureReported;

    private static final class State {
        int requested, applied;
        boolean eligible, applying, watched, queued;
        String status = "";
    }

    DouyinSharePanelController(String pkg, BooleanSupplier enabled) {
        this.pkg = pkg;
        this.enabled = enabled;
    }

    void install() {
        XposedHelpers.findAndHookMethod(Window.class, "setLayout", int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Window w = (Window) p.thisObject;
                    if (!target(w)) return;
                    State s = state(w);
                    if (s.applying) return;
                    s.requested = (Integer) p.args[1];
                    s.applied = constrain(w, s.requested);
                    p.args[1] = s.applied;
                    changed(s, "request");
                } catch (Throwable e) { failure(e); }
            }
        });
        XposedHelpers.findAndHookMethod(Window.class, "setAttributes", WindowManager.LayoutParams.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Window w = (Window) p.thisObject;
                    if (!target(w) || !(p.args[0] instanceof WindowManager.LayoutParams)) return;
                    State s = state(w);
                    if (s.applying) return;
                    WindowManager.LayoutParams a = (WindowManager.LayoutParams) p.args[0];
                    // Unrelated flag/gravity updates often reuse our already-clamped height.
                    if (a.height != s.applied || s.requested == 0) s.requested = a.height;
                    int next = constrain(w, s.requested);
                    if (next != a.height) {
                        WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
                        copy.copyFrom(a); copy.height = next; p.args[0] = copy;
                    }
                    s.applied = next;
                } catch (Throwable e) { failure(e); }
            }
        });
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Dialog dialog = (Dialog) p.thisObject;
                    if (targetDialog(dialog) && dialog.getWindow() != null)
                        state(dialog.getWindow()).eligible = true;
                } catch (Throwable e) { failure(e); }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Dialog dialog = (Dialog) p.thisObject;
                    Window w = dialog.getWindow();
                    if (w != null) Log.i("PopupScale", "[" + pkg + "] dialog geometry class="
                            + dialog.getClass().getName() + " requestedHeight=" + w.getAttributes().height);
                    if (!target(w)) return;
                    watch(w); update(w);
                } catch (Throwable e) { failure(e); }
            }
        });
        Log.i("PopupScale", "[" + pkg + "] share dialog height compatibility ready; external content scaling unchanged");
    }

    private boolean target(Window w) {
        if (w == null) return false;
        State known = windows.get(w);
        if (known != null && known.eligible) return true;
        // AppCompat normally wraps Window.Callback, so callback instanceof Dialog
        // cannot establish identity. Dialog.show registers its actual window first.
        return w.getCallback() instanceof Dialog && targetDialog((Dialog) w.getCallback());
    }

    private boolean targetDialog(Dialog dialog) {
        for (Class<?> c = dialog.getClass(); c != null && c != Dialog.class; c = c.getSuperclass())
            if (DouyinShareGeometry.target(pkg, c.getName())) return true;
        return false;
    }

    private State state(Window w) {
        State s = windows.get(w);
        if (s == null) { s = new State(); windows.put(w, s); }
        return s;
    }

    private int constrain(Window w, int requested) {
        if (android.os.Build.VERSION.SDK_INT < 30 || requested <= 0) return requested;
        boolean active = enabled.getAsBoolean();
        Configuration cfg = w.getContext().getResources().getConfiguration();
        Object wc = XposedHelpers.getObjectField(cfg, "windowConfiguration");
        int mode = (Integer) XposedHelpers.callMethod(wc, "getWindowingMode");
        WindowManager wm = w.getWindowManager();
        if (wm == null) return requested;
        int height = wm.getCurrentWindowMetrics().getBounds().height();
        int display = wm.getDefaultDisplay().getDisplayId();
        State s = state(w);
        String status = "active=" + active + " mode=" + mode + " available=" + height + " display=" + display;
        if (!status.equals(s.status)) {
            s.status = status;
            Log.i("PopupScale", "[" + pkg + "] share dialog geometry " + status);
        }
        return DouyinShareGeometry.height(requested, height, active, mode, display);
    }

    private void watch(Window w) {
        State s = state(w);
        if (s.watched) return;
        s.watched = true;
        View root = w.getDecorView();
        WeakReference<Window> ref = new WeakReference<>(w);
        android.view.ViewTreeObserver.OnGlobalLayoutListener layout = () -> {
            Window current = ref.get();
            if (current == null || s.queued) return;
            s.queued = true;
            root.post(() -> {
                s.queued = false;
                Window next = ref.get();
                if (next != null && root.isAttachedToWindow()) {
                    try { update(next); } catch (Throwable e) { failure(e); }
                }
            });
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                if (v.getViewTreeObserver().isAlive()) v.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
                v.removeOnAttachStateChangeListener(this);
                Window current = ref.get();
                if (current != null) windows.remove(current);
            }
        });
    }

    private void update(Window w) {
        State s = state(w);
        if (s.applying) return;
        int actual = w.getAttributes().height;
        if (s.requested == 0 || actual != s.applied) s.requested = actual;
        int next = constrain(w, s.requested);
        if (actual == next) { s.applied = next; return; }
        s.applying = true;
        try { w.setLayout(w.getAttributes().width, next); s.applied = next; changed(s, "resize"); }
        finally { s.applying = false; }
    }

    private void changed(State s, String reason) {
        if (s.requested == s.applied) return;
        Log.i("PopupScale", "[" + pkg + "] share dialog height " + s.requested + "->" + s.applied + " reason=" + reason);
    }
    private void failure(Throwable e) {
        if (failureReported) return;
        failureReported = true;
        Log.w("PopupScale", "share dialog kept native behavior: " + e.getClass().getSimpleName());
    }
}
