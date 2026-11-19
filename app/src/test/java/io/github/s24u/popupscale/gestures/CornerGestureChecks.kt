package io.github.s24u.popupscale.gestures

object CornerGestureChecks {
    fun run(){
        fun shape(edge:Int,x:Int,y:Int)=checkNotNull(CornerGesturePolicy.centered(1440,3120,edge,
            if(edge==5)x else 55,y,if(edge==9)x else 1385,3065,825,825))
        val left=shape(5,200,700);val right=shape(9,1240,700)
        check(left==right&&left==LivePlacement.Bounds(200,700,1040,1720))
        check(left.x*2+left.width==1440&&left.y*2+left.height==3120)
        // Native rewrites just the moving sides. Two consecutive samples must not
        // accumulate the mirrored opposite corner from the preceding preview.
        val a=shape(5,220,710);val b=shape(5,240,720)
        check(a.width-b.width==40&&a.height-b.height==20)
        val min=shape(5,900,2000)
        check(min.width>=825&&min.height>=825&&min.x*2+min.width==1440&&min.y*2+min.height==3120)
        for(w in listOf(1080,1440,3120))for(h in listOf(1080,2400,3120))for(edge in listOf(5,9)){
            val fit=checkNotNull(CornerGesturePolicy.centered(w,h,edge,80,100,w-80,h-40,300,300))
            check(fit.x*2+fit.width==w&&fit.y*2+fit.height==h)
            check(fit.width in 300..w&&fit.height in 300..h)
        }
        check(CornerGesturePolicy.centered(1440,3120,1,0,100,1300,3000,825,825)==null)
        check(CornerGesturePolicy.centered(1440,3120,5,100,100,1300,3000,1500,825)==null)
        check(CornerGesturePolicy.centered(1440,3120,5,-1,100,1300,3000,825,825)==null)
        check(CornerGesturePolicy.adopts(true,5,1440,3120,1440,3120))
        check(!CornerGesturePolicy.adopts(false,5,1440,3120,1440,3120))
        check(!CornerGesturePolicy.adopts(true,1,1440,3120,1440,3120))
        check(!CornerGesturePolicy.adopts(true,5,1440,3120,3120,1440))
        val entries=LivePlacement.EntryLayoutState()
        check(entries.observe(7,5,true,1440,3120))
        entries.cancel(7);check(!entries.observe(7,5,true,1440,3120))
        check(!entries.awaiting(7))
        check(!entries.observe(7,1,true,1440,3120))
        check(entries.observe(7,5,true,1440,3120)&&entries.awaiting(7))
        println("PASS: mirrored upper corners keep display centre, continuous opposite edges, valid native minima and per-entry adoption; ordinary reentry and rotation retain their original layout rules.")
    }
}
