package io.github.s24u.popupscale.gestures

import kotlin.math.hypot

/** Upper-right corner hang intent; rejects normal upward Home flicks and center/left swipes. */
object HangTargetPolicy {
    data class State(val visible:Boolean=false,val armed:Boolean=false,val progress:Float=0f)
    fun evaluate(startY:Float,x:Float,y:Float,width:Int,height:Int,density:Float,
                 movingTask:Boolean,wasArmed:Boolean,live:Boolean=true,startX:Float=Float.NaN):State {
        if(!movingTask||!startY.isFinite()||!x.isFinite()||!y.isFinite()||
            !density.isFinite()||density<=0||width<=0||height<=0||
            startY<height-80*density||startY>height||x<0||x>width||y<0||y>height)return State()
        val target=LivePlacement.guideBounds(width,height,density,live)?:return State()
        val travel=startY-y
        val portrait=height>=width
        val visibleTravel=height*(if(portrait).32f else .28f)
        if(travel<visibleTravel||(!wasArmed&&x<width*.52f))return State()
        if(startX.isFinite()&&!wasArmed&&x-startX<-16f*density&&x<width*.72f)return State()
        val dx=maxOf(target.x-x,0f,x-(target.x+target.width))
        val dy=maxOf(target.y-y,0f,y-(target.y+target.height))
        val distance=hypot(dx,dy)
        val padding=(if(wasArmed)24 else 8)*density
        val minArmTravel=if(portrait)height*(if(wasArmed).42f else .46f) else height*(if(wasArmed).30f else 1f/3f)
        val rightwardOk=!startX.isFinite()||wasArmed||(x-startX>=24f*density)||x>=width*.72f
        val armed=travel>=minArmTravel&&x>=width*.60f&&rightwardOk&&dx<=padding&&dy<=padding
        val approach=(1f-distance/(140*density)).coerceIn(0f,1f)
        return State(true,armed,if(armed)1f else approach*.85f)
    }
    /** Reject fast Home flicks that pass through the zone without pausing/decelerating. */
    fun allowRelease(dwellMs:Long,upwardVelocityDp:Float,totalSpeedDp:Float=kotlin.math.abs(upwardVelocityDp)):Boolean {
        if(dwellMs<80L)return false
        if(upwardVelocityDp.isFinite()&&upwardVelocityDp>750f)return false
        if(totalSpeedDp.isFinite()&&totalSpeedDp>1100f)return false
        return true
    }
}
