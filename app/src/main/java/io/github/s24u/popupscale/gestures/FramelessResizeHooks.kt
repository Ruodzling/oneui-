package io.github.s24u.popupscale.gestures

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X

class FramelessResizeHooks(private val loader:ClassLoader,private val state:FramelessTaskState) {
    private val reported=mutableSetOf<Int>()
    fun install(){
        val listener=X.findClassIfExists("com.android.wm.shell.windowdecor.DragResizeInputListener",loader)
        if(listener==null){Guard.info("frameless resize listener unavailable");return}
        val geometry=X.findClassIfExists("com.android.wm.shell.windowdecor.DragResizeWindowGeometry",loader)
        if(geometry==null){Guard.info("frameless resize geometry unavailable");return}
        val methods=listener.declaredMethods.filter{it.name=="setGeometry"&&it.parameterTypes.firstOrNull()==geometry}
        for(method in methods)XposedBridge.hookMethod(method,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!Guard.open("frameless-resize-geometry"))return
                Guard.call("frameless-resize-geometry"){
                    val info=Guard.field(p.thisObject,"mTaskInfo")?:return@call
                    val entry=state.observe(info)
                    if(entry!=null&&entry.caption<=0){
                        val caption=readCaptionHeight(p.thisObject)
                        if(caption>0)entry.caption=caption
                    }
                    val shape=p.args[0]
                    val density=android.content.res.Resources.getSystem().displayMetrics.density
                    val changed=FramelessResizeGeometry.apply(shape,entry?.caption?:0,density)
                    if(changed){
                        // Same-object native equals would otherwise skip input-channel refresh.
                        val receiver=Guard.field(p.thisObject,"mInputEventReceiver")?:return@call
                        val old=Guard.field(receiver,"mDragResizeWindowGeometry")
                        if(old===shape){
                            p.setObjectExtra("popupscale.frameless.resize.receiver",receiver)
                            p.setObjectExtra("popupscale.frameless.resize.old",old)
                            X.setObjectField(receiver,"mDragResizeWindowGeometry",null)
                        }
                        if(entry!=null&&reported.size<128&&reported.add(entry.id))
                            Guard.info("frameless resize input aligned task=${entry.id} top=${entry.caption}; top edge disabled; upper touch corners +8dp/pointer +4dp; central caption lane retained")
                    }
                }
            }
            override fun afterHookedMethod(p:MethodHookParam){
                if(!p.hasThrowable())return
                val receiver=p.getObjectExtra("popupscale.frameless.resize.receiver")?:return
                Guard.call("frameless-resize-restore"){
                    X.setObjectField(receiver,"mDragResizeWindowGeometry",p.getObjectExtra("popupscale.frameless.resize.old"))
                }
            }
        })
        Guard.info("frameless resize native geometry hook ready count=${methods.size}")
    }
    private fun readCaptionHeight(listener:Any):Int=Guard.call("frameless-resize-caption-height"){
        val receiver=Guard.field(listener,"mInputEventReceiver")
        val callback=receiver?.let{Guard.field(it,"mCallback")}?:Guard.field(listener,"mCallback")
        val decor=callback?.let{Guard.field(it,"mWindowDecoration","windowDecoration","mDesktopWindowDecoration")}
        val controller=decor?.let{Guard.field(it,"captionController","mCaptionController")}?:return@call 0
        (X.callMethod(controller,"getCaptionHeight") as? Int)?:0
    }?:0
}
