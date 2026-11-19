package io.github.s24u.popupscale.gestures

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Process
import de.robv.android.xposed.XposedHelpers as X

data class TaskRecord(val info: Any, val id: Int, val token: Any, val bounds: Rect,
                      val mode: Int, val focused: Boolean, val visible: Boolean,
                      val position: Point, val packageName: String, val userId:Int=0)

/** Gesture coordinates belong to the physical display, not a portrait-locked Launcher context. */
object GestureDisplay {
    @Suppress("DEPRECATION")
    fun metrics(context:Context):android.util.DisplayMetrics {
        val result=android.util.DisplayMetrics().apply{setTo(context.resources.displayMetrics)}
        Guard.call("gesture-display"){
            val display=context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
            // On this ROM getRealMetrics also applies the app's local rotation/maxBounds.
            // getDisplayInfo copies the underlying display info without that adjustment.
            val info=X.newInstance(X.findClass("android.view.DisplayInfo",null))
            if(X.callMethod(display,"getDisplayInfo",info)==true){
                result.widthPixels=X.getIntField(info,"logicalWidth")
                result.heightPixels=X.getIntField(info,"logicalHeight")
                val dpi=X.getIntField(info,"logicalDensityDpi")
                if(dpi>0){
                    result.scaledDensity*=dpi.toFloat()/result.densityDpi.coerceAtLeast(1)
                    result.densityDpi=dpi;result.density=dpi/160f
                }
            }
        }
        return result
    }
}

class TaskBackend(val context: Context, private val organizer: Any, private val loader: ClassLoader,
                  private val config: FeatureConfig, private val nativeTransitions: NativeTransitionBridge,
                  private val surfaceProvider:(Int)->Any?) {
    var opacityPanel:((Int)->Unit)?=null
    private val rotationLayout=LivePlacement.RotationLayoutState()
    private val entryLayout=LivePlacement.EntryLayoutState()
    private data class ContentProfile(val key:String,val wide:Boolean,val aspect:Float,
        val corner:Boolean,val displayWidth:Int,val displayHeight:Int)
    private val contentProfiles=java.util.concurrent.ConcurrentHashMap<Int,ContentProfile>()
    private val contentLayoutPending=mutableSetOf<Int>() // main thread only
    private val contentWaiting=mutableMapOf<Int,MutableList<()->Unit>>() // main thread only
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private fun contentKey(task:TaskRecord)=
        (X.getObjectField(task.info,"topActivity") as? ComponentName)?.flattenToString().orEmpty()
    fun contentProfileReady(task:TaskRecord)=contentProfiles[task.id]?.key==contentKey(task)
    fun loadContentProfile(task:TaskRecord,force:Boolean=false,done:()->Unit){
        if(!force&&contentProfileReady(task)){done();return}
        contentWaiting[task.id]?.let{
            // A player may change orientation while an earlier profile query is in flight.
            // Re-query after that reply instead of treating its stale result as the update.
            if(force)it.add{loadContentProfile(task,force=true,done=done)} else it.add(done)
            return
        }
        val callbacks=mutableListOf(done)
        contentWaiting[task.id]=callbacks
        val key=contentKey(task)
        val delivered=java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(data:android.os.Bundle?){
            if(!delivered.compareAndSet(false,true))return
            if(contentWaiting[task.id]!==callbacks)return
            val fallback=Guard.call("content-profile-fallback"){
                val info=Guard.field(task.info,"topActivityInfo") as? android.content.pm.ActivityInfo
                val app=info?.applicationInfo
                @Suppress("DEPRECATION")
                val game=app!=null&&(app.category==android.content.pm.ApplicationInfo.CATEGORY_GAME||
                    app.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME!=0)
                LivePlacement.preserveLandscape(task.bounds.width(),task.bounds.height(),game,false,
                    info?.screenOrientation in setOf(0,6,8,11))
            }==true
            val wide=data?.getBoolean("wide") ?: fallback
            val raw=data?.getFloat("aspect") ?: if(task.bounds.width()>task.bounds.height())
                task.bounds.width().toFloat()/task.bounds.height() else 16f/9f
            val aspect=if(raw.isFinite())raw.coerceIn(1.05f,4f) else 16f/9f
            val corner=data?.getBoolean("cornerEntry")==true
            val previous=contentProfiles.put(task.id,ContentProfile(key,wide,aspect,corner,
                data?.getInt("cornerDisplayWidth") ?: 0,data?.getInt("cornerDisplayHeight") ?: 0))
            if(previous!=null&&previous.wide!=wide)contentLayoutPending.add(task.id)
            Guard.info("content profile task=${task.id} wide=$wide aspect=$aspect server=${data!=null} corner=$corner")
            contentWaiting.remove(task.id)?.forEach{callback->Guard.call("content-profile-ready"){callback()}}
        }
        if(android.os.Build.VERSION.SDK_INT<34){finish(null);return}
        val reply=object:android.os.ResultReceiver(null){
            override fun onReceiveResult(code:Int,data:android.os.Bundle?){
                if(android.os.Binder.getCallingUid()!=android.os.Process.SYSTEM_UID)return
                main.post{finish(if(code==1&&data?.getInt("task")==task.id)data else null)}
            }
        }
        // Send a framework ResultReceiver proxy, never the anonymous module class:
        // system_server cannot load a SystemUI module subclass while unparcelling.
        val frameworkReply=FrameworkResultReceiver.proxy(reply)
        if(Guard.call("content-profile-query"){
            context.sendBroadcast(android.content.Intent(MiniTaskLease.CONTENT_PROFILE).setPackage("android")
                .putExtra("task",task.id).putExtra("when",android.os.SystemClock.uptimeMillis()).putExtra("reply",frameworkReply),
                MiniTaskLease.PERMISSION,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            true
        }!=true){finish(null);return}
        main.postDelayed({finish(null)},500)
    }
    fun record(info: Any): TaskRecord? = Guard.call("task-info") {
        val c = X.getObjectField(info, "configuration") as Configuration
        if(c.orientation != Configuration.ORIENTATION_PORTRAIT&&c.orientation != Configuration.ORIENTATION_LANDSCAPE)return@call null
        val w = X.getObjectField(c, "windowConfiguration")
        val type = X.callMethod(w, "getActivityType") as Int
        val mode = X.callMethod(w, "getWindowingMode") as Int
        val component = X.getObjectField(info, "topActivity") as? ComponentName ?: return@call null
        config.reload()
        io.github.s24u.popupscale.CloneProfileRegistry.refresh(context)
        if (type != 1 || mode !in setOf(1, 5) || X.getIntField(info, "displayId") != 0 ||
            !io.github.s24u.popupscale.CloneUserPolicy.visibleToOwner(X.getIntField(info,"userId"),Process.myUid()/100000) || !config.accepts(component.packageName))
            return@call null
        TaskRecord(info, X.getIntField(info, "taskId"), X.getObjectField(info, "token"),
            Rect(X.callMethod(w, "getBounds") as Rect), mode,
            X.getBooleanField(info, "isFocused"), X.getBooleanField(info, "isVisible"),
            Point((X.getObjectField(info, "positionInParent") as Point)), component.packageName,X.getIntField(info,"userId")).also{task->
                val m=GestureDisplay.metrics(context)
                if(entryLayout.observe(task.id,mode,task.visible,m.widthPixels,m.heightPixels,taskTransformActive(task.id))){
                    // A new native corner entry may reuse a Task previously queried in
                    // fullscreen. Discard that profile and its in-flight response before
                    // deciding whether this particular entry needs standard dimensions.
                    contentProfiles.remove(task.id);contentLayoutPending.remove(task.id)
                    contentWaiting.remove(task.id)
                }
                if(mode==5){
                    contentProfiles[task.id]?.takeIf{it.corner&&
                        (it.displayWidth!=m.widthPixels||it.displayHeight!=m.heightPixels)}?.let{
                        // Rotation must still run the existing shape repair; returning to
                        // the old orientation must not revive this entry-only exemption.
                        contentProfiles[task.id]=it.copy(corner=false)
                    }
                    rotationLayout.observe(task.id,m.widthPixels,m.heightPixels)
                }else rotationLayout.forget(task.id)
            }
    }?.let { it }
    fun info(id: Int): Any? = X.callMethod(organizer, "getRunningTaskInfo", id)
    fun position(id: Int): Point? = info(id)?.let { Point(X.getObjectField(it, "positionInParent") as Point) }
    fun locked(): Boolean = try {
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            context.getSystemService(android.app.ActivityManager::class.java).lockTaskModeState != 0
    } catch(error: Throwable) { Guard.failure("task-lock-state", error); true }
    var decorationProvider: ((Int)->Any?)? = null
    data class SurfaceTransform(val surface:Any,val sx:Float,val sy:Float,val x:Float,val y:Float,
                                val alpha:Float,val crop:Rect?=null,val layer:Int?=null,
                                val cornerRadius:Float?=null){
        fun apply(tx:Any){
            if(X.callMethod(surface,"isValid")!=true)return
            X.callMethod(tx,"setMatrix",surface,sx,0f,0f,sy)
            X.callMethod(tx,"setPosition",surface,x,y)
            X.callMethod(tx,"setAlpha",surface,alpha)
            crop?.let{X.callMethod(tx,"setCrop",surface,it)}
            layer?.let{X.callMethod(tx,"setLayer",surface,it)}
            cornerRadius?.let{X.callMethod(tx,"setCornerRadius",surface,it)}
        }
    }
    private data class OwnedTransform(val owner:Any,val transform:SurfaceTransform)
    private val taskTransforms=java.util.concurrent.ConcurrentHashMap<Int,OwnedTransform>()
    private val miniOwner=Any()
    fun taskTransformActive(id:Int)=taskTransforms.containsKey(id)
    fun rotationLayoutPending(id:Int)=rotationLayout.awaitingPortrait(id)||entryLayout.awaiting(id)||id in contentLayoutPending
    fun cornerEntryProfile(id:Int)=contentProfiles[id]?.corner==true
    fun portraitBounds(task:TaskRecord):Rect {
        val m=GestureDisplay.metrics(context);val b=task.bounds
        val entry=contentProfiles[task.id]?.takeIf{it.key==contentKey(task)}
        if(task.id !in contentLayoutPending&&entry!=null&&CornerGesturePolicy.adopts(entry.corner,task.mode,
                entry.displayWidth,entry.displayHeight,m.widthPixels,m.heightPixels)){
            entryLayout.cancel(task.id)
            return Rect(b) // Keep the released corner bounds and later manual adjustments.
        }
        val profile=contentProfiles[task.id]?.takeIf{it.key==contentKey(task)&&it.wide}
        if(profile!=null){
            entryLayout.cancel(task.id)
            rotationLayout.forget(task.id)
            // Accept native minimum-size adjustments once the result is a usable wide
            // window; never repeatedly fight WindowManager's minimum dimensions.
            if(b.width()>b.height()&&b.width()<=m.widthPixels*.90f&&b.height()<=m.heightPixels*.90f){
                contentLayoutPending.remove(task.id);return Rect(b)
            }
            val fit=LivePlacement.landscapeContentWindow(m.widthPixels,m.heightPixels,profile.aspect) ?: return Rect(b)
            return Rect(fit.x,fit.y,fit.x+fit.width,fit.y+fit.height)
        }
        if(task.id in contentLayoutPending){
            val fit=LivePlacement.standardWindow(m.widthPixels,m.heightPixels)
            if(fit!=null){
                if(kotlin.math.abs(b.width()-fit.width)<=2&&kotlin.math.abs(b.height()-fit.height)<=2)
                    contentLayoutPending.remove(task.id)
                else return Rect(fit.x,fit.y,fit.x+fit.width,fit.y+fit.height)
            }
        }
        val source=LivePlacement.Bounds(b.left,b.top,b.width(),b.height())
        val fit=entryLayout.portraitDefault(task.id,m.widthPixels,m.heightPixels,source)
            ?: rotationLayout.portraitDefault(task.id,m.widthPixels,m.heightPixels,source)
            ?: LivePlacement.portraitInLandscape(m.widthPixels,m.heightPixels,source,m.density)
        return Rect(fit.x,fit.y,fit.x+fit.width,fit.y+fit.height)
    }
    fun pinTaskTransform(id:Int,owner:Any,transform:SurfaceTransform?){
        if(transform!=null)taskTransforms[id]=OwnedTransform(owner,transform)
        else taskTransforms.computeIfPresent(id){_,old->if(old.owner===owner)null else old}
    }
    fun forgetTaskTransforms(id:Int){
        taskTransforms.remove(id);captionTransforms.remove(id);captionTouched.remove(id);rotationLayout.forget(id);entryLayout.forget(id)
        contentProfiles.remove(id)
        contentLayoutPending.remove(id)
        contentWaiting.remove(id)
    }
    fun releaseMiniTransform(id:Int)=pinTaskTransform(id,miniOwner,null)
    fun miniTransformActive(id:Int)=taskTransforms[id]?.owner===miniOwner
    fun pinMiniFinish(id:Int,tx:Any){
        val owned=taskTransforms[id] ?: return
        if(owned.owner===miniOwner)owned.transform.apply(tx)
    }
    fun pinMiniFrame(id:Int,start:Any,finish:Any):Boolean {
        val owned=taskTransforms[id] ?: return false
        if(owned.owner!==miniOwner||X.callMethod(owned.transform.surface,"isValid")!=true)return false
        owned.transform.apply(start)
        if(finish!==start)owned.transform.apply(finish)
        return true
    }
    fun applyTaskTransform(id:Int,start:Any,finish:Any){
        val pin=taskTransforms[id]?.transform ?: return
        // The ROM writes native position/crop into these transactions during relayout.
        // Repair those very transactions, rather than racing them with a separate apply().
        pin.apply(start)
        if(finish!==start)pin.apply(finish)
    }
    data class CaptionTransform(val surface:Any,val sx:Float,val sy:Float,val x:Float,val y:Float,
                                val nativeX:Float,val nativeY:Float,val sourceBounds:Rect){
        fun apply(tx:Any){
            if(X.callMethod(surface,"isValid")!=true)return
            X.callMethod(tx,"setMatrix",surface,sx,0f,0f,sy)
            X.callMethod(tx,"setPosition",surface,x,y)
        }
    }
    val captionTransforms=java.util.concurrent.ConcurrentHashMap<Int,CaptionTransform>()
    private val captionTouched=java.util.concurrent.ConcurrentHashMap<Int,java.lang.ref.WeakReference<Any>>()
    fun pinCaptionTransform(id:Int,pin:CaptionTransform?){
        if(pin==null)captionTransforms.remove(id) else{
            captionTouched[id]=java.lang.ref.WeakReference(pin.surface)
            captionTransforms[id]=pin
        }
    }
    fun applyCaptionTransform(controller:Any,tx:Any){
        val info=Guard.field(controller,"taskInfo") ?: return
        val id=X.getIntField(info,"taskId")
        val pin=captionTransforms[id]
        val touched=captionTouched[id]?.get()
        if(pin==null&&touched==null)return
        val host=Guard.field(controller,"captionViewHost") ?: return
        val surface=X.callMethod(host,"getSurfaceControl") ?: return
        if(X.callMethod(surface,"isValid")!=true)return
        val config=X.getObjectField(info,"configuration")
        val window=X.getObjectField(config,"windowConfiguration")
        val bounds=X.callMethod(window,"getBounds") as Rect
        if(pin!=null&&pin.surface==surface&&(pin.sourceBounds==bounds||taskTransformActive(id)))pin.apply(tx)
        else if(surface==touched){
            // Native code already set this frame's position, but never resets matrix.
            // Do not encode an old gesture's inverse scale into a new-size transaction.
            X.callMethod(tx,"setMatrix",surface,1f,0f,0f,1f)
        }
    }
    private fun releaseCaptionTransform(id:Int,tx:Any) {
        val pin=captionTransforms.remove(id)
        val touched=captionTouched[id]?.get()
        if(pin==null&&touched==null)return
        // Removing the registry entry alone does not undo the matrix already applied
        // to SurfaceFlinger. Hand the child back in the SAME transaction as the mini
        // parent transform; resetting the whole task here would reveal its large frame.
        val latest=captionState(id)
        if(pin!=null&&X.callMethod(pin.surface,"isValid")==true){
            val same=latest?.surface==pin.surface
            X.callMethod(tx,"setMatrix",pin.surface,1f,0f,0f,1f)
            X.callMethod(tx,"setPosition",pin.surface,
                if(same)latest!!.bounds.left.toFloat() else pin.nativeX,
                if(same)latest!!.bounds.top.toFloat() else pin.nativeY)
        }
        // Also repair already-orphaned state: the inverse matrix can outlive its map
        // entry, including after a delayed native transaction commits an older frame.
        if(latest!=null&&latest.surface==touched){
            X.callMethod(tx,"setMatrix",latest.surface,1f,0f,0f,1f)
            X.callMethod(tx,"setPosition",latest.surface,latest.bounds.left.toFloat(),latest.bounds.top.toFloat())
        }
        if(pin!=null)Guard.info("caption compensation released task=$id")
    }
    fun normalizeCaptionIfUnowned(id:Int){
        if(taskTransforms.containsKey(id)||captionTransforms.containsKey(id)||captionTouched[id]?.get()==null)return
        val tx=X.newInstance(X.findClass("android.view.SurfaceControl\$Transaction",loader))
        try{releaseCaptionTransform(id,tx);X.callMethod(tx,"apply")}
        finally{X.callMethod(tx,"close")}
    }
    data class CaptionState(val surface:Any,val bounds:Rect)
    fun captionState(id:Int):CaptionState?=Guard.call("caption-state"){
        val decor=decorationProvider?.invoke(id) ?: return@call null
        val controller=Guard.field(decor,"captionController") ?: return@call null
        val layout=Guard.field(controller,"captionLayoutResult") ?: return@call null
        val host=Guard.field(controller,"captionViewHost") ?: return@call null
        val surface=X.callMethod(host,"getSurfaceControl") ?: return@call null
        if(X.callMethod(surface,"isValid")!=true)return@call null
        val x=X.getIntField(layout,"captionX");val y=X.getIntField(layout,"captionY")
        CaptionState(surface,Rect(x,y,x+X.getIntField(layout,"captionWidth"),y+X.getIntField(layout,"captionHeight")))
    }
    fun transaction(block: (Any) -> Unit) {
        val wct = X.newInstance(X.findClass("android.window.WindowContainerTransaction", loader))
        block(wct)
        X.callMethod(organizer, "applyTransaction", wct)
    }
    fun resizeFreeform(task: TaskRecord, bounds: Rect, done:(Boolean)->Unit):Boolean {
        return resizeFreeformInSync(task,bounds,null,done)
    }
    fun resizeFreeformInSync(task:TaskRecord,bounds:Rect,
                            finishSurface:((Any,Any,Rect,Point)->Unit)?,done:(Boolean)->Unit):Boolean {
        return resizeFreeformInSync(task,bounds,finishSurface,{true},done)
    }
    fun resizeFreeformInSync(task:TaskRecord,bounds:Rect,
                            finishSurface:((Any,Any,Rect,Point)->Unit)?,isCurrent:()->Boolean,
                            done:(Boolean)->Unit):Boolean {
        if(task.mode!=5 || bounds.isEmpty || bounds.width()<100 || bounds.height()<100)return false
        val wct=X.newInstance(X.findClass("android.window.WindowContainerTransaction",loader))
        X.callMethod(wct,"setBounds",task.token,Rect(bounds))
        val applied=java.util.concurrent.atomic.AtomicReference<Rect?>()
        return nativeTransitions.resizeInSync(wct,task.id,{tx->
            val leash=surface(task.id)
            val latest=info(task.id)?.let{record(it)}
            if(leash!=null && latest!=null && latest.mode==5){
                // The sync draw callback can precede ShellTaskOrganizer's TaskInfo callback.
                // Never write its old rotated crop back over the new WM geometry.
                val actual=Guard.call("resize-applied-bounds"){
                    val service=X.callStaticMethod(X.findClass("android.app.ActivityTaskManager",null),"getService")
                    (X.callMethod(service,"getTaskBounds",task.id) as Rect).takeUnless{it.isEmpty}?.let{Rect(it)}
                } ?: Rect(bounds)
                applied.set(actual)
                // Retain the display-area parent's origin offset, not the old task position.
                val geometry=resizeFrame(if(latest.bounds==actual)latest else task,bounds,actual)
                if(finishSurface!=null){
                    // Mini ownership must survive the size commit. Never show a native-size
                    // identity frame before its main-thread consumer reapplies the mini pin.
                    finishSurface(tx,leash,Rect(actual),Point(geometry.x,geometry.y))
                }else{
                    taskTransforms.remove(task.id)
                    X.callMethod(tx,"setMatrix",leash,1f,0f,0f,1f)
                    X.callMethod(tx,"setPosition",leash,geometry.x.toFloat(),geometry.y.toFloat())
                    X.callMethod(tx,"setCrop",leash,Rect(0,0,geometry.width,geometry.height))
                }
                Guard.call("resize-caption-sync"){releaseCaptionTransform(task.id,tx)}
                Guard.info("resize geometry task=${task.id} applied=${actual.toShortString()} cached=${latest.bounds.toShortString()}")
            }
        },isCurrent){ok->
            if(!ok){done(false);return@resizeInSync}
            val expected=applied.get()
            fun deliver(remaining:Int){
                val latest=Guard.call("resize-info-state"){info(task.id)?.let{record(it)}}
                if(expected==null||latest==null||latest.mode!=5||latest.bounds==expected||remaining==0){
                    done(true)
                }else main.postDelayed({Guard.call("resize-info-ready"){deliver(remaining-1)}},16)
            }
            // Consumers (including the handle) should see acknowledged geometry.
            deliver(12)
        }
    }
    private fun resizeFrame(task:TaskRecord,requested:Rect,applied:Rect?):LivePlacement.Bounds {
        fun frame(r:Rect)=LivePlacement.Bounds(r.left,r.top,r.width(),r.height())
        return LivePlacement.resizeFrame(frame(requested),applied?.let{frame(it)},frame(task.bounds),task.position.x,task.position.y)
    }
    fun resetChangedGesture(task:TaskRecord){
        taskTransforms.remove(task.id)
        val leash=surface(task.id) ?: return
        val tx=X.newInstance(X.findClass("android.view.SurfaceControl\$Transaction",loader))
        try{
            val actual=Guard.call("gesture-applied-bounds"){
                val service=X.callStaticMethod(X.findClass("android.app.ActivityTaskManager",null),"getService")
                X.callMethod(service,"getTaskBounds",task.id) as Rect
            }
            val geometry=resizeFrame(task,task.bounds,actual)
            X.callMethod(tx,"setMatrix",leash,1f,0f,0f,1f)
            X.callMethod(tx,"setPosition",leash,geometry.x.toFloat(),geometry.y.toFloat())
            X.callMethod(tx,"setCrop",leash,Rect(0,0,geometry.width,geometry.height))
            releaseCaptionTransform(task.id,tx)
            X.callMethod(tx,"apply")
        }finally{X.callMethod(tx,"close")}
    }
    @Volatile private var cachedFreeformRadius:Float?=null
    fun nativeCornerRadius(taskId:Int):Float? {
        val decor=decorationProvider?.invoke(taskId)
        val decorRadius=if(decor!=null)Guard.call("settled-native-radius"){
            (if(X.findMethodExactIfExists(decor.javaClass,"getCornerRadius")!=null)X.callMethod(decor,"getCornerRadius") as? Int
            else (Guard.field(decor,"cornerRadius","mCornerRadius") as? Int))
                ?.takeIf{it>0}
                ?: Guard.field(decor,"freeformOutline","mFreeformOutline")?.let{outline->
                    (if(X.findMethodExactIfExists(outline.javaClass,"getCornerRadius")!=null)X.callMethod(outline,"getCornerRadius") as? Int
                    else Guard.field(outline,"cornerRadius","mCornerRadius") as? Int)?.takeIf{it>0}
                }
        }?.toFloat() else null
        if(decorRadius!=null&&decorRadius>0f){cachedFreeformRadius=decorRadius;return decorRadius}
        cachedFreeformRadius?.let{return it}
        val fallback=Guard.call("native-corner-radius-utils"){
            val utils=X.findClassIfExists("com.samsung.android.multiwindow.MultiWindowUtils",loader)?:return@call null
            val fn=X.findMethodExactIfExists(utils,"getFreeformRoundedCornerRadius",Context::class.java,Integer.TYPE)
                ?: X.findMethodExactIfExists(utils,"getFreeformRoundedCornerRadius",Context::class.java)?:return@call null
            val value=(if(fn.parameterCount==2)fn.invoke(null,context,0) else fn.invoke(null,context)) as? Number
            value?.toFloat()?.takeIf{it.isFinite()&&it>0f}
        }
        if(fallback!=null)cachedFreeformRadius=fallback
        return fallback
    }
    /** One hand-off after native transitions finish, never a per-frame geometry pin. */
    fun settleFreeformSurface(task:TaskRecord):Boolean {
        if(task.mode!=5||!task.visible||!task.focused||taskTransformActive(task.id)||locked())return false
        val leash=surface(task.id) ?: return false
        val decor=decorationProvider?.invoke(task.id) ?: return false
        val tx=X.newInstance(X.findClass("android.view.SurfaceControl\$Transaction",loader))
        try{
            X.callMethod(tx,"setMatrix",leash,1f,0f,0f,1f)
            X.callMethod(tx,"setPosition",leash,task.position.x.toFloat(),task.position.y.toFloat())
            X.callMethod(tx,"setCrop",leash,Rect(0,0,task.bounds.width(),task.bounds.height()))
            val radius=nativeCornerRadius(task.id)?.toInt()
            if(radius!=null&&radius>0)X.callMethod(tx,"setCornerRadius",leash,radius.toFloat())
            releaseCaptionTransform(task.id,tx)
            X.callMethod(tx,"apply")
            Guard.info("freeform surface settled task=${task.id} bounds=${task.bounds.toShortString()} radius=$radius")
            return true
        }finally{X.callMethod(tx,"close")}
    }
    fun maximize(task: TaskRecord) {
        nativeTransitions.maximize(task) {
            if(locked())null else info(task.id)?.let { record(it) }
        }
    }
    fun canRestore() = nativeTransitions.ready()
    fun whenIdle(done:(Boolean)->Unit) = nativeTransitions.whenIdle(done)
    fun whenHomeTasksReady(ids:List<Int>,deadline:Long,done:(Boolean)->Unit) {
        val affected=ids.toMutableSet()
        for(id in ids) {
            val task=info(id) ?: continue
            val parent=Guard.call("home-parent-task"){X.getIntField(task,"parentTaskId")} ?: -1
            if(parent>=0)affected.add(parent)
        }
        nativeTransitions.whenTasksReady(affected,deadline,done)
    }
    fun surface(id:Int):Any? {
        // WindowDecoration owns a COPY of the task leash and releases it on maximize.
        // Read the current organizer-owned leash under the same lock as getRunningTaskInfo.
        val current=Guard.call("organizer-task-surface") {
            synchronized(X.getObjectField(organizer,"mLock")) {
                val tasks=X.getObjectField(organizer,"mTasks") as android.util.SparseArray<*>
                tasks.get(id)?.let{X.callMethod(it,"getLeash")}
                    ?.takeIf{X.callMethod(it,"isValid")==true}
            }
        }
        return current ?: surfaceProvider(id)?.takeIf { X.callMethod(it,"isValid")==true }
    }
    fun liveBounds(taskId:Int=-1):Rect {
        val m=GestureDisplay.metrics(context)
        val profile=contentProfiles[taskId]?.takeIf{it.wide}
        val b=checkNotNull(if(profile!=null)LivePlacement.landscapeContentWindow(m.widthPixels,m.heightPixels,profile.aspect)
            else LivePlacement.standardWindow(m.widthPixels,m.heightPixels))
        return Rect(b.x,b.y,b.x+b.width,b.y+b.height)
    }
    fun miniBounds(base:Rect,left:Boolean=false):Rect? {
        val m=GestureDisplay.metrics(context)
        val p=LivePlacement.miniPlace(m.widthPixels,m.heightPixels,base.width(),base.height(),m.density,left)?:return null
        return Rect(p.x,p.y,p.x+p.width,p.y+p.height)
    }
    fun surfaceTransaction(block:(Any)->Unit){
        val tx=X.newInstance(X.findClass("android.view.SurfaceControl\$Transaction",loader))
        try{block(tx);X.callMethod(tx,"apply")}finally{X.callMethod(tx,"close")}
    }
    fun transform(taskId:Int,surface:Any, scale:Float, x:Float, y:Float,alpha:Float?=null,
                  retain:Boolean=true,crop:Rect?=null,transaction:Any?=null,layer:Int?=null,
                  cornerRadius:Float?=null) {
        require(scale.isFinite()&&scale>0&&x.isFinite()&&y.isFinite())
        val tx=transaction ?: X.newInstance(X.findClass("android.view.SurfaceControl\$Transaction",loader))
        try{
            val sourceCrop=crop?.let{Rect(it)} ?: if(retain)taskTransforms[taskId]?.transform?.crop else null
            val resolvedRadius=cornerRadius?.takeIf{it.isFinite()&&it>=0f}
                ?: if(retain)taskTransforms[taskId]?.transform?.cornerRadius else null
            pinTaskTransform(taskId,miniOwner,if(retain)
                SurfaceTransform(surface,scale,scale,x,y,alpha ?: 1f,sourceCrop,layer,resolvedRadius) else null)
            releaseCaptionTransform(taskId,tx)
            X.callMethod(tx,"setMatrix",surface,scale,0f,0f,scale)
            X.callMethod(tx,"setPosition",surface,x,y)
            if(sourceCrop!=null)X.callMethod(tx,"setCrop",surface,sourceCrop)
            if(layer!=null)X.callMethod(tx,"setLayer",surface,layer)
            if(resolvedRadius!=null)X.callMethod(tx,"setCornerRadius",surface,resolvedRadius)
            if(alpha!=null){require(alpha.isFinite()&&alpha in 0f..1f);X.callMethod(tx,"setAlpha",surface,alpha)}
            if(transaction==null)X.callMethod(tx,"apply")
        }finally{if(transaction==null)X.callMethod(tx,"close")}
    }
    fun alpha(surface:Any,value:Float) {
        val tx=X.newInstance(X.findClass("android.view.SurfaceControl\$Transaction",loader))
        try{X.callMethod(tx,"setAlpha",surface,value);X.callMethod(tx,"apply")}
        finally{X.callMethod(tx,"close")}
    }
    fun captureFrame(task:TaskRecord,leash:Any):Bitmap? = Guard.call("transition-capture") {
        if(task.bounds.width()<=0||task.bounds.height()<=0)return@call null
        val name=captureProvider ?: return@call null
        val type=X.findClass(name,loader)
        val builder=X.newInstance(X.findClass(name+"\$LayerCaptureArgs\$Builder",loader),leash)
        X.callMethod(builder,"setChildrenOnly",true)
        X.callMethod(builder,"setSourceCrop",Rect(0,0,task.bounds.width(),task.bounds.height()))
        X.callMethod(builder,"setFrameScale",minOf(1f,1440f/task.bounds.width(),3120f/task.bounds.height()))
        val captured=X.callStaticMethod(type,"captureLayers",X.callMethod(builder,"build"))?:return@call null
        try {
            if(X.callMethod(captured,"containsSecureLayers")==true)return@call null
            val bitmap=X.callMethod(captured,"asBitmap") as? Bitmap?:return@call null
            try{bitmap.copy(Bitmap.Config.ARGB_8888,false)}finally{bitmap.recycle()}
        }finally{X.callMethod(X.callMethod(captured,"getHardwareBuffer"),"close")}
    }?.let{it}
    private val captureProvider:String? by lazy {
        val p=RuntimeCompatibilityProbe.create(loader)
        p.first(listOf("android.window.ScreenCaptureInternal","android.window.ScreenCapture")){name->
            p.method(name,"captureLayers",listOf(name+"\$LayerCaptureArgs"))&&p.type(name+"\$LayerCaptureArgs\$Builder")!=null
        }
    }
    fun hideMini(task: TaskRecord) {
        transaction { wct ->
            // Keep the original mode/geometry until the user restores. The native
            // transition must own the later fullscreen -> freeform conversion.
            X.callMethod(wct, "setHidden", task.token, true)
        }
    }
    fun restore(id: Int, bounds: Rect, accepted:(Boolean)->Unit) {
        nativeTransitions.restore(id,bounds,{info(id)?.let{X.getObjectField(it,"token")}},accepted)
    }
    fun openLive(id:Int,bounds:Rect,quiet:Boolean=false,accepted:(Boolean)->Unit) {
        nativeTransitions.restore(id,bounds,{info(id)?.let{X.getObjectField(it,"token")}},accepted,false,quiet){surface(id)}
    }
    fun setFocusable(id:Int,focusable:Boolean) {
        val current=info(id)?:return
        transaction{X.callMethod(it,"setFocusable",X.getObjectField(current,"token"),focusable)}
    }
    fun restoreFocus(id:Int)=setFocusable(id,true)
    fun miniPlaybackSelected(name:String):Boolean {
        config.reload()
        return config.homeMiniEnabled(name)
    }
    fun miniKeepPlaybackSelected(name:String):Boolean {
        config.reload()
        return config.homeMiniKeepPlayback(name)
    }
    fun restoreLiveFocus(id:Int,expectedBounds:Rect):Boolean {
        val task=info(id)?.let{record(it)} ?: return false
        if(task.mode!=5||!task.visible||task.bounds!=expectedBounds)return false
        transaction{wct->
            X.callMethod(wct,"setHidden",task.token,false)
            X.callMethod(wct,"setFocusable",task.token,true)
            X.callMethod(wct,"reorder",task.token,true)
        }
        return true
    }
    fun closeHandleToBack() = config.closeToBack
    fun background(task: TaskRecord, gestureCurrent:()->Boolean, done:(Boolean)->Unit):Boolean {
        val leash=surface(task.id) ?: return false
        val pin=taskTransforms[task.id] ?: return false
        val wct=X.newInstance(X.findClass("android.window.WindowContainerTransaction",loader))
        BackgroundTaskOperation.configure(wct,task.token)
        // Decoration release may invalidate the gesture after submission. The retained task
        // still needs a clean surface when it next comes forward.
        return nativeTransitions.resizeInSync(wct,task.id,{ tx ->
            val currentPin=taskTransforms[task.id]
            if ((currentPin==null || currentPin.owner===pin.owner) && X.callMethod(leash,"isValid")==true) {
                if(currentPin!=null)taskTransforms.remove(task.id,currentPin)
                X.callMethod(tx,"setMatrix",leash,1f,0f,0f,1f)
                X.callMethod(tx,"setPosition",leash,0f,0f)
                X.callMethod(tx,"setWindowCrop",leash,null as android.graphics.Rect?)
                X.callMethod(tx,"setAlpha",leash,1f)
                releaseCaptionTransform(task.id,tx)
            }
        },canSubmit={
            val latest=info(task.id)?.let{record(it)}
            gestureCurrent() && !locked() && latest?.token==task.token && latest.mode==5 &&
                latest.visible && latest.focused && latest.bounds==task.bounds
        },done=done)
    }
    fun markClosing(taskId: Int) = nativeTransitions.markClosing(taskId)
    fun close(task: TaskRecord) {
        nativeTransitions.markClosing(task.id)
        val service = X.callStaticMethod(X.findClass("android.app.ActivityTaskManager", loader), "getService")
        check(X.callMethod(service, "removeTask", task.id) == true) { "Task close rejected" }
    }
    fun returnHome() {
        Guard.call("mini-dismiss-recents") {
            val service=X.callStaticMethod(X.findClass("android.app.ActivityManager",loader),"getService")
            X.callMethod(service,"closeSystemDialogs","homekey")
        }
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("homeKeyContext",true))
    }
    fun homeFocused():Boolean = Guard.call("live-home-check") {
        val service=X.callStaticMethod(X.findClass("android.app.ActivityTaskManager",loader),"getService")
        val task=X.callMethod(service,"getFocusedRootTaskInfo") ?: return@call false
        val c=X.getObjectField(task,"configuration") as Configuration
        val wc=X.getObjectField(c,"windowConfiguration")
        val top=X.getObjectField(task,"topActivity") as? ComponentName
        X.callMethod(wc,"getActivityType")==2&&X.getIntField(task,"displayId")==0&&
            top?.packageName==RomProfile.LAUNCHER
    }==true
    fun snapshot(id: Int): Bitmap? = Guard.call("snapshot") {
        if(android.os.Build.VERSION.SDK_INT<29)return@call null
        // Mirror this ROM's ActivityManagerWrapper instead of guessing Binder boolean flags.
        val facade=X.findClass("android.window.TaskSnapshotManager",loader)
        val manager=X.callStaticMethod(facade,"getInstance")
        val flags=X.callStaticMethod(facade,"convertRetrieveFlag",false)
        val snapshot=X.callMethod(manager,"getTaskSnapshot",id,flags)
            ?: X.callMethod(manager,"getTaskSnapshotForCarousel",id)
            ?: X.callMethod(manager,"takeTaskSnapshot",id,true)
        if(snapshot == null){Guard.info("snapshot unavailable task=$id");return@call null}
        try {
            // This firmware's deprecated getHardwareBuffer() deliberately returns null.
            // Match ThumbnailData.Companion: wrapToBitmap(), then closeBuffer().
            if(X.callMethod(snapshot,"containsSecureLayers")==true)return@call null
            val bitmap=X.callMethod(snapshot,"wrapToBitmap") as? Bitmap
            Guard.info("snapshot conversion task=$id valid=${X.callMethod(snapshot,"isBufferValid")} " +
                "bitmap=${if(bitmap==null)"none" else "${bitmap.width}x${bitmap.height}"}")
            bitmap
        } finally { X.callMethod(snapshot,"closeBuffer") }
    }?.let { it }
}
