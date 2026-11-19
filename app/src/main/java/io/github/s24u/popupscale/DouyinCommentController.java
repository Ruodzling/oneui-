package io.github.s24u.popupscale;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.util.WeakHashMap;

/** Douyin 40.9: CommentNestedLayout is an inline content sibling, not a Dialog. */
final class DouyinCommentController {
    static final String LAYOUT = "com.ss.android.ugc.aweme.comment.widget.CommentNestedLayout";
    private final WeakHashMap<View, CommentPanelGeometry> panels = new WeakHashMap<>();
    private final WeakHashMap<View, Integer> reportedHeights = new WeakHashMap<>();

    static boolean isTarget(String name) {
        return "com.ss.android.ugc.aweme".equals(name) || "com.ss.android.ugc.aweme.mobile".equals(name);
    }

    void beforeMeasure(ViewGroup root, int heightSpec, boolean enabled, boolean logging) {
        if (!enabled || !LAYOUT.equals(root.getClass().getName()) || root.getChildCount() != 2) return;
        ZoomFrameLayout canvas = null;
        for (android.view.ViewParent p = root.getParent(); p != null; p = p.getParent()) {
            if (p instanceof ZoomFrameLayout) { canvas = (ZoomFrameLayout) p; break; }
        }
        if (canvas == null) return;
        int offered = View.MeasureSpec.getSize(heightSpec);
        float scale = canvas.measureScale();
        if (offered <= 0 || !Float.isFinite(scale) || scale <= 0) return;
        int physical = Math.max(1, Math.round(offered * scale));
        // The verified root has a weighted spacer and one content container. Find the
        // finite height on that container's single-child chain, never header/comment rows.
        View anchor = root.getChildAt(1);
        for (int depth = 0; depth < 4; depth++) {
            ViewGroup.LayoutParams lp = anchor.getLayoutParams();
            if (lp == null) return;
            if (lp.height > 0) {
                CommentPanelGeometry geometry = panels.get(anchor);
                if (geometry == null) { geometry = new CommentPanelGeometry(); panels.put(anchor, geometry); }
                int height = geometry.height(lp.height, physical, offered);
                // This runs inside onMeasure: assign directly; don't schedule another layout loop.
                lp.height = height;
                if (logging && (!reportedHeights.containsKey(anchor) || reportedHeights.get(anchor) != height)) {
                    reportedHeights.put(anchor, height);
                    Log.i("PopupScale", "douyin comment height physicalViewport=" + physical
                            + " virtualViewport=" + offered + " panelVirtual=" + height + " scale=" + scale);
                }
                return;
            }
            if (!(anchor instanceof ViewGroup) || ((ViewGroup) anchor).getChildCount() != 1) return;
            anchor = ((ViewGroup) anchor).getChildAt(0);
        }
    }
}
