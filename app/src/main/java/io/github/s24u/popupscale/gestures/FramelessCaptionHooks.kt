package io.github.s24u.popupscale.gestures

import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X

/** Hide the independent native outline, not the caption/input surface. */
class FramelessCaptionHooks(private val loader:ClassLoader,private val config:FeatureConfig,
                            private val tasks:TaskBackend) {
    private val state=FramelessTaskState(config)
    private val installed=mutableSetOf<java.lang.reflect.Method>()
    private val reported=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val outlineOwners=java.util.Collections.synchronizedMap(java.util.WeakHashMap<View,FramelessTaskState.Entry>())
    private val outlineVisibility=FramelessOutlineVisibility()
    private fun traceOutline(view:View,owner:FramelessTaskState.Entry?,stage:String,callback:String){
    }

    private fun bindOutline(view:View,callback:String):Boolean {
        val title=(view.layoutParams as? WindowManager.LayoutParams)?.title?.toString()
        val previous=outlineOwners[view]
        val id=CaptionFillPolicy.taskFromHostTitle(title)?:previous?.id?:return false
        // Organizer fallback only at a native style/bind event, never draw/pre-draw.
        val owner=state.get(id)?:tasks.info(id)?.let{state.observe(it)}
        if(owner==null){outlineOwners.remove(view);outlineVisibility.apply(view,false);return false}
        outlineOwners[view]=owner
        traceOutline(view,owner,"before-hide",callback)
        outlineVisibility.apply(view,true)
        traceOutline(view,owner,"after-hide",callback)
        return true
    }
    private fun outlineActive(view:View):Boolean {
        val owner=outlineOwners[view]?:return false
        val current=state.get(owner.id)
        // A verified retiring outline stays hidden until removal; disabling restores it.
        val active=state.active()&&config.acceptsInteraction(owner.packageName)&&
            (current==null||current.token==owner.token&&current.user==owner.user)
        if(!active)outlineVisibility.apply(view,false)
        return active
    }
    private fun installOutline(){
        val type=X.findClassIfExists("com.android.wm.shell.windowdecor.widget.OutlineView",loader)?:return
        val updates=OutlineHookMethods.updates(type)
        for(update in updates)Guard.call("frameless-outline-update-install"){XposedBridge.hookMethod(update,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable())return
                Guard.call("frameless-outline-bind"){bindOutline(p.thisObject as View,"OutlineView."+update.name)}
            }
        })}
        // Independent registration: a changed style descriptor must never skip owner
        // binding or the draw barrier, as happened on the Android 15/16 reports.
        Guard.call("frameless-outline-owner-install"){X.findClassIfExists("com.android.wm.shell.windowdecor.FreeformOutline",loader)?.let{native->
            for(method in OutlineHookMethods.owners(native))XposedBridge.hookMethod(method,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    Guard.call("frameless-outline-owner"){
                        val info=p.args.firstOrNull{it?.javaClass?.name=="android.app.ActivityManager\$RunningTaskInfo"}?:return@call
                        val view=p.args.filterIsInstance<View>().firstOrNull{type.isInstance(it)}
                            ?:OutlineHookMethods.ownerView(p.thisObject,type)?:run{
                                return@call
                            }
                        val owner=state.observe(info)
                        traceOutline(view,owner,"before-hide","FreeformOutline."+method.name)
                        if(owner==null)outlineOwners.remove(view) else outlineOwners[view]=owner
                        outlineVisibility.apply(view,owner!=null)
                        traceOutline(view,owner,"after-hide","FreeformOutline."+method.name)
                        if(owner!=null&&reported.size<128&&reported.add(-owner.id))
                            Guard.info("frameless outline owner bound task=${owner.id} callback=${method.name}")
                    }
                }
            })
        }}
        val draws=OutlineHookMethods.draws(type)
        for(draw in draws)Guard.call("frameless-outline-draw-install"){XposedBridge.hookMethod(draw,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                Guard.call("frameless-outline-draw"){
                    val view=p.thisObject as View
                    if(outlineActive(view)){
                        // No temporary Paint restore or organizer lookup on a rendering frame.
                        outlineVisibility.apply(view,true)
                        p.result=null
                    }
                }
            }
        })}
        Guard.info("frameless outline callbacks updates=${updates.joinToString{it.toString()}} draws=${draws.size}; native non-input node, no organizer calls in draw")
    }
    private fun owner(p:XC_MethodHook.MethodHookParam):FramelessTaskState.Entry? {
        val info=Guard.field(p.thisObject,"taskInfo","mTaskInfo")?:return null
        val entry=state.observe(info)?:return null
        val caption=Guard.field(p.args[0],"captionType")?.toString()
        val holder=Guard.field(p.thisObject,"mWindowDecorViewHolder","windowDecorationViewHolder")
        val handle=if(caption!=null)caption=="APP_HANDLE"
            else holder?.let{Guard.field(it,"captionHandle","mHandleView") is View}==true
        if(!handle)return null
        val controller=Guard.field(p.thisObject,"captionController")
        if(controller!=null){
            val height=(X.callMethod(controller,"getCaptionHeight") as? Int)?:0
            if(height>0)entry.caption=height
        }
        return entry
    }
    fun install(decorations:List<String>){
        config.reload()
        X.findClassIfExists("com.android.wm.shell.ShellTaskOrganizer",loader)?.let{organizer->
            for(name in listOf("onTaskInfoChanged","onTaskVanished"))XposedBridge.hookAllMethods(organizer,name,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    Guard.call("frameless-task-metadata"){
                        val info=p.args.firstOrNull{it?.javaClass?.name=="android.app.ActivityManager\$RunningTaskInfo"}?:return@call
                        if(name=="onTaskVanished")state.forget(X.getIntField(info,"taskId")) else state.observe(info)
                    }
                }
            })
        }
        Guard.call("frameless-outline-install"){installOutline()}
        Guard.call("frameless-caption-anchor-install"){CaptionAnchorHooks(loader,state).install()}
        Guard.call("frameless-resize-install"){FramelessResizeHooks(loader,state).install()}
        if(android.os.Build.VERSION.SDK_INT<29)return
        if(X.findMethodExactIfExists(SurfaceControl.Transaction::class.java,"unsetColor",SurfaceControl::class.java)==null)return
        for(name in listOf("com.android.wm.shell.windowdecor.WindowDecoration2",
            "com.android.wm.shell.windowdecor.WindowDecoration")+decorations){
            val type=X.findClassIfExists(name,loader)?:continue
            for(method in type.declaredMethods){
                val params=method.parameterTypes
                if(method.name !in setOf("updateTaskSurface","updateTaskSurfaceOutline")||params.size<3||
                    params[1]!=SurfaceControl.Transaction::class.java||params[2]!=SurfaceControl.Transaction::class.java||
                    !installed.add(method))continue
                XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun afterHookedMethod(p:MethodHookParam){
                        if(p.hasThrowable())return
                        Guard.call("frameless-caption-fill"){
                            val entry=owner(p)?:return@call
                            val surface=Guard.field(p.thisObject,"taskSurface","mTaskSurface")?:return@call
                            if(X.callMethod(surface,"isValid")!=true)return@call
                            for(tx in listOf(p.args[1],p.args[2]).distinct()){
                                // Old task bounds still include the caption. Its shadow would
                                // leave a faint full-width ghost above the visible content.
                                FramelessSurfaceStyle.apply(tx,surface)
                            }
                            if(reported.size<128&&reported.add(entry.id))Guard.info("frameless caption fill/shadow cleared task=${entry.id} backend=${type.simpleName} caption=${entry.caption}")
                        }
                    }
                })
            }
        }
        Guard.info("frameless caption surface hooks installed count=${installed.size} ${FramelessSurfaceStyle.describe()}")
    }
}
