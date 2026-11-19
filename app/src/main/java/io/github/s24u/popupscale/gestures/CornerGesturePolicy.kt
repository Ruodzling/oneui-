package io.github.s24u.popupscale.gestures

/** Mirror the moving upper corner about the physical display centre, respecting native minima. */
object CornerGesturePolicy {
    fun centered(width:Int,height:Int,edge:Int,left:Int,top:Int,right:Int,bottom:Int,
                 minWidth:Int,minHeight:Int):LivePlacement.Bounds? {
        if(width<=0||height<=0||edge !in setOf(5,9)||minWidth !in 1..width||minHeight !in 1..height||
            left<0||top<0||right>width||bottom>height||right<=left||bottom<=top)return null
        val dx=(if(edge==5)left else width-right).coerceIn(0,(width-minWidth)/2)
        val dy=top.coerceIn(0,(height-minHeight)/2)
        return LivePlacement.Bounds(dx,dy,width-2*dx,height-2*dy)
    }
    fun adopts(corner:Boolean,mode:Int,sourceWidth:Int,sourceHeight:Int,width:Int,height:Int)=
        corner&&mode==5&&width>0&&height>0&&sourceWidth==width&&sourceHeight==height
}
