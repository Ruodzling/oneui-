package io.github.s24u.popupscale;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Dialogs use their owner's scale and measure wrap-content height in virtual coordinates. */
final class WechatDialogController {
    private final WeakHashMap<Dialog, WeakReference<ZoomFrameLayout>> frames = new WeakHashMap<>();
    private final WeakHashMap<Dialog, Integer> originalWidths = new WeakHashMap<>();

    void update(Dialog dialog, CanvasController activities, boolean enabled, boolean diagnostic,
            int density, int referenceDp, boolean logging) {
        if (!dialog.isShowing()) return;
        Window window = dialog.getWindow();
        Activity owner = owner(dialog);
        if (window == null || owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        WeakReference<ZoomFrameLayout> ref = frames.get(dialog);
        ZoomFrameLayout frame = ref == null ? null : ref.get();
        int mode = activities.windowMode(owner);
        boolean isFreeform = mode == 5;
        if (!isFreeform && frame == null) return;
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (!originalWidths.containsKey(dialog)) originalWidths.put(dialog, attributes.width);
        int canvasDpi = activities.canvasDensity(owner, density);
        int widthDp = activities.canvasReferenceDp(owner, mode, referenceDp);
        boolean zoom = WechatScalePolicy.shouldScaleDialog(mode, enabled, diagnostic);
        int originalWidth = originalWidths.get(dialog);
        // Floating DecorView otherwise measures WRAP_CONTENT from the virtual
        // resource configuration, which may be wider than the real task window.
        int desiredWidth = zoom && originalWidth == ViewGroup.LayoutParams.WRAP_CONTENT
                ? ViewGroup.LayoutParams.MATCH_PARENT : originalWidth;
        if (attributes.width != desiredWidth) window.setLayout(desiredWidth, attributes.height);
        View content = window.getDecorView().findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) content;
        if (frame == null || frame.getParent() != parent) {
            if (!zoom || parent.getChildCount() == 0) return;
            frame = parent.getChildCount() == 1 && parent.getChildAt(0) instanceof ZoomFrameLayout
                    ? (ZoomFrameLayout) parent.getChildAt(0) : null;
            if (frame == null) {
                frame = new ZoomFrameLayout(dialog.getContext());
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
                parent.addView(frame, new ViewGroup.LayoutParams(-1,
                        attributes.height == ViewGroup.LayoutParams.WRAP_CONTENT ? -2 : -1));
            }
            frames.put(dialog, new WeakReference<>(frame));
            if (logging) Log.i("PopupScale", "dialog-canvas class=" + dialog.getClass().getName()
                    + " owner=" + owner.getClass().getName() + " mode=" + mode
                    + " windowSize=" + attributes.width + "x" + attributes.height);
        }
        frame.configureDialog(zoom, canvasDpi, widthDp, owner.getWindow().getDecorView(),
                owner.getResources().getDisplayMetrics().widthPixels, logging);
    }

    void updateForActivity(Activity activity, CanvasController activities, boolean enabled,
            boolean diagnostic, int density, int referenceDp, boolean logging) {
        for (Dialog dialog : new ArrayList<>(frames.keySet())) {
            if (dialog != null && owner(dialog) == activity)
                update(dialog, activities, enabled, diagnostic, density, referenceDp, logging);
        }
    }

    private Activity owner(Dialog dialog) {
        Activity activity = dialog.getOwnerActivity();
        if (activity != null) return activity;
        Context context = dialog.getContext();
        for (int depth = 0; depth < 16; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }
}
