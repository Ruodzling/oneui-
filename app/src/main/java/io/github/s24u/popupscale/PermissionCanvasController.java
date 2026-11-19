package io.github.s24u.popupscale;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Rendering only: never intercepts permission requests, choices or results. */
final class PermissionCanvasController {
    static final String PACKAGE = "com.google.android.permissioncontroller";
    private static final String ACTIVITY =
            "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity";
    private final WeakHashMap<Activity, WeakReference<ZoomFrameLayout>> frames = new WeakHashMap<>();

    static boolean isTarget(Activity activity) {
        return ACTIVITY.equals(activity.getClass().getName());
    }

    void update(Activity activity, CanvasController activities, boolean enabled,
            int referenceDp, boolean logging) {
        if (!isTarget(activity) || activity.isFinishing()
                || activity.isDestroyed() || activity.getWindow() == null) return;
        // Use the framework caller identity, never an untrusted intent extra.
        // The caller has already passed the shared app selection/opt-out policy.
        boolean zoom = enabled && activities.windowMode(activity) == 5;
        WeakReference<ZoomFrameLayout> stored = frames.get(activity);
        ZoomFrameLayout frame = stored == null ? null : stored.get();
        View decor = activity.getWindow().getDecorView();
        View content = decor.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) content;
        if (frame == null || frame.getParent() != parent) {
            if (!zoom || parent.getChildCount() == 0) return;
            frame = new ZoomFrameLayout(activity);
            while (parent.getChildCount() > 0) {
                View child = parent.getChildAt(0);
                ViewGroup.LayoutParams previous = child.getLayoutParams();
                parent.removeViewAt(0);
                FrameLayout.LayoutParams params = previous instanceof FrameLayout.LayoutParams
                        ? new FrameLayout.LayoutParams((FrameLayout.LayoutParams) previous)
                        : previous instanceof ViewGroup.MarginLayoutParams
                        ? new FrameLayout.LayoutParams((ViewGroup.MarginLayoutParams) previous)
                        : new FrameLayout.LayoutParams(previous);
                frame.canvas().addView(child, params);
            }
            // Preserve the sheet's natural height and the system Window attributes.
            parent.addView(frame, new ViewGroup.LayoutParams(-1, -2));
            frames.put(activity, new WeakReference<>(frame));
            if (logging) Log.i("PopupScale", "permission-canvas caller="
                    + activity.getCallingPackage() + " mode=5");
        }
        int dpi = Math.round(activity.getResources().getDisplayMetrics().density * 160f);
        frame.configureDialog(zoom, dpi, referenceDp, decor,
                activity.getResources().getDisplayMetrics().widthPixels, logging);
    }

    void forget(Activity activity) { frames.remove(activity); }
}
