package io.github.s24u.popupscale

/** Verified against Bilibili 9.17.0. Unknown callers retain the platform result. */
object PlayerCompatPolicy {
    const val PACKAGE="tv.danmaku.bili"
    private const val FULLSCREEN_WIDGET="com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullscreenWidget"
    private const val FULLSCREEN_HELPER="jv0.d"
    private const val HARDWARE_SERVICE="com.bilibili.playerbizcommon.features.hardware.HardwareService"
    @JvmStatic fun playerCallerTypes()=arrayOf(FULLSCREEN_WIDGET,FULLSCREEN_HELPER,HARDWARE_SERVICE)
    @JvmStatic fun eligible(pkg:String,enabled:Boolean,diagnostic:Boolean,selected:Boolean,mode:Int)=
        pkg==PACKAGE&&enabled&&!diagnostic&&selected&&mode==5

    @JvmStatic fun playerCaller(type:String,method:String):Boolean = when(type){
        FULLSCREEN_WIDGET -> method=="n"
        FULLSCREEN_HELPER -> method=="a"
        HARDWARE_SERVICE -> method=="onConfigurationChanged"
        else -> false
    }

    @JvmStatic fun playerActivity(pkg:String,name:String)=pkg==PACKAGE&&name in setOf(
        "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity")

    /** The player already handles orientation/size; external density changes alone lose its
     * fullscreen state by relaunching the Activity. No other configuration bits are added. */
    @JvmStatic fun densityConfigChanges(pkg:String,name:String,active:Boolean,external:Boolean,
        user:Int,display:Int,type:Int,mode:Int,original:Int):Int {
        if(!active||!external||!playerActivity(pkg,name)||user!=0||display!=0||type!=1||mode!=5||
            original and 0x480 != 0x480)return original
        return original or 0x1000
    }

    /** A player first requests orientation, then hides system bars after its config callback.
     * Do not require already-wide bounds or already-hidden bars to enter that state. */
    @JvmStatic fun explicitWide(pkg:String,name:String,mode:Int,orientation:Int):Boolean? {
        if(mode!=5||!playerActivity(pkg,name))return null
        return when(orientation){
            0,6,8,11 -> true
            -1,1,7,9,12 -> false
            else -> null
        }
    }
}
