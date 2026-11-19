package io.github.s24u.popupscale;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import dalvik.system.PathClassLoader;
import io.github.s24u.popupscale.gestures.CompatibilityReporter;
import io.github.s24u.popupscale.gestures.RomCapabilities;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads class metadata only: no SystemUI/Launcher instance or window transaction is created. */
final class CompatibilityDeviceChecks {
  static void run(Instrumentation runner) {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    StringBuilder report = new StringBuilder();
    try {
      Context app = runner.getTargetContext();
      try {
        Class<?> helper = Class.forName("de.robv.android.xposed.XposedHelpers");
        report.append("xposed-helper=").append(helper.getClassLoader()).append("\n");
        for (java.lang.reflect.Method m : helper.getDeclaredMethods())
          if (m.getName().equals("findFieldIfExists")
              || m.getName().equals("findMethodExactIfExists"))
            report.append(m.toString()).append("\n");
      } catch (Throwable absent) {
        report.append("xposed-helper=").append(absent.toString()).append("\n");
      }
      for (String role : new String[] {"com.android.systemui", "com.sec.android.app.launcher"}) {
        Context other =
            app.createPackageContext(
                role, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        RomCapabilities caps = new RomCapabilities(role, other.getClassLoader());
        report.append(role).append("\n").append(caps.summary()).append("\n");
        if (role.equals("com.android.systemui") && !caps.getSystemUi())
          throw new AssertionError(
              "Current development firmware lost SystemUI core: " + caps.summary());
        if (role.equals("com.android.systemui")
            && (!caps.getStash() || caps.getPositioners().isEmpty()))
          throw new AssertionError("Current development stash/follow ABI lost: " + caps.summary());
        if (role.equals("com.sec.android.app.launcher") && !caps.getLauncher())
          throw new AssertionError("Current development firmware lost Launcher: " + caps.summary());
      }
      String services = System.getenv("SYSTEMSERVERCLASSPATH");
      if (services == null || services.isEmpty()) throw new AssertionError("No server classpath");
      RomCapabilities server =
          new RomCapabilities(
              "android", new PathClassLoader(services, ClassLoader.getSystemClassLoader()));
      report.append("android\n").append(server.summary()).append("\n");
      if (!server.getServerScale() || !server.getServerLease() || !server.getServerEntry())
        throw new AssertionError(
            "Current development framework lost capabilities: " + server.summary());
      android.content.SharedPreferences prefs =
          app.createDeviceProtectedStorageContext()
              .getSharedPreferences(CompatibilityReportReceiver.PREFS, Context.MODE_PRIVATE);
      Map<String, ?> before = new LinkedHashMap<>(prefs.getAll());
      new CompatibilityReportReceiver()
          .onReceive(
              app,
              new Intent(CompatibilityReporter.ACTION)
                  .putExtra("role", "android")
                  .putExtra("version", BuildConfig.VERSION_CODE)
                  .putExtra("fingerprint", Build.FINGERPRINT)
                  .putExtra("summary", "spoof")
                  .putStringArrayListExtra(
                      "available",
                      new java.util.ArrayList<>(java.util.Arrays.asList("samsung", "scale")))
                  .putExtra("partial", false));
      if (!before.equals(prefs.getAll()))
        throw new AssertionError("Untrusted report changed stored diagnostics");
      result.putString(
          "summary",
          "PASS: current firmware class metadata matches; untrusted report rejected. No native"
              + " window methods invoked.");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("error", android.util.Log.getStackTraceString(error));
    }
    result.putString("capabilities", report.toString());
    runner.finish(code, result);
  }
}
