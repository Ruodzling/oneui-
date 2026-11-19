package io.github.s24u.popupscale.gestures

object LeasePreparationChecks {
    fun run(){
        io.github.s24u.popupscale.CloneUserPolicy.replaceVerifiedClones(setOf(95))
        try{
            for(user in listOf(0,95)){
                // Regression: Launcher requests protection BEFORE converting this fullscreen task.
                check(MiniLeasePolicy.acquirable(user,user,0,1,1,true))
                check(!MiniLeasePolicy.acquirable(user,user,0,1,1,false))
                check(MiniLeasePolicy.acquirable(user,user,0,1,5,false))
                check(MiniLeasePolicy.acquirable(user,user,0,1,5,true))
                // A preparation ACK must never protect a still-fullscreen task from HOME.
                check(!MiniLeasePolicy.protects(41,user,6000,2000,41,user,0,0,1,true))
                check(MiniLeasePolicy.protects(41,user,6000,2000,41,user,0,0,5,true))
                for(mode in listOf(0,2,3,4,6))check(!MiniLeasePolicy.acquirable(user,user,0,1,mode,true))
                for(type in listOf(0,2,3,4,5))check(!MiniLeasePolicy.acquirable(user,user,0,type,1,true))
                check(!MiniLeasePolicy.acquirable(user,user,1,1,1,true))
            }
            check(!MiniLeasePolicy.acquirable(0,95,0,1,1,true))
            check(!MiniLeasePolicy.acquirable(95,0,0,1,1,true))
            for(user in listOf(-1,10,94,96,150))check(!MiniLeasePolicy.acquirable(user,user,0,1,1,true))
        }finally{io.github.s24u.popupscale.CloneUserPolicy.replaceVerifiedClones(emptySet())}
        check(!MiniLeasePolicy.acquirable(95,95,0,1,1,true))
        println("PASS: owner/verified-clone fullscreen preparation handshake followed by freeform renewal; ordinary fullscreen, wrong user/display/type/mode denied; HOME protection remains freeform-only.")
    }
}
