package io.github.s24u.popupscale.gestures

import android.view.View
import java.util.WeakHashMap

/** Bind after the complete native hierarchy is attached, not in an early inflate callback. */
class NotificationSurfaceLifecycle(private val bind:(View)->Unit,private val detach:(View)->Unit) {
    private val watched=WeakHashMap<View,Boolean>()
    @Synchronized fun watch(owner:View){
        if(watched.put(owner,true)!=null)return
        var queued=false
        fun schedule(){
            if(queued||!owner.isAttachedToWindow)return
            queued=true
            owner.post{queued=false;if(owner.isAttachedToWindow)bind(owner)}
        }
        owner.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){schedule()}
            override fun onViewDetachedFromWindow(v:View){detach(v)}
        })
        owner.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->schedule()}
        schedule()
    }
}
