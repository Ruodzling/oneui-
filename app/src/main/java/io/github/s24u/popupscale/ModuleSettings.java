package io.github.s24u.popupscale;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ModuleSettings {
  public static final String PACKAGE = AppScopePolicy.MODULE_PACKAGE;
  public static final String PREFS = "popup_scale";
  public static final String DEFAULT_PACKAGES = SettingsDefaults.PACKAGES;
  public static final String ACTION_SETTINGS_CHANGED = "io.github.s24u.popupscale.ACTION_SETTINGS_CHANGED";

  private ModuleSettings() {}

  @SuppressWarnings("deprecation")
  public static synchronized SharedPreferences openShared(Context context) {
    if (context == null) return null;
    SharedPreferences preferences = null;
    try {
      preferences = context.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
    } catch (SecurityException ignored) {
    } catch (Throwable ignored) {
    }
    if (preferences != null) {
      migratePrivateIfNeeded(context, preferences);
    } else {
      try {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      } catch (Throwable ignored) {
      }
      makeWorldReadable(context, PREFS);
    }
    if (preferences != null) {
      initializeDefaults(preferences);
    }
    return preferences;
  }

  private static void migratePrivateIfNeeded(Context context, SharedPreferences target) {
    if (target.contains("__migrated_world_prefs")) return;
    SharedPreferences.Editor editor = target.edit();
    try {
      SharedPreferences legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      if (legacy != null && legacy != target) {
        Map<String, ?> existing = legacy.getAll();
        if (existing != null) {
          for (Map.Entry<String, ?> entry : existing.entrySet()) {
            String key = entry.getKey();
            if (target.contains(key)) continue;
            Object val = entry.getValue();
            if (val instanceof Boolean) editor.putBoolean(key, (Boolean) val);
            else if (val instanceof Integer) editor.putInt(key, (Integer) val);
            else if (val instanceof Long) editor.putLong(key, (Long) val);
            else if (val instanceof Float) editor.putFloat(key, (Float) val);
            else if (val instanceof String) editor.putString(key, (String) val);
          }
        }
      }
    } catch (Throwable ignored) {
    }
    editor.putBoolean("__migrated_world_prefs", true).commit();
  }

  public static void makeWorldReadable(Context context, String name) {
    if (context == null) return;
    try {
      File dataDir = android.os.Build.VERSION.SDK_INT >= 24
          ? context.getDataDir() : new File(context.getApplicationInfo().dataDir);
      File prefsDir = new File(dataDir, "shared_prefs");
      File prefsFile = new File(prefsDir, name + ".xml");
      dataDir.setExecutable(true, false);
      dataDir.setReadable(true, false);
      prefsDir.setExecutable(true, false);
      prefsDir.setReadable(true, false);
      if (prefsFile.exists()) {
        prefsFile.setReadable(true, false);
      }
    } catch (Throwable ignored) {
    }
  }

  public static void notifySettingsChanged(Context context) {
    if (context == null) return;
    try {
      SharedPreferences preferences = openShared(context);
      if (preferences == null) return;
      Intent intent = new Intent(ACTION_SETTINGS_CHANGED);
      Bundle b = new Bundle();
      for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
        Object val = entry.getValue();
        if (val instanceof Boolean) b.putBoolean(entry.getKey(), (Boolean) val);
        else if (val instanceof Integer) b.putInt(entry.getKey(), (Integer) val);
        else if (val instanceof Long) b.putLong(entry.getKey(), (Long) val);
        else if (val instanceof Float) b.putFloat(entry.getKey(), (Float) val);
        else if (val instanceof String) b.putString(entry.getKey(), (String) val);
      }
      intent.putExtra("settings", b);
      context.sendBroadcast(intent);
    } catch (Throwable ignored) {
    }
  }

  public static Map<String, Object> queryProvider(Context context) {
    if (context == null) return Collections.emptyMap();
    try {
      Uri uri = Uri.parse("content://" + PACKAGE + ".provider");
      Bundle res = context.getContentResolver().call(uri, "getAll", null, null);
      if (res == null) return Collections.emptyMap();
      Map<String, Object> map = new HashMap<>();
      for (String key : res.keySet()) {
        map.put(key, res.get(key));
      }
      return map;
    } catch (Throwable ignored) {
      return Collections.emptyMap();
    }
  }

  static boolean initializeDefaults(SharedPreferences preferences) {
    Map<String, Object> missing = SettingsDefaults.missing(preferences.getAll());
    if (missing.isEmpty()) return true;
    SharedPreferences.Editor editor = preferences.edit();
    for (Map.Entry<String, Object> entry : missing.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
      else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
      else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
    }
    // Publish the complete first-run profile to XSharedPreferences in one write.
    return editor.commit();
  }

  public static Set<String> parsePackages(String input) {
    Set<String> packages = new LinkedHashSet<>();
    for (String part : input.split("[\\s,;，；]+")) {
      String value = part.trim();
      if (!value.isEmpty()) {
        if (!value.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
          throw new IllegalArgumentException("包名格式不正确：" + value);
        if (isBlocked(value)) throw new IllegalArgumentException("不对这个系统组件启用通用缩放：" + value);
        packages.add(value);
      }
    }
    return packages;
  }

  public static boolean isBlocked(String name) {
    return AppScopePolicy.isBlocked(name);
  }
}
