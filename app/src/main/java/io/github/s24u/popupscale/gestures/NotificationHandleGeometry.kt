package io.github.s24u.popupscale.gestures

import android.graphics.Rect

object NotificationHandleGeometry {
    /** getGlobalVisibleRect is root-relative; convert it to the native effect's local space. */
    @JvmStatic fun place(visible:Rect,rootX:Int,rootY:Int,hostX:Int,hostY:Int,width:Int,height:Int,
                         handleWidth:Int,handleHeight:Int,density:Float,out:Rect):Boolean {
        if(!density.isFinite()||density<=0||visible.width()<56*density||visible.height()<20*density||
            handleWidth<=0||handleHeight<=0||handleWidth>width){out.setEmpty();return false}
        val x=(visible.centerX()+rootX-hostX-handleWidth/2).coerceIn(0,width-handleWidth)
        val y=visible.bottom+rootY-hostY+(2*density).toInt()
        if(y<0||y+handleHeight>height){out.setEmpty();return false}
        out.set(x,y,x+handleWidth,y+handleHeight);return true
    }
}
