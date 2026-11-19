package io.github.s24u.popupscale.gestures

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Explicitly simulated reference interactions; no task, launcher, snapshot or privileged APIs. */
class GesturePreviewActivity:Activity(){
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val density=resources.displayMetrics.density
        val width=resources.displayMetrics.widthPixels.toFloat()
        val height=resources.displayMetrics.heightPixels.toFloat()
        val root=FrameLayout(this).apply{setBackgroundColor(Color.rgb(170,180,190))}
        setContentView(root)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,(48*density).toInt(),0,0)}
        root.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        controls.addView(TextView(this).apply{text="交互演示：点按钮挂起，点迷你窗展开；拖底条上下滑。只操作模拟窗口。";textSize=14f})
        val card=FrameLayout(this).apply{
            background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=24*density}
            clipToOutline=true;pivotX=0f;pivotY=0f
        }
        val cw=(width*.76f).toInt();val ch=(height*.65f).toInt()
        val baseX=(width-cw)/2;val baseY=(height-ch)/2
        root.addView(card,FrameLayout.LayoutParams(cw,ch))
        card.x=baseX;card.y=baseY
        card.addView(TextView(this).apply{
            text="模拟应用\n\n向下拖动底条 → 全屏\n向上拖动底条 → 关闭\n短滑或取消 → 回弹";textSize=20f
            setPadding((20*density).toInt(),(24*density).toInt(),0,0)
        })
        val touch=FrameLayout(this)
        card.addView(touch,FrameLayout.LayoutParams((96*density).toInt(),(40*density).toInt(),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        touch.addView(android.view.View(this).apply{
            background=GradientDrawable().apply{setColor(Color.LTGRAY);cornerRadius=2*density}
        },FrameLayout.LayoutParams((40*density).toInt(),(4*density).toInt(),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=(8*density).toInt()})
        var mini=false;var downX=0f;var downY=0f;var cancelled=false
        fun standard(){mini=false;card.visibility=android.view.View.VISIBLE;card.animate().x(baseX).y(baseY).scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()}
        card.setOnClickListener{if(mini)standard()}
        controls.addView(Button(this).apply{text="模拟挂起 / 重开窗口";setOnClickListener{
            card.visibility=android.view.View.VISIBLE;mini=true
            card.animate().x(width-width*.25f-12*density).y(56*density).scaleX(width*.25f/cw)
                .scaleY(width*.25f/cw).alpha(1f).setDuration(260).start()
        }})
        touch.setOnTouchListener{_,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;cancelled=false;card.animate().cancel()}
                MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->{cancelled=true;standard()}
                MotionEvent.ACTION_MOVE->{if(!cancelled){
                    val dy=event.rawY-downY;val s=GesturePolicy.followScale(dy,density)
                    card.scaleX=s;card.scaleY=s
                    card.x=baseX+cw*(1f-s)/2f;card.y=baseY+ch*(1f-s)/2f;card.alpha=1f
                }}
                MotionEvent.ACTION_UP->{
                    when(GesturePolicy.handle(event.rawX-downX,event.rawY-downY,0f,density,cancelled)){
                        GesturePolicy.Result.RESTORE->standard()
                        GesturePolicy.Result.RESIZE->{ /* Keep the current proportional preview. */ }
                        GesturePolicy.Result.FULLSCREEN->{mini=false;card.animate().x(0f).y(0f).scaleX(width/cw).scaleY(height/ch).alpha(1f).setDuration(220).start()}
                        GesturePolicy.Result.HANG_MINI->{
                            mini=true
                            card.animate().x(width-width*.25f-12*density).y(48*density).scaleX(width*.25f/cw)
                                .scaleY(width*.25f/cw).alpha(1f).setDuration(240).start()
                        }
                        GesturePolicy.Result.CLOSE->{
                            val targetS=GesturePolicy.closeTargetScale(card.scaleX)
                            card.animate().x(baseX+cw*(1f-targetS)/2f).y(baseY+ch*(1f-targetS)/2f)
                                .scaleX(targetS).scaleY(targetS).alpha(0f).setDuration(220)
                                .withEndAction{card.visibility=android.view.View.GONE}.start()
                        }
                    }
                }
            };true
        }
    }
}
