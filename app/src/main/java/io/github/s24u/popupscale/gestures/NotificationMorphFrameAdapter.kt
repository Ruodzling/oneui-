package io.github.s24u.popupscale.gestures

import android.graphics.Point
import android.graphics.RectF
import java.lang.reflect.Modifier

/** Native notification transforms assume a zero-based buffer, unlike freeform screen bounds.
 * Make a drawing-only copy. Never change the surface used to choose the animation destination. */
object NotificationMorphFrameAdapter {
    private fun field(value:Any,name:String)=generateSequence(value.javaClass){it.superclass}
        .firstNotNullOf{runCatching{it.getDeclaredField(name).apply{isAccessible=true}}.getOrNull()}
    private fun copy(value:Any,type:Class<*> = value.javaClass):Any {
        val result=type.getDeclaredConstructor().apply{isAccessible=true}.newInstance()
        for(owner in generateSequence(type){it.superclass})for(f in owner.declaredFields){
            if(Modifier.isStatic(f.modifiers))continue
            f.isAccessible=true;f.set(result,f.get(value))
        }
        return result
    }
    @JvmStatic fun localSurface(surface:Any):Any {
        val end=field(surface,"endState").get(surface)?:error("Native end state missing")
        val bounds=field(end,"bounds").get(end) as RectF
        check(bounds.width()>0&&bounds.height()>0){"Invalid native window geometry"}
        val localEnd=copy(end)
        field(localEnd,"bounds").set(localEnd,RectF(0f,0f,bounds.width(),bounds.height()))
        return copy(surface).also{field(it,"endState").set(it,localEnd)}
    }
    @JvmStatic fun parentState(state:Any,offset:Point):Any {
        if(offset.x==0&&offset.y==0)return state
        val type=Class.forName("com.android.systemui.animation.TransitionAnimator\$State",false,state.javaClass.classLoader)
        return copy(state,type).also{local->
            for(name in arrayOf("left","right")){val f=field(local,name);f.setInt(local,f.getInt(local)-offset.x)}
            for(name in arrayOf("top","bottom")){val f=field(local,name);f.setInt(local,f.getInt(local)-offset.y)}
        }
    }
}
