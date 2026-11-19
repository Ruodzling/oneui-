package io.github.s24u.popupscale.gestures

object NotificationPullPolicy {
    const val EDGE_PROCESS="com.android.systemui:edgelighting"
    fun eligible(enabled:Boolean,observe:Boolean,option:Boolean,selected:Boolean,activity:Boolean,
                 applicationUid:Boolean,user:Int,display:Int,locked:Boolean,interactive:Boolean,
                 nativeAction:Boolean)=enabled&&!observe&&option&&selected&&activity&&applicationUid&&
        io.github.s24u.popupscale.CloneUserPolicy.supported(user)&&display==0&&!locked&&interactive&&nativeAction

    fun shouldStartOnMove(dx:Float,dy:Float,touchSlop:Int):Boolean =
        dx.isFinite() && dy.isFinite() && dy >= touchSlop.coerceAtLeast(1) &&
            kotlin.math.abs(dx) <= dy * .75f

    fun release(dx:Float,dy:Float,vy:Float,density:Float,downGeneration:Int,currentGeneration:Int,
                cancelled:Boolean):Boolean {
        if(cancelled||downGeneration<0||downGeneration!=currentGeneration||!dx.isFinite()||!dy.isFinite()||!vy.isFinite()||
            !density.isFinite()||density<=0)return false
        val x=kotlin.math.abs(dx)/density;val y=dy/density;val speed=vy/density
        return y>=12f&&x<=maxOf(8f,y*.75f)&&(y>=32f||speed>=700f)
    }
}
