package io.github.s24u.popupscale;

/** Validate the separate status spacer and navigation row used by Douyin's main tabs. */
public final class DouyinHeaderGeometry {
  private DouyinHeaderGeometry() {}

  public static int gap(
      int spacer, int titleTop, int row, int host, int width, int hostWidth, float density) {
    if (!Float.isFinite(density)
        || density <= 0
        || width <= 0
        || hostWidth <= 0
        || width < hostWidth * .9f
        || spacer < 20 * density
        || spacer > 96 * density
        || row < 28 * density
        || row > 96 * density
        || Math.abs((long) titleTop - spacer) > 2
        || Math.abs((long) host - spacer - row) > 2) return 0;
    return spacer;
  }
}
