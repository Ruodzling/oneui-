package io.github.s24u.popupscale;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Standard finite PopupWindow panels, with their original public content view retained. */
final class PopupCanvasController {
    private final WeakHashMap<Activity, Boolean> activities = new WeakHashMap<>();
    private final WeakHashMap<PopupWindow, State> states = new WeakHashMap<>();
    private final WechatVoicePopupController voiceController = new WechatVoicePopupController();

    WindowManager.LayoutParams prepare(PopupWindow popup, WindowManager.LayoutParams source,
            CanvasController canvases, boolean enabled, int density, int referenceDp,
            boolean logging, boolean initial) {
        if (source.type != WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                && source.type != WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL) return source;
        Object value = XposedHelpers.getObjectField(popup, "mDecorView");
        if (!(value instanceof FrameLayout)) return source;
        FrameLayout decor = (FrameLayout) value;
        State previous = states.get(popup);
        State state = initial ? null : previous;
        Activity owner = owner(popup, source);
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) return source;
        WindowManager.LayoutParams voice = voiceController.prepare(popup, source, owner, decor,
                canvases, enabled, referenceDp, logging);
        if (voice != null) return voice;
        int mode = canvases.windowMode(owner);
        boolean zoom = enabled && canvases.canvasMode(owner, mode);
        View host = owner.getWindow().getDecorView();
        int hostWidth = host.getWidth();
        int dpi = canvases.canvasDensity(owner, density);
        int widthDp = canvases.canvasReferenceDp(owner, mode, referenceDp);
        ScalePolicy.Canvas geometry = zoom
                ? ScalePolicy.canvas(5, hostWidth, 1, dpi, widthDp) : null;
        float scale = geometry == null ? 1f : geometry.scale;
        int rawWidth = source.width, rawHeight = source.height, rawX = source.x, rawY = source.y;
        if (state != null) {
            if (rawWidth == state.appliedWidth) rawWidth = state.width;
            if (rawHeight == state.appliedHeight) rawHeight = state.height;
            if (rawX == state.appliedX) rawX = state.x;
            if (rawY == state.appliedY) rawY = state.y;
        }
        // WRAP_CONTENT, full-window overlays and system UI popups need separate policies.
        if (rawWidth <= 0 || rawHeight <= 0) {
            PopupZoomLayout old = state == null ? null : state.frame.get();
            if (old != null && old.getParent() == decor) {
                decor.removeView(old);
                while (old.canvas.getChildCount() > 0) {
                    View child = old.canvas.getChildAt(0);
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    old.canvas.removeViewAt(0);
                    decor.addView(child, params);
                }
                states.remove(popup);
            }
            return source;
        }
        if (!zoom && state == null) return source;
        PopupZoomLayout frame = state == null ? null : state.frame.get();
        WindowManager.LayoutParams result = new WindowManager.LayoutParams();
        result.copyFrom(source);
        result.width = PopupGeometry.size(rawWidth, scale);
        result.height = PopupGeometry.size(rawHeight, scale);
        int horizontal = Gravity.getAbsoluteGravity(source.gravity, host.getLayoutDirection())
                & Gravity.HORIZONTAL_GRAVITY_MASK;
        boolean leftCoordinates = horizontal == Gravity.LEFT || horizontal == Gravity.NO_GRAVITY;
        View anchor = weakView(popup, "mAnchor");
        boolean dropdown = XposedHelpers.getBooleanField(popup, "mIsDropdown");
        boolean endAligned = false;
        if (leftCoordinates) {
            if (dropdown && anchor != null) {
                int anchoredGravity = XposedHelpers.getIntField(popup, "mAnchoredGravity");
                endAligned = (Gravity.getAbsoluteGravity(anchoredGravity, host.getLayoutDirection())
                        & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT;
            } else {
                // Absolute menus placed at the far right are clamped there by WindowManager.
                endAligned = rawX > 0 && rawX + rawWidth >= hostWidth * 0.9f;
            }
        }
        result.x = PopupGeometry.alignedStart(rawX, rawWidth, result.width, endAligned);
        result.y = rawY;
        if (dropdown && anchor != null) {
            if (popup.isAboveAnchor()) result.y += rawHeight - result.height;
            else {
                Rect visible = new Rect();
                if (anchor.getGlobalVisibleRect(visible))
                    result.y += visible.height() - anchor.getHeight();
            }
        }
        // Validate/read firmware fields above before changing the original hierarchy.
        if (frame == null || frame.getParent() != decor) {
            if (!zoom || decor.getChildCount() == 0) return source;
            frame = new PopupZoomLayout(decor.getContext());
            while (decor.getChildCount() > 0) {
                View child = decor.getChildAt(0);
                ViewGroup.LayoutParams old = child.getLayoutParams();
                decor.removeViewAt(0);
                FrameLayout.LayoutParams params = old instanceof FrameLayout.LayoutParams
                        ? new FrameLayout.LayoutParams((FrameLayout.LayoutParams) old)
                        : new FrameLayout.LayoutParams(old);
                frame.canvas.addView(child, params);
            }
            decor.addView(frame, new FrameLayout.LayoutParams(-1, -1));
            state = new State(owner, frame);
            states.put(popup, state);
        }
        frame.configure(rawWidth, rawHeight, scale);
        state.width = rawWidth; state.height = rawHeight; state.x = rawX; state.y = rawY;
        state.appliedWidth = result.width; state.appliedHeight = result.height;
        state.appliedX = result.x; state.appliedY = result.y;
        if (logging) Log.i("PopupScale", "popup-canvas owner=" + owner.getClass().getName()
                + " mode=" + mode + " raw=" + rawWidth + "x" + rawHeight
                + " physical=" + result.width + "x" + result.height + " scale=" + scale
                + " xy=" + result.x + "," + result.y + " gravity=" + source.gravity);
        return result;
    }

    void updateForActivity(Activity owner) {
        activities.put(owner, true);
        voiceController.updateForActivity(owner);
        for (PopupWindow popup : new ArrayList<>(states.keySet())) {
            State state = states.get(popup);
            if (state != null && state.owner.get() == owner && popup.isShowing())
                popup.update(state.x, state.y, state.width, state.height, true);
        }
    }
    void forget(Activity owner) {
        activities.remove(owner);
        voiceController.forget(owner);
        states.entrySet().removeIf(entry -> entry.getValue().owner.get() == owner);
    }
    private Activity owner(PopupWindow popup, WindowManager.LayoutParams params) {
        View content = popup.getContentView();
        Context context = content == null ? null : content.getContext();
        for (int depth = 0; depth < 16 && context != null; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        for (Activity candidate : new ArrayList<>(activities.keySet())) {
            if (candidate != null && candidate.getWindow() != null
                    && params.token != null
                    && params.token.equals(candidate.getWindow().getDecorView().getWindowToken()))
                return candidate;
        }
        return null;
    }
    private View weakView(PopupWindow popup, String field) {
        Object value = XposedHelpers.getObjectField(popup, field);
        if (!(value instanceof WeakReference<?>)) return null;
        Object view = ((WeakReference<?>) value).get();
        return view instanceof View ? (View) view : null;
    }
    private static final class State {
        final WeakReference<Activity> owner;
        final WeakReference<PopupZoomLayout> frame;
        int width, height, x, y, appliedWidth, appliedHeight, appliedX, appliedY;
        State(Activity activity, PopupZoomLayout view) {
            owner = new WeakReference<>(activity); frame = new WeakReference<>(view);
        }
    }
}
