package io.github.s24u.popupscale.gestures

import android.view.View

/** The native outline is separate and noninteractive; the caption remains untouched. */
class FramelessOutlineVisibility {
    private data class Original(val visibility:Int,val alpha:Float)
    private val saved=java.util.WeakHashMap<View,Original>()
    fun apply(view:View,hide:Boolean){
        if(hide){
            if(!saved.containsKey(view))saved[view]=Original(view.visibility,view.alpha)
            if(view.alpha!=0f)view.alpha=0f
            if(view.visibility!=View.INVISIBLE)view.visibility=View.INVISIBLE
        }else saved.remove(view)?.let{
            view.alpha=it.alpha;view.visibility=it.visibility
        }
    }
}
