package io.github.s24u.popupscale.gestures

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import io.github.s24u.popupscale.BuildConfig
import io.github.s24u.popupscale.ModuleSettings

/** Read-only ABI report, authenticated by the receiver's framework-supplied sender UID. */
object CompatibilityReporter {
    const val ACTION="io.github.s24u.popupscale.COMPATIBILITY_REPORT"
    fun install(role:String,loader:ClassLoader,summary:String,features:List<RomCapabilities.Feature> = emptyList()) {
        Guard.info("compatibility role=$role ${summary.replace('\n',';')}")
        if(android.os.Build.VERSION.SDK_INT<34)return
        val sent=java.util.concurrent.atomic.AtomicBoolean(false)
        val registered=java.util.concurrent.atomic.AtomicBoolean(false)
        fun send(context:Context,request:String=""){
            if(request.isEmpty()&&sent.get())return
            Handler(Looper.getMainLooper()).post{Guard.call("compatibility-report"){
                if(request.isEmpty()&&sent.get())return@call
                val checked=features.map{f->
                    val failed=when(f.key){
                        "frameless-outline"->Guard.failedAny("frameless-outline-install","frameless-outline-update-install",
                            "frameless-outline-owner-install","frameless-outline-draw-install")
                        "entry-transaction"->Guard.failedAny("server-popup-change-install")
                        "corner-symmetric"->Guard.failedAny("corner-install","corner-preview","corner-commit","corner-commit-result")
                        "notification-route"->Guard.failedAny("notification-route-install","notification-route-send","notification-route-start")
                        "frameless-sync"->Guard.failedAny("server-frameless-window-install")
                        "frameless-root"->Guard.failedAny("server-frameless-window-install","frameless-root-prepare")
                        else->false
                    }
                    if(failed)f.copy(available=false,missing=f.missing+"runtime hook installation failed") else f
                }
                val currentSummary=checked.joinToString("\n"){f->f.title+if(f.available)"：接口匹配（待实测）"
                    else "：未启用 [${f.missing.joinToString()}]"}.ifEmpty{summary}
                val details=""
                context.sendBroadcast(Intent(ACTION).setClassName(ModuleSettings.PACKAGE,
                    "io.github.s24u.popupscale.CompatibilityReportReceiver")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra("role",role).putExtra("summary",currentSummary.take(12000))
                    .putStringArrayListExtra("available",ArrayList(checked.filter{it.available}.map{it.key}))
                    .putExtra("partial",checked.isEmpty()||checked.any{!it.available})
                    .putExtra("request",request).putExtra("details",details)
                    .putExtra("xposedApi",runCatching{XposedBridge.getXposedVersion()}.getOrDefault(-1))
                    .putExtra("failures","")
                    .putExtra("version",BuildConfig.VERSION_CODE).putExtra("fingerprint",android.os.Build.FINGERPRINT),
                    null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                sent.set(true)
            }}
        }
        fun attach(context:Context){
            if(role!="android")io.github.s24u.popupscale.CloneProfileRegistry.refresh(context)
            send(context)
        }
        if(role=="android"){
            val atm=X.findClassIfExists("com.android.server.wm.ActivityTaskManagerService",loader) ?: return
            XposedBridge.hookAllMethods(atm,"onSystemReady",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    Guard.call("compatibility-context"){(X.getObjectField(p.thisObject,"mContext") as? Context)?.let{attach(it)}}
                }
            })
        }else{
            val currentApp: Context? = runCatching {
                Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
            }.getOrNull()
            if (currentApp != null && currentApp.packageName == role) {
                if(role==RomProfile.LAUNCHER)Guard.call("compatibility-launcher-ready"){SystemUiReadiness.attachLauncher(currentApp)}
                attach(currentApp)
            }
            XposedBridge.hookAllMethods(android.app.Application::class.java,"attach",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    val c=p.args.firstOrNull() as? Context ?: return
                    if(c.packageName==role){
                        if(role==RomProfile.LAUNCHER)Guard.call("compatibility-launcher-ready"){SystemUiReadiness.attachLauncher(c)}
                        attach(c)
                    }
                }
            })
            XposedBridge.hookAllMethods(android.app.Service::class.java,"onCreate",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    val svc = p.thisObject as? android.app.Service ?: return
                    if(svc.packageName==role){
                        attach(svc.applicationContext ?: svc)
                    }
                }
            })
        }
    }
}
