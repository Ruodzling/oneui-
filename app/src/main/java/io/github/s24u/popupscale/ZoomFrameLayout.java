package io.github.s24u.popupscale;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;

/** A fixed canvas in a physical viewport; ViewGroup maps touch through the child matrix. */
public final class ZoomFrameLayout extends ViewGroup {
    private final FrameLayout canvas;
    private boolean zoom, logging, captionInsetEnabled, observingCaption;
    private int baseDensity = 600, referenceDp = ScalePolicy.DEFAULT_REFERENCE_DP;
    private int virtualWidth, virtualHeight, topOffset, statusBarPx = -1;
    private int lastCaptionTop = -1, lastNativeTop = -1;
    private int lastReportedWidth = -1, lastReportedTopOffset = -1;
    private float scale = 1f;
    private boolean dialogMode, observingHost;
    private WeakReference<View> dialogHost;
    private int fallbackHostWidth, lastReportedHeight = -1;
    private float lastReportedScale = -1;
    private final OnLayoutChangeListener hostLayout = (view, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft) requestLayout();
    };
    private final ViewTreeObserver.OnPreDrawListener captionPreDraw = () -> {
        checkCaptionInsetChange();
        return true;
    };

    public ZoomFrameLayout(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        canvas = new FrameLayout(context);
        addView(canvas, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
    public FrameLayout canvas() { return canvas; }
    public float contentScale() { return canvas.getScaleY(); }
    // Child onMeasure runs before onLayout applies the View matrix.
    public float measureScale() { return scale; }
    public int topOffset() { return topOffset; }
    public void setCaptionInsetEnabled(boolean enabled) {
        if (captionInsetEnabled != enabled) {
            captionInsetEnabled = enabled;
            updateCaptionObserver();
            requestLayout();
        }
    }
    public void configure(boolean enabled, int density, int widthDp, boolean log) {
        logging = log;
        if (zoom != enabled || baseDensity != density || referenceDp != widthDp) {
            zoom = enabled; baseDensity = density; referenceDp = widthDp;
            statusBarPx = -1;
            updateCaptionObserver();
            requestLayout();
        }
    }
    public void configureDialog(boolean enabled, int density, int widthDp, View host,
            int hostWidth, boolean log) {
        View previous = dialogHost == null ? null : dialogHost.get();
        if (!dialogMode || previous != host || fallbackHostWidth != hostWidth) {
            stopObservingHost();
            dialogMode = true;
            dialogHost = new WeakReference<>(host);
            fallbackHostWidth = hostWidth;
            if (isAttachedToWindow()) observeHost();
            requestLayout();
        }
        configure(enabled, density, widthDp, log);
    }
    private void observeHost() {
        View host = dialogHost == null ? null : dialogHost.get();
        if (!observingHost && host != null) {
            host.addOnLayoutChangeListener(hostLayout);
            observingHost = true;
        }
    }
    private void stopObservingHost() {
        View host = dialogHost == null ? null : dialogHost.get();
        if (observingHost && host != null) host.removeOnLayoutChangeListener(hostLayout);
        observingHost = false;
    }
    private void updateCaptionObserver() {
        boolean wanted = isAttachedToWindow() && !dialogMode && zoom && captionInsetEnabled;
        if (wanted == observingCaption) return;
        ViewTreeObserver tree = getViewTreeObserver();
        if (!tree.isAlive()) return;
        if (wanted) tree.addOnPreDrawListener(captionPreDraw);
        else tree.removeOnPreDrawListener(captionPreDraw);
        observingCaption = wanted;
    }
    private void checkCaptionInsetChange() {
        if (!zoom || !captionInsetEnabled || dialogMode) {
            if (topOffset != 0) requestLayout();
            return;
        }
        int captionTop = readCaptionTop(), nativeTop = readNativeTop();
        if (captionTop != lastCaptionTop || nativeTop != lastNativeTop) {
            lastCaptionTop = captionTop;
            lastNativeTop = nativeTop;
            requestLayout();
        }
    }
    private int readCaptionTop() {
        if (Build.VERSION.SDK_INT < 30) return 0;
        WindowInsets insets = getRootWindowInsets();
        return insets == null ? 0 : insets.getInsets(WindowInsets.Type.captionBar()).top;
    }
    private int readNativeTop() {
        int top = 0;
        for (ViewParent p = this; p instanceof View; p = p.getParent()) {
            View v = (View) p;
            if (!(v.getParent() instanceof View)) break;
            top += v.getTop() + Math.round(v.getTranslationY());
        }
        return top;
    }
    private int readStatusBarPx() {
        if (statusBarPx < 0) {
            int id = canvas.getResources().getIdentifier("status_bar_height", "dimen", "android");
            statusBarPx = id > 0 ? canvas.getResources().getDimensionPixelSize(id) : 0;
        }
        return statusBarPx;
    }
    @Override public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
        WindowInsets result = super.dispatchApplyWindowInsets(insets);
        checkCaptionInsetChange();
        return result;
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (dialogMode) observeHost();
        updateCaptionObserver();
    }
    @Override protected void onDetachedFromWindow() {
        stopObservingHost();
        if (observingCaption) {
            ViewTreeObserver tree = getViewTreeObserver();
            if (tree.isAlive()) tree.removeOnPreDrawListener(captionPreDraw);
            observingCaption = false;
        }
        super.onDetachedFromWindow();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        if (dialogMode) {
            topOffset = 0;
            View host = dialogHost == null ? null : dialogHost.get();
            int hostWidth = host != null && host.getWidth() > 0 ? host.getWidth() : fallbackHostWidth;
            ScalePolicy.Canvas geometry = zoom ? ScalePolicy.dialog(hostWidth, width,
                    Math.max(1, height), baseDensity, referenceDp) : null;
            scale = geometry == null ? 1f : geometry.scale;
            virtualWidth = geometry == null ? width : geometry.widthPx;
            int mode = MeasureSpec.getMode(heightSpec);
            int virtualLimit = Math.max(0, (int) Math.ceil(height / (double) scale));
            canvas.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(virtualLimit, mode));
            virtualHeight = canvas.getMeasuredHeight();
            int desiredHeight = (int) Math.ceil(virtualHeight * (double) scale);
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(desiredHeight, heightSpec));
            return;
        }
        ScalePolicy.Canvas base = zoom
                ? ScalePolicy.canvas(5, width, height, baseDensity, referenceDp) : null;
        scale = base == null ? 1f : base.scale;
        if (zoom && captionInsetEnabled) {
            lastCaptionTop = readCaptionTop();
            lastNativeTop = readNativeTop();
            topOffset = ScalePolicy.captionOffset(true, 5, height, baseDensity, scale,
                    lastCaptionTop, lastNativeTop, readStatusBarPx());
        } else {
            topOffset = 0;
        }
        ScalePolicy.Canvas geometry = zoom
                ? ScalePolicy.canvas(5, width, Math.max(1, height - topOffset), baseDensity, referenceDp) : null;
        virtualWidth = geometry == null ? width : geometry.widthPx;
        virtualHeight = geometry == null ? height : geometry.heightPx;
        scale = geometry == null ? 1f : geometry.scale;
        canvas.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        canvas.layout(0, topOffset, virtualWidth, topOffset + virtualHeight);
        canvas.setPivotX(0); canvas.setPivotY(0);
        canvas.setScaleX(scale); canvas.setScaleY(scale);
        if (logging && zoom && (getWidth() != lastReportedWidth
                || getHeight() != lastReportedHeight || scale != lastReportedScale
                || topOffset != lastReportedTopOffset)) {
            lastReportedWidth = getWidth();
            lastReportedHeight = getHeight();
            lastReportedScale = scale;
            lastReportedTopOffset = topOffset;
            Log.i("PopupScale", "canvas viewport=" + getWidth() + "x" + getHeight()
                    + " virtual=" + virtualWidth + "x" + virtualHeight + " scale=" + scale
                    + (topOffset > 0 ? " topOffset=" + topOffset : "")
                    + (dialogMode ? " kind=dialog" : " kind=activity"));
        }
    }
    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
