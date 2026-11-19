package io.github.s24u.popupscale;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings UI only: local release notes work even before the module is activated. */
final class UpdateLogDialog {
  private UpdateLogDialog() {}

  static void show(Context context) {
    SpannableStringBuilder body = new SpannableStringBuilder();
    body.append("当前版本 ").append(BuildConfig.VERSION_NAME).append("\n\n");
    try (InputStream input = context.getResources().openRawResource(R.raw.release_notes);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      JSONArray releases = new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8));
      for (int i = 0; i < releases.length(); i++) {
        JSONObject release = releases.getJSONObject(i);
        int start = body.length();
        body.append("v").append(release.getString("version"))
            .append("  ·  ").append(release.getString("date"));
        body.setSpan(new StyleSpan(Typeface.BOLD), start, body.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.append('\n');
        JSONArray changes = release.getJSONArray("changes");
        for (int j = 0; j < changes.length(); j++) body.append("• ").append(changes.getString(j)).append('\n');
        body.append('\n');
      }
      body.append("以上为近期版本的主要改动。不同系统按已匹配的接口启用相应功能。");
    } catch (Exception error) {
      // Release notes could not be parsed cleanly.
      body.append("暂时无法读取更新日志。");
    }
    new MaterialAlertDialogBuilder(context)
        .setTitle("更新日志")
        .setMessage(body)
        .setPositiveButton("知道了", null)
        .show();
  }
}
