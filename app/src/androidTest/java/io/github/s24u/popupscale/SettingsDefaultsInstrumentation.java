package io.github.s24u.popupscale;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real Android preference persistence checks using isolated files, never the user's popup_scale.
 */
public final class SettingsDefaultsInstrumentation extends Instrumentation {
  private boolean compatibility;
  private boolean ohoDispatcher;
  private boolean framelessPreview;
  private boolean framelessAbi;
  private boolean framelessResize;
  private boolean notificationMorph;
  private boolean backgroundHandle;
  private boolean notificationMorphGeometry;
  private boolean douyinDensity;
  private static final String FRESH = "first_run_0567_test_fresh";
  private static final String UPGRADE = "first_run_0567_test_upgrade";

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    ohoDispatcher=arguments!=null&&"true".equals(arguments.getString("ohoDispatcher"));
    framelessPreview=arguments!=null&&"true".equals(arguments.getString("framelessPreview"));
    framelessAbi=arguments!=null&&"true".equals(arguments.getString("framelessAbi"));
    framelessResize=arguments!=null&&"true".equals(arguments.getString("framelessResize"));
    notificationMorph=arguments!=null&&"true".equals(arguments.getString("notificationMorph"));
    backgroundHandle=arguments!=null&&"true".equals(arguments.getString("backgroundHandle"));
    notificationMorphGeometry=arguments!=null&&"true".equals(arguments.getString("notificationMorphGeometry"));
    douyinDensity=arguments!=null&&"true".equals(arguments.getString("douyinDensity"));
    compatibility = arguments != null && "true".equals(arguments.getString("compatibility"));
    start();
  }

  @Override
  public void onStart() {
    if(ohoDispatcher){OhoDispatcherDeviceChecks.run(this);return;}
    if(backgroundHandle){BackgroundHandleCheck.run(this);return;}
    if(notificationMorphGeometry){NotificationMorphGeometryDeviceChecks.run(this);return;}
    if(notificationMorph){NotificationMorphDeviceChecks.run(this);return;}
    if(framelessResize){FramelessPreviewDeviceChecks.resize(this);return;}
    if(douyinDensity){DouyinDensityDeviceChecks.run(this);return;}
    if(framelessAbi){FramelessPreviewDeviceChecks.abi(this);return;}
    if(framelessPreview){FramelessPreviewDeviceChecks.run(this);return;}
    if (compatibility) {
      CompatibilityDeviceChecks.run(this);
      return;
    }
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    Context context = getTargetContext();
    try {
      check(
          !FRESH.equals(ModuleSettings.PREFS) && !UPGRADE.equals(ModuleSettings.PREFS),
          "test files isolated");
      context.deleteSharedPreferences(FRESH);
      context.deleteSharedPreferences(UPGRADE);
      SharedPreferences fresh = context.getSharedPreferences(FRESH, Context.MODE_PRIVATE);
      check(fresh.edit().clear().commit(), "reset isolated fresh-test file");
      check(fresh.getAll().isEmpty(), "empty installation preferences");
      check(ModuleSettings.initializeDefaults(fresh), "commit initial profile");
      check(!fresh.getBoolean("enabled", true), "master starts off");
      check(
          !fresh.getBoolean("diagnostic", true) && !fresh.getBoolean("system_observe", true),
          "diagnostics off on disk");
      for (String key :
          new String[] {
            "all_apps",
            "system_scale_all",
            "system_features",
            "system_handle",
            "system_hang",
            "system_live"
          }) check(fresh.getBoolean(key, false), "fresh setting " + key);
      check(
          fresh.getInt("reference_dp", 0) == 500
              && "medium".equals(fresh.getString("density_preset", "")),
          "mid500 persisted");
      File file = preferenceFile(fresh);
      check(file.isFile() && file.length() > 0, "synchronous preferences XML exists");
      check(fresh.edit().putBoolean("enabled", true).commit(), "only master is changed");
      check(
          SettingsDefaults.gesturesEnabled(
              fresh.getBoolean("enabled", false), fresh.getBoolean("system_features", false)),
          "one toggle enables fresh interactions");
      Map<String, ?> saved = new LinkedHashMap<>(fresh.getAll());
      check(
          ModuleSettings.initializeDefaults(fresh) && saved.equals(fresh.getAll()),
          "second open keeps current state");
      SharedPreferences old = context.getSharedPreferences(UPGRADE, Context.MODE_PRIVATE);
      check(old.edit().clear().commit(), "reset isolated upgrade-test file");
      check(
          old.edit()
              .putBoolean("enabled", true)
              .putBoolean("diagnostic", true)
              .putBoolean("system_scale_all", false)
              .putBoolean("system_live", false)
              .putInt("reference_dp", 570)
              .putString("excluded_packages", "org.example.reader")
              .putInt("ui_appearance", 2)
              .commit(),
          "create independent upgraded-user settings");
      saved = new LinkedHashMap<>(old.getAll());
      check(ModuleSettings.initializeDefaults(old), "complete missing upgrade defaults");
      for (Map.Entry<String, ?> entry : saved.entrySet())
        check(
            entry.getValue().equals(old.getAll().get(entry.getKey())),
            "preserve upgrade " + entry.getKey());
      check("custom".equals(old.getString("density_preset", "")), "keep legacy custom width");
      result.putString(
          "summary",
          "PASS: real Android fresh defaults saved; one master toggle ready; second open and"
              + " upgraded custom settings preserved; user settings untouched.");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("error", android.util.Log.getStackTraceString(error));
    } finally {
      clean(context, FRESH);
      clean(context, UPGRADE);
    }
    finish(code, result);
  }

  private static void check(boolean pass, String message) {
    if (!pass) throw new AssertionError(message);
  }

  private static File preferenceFile(SharedPreferences preferences) throws Exception {
    // LSPosed can redirect the location of this test object's isolated preference file.
    for (Class<?> type = preferences.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField("mFile");
        field.setAccessible(true);
        Object value = field.get(preferences);
        if (value instanceof File) return (File) value;
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new AssertionError("test preference implementation has no file");
  }

  private static void clean(Context context, String name) {
    SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    try {
      File file = preferenceFile(preferences);
      if (file.getName().equals(name + ".xml")) file.delete();
    } catch (Exception ignored) {
    }
    context.deleteSharedPreferences(name);
  }
}
