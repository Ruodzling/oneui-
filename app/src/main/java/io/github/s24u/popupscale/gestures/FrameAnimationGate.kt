package io.github.s24u.popupscale.gestures

/** Main-thread rendezvous: frame motion and native mode change may finish in either order. */
class FrameAnimationGate {
    private var started=false
    private var result:Boolean?=null
    private val waiters=mutableListOf<(Boolean)->Unit>()
    fun begin():Boolean {
        if(started||result!=null)return false
        started=true
        return true
    }
    fun complete(success:Boolean) {
        if(result!=null)return
        result=success
        val callbacks=waiters.toList();waiters.clear()
        callbacks.forEach{it(success)}
    }
    fun await(done:(Boolean)->Unit) {
        val finished=result
        if(finished!=null)done(finished) else waiters.add(done)
    }
}
