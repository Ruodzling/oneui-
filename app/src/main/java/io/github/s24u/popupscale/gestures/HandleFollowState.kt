package io.github.s24u.popupscale.gestures

/** Latest geometry per task/drag generation, shared by Shell input and the UI frame callback. */
class HandleFollowState {
    companion object {
        fun focused(task:Int,reported:Boolean,displayOwner:Int?)=displayOwner?.let{it==task} ?: reported
    }
    data class Session(val task:Int,val generation:Long)
    data class Frame(val session:Session,val bounds:LivePlacement.Bounds)
    data class Stats(val samples:Int,val frames:Int)
    private data class Drag(val session:Session,val initial:LivePlacement.Bounds,
                            var bounds:LivePlacement.Bounds,var ended:Boolean=false,
                            var samples:Int=0,var frames:Int=0)
    private var generation=0L
    private val drags=mutableMapOf<Int,Drag>()
    private val pending=mutableMapOf<Int,Frame>()
    @Synchronized fun begin(task:Int,bounds:LivePlacement.Bounds):Session {
        val session=Session(task,++generation)
        drags[task]=Drag(session,bounds,bounds);pending.remove(task)
        return session
    }
    @Synchronized fun offer(session:Session,bounds:LivePlacement.Bounds):Boolean {
        val drag=drags[session.task] ?: return false
        if(drag.session!=session||drag.ended||bounds.width<=0||bounds.height<=0)return false
        drag.bounds=bounds;drag.samples++;pending[session.task]=Frame(session,bounds)
        return true
    }
    @Synchronized fun drain():List<Frame> {
        val result=pending.values.filter{drags[it.session.task]?.let{d->d.session==it.session&&!d.ended}==true}
        result.forEach{drags[it.session.task]?.let{d->d.frames++}}
        pending.clear();return result
    }
    @Synchronized fun end(session:Session,bounds:LivePlacement.Bounds?,cancelled:Boolean=false):Boolean {
        val drag=drags[session.task] ?: return false
        if(drag.session!=session||drag.ended)return false
        drag.ended=true
        drag.bounds=if(cancelled)drag.initial else bounds?.takeIf{it.width>0&&it.height>0} ?: drag.bounds
        pending.remove(session.task);return true
    }
    @Synchronized fun current(task:Int)=drags[task]?.bounds
    @Synchronized fun matches(session:Session)=drags[session.task]?.session==session
    @Synchronized fun stats(session:Session)=drags[session.task]?.takeIf{it.session==session}?.let{Stats(it.samples,it.frames)}
    @Synchronized fun contains(task:Int)=drags.containsKey(task)
    @Synchronized fun positionOnly(task:Int)=drags[task]?.let{
        it.initial.width==it.bounds.width&&it.initial.height==it.bounds.height
    }==true
    @Synchronized fun acknowledge(task:Int,bounds:LivePlacement.Bounds):Boolean {
        val drag=drags[task] ?: return false
        if(!drag.ended||drag.bounds!=bounds)return false
        drags.remove(task);pending.remove(task);return true
    }
    @Synchronized fun finish(session:Session):Boolean {
        val drag=drags[session.task] ?: return false
        if(drag.session!=session||!drag.ended)return false
        drags.remove(session.task);pending.remove(session.task);return true
    }
    @Synchronized fun forget(task:Int){drags.remove(task);pending.remove(task)}
    @Synchronized fun discard(session:Session){
        if(drags[session.task]?.session==session)forget(session.task)
    }
}
