package io.github.s24u.popupscale.gestures

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.ref.WeakReference

/** Finish the ROM's own Recents session and verify its actual UI state before hanging. */
class LauncherHomeBridge(private val loader:ClassLoader,private val config:FeatureConfig) {
    private val main=Handler(Looper.getMainLooper())
    private var manager=WeakReference<Any>(null)
    private var generation=0
    private var gestureTask=-1
    fun install() {
        val type=X.findClass("com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",loader)
        XposedBridge.hookAllMethods(type,"start",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){Guard.call("launcher-home-capture"){
                if(X.callMethod(p.thisObject,"getDisplayId")==0){
                    manager=WeakReference(p.thisObject)
                    gestureTask=p.args.firstOrNull() as? Int ?: -1
                }
            }}
        })
    }
    fun cancelPending(){generation++;gestureTask=-1}
    fun ticket()=generation
    fun currentRect(taskId:Int):Rect?=Guard.call("launcher-frame-bounds"){
        if(gestureTask!=taskId)return@call null
        val action=manager.get()?:return@call null
        val player=X.getObjectField(action,"appPlayer")?:return@call null
        val bounds=X.callMethod(player,"getCurrentRectF") as RectF
        if(!bounds.left.isFinite()||!bounds.top.isFinite()||!bounds.right.isFinite()||!bounds.bottom.isFinite())return@call null
        Rect().also{bounds.roundOut(it)}
    }?.let{it}
    fun moving(taskId:Int):Boolean=currentRect(taskId)?.let{it.width()>32&&it.height()>32}==true
    private fun markFinishToHome(action:Any){
        if(X.findMethodExactIfExists(action.javaClass,"willFinishToHome")!=null)X.callMethod(action,"willFinishToHome")
        else if(X.findFieldIfExists(action.javaClass,"cancelToHome")!=null)X.setBooleanField(action,"cancelToHome",true)
    }
    fun chooseHome():Boolean = Guard.call("launcher-home-target") {
        val action=manager.get()?:return@call false
        markFinishToHome(action)
        true
    }==true
    fun finish(context:Context,ticket:Int,done:(Boolean)->Unit) {
        if(ticket!=generation){done(false);return}
        val g=++generation
        val action=manager.get()
        if(action==null){done(false);return}
        val screen=Guard.call("launcher-home-finish") {
            markFinishToHome(action)
            X.callMethod(action,"finish")
            // This ROM method also cleans the input consumer and emits GestureFinished.
            X.callMethod(action,"finishController",true,false)
            val utility=X.getStaticObjectField(X.findClass("com.honeyspace.ui.common.ScreenManagerUtil",loader),"INSTANCE")
            X.callMethod(utility,"getScreenManager",context)
        }
        if(screen==null){done(false);return}
        awaitNormal(screen,g,30,0,done)
    }
    private fun awaitNormal(screen:Any,g:Int,remaining:Int,stable:Int,done:(Boolean)->Unit) {
        if(g!=generation){done(false);return}
        if(!Guard.open("launcher-home-state")){done(false);return}
        config.reload()
        if(!config.enabled||config.observe||!config.live||!config.hang){done(false);return}
        var detail="unavailable"
        val settled=Guard.call("launcher-home-state") {
            val companion=X.getStaticObjectField(X.findClass("com.honeyspace.transition.ShellTransitionManager",loader),"Companion")
            val running=X.callMethod(X.callMethod(companion,"getTransitionRunning"),"getValue")==true
            val input=X.callMethod(X.callMethod(companion,"getRecentsAnimInputConsumerFocusing"),"getValue")==true
            detail="transition=$running recentsInput=$input"
            if(running||input)return@call false
            val normal=X.getStaticObjectField(X.findClass("com.honeyspace.sdk.HomeScreen\$Normal",loader),"INSTANCE")
            if(X.callMethod(screen,"isNormalHomescreen")!=true) {
                X.callMethod(screen,"endOnGoingAnimation")
                X.callMethod(screen,"gotoScreen",normal)
            }
            val normalState=X.callMethod(screen,"isNormalHomescreen")==true
            val animating=X.callMethod(screen,"isOnGoingAnimationRunning")==true
            val changing=X.callMethod(screen,"isOnStateTransition")==true
            detail+=" normal=$normalState animating=$animating changing=$changing"
            normalState&&!animating&&!changing
        }==true
        if(settled&&stable>=1){Guard.info("launcher HOME Normal and Recents/input idle confirmed");done(true);return}
        if(remaining<=0){Guard.info("live hang skipped: launcher Recents/UI did not settle to HOME Normal $detail");done(false);return}
        main.postDelayed({awaitNormal(screen,g,remaining-1,if(settled)stable+1 else 0,done)},60)
    }
}
