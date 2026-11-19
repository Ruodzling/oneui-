package io.github.s24u.popupscale.gestures

import de.robv.android.xposed.XposedHelpers as X

/** Use the framework's trusted lookup for boot hidden APIs; normal reflection may filter them. */
object RuntimeCompatibilityProbe {
    fun create(loader:ClassLoader):CompatibilityProbe {
        fun type(name:String):Class<*> = when(name){
            "int"->Integer.TYPE
            "boolean"->java.lang.Boolean.TYPE
            "float"->java.lang.Float.TYPE
            "long"->java.lang.Long.TYPE
            else->Class.forName(name,false,loader)
        }
        val framework=runCatching{Class.forName("de.robv.android.xposed.XposedHelpers",false,
            RuntimeCompatibilityProbe::class.java.classLoader)}.isSuccess
        if(!framework)return CompatibilityProbe({name->type(name)}) // metadata-only instrumentation
        return CompatibilityProbe({name->type(name)},
            {owner,name,params->X.findMethodExactIfExists(owner,name,*params.map{type(it)}.toTypedArray())},
            {owner,name->X.findFieldIfExists(owner,name)!=null})
    }
}
