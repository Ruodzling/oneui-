package io.github.s24u.popupscale.gestures

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** The native animator may invoke its starter asynchronously. Carry scope with that callback. */
class NotificationCallbackScope<T> {
    private val active=ThreadLocal<T?>()
    fun current():T?=active.get()
    fun <R> within(value:T,block:()->R):R {
        val previous=active.get();active.set(value)
        try{return block()}finally{if(previous==null)active.remove()else active.set(previous)}
    }
    fun wrap(value:T,type:Class<*>,original:Any):Any =
        Proxy.newProxyInstance(type.classLoader,arrayOf(type)){self,method,args->
            when(method.name){
                "hashCode"->System.identityHashCode(self)
                "equals"->self===args?.getOrNull(0)
                "toString"->"NotificationAnimationStarter"
                else->within(value){
                    try{method.invoke(original,*(args?:emptyArray()))}
                    catch(error:InvocationTargetException){throw error.cause?:error}
                }
            }
        }
}
