package io.github.s24u.popupscale.gestures

object ShadeInteractionChecks {
    fun run(){
        val state=ShadeInteractionState()
        val expanding=ShadeInteractionState.Signal.EXPANDING
        val expanded=ShadeInteractionState.Signal.EXPANDED
        val focused=ShadeInteractionState.Signal.FOCUSABLE
        check(!state.blocked)
        check(state.update(expanding,true)&&state.blocked)
        check(!state.update(expanded,true)&&state.blocked)
        check(!state.update(focused,true)&&state.blocked)
        // The old implementation incorrectly unlocked on this animation callback.
        check(!state.update(expanding,false)&&state.blocked)
        check(!state.update(focused,false)&&state.blocked)
        check(state.update(expanded,false)&&!state.blocked)
        // Closing animation remains blocked while native flags settle in either order.
        state.update(expanded,true);state.update(focused,true);state.update(expanding,true)
        state.update(expanded,false);state.update(focused,false)
        check(state.blocked)
        check(state.update(expanding,false)&&!state.blocked)
        check(!state.update(expanding,false)&&!state.blocked)
        state.update(focused,true);check(state.blocked)
        state.update(focused,false);check(!state.blocked)
        // Collapsing shade restores only the front card's prior touch ownership.
        check(!ShadeInteractionState.touchable(false,true))
        check(!ShadeInteractionState.touchable(true,true))
        check(!ShadeInteractionState.touchable(false,false))
        check(ShadeInteractionState.touchable(true,false))
        fun native(values:Map<String,Any>):NativeShadeState?=NativeShadeState.read(values,
            {owner,name->(owner as? Map<*,*>)?.get(name)},
            {owner,name->(owner as? Map<*,*>)?.get(name)})
        // Regression from134: EXPANDING=true, no FINISHED callback for several minutes.
        state.update(expanding,true);state.update(focused,true)
        val closed=checkNotNull(native(mapOf("isExpandedWithoutHeadsUp" to false,"isTracking" to false)))
        check(state.reconcile(closed)&&!state.blocked)
        check(!state.reconcile(closed)&&!state.blocked)
        val animating=checkNotNull(native(mapOf("isExpandedWithoutHeadsUp" to true,"mExpandedFraction" to 0f)))
        check(state.reconcile(animating)&&state.blocked)
        check(state.reconcile(closed)&&!state.blocked)
        val tracking=checkNotNull(native(mapOf("mExpandedFraction" to 0f,"isTracking" to true)))
        state.reconcile(tracking);check(state.blocked)
        state.reconcile(closed);check(!state.blocked)
        val qs=checkNotNull(native(mapOf("mExpandedFraction" to 0f,"mQsController" to mapOf("getExpanded" to true))))
        state.reconcile(qs);check(state.blocked)
        state.reconcile(closed);check(!state.blocked)
        check(native(mapOf("mView" to "always-visible-root"))==null)
        check(native(mapOf("mExpandedFraction" to Float.NaN))==null)
        // Actual166 corner-entry capture: QS legacy expanded=true, height=min451,
        // max3120, both tracking flags false; shade itself is physically collapsed.
        val staleQs=mapOf<String,Any>("getExpanded" to true,"mExpansionHeight" to 451f,
            "mMinExpansionHeight" to 451,"mMaxExpansionHeight" to 3120,
            "isTracking" to false,"mAnimating" to false,"isExpandImmediate" to false)
        fun qsState(q:Map<String,Any>,panelOpen:Boolean=false)=checkNotNull(native(mapOf(
            "isExpandedWithoutHeadsUp" to panelOpen,"mExpandedFraction" to (if(panelOpen)1f else 0f),
            "mExpandedHeight" to (if(panelOpen)3120f else 0f),"mQsController" to q)))
        state.update(expanded,true)
        val corner=qsState(staleQs)
        check(!corner.expanded&&corner.qsFraction==0f&&corner.qsReportedExpanded==true)
        check(corner.source=="native-qs-collapsed"&&state.reconcile(corner)&&!state.blocked)
        check(qsState(staleQs,true).expanded) // Real notifications remain protected.
        check(qsState(staleQs+("mExpansionHeight" to 452f)).expanded)
        check(qsState(staleQs+("isTracking" to true)).tracking)
        check(qsState(staleQs+("mAnimating" to true)).expanded)
        check(qsState(staleQs+("isExpandImmediate" to true)).expanded)
        check(qsState(staleQs+("mExpansionAnimator" to mapOf("isStarted" to true))).expanded)
        check(qsState(staleQs+("mMaxExpansionHeight" to 451)).expanded) // Uninitialized range.
        check(qsState(staleQs+("mExpansionHeight" to Float.NaN)).expanded)
        check(qsState(staleQs-"isTracking").expanded)
        check(qsState(staleQs-"mAnimating").expanded)
        check(qsState(staleQs-"isExpandImmediate").expanded)
        check(qsState(mapOf("getExpanded" to true)).expanded) // Unknown API falls back.
        check(qsState(staleQs+("getExpanded" to false)+("mExpansionHeight" to 900f)).expanded)
        // S9360/8.5 report: raw native open and animation hints stick while both
        // measured geometries are closed. Replay the report rather than toggle APIs.
        val stuck=checkNotNull(native(mapOf("isExpandedWithoutHeadsUp" to true,
            "mExpandedFraction" to 0f,"mExpandedHeight" to 0f,"isTracking" to false,
            "mQsController" to (staleQs+("mAnimating" to true)+("isExpandImmediate" to true)))))
        state.reconcile(stuck,1000);check(state.blocked&&!state.settledOverride)
        state.reconcile(stuck,1299);check(state.blocked)
        state.reconcile(stuck,1300);check(!state.blocked&&state.settledOverride)
        val running=stuck.copy(runningAnimator=true)
        state.reconcile(running,1400);check(state.blocked&&!state.settledOverride)
        state.reconcile(running,9000);check(state.blocked)
        state.reconcile(stuck.copy(tracking=true,geometryCollapsed=false),9100);check(state.blocked)
        state.reconcile(stuck,9200);check(state.blocked)
        state.reconcile(stuck.copy(geometryCollapsed=false,panelFraction=.01f),9500);check(state.blocked)
        state.reconcile(stuck,9600);check(state.blocked)
        state.reconcile(stuck,9900);check(!state.blocked)
        state.update(expanded,true)
        state.reconcile(stuck,9950);check(state.blocked&&!state.settledOverride)
        state.reconcile(stuck.copy(geometryCollapsed=false),10000);check(state.blocked) // Unknown geometry never bypasses.
        println("PASS: S9360 collapsed-geometry/stale-animation replay unlocks only after300ms; real running animation, tracking, partial/open/unknown geometry stay blocked.")
        println("PASS: captured corner-gesture stale QS boolean with collapsed height unlocks; partial/open/tracking/animation/immediate QS stay protected; unknown/invalid ranges retain native fallback.")
        println("PASS: missed FINISHED/stale focus repaired by native collapsed state; native animation/tracking/QS remain blocked; always-visible root is never used as panel expansion.")
        println("PASS: shade open/fully-expanded/closing signal races keep mini input blocked; collapse preserves front/back-card touch ownership.")
    }
}
