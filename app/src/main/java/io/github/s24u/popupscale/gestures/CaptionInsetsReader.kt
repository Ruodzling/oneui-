package io.github.s24u.popupscale.gestures

import android.graphics.Insets
import android.graphics.Rect
import android.view.WindowInsets
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/** Exact native Insets overload; attached sources on newer ROMs need their task bounds. */
@android.annotation.TargetApi(30)
class CaptionInsetsReader private constructor(
    private val method:Method,
    private val copyCtor:Constructor<*>?,
    private val sourceSizeMethod:Method?,
    private val sourceAtMethod:Method?,
    private val removeSourceAtMethod:Method?,
    private val removeSourceMethod:Method?
) {
    private var sourceTypeMethod:Method?=null
    private var sourceIdMethod:Method?=null
    val arity get()=method.parameterCount
    val canStripCaption:Boolean
        get()=copyCtor!=null&&sourceSizeMethod!=null&&sourceAtMethod!=null&&
            (removeSourceAtMethod!=null||removeSourceMethod!=null)
    fun calculate(state:Any,frame:Rect,bounds:Rect):Insets =
        (if(arity==4)method.invoke(state,frame,bounds,WindowInsets.Type.captionBar(),true)
        else method.invoke(state,frame,WindowInsets.Type.captionBar(),true)) as Insets

    /** Returns a copy with captionBar sources removed when present; returns [state] unchanged otherwise. */
    fun stripCaptionBar(state:Any):Any {
        val ctor=copyCtor ?: return state
        val sizeFn=sourceSizeMethod ?: return state
        val atFn=sourceAtMethod ?: return state
        val captionMask=WindowInsets.Type.captionBar()
        val count=sizeFn.invoke(state) as? Int ?: return state
        var indices:MutableList<Int>?=null
        for(i in (count-1) downTo 0){
            val source=atFn.invoke(state,i) ?: continue
            val typeFn=sourceTypeMethod ?: runCatching{source.javaClass.getMethod("getType").apply{isAccessible=true}}.getOrNull()?.also{sourceTypeMethod=it} ?: return state
            val type=typeFn.invoke(source) as? Int ?: continue
            if((type and captionMask)!=0){
                if(indices==null)indices=mutableListOf()
                indices.add(i)
            }
        }
        val toRemove=indices ?: return state
        val copy=if(ctor.parameterCount==2)ctor.newInstance(state,true) else ctor.newInstance(state)
        for(i in toRemove){
            val removeAt=removeSourceAtMethod
            if(removeAt!=null){
                removeAt.invoke(copy,i)
            }else{
                val removeById=removeSourceMethod ?: continue
                val source=atFn.invoke(copy,i) ?: continue
                val idFn=sourceIdMethod ?: runCatching{source.javaClass.getMethod("getId").apply{isAccessible=true}}.getOrNull()?.also{sourceIdMethod=it} ?: continue
                val id=idFn.invoke(source) as? Int ?: continue
                removeById.invoke(copy,id)
            }
        }
        return copy
    }

    companion object {
        @JvmStatic fun create(type:Class<*>):CaptionInsetsReader? {
            val method=runCatching{type.getMethod("calculateInsets",Rect::class.java,Rect::class.java,Integer.TYPE,java.lang.Boolean.TYPE)}.getOrNull()
                ?:runCatching{type.getMethod("calculateInsets",Rect::class.java,Integer.TYPE,java.lang.Boolean.TYPE)}.getOrNull()
                ?:return null
            method.isAccessible=true
            val copyCtor=runCatching{type.getConstructor(type,java.lang.Boolean.TYPE).apply{isAccessible=true}}.getOrNull()
                ?:runCatching{type.getConstructor(type).apply{isAccessible=true}}.getOrNull()
            val sourceSize=runCatching{type.getMethod("sourceSize").apply{isAccessible=true}}.getOrNull()
            val sourceAt=runCatching{type.getMethod("sourceAt",Integer.TYPE).apply{isAccessible=true}}.getOrNull()
            val removeSourceAt=runCatching{type.getMethod("removeSourceAt",Integer.TYPE).apply{isAccessible=true}}.getOrNull()
            val removeSource=runCatching{type.getMethod("removeSource",Integer.TYPE).apply{isAccessible=true}}.getOrNull()
            return CaptionInsetsReader(method,copyCtor,sourceSize,sourceAt,removeSourceAt,removeSource)
        }
    }
}
