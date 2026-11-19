package io.github.s24u.popupscale

/** Per-window density is not a physical display-density change requiring QQ's restart. */
object QQDensityPolicy {
    const val PACKAGE = "com.tencent.mobileqq"
    const val TIM = "com.tencent.tim"
    /** Verified APK entry points; obfuscated legacy names remain pinned to their exact versionCode. */
    data class RestartTarget(val pkg:String,val version:Long,val manager:String,val method:String,
                             val originField:String,val contextArgument:Boolean,
                             val minVersion:Long=version,val maxVersion:Long=version)
    private const val MODERN_QQ_MIN_VERSION=15900L
    val restartTargets = listOf(
        RestartTarget(PACKAGE,16750L,"com.tencent.mobileqq.app.utils.GlobalDisplayMetricsManager",
            "checkChangeOfSysDensity","sOriginDisplayMetrics",true,MODERN_QQ_MIN_VERSION,Long.MAX_VALUE),
        RestartTarget(PACKAGE,3350L,"com.tencent.mobileqq.app.utils.f","e","c",false),
        RestartTarget(TIM,4050L,"com.tencent.mobileqq.app.utils.GlobalDisplayMetricsManager",
            "checkChangeOfSysDensity","sOriginDisplayMetrics",true))
    fun restartTarget(pkg:String,version:Long)=
        restartTargets.singleOrNull { it.pkg==pkg&&version in it.minVersion..it.maxVersion }
    fun chatActivity(pkg: String, name: String): Boolean = pkg in setOf(PACKAGE,TIM) && name in setOf(
        "com.tencent.mobileqq.activity.SplashActivity", "com.tencent.mobileqq.activity.ChatActivity")
    fun configurationActivity(pkg:String,name:String):Boolean=chatActivity(pkg,name)||
        (pkg in setOf(PACKAGE,TIM)&&name=="com.tencent.mobileqq.activity.LoginActivity")

    /** Verified main/chat activities already handle orientation and window sizes (0xda4).
     * Their layout paths handle resizing; add only the missing density callback.
     * The server temporarily copies ActivityInfo for this call, never changing PackageManager. */
    fun densityCallbacks(version: Long, pkg: String, name: String, active: Boolean,
                         external: Boolean, user: Int, display: Int, type: Int, mode: Int,
                         original: Int): Int {
        if (restartTarget(pkg,version)==null || !active || !external ||
            user != 0 || display != 0 || type != 1 || mode != 5) return original
        // These two verified login pages are now hosted by our fixed-DPI canvas. Its
        // ActivityThread resize callbacks remeasure the same View hierarchy in place;
        // the old manifests omitted screen-size/density handling and caused a relaunch.
        val legacyLogin=name=="com.tencent.mobileqq.activity.LoginActivity"&&
            ((pkg==PACKAGE&&version==3350L)||(pkg==TIM&&version==4050L))
        if(legacyLogin)return if(original and 0xa0==0xa0) original or 0x1d00 else original
        if(!chatActivity(pkg,name)||original and 0xc80 != 0xc80)return original
        return original or 0x1000
    }
    fun windowChange(version: Long, enabled: Boolean, user: Int, previousDisplay: Int,
                     display: Int, supplied: Int, original: Int, mode: Int, scaledBefore: Boolean,
                     displayId: Int = 0): Boolean {
        val target = restartTarget(PACKAGE, version)?.takeIf { it.minVersion == MODERN_QQ_MIN_VERSION } ?: return false
        return windowChange(target, version, enabled, user, previousDisplay, display, supplied, original, mode, scaledBefore, displayId)
    }
    fun windowChange(target:RestartTarget,version:Long,enabled:Boolean,user:Int,previousDisplay:Int,
                     display:Int,supplied:Int,original:Int,mode:Int,scaledBefore:Boolean,displayId:Int):Boolean {
        if(restartTarget(target.pkg,version)!=target)return false
        if(!enabled||user!=0||displayId!=0||previousDisplay!=display||display !in 120..1000||
            supplied !in 120..1000||original !in 120..1000||supplied==original)return false
        if(target.minVersion==MODERN_QQ_MIN_VERSION)
            return (mode == 5 && supplied != display) || (mode == 1 && scaledBefore && supplied == display)
        // Legacy apps can cache the per-window DPI on startup, then see the restored physical
        // DPI while still freeform. Both directions belong to this window, not a display change.
        return mode==5 || (mode==1&&scaledBefore&&supplied==display)
    }
}
