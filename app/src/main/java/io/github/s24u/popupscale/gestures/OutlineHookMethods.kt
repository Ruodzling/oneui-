package io.github.s24u.popupscale.gestures

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** These callbacks supply no geometry to us: bind the native View after its own update.
 * Parameter additions between One UI releases therefore need no guessed argument values. */
object OutlineHookMethods {
    @JvmStatic fun owners(type:Class<*>):List<Method> = methods(type).filter {
        it.name in setOf("updateOutlineView","relayout")&&
            it.parameterTypes.any{p->p.name=="android.app.ActivityManager\$RunningTaskInfo"}&&
            !Modifier.isStatic(it.modifiers)&&!Modifier.isAbstract(it.modifiers)
    }
    /** Known native container path only; never hide an arbitrary decoration child. */
    @JvmStatic fun ownerView(owner:Any,outline:Class<*>):android.view.View? {
        fun field(value:Any,name:String):Any?=generateSequence(value.javaClass){it.superclass}
            .mapNotNull{runCatching{it.getDeclaredField(name).apply{isAccessible=true}.get(value)}.getOrNull()}
            .firstOrNull()
        val container=field(owner,"mFreeformOutline")?:return null
        val view=if(outline.isInstance(container))container else
            runCatching{container.javaClass.getMethod("getView").apply{isAccessible=true}.invoke(container)}.getOrNull()
                ?:field(container,"view")?:field(container,"mView")
        return view?.takeIf{outline.isInstance(it)} as? android.view.View
    }
    @JvmStatic fun updates(type:Class<*>):List<Method> = methods(type).filter {
        it.name=="updateOutlineViewData"&&it.returnType in setOf(Void.TYPE,java.lang.Boolean.TYPE)&&
            !Modifier.isStatic(it.modifiers)&&!Modifier.isAbstract(it.modifiers)
    }
    @JvmStatic fun draws(type:Class<*>):List<Method> = methods(type).filter {
        it.name in setOf("draw","onDraw")&&it.returnType==Void.TYPE&&
            it.parameterTypes.map{p->p.name}==listOf("android.graphics.Canvas")&&
            !Modifier.isStatic(it.modifiers)&&!Modifier.isAbstract(it.modifiers)
    }
    private fun methods(type:Class<*>):List<Method> = generateSequence(type){it.superclass}
        // Never hook View.draw globally just because a ROM inherits the default method.
        .takeWhile{it.name!="android.view.View"&&it!=Any::class.java}
        .flatMap{runCatching{it.declaredMethods.toList()}.getOrDefault(emptyList()).asSequence()}
        .distinctBy{it.name+it.parameterTypes.joinToString{p->p.name}}.toList()
}
