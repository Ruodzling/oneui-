package io.github.s24u.popupscale;

public final class PolicyChecks {
    private static int checks;
    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        ScalePolicy.Canvas large = ScalePolicy.canvas(5,1274,2319,600,480);
        ScalePolicy.Canvas small = ScalePolicy.canvas(5,825,1502,600,480);
        check(large.widthPx == 1800 && small.widthPx == 1800, "Logical width must remain fixed");
        check(Math.abs(large.heightPx-small.heightPx)<=3, "Equal aspect ratio keeps canvas height");
        check(Math.abs(large.widthPx*large.scale-1274)<0.01, "Large canvas fills viewport");
        check(Math.abs(small.widthPx*small.scale-825)<0.01, "Small canvas fills viewport");
        check(Math.abs(small.scale/large.scale-825.0/1274)<0.0001,
                "Existing controls scale proportionally");
        for(int mode : new int[]{0,1,2,3,4,6})
            check(ScalePolicy.canvas(mode,825,1502,600,480)==null,"Other window modes stay unscaled");
        check(ScalePolicy.canvas(5,0,1502,600,480)==null,"Empty viewport");
        check(ScalePolicy.canvas(5,825,-1,600,480)==null,"Invalid height");
        check(ScalePolicy.canvas(5,825,1502,0,480)==null,"Undefined base density");
        check(ScalePolicy.canvas(5,825,1502,600,9999)==null,"Corrupt reference width");
        ScalePolicy.Canvas wide = ScalePolicy.canvas(5,825,825,600,480);
        check(wide.widthPx == small.widthPx && wide.heightPx != small.heightPx,
                "Changing aspect ratio legitimately changes visible height");
        ScalePolicy.Canvas coldWechat = ScalePolicy.canvas(5,825,1415,330,500);
        ScalePolicy.Canvas normalWechat = ScalePolicy.canvas(5,825,1415,576,500);
        check(Math.abs(coldWechat.scale*330-normalWechat.scale*576)<0.1,
                "Different startup densities preserve the same displayed dp size");
        ScalePolicy.Canvas restoredWechat = ScalePolicy.canvas(5,1440,2936,330,400);
        check(Math.abs(restoredWechat.scale*330-576)<0.1,
                "Portrait restore compensates a retained cold-start density");
        ScalePolicy.Canvas dialogLarge = ScalePolicy.dialog(1180,1152,2016,576,500);
        ScalePolicy.Canvas dialogSmall = ScalePolicy.dialog(825,797,1355,576,500);
        check(Math.abs(dialogLarge.scale-1180f/1800)<0.0001,
                "Dialog text uses owner scale rather than its narrower window width");
        check(Math.abs(dialogLarge.widthPx*dialogLarge.scale-1152)<1,
                "Virtual dialog width covers its physical content viewport");
        check(Math.abs(dialogSmall.scale/dialogLarge.scale-825f/1180)<0.0001,
                "Dialog content follows owner resize proportionally");
        check(500*dialogLarge.scale<2016,
                "Short wrap-content dialog must not expand to available screen height");
        check(ScalePolicy.dialog(0,797,1355,576,500)==null,"Missing owner viewport");
        check(ScalePolicy.dialog(825,0,1355,576,500)==null,"Empty dialog viewport");
        check(ScalePolicy.dialog(825,797,0,576,500)==null,"Empty dialog height limit");
        check(ScalePolicy.inputBottomOnScreen(0,3064,3120,1375)==1745,
                "Fullscreen input boundary meets the physical keyboard edge");
        check(ScalePolicy.inputBottomOnScreen(129,1616,2411,795)==1745,
                "Offset popup and fullscreen resolve to the same keyboard edge");
        check(ScalePolicy.inputBottomOnScreen(129,1415,1415,0)==1544,
                "A window above the keyboard stays within its own lower edge");
        check(ScalePolicy.captionOffset(true,5,1805,600,0.58346665f,150,0,130)==74,
                "Solid freeform header bar (150px) offsets QQ edge-to-edge canvas by unconsumed deficit (74px)");
        check(ScalePolicy.captionOffset(true,5,1209,600,0.4448f,150,0,130)==92,
                "Smaller popup window compensates larger scaled status-bar shrinkage");
        check(ScalePolicy.captionOffset(true,5,1805,600,0.58346665f,60,0,130)==0,
                "Compact 16dp pill handle (60px) retains zero offset");
        check(ScalePolicy.captionOffset(true,5,1805,600,0.58346665f,0,0,130)==0,
                "Absent caption bar retains zero offset");
        check(ScalePolicy.captionOffset(true,5,1805,600,0.58346665f,150,150,130)==0,
                "Decor-consumed caption bar is never double-offset");
        check(ScalePolicy.captionOffset(false,5,1805,600,0.58346665f,150,0,130)==0,
                "Disabled caption offset retains native placement");
        check(ScalePolicy.captionOffset(true,1,3120,600,1f,150,0,130)==0,
                "Fullscreen mode never applies freeform caption offset");
        check(ScalePolicy.captionOffset(true,5,900,600,0.5f,2000,0,130)==300,
                "Malformed caption inset is capped at one third of viewport height");
        for(int density : new int[]{320,330,450,454,576,600}) {
            int expectedWidth = 480*density/160;
            for(int width=350;width<=1300;width++) {
                ScalePolicy.Canvas value=ScalePolicy.canvas(5,width,width*2,density,480);
                check(value.widthPx==expectedWidth,"Stable canvas width for each baseline density");
                check(Math.abs(value.widthPx*value.scale-width)<0.01,"Physical width preservation");
                check(Math.abs(value.heightPx*value.scale-width*2)<1,"Height rounding remains subpixel");
            }
        }
        System.out.println("PASS: " + checks + " canvas acceptance checks.");
    }
}
