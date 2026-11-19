package io.github.s24u.popupscale;

/** Remove only an oversized empty prefix; retain a complete navigation row and its padding. */
public final class CommerceHeaderGeometry {
  private CommerceHeaderGeometry() {}

  public static int trim(int firstControlTop, int headerHeight, int contentHeight, float density) {
    if (!Float.isFinite(density)
        || density <= 0
        || contentHeight <= 0
        || headerHeight <= 0
        || headerHeight > contentHeight / 2
        || firstControlTop < 32 * density) return 0;
    int amount = firstControlTop - Math.round(4 * density);
    int row = Math.round(48 * density);
    if (amount <= 0 || amount > 160 * density || headerHeight - amount < row) return 0;
    return amount;
  }
}
