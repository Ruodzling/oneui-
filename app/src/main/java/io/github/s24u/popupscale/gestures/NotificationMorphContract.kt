package io.github.s24u.popupscale.gestures

import java.lang.reflect.Method

object NotificationMorphContract {
    @JvmStatic fun callbackIndex(method:Method)=method.parameterTypes.indexOfLast{it.isInterface&&
        (it.name=="kotlin.jvm.functions.Function1"||it.name.endsWith("\$PendingIntentStarter"))}
    @JvmStatic fun starters(type:Class<*>)=type.declaredMethods.filter{m->
        (m.name.startsWith("startIntentWithAnimation")||m.name.startsWith("startPendingIntentWithAnimation"))&&callbackIndex(m)>=0
    }
    @JvmStatic fun entryClick(type:Class<*>,row:Class<*>):Method?=type.methods.firstOrNull{
        it.name=="onEntryClicked"&&it.parameterTypes.size==1&&it.parameterTypes[0].isAssignableFrom(row)
    }
    @JvmStatic fun keyguardIndex(method:Method)=method.parameterTypes.indexOfFirst{it.isInterface&&
        (it.name.endsWith("\$OnKeyguardDismissedAction")||it.name.endsWith("\$OnDismissAction"))}
    @JvmStatic fun keyguardCallbacks(type:Class<*>)=type.declaredMethods.filter{
        (it.name=="performActionAfterKeyguardDismissed"||it.name=="dismissKeyguardThenExecute")&&keyguardIndex(it)>=0
    }
}
