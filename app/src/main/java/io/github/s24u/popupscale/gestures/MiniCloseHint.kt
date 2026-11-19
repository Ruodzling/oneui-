package io.github.s24u.popupscale.gestures

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Non-touchable cue appears only when a completed drag would close the task. */
class MiniCloseHint(context:Context) {
    private val wm=context.getSystemService(WindowManager::class.java)
    private val d=context.resources.displayMetrics.density
    private val view=TextView(context).apply{
        text="松手关闭";textSize=16f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)
        background=GradientDrawable().apply{setColor(0xddb02a30.toInt());cornerRadius=20*d}
    }
    private var edge:MiniDragPolicy.Action?=null
    fun show(action:MiniDragPolicy.Action) {
        if(action!=MiniDragPolicy.Action.CLOSE_TOP&&action!=MiniDragPolicy.Action.CLOSE_BOTTOM){hide();return}
        if(edge==action)return
        hide()
        val p=WindowManager.LayoutParams((110*d).toInt(),(42*d).toInt(),2024,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT)
        p.gravity=Gravity.CENTER_HORIZONTAL or if(action==MiniDragPolicy.Action.CLOSE_TOP)Gravity.TOP else Gravity.BOTTOM
        p.y=(32*d).toInt();p.title="PopupScaleMiniCloseHint"
        wm.addView(view,p);edge=action
    }
    fun hide(){if(edge!=null){edge=null;Guard.call("mini-close-hint"){wm.removeViewImmediate(view)}}}
}
