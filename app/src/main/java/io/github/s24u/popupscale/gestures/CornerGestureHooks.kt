package io.github.s24u.popupscale.gestures

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.util.WeakHashMap

/** Only the native corner listener's own guide and matching asynchronous commit are adapted. */
class CornerGestureHooks(private val loader:ClassLoader,private val caps:RomCapabilities,
    private val eligible:(Any)->Boolean) {
    private data class Preview(val native:Rect,val centered:Rect,val width:Int,val height:Int,
        val rotation:Int,var released:Long=-1)
    private data class Session(val width:Int,val height:Int)
    private val previews=WeakHashMap<Any,Preview>() // Values never retain their Task keys.
    private val sessions=WeakHashMap<Any,Session>()
    private val pointer=ThreadLocal<Any>()
    private val committing=ThreadLocal<Any>()
    private fun screen(task:Any):Triple<Int,Int,Int> {
        val display=X.callMethod(task,"getDisplayContent")
        val info=X.getObjectField(display,"mDisplayInfo")
        return Triple(X.getIntField(info,"logicalWidth"),X.getIntField(info,"logicalHeight"),X.getIntField(info,"rotation"))
    }
    fun owns(task:Any):Boolean {
        if(committing.get()===task)return true
        val session=synchronized(sessions){sessions[task]} ?: return false
        val size=screen(task)
        if(X.callMethod(task,"getWindowingMode")==5&&size.first==session.width&&size.second==session.height)return true
        synchronized(sessions){sessions.remove(task)}
        return false
    }
    fun install(){
        check(caps.cornerGesture)
        val listener=X.findClass("com.android.server.wm.MultiWindowPointerEventListener",loader)
        X.findAndHookMethod(listener,"onPointerEvent",MotionEvent::class.java,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                p.setObjectExtra("corner.pointer.previous",pointer.get());pointer.set(p.thisObject)
                Guard.call("corner-pointer"){
                    val event=p.args[0] as MotionEvent
                    val task=Guard.field(p.thisObject,"mTask") ?: return@call
                    synchronized(previews){
                        if(event.actionMasked==MotionEvent.ACTION_CANCEL||event.pointerCount!=1||
                            event.actionMasked==MotionEvent.ACTION_DOWN)previews.remove(task)
                        else if(event.actionMasked==MotionEvent.ACTION_UP&&X.getBooleanField(p.thisObject,"mReadyToFreeform"))
                            previews[task]?.let{
                                it.released=SystemClock.uptimeMillis()
                                Guard.info("corner symmetric released task=${X.getIntField(task,"mTaskId")} bounds=${it.centered.toShortString()}")
                            }
                    }
                }
            }
            override fun afterHookedMethod(p:MethodHookParam){
                pointer.set(p.getObjectExtra("corner.pointer.previous"))
                // Native DOWN discovers the target only inside the original method.
                if((p.args[0] as MotionEvent).actionMasked==MotionEvent.ACTION_DOWN)
                    Guard.field(p.thisObject,"mTask")?.let{task->synchronized(previews){previews.remove(task)}}
            }
        })
        X.findAndHookMethod("com.samsung.android.multiwindow.FreeformResizeGuide",loader,"show",Rect::class.java,
            object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){Guard.call("corner-preview"){
                    if(!Guard.open("corner-preview"))return@call
                    val owner=pointer.get() ?: return@call
                    if(Guard.field(owner,"mFreeformResizeGuide")!==p.thisObject||
                        !X.getBooleanField(owner,"mReadyToFreeform")||X.getBooleanField(owner,"mNotSupport")||
                        !X.getBooleanField(owner,"mTaskResizable"))return@call
                    val task=Guard.field(owner,"mTask") ?: return@call
                    if(!eligible(task))return@call
                    val native=p.args[0] as? Rect ?: return@call
                    val size=screen(task)
                    val edge=X.callMethod(X.getObjectField(owner,"mMultiWindowEdgeDetector"),"getEdgeFlags") as Int
                    val bounds=CornerGesturePolicy.centered(size.first,size.second,edge,
                        native.left,native.top,native.right,native.bottom,
                        X.getIntField(owner,"mMinWidth"),X.getIntField(owner,"mMinHeight")) ?: return@call
                    val centered=Rect(bounds.x,bounds.y,bounds.x+bounds.width,bounds.y+bounds.height)
                    val first=synchronized(previews){
                        val fresh=!previews.containsKey(task)
                        previews[task]=Preview(Rect(native),Rect(centered),size.first,size.second,size.third)
                        fresh
                    }
                    // Never mutate mTmpRect: native MOVE rewrites only two of its sides.
                    p.args[0]=centered
                    if(first)Guard.info("corner symmetric preview task=${X.getIntField(task,"mTaskId")} edge=$edge bounds=${centered.toShortString()}")
                }}
            })
        X.findAndHookMethod("com.android.server.wm.FreeformController",loader,
            "setFreeformWindowingModeByCornerGestureLocked",Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Rect::class.java,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){Guard.call("corner-commit"){
                    val atm=X.getObjectField(p.thisObject,"mAtm")
                    val root=X.getObjectField(atm,"mRootWindowContainer")
                    val task=X.callMethod(root,"anyTaskForId",p.args[0] as Int,0,null,false) ?: return@call
                    val preview=synchronized(previews){previews.remove(task)} ?: return@call
                    val size=screen(task)
                    if(!eligible(task)||SystemClock.uptimeMillis()-preview.released !in 0..3000||
                        p.args[1]!=preview.rotation||size.third!=preview.rotation||
                        size.first!=preview.width||size.second!=preview.height||p.args[2]!=preview.native)return@call
                    p.setObjectExtra("corner.commit.task",task)
                    p.setObjectExtra("corner.commit.previous",committing.get())
                    committing.set(task)
                    synchronized(sessions){sessions[task]=Session(preview.width,preview.height)}
                    p.args[2]=Rect(preview.centered)
                }}
                override fun afterHookedMethod(p:MethodHookParam){
                    val task=p.getObjectExtra("corner.commit.task") ?: return
                    try{Guard.call("corner-commit-result"){
                        if(p.hasThrowable()||X.callMethod(task,"getWindowingMode")!=5){
                            synchronized(sessions){sessions.remove(task)};return@call
                        }
                        Guard.info("corner symmetric committed task=${X.getIntField(task,"mTaskId")} bounds=${(X.callMethod(task,"getBounds") as Rect).toShortString()}")
                    }}finally{committing.set(p.getObjectExtra("corner.commit.previous"))}
                }
            })
        XposedBridge.hookMethod(checkNotNull(caps.modeMethod),object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){Guard.call("corner-mode"){
                // A new ordinary opening of the same Task must not inherit this exemption.
                if(committing.get()!==p.thisObject&&(p.args[0]!=5||X.callMethod(p.thisObject,"getWindowingMode")!=5))
                    synchronized(sessions){sessions.remove(p.thisObject)}
            }}
        })
        Guard.info("native corner symmetric preview/commit ready")
    }
}
