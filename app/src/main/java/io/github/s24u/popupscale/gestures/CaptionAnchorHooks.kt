package io.github.s24u.popupscale.gestures

import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.ref.WeakReference

/** Move the real caption button, so its own hit target moves with the visible pill. */
class CaptionAnchorHooks(private val loader:ClassLoader,private val state:FramelessTaskState) {
    private data class Anchor(val task:Int,val user:Int,val token:Any,val root:WeakReference<View>,
                              val nativeY:Float,var caption:Float,val density:Float,var delta:Float=0f)
    private val anchors=java.util.WeakHashMap<ImageView,Anchor>() // Native caption/UI thread.
    private val registered=java.util.Collections.newSetFromMap(java.util.WeakHashMap<ImageView,Boolean>())
    private var reports=0
    fun install(){
        val type=X.findClassIfExists("com.android.wm.shell.windowdecor.caption.CaptionController",loader)?:return
        XposedBridge.hookAllMethods(type,"updateViewHierarchy",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                Guard.call("caption-anchor-bind"){
                    val root=p.args.getOrNull(2) as? View?:return@call
                    val holder=Guard.field(p.thisObject,"windowDecorationViewHolder")?:return@call
                    val bar=Guard.field(holder,"captionHandle") as? ImageView?:return@call
                    val info=Guard.field(p.thisObject,"taskInfo")?:return@call
                    val task=state.observe(info)
                    if(task==null||X.callMethod(p.thisObject,"getCaptionType").toString()!="APP_HANDLE"){
                        anchors.remove(bar)?.let{bar.translationY=it.nativeY};return@call
                    }
                    val caption=(X.callMethod(p.thisObject,"getCaptionHeight") as Int).toFloat()
                    if(caption>0)task.caption=caption.toInt()
                    val identity=task.token;val previous=anchors[bar]
                    if(previous!=null&&previous.task==task.id&&previous.user==task.user&&previous.token==identity){
                        previous.caption=caption
                    }else{
                        if(previous!=null)bar.translationY=previous.nativeY
                        anchors[bar]=Anchor(task.id,task.user,identity,WeakReference(root),bar.translationY,caption,
                            android.content.res.Resources.getSystem().displayMetrics.density)
                    }
                    if(registered.add(bar))watch(bar)
                    align(bar)
                }
            }
        })
        Guard.info("frameless caption anchor ready: real button translation, native input retained")
    }
    private fun watch(bar:ImageView){
        val weak=WeakReference(bar)
        val beforeDraw=ViewTreeObserver.OnPreDrawListener{
            weak.get()?.let{Guard.call("caption-anchor-frame"){align(it)}};true
        }
        bar.addOnLayoutChangeListener{view,_,_,_,_,_,_,_,_->Guard.call("caption-anchor-layout"){align(view as ImageView)}}
        bar.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){v.viewTreeObserver.addOnPreDrawListener(beforeDraw)}
            override fun onViewDetachedFromWindow(v:View){if(v.viewTreeObserver.isAlive)v.viewTreeObserver.removeOnPreDrawListener(beforeDraw)}
        })
        if(bar.isAttachedToWindow)bar.viewTreeObserver.addOnPreDrawListener(beforeDraw)
    }
    private fun align(bar:ImageView){
        val entry=anchors[bar]?:return
        val current=state.get(entry.task)
        if(current==null||current.user!=entry.user||current.token!=entry.token){
            if(bar.translationY!=entry.nativeY)bar.translationY=entry.nativeY
            entry.delta=0f;return
        }
        val root=entry.root.get()?:return
        val bottom=CaptionAnchorGeometry.visualBottom(bar,root,entry.nativeY)?:return
        val delta=CaptionAnchorPolicy.shift(entry.caption,bottom,entry.density)
        val target=entry.nativeY+delta
        if(kotlin.math.abs(bar.translationY-target)>.1f)bar.translationY=target
        if(reports<96&&kotlin.math.abs(entry.delta-delta)>.5f){
            reports++;Guard.info("frameless caption anchor task=${entry.task} shift=$delta nativeBottom=$bottom contentTop=${entry.caption} gap=${entry.caption-bottom-delta}")
        }
        entry.delta=delta
    }
}
