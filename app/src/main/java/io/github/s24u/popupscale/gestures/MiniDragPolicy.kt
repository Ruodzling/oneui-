package io.github.s24u.popupscale.gestures

import kotlin.math.roundToInt

/** Pixel geometry shared by live drag handling and host-independent boundary tests. */
object MiniDragPolicy {
    data class Box(val x:Int,val y:Int,val width:Int,val height:Int) {
        val right get()=x+width
        val bottom get()=y+height
    }
    enum class Action { FLOAT, DOCK_LEFT, DOCK_RIGHT, CLOSE_TOP, CLOSE_BOTTOM }
    data class Motion(val action:Action,val destination:Box,val durationMs:Long,
                      val impact:Box?=null)
    fun motion(box:Box,screenWidth:Int,screenHeight:Int,density:Float,vx:Float,vy:Float,
               dragX:Float=0f,dragY:Float=0f):Motion {
        val direct=release(box,screenWidth,screenHeight,density)
        if(direct==Action.CLOSE_TOP||direct==Action.CLOSE_BOTTOM)return Motion(direct,box,240)
        val xSpeed=if(vx.isFinite())vx.coerceIn(-6000*density,6000*density) else 0f
        val ySpeed=if(vy.isFinite())vy.coerceIn(-6000*density,6000*density) else 0f
        val horizontal=kotlin.math.abs(xSpeed)>=700*density&&kotlin.math.abs(xSpeed)>kotlin.math.abs(ySpeed)*2f
        // Dock only after a deliberate, slow sideways drag physically reaches the edge.
        // Neither a projected fling endpoint nor an up/down swipe can request docking.
        val manual=kotlin.math.abs(xSpeed)<700*density&&kotlin.math.abs(ySpeed)<700*density&&
            dragX.isFinite()&&dragY.isFinite()&&kotlin.math.abs(dragX)>12*density&&
            kotlin.math.abs(dragX)>kotlin.math.abs(dragY)*1.1f
        val action=when {
            manual&&dragX<0&&box.x<=6*density&&xSpeed<=180*density->Action.DOCK_LEFT
            manual&&dragX>0&&box.right>=screenWidth-6*density&&xSpeed>=-180*density->Action.DOCK_RIGHT
            else->Action.FLOAT
        }
        if(action!=Action.FLOAT)return Motion(action,dock(action==Action.DOCK_LEFT,box.y+box.height/2,
            box.width,box.height,screenWidth,screenHeight,density),260)
        // Inertia can move a window vertically, but it must never synthesize a close gesture.
        var coastX=(xSpeed*.065f).coerceIn(-80*density,80*density)
        var coastY=(ySpeed*.04f).coerceIn(-112*density,112*density)
        // Diagonal throws lose energy on both axes; keep near-horizontal cross-screen throws.
        if(!horizontal&&kotlin.math.abs(xSpeed)>180*density&&kotlin.math.abs(ySpeed)>180*density){
            val length=kotlin.math.hypot(coastX,coastY)
            val limit=minOf(96*density,screenWidth*.28f,screenHeight*.15f)
            if(length>limit){coastX*=limit/length;coastY*=limit/length}
        }
        val projectedX=if(horizontal) {if(xSpeed<0)0 else screenWidth-box.width}
            else (box.x+coastX).roundToInt()
        val projectedY=(box.y+coastY).roundToInt()
        val destination=constrain(box.copy(x=projectedX,y=projectedY),screenWidth,screenHeight,density)
        val hitX=when {
            projectedX<=0&&xSpeed< -180*density->-1
            projectedX+box.width>=screenWidth&&xSpeed>180*density->1
            else->0
        }
        val hitY=when {
            projectedY<=0&&ySpeed< -180*density->-1
            projectedY+box.height>=screenHeight&&ySpeed>180*density->1
            else->0
        }
        val impact=if(hitX!=0||hitY!=0)destination.copy(
            x=if(hitX<0)0 else if(hitX>0)screenWidth-box.width else destination.x,
            y=if(hitY<0)0 else if(hitY>0)screenHeight-box.height else destination.y) else null
        val distance=kotlin.math.hypot((destination.x-box.x).toFloat(),(destination.y-box.y).toFloat())
        val speed=kotlin.math.hypot(xSpeed,ySpeed).coerceAtLeast(400*density)
        val duration=if(impact!=null)(2000*distance/speed+180).toLong().coerceIn(360,560) else 260L
        return Motion(Action.FLOAT,destination,duration,impact)
    }
    /** Nonlinear flight followed by a single small inward bounce, keeping the mini's size. */
    fun sample(from:Box,motion:Motion,fraction:Float):Box {
        val t=if(fraction.isFinite())fraction.coerceIn(0f,1f) else 0f
        if(t==0f)return from
        if(t==1f)return motion.destination
        fun ease(v:Float):Float {val u=1-v;return 1-u*u*u}
        fun between(a:Box,b:Box,p:Float)=Box((a.x+(b.x-a.x)*p).roundToInt(),
            (a.y+(b.y-a.y)*p).roundToInt(),(a.width+(b.width-a.width)*p).roundToInt(),
            (a.height+(b.height-a.height)*p).roundToInt())
        val impact=motion.impact?:return between(from,motion.destination,ease(t))
        return when {
            t<.72f->{val u=t/.72f;between(from,impact,.9f*ease(u)+.1f*u)}
            // Rebound directly to rest: no overshoot point and no second pull-back motion.
            else->between(impact,motion.destination,ease((t-.72f)/.28f))
        }
    }
    fun release(box:Box,screenWidth:Int,screenHeight:Int,density:Float):Action {
        require(screenWidth>0&&screenHeight>0&&box.width>0&&box.height>0&&density>0&&density.isFinite())
        // Vertical exit takes precedence at corners. Only evaluate for an actual completed drag.
        if(box.y+box.height/2f<=0)return Action.CLOSE_TOP
        if(box.y+box.height/2f>=screenHeight)return Action.CLOSE_BOTTOM
        if(box.x<=6*density)return Action.DOCK_LEFT
        if(box.right>=screenWidth-6*density)return Action.DOCK_RIGHT
        return Action.FLOAT
    }
    fun constrain(box:Box,screenWidth:Int,screenHeight:Int,density:Float):Box {
        val pad=(12*density).roundToInt()
        val top=(24*density).roundToInt()
        fun fit(value:Int,size:Int,screen:Int,margin:Int):Int {
            val room=(screen-size).coerceAtLeast(0)
            val inset=minOf(margin,room/2)
            return value.coerceIn(inset,room-inset)
        }
        return box.copy(x=fit(box.x,box.width,screenWidth,pad),y=fit(box.y,box.height,screenHeight,top))
    }
    fun reveal(left:Boolean,centerY:Int,width:Int,height:Int,screenWidth:Int,screenHeight:Int,density:Float):Box =
        constrain(Box(if(left)0 else screenWidth-width,centerY-height/2,width,height),screenWidth,screenHeight,density)
    fun dock(left:Boolean,centerY:Int,width:Int,height:Int,screenWidth:Int,screenHeight:Int,density:Float):Box {
        val w=minOf(width,(44*density).roundToInt()).coerceAtLeast(1)
        val h=(height.toFloat()*w/width).roundToInt().coerceAtLeast(1)
        val y=(centerY-h/2).coerceIn(0,(screenHeight-h).coerceAtLeast(0))
        return Box(if(left)-w else screenWidth,y,w,h)
    }
    fun tab(left:Boolean,centerY:Int,screenWidth:Int,screenHeight:Int,density:Float):Box {
        // Enlarge only the invisible in-screen grab area; leave the visible tab at28dp.
        val w=(44*density).roundToInt().coerceAtMost(screenWidth)
        val h=(56*density).roundToInt().coerceAtMost(screenHeight)
        val pad=(24*density).roundToInt().coerceAtMost((screenHeight-h)/2)
        return Box(if(left)0 else screenWidth-w,(centerY-h/2).coerceIn(pad,screenHeight-h-pad),w,h)
    }
    fun tabBadgeMargin(left:Boolean,tabWidth:Int,density:Float):Int {
        val visible=minOf(tabWidth,(28*density).roundToInt())
        return if(left)visible-(48*density).toInt() else tabWidth-visible
    }
    enum class DockRelease { MOTION, RETURN_TO_EDGE, RESTORE_STANDARD }
    const val DOCK_RESTORE_DISTANCE_DP=32f
    /** Only standard-window docks expand on a deliberate inward drag; native-mini docks stay mini. */
    fun dockRelease(fromDock:Boolean,fromStandard:Boolean,left:Boolean,dx:Float,dy:Float,
                    density:Float,motion:Action):DockRelease {
        if(!fromDock||!fromStandard||density<=0f||!density.isFinite()||!dx.isFinite()||!dy.isFinite()||
            motion==Action.CLOSE_TOP||motion==Action.CLOSE_BOTTOM)return DockRelease.MOTION
        val inward=if(left)dx else -dx
        if(inward<=0f||inward<kotlin.math.abs(dy)*1.1f)return DockRelease.MOTION
        return if(inward>=DOCK_RESTORE_DISTANCE_DP*density)DockRelease.RESTORE_STANDARD else DockRelease.RETURN_TO_EDGE
    }
    /** Resolves which edge owns a top-handle freeform stash from release overhang or ±12000px/s fling. */
    fun stashLeft(left:Int,right:Int,screenWidth:Int,vx:Float=0f):Boolean {
        require(screenWidth>0&&right>left)
        if(vx.isFinite()&&kotlin.math.abs(vx)>=12000f)return vx<0f
        val overLeft=(-left).coerceAtLeast(0)
        val overRight=(right-screenWidth).coerceAtLeast(0)
        if(overLeft!=overRight)return overLeft>overRight
        return left+right<=screenWidth
    }
    /** Standard freeform windows docked via top-bar stash restore directly on icon tap. */
    fun dockTapRestoresStandard(dockedFromStandard:Boolean):Boolean = dockedFromStandard
    fun keepStandardDockOrigin(dockedFromStandard:Boolean,settledDocked:Boolean):Boolean =
        dockedFromStandard&&settledDocked
    const val DOCK_IDLE_DELAY_MS=2000L
    const val DOCK_ACTIVE_ALPHA=1f
    const val DOCK_IDLE_ALPHA=0.5f
    fun dockBadgeAlpha(idleMs:Long):Float =
        if(idleMs>=DOCK_IDLE_DELAY_MS)DOCK_IDLE_ALPHA else DOCK_ACTIVE_ALPHA
    fun dockedSurfaceAlpha(docked:Boolean):Float = if(docked)0f else 1f
    const val CTRL_TYPE_LEFT=1
    const val CTRL_TYPE_RIGHT=2
    fun resizeHangLeft(ctrlType:Int,gestureBox:Box,screenWidth:Int):Boolean {
        require(screenWidth>0&&gestureBox.width>0)
        val left=(ctrlType and CTRL_TYPE_LEFT)!=0
        val right=(ctrlType and CTRL_TYPE_RIGHT)!=0
        if(left!=right)return left
        return gestureBox.x+gestureBox.right<=screenWidth
    }
    fun resizeMiniTarget(left:Boolean,sourceWidth:Int,sourceHeight:Int,screenWidth:Int,screenHeight:Int,density:Float):Box? {
        val p=LivePlacement.place(screenWidth,screenHeight,sourceWidth,sourceHeight,density,left)?:return null
        return Box(p.x,p.y,p.width,p.height)
    }
}
