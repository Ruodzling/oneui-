package io.github.s24u.popupscale;

public final class DouyinShareChecks {
    public static void main(String[] args) {
        String dialog = "com.ss.android.ugc.aweme.sharer.panelmodel.base.DialogPanel";
        check(DouyinShareGeometry.target("com.ss.android.ugc.aweme", dialog));
        check(DouyinShareGeometry.target("com.ss.android.ugc.aweme.mobile", dialog));
        check(!DouyinShareGeometry.target("flar2.devcheck", dialog));
        check(!DouyinShareGeometry.target("com.ss.android.ugc.aweme", "android.app.Dialog"));
        // Actual report: requested2991 while the freeform frame is only1805px high.
        check(height(2991,1805,true,5,0)==1805);
        check(height(2991,1326,true,5,0)==1326);
        check(height(2991,2050,true,5,0)==2050);
        check(height(800,1805,true,5,0)==800);
        check(height(-1,1805,true,5,0)==-1);
        check(height(-2,1805,true,5,0)==-2);
        check(height(2991,0,true,5,0)==2991);
        check(height(2991,1805,false,5,0)==2991);
        check(height(2991,1805,true,1,0)==2991);
        check(height(2991,1805,true,6,0)==2991);
        check(height(2991,1805,true,5,1)==2991);
        System.out.println("PASS: Douyin share DialogPanel fits actual freeform task height; smaller/wrap/fullscreen/split/disabled/other-display windows remain native.");
    }
    private static int height(int request,int available,boolean enabled,int mode,int display) {
        return DouyinShareGeometry.height(request,available,enabled,mode,display);
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
