package io.github.s24u.popupscale

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.function.BooleanSupplier
import org.json.JSONObject

/** Verified QQ/TIM display-restart checks only; never hooks Process.killProcess or System.exit. */
object QQDensityController {
    @JvmStatic fun install(loader: ClassLoader, name: String, process: String, enabled: BooleanSupplier) {
        if (QQDensityPolicy.restartTargets.none { it.pkg==name } || process != name || Process.myUid() / 100000 != 0) return
        var physical = displayDpi()
        var scaledBefore = false
        var version = 0L
        var current = WeakReference<Activity>(null)
        X.findAndHookMethod(android.app.Instrumentation::class.java, "callApplicationOnCreate", Application::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val app = param.args[0] as Application
                        version = app.packageManager.getPackageInfo(name, 0).longVersionCode
                        physical = displayDpi()
                        Log.i("PopupScaleQQ", "density observer version=$version physicalDpi=$physical")
                    }
                }
            })
        for (method in arrayOf("callActivityOnCreate", "callActivityOnResume")) {
            XposedBridge.hookAllMethods(android.app.Instrumentation::class.java, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val a = param.args.firstOrNull() as? Activity ?: return
                    if (a.packageName == name) current = WeakReference(a)
                }
            })
        }
        var hooked=0
        for(target in QQDensityPolicy.restartTargets.filter { it.pkg==name }) {
        val manager = X.findClassIfExists(target.manager, loader) ?: continue
        val check = if(target.contextArgument)X.findMethodExactIfExists(manager,target.method,
            Configuration::class.java,Context::class.java)
            else X.findMethodExactIfExists(manager,target.method,Configuration::class.java)
        if(check==null||!Modifier.isStatic(check.modifiers)||check.returnType!=Void.TYPE)continue
        val originField=runCatching{X.findField(manager,target.originField)}.getOrNull()?:continue
        if(!Modifier.isStatic(originField.modifiers)||originField.type!=DisplayMetrics::class.java)continue
        hooked++
        XposedBridge.hookMethod(check, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val config = param.args[0] as? Configuration ?: return@runCatching
                    val native = displayDpi()
                    val previous = physical
                    if (native in 120..1000) physical = native
                    val selected = enabled.asBoolean
                    val suppliedMode = mode(config)
                    val windowMode = if (suppliedMode in arrayOf(1, 5)) suppliedMode
                        else current.get()?.resources?.configuration?.let { mode(it) } ?: suppliedMode
                    val owner = current.get()?.takeUnless { it.isDestroyed }
                    @Suppress("DEPRECATION")
                    val displayId = owner?.windowManager?.defaultDisplay?.displayId ?: -1
                    val origin = (originField.get(null) as? DisplayMetrics)?.densityDpi ?: 0
                    val suppress = QQDensityPolicy.windowChange(target,version, selected, Process.myUid() / 100000,
                        previous, native, config.densityDpi, origin, windowMode, scaledBefore, displayId)
                    if (selected && windowMode == 5 && previous == native &&
                        QQDensityPolicy.restartTarget(name,version)==target &&
                        (config.densityDpi != native || origin != native)) scaledBefore = true
                    if (windowMode == 1 || previous != native || !selected) scaledBefore = false
                    Log.i("PopupScaleQQ", "density check package=$name version=$version mode=$windowMode supplied=${config.densityDpi} origin=$origin display=$previous->$native displayId=$displayId windowOnly=$suppress")
                    if (suppress) param.result = null
                }.onFailure { Log.i("PopupScaleQQ", "density compatibility kept native: ${it.javaClass.simpleName}") }
            }
        })
        Log.i("PopupScaleQQ","density restart check installed package=$name version=${target.version} callback=${target.manager}.${target.method}")
        }
    }
    private fun mode(config: Configuration): Int = runCatching {
        X.callMethod(X.getObjectField(config, "windowConfiguration"), "getWindowingMode") as Int
    }.getOrDefault(0)
    private fun displayDpi(): Int = runCatching {
        val global = X.callStaticMethod(X.findClass("android.hardware.display.DisplayManagerGlobal", null), "getInstance")
        X.getIntField(X.callMethod(global, "getDisplayInfo", 0), "logicalDensityDpi")
    }.getOrDefault(0)
}
