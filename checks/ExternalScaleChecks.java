package io.github.s24u.popupscale;

/** A native crash before Activity startup requires an external path with strict isolation. */
public final class ExternalScaleChecks {
    private static ExternalScalePolicy.Geometry get(String name, boolean enabled, boolean diagnostic,
            boolean selected, int user, int display, int type, int mode, int w, int h, int dpi, int dp) {
        return ExternalScalePolicy.calculate(name, enabled, diagnostic, selected, user, display, type, mode,w,h,dpi,dp);
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("External scale isolation/geometry"); }
    public static void main(String[] args) {
        check(AppCanvasPolicy.requiresConsistentDensity("com.sina.weibo"));
        check(AppCanvasPolicy.usesCanvas("com.sina.weibo", true));
        check(AppCanvasPolicy.rewriteActivityResources("com.sina.weibo", true));
        check(AppCanvasPolicy.ownsCanvas("com.sina.weibo",false,false));
        check(!AppCanvasPolicy.ownsCanvas("com.sina.weibo",true,false));
        check(!AppCanvasPolicy.ownsCanvas("com.sina.weibo",false,true));
        check(!AppCanvasPolicy.ownsCanvas("com.sina.weibo",true,true));
        check(AppCanvasPolicy.ownsCanvas("com.tencent.mm",true,true));
        check(AppCanvasPolicy.processDensity("com.sina.weibo",true,false,true,0,350,600)==600);
        check(AppCanvasPolicy.processDensity("com.sina.weibo",true,false,true,0,305,600)==600);
        check(AppCanvasPolicy.processDensity("com.sina.weibo",true,false,true,0,600,600)==0);
        check(AppCanvasPolicy.processDensity("com.tencent.mm",true,false,true,0,350,600)==0);
        check(AppCanvasPolicy.processDensity("com.sina.weibo",false,false,true,0,350,600)==0);
        check(AppCanvasPolicy.processDensity("com.sina.weibo",true,true,true,0,350,600)==0);
        check(AppCanvasPolicy.processDensity("com.sina.weibo",true,false,false,0,350,600)==0);
        check(AppCanvasPolicy.processDensity("com.sina.weibo",true,false,true,10,350,600)==0);
        check(AppCanvasPolicy.processDensity("com.sina.weibo",true,false,true,0,0,600)==0);
        check(AppCanvasPolicy.usesCanvas("com.tencent.mm", true));
        check(!AppCanvasPolicy.rewriteActivityResources("com.tencent.mm", true));
        for(String name:new String[]{null,"com.sina.weibo.fake","com.sina.weibolite","org.example.app","flar2.devcheck"}) {
            check(!AppCanvasPolicy.requiresConsistentDensity(name));
            check(!AppCanvasPolicy.usesCanvas(name,true));
            check(!AppCanvasPolicy.rewriteActivityResources(name,true));
            check(AppCanvasPolicy.rewriteActivityResources(name,false));
        }
        var weiboCanvas=ScalePolicy.canvas(5,1094,1805,600,500);
        check(weiboCanvas!=null && weiboCanvas.widthPx==1875);
        check(Math.abs(weiboCanvas.scale*weiboCanvas.widthPx-1094)<.01f);
        check(ScalePolicy.canvas(1,1440,3120,600,500)==null);
        check(WechatScalePolicy.usesCanvas("com.tencent.mm", true));
        check(!WechatScalePolicy.usesCanvas("com.ss.android.ugc.aweme", true));
        check(!WechatScalePolicy.usesCanvas("flar2.devcheck", true));
        check(WechatScalePolicy.usesCanvas("ordinary.app", false));
        check(WechatScalePolicy.portraitWidth(1440, 3120, 3.6f) == 400);
        check(WechatScalePolicy.portraitWidth(3120, 1440, 3.6f) == 400);
        check(WechatScalePolicy.portraitWidth(1440, 3120, 350/160f) == 0);
        check(WechatScalePolicy.portraitWidth(0, 3120, 3.6f) == 0);
        check(WechatScalePolicy.portraitWidth(1440, 3120, Float.NaN) == 0);
        var adapted = ScalePolicy.canvas(5,1094,1745,576,500);
        check(adapted != null && Math.abs(adapted.scale - 1094f/1800) < .002f);
        var honored = ScalePolicy.canvas(5,1094,1745,350,500);
        check(honored == null || Math.abs(honored.scale - 1f) < .002f);
        var full = ScalePolicy.canvas(5,1440,3060,576,400);
        check(full == null || Math.abs(full.scale - 1f) < .002f);
        var p=get("flar2.devcheck",true,false,true,0,0,1,5,1094,1745,600,500);
        check(p!=null && p.densityDpi==350 && p.widthDp==500 && p.heightDp==798);
        var s=get("flar2.devcheck",true,false,true,0,0,1,5,900,1425,600,500);
        check(s!=null && s.densityDpi==288 && s.widthDp==500 && s.heightDp==792);
        for(String name:new String[]{null,"other.app","flar2.devcheck.fake","com.android.systemui"})
            check(get(name,true,false,true,0,0,1,5,1094,1745,600,500)==null);
        check(get("flar2.devcheck",false,false,true,0,0,1,5,1094,1745,600,500)==null);
        check(get("flar2.devcheck",true,true,true,0,0,1,5,1094,1745,600,500)==null);
        check(get("flar2.devcheck",true,false,false,0,0,1,5,1094,1745,600,500)==null);
        check(get("flar2.devcheck",true,false,true,10,0,1,5,1094,1745,600,500)==null);
        check(get("flar2.devcheck",true,false,true,0,1,1,5,1094,1745,600,500)==null);
        for(int type:new int[]{0,2,3})check(get("flar2.devcheck",true,false,true,0,0,type,5,1094,1745,600,500)==null);
        for(int mode:new int[]{0,1,2,6})check(get("flar2.devcheck",true,false,true,0,0,1,mode,1094,1745,600,500)==null);
        for(int bad:new int[]{0,-1})check(get("flar2.devcheck",true,false,true,0,0,1,5,bad,1745,600,500)==null);
        check(get("flar2.devcheck",true,false,true,0,0,1,5,1094,1745,0,500)==null);
        check(get("flar2.devcheck",true,false,true,0,0,1,5,1094,1745,600,0)==null);
        var large=get("flar2.devcheck",true,false,true,0,0,1,5,2200,1745,600,500);
        check(large!=null&&large.densityDpi==704&&large.widthDp==500);
        var bounded=get("flar2.devcheck",true,false,true,0,0,1,5,200,300,600,500);
        check(bounded!=null && bounded.densityDpi==120);
        var landscape=get("flar2.devcheck",true,false,true,0,0,1,5,900,600,600,500);
        check(landscape!=null && landscape.widthDp==500 && landscape.heightDp==333);
        for(String name:new String[]{"org.example.reader","com.tencent.mm","com.coolapk.market","com.android.chrome"}) {
            check(!ExternalScalePolicy.usesExternal(name,false));
            check(ExternalScalePolicy.usesExternal(name,true));
            var unified=ExternalScalePolicy.calculate(name,true,true,false,true,0,0,1,5,1094,1745,600,500);
            check(unified!=null && unified.densityDpi==350 && unified.widthDp==500);
            check(ExternalScalePolicy.calculate(name,true,true,false,false,0,0,1,5,1094,1745,600,500)==null);
            check(ExternalScalePolicy.calculate(name,true,true,false,true,0,0,1,1,1094,1745,600,500)==null);
        }
        for(String core:new String[]{null,"android","com.android.systemui","com.sec.android.app.launcher",
                AppScopePolicy.PERMISSION_PACKAGE,"com.android.settings"})
            check(!ExternalScalePolicy.usesExternal(core,true));
        check(ExternalScalePolicy.usesExternal(AppScopePolicy.MODULE_PACKAGE,true));
        check(ExternalScalePolicy.calculate("org.example.reader",false,true,false,true,0,0,1,5,1094,1745,600,500)==null);
        System.out.println("PASS: external Activity density, fullscreen/split/opt-out/user/display isolation, native-density ceiling and minimum density floor.");
        System.out.println("PASS: unified system mode covers newly selected ordinary apps without an application scope; disabled mode preserves legacy app scaling and DevCheck fallback.");
        System.out.println("PASS: Weibo alone uses consistent-density Activity resources and a single fixed canvas; WeChat resources, other packages, fullscreen and system-wide scaling remain isolated.");
    }
}
