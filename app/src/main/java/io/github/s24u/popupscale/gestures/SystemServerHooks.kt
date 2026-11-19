package io.github.s24u.popupscale.gestures

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Binder
import android.os.Bundle
import io.github.s24u.popupscale.ModuleSettings
import io.github.s24u.popupscale.PlayerCompatPolicy
import io.github.s24u.popupscale.QQDensityPolicy
import io.github.s24u.popupscale.ExternalScalePolicy
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers as X

/** Per-task mini ownership and trusted HOME/selection handoffs. */
@android.annotation.TargetApi(34)
class SystemServerHooks(private val loader:ClassLoader,private val caps:RomCapabilities) {
    private data class Lease(val task:Int,val user:Int,val session:String,val expires:Long)
    private val leases=java.util.concurrent.ConcurrentHashMap<Int,Lease>()
    private val batch=ThreadLocal<Int>()
    private var registered=false
    @Volatile private var launcherUid=-1
    @Volatile private var systemUiUid=-1
    @Volatile private var appContext:Context?=null
    @Volatile private var dispatcher:Handler?=null
    @Volatile private var atmService:Any?=null
    // Remember an explicit player request even if Samsung ignores physical rotation in freeform.
    private val playerOrientations=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any,Int>())
    private val contentNotifications=mutableMapOf<Int,Runnable>() // dispatcher thread only
    private val layoutConfig=FeatureConfig("system-server-layout")
    private val settingsExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val settingsSyncing=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastProviderAttemptMs=-5000L
    private fun syncSettingsFromProvider(context:Context,force:Boolean=false){
        val now=SystemClock.uptimeMillis()
        if(!force&&(!layoutConfig.needsProviderSync()||now-lastProviderAttemptMs<3000L))return
        if(!settingsSyncing.compareAndSet(false,true))return
        lastProviderAttemptMs=now
        settingsExecutor.execute{
            try{
                Guard.call("server-settings-provider-sync"){
                    val fresh=ModuleSettings.queryProvider(context)
                    if(layoutConfig.applyProviderMap(fresh)){
                        Guard.info("server settings synced from provider enabled=${layoutConfig.enabled} frameless=${layoutConfig.frameless}")
                    }
                }
            }finally{settingsSyncing.set(false)}
        }
    }
    private var cornerGestures:CornerGestureHooks?=null
    @Volatile private var framelessHooks:FramelessWindowHooks?=null
    private fun applyMiniVisual(id:Int,deadlineUptimeMs:Long):Int=Guard.call("server-mini-visual"){
        val hooks=framelessHooks?:return@call 0
        val atm=atmService
        if(atm!=null)synchronized(X.getObjectField(atm,"mGlobalLock")){hooks.setMiniVisual(id,deadlineUptimeMs)}
        else hooks.setMiniVisual(id,deadlineUptimeMs)
    } ?: 0
    private fun pruneMiniVisuals(now:Long)=Guard.call("server-mini-visual-prune"){
        val hooks=framelessHooks?:return@call
        val atm=atmService ?: return@call
        synchronized(X.getObjectField(atm,"mGlobalLock")){hooks.pruneExpiredMiniVisuals(now)}
    }
    private val edgeLayoutTask=ThreadLocal<Int>()
    private var reportedLayoutOptions="" // dispatcher thread only
    private val externalGeometries=java.util.WeakHashMap<Any,String>() // WM global lock
    private val homeScope=ThreadLocal<Boolean>()
    private data class HomeBatch(val token:String,val tasks:LinkedHashSet<Int> = linkedSetOf())
    private data class PendingHome(val token:String,val expires:Long)
    private val homeBatch=ThreadLocal<HomeBatch>()
    private val pendingHome=java.util.concurrent.ConcurrentHashMap<Int,PendingHome>()
    private fun installPlayerDensityCallbacks() {
        val method=caps.densityMethod ?: return
        Guard.call("server-player-density-install") {
            de.robv.android.xposed.XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){Guard.call("server-player-density"){
                        val activity=p.thisObject
                        val info=X.getObjectField(activity,"info") as android.content.pm.ActivityInfo
                        val qq=QQDensityPolicy.configurationActivity(info.packageName,info.name)
                        val douyin=io.github.s24u.popupscale.DouyinDensityPolicy.playerActivity(info.packageName,info.name)
                        if(!qq&&!douyin&&!PlayerCompatPolicy.playerActivity(info.packageName,info.name))return@call
                        val task=X.callMethod(activity,"getTask")?:return@call
                        val user=X.getIntField(task,"mUserId")
                        val display=X.callMethod(task,"getDisplayId") as Int
                        val type=X.callMethod(task,"getActivityType") as Int
                        val mode=X.callMethod(task,"getWindowingMode") as Int
                        val external=externalGeometries.containsKey(activity)
                        val changed=if(qq)layoutConfig.qqDensityConfig(
                            runCatching{X.getLongField(info.applicationInfo,"longVersionCode")}.getOrDefault(0L),
                            info.packageName,info.name,external,user,display,type,mode,info.configChanges)
                        else if(douyin)layoutConfig.douyinDensityConfig(info.packageName,info.name,
                            external,user,display,type,mode,info.configChanges)
                        else layoutConfig.playerDensityConfig(info.packageName,info.name,
                            external,user,display,type,mode,info.configChanges)
                        if(changed==info.configChanges)return@call
                        val current=X.callMethod(activity,"getConfiguration") as android.content.res.Configuration
                        val reported=X.callMethod(X.getObjectField(activity,"mLastReportedConfiguration"),
                            "getMergedConfiguration") as android.content.res.Configuration
                        if(current.densityDpi<=0||current.densityDpi==reported.densityDpi)return@call
                        // Only this native call sees the extra capability. Never mutate a shared
                        // PackageManager ActivityInfo or leave flags on the record after returning.
                        val scoped=android.content.pm.ActivityInfo(info)
                        scoped.configChanges=changed
                        p.setObjectExtra("popupscale.player-density-original",info)
                        p.setObjectExtra("popupscale.player-density-scoped",scoped)
                        X.setObjectField(activity,"info",scoped)
                        Guard.info((if(qq)"QQ/TIM" else if(douyin)"Douyin" else "player")+" density callback enabled task="+X.getIntField(task,"mTaskId")+
                            " dpi="+reported.densityDpi+"->"+current.densityDpi+
                            if(douyin)" activity=${info.name} flags=${Integer.toHexString(info.configChanges)}->${Integer.toHexString(changed)}" else "")
                    }}
                    override fun afterHookedMethod(p:MethodHookParam){Guard.call("server-player-density-restore"){
                        val original=p.getObjectExtra("popupscale.player-density-original")?:return@call
                        val scoped=p.getObjectExtra("popupscale.player-density-scoped")?:return@call
                        if(X.getObjectField(p.thisObject,"info")===scoped)X.setObjectField(p.thisObject,"info",original)
                    }}
                })
        }
    }
    fun install() {
        Guard.call("server-frameless-window-install"){FramelessWindowHooks(loader,layoutConfig).also{it.install();framelessHooks=it}}
        if(caps.cornerGesture)Guard.call("corner-install"){
            val hooks=CornerGestureHooks(loader,caps){task->
                val activity=X.callMethod(task,"topRunningActivityLocked")
                val info=activity?.let{X.getObjectField(it,"info") as android.content.pm.ActivityInfo}
                layoutConfig.enabled&&!layoutConfig.observe&&layoutConfig.handles&&info!=null&&
                    layoutConfig.accepts(info.packageName)&&
                    io.github.s24u.popupscale.CloneUserPolicy.supported(X.getIntField(task,"mUserId"))&&
                    X.callMethod(task,"getDisplayId")==0&&X.callMethod(task,"getActivityType")==1&&
                    X.callMethod(task,"getWindowingMode")==1&&X.getBooleanField(task,"mVisibleRequested")
            }
            hooks.install();cornerGestures=hooks
        }
        if(caps.serverEntry)installInitialPopupLayout()
        if(caps.notificationRoute)Guard.call("notification-route-install"){
            NotificationRouteHooks(loader,caps,layoutConfig,{appContext},{systemUiUid}).install()
        }
        if(caps.serverLease)installHomeMinimize()
        installPlayerDensityCallbacks()
        if(caps.serverScale)Guard.call("server-external-scale-install"){
            X.findAndHookMethod("com.android.server.wm.ActivityRecord",loader,"resolveOverrideConfiguration",
                android.content.res.Configuration::class.java,object:XC_MethodHook(){
                    override fun afterHookedMethod(p:MethodHookParam){
                        if(p.hasThrowable())return
                        Guard.call("server-external-scale"){
                            val activity=p.thisObject
                            val info=X.getObjectField(activity,"info") as android.content.pm.ActivityInfo
                            if(!layoutConfig.externalCandidate(info.packageName))return@call
                            val task=X.callMethod(activity,"getTask") ?: return@call
                            val resolved=X.callMethod(activity,"getResolvedOverrideConfiguration") as android.content.res.Configuration
                            val window=X.getObjectField(resolved,"windowConfiguration")
                            val parent=p.args[0] as android.content.res.Configuration
                            // An unset Activity appBounds inherits its Task's appBounds.
                            val appBounds=(X.callMethod(window,"getAppBounds") as? android.graphics.Rect)
                                ?: (X.callMethod(X.getObjectField(parent,"windowConfiguration"),"getAppBounds") as? android.graphics.Rect)
                                ?: return@call
                            val geometry=layoutConfig.externalScale(info.packageName,X.getIntField(task,"mUserId"),
                                X.callMethod(task,"getDisplayId") as Int,X.callMethod(task,"getActivityType") as Int,
                                X.callMethod(task,"getWindowingMode") as Int,appBounds.width(),appBounds.height(),parent.densityDpi)
                            if(geometry==null){
                                if(externalGeometries.remove(activity)!=null)
                                    Guard.info("external scale native configuration restored package=${info.packageName}")
                                return@call
                            }
                            // Change only this Activity's resolved copy after native calculation.
                            // Task/display density, bounds, min-size policy and requested overrides stay native.
                            val next=android.content.res.Configuration(resolved)
                            next.densityDpi=geometry.densityDpi
                            next.screenWidthDp=geometry.widthDp;next.screenHeightDp=geometry.heightDp
                            next.smallestScreenWidthDp=minOf(geometry.widthDp,geometry.heightDp)
                            X.setIntField(next,"compatScreenWidthDp",next.screenWidthDp)
                            X.setIntField(next,"compatScreenHeightDp",next.screenHeightDp)
                            X.setIntField(next,"compatSmallestScreenWidthDp",next.smallestScreenWidthDp)
                            val layout=X.callStaticMethod(android.content.res.Configuration::class.java,
                                "resetScreenLayout",next.screenLayout) as Int
                            next.screenLayout=X.callStaticMethod(android.content.res.Configuration::class.java,
                                "reduceScreenLayout",layout,maxOf(geometry.widthDp,geometry.heightDp),
                                minOf(geometry.widthDp,geometry.heightDp)) as Int
                            resolved.setTo(next)
                            val state="${appBounds.width()}x${appBounds.height()}/${geometry.densityDpi}"
                            if(externalGeometries.put(activity,state)!=state)
                                Guard.info("external scale package=${info.packageName} task=${X.getIntField(task,"mTaskId")} " +
                                    "activityDpi=${geometry.densityDpi} taskDpi=${parent.densityDpi} dp=${geometry.widthDp}x${geometry.heightDp}")
                        }
                    }
                })
        }
        Guard.call("server-edge-layout-install"){
            X.findAndHookMethod("com.android.server.wm.TaskFragment",loader,"resolveOverrideConfiguration",
                android.content.res.Configuration::class.java,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        p.setObjectExtra("popupscale.edge-layout-previous",edgeLayoutTask.get())
                        // Child TaskFragments/configuration recursion must not inherit a task's exception.
                        edgeLayoutTask.remove()
                        Guard.call("server-edge-layout-scope"){
                            val task=X.callMethod(p.thisObject,"asTask") ?: return@call
                            val display=X.callMethod(task,"getDisplayContent") ?: return@call
                            val screen=X.getObjectField(display,"mDisplayInfo")
                            val activity=X.callMethod(task,"topRunningActivityLocked") ?: return@call
                            val info=X.getObjectField(activity,"info") as android.content.pm.ActivityInfo
                            if(layoutConfig.allowsEdgeTop(info.packageName,
                                X.getIntField(task,"mUserId"),X.callMethod(task,"getDisplayId") as Int,
                                X.callMethod(task,"getActivityType") as Int,X.callMethod(task,"getWindowingMode") as Int,
                                X.getIntField(screen,"logicalWidth"),X.getIntField(screen,"logicalHeight")))
                                edgeLayoutTask.set(X.getIntField(task,"mTaskId"))
                        }
                    }
                    override fun afterHookedMethod(p:MethodHookParam){
                        val previous=p.getObjectExtra("popupscale.edge-layout-previous") as? Int
                        if(previous==null)edgeLayoutTask.remove() else edgeLayoutTask.set(previous)
                    }
                })
            X.findAndHookMethod("com.android.server.wm.WindowManagerService",loader,"getOverrideStableInsetsLocked",
                Int::class.javaPrimitiveType,android.graphics.Rect::class.java,object:XC_MethodHook(){
                    override fun afterHookedMethod(p:MethodHookParam){
                        val id=edgeLayoutTask.get() ?: return
                        if(p.hasThrowable()||p.args[0]!=0)return
                        Guard.call("server-edge-stable-insets"){
                            val insets=p.args[1] as android.graphics.Rect
                            if(insets.top>0){
                                val old=insets.top;insets.top=0
                                Guard.info("freeform top reserve omitted task=$id inset=$old")
                            }
                        }
                    }
                })
        }
        Guard.call("server-player-orientation-install"){
            X.findAndHookMethod("com.android.server.wm.ActivityRecord",loader,"setRequestedOrientation",
                Int::class.javaPrimitiveType,object:XC_MethodHook(){
                    override fun afterHookedMethod(p:MethodHookParam){Guard.call("server-player-orientation"){
                        if(p.hasThrowable())return@call
                        val activity=p.thisObject
                        val info=X.getObjectField(activity,"info") as android.content.pm.ActivityInfo
                        if(!PlayerCompatPolicy.playerActivity(info.packageName,info.name))return@call
                        val task=X.callMethod(activity,"getTask") ?: return@call
                        if(X.callMethod(task,"getWindowingMode")!=5){playerOrientations.remove(activity);return@call}
                        if(!io.github.s24u.popupscale.CloneUserPolicy.supported(X.getIntField(task,"mUserId"))||X.callMethod(task,"getDisplayId")!=0||
                            X.callMethod(task,"getActivityType")!=1||X.callMethod(task,"topRunningActivityLocked")!==activity)return@call
                        val requested=p.args[0] as Int
                        if(playerOrientations.put(activity,requested)==requested)return@call
                        val id=X.getIntField(task,"mTaskId")
                        Guard.info("player orientation task=$id requested=$requested")
                        notifyContentChanged(id)
                    }}
                })
        }
        Guard.call("server-recents-install"){
            X.findAndHookMethod("com.android.server.wm.ActivityTaskManagerService",loader,
                "startActivityFromRecents",Int::class.javaPrimitiveType,Bundle::class.java,
                object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){Guard.call("server-recents"){
                        val current=leases[p.args[0] as Int]?:return@call
                        if(MiniLeasePolicy.selected(current.task,current.user,current.expires,SystemClock.uptimeMillis(),
                            p.args[0] as Int,Binder.getCallingUid(),launcherUid,systemUiUid))
                            p.setObjectExtra("popupscale.selected",current)
                    }}
                    override fun afterHookedMethod(p:MethodHookParam){
                        val selected=p.getObjectExtra("popupscale.selected") as? Lease?:return
                        if(p.hasThrowable()||(p.result as? Int ?: -1)<0)return
                        // Keep native permission checks/results and remote-animation completion intact.
                        // Notify from the main thread, outside the Binder call and WM locks.
                        notifySelected(selected,"Recents")
                    }
                })
        }
        Guard.call("server-launcher-install"){
            X.findAndHookMethod("com.android.server.wm.ActivityStarter",loader,"executeRequest",
                X.findClass("com.android.server.wm.ActivityStarter\$Request",loader),object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){Guard.call("server-launcher-select"){
                        if(leases.isEmpty())return@call
                        val request=p.args[0]
                        val intent=Guard.field(request,"intent") as? Intent ?: return@call
                        if(intent.action!=Intent.ACTION_MAIN||!intent.hasCategory(Intent.CATEGORY_LAUNCHER))return@call
                        p.setObjectExtra("popupscale.launcher",true)
                    }}
                    override fun afterHookedMethod(p:MethodHookParam){Guard.call("server-launcher-result"){
                        if(p.getObjectExtra("popupscale.launcher")!=true)return@call
                        if(p.hasThrowable())return@call
                        val result=p.result as? Int ?: return@call
                        // executeRequest resolves the originating application thread to this UID.
                        // realCallingUid may instead be system_server for LauncherApps IPC.
                        val caller=X.getIntField(p.thisObject,"mCallingUid")
                        val record=Guard.field(p.thisObject,"mLastStartActivityRecord")
                        val task=record?.let{X.callMethod(it,"getTask")}
                            ?: Guard.field(p.thisObject,"mTargetTask") ?: return@call
                        val selected=leases[X.getIntField(task,"mTaskId")] ?: return@call
                        if(MiniLeasePolicy.launcherSelected(selected.task,selected.user,selected.expires,
                            SystemClock.uptimeMillis(),X.getIntField(task,"mTaskId"),X.getIntField(task,"mUserId"),
                            X.callMethod(task,"getDisplayId") as Int,X.callMethod(task,"getWindowingMode") as Int,
                            caller,launcherUid,result,true))
                            notifySelected(selected,"Launcher")
                    }}
                })
        }
        X.findAndHookMethod("com.android.server.wm.ActivityTaskManagerService",loader,"onSystemReady",
            object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    atmService=p.thisObject
                    val c=Guard.call("server-context"){X.getObjectField(p.thisObject,"mContext") as Context}?:return
                    Handler(Looper.getMainLooper()).post{Guard.call("server-receiver"){register(c)}}
                }
            })
        if(caps.serverLease){
        de.robv.android.xposed.XposedBridge.hookMethod(checkNotNull(caps.minimizeAllMethod),
            object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    p.setObjectExtra("popupscale.depth",batch.get()?:0)
                    batch.set((batch.get()?:0)+1)
                    if(batch.get()==1&&homeScope.get()==true)homeBatch.set(HomeBatch(java.util.UUID.randomUUID().toString()))
                }
                override fun afterHookedMethod(p:MethodHookParam){
                    val previous=p.getObjectExtra("popupscale.depth") as? Int?:0
                    if(previous==0){val request=homeBatch.get();homeBatch.remove();if(request!=null)Guard.call("home-mini-dispatch"){sendHomeMinis(request)}}
                    if(previous==0)batch.remove() else batch.set(previous)
                }
            })
        X.findAndHookMethod("com.android.server.wm.Task",loader,"canMinimize",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if((batch.get()?:0)==0||!Guard.open("server-mini-filter"))return
                Guard.call("server-mini-filter"){
                    val task=p.thisObject
                    val id=X.getIntField(task,"mTaskId")
                    val atm=X.getObjectField(task,"mAtmService")
                    val root=X.getObjectField(atm,"mRootWindowContainer")
                    val pending=pendingHome[id]
                    if(pending!=null&&MiniLeasePolicy.protects(id,X.getIntField(task,"mUserId"),pending.expires,SystemClock.uptimeMillis(),
                        id,X.getIntField(task,"mUserId"),X.getIntField(root,"mCurrentUser"),
                        X.callMethod(task,"getDisplayId") as Int,X.callMethod(task,"getWindowingMode") as Int,true)){
                        p.result=false;return@call
                    }
                    val current=leases[id] ?: return@call
                    if(MiniLeasePolicy.protects(current.task,current.user,current.expires,SystemClock.uptimeMillis(),
                        X.getIntField(task,"mTaskId"),X.getIntField(task,"mUserId"),X.getIntField(root,"mCurrentUser"),
                        X.callMethod(task,"getDisplayId") as Int,X.callMethod(task,"getWindowingMode") as Int,true)) {
                        p.result=false
                        Guard.info("minimize-all preserved live task=${current.task}")
                    }
                }
            }
        })
        }
        Guard.info("server capabilities scale=${caps.serverScale} entry=${caps.serverEntry} miniProtection=${caps.serverLease}")
    }
    private fun installHomeMinimize(){
        fun origin(type:String,method:String,launcherOnly:Boolean){
            Guard.call("home-origin-install-$method"){
                de.robv.android.xposed.XposedBridge.hookAllMethods(X.findClass(type,loader),method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        p.setObjectExtra("popupscale.home-origin",homeScope.get())
                        if(!launcherOnly||Binder.getCallingUid()==launcherUid)homeScope.set(true)
                    }
                    override fun afterHookedMethod(p:MethodHookParam){
                        val previous=p.getObjectExtra("popupscale.home-origin") as? Boolean
                        if(previous==null)homeScope.remove() else homeScope.set(previous)
                    }
                })
            }
        }
        origin("com.android.server.wm.RootWindowContainer","startHomeOnTaskDisplayArea",false)
        origin("com.android.server.wm.MultiTaskingBinder","minimizeAllTasks",true)
        Guard.call("home-minimize-install"){
            X.findAndHookMethod(checkNotNull(caps.serverCore),loader,"minimizeTaskLocked",
                Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,X.findClass("com.android.server.wm.Task",loader),
                Boolean::class.javaPrimitiveType,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        val request=homeBatch.get() ?: return
                        if((batch.get() ?: 0)<=0||homeScope.get()!=true)return
                        Guard.call("home-minimize-intercept"){
                            val task=p.args[2] ?: return@call
                            val id=X.getIntField(task,"mTaskId");val now=SystemClock.uptimeMillis()
                            val root=X.getObjectField(X.getObjectField(task,"mAtmService"),"mRootWindowContainer")
                            val expiry=leases[id]?.expires ?: pendingHome[id]?.expires ?: -1
                            if(MiniLeasePolicy.protects(id,X.getIntField(task,"mUserId"),expiry,now,id,X.getIntField(task,"mUserId"),
                                X.getIntField(root,"mCurrentUser"),X.callMethod(task,"getDisplayId") as Int,
                                X.callMethod(task,"getWindowingMode") as Int,true)){p.result=true;return@call}
                            if(!homeCandidate(task)||X.callMethod(task,"canMinimize")!=true)return@call
                            request.tasks.add(id)
                            pendingHome[id]=PendingHome(request.token,now+2500)
                            p.result=true // no native minimized flag, icon, cancelAnimation or move-to-back
                        }
                    }
                })
        }
    }
    private fun homeCandidate(task:Any):Boolean {
        if(!SystemUiReadiness.ready())return false
        val atm=X.getObjectField(task,"mAtmService")
        val root=X.getObjectField(atm,"mRootWindowContainer")
        val activity=X.callMethod(task,"topRunningActivityLocked") ?: return false
        val info=X.getObjectField(activity,"info") as android.content.pm.ActivityInfo
        val unlocked=X.callMethod(X.getObjectField(atm,"mKeyguardController"),"isKeyguardLocked",0)!=true
        return MiniLeasePolicy.homeEligible(layoutConfig.enabled,layoutConfig.observe,layoutConfig.live,
            layoutConfig.hang,layoutConfig.accepts(info.packageName),X.getIntField(task,"mUserId"),
            X.getIntField(root,"mCurrentUser"),X.callMethod(task,"getDisplayId") as Int,
            X.callMethod(task,"getActivityType") as Int,X.callMethod(task,"getWindowingMode") as Int,
            X.getBooleanField(task,"mVisibleRequested"),unlocked)
    }
    private fun sendHomeMinis(request:HomeBatch){
        if(request.tasks.isEmpty())return
        dispatcher?.post{Guard.call("home-mini-send"){
            val c=appContext ?: return@call
            c.sendBroadcast(Intent(MiniTaskLease.HOME_HANG).setPackage(RomProfile.SYSTEM_UI)
                .putExtra("tasks",request.tasks.toIntArray()).putExtra("token",request.token)
                .putExtra("when",SystemClock.uptimeMillis()),MiniTaskLease.PERMISSION,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            Guard.info("HOME mini handoff tasks=${request.tasks.joinToString(",")}")
        }}
        dispatcher?.postDelayed({request.tasks.forEach{id->
            pendingHome[id]?.takeIf{it.token==request.token}?.let{fallbackHome(id,it)}
        }},2600)
    }
    private fun fallbackHome(id:Int,pending:PendingHome){
        // Runs on dispatcher, outside the original HOME call and its WM lock.
        if(!pendingHome.remove(id,pending))return
        if(leases[id]?.expires?.let{it>SystemClock.uptimeMillis()}==true)return
        Guard.call("home-mini-fallback"){
            val atm=atmService ?: return@call
            synchronized(X.getObjectField(atm,"mGlobalLock")){
                val root=X.getObjectField(atm,"mRootWindowContainer")
                val task=X.callMethod(root,"anyTaskForId",id,0,null,false) ?: return@synchronized
                if(!io.github.s24u.popupscale.CloneUserPolicy.supported(X.getIntField(task,"mUserId"))||X.callMethod(task,"getDisplayId")!=0||
                    X.callMethod(task,"getWindowingMode")!=5||!X.getBooleanField(task,"mVisibleRequested"))return@synchronized
                val core=Guard.field(atm,"mMultiTaskingCore","mMultiTaskingController") ?: return@synchronized
                X.callMethod(core,"minimizeTaskLocked",-1,-1,task,false)
                Guard.info("HOME mini handoff unavailable; native minimize restored task=$id")
            }
        }
    }
    private fun installInitialPopupLayout(){
        // Native corner/Recents conversion supplies its own WCT bounds AFTER changing
        // Task mode. Normalize that very change, not a second resize after Shell idle.
        if(caps.popupChangeBounds)Guard.call("server-popup-change-install"){
            val method=checkNotNull(caps.popupChangeMethod)
            val taskType=X.findClass("com.android.server.wm.Task",loader)
            val boundsChange=PopupBoundsChange(method.parameterTypes[1])
            de.robv.android.xposed.XposedBridge.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){Guard.call("server-popup-change"){
                    val task=p.args[0]?:return@call
                    if(!taskType.isInstance(task))return@call // generic applyChanges also receives display/embedded containers
                    val change=p.args[1]?:return@call
                    val requested=X.callMethod(change,"getWindowingMode") as Int
                    val current=X.callMethod(task,"getWindowingMode") as Int
                    if(!FrameworkLayoutPolicy.organizerEntry(current,requested))return@call
                    val activity=X.callMethod(task,"topRunningActivityLocked")?:return@call
                    val display=X.callMethod(task,"getDisplayContent")?:return@call
                    val bounds=initialPortraitBounds(task,activity,display,5,true)?:return@call
                    if(boundsChange.apply(change,bounds))Guard.info(
                        "portrait popup transaction task=${X.getIntField(task,"mTaskId")} bounds=${bounds.toShortString()} beforeApply=true")
                }}
            })
            Guard.info("portrait popup native transaction callback ready: $method")
        }
        Guard.call("server-popup-launch-install"){
            de.robv.android.xposed.XposedBridge.hookAllMethods(X.findClass("com.android.server.wm.LaunchParamsController",loader),
                "calculate",object:XC_MethodHook(){
                    override fun afterHookedMethod(p:MethodHookParam){
                        if(p.hasThrowable()||p.args.size!=8||p.args[6]!=3)return
                        Guard.call("server-popup-launch"){
                            val out=p.args[7] ?: return@call
                            val task=p.args[0]
                            val activity=p.args[2] ?: task?.let{X.callMethod(it,"topRunningActivityLocked")} ?: return@call
                            val options=p.args[4]
                            val mode=FrameworkLayoutPolicy.launchMode(X.getIntField(out,"mWindowingMode"),
                                options?.let{X.callMethod(it,"getLaunchWindowingMode") as Int} ?: 0,
                                task?.let{X.callMethod(it,"getWindowingMode") as Int} ?: 0)
                            if(mode!=5)return@call
                            val area=Guard.field(out,"mPreferredTaskDisplayArea")
                            val display=area?.let{X.callMethod(it,"getDisplayContent")}
                                ?: task?.let{X.callMethod(it,"getDisplayContent")}
                                ?: X.callMethod(activity,"getDisplayContent") ?: return@call
                            val currentBounds=task?.let{X.callMethod(it,"getBounds") as android.graphics.Rect}
                            val entering=FrameworkLayoutPolicy.popupEntryRequest(task!=null,
                                task?.let{X.callMethod(it,"getWindowingMode") as Int} ?: 0,
                                options?.let{X.callMethod(it,"getLaunchWindowingMode") as Int} ?: 0,
                                currentBounds?.let{it.width()<it.height()} ?: false)
                            val bounds=initialPortraitBounds(task,activity,display,mode,entering) ?: return@call
                            (X.getObjectField(out,"mBounds") as android.graphics.Rect).set(bounds)
                            Guard.info("portrait popup launch bounds=${bounds.toShortString()} beforeVisibility=true")
                        }
                    }
                })
        }
        Guard.call("server-popup-visible-install"){
            X.findAndHookMethod("com.android.server.wm.ActivityRecord",loader,"setVisibility",Boolean::class.javaPrimitiveType,
                object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        if(p.args[0]!=true)return
                        Guard.call("server-popup-visible"){
                            val activity=p.thisObject
                            if(X.getBooleanField(activity,"mVisibleRequested"))return@call
                            val task=X.callMethod(activity,"getTask") ?: return@call
                            if(X.getBooleanField(task,"mVisibleRequested"))return@call // new Activity in an existing popup
                            val bounds=X.callMethod(task,"getBounds") as android.graphics.Rect
                            // A back navigation can transiently hide every Activity in this task.
                            // Visibility alone is not a new popup: only repair malformed remembered shape here.
                            if(bounds.isEmpty||bounds.width()<bounds.height())return@call
                            applyInitialPortraitBounds(task,activity,"visibility")
                        }
                    }
                })
        }
        Guard.call("server-popup-mode-install"){
            val method=caps.modeMethod?:return@call
            val taskType=X.findClass("com.android.server.wm.Task",loader)
            de.robv.android.xposed.XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        if(!taskType.isInstance(p.thisObject))return
                        if(p.args[0]==5&&Guard.call("server-popup-old-mode"){X.callMethod(p.thisObject,"getWindowingMode")!=5}==true)
                            p.setObjectExtra("popupscale.entering",true)
                    }
                    override fun afterHookedMethod(p:MethodHookParam){
                        if(p.hasThrowable()||p.getObjectExtra("popupscale.entering")!=true)return
                        Guard.call("server-popup-mode"){
                            val activity=X.callMethod(p.thisObject,"topRunningActivityLocked") ?: return@call
                            applyInitialPortraitBounds(p.thisObject,activity,"mode")
                        }
                    }
                })
        }
    }
    private fun applyInitialPortraitBounds(task:Any,activity:Any,source:String){
        val display=X.callMethod(task,"getDisplayContent") ?: return
        val bounds=initialPortraitBounds(task,activity,display,X.callMethod(task,"getWindowingMode") as Int,true) ?: return
        if(X.callMethod(task,"getBounds")==bounds)return
        X.callMethod(task,"setBounds",bounds)
        Guard.info("portrait popup $source task=${X.getIntField(task,"mTaskId")} bounds=${bounds.toShortString()} beforeVisibility=true")
    }
    private fun initialPortraitBounds(task:Any?,activity:Any,display:Any,mode:Int,newEntry:Boolean):android.graphics.Rect? {
        if(task!=null&&cornerGestures?.owns(task)==true)return null
        val info=X.getObjectField(activity,"info") as android.content.pm.ActivityInfo
        val screen=X.getObjectField(display,"mDisplayInfo")
        val width=X.getIntField(screen,"logicalWidth");val height=X.getIntField(screen,"logicalHeight")
        if(!newEntry||mode!=5||width<=0||height<=width||!layoutConfig.enabled||layoutConfig.observe||
            !layoutConfig.handles||!layoutConfig.accepts(info.packageName))return null
        val id=task?.let{X.getIntField(it,"mTaskId")} ?: -1
        val current=leases[id]
        val owned=current!=null&&current.task==id&&current.expires>SystemClock.uptimeMillis()
        val wide=if(task!=null)contentProfileFor(task)?.getBoolean("wide")==true else {
            @Suppress("DEPRECATION")
            val game=info.applicationInfo.category==android.content.pm.ApplicationInfo.CATEGORY_GAME||
                info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME!=0
            PlayerCompatPolicy.explicitWide(info.packageName,info.name,mode,info.screenOrientation)
                ?: (game&&info.screenOrientation in setOf(0,6,8,11))
        }
        if(!layoutConfig.portraitEntry(info.packageName,X.getIntField(activity,"mUserId"),
            X.getIntField(display,"mDisplayId"),
            task?.let{X.callMethod(it,"getActivityType") as Int} ?: 1,mode,width,height,newEntry,wide,owned))return null
        val fit=LivePlacement.standardWindow(width,height) ?: return null
        return android.graphics.Rect(fit.x,fit.y,fit.x+fit.width,fit.y+fit.height)
    }
    private fun notifyContentChanged(id:Int){
        val handler=dispatcher ?: return
        handler.post{
            contentNotifications.remove(id)?.let{handler.removeCallbacks(it)}
            val pending=Runnable{Guard.call("server-player-notify"){
                contentNotifications.remove(id)
                val c=appContext ?: return@call
                // Query and send outside the requesting Binder call and its WM locks.
                if(contentProfile(id)==null)return@call
                c.sendBroadcast(Intent(MiniTaskLease.CONTENT_CHANGED).setPackage(RomProfile.SYSTEM_UI)
                    .putExtra("task",id).putExtra("when",SystemClock.uptimeMillis()),MiniTaskLease.PERMISSION,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            }}
            contentNotifications[id]=pending;handler.postDelayed(pending,120)
        }
    }
    private fun notifySelected(selected:Lease,source:String){
        // No broadcast or window mutation under the launcher's Binder call / WM locks.
        dispatcher?.post{Guard.call("server-selection-notify"){
            val current=leases[selected.task] ?: return@call
            val c=appContext ?: return@call
            val now=SystemClock.uptimeMillis()
            if(current.task!=selected.task||current.session!=selected.session||current.expires<=now)return@call
            c.sendBroadcast(Intent(MiniTaskLease.SELECTED).setPackage(RomProfile.SYSTEM_UI)
                .putExtra("task",current.task).putExtra("session",current.session).putExtra("when",now)
                .putExtra("source",source),
                MiniTaskLease.PERMISSION,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            Guard.info("mini selected from $source task=${current.task}")
        }}
    }
    private fun register(context:Context) {
        if(registered)return
        systemUiUid=context.packageManager.getApplicationInfo(RomProfile.SYSTEM_UI,0).uid
        launcherUid=runCatching{context.packageManager.getApplicationInfo(RomProfile.LAUNCHER,0).uid}.getOrDefault(-1)
        appContext=context;dispatcher=Handler(Looper.getMainLooper())
        // Read shared preferences outside WM's global lock; hooks only inspect the cached selection.
        dispatcher?.post(object:Runnable{
            override fun run(){
                Guard.call("server-layout-options"){
                    io.github.s24u.popupscale.CloneProfileRegistry.refresh(context)
                    layoutConfig.reload()
                    if(layoutConfig.needsProviderSync())syncSettingsFromProvider(context)
                    val state="${layoutConfig.enabled}/${layoutConfig.observe}/${layoutConfig.unifiedMode()}"
                    if(state!=reportedLayoutOptions){
                        reportedLayoutOptions=state
                        Guard.info("server layout options enabled=${layoutConfig.enabled} observe=${layoutConfig.observe} unifiedScale=${layoutConfig.unifiedMode()}")

                    }
                }
                Guard.call("server-lease-prune"){
                    val now=SystemClock.uptimeMillis()
                    leases.entries.filter{it.value.expires<=now}.forEach{leases.remove(it.key,it.value)}
                    pruneMiniVisuals(now)
                }
                dispatcher?.postDelayed(this,1000)
            }
        })
        context.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(c:Context,intent:Intent){Guard.call("server-lease"){
                // Samsung assigns SystemUI an application UID on this firmware.
                // Verify the installed package identity, never assume the system_server UID.
                if(sentFromUid!=systemUiUid)return@call
                val now=SystemClock.uptimeMillis();val sent=intent.getLongExtra("when",-10000)
                if(now-sent !in 0..3000)return@call
                if(intent.action==SystemUiReadiness.ACTION){SystemUiReadiness.accept(intent);return@call}
                if(intent.action==MiniTaskLease.HOME_REPLY){
                    val token=intent.getStringExtra("token") ?: return@call
                    val accepted=intent.getIntArrayExtra("tasks")?.toSet() ?: emptySet()
                    pendingHome.entries.filter{it.value.token==token&&it.key !in accepted}.forEach{fallbackHome(it.key,it.value)}
                    return@call
                }
                if(intent.action==MiniTaskLease.CONTENT_PROFILE){
                    val reply=intent.getParcelableExtra("reply",android.os.ResultReceiver::class.java) ?: return@call
                    val result=Guard.call("server-content-profile"){contentProfile(intent.getIntExtra("task",-1))}
                    reply.send(if(result!=null)1 else 0,result)
                    return@call
                }
                if(!caps.serverLease)return@call // Never acknowledge a mini without native HOME protection.
                val session=intent.getStringExtra("session")?:return@call
                if(session.length !in 20..80)return@call
                val id=intent.getIntExtra("task",-1)
                if(id<0){
                    val removed=leases.entries.filter{it.value.session==session}.mapNotNull{entry->
                        if(leases.remove(entry.key,entry.value))entry.key else null
                    }
                    val endVisual=intent.getIntExtra("endVisualTask",-1)
                    val keepVisual=intent.getIntExtra("keepVisualTask",-1)
                    if(endVisual>=0)applyMiniVisual(endVisual,0L)
                    else if(keepVisual>=0)applyMiniVisual(keepVisual,now+400L)
                    else removed.forEach{applyMiniVisual(it,0L)}
                    return@call
                }
                val user=intent.getIntExtra("user",-1)
                val identity=leaseTaskIdentity(id)
                val preparing=intent.getBooleanExtra("preparingFullscreen",false)
                if(identity==null||!MiniLeasePolicy.acquirable(user,identity.user,identity.display,identity.type,identity.mode,preparing)){
                    Guard.info("mini protection rejected task=$id user=$user actualUser=${identity?.user} " +
                        "display=${identity?.display} type=${identity?.type} mode=${identity?.mode} preparing=$preparing")
                    return@call
                }
                val first=leases[id]?.session!=session
                leases[id]=Lease(id,user,session,now+MiniLeasePolicy.TTL)
                val framelessCaption=applyMiniVisual(id,now+MiniLeasePolicy.TTL)
                if(first)Guard.info("mini protection granted task=$id user=$user mode=${identity.mode} preparing=$preparing")
                pendingHome.remove(id)
                context.sendBroadcast(Intent(MiniTaskLease.ACK).setPackage(RomProfile.SYSTEM_UI)
                    .putExtra("task",id).putExtra("session",session).putExtra("when",now).putExtra("layer",nativeTaskLayer(id))
                    .putExtra("mode",identity.mode).putExtra("framelessCaption",framelessCaption),
                    MiniTaskLease.PERMISSION,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            }}
        },IntentFilter(MiniTaskLease.ACTION).apply{addAction(MiniTaskLease.CONTENT_PROFILE);addAction(MiniTaskLease.HOME_REPLY);addAction(SystemUiReadiness.ACTION)},MiniTaskLease.PERMISSION,
            Handler(Looper.getMainLooper()),Context.RECEIVER_EXPORTED)
        runCatching {
            syncSettingsFromProvider(context, force=true)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    Guard.call("server-settings-unlocked") {
                        layoutConfig.invalidateProvider()
                        syncSettingsFromProvider(context, force=true)
                    }
                }
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED), null, Handler(Looper.getMainLooper()), Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    Guard.call("server-settings-changed") {
                        intent.getBundleExtra("settings")?.let { layoutConfig.applyBundle(it) }
                        layoutConfig.invalidateProvider(); syncSettingsFromProvider(context, force=true)
                        Guard.info("server settings live updated via broadcast enabled=${layoutConfig.enabled}")
                    }
                }
            }, IntentFilter(ModuleSettings.ACTION_SETTINGS_CHANGED), null, Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
        }
        registered=true
        Guard.info("server mini lease receiver ready systemUiUid=$systemUiUid")
    }
    private data class LeaseTaskIdentity(val user:Int,val display:Int,val type:Int,val mode:Int)
    private fun leaseTaskIdentity(id:Int):LeaseTaskIdentity?=Guard.call("mini-task-identity"){
        val atm=atmService ?: return@call null
        synchronized(X.getObjectField(atm,"mGlobalLock")){
            val task=X.callMethod(X.getObjectField(atm,"mRootWindowContainer"),"anyTaskForId",id,0,null,false)
                ?: return@synchronized null
            LeaseTaskIdentity(X.getIntField(task,"mUserId"),X.callMethod(task,"getDisplayId") as Int,
                X.callMethod(task,"getActivityType") as Int,X.callMethod(task,"getWindowingMode") as Int)
        }
    }
    private fun nativeTaskLayer(id:Int):Int=Guard.call("mini-native-layer"){
        val atm=atmService ?: return@call -1
        synchronized(X.getObjectField(atm,"mGlobalLock")){
            val task=X.callMethod(X.getObjectField(atm,"mRootWindowContainer"),"anyTaskForId",id,0,null,false)
                ?: return@synchronized -1
            if(!io.github.s24u.popupscale.CloneUserPolicy.supported(X.getIntField(task,"mUserId"))||X.callMethod(task,"getDisplayId")!=0||
                X.callMethod(task,"getWindowingMode")!=5)return@synchronized -1
            X.getIntField(task,"mLastLayer")
        }
    } ?: -1
    @Suppress("DEPRECATION")
    private fun contentProfile(id:Int):Bundle? {
        val atm=atmService ?: return null
        return synchronized(X.getObjectField(atm,"mGlobalLock")){
            val root=X.getObjectField(atm,"mRootWindowContainer")
            // Attached tasks only: a profile read must never restore a task from Recents.
            val task=X.callMethod(root,"anyTaskForId",id,0,null,false) ?: return@synchronized null
            if(!io.github.s24u.popupscale.CloneUserPolicy.supported(X.getIntField(task,"mUserId"))||X.callMethod(task,"getDisplayId")!=0||
                X.callMethod(task,"getActivityType")!=1||X.callMethod(task,"getWindowingMode") !in setOf(1,5))return@synchronized null
            contentProfileFor(task)
        }
    }
    @Suppress("DEPRECATION")
    private fun contentProfileFor(task:Any):Bundle? {
            val id=X.getIntField(task,"mTaskId")
            val activity=X.callMethod(task,"topRunningActivityLocked") ?: return null
            val info=X.getObjectField(activity,"info") as android.content.pm.ActivityInfo
            val app=info.applicationInfo
            val game=app.category==android.content.pm.ApplicationInfo.CATEGORY_GAME||
                app.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME!=0
            val mode=X.callMethod(task,"getWindowingMode") as Int
            val requested=if(mode==5)playerOrientations[activity] ?: X.callMethod(activity,"getRequestedOrientation") as Int
                else X.callMethod(activity,"getRequestedOrientation") as Int
            val requestsLandscape=requested in setOf(0,6,8,11)
            val window=X.callMethod(activity,"findMainWindow",false)
            val hidden=window?.let{
                val types=X.callMethod(it,"getRequestedVisibleTypes") as Int
                val attrs=X.getObjectField(it,"mAttrs") as android.view.WindowManager.LayoutParams
                types and android.view.WindowInsets.Type.statusBars()==0||
                    attrs.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN!=0
            } ?: false
            val bounds=X.callMethod(task,"getBounds") as android.graphics.Rect
            if(bounds.isEmpty)return null
            val wide=PlayerCompatPolicy.explicitWide(info.packageName,info.name,mode,requested)
                ?: LivePlacement.preserveLandscape(bounds.width(),bounds.height(),game,hidden,requestsLandscape)
            val aspect=if(bounds.width()>bounds.height())bounds.width().toFloat()/bounds.height() else 16f/9f
            val corner=cornerGestures?.owns(task)==true
            val screen=X.getObjectField(X.callMethod(task,"getDisplayContent"),"mDisplayInfo")
            return Bundle().apply{
                putInt("task",id);putBoolean("wide",wide);putFloat("aspect",aspect.coerceIn(1.05f,4f))
                putBoolean("cornerEntry",corner)
                putInt("cornerDisplayWidth",X.getIntField(screen,"logicalWidth"))
                putInt("cornerDisplayHeight",X.getIntField(screen,"logicalHeight"))
            }
    }
}
