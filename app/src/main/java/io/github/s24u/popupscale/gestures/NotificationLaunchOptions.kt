package io.github.s24u.popupscale.gestures

import android.app.ActivityOptions
import android.graphics.Rect
import android.os.Bundle

/** Keep native bounds/BAL/options; never inspect or replace the notification's Intent. */
// Runs inside LSPosed's privileged SystemUI process; the contract is checked at install.
@android.annotation.SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi")
object NotificationLaunchOptions {
    private val mode=ActivityOptions::class.java.getDeclaredMethod("setLaunchWindowingMode",Integer.TYPE).apply{isAccessible=true}
    private val from=ActivityOptions::class.java.getDeclaredMethod("fromBundle",Bundle::class.java).apply{isAccessible=true}
    private val cookie=runCatching{ActivityOptions::class.java.getDeclaredMethod("setLaunchCookie",android.os.IBinder::class.java).apply{isAccessible=true}}.getOrNull()
    @JvmStatic fun freeform(original:Bundle):Bundle=freeform(original,null)
    @JvmStatic fun freeform(original:Bundle,bounds:Rect?):Bundle {
        val options=from.invoke(null,original) as ActivityOptions
        mode.invoke(options,5)
        if(bounds!=null&&!bounds.isEmpty)options.launchBounds=Rect(bounds)
        return Bundle(original).apply{putAll(options.toBundle())}
    }
    @JvmStatic fun notification(original:Bundle,bounds:Rect,identity:NotificationRouteIdentity):Bundle {
        val bundle=freeform(original,bounds)
        val options=from.invoke(null,bundle) as ActivityOptions
        val existing=runCatching{ActivityOptions::class.java.getDeclaredMethod("getLaunchCookie").invoke(options)}.getOrNull()
        // Existing native launch cookies belong to their original controller. Keep them.
        if(existing!=null||cookie==null)return bundle
        val marker=android.os.Binder().apply{attachInterface(null,identity.descriptor())}
        cookie.invoke(options,marker)
        return Bundle(bundle).apply{putAll(options.toBundle())}
    }
    @JvmStatic fun hasRemoteAnimation(original:Bundle):Boolean {
        val options=from.invoke(null,original) as ActivityOptions
        return listOf("getRemoteAnimationAdapter","getRemoteTransition").any{n->
            runCatching{ActivityOptions::class.java.getDeclaredMethod(n).apply{isAccessible=true}.invoke(options)!=null}.getOrDefault(false)
        }
    }
}
