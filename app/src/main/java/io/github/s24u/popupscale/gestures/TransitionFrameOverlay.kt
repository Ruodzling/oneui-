package io.github.s24u.popupscale.gestures

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import de.robv.android.xposed.XposedHelpers as X
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One in-memory frame bridges native mode changes; the final mini remains a live task. */
@android.annotation.TargetApi(29)
class TransitionFrameOverlay(private val context:Context,private val backend:TaskBackend) {
    private val main=Handler(Looper.getMainLooper())
    private val wm=context.getSystemService(WindowManager::class.java)
    private val journal=context.createDeviceProtectedStorageContext().getSharedPreferences("popupscale_frame_recovery",Context.MODE_PRIVATE)
    private var recoveryTask=journal.getInt("task",-1)
    private val created=SystemClock.uptimeMillis()
    private val capture=Executors.newSingleThreadExecutor{Thread(it,"PopupScaleFrameCapture").apply{isDaemon=true}}
    private var captureJob:Future<*>?=null
    private var task=-1
    private var token=""
    private var view:FrameLayout?=null
    private var content:ImageView?=null
    private var params:WindowManager.LayoutParams?=null
    private var rect=Rect()
    private var destination=Rect()
    private var motion=FrameAnimationGate()
    private val masks=mutableListOf<Any>()
    private var animator:ValueAnimator?=null
    private var generation=0

    fun matches(id:Int,nonce:String)=id==task&&nonce.isNotEmpty()&&nonce==token&&view!=null
    fun targets(target:Rect)=target==destination
    fun prepare(record:TaskRecord,start:Rect,nonce:String,reply:ResultReceiver) {
        clear()
        val ticket=generation
        backend.loadContentProfile(record,force=true){
            if(ticket!=generation)reply.send(0,null) else prepareReady(record,start,nonce,reply)
        }
    }
    private fun prepareReady(record:TaskRecord,start:Rect,nonce:String,reply:ResultReceiver) {
        val latest=backend.info(record.id)
        if(latest==null||X.getObjectField(latest,"topActivity")!=X.getObjectField(record.info,"topActivity")){
            reply.send(0,null);return
        }
        val m=GestureDisplay.metrics(context)
        if(nonce.length!=36||start.width()<32||start.height()<32||start.left<0||start.top<0||
            start.right>m.widthPixels||start.bottom>m.heightPixels||backend.locked()){
            reply.send(0,null);return
        }
        clear()
        recoverIfNeeded()
        if(recoveryTask>=0 && backend.info(recoveryTask)==null){
            journal.edit().remove("task").commit()
            recoveryTask=-1
        }
        if(recoveryTask>=0){reply.send(0,null);return}
        val source=backend.surface(record.id)
        if(source==null){Guard.info("transition frame rejected: no valid organizer surface task=${record.id}");reply.send(0,null);return}
        val target=backend.miniBounds(backend.liveBounds(record.id))
        if(target==null){reply.send(0,null);return}
        task=record.id;token=nonce;rect=Rect(start)
        destination=target;motion=FrameAnimationGate()
        val g=++generation
        captureJob=capture.submit{
            val bitmap=backend.captureFrame(record,source)
            main.post{Guard.call("transition-frame-present"){
                if(g!=generation||backend.locked()){bitmap?.recycle();reply.send(0,null);return@call}
                if(bitmap==null){
                    Guard.info("transition frame unavailable; using legacy live animation")
                    clear();reply.send(0,null);return@call
                }
                show(bitmap,start,g,reply)
            }}
        }
        main.postDelayed({if(g==generation){Guard.info("transition frame expired task=$task");clear()}},5000)
    }
    private fun show(bitmap:Bitmap,start:Rect,g:Int,reply:ResultReceiver) {
        val m=GestureDisplay.metrics(context)
        val cornerRadiusPx=backend.nativeCornerRadius(task) ?: (14f*m.density)
        try {
            val image=ImageView(context).apply {
                scaleType=ImageView.ScaleType.FIT_XY
                setImageBitmap(bitmap)
                setBackgroundColor(Color.BLACK)
                outlineProvider=object:android.view.ViewOutlineProvider() {
                    override fun getOutline(v:android.view.View,outline:android.graphics.Outline) {
                        val s=minOf(v.scaleX,v.scaleY).coerceAtLeast(0.05f)
                        val r=LivePlacement.scaledCornerRadius(cornerRadiusPx,s,v.width,v.height) ?: 0f
                        outline.setRoundRect(0,0,v.width,v.height,r)
                    }
                }
                clipToOutline=true
                importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                pivotX=0f;pivotY=0f
            }
            // One fixed surface covering the whole path. Per-frame relayout used to cause
            // WM round trips and buffer resizing during the most expensive native transition.
            val envelope=Rect(start).apply{union(destination)}
            val root=FrameLayout(context)
            root.addView(image,FrameLayout.LayoutParams(start.width(),start.height()))
            image.translationX=(start.left-envelope.left).toFloat()
            image.translationY=(start.top-envelope.top).toFloat()
            val p=WindowManager.LayoutParams(envelope.width(),envelope.height(),2024,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT)
            p.gravity=Gravity.TOP or Gravity.LEFT;p.x=envelope.left;p.y=envelope.top
            p.title="PopupScaleTransitionFrame"
            X.callMethod(p,"setTrustedOverlay")
            view=root;content=image;params=p
            // Only acknowledge after HWUI has submitted the first frame.
            image.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:android.view.View){Guard.call("transition-frame-commit"){
                    image.viewTreeObserver.registerFrameCommitCallback{main.post{Guard.call("transition-frame-reply"){
                        if(g==generation&&content===image&&!backend.locked()){
                            if(!journal.edit().putInt("task",task).commit()){
                                clear();reply.send(0,null);return@call
                            }
                            recoveryTask=task
                            if(!maskTask(task,token)){clear();reply.send(0,null);return@call}
                            Guard.info("transition frame committed task=$task bitmap=${bitmap.width}x${bitmap.height} start=${rect.toShortString()}")
                            // Start at the release position immediately; HOME and freeform prepare
                            // underneath this frame instead of holding it in the middle of the screen.
                            startMotion()
                            reply.send(1,null)
                        }
                    }}}
                }}
                override fun onViewDetachedFromWindow(v:android.view.View){}
            })
            wm.addView(root,p)
        }catch(error:Throwable){Guard.failure("transition-frame",error);clear();reply.send(0,null)}
    }
    fun maskTask(id:Int,nonce:String):Boolean {
        if(!matches(id,nonce))return false
        val surface=backend.surface(id)?:return false
        if(!masks.contains(surface))masks.add(surface)
        backend.alpha(surface,0f)
        return true
    }
    fun awaitTarget(id:Int,nonce:String,target:Rect,done:(Boolean)->Unit) {
        if(!matches(id,nonce)){done(false);return}
        if(target!=destination){
            Guard.info("transition target changed task=$id expected=${destination.toShortString()} actual=${target.toShortString()}")
            done(false);return
        }
        motion.await(done)
    }
    private fun startMotion() {
        if(!motion.begin())return
        val target=Rect(destination)
        val g=generation;val start=Rect(rect)
        var cancelled=false
        var frames=0;var previous=0L;var maxGap=0L
        if(backend.locked()){motion.complete(false);clear();return}
        Guard.info("transition frame motion started task=$task from=${start.toShortString()} to=${target.toShortString()}")
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=280;interpolator=DecelerateInterpolator()
            addUpdateListener{
                // Screen-off receiver cancels this overlay. Avoid keyguard/ATM Binder queries per frame.
                if(g!=generation){cancel();return@addUpdateListener}
                val t=it.animatedValue as Float
                val ok=Guard.call("transition-frame-animation"){
                    val p=params?:error("Frame layout missing")
                    val image=content?:error("Frame image missing")
                    val x=start.left+(target.left-start.left)*t
                    val y=start.top+(target.top-start.top)*t
                    val width=start.width()+(target.width()-start.width())*t
                    val height=start.height()+(target.height()-start.height())*t
                    image.translationX=x-p.x;image.translationY=y-p.y
                    image.scaleX=width/start.width();image.scaleY=height/start.height()
                    image.invalidateOutline()
                    rect.set(x.toInt(),y.toInt(),(x+width).toInt(),(y+height).toInt())
                    val now=SystemClock.uptimeMillis()
                    if(previous>0)maxGap=maxOf(maxGap,now-previous)
                    previous=now;frames++;true
                }==true
                if(!ok)cancel()
            }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){if(g==generation)Guard.call("transition-frame-end"){
                    Guard.info("transition frame motion ended task=$task cancelled=$cancelled frames=$frames maxGapMs=$maxGap relayouts=0")
                    motion.complete(!cancelled&&!backend.locked())
                }}
            })
            start()
        }
    }
    fun reveal(id:Int,nonce:String) {
        if(!matches(id,nonce))return
        unmask()
        val image=view?:return;val g=generation
        // Keep the final frame covering the same rect for two display frames while the real surface commits.
        image.postOnAnimation{image.postOnAnimation{if(g==generation)clear()}}
    }
    fun clearForTask(id:Int){if(id==task)clear()}
    fun cancel(nonce:String){if(nonce==token)clear()}
    private fun unmask() {
        if(masks.isEmpty()&&recoveryTask==task){journal.edit().remove("task").commit();recoveryTask=-1}
        masks.forEach{Guard.call("transition-frame-unmask"){backend.alpha(it,1f)}}
        if(masks.isNotEmpty())Guard.call("transition-frame-unmask"){backend.surface(task)?.let{
            backend.alpha(it,1f)
            if(recoveryTask==task){journal.edit().remove("task").commit();recoveryTask=-1}
        }}
        masks.clear()
    }
    fun recoverIfNeeded() {
        if(recoveryTask<0||task>=0)return
        Guard.call("transition-frame-recovery"){
            if(backend.info(recoveryTask)==null){
                journal.edit().remove("task").commit();recoveryTask=-1
                Guard.info("transition frame recovery cleared dead task")
                return@call
            }
            val source=backend.surface(recoveryTask)?:return@call
            backend.alpha(source,1f)
            journal.edit().remove("task").commit()
            Guard.info("transition frame visibility recovered task=$recoveryTask")
            recoveryTask=-1
        }
    }
    fun clear() {
        generation++
        motion.complete(false)
        captureJob?.cancel(false);captureJob=null
        animator?.cancel();animator=null
        unmask()
        view?.let{root->Guard.call("transition-frame-release"){
            content?.setImageDrawable(null);wm.removeViewImmediate(root)
        }}
        view=null;content=null;params=null;task=-1;token=""
    }
}
