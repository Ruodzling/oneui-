package io.github.s24u.popupscale.gestures

/** Clear legacy task-frame styles in the caller's native transaction; never applies it. */
object FramelessSurfaceStyle {
    private val surface=Class.forName("android.view.SurfaceControl")
    private val transaction=Class.forName("android.view.SurfaceControl\$Transaction")
    private val color=transaction.getMethod("unsetColor",surface)
    private val shadow=transaction.getMethod("setShadowRadius",surface,java.lang.Float.TYPE)
    private data class Setting(val method:java.lang.reflect.Method,val value:Any)
    private fun optional(name:String,setter:String,array:String?=null):Setting?=runCatching{
        val type=Class.forName(name)
        val method=transaction.getMethod(setter,surface,type)
        val value=type.getDeclaredConstructor().newInstance()
        if(array!=null){val field=type.getField(array);field.set(value,java.lang.reflect.Array.newInstance(field.type.componentType!!,0))}
        Setting(method,value)
    }.getOrNull()
    private val box=optional("android.gui.BoxShadowSettings","setBoxShadowSettings","boxShadows")
    private val border=optional("android.gui.BorderSettings","setBorderSettings")
    @JvmStatic fun describe()="box=${box!=null} border=${border!=null} legacyShadow=true"
    @JvmStatic fun apply(tx:Any,layer:Any){
        color.invoke(tx,layer)
        shadow.invoke(tx,layer,0f)
        box?.let{it.method.invoke(tx,layer,it.value)}
        border?.let{it.method.invoke(tx,layer,it.value)}
    }
}
