package io.github.s24u.popupscale.gestures

object NotificationRouteChecks {
    fun run(){
        val id=NotificationRouteIdentity("com.example.target",10234,1000,"a".repeat(32))
        check(NotificationRouteIdentity.parse(id.descriptor(),1000)==id)
        check(NotificationRouteIdentity.parse(id.descriptor(),4500)==id)
        check(NotificationRouteIdentity.parse(id.descriptor(),4501)==null)
        check(NotificationRouteIdentity.parse(id.descriptor(),999)==null)
        check(NotificationRouteIdentity.parse(id.copy(uid=1000).descriptor(),1000)==null)
        check(NotificationRouteIdentity.parse(id.copy(packageName="bad|pkg").descriptor(),1000)==null)
        check(NotificationRouteIdentity.parse(id.copy(nonce="not-a-nonce").descriptor(),1000)==null)
        fun auth(sender:Int=10100,resolved:Int=10234,creator:Int=10345,selected:Boolean=true,
            enabled:Boolean=true,activity:Boolean=true,mode:Int=5,display:Int=0,unlocked:Boolean=true,supported:Boolean=true)=
            NotificationRoutePolicy.accepts(sender,10100,10234,resolved,creator,supported,enabled,selected,activity,mode,display,unlocked)
        check(auth()&&auth(display=-1))
        check(!auth(sender=2000)&&!auth(sender=1000)&&!auth(sender=10234))
        check(!auth(resolved=10235)&&!auth(creator=9510345))
        check(!auth(selected=false)&&!auth(enabled=false)&&!auth(activity=false))
        check(!auth(mode=1)&&!auth(display=2)&&!auth(unlocked=false)&&!auth(supported=false))
        fun hop(actual:String="com.example.target",user:Int=0,depth:Int=1,fresh:Boolean=true,initial:Boolean=false,
            linked:Boolean=true,transient:Boolean=true,caller:Int=10345)=NotificationRoutePolicy.destination(
            "com.example.target","com.example.proxy",actual,0,user,depth,fresh,initial,linked,transient,caller,10345)
        check(hop()&&hop(actual="com.example.proxy",initial=true,linked=false,transient=false))
        check(hop(linked=false)) // Application-context hop: exact original proxy UID, unique active route.
        check(!hop(linked=false,caller=10555))
        check(!hop(actual="com.example.unrelated")&&!hop(user=95)&&!hop(depth=4)&&!hop(fresh=false))
        check(!hop(transient=false)) // Already drawn normal page cannot propagate the route.
        check(!hop(actual="com.example.proxy",linked=false))
        println("PASS: notification route marker expiry/authenticated sender/package UID/profile/display isolation; only linked invisible proxy or exact creator-to-owner handoff propagates, rendered/unrelated launches stay native.")
    }
}
