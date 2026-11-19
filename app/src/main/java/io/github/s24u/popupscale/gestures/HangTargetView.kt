package io.github.s24u.popupscale.gestures

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Large approach guide anchored to the mini's top/right corner; never consumes a touch. */
class HangTargetView(context:Context):View(context) {
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress=0f
    private var desired=0f
    private var armed=false
    private var animator:ValueAnimator?=null
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false }
    fun update(state:HangTargetPolicy.State) {
        val changed=armed!=state.armed
        armed=state.armed
        contentDescription=if(armed)"松手挂起浮窗" else "向右上方滑动，挂起浮窗"
        if(abs(desired-state.progress)<.025f&&!changed)return
        desired=state.progress
        animator?.cancel()
        animator=ValueAnimator.ofFloat(progress,desired).apply {
            duration=if(armed)90 else 120;interpolator=DecelerateInterpolator()
            addUpdateListener{progress=it.animatedValue as Float;invalidate()};start()
        }
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val d=density;val inset=1.5f*d
        val bottom=(96*d+(height-96*d)*progress).coerceAtMost(height.toFloat())
        val box=RectF(inset,inset,width-inset,bottom-inset)
        paint.style=Paint.Style.FILL
        paint.color=Color.argb((100+progress*85).toInt(),245,248,252)
        canvas.drawRoundRect(box,12*d,12*d,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=(1+progress*.7f)*d
        paint.color=Color.argb((65+progress*135).toInt(),255,255,255)
        canvas.drawRoundRect(box,12*d,12*d,paint)
        val cx=width/2f;val cy=30*d
        paint.strokeWidth=2f*d;paint.color=Color.rgb(65,73,85)
        canvas.drawRoundRect(RectF(cx-10*d,cy-8*d,cx+5*d,cy+8*d),2*d,2*d,paint)
        canvas.drawRoundRect(RectF(cx-2*d,cy-13*d,cx+11*d,cy+2*d),2*d,2*d,paint)
        paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER
        paint.textSize=minOf(18*resources.displayMetrics.scaledDensity,width/8f)
        paint.isFakeBoldText=armed
        canvas.drawText(if(armed)"松手挂起" else "滑向此处挂起",cx,67*d,paint)
        paint.isFakeBoldText=false
        if(progress>.15f){
            // Upward chevrons guide the card into the reserved landing rectangle.
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f*d
            paint.strokeCap=Paint.Cap.ROUND
            paint.color=Color.argb((progress*100).toInt(),65,73,85)
            val y=maxOf(86*d,bottom-26*d)
            if(y+6*d<bottom)for(i in 0..1){
                val yy=y-i*7*d
                canvas.drawLine(cx-5*d,yy+4*d,cx,yy,paint)
                canvas.drawLine(cx,yy,cx+5*d,yy+4*d,paint)
            }
        }
    }
    override fun onDetachedFromWindow(){animator?.cancel();animator=null;super.onDetachedFromWindow()}
}
