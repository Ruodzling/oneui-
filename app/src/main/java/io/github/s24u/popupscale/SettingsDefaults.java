package io.github.s24u.popupscale;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared first-run values for all readers. Never replace saved choices. */
public final class SettingsDefaults {
  public static final String PACKAGES = "com.coolapk.market\ncom.tencent.mm";
  private static final Map<String, Object> VALUES;

  static {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", false);
    values.put("diagnostic", false);
    values.put("logging", true);
    values.put("demo", true);
    values.put("all_apps", true);
    values.put("system_scale_all", true);
    values.put("system_features", true);
    values.put("system_observe", false);
    values.put("system_handle", true);
    values.put("handle_close_to_back", true);
    values.put("system_frameless", true);
    values.put("notification_pull_popup", true);
    values.put("system_hang", true);
    values.put("system_live", true);
    values.put("mini_keep_playback", true);
    values.put("mini_keep_playback_all", false);
    values.put("mini_keep_playback_packages", io.github.s24u.popupscale.gestures.MiniPlaybackPolicy.DEFAULT_PACKAGES);
    values.put(AppScopePolicy.FAST_ACTION_POPUP, true);
    values.put("reference_dp", ScalePolicy.DEFAULT_REFERENCE_DP);
    values.put("density_preset", "medium");
    values.put("packages", PACKAGES);
    values.put("excluded_packages", "");
    VALUES = Collections.unmodifiableMap(values);
  }

  private SettingsDefaults() {}

  public static boolean flag(String key, boolean fallback) {
    Object value = VALUES.get(key);
    return value instanceof Boolean ? (Boolean) value : fallback;
  }

  public static boolean gesturesEnabled(boolean master, boolean gestures) {
    return master && gestures;
  }

  public static Map<String, Object> missing(Map<String, ?> saved) {
    Map<String, Object> missing = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : VALUES.entrySet()) {
      if (!saved.containsKey(entry.getKey())) missing.put(entry.getKey(), entry.getValue());
    }
    if (missing.containsKey("density_preset") && saved.get("reference_dp") instanceof Integer) {
      int width = (Integer) saved.get("reference_dp");
      missing.put(
          "density_preset",
          width == 600 ? "high" : width == 500 ? "medium" : width == 400 ? "low" : "custom");
    }
    return missing;
  }
}
