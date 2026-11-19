package io.github.s24u.popupscale;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Align the chat root with Samsung's caption space already excluded from its height. */
final class ChatWindowInsets {
    private final WeakHashMap<Activity, Entry> entries = new WeakHashMap<>();

    void update(Activity activity, ViewGroup host, ZoomFrameLayout canvas,
            boolean enabled, int mode, boolean logging) {
        if (Build.VERSION.SDK_INT < 30) return;
        boolean standalone = "com.tencent.mm.ui.chatting.ChattingUI".equals(activity.getClass().getName());
        boolean inline = "com.tencent.mm.ui.LauncherUI".equals(activity.getClass().getName());
        if (!standalone && !inline) return;
        View target;
        if (standalone) {
            if (!(host.getParent() instanceof LinearLayout)) return;
            target = (View) host.getParent();
            if (!(target.getParent() instanceof View)
                    || !target.getParent().getClass().getName().endsWith(".SwipeBackLayout")) return;
        } else target = host;
        Entry entry = entries.get(activity);
        if (entry == null || entry.target.get() != target) {
            if (entry != null) entry.close();
            entry = new Entry(activity.getWindow().getDecorView(), target, host);
            entries.put(activity, entry);
        }
        entry.active = enabled && mode == 5 && standalone;
        entry.keyboardActive = enabled && (mode == 1 || mode == 5);
        entry.canvas = new WeakReference<>(canvas);
        entry.logging = logging;
        entry.apply();
    }

    void forget(Activity activity) {
        Entry entry = entries.remove(activity);
        if (entry != null) entry.close();
    }

    @android.annotation.TargetApi(30)
    private static final class Entry implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> decor, target, host;
        private WeakReference<ViewTreeObserver> observer;
        private final int[] decorPosition = new int[2], hostPosition = new int[2];
        private float baseline, applied;
        private boolean active, logging;
        private boolean keyboardActive;
        private WeakReference<ZoomFrameLayout> canvas;
        private WeakReference<View> footer, footerBottom;
        private Movement keyboardMovement;
        private final Rect contentBounds = new Rect();
        private final int[] footerPosition = new int[2];
        private int reportedImeTarget = -1;
        private int reportedImeBottom = -1;
        private float reportedImeScale = -1;
        private boolean failureLogged;
        private int reportedCaption = -1, reportedShift = -1;

        Entry(View decor, View target, View host) {
            this.decor = new WeakReference<>(decor);
            this.target = new WeakReference<>(target);
            this.host = new WeakReference<>(host);
            baseline = target.getTranslationY();
            decor.addOnAttachStateChangeListener(this);
            if (decor.isAttachedToWindow()) observe(decor);
        }

        private void observe(View view) {
            stopObserving();
            ViewTreeObserver tree = view.getViewTreeObserver();
            tree.addOnPreDrawListener(this);
            observer = new WeakReference<>(tree);
        }

        private void stopObserving() {
            ViewTreeObserver tree = observer == null ? null : observer.get();
            if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
            observer = null;
        }

        @Override public void onViewAttachedToWindow(View view) { observe(view); }
        @Override public void onViewDetachedFromWindow(View view) { stopObserving(); }
        @Override public boolean onPreDraw() {
            try { apply(); }
            catch (Throwable error) {
                if (!failureLogged) {
                    failureLogged = true;
                    Log.w("PopupScale", "chat-caption update failed", error);
                }
            }
            return true;
        }

        void apply() {
            View root = decor.get(), shifted = target.get(), content = host.get();
            if (root == null || shifted == null || content == null || root.getHeight() == 0) return;
            if (Math.abs(shifted.getTranslationY() - baseline - applied) > 0.5f) {
                // Respect a new absolute translation supplied by the host app.
                baseline = shifted.getTranslationY();
                applied = 0;
            }
            root.getLocationOnScreen(decorPosition);
            content.getLocationOnScreen(hostPosition);
            int nativeTop = Math.round(hostPosition[1] - decorPosition[1] - applied);
            int caption = 0;
            WindowInsets insets = root.getRootWindowInsets();
            if (active && insets != null) {
                caption = insets.getInsets(WindowInsets.Type.captionBar()).top;
                if (caption == 0)
                    caption = insets.getInsetsIgnoringVisibility(WindowInsets.Type.captionBar()).top;
            }
            if (active && caption == 0) {
                // Some Samsung paths consume caption insets before dispatching
                // them. The outer chat layout still reserves that exact height.
                int bottom = insets == null ? 0
                        : insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                int gap = root.getHeight() - shifted.getHeight() - bottom;
                int limit = Math.round(64 * root.getResources().getDisplayMetrics().density);
                if (gap > limit && insets != null) {
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    gap -= ime.bottom;
                }
                if (gap > 0 && gap <= limit) caption = gap;
            }
            int correction = active ? Math.max(0, caption - nativeTop) : 0;
            if (Math.abs(applied - correction) > 0.1f) {
                applied = correction;
                shifted.setTranslationY(baseline + applied);
            }
            if (logging && (reportedCaption != caption || reportedShift != correction)) {
                reportedCaption = caption;
                reportedShift = correction;
                Log.i("PopupScale", "chat-caption caption=" + caption + " nativeTop="
                        + nativeTop + " shift=" + correction + " active=" + active);
            }
            alignKeyboard(root, content, insets);
        }

        private void alignKeyboard(View root, View content, WindowInsets insets) {
            ZoomFrameLayout frame = canvas == null ? null : canvas.get();
            float scale = frame == null ? 1f : frame.contentScale();
            int imeBottom = insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom;
            if (logging && (reportedImeBottom != imeBottom || reportedImeScale != scale)) {
                reportedImeBottom = imeBottom;
                reportedImeScale = scale;
                Log.i("PopupScale", "chat-ime-state bottom=" + imeBottom + " scale=" + scale
                        + " enabled=" + keyboardActive);
            }
            if (!keyboardActive || insets == null || !insets.isVisible(WindowInsets.Type.ime())
                    || Math.abs(scale - 1f) < 0.001f || scale <= 0) {
                resetKeyboard();
                return;
            }
            View input = footer == null ? null : footer.get();
            if (input == null || input.getParent() == null || !input.isAttachedToWindow() || !input.isShown()) {
                resetKeyboard();
                input = find(frame, ".ChatFooter");
                footer = new WeakReference<>(input);
                footerBottom = null;
            }
            if (input == null || input.findFocus() == null || !input.isShown()) {
                resetKeyboard();
                return;
            }
            View boundary = footerBottom == null ? null : footerBottom.get();
            if (boundary == null || boundary.getParent() == null || !boundary.isAttachedToWindow()) {
                boundary = find(input, ".ChatFooterBottom");
                footerBottom = new WeakReference<>(boundary);
            }
            if (boundary == null || boundary.getHeight() == 0 || imeBottom <= 0
                    || !content.getGlobalVisibleRect(contentBounds)) return;
            // getGlobalVisibleRect uses root/window coordinates; the keyboard
            // edge and getLocationOnScreen use physical screen coordinates.
            int target = ScalePolicy.inputBottomOnScreen(decorPosition[1],
                    contentBounds.bottom, root.getHeight(), imeBottom);
            boundary.getLocationOnScreen(footerPosition);
            int delta = target - footerPosition[1];
            if (Math.abs(delta) <= 1) return;
            if (Math.abs(delta) > imeBottom + 100) return;
            if (keyboardMovement == null || keyboardMovement.view.get() == null
                    || !keyboardMovement.view.get().isAttachedToWindow()) {
                resetKeyboard();
                keyboardMovement = movement(input, frame, imeBottom, scale);
            }
            View moved = keyboardMovement.view.get();
            if (moved == null) return;
            float before = keyboardMovement.read(moved);
            keyboardMovement.move(moved, delta / scale);
            boundary.getLocationOnScreen(footerPosition);
            if (logging && reportedImeTarget != target) {
                reportedImeTarget = target;
                Log.i("PopupScale", "chat-ime target=" + target + " boundary=" + footerPosition[1]
                        + " delta=" + delta + " scale=" + scale + " layer="
                        + moved.getClass().getName() + " property="
                        + (keyboardMovement.scroll ? "scrollY" : "translationY")
                        + " value=" + before + "->" + keyboardMovement.read(moved));
            }
        }

        private Movement movement(View input, View stop, int imeBottom, float scale) {
            View translated = null;
            for (View item = input; item != null && item != stop;) {
                String name = item.getClass().getName();
                if ((name.endsWith(".ChattingScrollLayout") || name.endsWith(".ChattingUILayout"))
                        && item.getScrollY() != 0) return new Movement(item, true);
                if (translated == null && item.getTranslationY() < -Math.min(100, imeBottom / 4f) / scale)
                    translated = item;
                item = item.getParent() instanceof View ? (View) item.getParent() : null;
            }
            return new Movement(translated == null ? input : translated, false);
        }

        private View find(View view, String suffix) {
            if (view == null || view.getVisibility() != View.VISIBLE) return null;
            if (view.getClass().getName().endsWith(suffix)) return view;
            // No message text is inspected, and message-list subtrees are irrelevant here.
            if (view.getClass().getName().endsWith(".ChattingContent")) return null;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View found = find(group.getChildAt(index), suffix);
                    if (found != null) return found;
                }
            }
            return null;
        }

        private void resetKeyboard() {
            if (keyboardMovement != null) keyboardMovement.restore();
            keyboardMovement = null;
            reportedImeTarget = -1;
        }

        private static final class Movement {
            final WeakReference<View> view;
            final boolean scroll;
            float baseline, lastSet;
            Movement(View view, boolean scroll) {
                this.view = new WeakReference<>(view);
                this.scroll = scroll;
                baseline = lastSet = read(view);
            }
            float read(View view) { return scroll ? view.getScrollY() : view.getTranslationY(); }
            void write(View view, float value) {
                if (scroll) view.setScrollY(Math.round(value)); else view.setTranslationY(value);
            }
            void move(View view, float delta) {
                float current = read(view);
                if (Math.abs(current - lastSet) > 0.5f) baseline = current;
                write(view, scroll ? current - delta : current + delta);
                lastSet = read(view);
            }
            void restore() {
                View current = view.get();
                if (current != null && Math.abs(read(current) - lastSet) <= 0.5f) write(current, baseline);
            }
        }

        void close() {
            resetKeyboard();
            stopObserving();
            View root = decor.get(), shifted = target.get();
            if (root != null) root.removeOnAttachStateChangeListener(this);
            if (shifted != null && Math.abs(shifted.getTranslationY() - baseline - applied) < 0.5f)
                shifted.setTranslationY(baseline);
        }
    }
}
