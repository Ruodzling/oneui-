package io.github.s24u.popupscale.gestures

import java.lang.reflect.Method
import java.lang.reflect.Modifier

object NotificationRouteMethods {
    fun send(type:Class<*>?):Method?=type?.declaredMethods?.filter{
        it.name=="sendInner"&&it.returnType==Integer.TYPE&&!Modifier.isStatic(it.modifiers)&&
            it.parameterTypes.count{p->p.name=="android.os.Bundle"}==1
    }?.singleOrNull()
    fun initial(type:Class<*>?):Method?=type?.declaredMethods?.filter{
        it.name=="setInitialState"&&it.returnType==Void.TYPE&&!Modifier.isStatic(it.modifiers)&&
            it.parameterTypes.map{p->p.name}.take(6)==listOf("com.android.server.wm.ActivityRecord",
                "android.app.ActivityOptions","com.android.server.wm.Task","com.android.server.wm.TaskFragment",
                "int","com.android.server.wm.ActivityRecord")
    }?.singleOrNull()
}
