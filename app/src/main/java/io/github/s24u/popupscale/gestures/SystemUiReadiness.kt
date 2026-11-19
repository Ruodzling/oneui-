package io.github.s24u.popupscale.gestures

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.s24u.popupscale.BuildConfig

/** Missing/failed SystemUI capabilities must leave HOME and Launcher gestures native. */
@android.annotation.TargetApi(34)
object SystemUiReadiness {
    const val ACTION="io.github.s24u.popupscale.SYSTEM_UI_READY"
    @Volatile private var until=0L
    private var registered=false
    @Volatile private var lastSignal=0L
    @Volatile private var rejectedSignals=0L
    fun snapshot()=org.json.JSONObject().put("registered",registered).put("ready",ready())
        .put("validForMs",(until-SystemClock.uptimeMillis()).coerceAtLeast(0L))
        .put("lastAcceptedElapsed",lastSignal).put("rejectedSignals",rejectedSignals)
    fun ready()=SystemClock.uptimeMillis()<until
    fun accept(intent:Intent) {
        val next=CompatibilityPolicy.readiness(SystemClock.uptimeMillis(),intent.getLongExtra("when",-1),
            intent.getBooleanExtra("ready",false),intent.getIntExtra("version",-1),BuildConfig.VERSION_CODE)
        if(next==null){rejectedSignals++;return}
        lastSignal=SystemClock.elapsedRealtime()
        until=next
    }
    fun attachLauncher(context:Context) {
        if(registered)return
        val uid=context.packageManager.getApplicationInfo(RomProfile.SYSTEM_UI,0).uid
        context.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(c:Context,intent:Intent){
                if(sentFromUid==uid&&intent.action==ACTION)accept(intent)
            }
        },IntentFilter(ACTION),MiniTaskLease.PERMISSION,Handler(Looper.getMainLooper()),Context.RECEIVER_EXPORTED)
        registered=true
    }
    fun announce(context:Context,ready:Boolean) {
        val now=SystemClock.uptimeMillis()
        for(target in listOf("android",RomProfile.LAUNCHER)){
            context.sendBroadcast(Intent(ACTION).setPackage(target).putExtra("when",now)
                .putExtra("ready",ready).putExtra("version",BuildConfig.VERSION_CODE),
                if(target=="android")MiniTaskLease.PERMISSION else null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }
}
