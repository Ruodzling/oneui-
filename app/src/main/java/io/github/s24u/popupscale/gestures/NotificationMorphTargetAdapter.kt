package io.github.s24u.popupscale.gestures

import android.content.ComponentName
import android.graphics.Rect
import android.graphics.Point
import android.view.SurfaceControl

/** Select only the real task of this explicit notification launch. Never rewrite native target flags. */
@android.annotation.TargetApi(29)
class NotificationMorphTargetAdapter(loader:ClassLoader) {
    data class Selection(val surface:Any,val task:Int,val mode:Int,val translucent:Boolean)
    private val utils=Class.forName("com.android.wm.shell.shared.compat.AnimatedSurfaceUtils",false,loader)
    private val legacy=utils.methods.first{it.name=="from"&&it.parameterTypes.firstOrNull()?.name=="android.view.RemoteAnimationTarget"}
    private val shell=utils.methods.first{it.name=="from"&&it.parameterTypes.firstOrNull()?.name=="android.window.TransitionInfo\$Change"}
    private fun field(value:Any,name:String):Any?=generateSequence(value.javaClass){it.superclass}.firstNotNullOfOrNull{
        runCatching{it.getDeclaredField(name).apply{isAccessible=true}.get(value)}.getOrNull()
    }
    private fun call(value:Any,name:String)=runCatching{value.javaClass.getMethod(name).invoke(value)}.getOrNull()
    private fun number(value:Any,name:String)=(field(value,name) as? Number)?.toInt()?:-1
    private fun eligible(info:Any?,bounds:Rect?,leash:Any?,mode:Int,isShell:Boolean,pkg:String,user:Int):Boolean {
        if(info==null||bounds==null)return false
        val component=(field(info,"topActivity")?:field(info,"baseActivity")) as? ComponentName
        val config=field(info,"configuration")?:return false
        val wc=field(config,"windowConfiguration")?:return false
        return NotificationMorphTargetPolicy.accepts(pkg,user,component?.packageName,number(info,"userId"),
            number(info,"displayId"),(call(wc,"getActivityType") as? Number)?.toInt()?:-1,
            (call(wc,"getWindowingMode") as? Number)?.toInt()?:-1,mode,isShell,number(info,"taskId"),
            bounds.width(),bounds.height(),(leash as? SurfaceControl)?.isValid==true)
    }
    fun legacy(targets:Array<*>?,states:Array<*>?,pkg:String,user:Int):Selection? {
        if(targets==null)return null
        val matches=targets.indices.filter{i->val target=targets[i]?:return@filter false
            eligible(field(target,"taskInfo"),field(target,"screenSpaceBounds") as? Rect,field(target,"leash"),number(target,"mode"),false,pkg,user)}
        val index=matches.singleOrNull()?:return null // Ambiguous targets retain the native decision.
        val target=targets[index]!!
        val state=states?.takeIf{it.size==targets.size}?.get(index)
        return Selection(legacy.invoke(null,target,state),number(field(target,"taskInfo")!!,"taskId"),
            number(target,"mode"),field(target,"isTranslucent")==true)
    }
    fun shellChange(info:Any,pkg:String,user:Int):Any? {
        val changes=call(info,"getChanges") as? List<*>?:return null
        val matches=changes.indices.filter{i->val change=changes[i]?:return@filter false
            eligible(call(change,"getTaskInfo"),call(change,"getEndAbsBounds") as? Rect,call(change,"getLeash"),
                (call(change,"getMode") as? Number)?.toInt()?:-1,true,pkg,user)}
        val index=matches.singleOrNull()?:return null
        return changes[index]
    }
    fun shell(info:Any,states:Array<*>?,pkg:String,user:Int):Selection? {
        val change=shellChange(info,pkg,user)?:return null
        val changes=call(info,"getChanges") as List<*>
        val index=changes.indexOfFirst{it===change}
        val state=states?.takeIf{it.size==changes.size}?.get(index)
        return Selection(shell.invoke(null,change,state,changes.size-index),number(call(change,"getTaskInfo")!!,"taskId"),
            (call(change,"getMode") as Number).toInt(),false)
    }
    fun ownsSurface(surface:Any,pkg:String,user:Int):Boolean {
        val mode=number(surface,"mode")
        // AnimatedSurface uses OPENING=1, CLOSING=0, CHANGING=2.
        return eligible(field(surface,"taskInfo"),field(surface,"screenSpaceBounds") as? Rect,
            field(surface,"leash"),if(mode==1)0 else if(mode==2)2 else -1,false,pkg,user)
    }
    fun rootOffset(info:Any,pkg:String,user:Int):Point? {
        val change=shellChange(info,pkg,user)?:return null
        val util=Class.forName("com.android.wm.shell.shared.TransitionUtil",false,utils.classLoader)
        val method=util.methods.firstOrNull{it.name=="rootIndexFor"&&it.parameterTypes.size==2}?:return null
        val index=(method.invoke(null,change,info) as Number).toInt()
        if(index<0)return null
        val root=info.javaClass.getMethod("getRoot",Integer.TYPE).invoke(info,index)?:return null
        return (call(root,"getOffset") as? Point)?.let{Point(it)}
    }
}
