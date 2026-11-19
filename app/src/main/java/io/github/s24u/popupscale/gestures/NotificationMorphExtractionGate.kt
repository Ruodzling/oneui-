package io.github.s24u.popupscale.gestures

/** A native handler invocation owns only its exact provider and original Change bounds object. */
class NotificationMorphExtractionGate {
    class Lease internal constructor(internal val previous:Lease?,internal val provider:Any,internal val bounds:Any)
    private val active=ThreadLocal<Lease?>()
    fun begin(provider:Any,bounds:Any):Lease=Lease(active.get(),provider,bounds).also{active.set(it)}
    fun owns(provider:Any,bounds:Any?):Boolean=active.get()?.let{it.provider===provider&&it.bounds===bounds}==true
    fun end(lease:Lease){
        check(active.get()===lease){"Notification morph extraction scope out of order"}
        if(lease.previous==null)active.remove()else active.set(lease.previous)
    }
}
