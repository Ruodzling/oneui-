package io.github.s24u.popupscale.gestures

import android.util.Log
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X

object Guard {
    private val failures = mutableMapOf<String, Int>()
    inline fun <T> call(component: String, block: () -> T): T? = try { block() }
        catch (error: Throwable) { failure(component, error); null }
    fun info(message: String) {
        // Boot logcat may wrap before USB is connected; retain entry evidence in LSPosed too.
        runCatching { Log.i("PopupGestures", message) }
        runCatching { XposedBridge.log("PopupGestures $message") }
    }
    @Synchronized fun failure(component: String, error: Throwable) {
        val count = (failures[component] ?: 0) + 1
        failures[component] = count
        if (count <= 2) {
            val causes = generateSequence(error) { it.cause }.take(4)
                .joinToString(" <- ") { it.javaClass.simpleName }
            info("$component disabled/fallback: $causes")
        }
    }
    @Synchronized fun open(component: String) = (failures[component] ?: 0) < 3
    @Synchronized fun failedAny(vararg components:String)=components.any{(failures[it]?:0)>0}
    fun field(value: Any, vararg names: String): Any? {
        for (name in names) try { return X.getObjectField(value, name) } catch (_: Throwable) { }
        return null
    }
}
