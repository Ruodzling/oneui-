package io.github.s24u.popupscale.gestures

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Follow one authenticated SystemUI notification launch, through invisible proxy activities only. */
class NotificationRouteHooks(private val loader:ClassLoader,private val caps:RomCapabilities,
    private val config:FeatureConfig,private val context:()->Context?,private val systemUiUid:()->Int) {
    private data class Route(val identity:NotificationRouteIdentity,val creator:String,val creatorUid:Int,
        val bounds:Rect,val records:MutableList<WeakReference<Any>> = mutableListOf(),var hops:Int=0)
    private data class Node(val route:Route,val depth:Int)
    private val initial=ThreadLocal<Route>()
    private val nodes=WeakHashMap<Any,Node>()
    private val active=linkedMapOf<String,Route>() // Under lock below; all entries expire quickly.
    private val lock=Any()
    @Volatile private var liveUntil=0L
    private fun hasActiveRoutes():Boolean {
        val now=SystemClock.uptimeMillis()
        if(now<=liveUntil)return true
        if(liveUntil==0L)return false
        return synchronized(lock){
            if(now>liveUntil){active.clear();nodes.clear();liveUntil=0L;false}else true
        }
    }
    private fun fresh(r:Route)=SystemClock.uptimeMillis()-r.identity.created in 0..NotificationRouteIdentity.TTL
    private fun enabled(pkg:String)=config.enabled&&!config.observe&&config.notificationPull&&config.accepts(pkg)
    private fun transient(record:Any)=Guard.field(record,"mNoDisplay")==true||
        Guard.field(record,"finishing")==true||Guard.field(record,"firstWindowDrawn")==false
    private fun selected(route:Route)=fresh(route)&&enabled(route.identity.packageName)
    fun install(){
        check(caps.notificationRoute)
        val send=checkNotNull(caps.notificationSendMethod)
        val optionsIndex=send.parameterTypes.indexOfFirst{it==Bundle::class.java}
        XposedBridge.hookMethod(send,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                // Clear an outer scope even for an unrelated nested send.
                p.setObjectExtra("notification.route.previous",initial.get());initial.remove()
                Guard.call("notification-route-send"){
                    if(Binder.getCallingUid()!=systemUiUid())return@call
                    if(!Guard.open("notification-route-send"))return@call
                    val bundle=p.args[optionsIndex] as? Bundle ?: return@call
                    val options=X.callStaticMethod(ActivityOptions::class.java,"fromBundle",bundle) ?: return@call
                    if(X.callMethod(options,"getLaunchWindowingMode")!=5)return@call
                    val marker=X.callMethod(options,"getLaunchCookie") as? IBinder ?: return@call
                    // This standard Binder descriptor is read before native sendInner's
                    // PI/WM locks. The only accepted sender is the installed SystemUI UID.
                    val descriptor=marker.interfaceDescriptor
                    val id=NotificationRouteIdentity.parse(descriptor,SystemClock.uptimeMillis()) ?: run{
                        if(descriptor?.startsWith(NotificationRouteIdentity.PREFIX)==true)
                            Guard.info("notification route marker invalid/expired; native launch retained")
                        return@call
                    }
                    val c=context() ?: return@call
                    val key=X.getObjectField(p.thisObject,"key")
                    val creator=X.getObjectField(key,"packageName") as String
                    val creatorUid=X.getIntField(p.thisObject,"uid")
                    val user=id.uid/100000
                    val app=X.callMethod(c.packageManager,"getApplicationInfoAsUser",id.packageName,0,user) as android.content.pm.ApplicationInfo
                    val mode=X.callMethod(options,"getLaunchWindowingMode") as Int
                    val display=X.callMethod(options,"getLaunchDisplayId") as Int
                    if(!NotificationRoutePolicy.accepts(Binder.getCallingUid(),systemUiUid(),id.uid,app.uid,creatorUid,
                            io.github.s24u.popupscale.CloneUserPolicy.supported(user),enabled(id.packageName),
                            config.accepts(id.packageName),X.getIntField(key,"type")==2,mode,display,
                            !c.getSystemService(KeyguardManager::class.java).isKeyguardLocked)){
                        Guard.info("notification route rejected owner=${id.packageName} targetUser=$user creator=$creator creatorUser=${creatorUid/100000} display=$display")
                        return@call
                    }
                    val bounds=X.callMethod(options,"getLaunchBounds") as? Rect ?: return@call
                    if(bounds.isEmpty||bounds.left<0||bounds.top<0)return@call
                    val route=Route(id,creator,creatorUid,Rect(bounds))
                    synchronized(lock){
                        active.entries.removeAll{!fresh(it.value)}
                        nodes.entries.removeAll{!fresh(it.value.route)}
                        if(active.size>=16)active.remove(active.keys.first())
                        active[id.nonce]=route
                        liveUntil=maxOf(liveUntil,id.created+NotificationRouteIdentity.TTL)
                    }
                    initial.set(route)
                    Guard.info("notification route accepted owner=${id.packageName} user=$user creator=$creator")
                }
            }
            override fun afterHookedMethod(p:MethodHookParam){initial.set(p.getObjectExtra("notification.route.previous") as? Route)}
        })
        XposedBridge.hookMethod(checkNotNull(caps.notificationStartMethod),object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("notification-route-start"){
                if(!hasActiveRoutes())return@call
                if(!Guard.open("notification-route-start"))return@call
                val target=p.args[0] ?: return@call
                val info=X.getObjectField(target,"info") as ActivityInfo
                val user=X.getIntField(target,"mUserId")
                val source=p.args[5]
                val parent=source?.let{synchronized(lock){nodes[it]}}
                val direct=initial.get()?.takeIf{selected(it)}
                val callingUid=X.getIntField(target,"launchedFromUid")
                val fallback=if(direct==null&&parent==null)synchronized(lock){
                    active.values.filter{r->selected(r)&&r.creatorUid==callingUid&&r.identity.packageName==info.packageName&&
                        r.records.any{it.get()?.let{record->transient(record)}==true}}.singleOrNull()
                } else null
                val route=direct ?: parent?.route ?: fallback ?: return@call
                if(synchronized(lock){route.hops>=4})return@call
                val depth=if(direct!=null)0 else (parent?.depth ?: 0)+1
                val linked=parent?.route===route
                val sourceTransient=source?.let{transient(it)} ?: (fallback!=null)
                if(!NotificationRoutePolicy.destination(route.identity.packageName,route.creator,info.packageName,
                        route.identity.uid/100000,user,depth,selected(route),direct!=null,linked,sourceTransient,
                        callingUid,route.creatorUid))return@call
                // Activity permissions and notification BAL checks remain native. Only
                // this resolved launch's copied window options change before task selection.
                val original=p.args[1] as? ActivityOptions
                val bundle=original?.toBundle() ?: Bundle()
                val currentDisplay=original?.let{X.callMethod(it,"getLaunchDisplayId") as Int} ?: -1
                if(currentDisplay !in setOf(-1,0)||source?.let{X.callMethod(it,"getDisplayId") as Int}?.let{it>0}==true)return@call
                val c=context() ?: return@call
                if(c.getSystemService(KeyguardManager::class.java).isKeyguardLocked)return@call
                val copy=X.callStaticMethod(ActivityOptions::class.java,"fromBundle",
                    NotificationLaunchOptions.freeform(bundle,route.bounds)) as ActivityOptions
                X.callMethod(copy,"setLaunchDisplayId",0)
                X.findMethodExactIfExists(ActivityOptions::class.java,"setForceLaunchWindowingMode",Integer.TYPE)?.let{
                    it.invoke(copy,5)
                }
                p.args[1]=copy
                synchronized(lock){nodes[target]=Node(route,depth);route.records.add(WeakReference(target));route.hops++}
                Guard.info("notification route hop owner=${route.identity.packageName} user=$user target=${info.packageName} " +
                    "activity=${info.name} depth=$depth sourceLinked=$linked fallback=${fallback!=null} mode=5 bounds=${route.bounds.toShortString()}")
            }}
        })
        // Record the final task result without resizing it after visibility/animation.
        XposedBridge.hookAllMethods(X.findClass("com.android.server.wm.ActivityStarter",loader),"startActivityUnchecked",
            object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){if(!p.hasThrowable())Guard.call("notification-route-result"){
                    if(!hasActiveRoutes())return@call
                    if(!Guard.open("notification-route-result"))return@call
                    val record=p.args.firstOrNull() ?: return@call
                    val node=synchronized(lock){nodes[record]} ?: return@call
                    val task=Guard.field(p.thisObject,"mTargetTask") ?: X.callMethod(record,"getTask") ?: return@call
                    Guard.info("notification route result owner=${node.route.identity.packageName} task=${X.getIntField(task,"mTaskId")} " +
                        "mode=${X.callMethod(task,"getWindowingMode")} bounds=${(X.callMethod(task,"getBounds") as Rect).toShortString()}")
                }}
            })
        Guard.info("notification proxy route hooks ready; native pending send and pre-task launch options")
    }
}
