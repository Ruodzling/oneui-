package io.github.s24u.popupscale.gestures

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers as X

class HangTargetOverlay(private val context:Context,live:Boolean) {
    private val wm=context.getSystemService(WindowManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private var view:HangTargetView?=null
    private var closing=false
    init {
        val m=GestureDisplay.metrics(context)
        val target=checkNotNull(LivePlacement.guideBounds(m.widthPixels,m.heightPixels,m.density,live))
        val v=HangTargetView(context)
        val p=WindowManager.LayoutParams(target.width,target.height,2024,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT)
        p.gravity=Gravity.TOP or Gravity.LEFT;p.x=target.x;p.y=target.y;p.title="PopupScaleHangHint"
        X.callMethod(p,"setTrustedOverlay")
        v.alpha=0f;v.scaleX=.94f;v.scaleY=.94f;v.pivotX=target.width.toFloat();v.pivotY=0f
        wm.addView(v,p);view=v
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        // Bound an orphan if the ROM abandons this input consumer without CANCEL/UP.
        main.postDelayed({dismiss(true)},30000)
    }
    fun update(state:HangTargetPolicy.State){if(!closing)view?.update(state)}
    fun dismiss(immediate:Boolean=false) {
        if(immediate){remove();return}
        if(closing)return
        closing=true
        view?.animate()?.cancel()
        view?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction{remove()}?.start()
        main.postDelayed({remove()},250)
    }
    private fun remove(){
        closing=true
        val v=view?:return;view=null
        v.animate().cancel()
        Guard.call("hint-release"){wm.removeViewImmediate(v)}
    }
}
