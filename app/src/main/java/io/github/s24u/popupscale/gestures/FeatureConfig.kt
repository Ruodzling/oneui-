package io.github.s24u.popupscale.gestures

import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences
import io.github.s24u.popupscale.ModuleSettings
import io.github.s24u.popupscale.AppScopePolicy
import io.github.s24u.popupscale.ExternalScalePolicy
import io.github.s24u.popupscale.ScalePolicy
import io.github.s24u.popupscale.SettingsDefaults
import io.github.s24u.popupscale.PlayerCompatPolicy
import io.github.s24u.popupscale.QQDensityPolicy
import java.util.concurrent.ConcurrentHashMap

class FeatureConfig(private val owner:String="unspecified") {
    companion object {
        private val instances=java.util.WeakHashMap<FeatureConfig,Boolean>()
        fun snapshots():org.json.JSONArray {
            val copy=synchronized(instances){
                val out=ArrayList<FeatureConfig>()
                val it=instances.keys.iterator()
                while(it.hasNext())out.add(it.next())
                out
            }
            return org.json.JSONArray().apply{copy.forEach{put(it.snapshot())}}
        }
    }
    private val prefs = XSharedPreferences(ModuleSettings.PACKAGE, ModuleSettings.PREFS)
    private val providerCache = ConcurrentHashMap<String, Any>()
    private var last = -5000L
    private var hasLoaded = false
    var enabled = false; private set
    var observe = false; private set
    var closeToBack = true; private set
    var handles = true; private set
    var frameless = true; private set
    var notificationPull = true; private set
    var hang = true; private set
    var live = false; private set
    private var miniKeepPlayback = true
    private var miniKeepPlaybackAll = false
    private var miniKeepPlaybackPackages: Set<String> = parsePackagesSafe(MiniPlaybackPolicy.DEFAULT_PACKAGES)
    private var all = false
    private var demo = false
    private var selected: Set<String> = emptySet()
    private var excluded: Set<String> = emptySet()
    private var contentEnabled = false
    private var contentDiagnostic = false
    private var referenceDp = ScalePolicy.DEFAULT_REFERENCE_DP
    private var unifiedScale = false
    private var source="not-loaded"
    private var providerAt=-1L
    private var prefsReadable=false
    private var prefsHaveData=false
    private var lastError=""
    @Volatile private var providerDirty=false
    init { synchronized(instances){instances[this]=true} }

    @Synchronized fun invalidateProvider() {
        providerDirty = true
        last = -5000L
    }

    @Synchronized fun needsProviderSync(): Boolean =
        !prefsReadable && !prefsHaveData && (providerCache.isEmpty() || providerDirty)

    fun applyProviderMap(values: Map<String, Any>): Boolean {
        if (values["enabled"] !is Boolean) return false
        val b = Bundle()
        for ((k, v) in values) {
            when (v) {
                is Boolean -> b.putBoolean(k, v)
                is Int -> b.putInt(k, v)
                is Long -> b.putLong(k, v)
                is Float -> b.putFloat(k, v)
                is String -> b.putString(k, v)
            }
        }
        applyBundle(b)
        return true
    }

    @Synchronized private fun snapshot()=org.json.JSONObject().put("owner",owner).put("source",source)
        .put("loaded",hasLoaded).put("lastReloadUptime",last).put("providerAppliedUptime",providerAt)
        .put("prefsReadable",prefsReadable).put("prefsHaveData",prefsHaveData).put("lastError",lastError)
        .put("enabled",enabled).put("observe",observe).put("handles",handles).put("frameless",frameless)
        .put("hang",hang).put("live",live).put("miniKeepPlayback",miniKeepPlayback).put("miniKeepPlaybackAll",miniKeepPlaybackAll).put("miniKeepPlaybackCount",miniKeepPlaybackPackages.size).put("notificationPull",notificationPull).put("closeToBack",closeToBack)
        .put("allApps",all).put("contentEnabled",contentEnabled).put("contentDiagnostic",contentDiagnostic)
        .put("unifiedScale",unifiedScale).put("referenceDp",referenceDp)
        .put("selectedCount",selected.size).put("excludedCount",excluded.size)

    @Synchronized fun reload() {
        val now = SystemClock.uptimeMillis()
        if (now - last < 750) return
        last = now
        runCatching {
            prefs.reload()
            val fileReadable = runCatching { prefs.file.canRead() }.getOrDefault(false)
            val hasData = runCatching { prefs.all?.isNotEmpty() == true }.getOrDefault(false)
            prefsReadable=fileReadable; prefsHaveData=hasData;lastError=""
            if (fileReadable || hasData) {
                source="shared-preferences-with-provider-fallback"
                observe = false
                handles = flag("system_handle")
                closeToBack = flag("handle_close_to_back")
                frameless = flag("system_frameless")
                notificationPull = flag("notification_pull_popup")
                hang = flag("system_hang")
                live = flag("system_live")
                miniKeepPlayback = flag("mini_keep_playback")
                miniKeepPlaybackAll = flag("mini_keep_playback_all")
                miniKeepPlaybackPackages = parsePackagesSafe(prefsString("mini_keep_playback_packages", MiniPlaybackPolicy.DEFAULT_PACKAGES))
                all = flag("all_apps")
                demo = flag("demo")
                selected = ModuleSettings.parsePackages(prefs.getString("packages", ModuleSettings.DEFAULT_PACKAGES))
                excluded = ModuleSettings.parsePackages(prefs.getString("excluded_packages", ""))
                contentEnabled = flag("enabled")
                contentDiagnostic = false
                referenceDp = prefs.getInt("reference_dp", ScalePolicy.DEFAULT_REFERENCE_DP)
                unifiedScale = flag("system_scale_all")
                enabled = SettingsDefaults.gesturesEnabled(contentEnabled, flag("system_features"))
                hasLoaded = true
            } else if (providerCache.isNotEmpty()) {
                source="provider-cache"
                observe = false
                handles = provFlag("system_handle", true)
                closeToBack = provFlag("handle_close_to_back", true)
                frameless = provFlag("system_frameless", true)
                notificationPull = provFlag("notification_pull_popup", true)
                hang = provFlag("system_hang", true)
                live = provFlag("system_live", true)
                miniKeepPlayback = provFlag("mini_keep_playback", true)
                miniKeepPlaybackAll = provFlag("mini_keep_playback_all", false)
                miniKeepPlaybackPackages = parsePackagesSafe(provString("mini_keep_playback_packages", MiniPlaybackPolicy.DEFAULT_PACKAGES))
                all = provFlag("all_apps", true)
                demo = provFlag("demo", true)
                selected = ModuleSettings.parsePackages(provString("packages", ModuleSettings.DEFAULT_PACKAGES))
                excluded = ModuleSettings.parsePackages(provString("excluded_packages", ""))
                contentEnabled = provFlag("enabled", true)
                contentDiagnostic = false
                referenceDp = provInt("reference_dp", ScalePolicy.DEFAULT_REFERENCE_DP)
                unifiedScale = provFlag("system_scale_all", true)
                enabled = SettingsDefaults.gesturesEnabled(contentEnabled, provFlag("system_features", true))
                hasLoaded = true
            } else if (!hasLoaded) {
                source="defaults"
                // Initial defaults: module injected into process means user has it active
                observe = SettingsDefaults.flag("system_observe", false)
                handles = SettingsDefaults.flag("system_handle", true)
                closeToBack = SettingsDefaults.flag("handle_close_to_back", true)
                frameless = SettingsDefaults.flag("system_frameless", true)
                notificationPull = SettingsDefaults.flag("notification_pull_popup", true)
                hang = SettingsDefaults.flag("system_hang", true)
                live = SettingsDefaults.flag("system_live", true)
                miniKeepPlayback = SettingsDefaults.flag("mini_keep_playback", true)
                miniKeepPlaybackAll = SettingsDefaults.flag("mini_keep_playback_all", false)
                miniKeepPlaybackPackages = parsePackagesSafe(MiniPlaybackPolicy.DEFAULT_PACKAGES)
                all = SettingsDefaults.flag("all_apps", true)
                demo = SettingsDefaults.flag("demo", true)
                selected = ModuleSettings.parsePackages(ModuleSettings.DEFAULT_PACKAGES)
                excluded = emptySet()
                contentEnabled = true
                contentDiagnostic = false
                referenceDp = ScalePolicy.DEFAULT_REFERENCE_DP
                unifiedScale = true
                enabled = true
                hasLoaded = true
            }
        }.onFailure{lastError=it.javaClass.name}
    }

    @Synchronized fun applyBundle(bundle: Bundle) {
        runCatching {
            if (!bundle.isEmpty) providerCache.clear()
            providerDirty = false
            for (key in bundle.keySet()) {
                bundle.get(key)?.let { providerCache[key] = it }
            }
            observe = false
            handles = provFlag("system_handle", true)
            closeToBack = provFlag("handle_close_to_back", true)
            frameless = provFlag("system_frameless", true)
            notificationPull = provFlag("notification_pull_popup", true)
            hang = provFlag("system_hang", true)
            live = provFlag("system_live", true)
            miniKeepPlayback = provFlag("mini_keep_playback", true)
            miniKeepPlaybackAll = provFlag("mini_keep_playback_all", false)
            miniKeepPlaybackPackages = parsePackagesSafe(provString("mini_keep_playback_packages", MiniPlaybackPolicy.DEFAULT_PACKAGES))
            all = provFlag("all_apps", true)
            demo = provFlag("demo", true)
            selected = ModuleSettings.parsePackages(provString("packages", ModuleSettings.DEFAULT_PACKAGES))
            excluded = ModuleSettings.parsePackages(provString("excluded_packages", ""))
            contentEnabled = provFlag("enabled", true)
            contentDiagnostic = false
            referenceDp = provInt("reference_dp", ScalePolicy.DEFAULT_REFERENCE_DP)
            unifiedScale = provFlag("system_scale_all", true)
            enabled = SettingsDefaults.gesturesEnabled(contentEnabled, provFlag("system_features", true))
            hasLoaded = true
            source="provider-bundle";providerAt=SystemClock.uptimeMillis();lastError=""
        }.onFailure{lastError=it.javaClass.name}
    }

    private fun flag(key: String): Boolean {
        if (prefs.contains(key)) return prefs.getBoolean(key, SettingsDefaults.flag(key, false))
        val prov = providerCache[key]
        if (prov is Boolean) return prov
        return SettingsDefaults.flag(key, false)
    }

    private fun provFlag(key: String, def: Boolean) = (providerCache[key] as? Boolean) ?: SettingsDefaults.flag(key, def)
    private fun provInt(key: String, def: Int) = (providerCache[key] as? Int) ?: def
    private fun prefsString(key: String, def: String): String =
        if (prefs.contains(key)) prefs.getString(key, def) ?: def else provString(key, def)
    private fun parsePackagesSafe(raw: String): Set<String> =
        runCatching { ModuleSettings.parsePackages(raw) }.getOrDefault(emptySet())
    private fun provString(key: String, def: String) = (providerCache[key] as? String) ?: def

    @Synchronized fun accepts(name: String?) = name != null &&
        AppScopePolicy.includes(name, all, demo, selected, excluded)
    @Synchronized fun acceptsInteraction(name: String?) = name != null &&
        AppScopePolicy.includesInteraction(name, demo)
    @Synchronized fun allowsEdgeTop(name:String?,user:Int,display:Int,type:Int,mode:Int,width:Int,height:Int)=
        FrameworkLayoutPolicy.allowEdgeTop(enabled,observe,acceptsInteraction(name),user,display,type,mode,width,height)
    @Synchronized fun externalScale(name:String?,user:Int,display:Int,type:Int,mode:Int,
                                   width:Int,height:Int,nativeDpi:Int)=
        ExternalScalePolicy.calculate(name,unifiedScale,contentEnabled,contentDiagnostic,accepts(name),
            user,display,type,mode,width,height,nativeDpi,referenceDp)
    @Synchronized fun externalCandidate(name:String?)=ExternalScalePolicy.usesExternal(name,unifiedScale)
    @Synchronized fun unifiedMode()=unifiedScale
    @Synchronized fun homeMiniEnabled(name:String?)=enabled&&!observe&&live&&hang&&acceptsInteraction(name)
    @Synchronized fun homeMiniKeepPlayback(name:String?)=homeMiniEnabled(name)&&miniKeepPlayback&&(miniKeepPlaybackAll||(name!=null&&miniKeepPlaybackPackages.contains(name)))
    @Synchronized fun playerDensityConfig(name:String,activity:String,external:Boolean,user:Int,
        display:Int,type:Int,mode:Int,original:Int)=PlayerCompatPolicy.densityConfigChanges(
        name,activity,unifiedScale&&contentEnabled&&!contentDiagnostic&&accepts(name),external,
        user,display,type,mode,original)
    @Synchronized fun douyinDensityConfig(name:String,activity:String,external:Boolean,user:Int,
        display:Int,type:Int,mode:Int,original:Int)=io.github.s24u.popupscale.DouyinDensityPolicy.densityCallbacks(
        name,activity,contentEnabled&&!contentDiagnostic&&accepts(name),external,
        user,display,type,mode,original)
    @Synchronized fun qqDensityConfig(version:Long,name:String,activity:String,external:Boolean,user:Int,
        display:Int,type:Int,mode:Int,original:Int)=QQDensityPolicy.densityCallbacks(version,
        name,activity,unifiedScale&&contentEnabled&&!contentDiagnostic&&accepts(name),external,
        user,display,type,mode,original)
    @Synchronized fun portraitEntry(name:String?,user:Int,display:Int,type:Int,mode:Int,width:Int,height:Int,
                                    newEntry:Boolean,wide:Boolean,owned:Boolean)=
        FrameworkLayoutPolicy.portraitEntry(enabled,observe,handles,acceptsInteraction(name),user,display,type,mode,
            width,height,newEntry,wide,owned)
}
