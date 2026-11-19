package io.github.s24u.popupscale.gestures

/** Routing metadata only: no notification key, message, account or Intent payload. */
data class NotificationRouteIdentity(val packageName:String,val uid:Int,val created:Long,val nonce:String) {
    fun descriptor()="$PREFIX|$packageName|$uid|$created|$nonce"
    companion object {
        const val PREFIX="io.github.s24u.popupscale.notification-route.v1"
        const val TTL=3500L
        fun parse(raw:String?,now:Long):NotificationRouteIdentity? {
            if(raw==null||raw.length>300)return null
            val p=raw.split('|');if(p.size!=5||p[0]!=PREFIX)return null
            if(!p[1].matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))return null
            val uid=p[2].toIntOrNull() ?: return null
            val created=p[3].toLongOrNull() ?: return null
            if(!io.github.s24u.popupscale.AppScopePolicy.hasApplicationUid(uid)||created<0||
                now-created !in 0..TTL||!p[4].matches(Regex("[a-f0-9]{32}")))return null
            return NotificationRouteIdentity(p[1],uid,created,p[4])
        }
    }
}

object NotificationRoutePolicy {
    fun accepts(sender:Int,systemUi:Int,targetUid:Int,resolvedUid:Int,creatorUid:Int,userSupported:Boolean,
        enabled:Boolean,selected:Boolean,activity:Boolean,mode:Int,display:Int,unlocked:Boolean)=
        systemUi>=0&&sender==systemUi&&targetUid==resolvedUid&&
            io.github.s24u.popupscale.AppScopePolicy.hasApplicationUid(targetUid)&&
            io.github.s24u.popupscale.AppScopePolicy.hasApplicationUid(creatorUid)&&
            targetUid/100000==creatorUid/100000&&userSupported&&enabled&&selected&&activity&&
            mode==5&&display in setOf(-1,0)&&unlocked
    fun destination(target:String,creator:String,actual:String,user:Int,actualUser:Int,depth:Int,
        fresh:Boolean,initial:Boolean,sourceLinked:Boolean,sourceTransient:Boolean,caller:Int,
        creatorUid:Int)=fresh&&user==actualUser&&depth in 0..3&&
        actual in setOf(target,creator)&&(initial||sourceLinked&&sourceTransient||
            sourceTransient&&caller==creatorUid&&actual==target)
}
