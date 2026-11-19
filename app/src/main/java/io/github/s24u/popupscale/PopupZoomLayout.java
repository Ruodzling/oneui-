package io.github.s24u.popupscale;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** A finite popup canvas; child matrices map normal pointer events back to raw coordinates. */
final class PopupZoomLayout extends ViewGroup {
    final FrameLayout canvas;
    private int rawWidth, rawHeight;
    private float scale = 1f;
    PopupZoomLayout(Context context) {
        super(context);
        canvas = new FrameLayout(context);
        addView(canvas, new LayoutParams(-1, -1));
        setClipChildren(true);
    }
    void configure(int width, int height, float value) {
        if (rawWidth != width || rawHeight != height || scale != value) {
            rawWidth = width; rawHeight = height; scale = value;
            requestLayout();
        }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        canvas.measure(MeasureSpec.makeMeasureSpec(rawWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rawHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(resolveSize(PopupGeometry.size(rawWidth, scale), widthSpec),
                resolveSize(PopupGeometry.size(rawHeight, scale), heightSpec));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        canvas.layout(0, 0, rawWidth, rawHeight);
        canvas.setPivotX(0); canvas.setPivotY(0);
        canvas.setScaleX(scale); canvas.setScaleY(scale);
    }
}
