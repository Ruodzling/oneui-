package io.github.s24u.popupscale;

/** A cached physical panel height must retain its share of a resized virtual viewport. */
public final class CommentPanelGeometry {
    private int nativeRequest, applied;
    private float fraction;

    public int height(int requested, int physicalViewport, int virtualViewport) {
        if (requested <= 0 || physicalViewport <= 0 || virtualViewport <= 0) return requested;
        if (fraction == 0 || (requested != applied && requested != nativeRequest)) {
            nativeRequest = requested;
            // Some callers already use the virtual parent height. Do not scale it twice.
            int basis = requested > physicalViewport && virtualViewport > physicalViewport
                    ? virtualViewport : physicalViewport;
            fraction = Math.min(1f, requested / (float) basis);
        }
        applied = Math.max(1, Math.min(virtualViewport, Math.round(virtualViewport * fraction)));
        return applied;
    }
}
