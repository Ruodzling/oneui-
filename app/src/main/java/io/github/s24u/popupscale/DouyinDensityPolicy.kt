package io.github.s24u.popupscale

/** Window DPI is not a physical display change. These pages already handle window sizes. */
object DouyinDensityPolicy {
    fun playerActivity(pkg:String,name:String)=pkg=="com.ss.android.ugc.aweme"&&name in setOf(
        "com.ss.android.ugc.aweme.main.MainActivity",
        "com.ss.android.ugc.aweme.splash.SplashActivity",
        "com.ss.android.ugc.aweme.live.LivePlayActivity")

    /** Add only DENSITY to a per-call ActivityInfo copy, after native size/orientation
     * handling is verified. Never suppress a lifecycle callback or change app Intent/state. */
    fun densityCallbacks(pkg:String,name:String,active:Boolean,external:Boolean,user:Int,
                         display:Int,type:Int,mode:Int,original:Int):Int {
        if(!playerActivity(pkg,name)||!active||!external||!CloneUserPolicy.supported(user)||
            display!=0||type!=1||mode!=5||original and 0xc80!=0xc80)return original
        return original or 0x1000
    }
}
