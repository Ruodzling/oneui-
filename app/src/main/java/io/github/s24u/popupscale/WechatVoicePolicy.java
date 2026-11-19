package io.github.s24u.popupscale;

/** The recorder is a full-window overlay, not a finite menu whose Window should shrink. */
public final class WechatVoicePolicy {
  private WechatVoicePolicy() {}

  public static boolean accepts(
      String ownerPackage,
      boolean voiceLayout,
      int rawWidth,
      int rawHeight,
      int hostWidth,
      int hostHeight,
      float density) {
    if (!"com.tencent.mm".equals(ownerPackage)
        || !voiceLayout
        || hostWidth <= 0
        || hostHeight <= 0
        || !Float.isFinite(density)
        || density <= 0) return false;
    boolean width = rawWidth == -1 || (rawWidth > 0 && Math.abs((long) rawWidth - hostWidth) <= 2);
    boolean height =
        rawHeight == -1
            || (rawHeight > 0
                && Math.abs((long) rawHeight - hostHeight) <= Math.round(120 * density));
    return width && height;
  }
}
