package io.github.s24u.popupscale.gestures

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.s24u.popupscale.PopupScaleModule
import io.github.s24u.popupscale.SideGestureHook

class MainHook : IXposedHookLoadPackage {
    private var systemAttempted = false

    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if(p.packageName=="android") {
            if(systemAttempted||android.os.Process.myUid()!=1000)return
            systemAttempted=true
            install(p)
            return
        }
        if (p.packageName == SideGestureHook.PACKAGE_NAME) {
            val ok = SideGestureHook.install(p.classLoader)
            Guard.call("compatibility-report-install") {
                CompatibilityReporter.install(p.packageName, p.classLoader,
                    if (ok) "Good Lock 单手操作（OHO+）：快速启动器小窗已激活"
                    else "Good Lock 单手操作（OHO+）：未匹配到启动接口")
            }
            return
        }
        if (p.packageName != RomProfile.SYSTEM_UI && p.packageName != RomProfile.LAUNCHER) {
            PopupScaleModule().handleLoadPackage(p)
            return
        }
        if(p.packageName==RomProfile.SYSTEM_UI&&p.processName==NotificationPullPolicy.EDGE_PROCESS){
            Guard.info("brief notification left native; no module gesture hooks build=${io.github.s24u.popupscale.BuildConfig.VERSION_CODE}")
            return
        }
        if (p.processName != p.packageName) return
        // Shared-UID system packages need not carry the first-application marker.
        // Package + main process and this per-process guard define our boundary.
        if (systemAttempted) return
        systemAttempted = true
        Guard.info("entry package=${p.packageName} first=${p.isFirstApplication} " +
            "sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL} " +
            "build=${android.os.Build.VERSION.INCREMENTAL} profile=${RomProfile.supported()}")
        install(p)
    }
    private fun install(p:XC_LoadPackage.LoadPackageParam){
        if(!RomProfile.supported()){
            Guard.call("compatibility-report-install"){CompatibilityReporter.install(p.packageName,p.classLoader,
                "当前检测范围为三星 One UI 7/8/9（Android 15–17）。此系统未开启系统侧功能。")}
            return
        }
        val caps=Guard.call("compatibility-probe"){RomCapabilities(p.packageName,p.classLoader)}
        if(caps==null){
            Guard.call("compatibility-report-install"){CompatibilityReporter.install(p.packageName,p.classLoader,
                "接口检测异常，请导出诊断包查看 compatibility-probe 错误。")}
            return
        }
        Guard.call("compatibility-report-install"){CompatibilityReporter.install(p.packageName,p.classLoader,caps.summary(),caps.features)}
        if(!caps.samsungFramework)return
        val config = FeatureConfig(p.packageName)
        when(p.packageName){
            "android"->Guard.call("server-install"){SystemServerHooks(p.classLoader,caps).install()}
            RomProfile.SYSTEM_UI->if(caps.systemUi)Guard.call("system-install"){SystemUiHooks(p.classLoader,config,caps).install()}
            RomProfile.LAUNCHER->if(caps.launcher)Guard.call("launcher-install"){LauncherGestureHook(p.classLoader,config,caps.consumer!!).install()}
        }
    }
}
