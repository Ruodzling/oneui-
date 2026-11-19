package io.github.s24u.popupscale.gestures

import kotlin.math.roundToInt

object DockInteractionChecks {
    fun run(){
        val floating=MiniDragPolicy.Action.FLOAT
        for(density in listOf(1f,2.625f,3.75f))for(left in listOf(true,false)){
            val sign=if(left)1f else -1f
            fun release(distance:Float,vertical:Float=0f,standard:Boolean=true,fromDock:Boolean=true,
                        action:MiniDragPolicy.Action=floating)=MiniDragPolicy.dockRelease(fromDock,standard,left,
                sign*distance*density,vertical*density,density,action)
            check(release(32f)==MiniDragPolicy.DockRelease.RESTORE_STANDARD)
            check(release(64f,12f)==MiniDragPolicy.DockRelease.RESTORE_STANDARD)
            check(release(31.5f)==MiniDragPolicy.DockRelease.RETURN_TO_EDGE)
            check(release(12f)==MiniDragPolicy.DockRelease.RETURN_TO_EDGE)
            check(release(40f,standard=false)==MiniDragPolicy.DockRelease.MOTION)
            check(release(40f,fromDock=false)==MiniDragPolicy.DockRelease.MOTION)
            check(release(-40f)==MiniDragPolicy.DockRelease.MOTION)
            check(release(0f,100f)==MiniDragPolicy.DockRelease.MOTION)
            check(release(40f,100f)==MiniDragPolicy.DockRelease.MOTION)
            // Vertical half-out close wins even on a diagonally inward drag.
            check(release(80f,-60f,action=MiniDragPolicy.Action.CLOSE_TOP)==MiniDragPolicy.DockRelease.MOTION)
            check(release(80f,60f,action=MiniDragPolicy.Action.CLOSE_BOTTOM)==MiniDragPolicy.DockRelease.MOTION)
            check(release(300f,action=if(left)MiniDragPolicy.Action.DOCK_RIGHT else MiniDragPolicy.Action.DOCK_LEFT)==MiniDragPolicy.DockRelease.RESTORE_STANDARD)
            check(release(Float.NaN)==MiniDragPolicy.DockRelease.MOTION)
        }
        check(MiniDragPolicy.dockRelease(true,true,true,100f,0f,0f,floating)==MiniDragPolicy.DockRelease.MOTION)
        for((width,height) in listOf(1080 to 2340,2340 to 1080,1440 to 3120,3120 to 1440))
            for(density in listOf(2.625f,3.75f))for(left in listOf(true,false))for(center in listOf(-100,height/2,height+100)){
                val tab=MiniDragPolicy.tab(left,center,width,height,density)
                check(tab.width==(44*density).roundToInt())
                check(tab.x>=0&&tab.right<=width&&tab.y>=0&&tab.bottom<=height)
                check(if(left)tab.x==0 else tab.right==width)
                val iconMargin=MiniDragPolicy.tabBadgeMargin(left,tab.width,density)
                val imageWidth=(48*density).toInt()
                val iconLeft=tab.x+maxOf(0,iconMargin)
                val iconRight=tab.x+minOf(tab.width,iconMargin+imageWidth)
                val visible=(28*density).roundToInt()
                // Wider input area must not move the visible badge away from either edge.
                check(iconRight-iconLeft==visible)
                check(if(left)iconLeft==0 else iconRight==width)
                check(if(left)iconRight==visible else iconLeft==width-visible)
                check(if(left)tab.right>iconRight else tab.x<iconLeft)
            }
        println("PASS: mirrored32dp standard-dock inward expansion, subthreshold return, mini/tap/outward/vertical close isolation;44dp screen-contained hit area preserves28dp visible icon on portrait/landscape edges.")
    }
}
