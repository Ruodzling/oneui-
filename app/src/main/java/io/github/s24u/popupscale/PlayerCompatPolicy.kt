package io.github.s24u.popupscale

/** Freeform video fullscreen compatibility for Bilibili across versions (9.17+ / 9.20+). */
object PlayerCompatPolicy {
    const val PACKAGE="tv.danmaku.bili"
    const val DETAIL_ACTIVITY="com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    private const val FULLSCREEN_WIDGET="com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullscreenWidget"
    private const val FULLSCREEN_HELPER="jv0.d"
    const val HARDWARE_SERVICE="com.bilibili.playerbizcommon.features.hardware.HardwareService"

    @JvmStatic fun playerCallerTypes()=arrayOf(
        FULLSCREEN_WIDGET, HARDWARE_SERVICE, DETAIL_ACTIVITY, FULLSCREEN_HELPER)

    @JvmStatic fun eligible(pkg:String,enabled:Boolean,diagnostic:Boolean,selected:Boolean,mode:Int)=
        pkg==PACKAGE&&enabled&&!diagnostic&&selected&&mode==5

    private fun simpleName(type:String):String {
        val dot=type.lastIndexOf('.')
        return if(dot>=0)type.substring(dot+1) else type
    }

    private fun isHookPlumbing(type:String,method:String):Boolean {
        if(type=="java.lang.Thread"||type.startsWith("de.robv.android.xposed.")||
            type.startsWith("io.github.libxposed.")||type.startsWith("LSPHooker_")||
            type=="J"||type.startsWith("java.lang.reflect."))return true
        if(type=="android.app.Activity"&&method=="isInMultiWindowMode")return true
        if(type=="io.github.s24u.popupscale.PopupScaleModule"||
            type.startsWith("io.github.s24u.popupscale.PopupScaleModule\$"))return true
        return false
    }

    private fun isFrameworkType(type:String)=
        type.startsWith("android.")||type.startsWith("androidx.")||type.startsWith("com.android.")||
            type.startsWith("com.samsung.")||type.startsWith("java.")||type.startsWith("javax.")||
            type.startsWith("kotlin.")||type.startsWith("kotlinx.")||type.startsWith("dalvik.")||
            type.startsWith("sun.")

    @JvmStatic fun excludedCaller(type:String,method:String):Boolean {
        if(type.startsWith("io.github.s24u.popupscale."))return true
        if(type.startsWith("com.bilibili.playerbizcommon.features.background."))return true
        if(type.startsWith("com.bilibili.search"))return true
        if(type=="sw1.h"&&method=="then")return true
        val simple=simpleName(type)
        return simple.contains("BackgroundPlay")||simple.contains("SearchActivity")||
            simple.contains("Clipboard")||simple.contains("ClipBoard")||
            simple.contains("PictureInPicture")||simple.contains("PipManager")||
            simple.contains("PipController")||simple.contains("PipHelper")
    }

    @JvmStatic fun playerCaller(type:String,method:String):Boolean {
        if(excludedCaller(type,method))return false
        if(type==FULLSCREEN_HELPER)return method=="a"
        if(type==FULLSCREEN_WIDGET||type==HARDWARE_SERVICE)return true
        if(type.startsWith("com.bilibili.app.gemini.player.")||
            type.startsWith("com.bilibili.playerbizcommon.features.hardware.")||
            type.startsWith("com.bilibili.playerbizcommon.features.fullscreen.")||
            type.startsWith("com.bilibili.playerbizcommon.player.")||
            type.startsWith("com.bilibili.ship.theseus.detail.")||
            type.startsWith("com.bilibili.ship.theseus.united.")||
            type.startsWith("tv.danmaku.biliplayer.")||
            type.startsWith("tv.danmaku.biliplayerv2."))return true
        if(type.startsWith("com.bilibili.")||type.startsWith("tv.danmaku.")){
            val simple=simpleName(type)
            if(simple.contains("Fullscreen")||simple.contains("FullScreen"))return true
        }
        return false
    }

    private fun isInteractiveTrigger(type:String,method:String):Boolean =
        (type=="android.view.View"&&(method=="performClick"||method=="performLongClick"||
            method=="dispatchTouchEvent"||method=="onTouchEvent"))||
        ((type=="android.app.Activity"||type==DETAIL_ACTIVITY)&&
            (method=="onConfigurationChanged"||method=="performConfigurationChanged"||
                method=="dispatchConfigurationChanged"))

    @JvmStatic fun shouldBypassStack(frames:Array<StackTraceElement>):Boolean =
        shouldBypassFrames(frames.map{it.className to it.methodName})

    @JvmStatic fun shouldBypassFrames(frames:List<Pair<String,String>>):Boolean {
        var start=0
        while(start<frames.size&&isHookPlumbing(frames[start].first,frames[start].second))start++
        if(start>=frames.size)return false
        val direct=frames[start]
        if(isFrameworkType(direct.first))return false
        var matched=false
        for(i in start until frames.size){
            val (type,method)=frames[i]
            if(excludedCaller(type,method))return false
            if(playerCaller(type,method)||isInteractiveTrigger(type,method))matched=true
        }
        return matched
    }

    @JvmStatic fun playerActivity(pkg:String,name:String)=pkg==PACKAGE&&name in setOf(DETAIL_ACTIVITY)

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
