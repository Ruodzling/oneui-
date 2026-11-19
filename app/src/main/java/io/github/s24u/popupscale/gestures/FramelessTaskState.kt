package io.github.s24u.popupscale.gestures

import android.content.ComponentName
import android.content.res.Configuration
import android.os.Process
import io.github.s24u.popupscale.CloneUserPolicy
import de.robv.android.xposed.XposedHelpers as X

/** Native callback metadata only. Never calls the organizer or changes placement history. */
class FramelessTaskState(private val config:FeatureConfig) {
    data class Entry(val id:Int,val user:Int,val token:Any,val packageName:String,var caption:Int=0)
    private val entries=java.util.concurrent.ConcurrentHashMap<Int,Entry>()
    fun active()=config.enabled&&!config.observe&&config.frameless
    fun get(id:Int)=entries[id]?.takeIf{active()&&config.accepts(it.packageName)}
    fun forget(id:Int){entries.remove(id)}
    fun observe(info:Any):Entry? {
        val id=X.getIntField(info,"taskId")
        val c=X.getObjectField(info,"configuration") as Configuration
        val w=X.getObjectField(c,"windowConfiguration")
        val component=Guard.field(info,"topActivity") as? ComponentName
        val user=X.getIntField(info,"userId")
        if(!active()||component==null||!config.accepts(component.packageName)||
            !CloneUserPolicy.visibleToOwner(user,Process.myUid()/100000)||
            X.getIntField(info,"displayId")!=0||X.callMethod(w,"getActivityType")!=1||
            X.callMethod(w,"getWindowingMode")!=5||(Guard.field(c,"semDesktopModeEnabled") as? Int)==1){
            entries.remove(id);return null
        }
        val token=X.callMethod(X.getObjectField(info,"token"),"asBinder")?:return null
        val previous=entries[id]
        if(previous!=null&&previous.user==user&&previous.token==token&&previous.packageName==component.packageName)return previous
        return Entry(id,user,token,component.packageName).also{entries[id]=it}
    }
}
