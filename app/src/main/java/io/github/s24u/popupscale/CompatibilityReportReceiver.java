package io.github.s24u.popupscale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import io.github.s24u.popupscale.gestures.CompatibilityReporter;

/** Stores interface detection only; this receiver cannot change feature settings. */
public final class CompatibilityReportReceiver extends BroadcastReceiver {
  public static final String PREFS = "compatibility_reports";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || Build.VERSION.SDK_INT < 34 || !CompatibilityReporter.ACTION.equals(intent.getAction()))
      return;
    try {
      String role = intent.getStringExtra("role");
      if (!"android".equals(role)
          && !"com.android.systemui".equals(role)
          && !"com.sec.android.app.launcher".equals(role)) return;
      int expected =
          "android".equals(role)
              ? Process.SYSTEM_UID
              : context.getPackageManager().getApplicationInfo(role, 0).uid;
      if (getSentFromUid() != expected
          || intent.getIntExtra("version", -1) != BuildConfig.VERSION_CODE
          || !Build.FINGERPRINT.equals(intent.getStringExtra("fingerprint"))) return;
      String summary = intent.getStringExtra("summary");
      if (summary == null || summary.length() > 12000) return;
      java.util.ArrayList<String> available = intent.getStringArrayListExtra("available");
      if (available == null || available.size() > 16) return;
      java.util.Set<String> known =
          new java.util.HashSet<>(
              java.util.Arrays.asList(
                  "samsung",
                  "scale",
                  "entry",
                  "entry-transaction",
                  "corner-symmetric",
                  "notification-route",
                  "frameless-outline",
                  "frameless-sync",
                  "frameless-root",
                  "lease",
                  "player-density",
                  "controls",
                  "follow",
                  "boundary",
                  "stash",
                  "gesture",
                  "preview"));
      if (!known.containsAll(available)) return;
      context
          .createDeviceProtectedStorageContext()
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putString(role, summary)
          .putInt(role + ".version", BuildConfig.VERSION_CODE)
          .putLong(role + ".when", System.currentTimeMillis())
          .putLong(role + ".elapsed", android.os.SystemClock.elapsedRealtime())
          .putInt(role + ".boot", CompatibilityStatus.boot(context))
          .putInt(role + ".xposedApi", intent.getIntExtra("xposedApi", -1))
          .putStringSet(role + ".available", new java.util.HashSet<>(available))
          .putBoolean(role + ".partial", intent.getBooleanExtra("partial", true))
          .apply();
      String request = intent.getStringExtra("request");
      String details = intent.getStringExtra("details");
      String failures = intent.getStringExtra("failures");
      if (request != null && request.matches("[a-f0-9-]{36}") && details != null
          && details.length() <= 60000 && failures != null && failures.length() <= 64000) {
        new org.json.JSONObject(details);
        new org.json.JSONArray(failures);
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(role + ".request", request).putString(role + ".details", details)
            .putString(role + ".failures", failures).apply();
      } else if (request == null || request.isEmpty()) {
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(role + ".request").remove(role + ".details").remove(role + ".failures").apply();
      }
    } catch (Exception ignored) {
      /* Missing packages/old senders never create a successful report. */
    }
  }
}
