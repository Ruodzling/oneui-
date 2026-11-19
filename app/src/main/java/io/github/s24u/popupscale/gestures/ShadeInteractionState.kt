package io.github.s24u.popupscale.gestures

/** Independent signals: finishing an expansion must not unlock a still-open shade. */
class ShadeInteractionState {
    enum class Signal { EXPANDED, FOCUSABLE, EXPANDING }
    private val signals=mutableSetOf<Signal>()
    private var collapsedSince:Long?=null
    var settledOverride=false; private set
    var collapsedForMs=0L; private set
    val blocked get()=signals.isNotEmpty()
    fun update(signal:Signal,value:Boolean):Boolean {
        val previous=blocked
        // A fresh opening callback starts a new grace interval even before geometry
        // catches up. Old fully-collapsed samples must not bypass a new expansion.
        if(value){collapsedSince=null;collapsedForMs=0;settledOverride=false}
        if(value)signals.add(signal) else signals.remove(signal)
        return previous!=blocked
    }
    /** Native state supersedes missed or stale callback hints, including EXPANDING=true. */
    fun reconcile(native:NativeShadeState,now:Long=System.nanoTime()/1000000L):Boolean {
        val previous=blocked
        // One UI8.5 capture: expanded/QS animation hints remain true with both
        // geometries exactly closed. Require stable geometry, known idle tracking,
        // and no actually running animator; do not unlock a real expansion/drag.
        if(native.geometryCollapsed&&!native.tracking&&native.runningAnimator!=true){
            if(collapsedSince==null||now<collapsedSince!!)collapsedSince=now
            collapsedForMs=(now-collapsedSince!!).coerceAtLeast(0)
        }else{collapsedSince=null;collapsedForMs=0}
        settledOverride=native.expanded&&collapsedSince!=null&&collapsedForMs>=300
        signals.clear()
        if(native.expanded&&!settledOverride)signals.add(Signal.EXPANDED)
        if(native.tracking)signals.add(Signal.EXPANDING)
        return previous!=blocked
    }
    companion object {
        fun touchable(requested:Boolean,obscured:Boolean)=requested&&!obscured
    }
}
