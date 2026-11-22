package io.github.s24u.popupscale;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One list represents the effective enabled state in both all/selected modes. */
final class ApplicationsPanel extends LinearLayout {
  private final MainActivity activity;
  private final List<Row> all = new ArrayList<>(), visible = new ArrayList<>();
  private final TextInputEditText search;
  private final TextView summary, hint;
  private final BaseAdapter adapter;
  private Set<String> selected = new HashSet<>(), excluded = new HashSet<>();
  private boolean allEnabled, disposed, loading = true, valid = true;
  private int filter;

  private record Row(String name, String label, Drawable icon) {}

  private record Holder(ImageView icon, TextView title, TextView detail, MaterialSwitch toggle) {}

  ApplicationsPanel(MainActivity owner) {
    super(owner);
    activity = owner;
    setOrientation(VERTICAL);
    setPadding(dp(20), dp(8), dp(20), 0);
    MaterialButtonToggleGroup modes = new MaterialButtonToggleGroup(owner);
    modes.setSingleSelection(true);
    modes.setSelectionRequired(true);
    MaterialButton every =
        new MaterialButton(
            owner, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    MaterialButton chosen =
        new MaterialButton(
            owner, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    every.setText("全部普通应用");
    chosen.setText("仅选应用");
    every.setId(View.generateViewId());
    chosen.setId(View.generateViewId());
    every.setEnabled(owner.usable());
    chosen.setEnabled(owner.usable());
    modes.addView(every, new LinearLayout.LayoutParams(0, dp(48), 1));
    modes.addView(chosen, new LinearLayout.LayoutParams(0, dp(48), 1));
    addView(modes, new LinearLayout.LayoutParams(-1, dp(48)));
    allEnabled = owner.flag("all_apps", false);
    modes.check(allEnabled ? every.getId() : chosen.getId());
    hint = owner.text("", 13, owner.muted());
    hint.setPadding(dp(4), dp(10), dp(4), dp(16));
    addView(hint);
    boolean[] binding = {false};
    modes.addOnButtonCheckedListener(
        (group, id, checked) -> {
          if (!checked || binding[0]) return;
          boolean next = id == every.getId();
          if (!owner.commit(owner.prefs().edit().putBoolean("all_apps", next))) {
            binding[0] = true;
            group.check(allEnabled ? every.getId() : chosen.getId());
            binding[0] = false;
          }
          refresh();
        });
    TextInputLayout field = new TextInputLayout(owner);
    field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    field.setBoxCornerRadii(dp(24), dp(24), dp(24), dp(24));
    field.setHint("搜索应用");
    field.setStartIconDrawable(R.drawable.ic_search);
    field.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
    search = new TextInputEditText(field.getContext());
    search.setSingleLine(true);
    search.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_FILTER
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.setOnEditorActionListener(
        (view, action, event) -> {
          if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
          android.view.inputmethod.InputMethodManager keyboard =
              owner.getSystemService(android.view.inputmethod.InputMethodManager.class);
          keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
          search.clearFocus();
          return true;
        });
    search.setTextSize(15);
    field.addView(search);
    addView(field, new LinearLayout.LayoutParams(-1, -2));
    ChipGroup filters = new ChipGroup(owner);
    filters.setSingleSelection(true);
    filters.setSelectionRequired(true);
    filters.setChipSpacingHorizontal(dp(8));
    String[] labels = {"全部", "已启用", "未启用"};
    for (int i = 0; i < labels.length; i++) {
      int index = i;
      Chip chip = new Chip(owner);
      chip.setText(labels[i]);
      chip.setCheckable(true);
      chip.setCheckedIconVisible(false);
      chip.setId(View.generateViewId());
      filters.addView(chip);
      chip.setOnCheckedChangeListener(
          (button, on) -> {
            if (on) {
              filter = index;
              refresh();
            }
          });
      if (i == 0) chip.setChecked(true);
    }
    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
    fp.topMargin = dp(8);
    addView(filters, fp);
    summary = owner.text("正在加载应用…", 12, owner.muted());
    summary.setPadding(dp(4), dp(4), 0, dp(10));
    addView(summary);
    MaterialCardView card = new MaterialCardView(owner);
    card.setRadius(dp(24));
    card.setCardBackgroundColor(owner.container());
    card.setCardElevation(0);
    card.setStrokeWidth(0);
    ListView list = new ListView(owner);
    list.setDivider(null);
    list.setClipToPadding(false);
    list.setPadding(0, dp(4), 0, dp(12));
    card.addView(list, new LayoutParams(-1, -1));
    adapter =
        new BaseAdapter() {
          @Override
          public int getCount() {
            return visible.size();
          }

          @Override
          public Row getItem(int p) {
            return visible.get(p);
          }

          @Override
          public long getItemId(int p) {
            return getItem(p).name.hashCode();
          }

          @Override
          public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            Holder h;
            if (recycled instanceof LinearLayout) {
              row = (LinearLayout) recycled;
              h = (Holder) row.getTag();
            } else {
              row = new LinearLayout(owner);
              row.setGravity(Gravity.CENTER_VERTICAL);
              row.setPadding(dp(16), dp(12), dp(12), dp(12));
              row.setMinimumHeight(dp(76));
              ImageView icon = new ImageView(owner);
              icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
              row.addView(icon, new LayoutParams(dp(40), dp(40)));
              LinearLayout words = owner.vertical();
              TextView title = owner.text("", 16, owner.ink());
              title.setMaxLines(2);
              TextView detail = owner.text("", 12, owner.muted());
              detail.setMaxLines(1);
              detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
              words.addView(title);
              words.addView(detail);
              LayoutParams wp = new LayoutParams(0, -2, 1);
              wp.leftMargin = dp(14);
              wp.rightMargin = dp(8);
              row.addView(words, wp);
              MaterialSwitch toggle = new MaterialSwitch(owner);
              row.addView(toggle, new LayoutParams(-2, dp(48)));
              h = new Holder(icon, title, detail, toggle);
              row.setTag(h);
            }
            Row app = getItem(position);
            h.icon.setImageDrawable(app.icon);
            h.title.setText(app.label);
            h.detail.setText(app.name);
            h.toggle.setOnCheckedChangeListener(null);
            h.toggle.setChecked(enabled(app.name));
            h.toggle.setEnabled(owner.usable() && valid);
            h.toggle.setContentDescription("缩放 " + app.label);
            h.toggle.setOnCheckedChangeListener(
                (button, on) -> {
                  owner.saveApp(app.name, on);
                  refresh();
                });
            Holder current = h;
            row.setOnClickListener(
                v -> {
                  if (current.toggle.isEnabled()) current.toggle.toggle();
                });
            return row;
          }
        };
    list.setAdapter(adapter);
    addView(card, new LayoutParams(-1, 0, 1));
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            refresh();
          }

          public void afterTextChanged(Editable e) {}
        });
    refresh();
    new Thread(this::load, "popupscale-material-app-list").start();
  }

  private int dp(int value) {
    return activity.dp(value);
  }

  private boolean enabled(String name) {
    return (allEnabled || selected.contains(name)) && !excluded.contains(name);
  }

  private void refresh() {
    if (search == null || hint == null) return;
    allEnabled = activity.flag("all_apps", false);
    try {
      selected = activity.names("packages", ModuleSettings.DEFAULT_PACKAGES);
      excluded = activity.names("excluded_packages", "");
      valid = true;
    } catch (RuntimeException bad) {
      valid = false;
    }
    hint.setText(allEnabled ? "默认缩放全部应用，关闭某一项即可排除（不影响无边框、控制条等小窗交互）。" : "仅缩放开启的应用，新安装应用默认关闭（不影响无边框、控制条等小窗交互）。");
    String query =
        search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
    visible.clear();
    int count = 0;
    for (Row row : all) {
      boolean on = enabled(row.name);
      if (on) count++;
      if ((filter == 1 && !on) || (filter == 2 && on)) continue;
      if (query.isEmpty()
          || row.label.toLowerCase(Locale.ROOT).contains(query)
          || row.name.toLowerCase(Locale.ROOT).contains(query)) visible.add(row);
    }
    if (summary != null)
      summary.setText(
          !valid
              ? "应用配置读取异常，请重新打开应用后重试"
              : loading
                  ? "正在加载应用…"
                  : visible.isEmpty()
                      ? "没有找到匹配的应用"
                      : "已启用 " + count + " / " + all.size() + " 个应用 · 更改自动保存");
    if (adapter != null) adapter.notifyDataSetChanged();
  }

  private void load() {
    List<Row> rows = new ArrayList<>();
    String error = null;
    try {
      PackageManager pm = activity.getPackageManager();
      Set<String> seen = new HashSet<>();
      for (ResolveInfo info :
          pm.queryIntentActivities(
              new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
        if (info.activityInfo == null || info.activityInfo.applicationInfo == null) continue;
        String name = info.activityInfo.packageName;
        if (!AppScopePolicy.hasApplicationUid(info.activityInfo.applicationInfo.uid)
            || !AppScopePolicy.isOrdinaryPackage(name)
            || !seen.add(name)) continue;
        rows.add(new Row(name, info.loadLabel(pm).toString(), info.loadIcon(pm)));
      }
      Collator collator = Collator.getInstance();
      rows.sort(
          (a, b) -> {
            int c = collator.compare(a.label, b.label);
            return c == 0 ? a.name.compareTo(b.name) : c;
          });
    } catch (RuntimeException unavailable) {
      error = "应用列表加载失败，请稍后重新打开此页";
    }
    String message = error;
    activity.runOnUiThread(
        () -> {
          if (disposed || activity.isFinishing() || activity.isDestroyed()) return;
          all.addAll(rows);
          loading = false;
          refresh();
          if (message != null) summary.setText(message);
        });
  }

  @Override
  protected void onDetachedFromWindow() {
    disposed = true;
    super.onDetachedFromWindow();
  }
}
