package io.github.s24u.popupscale.gestures

import android.graphics.Rect

/** Clear freeform always-on-top behavior and put the same task behind the foreground atomically. */
object BackgroundTaskOperation {
    @JvmStatic fun configure(transaction: Any, token: Any) {
        val type = transaction.javaClass
        val tokenType = Class.forName("android.window.WindowContainerToken")
        type.getMethod("setWindowingMode", tokenType, Integer.TYPE).invoke(transaction, token, 1)
        type.getMethod("setBounds", tokenType, Rect::class.java).invoke(transaction, token, null)
        type.getMethod("reorder", tokenType, java.lang.Boolean.TYPE).invoke(transaction, token, false)
    }
}
