package io.github.s24u.popupscale.gestures

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Optional

/** Bounded reads of known Shell ownership fields, never a general object/view-tree walk. */
object DecorationDiscovery {
    private val modelFields=listOf("mWindowDecorationViewModel","windowDecorationViewModel",
        "mWindowDecorViewModel","windowDecorViewModel","mCaptionWindowDecorViewModel","mDesktopModeWindowDecorViewModel")
    private val decorFields=listOf("mWindowDecorByTaskId","windowDecorByTaskId","mWindowDecorations","windowDecorations")
    data class Snapshot(val models:List<Any>,val decorations:List<Any>)
    fun collect(listeners:List<Any>,read:(Any,String)->Any?,values:(Any?)->List<Any>,trusted:(Any)->Boolean):Snapshot {
        val visited=Collections.newSetFromMap(IdentityHashMap<Any,Boolean>())
        val seenDecor=Collections.newSetFromMap(IdentityHashMap<Any,Boolean>())
        val models=mutableListOf<Any>();val decorations=mutableListOf<Any>()
        fun unwrap(value:Any?):Any?=if(value is Optional<*>)value.orElse(null) else value
        fun visit(owner:Any,depth:Int){
            if(depth>2||visited.size>=32||!trusted(owner)||!visited.add(owner))return
            var model=false
            for(name in decorFields){
                val entries=read(owner,name) ?: continue
                model=true
                for(decor in values(entries).take(64)){
                    if(decorations.size>=64)break
                    if(trusted(decor)&&seenDecor.add(decor))decorations.add(decor)
                }
            }
            if(model)models.add(owner)
            for(name in modelFields)unwrap(read(owner,name))?.let{visit(it,depth+1)}
        }
        listeners.take(64).forEach{visit(it,0)}
        return Snapshot(models,decorations)
    }
}
