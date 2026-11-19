package io.github.s24u.popupscale;

/** Finite popup bounds; preserve the aligned edge when its size changes. */
public final class PopupGeometry {
    private PopupGeometry() {}
    public static int size(int raw, float scale) {
        return Math.max(1, Math.round(raw * scale));
    }
    public static int alignedStart(int start, int rawSize, int size, boolean endAligned) {
        return endAligned ? start + rawSize - size : start;
    }
}
