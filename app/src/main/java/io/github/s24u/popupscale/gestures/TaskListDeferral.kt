package io.github.s24u.popupscale.gestures

/** Per-consumer, reversible deferral of the ROM's request to reveal other recent tasks. */
class TaskListDeferral {
    var blocking=false
    private var deferred:Boolean?=null
    val hasDeferred:Boolean get()=deferred!=null
    fun intercept(animated:Boolean):Boolean {
        if(!blocking)return false
        deferred=animated
        return true
    }
    fun takeDeferred():Boolean? {
        if(blocking)return null
        return deferred.also{deferred=null}
    }
    fun discard(){blocking=false;deferred=null}
}
