package io.github.s24u.popupscale.gestures

import kotlin.math.abs
import kotlin.math.roundToInt

/** Task identities and geometry only; a stack never owns another task's recovery data. */
object MiniStackPolicy {
    fun participates(task:Int,docked:Boolean,leaving:Boolean)=task>=0&&!docked&&!leaving
    class Order {
        private val tasks=mutableListOf<Int>()
        fun ids()=tasks.toList()
        fun add(ids:List<Int>){
            val incoming=ids.filter{it>=0}.distinct()
            val next=incoming+tasks.filter{it !in incoming}
            tasks.clear();tasks.addAll(next)
        }
        fun remove(id:Int){tasks.remove(id)}
        fun retain(ids:Set<Int>){tasks.retainAll(ids)}
        fun reconcile(eligible:List<Int>):Boolean {
            val allowed=eligible.filter{it>=0}.distinct()
            val next=allowed.filter{it !in tasks}+tasks.filter{it in allowed}
            if(next==tasks)return false
            tasks.clear();tasks.addAll(next);return true
        }
        fun switch(direction:Int){
            if(tasks.size<2||direction==0)return
            if(direction>0)tasks.add(tasks.removeAt(0)) else tasks.add(0,tasks.removeAt(tasks.lastIndex))
        }
    }
    data class Size(val width:Int,val height:Int)
    data class Frame(val task:Int,val bounds:LivePlacement.Bounds,val alpha:Float,val front:Boolean,val layer:Int?=null)
    class FrameCache {
        private val applied=mutableMapOf<Int,Frame>()
        fun changes(frames:List<Frame>)=frames.filter{applied[it.task]!=it}
        fun committed(frames:List<Frame>){frames.forEach{applied[it.task]=it}}
        fun invalidate(id:Int){applied.remove(id)}
        fun clear(){applied.clear()}
    }
    fun entryTarget(default:LivePlacement.Bounds,anchor:LivePlacement.Bounds?,sw:Int,sh:Int):LivePlacement.Bounds {
        if(anchor==null||sw<=0||sh<=0)return default
        return default.copy(x=anchor.x.coerceIn(0,(sw-default.width).coerceAtLeast(0)),
            y=anchor.y.coerceIn(0,(sh-default.height).coerceAtLeast(0)))
    }
    fun layers(ids:List<Int>,native:Map<Int,Int?>):Map<Int,Int>{
        if(ids.isEmpty()||ids.any{native[it]==null||native.getValue(it)!! !in 0..1000000})return emptyMap()
        val base=ids.maxOf{native.getValue(it)!!}
        return ids.mapIndexed{depth,id->id to base+ids.size-depth}.toMap()
    }
    enum class Motion { TAP, SWIPE, DRAG }
    class Gesture(val x:Float,val y:Float,private val started:Long,private val slop:Int,private val holdMs:Int){
        var motion=Motion.TAP;private set
        fun move(rawX:Float,rawY:Float,whenMs:Long):Motion {
            if(motion!=Motion.TAP||!rawX.isFinite()||!rawY.isFinite())return motion
            val dx=abs(rawX-x);val dy=abs(rawY-y)
            if(dx<=slop&&dy<=slop)return motion
            motion=if(whenMs-started<holdMs&&dx>dy*1.2f)Motion.SWIPE else Motion.DRAG
            return motion
        }
    }
    fun layout(ids:List<Int>,sizes:Map<Int,Size>,x:Int,y:Int,sw:Int,sh:Int,density:Float):List<Frame>{
        if(sw<=0||sh<=0||!density.isFinite()||density<=0)return emptyList()
        return ids.mapIndexedNotNull{depth,id->
            val size=sizes[id]?.takeIf{it.width>0&&it.height>0} ?: return@mapIndexedNotNull null
            val scale=1f-minOf(depth,2)*.055f
            val fit=minOf(scale,sw.toFloat()/size.width,sh.toFloat()/size.height)
            val w=(size.width*fit).roundToInt().coerceIn(1,sw)
            val h=(size.height*fit).roundToInt().coerceIn(1,sh)
            val dx=(minOf(depth,2)*10*density).roundToInt()
            val dy=(minOf(depth,2)*9*density).roundToInt()
            Frame(id,LivePlacement.Bounds((x-dx).coerceIn(0,sw-w),(y+dy).coerceIn(0,sh-h),w,h),
                if(depth<3)1f else 0f,depth==0)
        }
    }
    /** Keep every stacked card's interceptor overlay touchable so exposed back-card pixels never
     * leak input into the underlying Task surface. */
    fun overlayTouchable(front:Boolean,stackControlled:Boolean)=front||stackControlled
    fun hitFront(rawX:Float,rawY:Float,front:LivePlacement.Bounds?,pad:Int=0):Boolean {
        if(front==null||!rawX.isFinite()||!rawY.isFinite())return false
        val p=pad.coerceAtLeast(0)
        return rawX>=front.x-p&&rawX<front.x+front.width+p&&rawY>=front.y-p&&rawY<front.y+front.height+p
    }
    /** Tapping an exposed back card only blocks pass-through to the app without expanding. */
    fun expandOnTap(startedOnFront:Boolean)=startedOnFront
    /** When one mini expands into a standard popup, home remaining floating minis to top-right. */
    fun homeAnchor(defaultBounds:LivePlacement.Bounds?,remainingCount:Int):LivePlacement.Bounds? =
        if(remainingCount>0)defaultBounds else null
    /** Keep the expanding card above other popups during its animated restore; only reset to the
     * stale pre-stack native layer for immediate non-animated recovery/teardown. */
    fun restoreLayerBeforeExpand(animated:Boolean)=!animated
    /** Resync SurfaceFlinger Z-order from WindowManager's true hierarchy for every visible freeform
     * task not pinned inside an active multi-card mini stack. */
    fun resyncTaskLayer(windowingMode:Int,activeMultiStack:Boolean,leased:Boolean)=
        windowingMode==5&&!(activeMultiStack&&leased)
    /** Temporary SurfaceFlinger layer above existing popups and any remaining mini stack while
     * a mini animates into a standard popup; cleared by resyncFreeformLayers once focused. */
    fun expandingLayer(maxFreeformLayer:Int,remainingLeases:Int):Int? =
        if(maxFreeformLayer in 0..1000000)maxFreeformLayer+remainingLeases.coerceAtLeast(0)+1 else null
    /** Horizontal swipe changes cards. A hold or vertical movement drags the whole stack. */
    fun swipe(dx:Float,dy:Float,velocity:Float,density:Float,windowWidth:Int=0):Int {
        if(!dx.isFinite()||!dy.isFinite()||!velocity.isFinite()||!density.isFinite()||density<=0)return 0
        if(abs(dx)<abs(dy)*1.2f)return 0
        val distance=if(windowWidth>0)(windowWidth*.18f).coerceIn(14*density,24*density) else 18*density
        if(abs(dx)<distance&&!(abs(dx)>10*density&&abs(velocity)>450*density))return 0
        return if(dx<0)1 else -1
    }
}
