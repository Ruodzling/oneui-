package io.github.s24u.popupscale.gestures

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast

/** One suspended snapshot slot. Journal-before-hide makes a SystemUI restart recoverable. */
class MiniWindowController(private val context: Context, private val backend: TaskBackend) {
    private val journal = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("popupscale_gesture_recovery", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var pendingRecovery = journal.getString("hidden", "").orEmpty()
    private var activeId = -1
    private var savedBounds = Rect()
    private var root: FrameLayout? = null
    private var animator: ValueAnimator? = null
    private var screenOff = false
    private var shadeObscured = false
    fun setShadeObscured(obscured:Boolean){
        shadeObscured=obscured
        val view=root ?: return
        val params=view.layoutParams as WindowManager.LayoutParams
        params.flags=if(obscured)params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        manager.updateViewLayout(view,params)
    }
    private var restorePending = false
    fun isMini(id: Int) = activeId == id
    fun activeTaskId() = activeId
    fun busy(): Boolean {
        if (pendingRecovery.isNotEmpty()) {
            val parts = pendingRecovery.split(',').mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty() || backend.info(parts[0]) == null) {
                check(journal.edit().remove("hidden").commit())
                pendingRecovery = ""
            }
        }
        return activeId >= 0 || pendingRecovery.isNotEmpty() || restorePending
    }
    fun recoverIfNeeded() {
        if (pendingRecovery.isEmpty() || restorePending) return
        val parts = pendingRecovery.split(',').mapNotNull { it.toIntOrNull() }
        if (parts.size != 5) { pendingRecovery = ""; journal.edit().remove("hidden").commit(); return }
        if (backend.info(parts[0]) == null) {
            check(journal.edit().remove("hidden").commit())
            pendingRecovery = ""
            Guard.info("mini snapshot recovery cleared dead task=${parts[0]}")
            return
        }
        val marker=pendingRecovery
        restorePending=true
        backend.restore(parts[0], Rect(parts[1],parts[2],parts[3],parts[4])) callback@{ ok ->
            if(pendingRecovery!=marker)return@callback
            restorePending=false
            if(ok){
                check(journal.edit().remove("hidden").commit())
                pendingRecovery=""
            }
        }
    }
    fun hang(task: TaskRecord) {
        if (activeId >= 0 || pendingRecovery.isNotEmpty() || backend.locked()) return
        if(!backend.canRestore()){Guard.info("hang ignored: native recovery unavailable");return}
        val metrics = GestureDisplay.metrics(context)
        val density = metrics.density
        val width = (metrics.widthPixels * .76f).toInt()
        val height = minOf((width * 1.65f).toInt(), (metrics.heightPixels * .7f).toInt())
        val bounds = Rect((metrics.widthPixels-width)/2, (metrics.heightPixels-height)/2,
            (metrics.widthPixels+width)/2, (metrics.heightPixels+height)/2)
        val bitmap = backend.snapshot(task.id)
        val view = FrameLayout(context)
        view.background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = 12*density }
        view.clipToOutline = true
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
        if (bitmap != null) image.setImageBitmap(bitmap)
        else {
            image.scaleType=ImageView.ScaleType.CENTER_INSIDE
            image.setImageDrawable(context.packageManager.getApplicationIcon(task.packageName))
        }
        view.addView(image,FrameLayout.LayoutParams(-1,-1))
        view.contentDescription = "迷你小窗，点击恢复"
        val params = WindowManager.LayoutParams(width,height,2024,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = task.bounds.left; params.y = task.bounds.top
        params.setTitle("PopupScaleMiniPreview")
        if(shadeObscured)params.flags=params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val recovery = "${task.id},${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        check(journal.edit().putString("hidden", recovery).commit()) { "Recovery journal unavailable" }
        try {
            manager.addView(view,params)
            backend.hideMini(task)
            activeId = task.id; savedBounds = bounds; root = view
            backend.returnHome()
            Guard.info("mini created task=${task.id} preview=${if(bitmap!=null)"snapshot" else "icon"}")
            view.setOnClickListener { if(!shadeObscured)restore() }
            val targetWidth = (metrics.widthPixels * .25f).toInt()
            val aspect = if (bitmap != null) bitmap.height.toFloat()/bitmap.width else task.bounds.height().toFloat()/task.bounds.width()
            val targetHeight = minOf((targetWidth*aspect).toInt(),(metrics.heightPixels*.32f).toInt())
            animate(view,params,Rect(task.bounds),Rect(metrics.widthPixels-targetWidth-(12*density).toInt(),
                (48*density).toInt(),metrics.widthPixels-(12*density).toInt(),(48*density).toInt()+targetHeight)) {
                if(activeId==task.id&&!backend.locked())Guard.call("mini-home-settle"){backend.returnHome()}
            }
        } catch(error: Throwable) {
            animator?.cancel();animator=null;root=null;activeId=-1
            runCatching { manager.removeViewImmediate(view) }
            pendingRecovery=recovery;restorePending=false
            recoverIfNeeded()
            throw error
        }
    }
    fun restore() {
        val view = root ?: return
        if(restorePending)return
        restorePending=true
        val id=activeId
        val params = view.layoutParams as WindowManager.LayoutParams
        val from = Rect(params.x,params.y,params.x+params.width,params.y+params.height)
        animate(view,params,from,savedBounds) {
            backend.restore(id,savedBounds) callback@{ ok ->
                if(root!==view||activeId!=id)return@callback
                restorePending=false
                if(ok){
                    Guard.info("mini restore accepted task=$id")
                    journal.edit().remove("hidden").commit()
                    removeView()
                }else{
                    animate(view,params,Rect(savedBounds),from){}
                }
            }
        }
    }
    fun restoreImmediately() {
        if(restorePending)return
        if (activeId >= 0) {
            val id=activeId;val view=root
            restorePending=true
            backend.restore(id,savedBounds) callback@{ ok ->
                if(activeId!=id||root!==view)return@callback
                restorePending=false
                if(ok){journal.edit().remove("hidden").commit();removeView()}
            }
            return
        }
        recoverIfNeeded()
    }
    fun onTaskVanished(id: Int) {
        if (id == activeId) { journal.edit().remove("hidden").commit(); removeView() }
    }
    fun hideForLock() { screenOff=true;root?.visibility=View.GONE }
    fun screenOn() { screenOff=false;lockVisibility() }
    fun lockVisibility() { root?.visibility = if(screenOff||backend.locked()) View.GONE else View.VISIBLE }
    private fun removeView() {
        animator?.cancel(); animator = null
        root?.let { manager.removeViewImmediate(it) }; root = null; activeId = -1;restorePending=false
    }
    private fun animate(view: View, params: WindowManager.LayoutParams, from: Rect, to: Rect, end: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = 260
            addUpdateListener {
                val t = it.animatedValue as Float
                params.x = (from.left+(to.left-from.left)*t).toInt()
                params.y = (from.top+(to.top-from.top)*t).toInt()
                params.width = (from.width()+(to.width()-from.width())*t).toInt().coerceAtLeast(1)
                params.height = (from.height()+(to.height()-from.height())*t).toInt().coerceAtLeast(1)
                Guard.call("mini-animation") { manager.updateViewLayout(view,params) }
            }
            var cancelled = false
            addListener(object:android.animation.AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(animation:android.animation.Animator){if(!cancelled)end()}
            })
            start()
        }
    }
}
