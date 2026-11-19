package io.github.s24u.popupscale.gestures

import kotlin.math.abs
import kotlin.math.roundToInt

/** Screen pixels at the boundary; tests also cover cancellation and accidental diagonals. */
object GesturePolicy {
    const val HANG_TRAVEL_DP = 220f
    const val SIDE_HANG_TRAVEL_DP = HANG_TRAVEL_DP / 2f
    const val GROWTH_PER_PIXEL = 0.6f
    enum class Result { RESTORE, FULLSCREEN, CLOSE, HANG_MINI, RESIZE }
    // Native masks: left=1, right=2, bottom=8. null consumes a bottom-only gesture.
    fun bottomResizeDirection(mask:Int,x:Float,y:Float,w:Int,h:Int,density:Float):Int? {
        if(w<=0 || h<=0 || density<=0 || !x.isFinite() || !y.isFinite())return mask
        val zone=minOf(48*density,w/4f,h/4f)
        return when {
            mask==8 && x<=zone -> 9
            mask==8 && x>=w-zone -> 10
            mask==8 -> null
            mask in 1..2 && y>=h-zone -> mask or 8
            else -> mask
        }
    }

    fun handleBarWidth(windowWidth: Int): Int {
        require(windowWidth > 0)
        return (windowWidth * 0.375f).roundToInt().coerceIn(1, windowWidth)
    }
    // edge: 0=bottom, 1=left, 2=right. All coordinates are physical screen pixels.
    data class HandleLayout(val edge:Int,val x:Int,val y:Int,val width:Int,val height:Int,
                            val barX:Int,val barY:Int,val barWidth:Int,val barHeight:Int)
    fun handleLayout(left:Int,top:Int,width:Int,height:Int,screenWidth:Int,screenHeight:Int,
                     density:Float,previousEdge:Int=0,lockSide:Boolean=false):HandleLayout {
        require(width>0&&height>0&&screenWidth>0&&screenHeight>0&&density>0&&density.isFinite())
        fun dp(value:Int)=(value*density).roundToInt().coerceAtLeast(1)
        val length=handleBarWidth(width)
        val thickness=dp(5).coerceAtLeast(2);val gap=dp(6);val zone=dp(26)
        if(screenWidth<=screenHeight){
            val hotWidth=minOf(width,length+dp(16))
            return HandleLayout(0,left+(width-hotWidth)/2,top+height,hotWidth,zone,
                (hotWidth-length)/2,gap,length,thickness)
        }
        val centre=left+width/2f;val middle=screenWidth/2f
        val edge=when {
            lockSide&&previousEdge in 1..2->previousEdge
            previousEdge==2&&centre<=middle+12*density->2
            previousEdge==1&&centre>=middle-12*density->1
            centre<=middle->2
            else->1
        }
        val hotHeight=minOf(height,length+dp(16),(screenHeight-dp(24)).coerceAtLeast(1))
        val barLength=minOf(length,hotHeight)
        val x=(if(edge==2)left+width else left-zone).coerceIn(0,(screenWidth-zone).coerceAtLeast(0))
        val y=(top+(height-hotHeight)/2).coerceIn(0,(screenHeight-dp(24)-hotHeight).coerceAtLeast(0))
        return HandleLayout(edge,x,y,zone,hotHeight,
            if(edge==2)gap else zone-gap-thickness,(hotHeight-barLength)/2,thickness,barLength)
    }
    data class HandleMotion(val cross:Float,val main:Float,val velocity:Float)
    fun handleMotion(edge:Int,dx:Float,dy:Float,vx:Float=0f,vy:Float=0f):HandleMotion = when(edge){
        1->HandleMotion(dy,-dx,-vx) // left-side bar: rightward is into the window
        2->HandleMotion(dy,dx,vx)   // right-side bar: leftward is into the window
        else->HandleMotion(dx,dy,vy)
    }
    fun handle(dx: Float, dy: Float, velocityY: Float, density: Float,
               cancelled: Boolean = false,
               windowWidth: Int = 0, screenWidth: Int = 0, atMiniSize: Boolean = true,
               sideHandle:Boolean=false): Result {
        if (cancelled || !density.isFinite() || density <= 0 || !dy.isFinite() ||
            !dx.isFinite() || !velocityY.isFinite() || abs(dx) > abs(dy) * 1.35f)
            return Result.RESTORE
        val isAlreadyMin = if (windowWidth > 0 && density > 0) {
            val minAllowedWidth = (220f * density).roundToInt()
            windowWidth <= minAllowedWidth + 1
        } else {
            false
        }
        val upHangDistance = (if(sideHandle)SIDE_HANG_TRAVEL_DP else HANG_TRAVEL_DP)*density
        val fastDown = abs(dy) >= 12 * density && velocityY >= 900 * density && dy > 0
        val fastUp = abs(dy) >= 12 * density && velocityY <= -900 * density && dy < 0
        return when {
            fastDown -> Result.FULLSCREEN
            dy < 0 && fastUp -> Result.CLOSE
            dy <= -upHangDistance && atMiniSize -> Result.HANG_MINI
            dy <= -4 * density && !isAlreadyMin -> Result.RESIZE
            dy >= 4 * density -> Result.RESIZE
            else -> Result.RESTORE
        }
    }
    fun arm(startY: Float, y: Float, dx: Float, width: Int, height: Int, density: Float,
            overview: Boolean, wasArmed: Boolean): Boolean {
        if (!overview || width <= 0 || height <= 0 || density <= 0 || !density.isFinite() ||
            !startY.isFinite() || !y.isFinite() || !dx.isFinite() ||
            startY < height - 80 * density || abs(dx) > width * .45f) return false
        val top = minOf(height * .22f, 112 * density)
        return startY - y > height * .5f && y <= top + if (wasArmed) 24 * density else 0f
    }
    fun progress(dy: Float, density: Float) =
        if(dy.isFinite()&&density.isFinite()&&density>0) (dy/(HANG_TRAVEL_DP*density)).coerceIn(-1f,1f) else 0f
    fun followScale(dy: Float, density: Float, miniScale: Float = 0.3f, maxScale: Float = 1.08f, windowWidth: Int = 0): Float {
        val p = progress(dy, density)
        val end = if (miniScale.isFinite()) miniScale.coerceIn(0.01f, 1f) else 0.3f
        val max = if (maxScale.isFinite()) maxScale.coerceAtLeast(1f) else 1.08f
        if(dy>=0f && dy.isFinite() && density.isFinite() && density>0f && windowWidth>0){
            return (1f+dy*GROWTH_PER_PIXEL/windowWidth).coerceAtMost(max)
        }
        return when {
            p <= -1f -> end
            p < 0f -> 1f - abs(p) * (1f - end)
            p >= 1f -> max
            else -> 1f + p * (max - 1f)
        }
    }
    fun shrinkScale(dy:Float,density:Float,miniScale:Float,minResizeScale:Float,sideHandle:Boolean=false):Float {
        val shrinkDelta=if(sideHandle)dy*(HANG_TRAVEL_DP/SIDE_HANG_TRAVEL_DP) else dy
        val travel=(-progress(shrinkDelta,density)).coerceIn(0f,1f)
        val end=miniScale.coerceIn(0.01f,1f)
        if(travel>=1f)return end
        if(minResizeScale>=0.99f)return 1f-travel*(1f-end)
        val split=120f/HANG_TRAVEL_DP
        return if(travel<=split)1f-(travel/split)*(1f-minResizeScale)
            else minResizeScale-(travel-split)/(1f-split)*(minResizeScale-end)
    }
    fun closeTargetScale(startScale: Float): Float =
        (startScale * 0.72f).coerceIn(0.001f, 0.72f)
}
