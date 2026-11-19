package io.github.s24u.popupscale.gestures

/** Expiring identity, never a persistent global block on ordinary freeforms. */
object MiniLeasePolicy {
    const val TTL=5000L
    fun acquirable(requestedUser:Int,actualUser:Int,display:Int,type:Int,mode:Int,preparingFullscreen:Boolean)=
        requestedUser==actualUser&&io.github.s24u.popupscale.CloneUserPolicy.supported(actualUser)&&
            display==0&&type==1&&(mode==5||(preparingFullscreen&&mode==1))
    fun homeEligible(enabled:Boolean,observe:Boolean,live:Boolean,hang:Boolean,selected:Boolean,
                     user:Int,currentUser:Int,display:Int,type:Int,mode:Int,visible:Boolean,unlocked:Boolean)=
        enabled&&!observe&&live&&hang&&selected&&io.github.s24u.popupscale.CloneUserPolicy.visibleToOwner(user,currentUser)&&display==0&&type==1&&mode==5&&visible&&unlocked
    fun selected(id:Int,user:Int,expires:Long,now:Long,task:Int,caller:Int,launcher:Int,systemUi:Int):Boolean =
        id>=0&&id==task&&io.github.s24u.popupscale.CloneUserPolicy.supported(user)&&now>=0&&expires>now&&expires-now<=TTL&&
            caller>=0&&((launcher>=0&&caller==launcher)||(systemUi>=0&&caller==systemUi))
    fun launcherSelected(id:Int,user:Int,expires:Long,now:Long,task:Int,taskUser:Int,display:Int,mode:Int,
                         caller:Int,launcher:Int,result:Int,mainLauncherIntent:Boolean):Boolean =
        mainLauncherIntent&&result in setOf(0,2,3)&&taskUser==user&&display==0&&mode==5&&
            selected(id,user,expires,now,task,caller,launcher,-1)
    fun protects(id:Int,user:Int,expires:Long,now:Long,task:Int,taskUser:Int,currentUser:Int,
                 display:Int,mode:Int,insideBatch:Boolean):Boolean =
        insideBatch&&id>=0&&id==task&&user==taskUser&&io.github.s24u.popupscale.CloneUserPolicy.visibleToOwner(user,currentUser)&&display==0&&mode==5&&
            now>=0&&expires>now&&expires-now<=TTL
}
