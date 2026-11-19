package io.github.s24u.popupscale.gestures

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import io.github.s24u.popupscale.BuildConfig

/** A missing/old/unscoped app observer leaves the task non-focusable. No guessed player calls. */
@android.annotation.TargetApi(34)
class MiniPlaybackIntent(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    fun request(id: Int, done: (Boolean, String) -> Unit) {
        var completed = false
        fun finish(allowed: Boolean,reason:String) {
            if (!completed) {
                completed = true
                Guard.info("mini playback observer result task=$id allowed=$allowed reason=$reason")
                done(allowed, reason)
            }
        }
        val expectedUid=runCatching{context.packageManager.getApplicationInfo(MiniPlaybackPolicy.PACKAGE,0).uid}
            .getOrElse{finish(false,"app-not-installed");return}
        // Authenticate on the Binder thread; posting through a Handler first loses the UID.
        val reply = object : ResultReceiver(null) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if(android.os.Binder.getCallingUid()!=expectedUid){
                    return
                }
                main.post{receive(code,data)}
            }
            private fun receive(code:Int,data:Bundle?){
                if(data?.getInt("task",-1)!=id||data.getInt("version",-1)!=BuildConfig.VERSION_CODE){
                    finish(false,"invalid-reply");return
                }
                val reason=data.getString("reason").orEmpty().takeIf{it in setOf(
                    "playing","not-playing","no-snapshot","state-unknown","expired","wrong-task-or-locked")}
                    ?:if(code==1)"playing" else "observer-denied"
                val source=data.getString("source").orEmpty().takeIf{it in setOf("live-visible","feed","unavailable","none")}?:"unknown"
                finish(code==1,"$reason source=$source")
            }
        }
        val frameworkReply=Guard.call("mini-playback-reply-proxy"){FrameworkResultReceiver.proxy(reply)}
            ?:run{finish(false,"reply-proxy-failed");return}
        main.postDelayed({ finish(false,"timeout-check-app-scope-and-restart") }, 1000)
        if (Guard.call("mini-playback-intent") {
                context.sendBroadcast(Intent(MiniPlaybackPolicy.ACTION).setPackage(MiniPlaybackPolicy.PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("reply", frameworkReply).putExtra("task", id)
                    .putExtra("version", BuildConfig.VERSION_CODE).putExtra("when", SystemClock.uptimeMillis()),
                    null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
                        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE).toBundle())
                true
            } != true) finish(false,"request-failed")
    }
}
