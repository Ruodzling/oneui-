package io.github.s24u.popupscale;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Local launcher-app picker; cancel and search never modify the saved allow-list. */
final class AppSelectionDialog {
    private final Activity activity;
    private final Set<String> selected;
    private final List<Row> all = new ArrayList<>(), visible = new ArrayList<>();
    private final TextView status;
    private final EditText search;
    private final BaseAdapter adapter;
    private boolean dismissed;

    static void show(Activity activity, Set<String> current, Consumer<Set<String>> apply) {
        show(activity, "选择可启动应用", current, apply);
    }

    static void show(Activity activity, String title, Set<String> current, Consumer<Set<String>> apply) {
        new AppSelectionDialog(activity, title, current, apply);
    }

    private AppSelectionDialog(Activity owner, String title, Set<String> current, Consumer<Set<String>> apply) {
        activity = owner;
        selected = new LinkedHashSet<>(current);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        body.setPadding(padding, dp(8), padding, dp(4));
        search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("搜索应用名称或包名");
        body.addView(search, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(activity);
        status.setText("正在读取当前用户可从桌面启动的应用…");
        status.setPadding(0, dp(8), 0, dp(8));
        body.addView(status, new LinearLayout.LayoutParams(-1, -2));
        ListView list = new ListView(activity);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Row getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                CheckedTextView view = recycled instanceof CheckedTextView
                        ? (CheckedTextView) recycled
                        : (CheckedTextView) activity.getLayoutInflater().inflate(
                                android.R.layout.simple_list_item_multiple_choice, parent, false);
                Row row = getItem(position);
                view.setSingleLine(false);
                view.setTextSize(14);
                view.setPadding(dp(8), dp(10), dp(8), dp(10));
                view.setText(row.label + "\n" + row.name);
                view.setChecked(selected.contains(row.name));
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String name = visible.get(position).name;
            if (!selected.remove(name)) selected.add(name);
            refresh();
        });
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        // Bound the list to this Activity's available window, including popup mode.
        int available = activity.getResources().getDisplayMetrics().heightPixels;
        int bodyHeight = Math.max(dp(140), Math.min(dp(420), available * 3 / 5));
        body.setLayoutParams(new ViewGroup.LayoutParams(-1, bodyHeight));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用到列表", (ignored, which) ->
                        apply.accept(new LinkedHashSet<>(selected)))
                .create();
        dialog.setOnDismissListener(ignored -> dismissed = true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        dialog.show();
        // setView's custom panel uses the supplied height for the weighted list.
        body.getLayoutParams().height = bodyHeight;
        body.requestLayout();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        new Thread(this::load, "popupscale-app-list").start();
    }

    private void load() {
        List<Row> rows = new ArrayList<>();
        String error = null;
        try {
            PackageManager manager = activity.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : manager.queryIntentActivities(launcher, 0)) {
                if (info.activityInfo == null || info.activityInfo.applicationInfo == null) continue;
                String name = info.activityInfo.packageName;
                if (!AppScopePolicy.hasApplicationUid(info.activityInfo.applicationInfo.uid)
                        || !AppScopePolicy.isOrdinaryPackage(name) || !seen.add(name)) continue;
                CharSequence label = info.loadLabel(manager);
                rows.add(new Row(name, label == null ? name : label.toString()));
            }
            Collator collator = Collator.getInstance();
            rows.sort((a, b) -> {
                int compared = collator.compare(a.label, b.label);
                return compared == 0 ? a.name.compareTo(b.name) : compared;
            });
        } catch (RuntimeException failure) {
            error = "应用列表读取失败，可取消后手动填写包名。";
        }
        String message = error;
        activity.runOnUiThread(() -> {
            if (dismissed || activity.isFinishing() || activity.isDestroyed()) return;
            all.addAll(rows);
            refresh();
            if (message != null) status.setText(message);
        });
    }

    private void refresh() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (Row row : all)
            if (row.label.toLowerCase(Locale.ROOT).contains(query)
                    || row.name.toLowerCase(Locale.ROOT).contains(query)) visible.add(row);
        status.setText("已选 " + selected.size() + " 项 · 当前显示 " + visible.size()
                + " 项\n未列出的原有包名会保留；应用到列表后仍需保存设置。");
        adapter.notifyDataSetChanged();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    private static final class Row {
        final String name, label;
        Row(String name, String label) { this.name = name; this.label = label; }
    }
}
