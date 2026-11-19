package io.github.s24u.popupscale.gestures

/** One pre-pause observation per foreground visit, retained through config recreation. */
class MiniPlaybackSnapshot {
    data class Decision(val allowed:Boolean,val reason:String,val source:String,val age:Long)
    private data class Entry(val component:String,var armed:Boolean=true,var playing:Boolean?=null,
                             var at:Long=0,var source:String="none")
    private val entries=mutableMapOf<Int,Entry>() // Application main thread only.
    fun created(task:Int,component:String){
        if(entries[task]?.component!=component)entries[task]=Entry(component)
    }
    fun foreground(task:Int,component:String){entries[task]=Entry(component)}
    fun needsCapture(task:Int,component:String)=entries[task]?.let{it.component!=component||it.armed}?:true
    fun capture(task:Int,component:String,playing:Boolean?,source:String,now:Long){
        created(task,component)
        val entry=entries.getValue(task)
        if(!entry.armed)return // A later config PAUSE is not a new user pause decision.
        entry.armed=false;entry.playing=playing;entry.source=source;entry.at=now
    }
    fun decision(task:Int,component:String,now:Long):Decision {
        val entry=entries[task]?.takeIf{it.component==component}
            ?:return Decision(false,"no-snapshot","none",-1)
        val age=now-entry.at
        if(entry.armed||entry.playing==null)return Decision(false,"state-unknown",entry.source,age)
        if(age !in 0L..15000L)return Decision(false,"expired",entry.source,age)
        return Decision(entry.playing==true,if(entry.playing==true)"playing" else "not-playing",entry.source,age)
    }
    fun forget(task:Int){entries.remove(task)}
}
