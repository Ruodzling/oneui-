package io.github.s24u.popupscale.gestures

import android.graphics.Rect
import android.graphics.Region
import android.util.Size

/** Changes both native hit testing and the region used to register the input channel.
 * Never offsets task bounds or pointer coordinates. Native drag deltas remain unchanged.
 */
object FramelessResizeGeometry {
    private enum class Role { CORNER, TOP_EDGE, SIDE_EDGE }
    private data class Shape(val rect:Rect,val native:Rect,val role:Role,
                             val fine:Boolean=false,val left:Boolean=false)
    private data class Edges(val owner:Any,val region:Region,val native:Region)
    private data class Saved(val shapes:List<Shape>,val edges:List<Edges>,val handle:Rect?,
                             var caption:Int=0,var density:Float=0f)
    // Native geometry.hashCode includes the Rects we adjust. An ordinary WeakHashMap
    // would lose its own entry after the first shift and accumulate offsets on reuse.
    private class Key(value:Any,queue:java.lang.ref.ReferenceQueue<Any>?=null):java.lang.ref.WeakReference<Any>(value,queue){
        private val hash=System.identityHashCode(value)
        override fun hashCode()=hash
        override fun equals(other:Any?)=this===other||(other is Key&&get()!=null&&get()===other.get())
    }
    private val queue=java.lang.ref.ReferenceQueue<Any>()
    private val saved=mutableMapOf<Key,Saved>() // Shell decoration thread only.
    private fun field(owner:Any,name:String):Any=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)!!
    @JvmStatic @JvmOverloads fun apply(geometry:Any,caption:Int,density:Float=1f):Boolean {
        val size=field(geometry,"mTaskSize") as Size
        val top=caption.takeIf{it>0&&it*3<size.height&&density.isFinite()&&density in .125f..8f}?:0
        val scale=if(top>0)density else 0f
        while(true){val dead=queue.poll()?:break;saved.remove(dead)}
        val key=Key(geometry)
        var entry=saved[key]
        if(entry==null){
            if(top==0)return false
            val shapes=mutableListOf<Shape>();val edges=mutableListOf<Edges>()
            for(name in listOf("mFineTaskCorners","mLargeTaskCorners")){
                val corners=field(geometry,name)
                for(corner in listOf("mLeftTopCornerBounds","mRightTopCornerBounds")){
                    val rect=field(corners,corner) as Rect
                    shapes+=Shape(rect,Rect(rect),Role.CORNER,name=="mFineTaskCorners",corner=="mLeftTopCornerBounds")
                }
            }
            for(name in listOf("mTaskEdges","mPointerTaskEdges")){
                val owner=field(geometry,name)
                for(edge in listOf("mTopEdgeBounds","mLeftEdgeBounds","mRightEdgeBounds")){
                    val rect=field(owner,edge) as Rect
                    shapes+=Shape(rect,Rect(rect),if(edge=="mTopEdgeBounds")Role.TOP_EDGE else Role.SIDE_EDGE)
                }
                val region=field(owner,"mRegion") as Region
                edges+=Edges(owner,region,Region(region))
            }
            // Resolve every field before changing any native object.
            val handle=runCatching{field(edges.first().owner,"mHandleRect") as Rect}.getOrNull()
                ?.takeUnless{it.isEmpty}?.let{Rect(it)}
            entry=Saved(shapes,edges,handle);saved[Key(geometry,queue)]=entry
        }
        if(entry.caption==top&&entry.density==scale)return false
        try{
            for(shape in entry.shapes){
                shape.rect.set(shape.native)
                if(top==0)continue // Explicitly restore all native rectangles when disabled.
                when(shape.role){
                    Role.TOP_EDGE->shape.rect.setEmpty() // Middle top edge must not steal bluebar touches.
                    Role.SIDE_EDGE->shape.rect.top=(shape.native.top+top).coerceAtMost(shape.native.bottom)
                    Role.CORNER->{
                        shape.rect.offset(0,top)
                        // More forgiving corner acquisition; native drag slop/deltas stay intact.
                        val extra=kotlin.math.ceil((if(shape.fine)4f else 8f)*scale).toInt()
                        shape.rect.inset(-extra,-extra)
                        // Keep a central caption lane even in narrow windows. Native handle
                        // exclusion in TaskEdges alone does not exclude the separate corners.
                        if(shape.left)shape.rect.right=minOf(shape.rect.right,size.width/3,
                            entry.handle?.left?:size.width/3).coerceAtLeast(1)
                        else shape.rect.left=maxOf(shape.rect.left,size.width*2/3,
                            entry.handle?.right?:size.width*2/3).coerceAtMost(size.width-1)
                    }
                }
            }
            for(edge in entry.edges){
                edge.region.setEmpty()
                // Native union retains disabled-edge, navigation-gesture and bluebar exclusions.
                edge.owner.javaClass.getDeclaredMethod("union",Region::class.java).invoke(edge.owner,edge.region)
            }
            entry.caption=top
            entry.density=scale
        }catch(error:Throwable){
            for(shape in entry.shapes)shape.rect.set(shape.native)
            for(edge in entry.edges)edge.region.set(edge.native)
            saved.remove(key);throw error
        }
        return true
    }
}
