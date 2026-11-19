package io.github.s24u.popupscale;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.Map;

/**
 * High-reliability IPC bridge for cross-process configuration reading.
 * Bypasses SELinux and DAC filesystem restrictions across UIDs on Android 10-17+.
 */
public final class SettingsProvider extends ContentProvider {
  public static final String AUTHORITY = ModuleSettings.PACKAGE + ".provider";
  public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
  public static final String METHOD_GET_ALL = "getAll";
  public static final String METHOD_GET = "get";

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    Context context = getContext();
    if (context == null) return null;
    SharedPreferences prefs = ModuleSettings.openShared(context);
    if (prefs == null) return null;

    Bundle bundle = new Bundle();
    if (METHOD_GET_ALL.equals(method)) {
      Map<String, ?> all = prefs.getAll();
      for (Map.Entry<String, ?> entry : all.entrySet()) {
        putVal(bundle, entry.getKey(), entry.getValue());
      }
      return bundle;
    } else if (METHOD_GET.equals(method) && arg != null) {
      Object val = prefs.getAll().get(arg);
      if (val != null) {
        putVal(bundle, arg, val);
      }
      return bundle;
    }
    return bundle;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    Context context = getContext();
    if (context == null) return null;
    SharedPreferences prefs = ModuleSettings.openShared(context);
    if (prefs == null) return null;
    MatrixCursor cursor = new MatrixCursor(new String[]{"key", "type", "value"});
    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
      Object val = entry.getValue();
      if (val != null) {
        cursor.addRow(new Object[]{entry.getKey(), val.getClass().getSimpleName(), String.valueOf(val)});
      }
    }
    return cursor;
  }

  private static void putVal(Bundle bundle, String key, Object val) {
    if (val instanceof Boolean) bundle.putBoolean(key, (Boolean) val);
    else if (val instanceof Integer) bundle.putInt(key, (Integer) val);
    else if (val instanceof Long) bundle.putLong(key, (Long) val);
    else if (val instanceof Float) bundle.putFloat(key, (Float) val);
    else if (val instanceof String) bundle.putString(key, (String) val);
  }

  @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".settings"; }
  @Override public Uri insert(Uri uri, ContentValues values) { return null; }
  @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
