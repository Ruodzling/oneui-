package io.github.s24u.popupscale.gestures

/** Each sync resize owns exactly one native draw callback, including while another task draws. */
class SyncResizeRequest(private val started:Long,private val timeout:Long=3000) {
    enum class Step { WAIT, SUBMIT, CANCEL, TIMEOUT, IGNORE }
    private enum class State { WAITING, SUBMITTED, FINISHED }
    private var state=State.WAITING
    @Synchronized fun next(now:Long,busy:Boolean,current:Boolean):Step {
        if(state!=State.WAITING)return Step.IGNORE
        val step=when {
            !current->Step.CANCEL
            now-started>=timeout->Step.TIMEOUT
            busy->Step.WAIT
            else->Step.SUBMIT
        }
        if(step!=Step.WAIT)state=State.SUBMITTED
        return step
    }
    @Synchronized fun finish():Boolean {
        if(state==State.FINISHED)return false
        state=State.FINISHED
        return true
    }
}
