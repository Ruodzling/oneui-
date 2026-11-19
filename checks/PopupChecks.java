package io.github.s24u.popupscale;

public final class PopupChecks {
    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(WechatVoicePolicy.accepts("com.tencent.mm", true, -1, -1, 1094, 1805, 2.7375f), "portrait recorder full overlay");
        check(WechatVoicePolicy.accepts("com.tencent.mm", true, -1, -1, 834, 1209, 2.7375f), "landscape display upright recorder");
        check(WechatVoicePolicy.accepts("com.tencent.mm", true, 1805, 1034, 1805, 1094, 2.7375f), "wide recorder caption height tolerance");
        check(!WechatVoicePolicy.accepts("com.tencent.mm", false, -1, -1, 1094, 1805, 2.7375f), "unrelated full overlays unchanged");
        check(!WechatVoicePolicy.accepts("ordinary.app", true, -1, -1, 1094, 1805, 2.7375f), "other apps unchanged");
        check(!WechatVoicePolicy.accepts("com.tencent.mm", true, -2, -1, 1094, 1805, 2.7375f), "wrap popup unchanged");
        check(!WechatVoicePolicy.accepts("com.tencent.mm", true, 600, 300, 1094, 1805, 2.7375f), "finite menu unchanged");
        check(!WechatVoicePolicy.accepts("com.tencent.mm", true, -1, -1, 0, 1805, 2.7375f), "unmeasured host unchanged");
        for (int[] viewport : new int[][]{{1094,1745},{834,1149},{1805,1034}}) {
            var canvas = ScalePolicy.canvas(5, viewport[0], viewport[1], 438, 500);
            check(canvas != null && Math.abs(canvas.widthPx / (438f/160) - 500) < 1, "voice logical width matches chat");
            check(Math.abs(canvas.widthPx * canvas.scale - viewport[0]) <= 1
                && Math.abs(canvas.heightPx * canvas.scale - viewport[1]) <= 1, "voice canvas still fills original overlay");
            check(Math.abs(canvas.widthPx/(float)canvas.heightPx - viewport[0]/(float)viewport[1]) < .003f, "uniform voice scaling across display orientation");
        }
        float chromeScale = 1100f / 1875;
        int width = PopupGeometry.size(1026, chromeScale);
        int height = PopupGeometry.size(1758, chromeScale);
        check(width == 602 && height == 1031, "Chrome panel must shrink with its owner");
        int rightAligned = PopupGeometry.alignedStart(148, 1026, width, true);
        check(rightAligned + width == 148 + 1026, "Right menu edge moved away from anchor");
        check(PopupGeometry.alignedStart(35,1026,width,false)==35,"Left menu edge moved");
        for(float scale : new float[]{0.3f,0.44f,0.58666664f,0.8f,1f,1.17551f}) {
            for(int raw : new int[]{1,20,675,1026,1758}) {
                int value = PopupGeometry.size(raw, scale);
                check(value>0 && Math.abs(value-raw*scale)<=1,"Invalid popup rounding");
                int start = PopupGeometry.alignedStart(148, raw, value, true);
                check(start+value==148+raw,"Edge drift across size changes");
            }
        }
        check(PopupGeometry.size(1026,1f)==1026,"Fullscreen must restore raw width");
        System.out.println("PASS: finite popup size, edge alignment and fullscreen geometry.");
    }
}
