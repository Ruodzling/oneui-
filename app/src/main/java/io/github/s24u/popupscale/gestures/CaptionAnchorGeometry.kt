package io.github.s24u.popupscale.gestures

object CaptionAnchorGeometry {
    @JvmStatic fun visualBottom(bar:android.widget.ImageView,root:android.view.View,nativeY:Float):Float? {
        val drawable=bar.drawable?:return null
        if(bar.width<=0||bar.height<=0||drawable.bounds.isEmpty)return null
        val visual=android.graphics.RectF(drawable.bounds);bar.imageMatrix.mapRect(visual)
        var top=nativeY;var child:android.view.View=bar
        while(child!==root){
            top+=child.top
            if(child!==bar)top+=child.translationY
            child=child.parent as? android.view.View?:return null
        }
        return top+bar.paddingTop+visual.bottom
    }
}
