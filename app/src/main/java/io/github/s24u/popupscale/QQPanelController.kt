package io.github.s24u.popupscale

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.util.Log
import android.view.View
import android.view.ViewParent
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.function.BooleanSupplier
import kotlin.math.roundToInt

/** QQ 9.3.80 input geometry only. Never reads messages, emoji data or keyboard preferences. */
object QQPanelController {
    private var selected: BooleanSupplier? = null
    private var recentCanvas = WeakReference<ZoomFrameLayout>(null)
    private var version = -1L
    private val virtualColumnWidth = ThreadLocal<Int>()

    @JvmStatic fun watch(activity: Activity, frame: ZoomFrameLayout) {
        if (activity.packageName == QQDensityPolicy.PACKAGE) recentCanvas = WeakReference(frame)
    }

    @JvmStatic fun install(loader: ClassLoader, name: String, process: String, enabled: BooleanSupplier) {
        if (name != QQDensityPolicy.PACKAGE || process != name || Process.myUid() / 100000 != 0) return
        selected = enabled
        // Verified APK: l(panelId) computes the target for app panels; IME uses A/G directly.
        // Native animation updates both nested hosts. Mutating just one host clips the panel.
        val type = X.findClassIfExists("com.tencent.input.base.panelcontainer.j", loader)
        val target = type?.let { X.findMethodExactIfExists(it, "l", Int::class.javaPrimitiveType) }
        if (target != null && target.returnType == Int::class.javaPrimitiveType &&
            runCatching { X.findField(type, "f").type.name ==
                "com.tencent.input.base.panelcontainer.PanelFrameLayout" &&
                X.findField(type, "j").type == Boolean::class.javaPrimitiveType }.getOrDefault(false)) {
            val reported = WeakHashMap<View, String>()
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (param.hasThrowable()) return@runCatching
                        val panel = X.getObjectField(param.thisObject, "f") as? View ?: return@runCatching
                        val frame = canvas(panel) ?: return@runCatching
                        val activity = eligible(frame) ?: return@runCatching
                        if (!X.getBooleanField(param.thisObject, "j") || (param.args[0] as Int) < 0) return@runCatching
                        val original = param.result as Int
                        val visibleHeight = (frame.height - frame.topOffset()).coerceAtLeast(1)
                        val viewport = (visibleHeight / frame.measureScale()).roundToInt()
                        val height = QQPanelPolicy.height(true, version, 0, 0, 5,
                            frame.measureScale(), activity.resources.displayMetrics.density,
                            viewport, original)
                        param.result = height
                        val stamp = "native=$original target=$height viewport=$viewport scale=${frame.measureScale()}"
                        if (reported[panel] != stamp) {
                            reported[panel] = stamp
                            Log.i("PopupScaleQQ", "native input panel $stamp")
                            val weak = WeakReference(panel)
                            panel.postDelayed({
                                runCatching {
                                    val p = weak.get() as? ViewGroup ?: return@runCatching
                                    if (!p.isShown || eligible(frame) == null) return@runCatching
                                    Log.i("PopupScaleQQ", "input panel settled host=${p.width}x${p.height} childHeight=${p.getChildAt(0)?.height} parentHeight=${(p.parent as? View)?.height}")
                                }
                            }, 450L)
                        }
                    }.onFailure { Log.i("PopupScaleQQ", "input panel kept native: ${it.javaClass.simpleName}") }
                }
            })
            Log.i("PopupScaleQQ", "native input panel target hook ready module=${BuildConfig.VERSION_CODE}")
        }
        installColumns(loader)
    }

    private fun installColumns(loader: ClassLoader) {
        val constants = X.findClassIfExists("com.tencent.mobileqq.emoticonview.EmotionPanelConstans", loader) ?: return
        val predicate = X.findMethodExactIfExists(constants, "isSatisfiedNewColumnUI") ?: return
        val utils = X.findClassIfExists("com.tencent.mobileqq.utils.ViewUtils", loader) ?: return
        val width = X.findMethodExactIfExists(utils, "getScreenWidthByContext", Context::class.java) ?: return
        if (predicate.returnType != Boolean::class.javaPrimitiveType || width.returnType != Int::class.javaPrimitiveType) return
        XposedBridge.hookMethod(predicate, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val frame = recentCanvas.get() ?: return@runCatching
                    if (eligible(frame) == null) return@runCatching
                    param.setObjectExtra("qq.previous.width", virtualColumnWidth.get())
                    param.setObjectExtra("qq.width.entered", true)
                    virtualColumnWidth.set(QQPanelPolicy.layoutWidth(frame.width, frame.measureScale()))
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.getObjectExtra("qq.width.entered") != true) return
                val previous = param.getObjectExtra("qq.previous.width") as? Int
                if (previous == null) virtualColumnWidth.remove() else virtualColumnWidth.set(previous)
            }
        })
        // Only the column predicate sees virtual width. Font and QQ feature checks stay intact.
        XposedBridge.hookMethod(width, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val value = virtualColumnWidth.get() ?: return
                if (!param.hasThrowable()) param.result = value
            }
        })
    }

    private fun eligible(frame: ZoomFrameLayout): Activity? {
        if (selected?.asBoolean != true || !frame.isAttachedToWindow || frame.width <= 0 || frame.height <= 0 ||
            !frame.measureScale().isFinite() || frame.measureScale() !in 0.05f..0.999f) return null
        val activity = owner(frame.context) ?: return null
        if (activity.isDestroyed || activity.isFinishing || !activity.isInMultiWindowMode) return null
        if (version < 0) version = activity.packageManager.getPackageInfo(QQDensityPolicy.PACKAGE, 0).longVersionCode
        if (!QQPanelPolicy.supportedVersion(version)) return null
        @Suppress("DEPRECATION") val display = activity.windowManager.defaultDisplay.displayId
        val mode = X.callMethod(X.getObjectField(activity.resources.configuration, "windowConfiguration"), "getWindowingMode") as Int
        return if (display == 0 && mode == 5) activity else null
    }

    private fun owner(context: Context): Activity? {
        var c = context
        repeat(12) {
            if (c is Activity) return c as Activity
            val next = (c as? ContextWrapper)?.baseContext ?: return null
            if (next === c) return null
            c = next
        }
        return null
    }
    private fun canvas(view: View): ZoomFrameLayout? {
        var p: ViewParent? = view.parent
        repeat(32) {
            if (p is ZoomFrameLayout) return p as ZoomFrameLayout
            p = p?.parent ?: return null
        }
        return null
    }
}
