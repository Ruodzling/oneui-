package io.github.s24u.popupscale;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.*;

/** Distinguishes missing scope/startup evidence, old code, and actual unavailable interfaces. */
public final class CompatibilityStatus {
  public static final String[] ROLES = {"android", "com.android.systemui", "com.sec.android.app.launcher"};
  public static final String[] TITLES = {"系统框架", "系统界面", "One UI 主屏幕"};
  private CompatibilityStatus() {}
  public static SharedPreferences reports(Context context) {
    return context.createDeviceProtectedStorageContext().getSharedPreferences(CompatibilityReportReceiver.PREFS, Context.MODE_PRIVATE);
  }
  public static int boot(Context context) {
    return android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
  }
  public static String reportState(int reportVersion, int currentVersion, int reportBoot, int currentBoot,
      long reportElapsed, long currentElapsed) {
    if (reportVersion < 0) return "missing";
    if (reportVersion != currentVersion) return "stale_version";
    if (reportBoot >= 0 && currentBoot >= 0) return reportBoot == currentBoot ? "current" : "previous_boot";
    return reportElapsed >= 0 && currentElapsed >= 0 && reportElapsed <= currentElapsed
        ? "current" : "previous_boot_or_unknown";
  }
  public static String state(Context context, SharedPreferences reports, String role) {
    return reportState(reports.getInt(role + ".version", -1), BuildConfig.VERSION_CODE,
        reports.getInt(role + ".boot", -1), boot(context), reports.getLong(role + ".elapsed", -1), SystemClock.elapsedRealtime());
  }
  public static String reason(String state) {
    switch (state) {
      case "current": return "本版本、本次启动的进程报告已收到";
      case "missing": return "未收到报告：请检查 LSPosed 是否启用本模块及此组件作用域，再完整重启；也可能是入口安装异常";
      case "stale_version": return "仍是旧版本报告：更新后需要完整重启加载新代码";
      case "previous_boot": return "报告来自上次开机：本次未收到该进程的加载证据";
      default: return "报告缺少本次开机标记：请完整重启后再试";
    }
  }
  public static String summary(Context context, SharedPreferences settings) {
    if (!io.github.s24u.popupscale.gestures.RomProfile.supported()) return "系统版本未纳入适配范围";
    SharedPreferences r = reports(context);
    boolean unified = settings == null || settings.getBoolean("system_scale_all", true);
    boolean interactions = settings == null || settings.getBoolean("system_features", true);
    String server = state(context, r, ROLES[0]);
    if (!server.equals("current"))
      return server.equals("stale_version")
          ? "需重启加载新版本（请软重启一次）"
          : "未收到系统框架检测（请软重启一次）";
    boolean scale = r.getStringSet("android.available", Collections.emptySet()).contains("scale");
    if (unified && !scale) return "系统缩放接口未匹配";
    if (!interactions) return unified ? "缩放接口已匹配" : "应用兼容模式";
    for (int i = 1; i < ROLES.length; i++)
      if (!state(context, r, ROLES[i]).equals("current")) return scale ? "缩放已匹配 · 交互待检测" : "交互待检测";
    for (String role : ROLES) if (r.getBoolean(role + ".partial", true))
      return scale ? "缩放可用 · 部分交互受限" : "部分交互接口未匹配";
    return "接口已匹配";
  }
  public static String explain(Context context) {
    SharedPreferences r = reports(context);
    StringBuilder out = new StringBuilder("接口匹配只代表检测成功，不等于所有场景均已实测。缺失项保留原生行为。\n");
    for (int i = 0; i < ROLES.length; i++) {
      String role = ROLES[i];
      out.append('\n').append(TITLES[i]).append("：").append(reason(state(context, r, role))).append('\n');
      out.append("报告版本：").append(r.getInt(role + ".version", -1)).append("；当前版本：").append(BuildConfig.VERSION_CODE).append('\n');
      out.append(r.getString(role, "尚无检测结果")).append('\n');
    }
    return out.toString();
  }
  public static boolean activationIssue(Context context, SharedPreferences settings) {
    if(settings==null||!settings.getBoolean("enabled",false))return false;
    if(!io.github.s24u.popupscale.gestures.RomProfile.supported())return true;
    SharedPreferences r=reports(context);
    if(settings.getBoolean("system_scale_all",true)&&(!"current".equals(state(context,r,"android"))||
        !r.getStringSet("android.available",Collections.emptySet()).contains("scale")))return true;
    if(settings.getBoolean("system_features",true))for(String role:ROLES)
      if(!"current".equals(state(context,r,role))||r.getBoolean(role+".partial",true))return true;
    return false;
  }
}
