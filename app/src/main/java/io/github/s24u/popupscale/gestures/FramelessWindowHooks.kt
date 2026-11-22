package io.github.s24u.popupscale.gestures

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Insets
import android.graphics.Rect
import android.view.SurfaceControl
import android.view.WindowInsets
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X

/** Hide only the caption-filled pixels of a base app window. The native caption is a task
 * decoration sibling, so it remains above the content with its original input coordinates.
 * A verified direct Activity container adds a server-owned crop: clients can reset
 * WindowState's shared surface from ViewRootImpl.relayoutWindow. Neither task nor
 * temporary animation leashes, Insets, layout, alpha or position are changed here.
 */
@android.annotation.TargetApi(30)
class FramelessWindowHooks(private val loader:ClassLoader,private val config:FeatureConfig) {
    private data class Owned(val surface:Any,val crop:Rect,val radius:Float,val caption:Int,
                             val taskId:Int,var zeroSince:Long=0L)
    private val stateLock=Any()
    private val owned=java.util.WeakHashMap<Any,Owned>() // Guarded by stateLock.
    private data class RootCrop(val window:java.lang.ref.WeakReference<Any>,val surface:Any,
                                val crop:Rect,val radius:Float,val taskId:Int,val caption:Int)
    private val roots=java.util.WeakHashMap<Any,RootCrop>() // Guarded by stateLock.
    private val miniDeadlines=java.util.concurrent.ConcurrentHashMap<Int,Long>()
    private var radiusMethod:java.lang.reflect.Method?=null
    private var insetsMethod:java.lang.reflect.Method?=null
    private var insetsReader:CaptionInsetsReader?=null
    private data class ResizeTarget(val binder:Any,val bounds:Rect,val configMask:Int,val windowMask:Int,val mode:Int)
    private val resizeHolds=ResizeSyncHold()
    private val pendingResizeTargets=ThreadLocal<List<ResizeTarget>>()
    private val pendingStartedSync=ThreadLocal<Int>()
    private val readingRawInsets=ThreadLocal<Boolean>()
    private var windowContainerClass:Class<*>?=null
    private var reports=0
    private var syncReports=0
    private var strippedCaptionReports=0

    private data class Target(val window:Any,val attrs:WindowManager.LayoutParams,val activity:Any,
                              val info:ActivityInfo,val task:Any)

    private fun eligibleTarget(window:Any):Target? {
        val attrs=X.getObjectField(window,"mAttrs") as WindowManager.LayoutParams
        if(attrs.type!=WindowManager.LayoutParams.TYPE_BASE_APPLICATION)return null
        val activity=Guard.field(window,"mActivityRecord")?:return null
        val info=X.getObjectField(activity,"info") as ActivityInfo
        val task=X.callMethod(window,"getTask")?:return null
        val configuration=X.callMethod(task,"getConfiguration")?:return null
        // Samsung's native Configuration ABI marks on-device DeX too (display can be 0).
        if((Guard.field(configuration,"semDesktopModeEnabled") as? Int)==1)return null
        if(!config.acceptsInteraction(info.packageName)||X.callMethod(task,"getWindowingMode")!=5||
            X.callMethod(task,"getActivityType")!=1||X.callMethod(task,"getDisplayId")!=0||
            !io.github.s24u.popupscale.CloneUserPolicy.supported(X.getIntField(task,"mUserId")))return null
        return Target(window,attrs,activity,info,task)
    }

    private fun readRawInsets(method:java.lang.reflect.Method,window:Any):Any {
        readingRawInsets.set(true)
        return try {
            if(method.parameterCount==0)method.invoke(window) else method.invoke(window,false)
        } finally {
            readingRawInsets.remove()
        }
    }

    private fun snapshotOwned():List<Pair<Any,Owned>> = synchronized(stateLock){
        val out=ArrayList<Pair<Any,Owned>>()
        val it=owned.entries.iterator()
        while(it.hasNext()){
            val e=it.next()
            val k=e.key ?: continue
            out.add(k to e.value)
        }
        out
    }

    private fun snapshotRoots():List<RootCrop> = synchronized(stateLock){
        val out=ArrayList<RootCrop>()
        val it=roots.values.iterator()
        while(it.hasNext())out.add(it.next())
        out
    }

    private fun hasActiveCrop(taskId:Int):Boolean = synchronized(stateLock){
        val ownedIt=owned.values.iterator()
        while(ownedIt.hasNext())if(ownedIt.next().taskId==taskId)return true
        val rootsIt=roots.values.iterator()
        while(rootsIt.hasNext())if(rootsIt.next().taskId==taskId)return true
        false
    }

    private fun isMiniVisual(taskId:Int,now:Long=android.os.SystemClock.uptimeMillis()):Boolean {
        val deadline=miniDeadlines[taskId]?:return false
        if(now<=deadline)return true
        miniDeadlines.remove(taskId,deadline)
        return false
    }

    /** Temporarily delegate rounded-corner clipping to the Task leash while cropped in mini. */
    fun setMiniVisual(taskId:Int,deadlineUptimeMs:Long):Int {
        if(taskId<0)return 0
        val mini=deadlineUptimeMs>0L
        if(mini)miniDeadlines[taskId]=deadlineUptimeMs else miniDeadlines.remove(taskId)
        val rootSnapshot=snapshotRoots()
        val ownedSnapshot=snapshotOwned()
        var maxCaption=0
        var tx:SurfaceControl.Transaction?=null
        try {
            for(root in rootSnapshot){
                if(root.taskId!=taskId||X.callMethod(root.surface,"isValid")!=true)continue
                val t=tx?:SurfaceControl.Transaction().also{tx=it}
                X.callMethod(t,"setCrop",root.surface,root.crop)
                X.callMethod(t,"setCornerRadius",root.surface,if(mini)0f else root.radius)
                if(root.caption>maxCaption)maxCaption=root.caption
            }
            for((_,entry) in ownedSnapshot){
                if(entry.taskId!=taskId||X.callMethod(entry.surface,"isValid")!=true)continue
                val t=tx?:SurfaceControl.Transaction().also{tx=it}
                X.callMethod(t,"setCrop",entry.surface,entry.crop)
                X.callMethod(t,"setCornerRadius",entry.surface,if(mini)0f else entry.radius)
                if(entry.caption>maxCaption)maxCaption=entry.caption
            }
            tx?.apply()
        }finally{tx?.close()}
        return maxCaption
    }

    fun pruneExpiredMiniVisuals(now:Long=android.os.SystemClock.uptimeMillis()){
        if(miniDeadlines.isEmpty())return
        for((id,deadline) in miniDeadlines.entries.toList()){
            if(now>deadline)setMiniVisual(id,0L)
        }
    }

    private fun windowTransaction(container:Any):Any? =
        Guard.call("frameless-sync-tx"){
            if(X.findMethodExactIfExists(container.javaClass,"getSyncTransaction")!=null)
                X.callMethod(container,"getSyncTransaction")
            else null
        } ?: X.callMethod(container,"getPendingTransaction")

    fun install(){
        if(android.os.Build.VERSION.SDK_INT<30)return
        val window=X.findClassIfExists("com.android.server.wm.WindowState",loader)?:return
        val prepare=X.findMethodExactIfExists(window,"prepareSurfaces")?:return
        insetsMethod=X.findMethodExactIfExists(window,"getInsetsState",java.lang.Boolean.TYPE)
            ?:X.findMethodExactIfExists(window,"getInsetsState")?:return
        val state=X.findClass("android.view.InsetsState",loader)
        insetsReader=CaptionInsetsReader.create(state)?:return
        val insetsHooked=insetsMethod!!
        if(insetsReader!!.canStripCaption)XposedBridge.hookMethod(insetsHooked,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable()||readingRawInsets.get()==true||!Guard.open("frameless-window-crop"))return
                val raw=p.result?:return
                val reader=insetsReader?:return
                Guard.call("frameless-client-caption-inset"){
                    config.reload()
                    if(!config.enabled||config.observe||!config.frameless)return@call
                    val target=eligibleTarget(p.thisObject)?:return@call
                    if(!CaptionCropPolicy.suppressesClientCaption(target.info.packageName))return@call
                    val stripped=reader.stripCaptionBar(raw)
                    if(stripped!==raw){
                        p.result=stripped
                        if(strippedCaptionReports<32){
                            strippedCaptionReports++
                            Guard.info("frameless client caption inset omitted package=${target.info.packageName} task=${X.getIntField(target.task,"mTaskId")}")
                        }
                    }
                }
            }
        })
        val utils=X.findClassIfExists("com.samsung.android.multiwindow.MultiWindowUtils",loader)?:return
        radiusMethod=X.findMethodExactIfExists(utils,"getFreeformRoundedCornerRadius",Context::class.java,Integer.TYPE)
            ?:X.findMethodExactIfExists(utils,"getFreeformRoundedCornerRadius",Context::class.java)?:return
        if(X.findMethodExactIfExists(SurfaceControl.Transaction::class.java,"setCrop",SurfaceControl::class.java,Rect::class.java)==null)return
        val activityType=X.findClassIfExists("com.android.server.wm.ActivityRecord",loader)
        val rootPrepare=activityType?.let{X.findMethodExactIfExists(it,"prepareSurfaces")}
        if(rootPrepare!=null)XposedBridge.hookMethod(rootPrepare,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable()||!Guard.open("frameless-root-prepare"))return
                val root=synchronized(stateLock){roots[p.thisObject]}?:return
                val window=root.window.get()?:return
                Guard.call("frameless-root-prepare"){
                    val hasOwned=synchronized(stateLock){owned.containsKey(window)}
                    if(!config.enabled||config.observe||!config.frameless||!hasOwned){
                        restore(window);return@call
                    }
                    // WindowState.prepareSurfaces already refreshed ownership/geometry
                    // in this traversal. No second Insets/radius/Task lookup per frame.
                    if(X.callMethod(root.surface,"isValid")!=true)return@call
                    val tx=windowTransaction(p.thisObject)?:return@call
                    X.callMethod(tx,"setCrop",root.surface,root.crop)
                    X.callMethod(tx,"setCornerRadius",root.surface,if(isMiniVisual(root.taskId))0f else root.radius)
                }
            }
        })
        XposedBridge.hookMethod(prepare,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable())return
                if(!Guard.open("frameless-window-crop")){
                    Guard.call("frameless-window-restore"){restore(p.thisObject)};return
                }
                Guard.call("frameless-window-crop"){
                    try{update(p.thisObject)}catch(error:Throwable){runCatching{restore(p.thisObject)};throw error}
                }
            }
        })
        // A pending traversal is not the transaction which latches the client's next
        // buffer or finishes a BLAST group. Join those existing transactions too, so
        // an end-of-drag draw cannot show an uncropped frame before the next traversal.
        val draw=X.findMethodExactIfExists(window,"finishDrawing",SurfaceControl.Transaction::class.java,Integer.TYPE)
        if(draw!=null)XposedBridge.hookMethod(draw,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val tx=p.args[0]?:return
                join(p.thisObject,tx,"finishDrawing")
            }
        })
        val group=X.findClassIfExists("com.android.server.wm.BLASTSyncEngine\$SyncGroup",loader)
        val sync=group?.let{X.findMethodExactIfExists(window,"finishSync",SurfaceControl.Transaction::class.java,
            it,java.lang.Boolean.TYPE)}
        if(sync!=null)XposedBridge.hookMethod(sync,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                p.setObjectExtra("popupscale.caption.sync",Guard.call("frameless-sync-owner"){
                    X.callMethod(p.thisObject,"isDifferentSyncGroup",p.args[1])==false
                }==true)
            }
            override fun afterHookedMethod(p:MethodHookParam){
                if(!p.hasThrowable()&&p.getObjectExtra("popupscale.caption.sync")==true)
                    join(p.thisObject,p.args[0],"finishSync")
            }
        })
        val resizeSync=installResizeSyncHooks()
        Guard.info("frameless app-window crop ready: calculateInsets arity=${insetsReader!!.arity}; drawSync=${draw!=null} finishSync=${sync!=null} serverRoot=${rootPrepare!=null} resizeSync=$resizeSync")
    }
    private fun extractResizeTargets(wct:Any):List<ResizeTarget> {
        val changes=Guard.call("frameless-wct-changes"){
            X.callMethod(wct,"getChanges") as? Map<*,*>
        } ?: return emptyList()
        if(changes.isEmpty())return emptyList()
        val result=mutableListOf<ResizeTarget>()
        for((binder,change) in changes) {
            if(binder==null||change==null)continue
            val configMask=X.callMethod(change,"getConfigSetMask") as? Int ?: 0
            val windowMask=X.callMethod(change,"getWindowSetMask") as? Int ?: 0
            if((configMask and 0x20000000)==0||(windowMask and 1)==0)continue
            val conf=X.callMethod(change,"getConfiguration") ?: continue
            val winConf=X.getObjectField(conf,"windowConfiguration") ?: continue
            val bounds=X.callMethod(winConf,"getBounds") as? Rect ?: continue
            if(bounds.isEmpty)continue
            val mode=X.callMethod(winConf,"getWindowingMode") as? Int ?: 0
            result.add(ResizeTarget(binder,Rect(bounds),configMask,windowMask,mode))
        }
        return result
    }
    private fun installResizeSyncHooks():Boolean=Guard.call("frameless-resize-sync-install"){
        val organizerClass=X.findClassIfExists("com.android.server.wm.WindowOrganizerController",loader) ?: return@call false
        val syncEngineClass=X.findClassIfExists("com.android.server.wm.BLASTSyncEngine",loader) ?: return@call false
        val listenerClass=X.findClassIfExists("com.android.server.wm.BLASTSyncEngine\$TransactionReadyListener",loader) ?: return@call false
        val wctClass=X.findClassIfExists("android.window.WindowContainerTransaction",loader) ?: return@call false
        val cbClass=X.findClassIfExists("android.window.IWindowContainerTransactionCallback",loader) ?: return@call false
        windowContainerClass=X.findClassIfExists("com.android.server.wm.WindowContainer",loader) ?: return@call false
        val applySync=X.findMethodExactIfExists(organizerClass,"applySyncTransaction",wctClass,cbClass) ?: return@call false
        val prepareSync=X.findMethodExactIfExists(syncEngineClass,"prepareSyncSet",listenerClass,String::class.java) ?: return@call false
        val ready=X.findMethodExactIfExists(organizerClass,"onTransactionReady",SurfaceControl.Transaction::class.java,Integer.TYPE) ?: return@call false
        XposedBridge.hookMethod(applySync,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                pendingResizeTargets.remove();pendingStartedSync.remove()
                if(!Guard.open("frameless-window-crop")||!config.enabled||config.observe||!config.frameless)return
                val wct=p.args.getOrNull(0) ?: return
                if(p.args.getOrNull(1)==null)return
                val targets=extractResizeTargets(wct)
                if(targets.isNotEmpty())pendingResizeTargets.set(targets)
            }
            override fun afterHookedMethod(p:MethodHookParam){
                val started=pendingStartedSync.get()
                pendingStartedSync.remove();pendingResizeTargets.remove()
                if(started!=null&&(p.hasThrowable()||((p.result as? Int) ?: -1)<=0)){
                    resizeHolds.releaseSync(started,android.os.SystemClock.uptimeMillis())
                }
            }
        })
        XposedBridge.hookMethod(prepareSync,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val targets=pendingResizeTargets.get() ?: return
                if(p.hasThrowable()||p.result==null||!organizerClass.isInstance(p.args.getOrNull(0)))return
                val syncId=Guard.call("frameless-resize-sync-id"){X.getIntField(p.result,"mSyncId")} ?: return
                if(syncId<=0)return
                pendingStartedSync.set(syncId)
                val wcClass=windowContainerClass ?: return
                val held=linkedSetOf<Int>()
                Guard.call("frameless-resize-hold"){
                    for(target in targets){
                        val wc=X.callStaticMethod(wcClass,"fromBinder",target.binder) ?: continue
                        val task=X.callMethod(wc,"asTask") ?: continue
                        if(X.callMethod(task,"getWindowingMode")!=5)continue
                        val id=X.getIntField(task,"mTaskId")
                        if(!hasActiveCrop(id))continue
                        val cur=X.callMethod(task,"getBounds") as? Rect ?: continue
                        if(CaptionCropPolicy.resizesFreeformBounds(target.configMask,target.windowMask,target.mode,
                                cur.left,cur.top,cur.width(),cur.height(),
                                target.bounds.left,target.bounds.top,target.bounds.width(),target.bounds.height())){
                            held.add(id)
                        }
                    }
                }
                if(held.isNotEmpty()){
                    resizeHolds.hold(syncId,held,android.os.SystemClock.uptimeMillis())
                }
            }
        })
        XposedBridge.hookMethod(ready,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val syncId=p.args.getOrNull(1) as? Int ?: return
                val released=resizeHolds.releaseSync(syncId,android.os.SystemClock.uptimeMillis())
                if(released.isEmpty())return
                val tx=p.args.getOrNull(0) ?: return
                for((window,entry) in snapshotOwned()){
                    if(entry.taskId in released)Guard.call("frameless-resize-release"){update(window,tx)}
                }
            }
        })
        true
    } == true
    private fun join(window:Any,tx:Any,stage:String){
        if(!Guard.open("frameless-window-crop")||!Guard.open("frameless-crop-join"))return
        Guard.call("frameless-crop-join"){
            update(window,tx)
            if(synchronized(stateLock){owned.containsKey(window)}&&syncReports<24){
                syncReports++
                Guard.info("frameless crop joined $stage window=${System.identityHashCode(window)}")
            }
        }
    }
    private fun restore(window:Any,transaction:Any?=null){
        val old:Owned?
        val rootEntries=ArrayList<RootCrop>()
        synchronized(stateLock){
            old=owned.remove(window)
            val it=roots.entries.iterator()
            while(it.hasNext()){
                val entry=it.next()
                val root=entry.value
                if(root.window.get()===window){
                    rootEntries.add(root)
                    it.remove()
                }
            }
        }
        if(old==null&&rootEntries.isEmpty())return
        val tx=transaction?:windowTransaction(window)?:return
        if(old!=null&&X.callMethod(old.surface,"isValid")==true){
            X.callMethod(tx,"setCrop",old.surface,null)
            X.callMethod(tx,"setCornerRadius",old.surface,0f)
        }
        for(root in rootEntries)clearRoot(tx,root)
        old?.taskId?.let{id->if(!hasActiveCrop(id))resizeHolds.clearTask(id)}
    }
    private fun clearRoot(tx:Any,root:RootCrop){
        if(X.callMethod(root.surface,"isValid")!=true)return
        X.callMethod(tx,"setCrop",root.surface,null)
        X.callMethod(tx,"setCornerRadius",root.surface,0f)
    }
    private fun updateRoot(window:Any,activity:Any,task:Any,bounds:Rect,caption:Int,radius:Float,tx:Any,
                           resizeHeld:Boolean=false,allowZeroCaption:Boolean=false,
                           now:Long=android.os.SystemClock.uptimeMillis()){
        val taskId=X.getIntField(task,"mTaskId")
        val previous=synchronized(stateLock){roots[activity]}
        if(resizeHeld){
            if(previous!=null&&X.callMethod(previous.surface,"isValid")==true){
                X.callMethod(tx,"setCrop",previous.surface,previous.crop)
                X.callMethod(tx,"setCornerRadius",previous.surface,if(isMiniVisual(taskId,now))0f else previous.radius)
            }
            return
        }
        val current=X.callMethod(activity,"getBounds") as Rect
        fun box(r:Rect)=LivePlacement.Bounds(r.left,r.top,r.width(),r.height())
        val plan=CaptionCropPolicy.activityRoot(box(current),box(bounds),caption,
            X.callMethod(activity,"getParent")===task,
            Guard.field(activity,"mFixedRotationTransformState")!=null,allowZeroCaption)
        if(plan==null){
            val stale=synchronized(stateLock){
                roots[activity]?.takeIf{it.window.get()===window}?.also{roots.remove(activity)}
            }
            if(stale!=null)clearRoot(tx,stale)
            return
        }
        val surface=Guard.field(activity,"mSurfaceControl")?:return
        if(X.callMethod(surface,"isValid")!=true)return
        val crop=Rect(0,plan.top,plan.right,plan.bottom)
        X.callMethod(tx,"setCrop",surface,crop)
        X.callMethod(tx,"setCornerRadius",surface,if(isMiniVisual(taskId,now))0f else radius)
        synchronized(stateLock){
            roots[activity]=RootCrop(java.lang.ref.WeakReference(window),surface,crop,radius,taskId,caption)
        }
        if(reports<96&&(previous==null||previous.surface!==surface||previous.crop!=crop)){
            reports++;Guard.info("frameless server-owned root crop task=$taskId crop=${crop.flattenToString()}")
        }
    }
    private fun update(window:Any,transaction:Any?=null){
        config.reload()
        if(!config.enabled||config.observe||!config.frameless){restore(window,transaction);return}
        val target=eligibleTarget(window)?:run{restore(window,transaction);return}
        val attrs=target.attrs
        val activity=target.activity
        val task=target.task
        // A dialog-sized Activity or child window must not have its own header/content clipped.
        val frame=X.getObjectField(X.getObjectField(window,"mWindowFrames"),"mFrame") as Rect
        val bounds=X.callMethod(task,"getBounds") as Rect
        val surfaceInsets=Guard.field(attrs,"surfaceInsets") as? Rect?:run{restore(window,transaction);return}
        if(!surfaceInsets.isEmpty){restore(window,transaction);return}
        val old=synchronized(stateLock){owned[window]}
        val aligned=kotlin.math.abs(frame.left-bounds.left)<=2&&kotlin.math.abs(frame.top-bounds.top)<=2&&
            kotlin.math.abs(frame.width()-bounds.width())<=2
        // Shell's bounds update precedes the app's next layout. Do not reveal its old
        // caption pixels between acknowledgements. Retain only an already verified,
        // full-size base window; a dialog-sized window is still restored normally.
        val retain=old!=null&&attrs.width==WindowManager.LayoutParams.MATCH_PARENT&&
            attrs.height==WindowManager.LayoutParams.MATCH_PARENT
        if(!aligned&&!retain){restore(window,transaction);return}
        val now=android.os.SystemClock.uptimeMillis()
        val method=insetsMethod?:return
        val state=readRawInsets(method,window)
        val calculate=insetsReader?:return
        // Android 17 attached Insets sources need the container bounds as well as the
        // window frame. Older ROMs retain the three-argument absolute-source overload.
        val inset=calculate.calculate(state,frame,bounds)
        if(aligned&&inset.top<=0&&old!=null){if(old.zeroSince==0L)old.zeroSince=now}
        else if(inset.top>0)old?.zeroSince=0L
        val caption=CaptionCropPolicy.caption(inset.top,old?.caption,aligned,retain,
            if(old?.zeroSince==null||old.zeroSince==0L)0L else now-old.zeroSince)?:run{restore(window,transaction);return}
        val sx=X.getFloatField(window,"mGlobalScale")*X.getFloatField(window,"mHScale")
        val sy=X.getFloatField(window,"mGlobalScale")*X.getFloatField(window,"mVScale")
        val plan=CaptionCropPolicy.crop(frame.width(),frame.height(),caption,sx,sy)?:run{restore(window,transaction);return}
        val wm=X.getObjectField(window,"mWmService")
        val context=X.getObjectField(wm,"mContext") as Context
        val radiusFn=radiusMethod?:return
        val radius=(if(radiusFn.parameterCount==2)radiusFn.invoke(null,context,0)
            else radiusFn.invoke(null,context)) as? Number?:return
        val localRadius=radius.toFloat()/maxOf(sx,sy)
        if(!localRadius.isFinite()||localRadius<0)return
        val surface=Guard.field(window,"mSurfaceControl")?:return
        if(X.callMethod(surface,"isValid")!=true)return
        val taskId=X.getIntField(task,"mTaskId")
        val resizeHeld=resizeHolds.isHeld(taskId,now)
        val crop=Rect(0,plan.top,plan.right,plan.bottom)
        val previous=synchronized(stateLock){owned[window]}
        if(previous!=null&&previous.surface!==surface)restore(window,transaction)
        val retained=synchronized(stateLock){owned[window]}
        // While Shell holds the old Task leash scale for an in-flight resize sync, keep the
        // prior window/activity crop until onTransactionReady commits alongside setMatrix(1).
        if(resizeHeld&&(retained==null||retained.surface!==surface))return
        val tx=transaction?:windowTransaction(window)?:return
        val appliedCrop=if(resizeHeld)Rect(retained!!.crop) else crop
        val appliedRadius=if(resizeHeld)retained!!.radius else localRadius
        val appliedCaption=if(resizeHeld)retained!!.caption else caption
        // Keep the window fallback for embedded/rotating activities. A normal direct,
        // full-task Activity also gets a server-owned parent crop which the app's own
        // ViewRootImpl transaction cannot clear on the shared WindowState surface.
        X.callMethod(tx,"setCrop",surface,appliedCrop)
        X.callMethod(tx,"setCornerRadius",surface,if(isMiniVisual(taskId,now))0f else appliedRadius)
        synchronized(stateLock){
            owned[window]=Owned(surface,Rect(appliedCrop),appliedRadius,appliedCaption,taskId,old?.zeroSince?:0L)
        }
        if(aligned)updateRoot(window,activity,task,bounds,appliedCaption,radius.toFloat(),tx,resizeHeld,false,now)
        if(reports<96&&(retained==null||retained.crop!=appliedCrop||retained.radius!=appliedRadius)){
            reports++
            Guard.info("frameless app-window crop task=$taskId user=${X.getIntField(task,"mUserId")} caption=$appliedCaption aligned=$aligned scale=$sx/$sy crop=${appliedCrop.flattenToString()} radius=$appliedRadius")
        }
    }
}
