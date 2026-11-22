package io.github.s24u.popupscale;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.LinkedHashSet;
import java.util.Set;

/** Material 3 settings; preserves existing keys and saves each explicit user change. */
public final class MainActivity extends MaterialSettingsActivity {
  private LinearLayout root;
  private FrameLayout content;
  private BottomNavigationView navigation;
  private LinearLayout header;
  private TextView titleView;
  private LinearLayout author;
  private boolean ready;
  private boolean firstOpenSession;
  private int page;
  private static final int[] NAV = {
    R.id.nav_overview, R.id.nav_apps, R.id.nav_gestures, R.id.nav_more
  };
  private static final String[] TITLES = {"", "应用缩放", "小窗手势", "更多设置"};

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    ready = HookStatus.isActive() && preferences != null;
    if (preferences != null
        && (preferences.getBoolean("diagnostic", false)
            || preferences.getBoolean("system_observe", false)
            || !preferences.getBoolean("logging", true)
            || !preferences.getBoolean("demo", true))) {
      preferences
          .edit()
          .putBoolean("diagnostic", false)
          .putBoolean("system_observe", false)
          .putBoolean("logging", true)
          .putBoolean("demo", true)
          .apply();
    }
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
    boolean night =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    WindowInsetsControllerCompat bars =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    bars.setAppearanceLightStatusBars(!night);
    bars.setAppearanceLightNavigationBars(!night);
    root = vertical();
    root.setBackgroundColor(surface());

    // Material 3 Large Top Header Area with generous whitespace
    header = vertical();
    header.setPadding(dp(22), dp(20), dp(22), dp(14));

    titleView = new TextView(this);
    titleView.setTextColor(ink());
    titleView.setTextSize(30);
    titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    titleView.setLineSpacing(dp(2), 1.05f);
    titleView.setLetterSpacing(-0.02f);
    header.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

    // Refined, interactive author badge with balanced proportions and subtle M3 styling
    author = vertical();
    author.setPadding(0, dp(10), 0, 0);

    LinearLayout badge = new LinearLayout(this);
    badge.setOrientation(LinearLayout.HORIZONTAL);
    badge.setGravity(Gravity.CENTER_VERTICAL);
    badge.setMinimumHeight(dp(30));
    badge.setPadding(dp(10), dp(4), dp(10), dp(4));

    GradientDrawable badgeShape = new GradientDrawable();
    badgeShape.setColor(
        com.google.android.material.color.MaterialColors.layer(surface(), primary(), 0.05f));
    badgeShape.setStroke(
        dp(1), com.google.android.material.color.MaterialColors.layer(surface(), muted(), 0.15f));
    badgeShape.setCornerRadius(dp(100));
    badge.setBackground(badgeShape);

    // Subtle breathing pulse dot (Coolapk Green identity) instead of awkward letter
    FrameLayout dotContainer = new FrameLayout(this);
    View halo = new View(this);
    GradientDrawable haloShape = new GradientDrawable();
    haloShape.setShape(GradientDrawable.OVAL);
    haloShape.setColor(Color.parseColor("#3311B067"));
    halo.setBackground(haloShape);
    dotContainer.addView(halo, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));

    View coreDot = new View(this);
    GradientDrawable coreShape = new GradientDrawable();
    coreShape.setShape(GradientDrawable.OVAL);
    coreShape.setColor(Color.parseColor("#11B067"));
    coreDot.setBackground(coreShape);
    dotContainer.addView(coreDot, new FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER));

    badge.addView(dotContainer, new LinearLayout.LayoutParams(dp(16), dp(16)));

    // Gentle breathing pulse animation
    android.animation.ObjectAnimator pulseX =
        android.animation.ObjectAnimator.ofFloat(halo, "scaleX", 0.6f, 1.25f);
    pulseX.setDuration(1500);
    pulseX.setRepeatCount(android.animation.ValueAnimator.INFINITE);
    pulseX.setRepeatMode(android.animation.ValueAnimator.REVERSE);
    pulseX.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    pulseX.start();

    android.animation.ObjectAnimator pulseY =
        android.animation.ObjectAnimator.ofFloat(halo, "scaleY", 0.6f, 1.25f);
    pulseY.setDuration(1500);
    pulseY.setRepeatCount(android.animation.ValueAnimator.INFINITE);
    pulseY.setRepeatMode(android.animation.ValueAnimator.REVERSE);
    pulseY.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    pulseY.start();

    android.animation.ObjectAnimator pulseAlpha =
        android.animation.ObjectAnimator.ofFloat(halo, "alpha", 0.25f, 0.85f);
    pulseAlpha.setDuration(1500);
    pulseAlpha.setRepeatCount(android.animation.ValueAnimator.INFINITE);
    pulseAlpha.setRepeatMode(android.animation.ValueAnimator.REVERSE);
    pulseAlpha.start();

    TextView signature = new TextView(this);
    signature.setText(R.string.app_author);
    signature.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
    signature.setTextColor(muted());
    signature.setIncludeFontPadding(false);
    LinearLayout.LayoutParams signatureParams = new LinearLayout.LayoutParams(-2, -2);
    signatureParams.leftMargin = dp(6);
    badge.addView(signature, signatureParams);

    TextView arrow = text("↗", 11, muted());
    arrow.setAlpha(0.6f);
    LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(-2, -2);
    arrowParams.leftMargin = dp(4);
    badge.addView(arrow, arrowParams);

    // Interactive feedback: tap to visit Coolapk profile directly
    TypedValue ripple = new TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    badge.setForeground(ContextCompat.getDrawable(this, ripple.resourceId));
    badge.setClickable(true);
    badge.setFocusable(true);
    badge.setOnClickListener(v -> openAuthorProfile());

    author.addView(badge, new LinearLayout.LayoutParams(-2, -2));
    TextView versionLink = text("版本 " + BuildConfig.VERSION_NAME + "  ·  更新日志  ›", 12, muted());
    versionLink.setId(R.id.version_entry);
    versionLink.setMinimumHeight(dp(48));
    versionLink.setGravity(Gravity.CENTER_VERTICAL);
    versionLink.setPadding(dp(2), 0, dp(10), 0);
    versionLink.setBackgroundResource(ripple.resourceId);
    versionLink.setFocusable(true);
    versionLink.setContentDescription("当前版本 " + BuildConfig.VERSION_NAME + "，点击查看更新日志");
    versionLink.setOnClickListener(v -> UpdateLogDialog.show(this));
    author.addView(versionLink, new LinearLayout.LayoutParams(-2, -2));
    header.addView(author, new LinearLayout.LayoutParams(-1, -2));
    root.addView(header, new LinearLayout.LayoutParams(-1, -2));

    content = new FrameLayout(this);
    root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    navigation = new BottomNavigationView(this);
    navigation.inflateMenu(R.menu.main_navigation);
    navigation.setBackgroundColor(container());
    navigation.setElevation(0);
    navigation.setLabelVisibilityMode(
        com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
    root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
    setContentView(root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets safe =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          boolean keyboard = insets.isVisible(WindowInsetsCompat.Type.ime());
          root.setPadding(
              safe.left,
              0,
              safe.right,
              keyboard ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom : 0);
          header.setPadding(dp(22), safe.top + dp(22), dp(22), page == 0 ? dp(14) : dp(18));
          navigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
          return insets;
        });
    ViewCompat.setOnApplyWindowInsetsListener(
        navigation,
        (v, insets) -> {
          v.setPadding(
              0,
              dp(4),
              0,
              insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + dp(4));
          return insets;
        });
    navigation.setOnItemSelectedListener(
        item -> {
          for (int i = 0; i < NAV.length; i++) if (item.getItemId() == NAV[i]) showPage(i);
          return true;
        });
    page = state == null ? getIntent().getIntExtra("tab", 0) : state.getInt("page", 0);
    if (page < 0 || page > 3) page = 0;
    SharedPreferences onboarding = getSharedPreferences("ui_onboarding", MODE_PRIVATE);
    if (state != null) {
      firstOpenSession = state.getBoolean("firstOpenSession", false);
    } else if (!onboarding.getBoolean("soft_reboot_prompted", false)) {
      firstOpenSession = true;
    }
    // Compatibility status is updated via CompatibilityReportReceiver.
    navigation.setSelectedItemId(NAV[page]);
    ViewCompat.requestApplyInsets(root);
    if (state == null) checkUpdateRebootNotice(onboarding);
  }

  private void checkUpdateRebootNotice(SharedPreferences onboarding) {
    try {
      android.content.pm.PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
      boolean current = true;
      SharedPreferences reports = CompatibilityStatus.reports(this);
      for (String role : CompatibilityStatus.ROLES)
        current &= "current".equals(CompatibilityStatus.state(this, reports, role));
      boolean remind = UpdateRebootPolicy.shouldRemind(
          onboarding.getInt("seen_module_version", 0), BuildConfig.VERSION_CODE,
          pkg.firstInstallTime, pkg.lastUpdateTime, current);
      onboarding.edit().putInt("seen_module_version", BuildConfig.VERSION_CODE).apply();
      if (remind) root.post(() -> {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
            .setTitle("更新完成，请软重启")
            .setMessage("已更新至 " + BuildConfig.VERSION_NAME
                + "。系统框架、系统界面和桌面可能仍在运行旧代码，请在 LSPosed 或 Root 管理器中执行一次软重启，再使用新版功能。\n\n此提示不会自动重启设备。")
            .setPositiveButton("知道了", null).show();
      });
    } catch (Exception e) {
      // Ignore malformed changelog notice.
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putInt("page", page);
    state.putBoolean("firstOpenSession", firstOpenSession);
    super.onSaveInstanceState(state);
  }

  private void showFirstOpenSoftRebootDialog() {
    new MaterialAlertDialogBuilder(this)
        .setTitle("首次启用请进行软重启")
        .setMessage(
            "若已在 LSPosed 中勾选本模块，首次打开显示“未收到系统框架检测”属于正常现象，因为系统框架尚未加载模块。\n\n"
                + "请在 LSPosed 或 Root 管理器中执行一次“软重启”即可生效。\n\n"
                + "提示：许多设备使用的是临时 Root，硬重启会导致 Root 掉失，因此请务必选择“软重启”。")
        .setPositiveButton("知道了", null)
        .show();
  }

  private static final String AUTHOR_URL = "https://www.coolapk.com/u/2970139";

  private void openAuthorProfile() {
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AUTHOR_URL));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    } catch (Throwable e) {
      showAboutAuthorDialog();
    }
  }

  private void showAboutAuthorDialog() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.app_name)
        .setMessage(
            getString(R.string.app_author)
                + "\n\n酷安主页：\n"
                + AUTHOR_URL
                + "\n\n版本 "
                + BuildConfig.VERSION_NAME
                + "\n\n致力于为三星 One UI 提供更自然流畅的小窗交互与内容缩放体验。\n设置已保存在本机。")
        .setPositiveButton(
            "访问酷安主页",
            (dialog, which) -> {
              try {
                Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AUTHOR_URL));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
              } catch (Throwable ignored) {
              }
            })
        .setNegativeButton("知道了", null)
        .show();
  }

  private void showPage(int index) {
    page = index;
    titleView.setText(index == 0 ? getString(R.string.app_name) : TITLES[index]);
    author.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
    header.setPadding(
        header.getPaddingLeft(),
        header.getPaddingTop(),
        header.getPaddingRight(),
        index == 0 ? dp(14) : dp(18));
    content.removeAllViews();
    if (index == 1) {
      content.addView(new ApplicationsPanel(this));
      return;
    }
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    LinearLayout column =
        new LinearLayout(this) {
          @Override
          protected void onMeasure(int w, int h) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(
                    Math.min(MeasureSpec.getSize(w), dp(680)), MeasureSpec.EXACTLY),
                h);
          }
        };
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(20), dp(10), dp(20), dp(28));
    scroll.addView(
        column, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    content.addView(scroll);
    if (index == 0) overview(column);
    else if (index == 2) gestures(column);
    else more(column);
  }

  boolean usable() {
    return preferences != null;
  }

  SharedPreferences prefs() {
    return preferences;
  }

  boolean flag(String key, boolean fallback) {
    boolean initial = SettingsDefaults.flag(key, fallback);
    return preferences == null ? initial : preferences.getBoolean(key, initial);
  }

  Set<String> names(String key, String fallback) {
    return ModuleSettings.parsePackages(
        preferences == null ? fallback : preferences.getString(key, fallback));
  }

  boolean commit(SharedPreferences.Editor editor) {
    if (editor == null) return false;
    editor.apply();
    try {
      editor.commit();
    } catch (Throwable ignored) {
    }
    // Settings saved.
    ModuleSettings.makeWorldReadable(this, ModuleSettings.PREFS);
    ModuleSettings.notifySettingsChanged(this);
    return true;
  }

  void message(String value) {
    Snackbar.make(root, value, Snackbar.LENGTH_LONG).setAnchorView(navigation).show();
  }

  boolean saveApp(String name, boolean on) {
    if (preferences == null) return false;
    try {
      Set<String> selected =
          new LinkedHashSet<>(names("packages", ModuleSettings.DEFAULT_PACKAGES));
      Set<String> excluded = new LinkedHashSet<>(names("excluded_packages", ""));
      if (flag("all_apps", false)) {
        if (on) excluded.remove(name);
        else excluded.add(name);
      } else if (on) {
        selected.add(name);
        excluded.remove(name);
      } else selected.remove(name);
      return commit(
          preferences
              .edit()
              .putString("packages", String.join("\n", selected))
              .putString("excluded_packages", String.join("\n", excluded)));
    } catch (RuntimeException bad) {
      message("应用列表保存失败，请重新打开应用后重试");
      return false;
    }
  }

  private void overview(LinearLayout column) {
    LinearLayout hero =
        section(column, color(com.google.android.material.R.attr.colorPrimaryContainer));
    hero.setPadding(dp(22), dp(20), dp(22), dp(22));
    int heroInk = color(com.google.android.material.R.attr.colorOnPrimaryContainer);
    String status =
        preferences == null
            ? "共享配置不可用"
            : !HookStatus.isActive()
                ? "模块未加载（需在 LSPosed 勾选并重启）"
                : !flag("enabled", false) ? "缩放已关闭" : compatibilityStatus();
    boolean serverMissing =
        preferences != null
            && HookStatus.isActive()
            && flag("enabled", false)
            && !"current"
                .equals(CompatibilityStatus.state(this, CompatibilityStatus.reports(this), "android"));
    boolean nightMode =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    int redWarn =
        nightMode
            ? Color.parseColor("#FF5252")
            : color(androidx.appcompat.R.attr.colorError);
    TextView statusView = text("●  " + status, 13, serverMissing ? redWarn : heroInk);
    statusView.setOnClickListener(v -> showCompatibility());
    statusView.setTypeface(
        Typeface.create("sans-serif-medium", serverMissing ? Typeface.BOLD : Typeface.NORMAL));
    if (serverMissing) {
      statusView.setPadding(dp(10), dp(5), dp(10), dp(5));
      GradientDrawable warnBg = new GradientDrawable();
      warnBg.setColor(
          com.google.android.material.color.MaterialColors.layer(surface(), redWarn, 0.12f));
      warnBg.setStroke(
          dp(1),
          com.google.android.material.color.MaterialColors.layer(surface(), redWarn, 0.35f));
      warnBg.setCornerRadius(dp(100));
      statusView.setBackground(warnBg);
      hero.addView(statusView, new LinearLayout.LayoutParams(-2, -2));
    } else {
      hero.addView(statusView);
    }
    LinearLayout line = new LinearLayout(this);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.setPadding(0, dp(14), 0, 0);
    LinearLayout labels = vertical();
    TextView heroTitle = text("小窗缩放", 26, heroInk);
    heroTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    labels.addView(heroTitle);
    TextView sub = text("缩放与窗口交互，开启即可使用", 14, heroInk);
    sub.setPadding(0, dp(6), 0, 0);
    labels.addView(sub);
    line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    MaterialSwitch master = new MaterialSwitch(this);
    master.setContentDescription("启用小窗缩放");
    master.setChecked(flag("enabled", false));
    master.setEnabled(preferences != null);
    boolean[] bindingMaster = {false};
    master.setOnCheckedChangeListener(
        (v, on) -> {
          if (bindingMaster[0]) return;
          commit(preferences.edit().putBoolean("enabled", on));
          if (on) {
            SharedPreferences onboarding = getSharedPreferences("ui_onboarding", MODE_PRIVATE);
            if (!onboarding.getBoolean("soft_reboot_prompted", false)) {
              firstOpenSession = true;
              onboarding.edit().putBoolean("soft_reboot_prompted", true).apply();
              if (!"current"
                  .equals(
                      CompatibilityStatus.state(this, CompatibilityStatus.reports(this), "android"))) {
                showFirstOpenSoftRebootDialog();
              }
            }
          }
          showPage(0);
        });
    line.addView(master, new LinearLayout.LayoutParams(-2, dp(56)));
    hero.addView(line);
    if (!HookStatus.isActive()) {
      action(column, R.drawable.ic_check, "完成模块配置", "查看一次性启用步骤", this::setup);
    }
    heading(column, "按你的习惯调整");
    LinearLayout quick = section(column);
    String summary = flag("all_apps", false) ? "全部普通应用" : "仅选择的应用";
    int excluded = names("excluded_packages", "").size();
    if (excluded > 0) summary += " · 排除 " + excluded + " 项";
    action(
        quick,
        R.drawable.ic_apps,
        "应用缩放管理",
        summary,
        () -> navigation.setSelectedItemId(R.id.nav_apps));
    divider(quick);
    action(
        quick,
        R.drawable.ic_gesture,
        "手势与迷你窗",
        flag("system_features", true) ? "控制条、挂起、堆叠与贴边收纳" : "设置你的窗口交互",
        () -> navigation.setSelectedItemId(R.id.nav_gestures));
    densityPresets(column);
    TextView note = text("设置自动保存；手势调整实时生效，缩放与无边框更改在重开目标应用后生效。", 12, muted());
    note.setPadding(dp(4), dp(4), dp(4), 0);
    column.addView(note);
  }

  private int referenceWidth() {
    int value =
        preferences == null
            ? ScalePolicy.DEFAULT_REFERENCE_DP
            : preferences.getInt("reference_dp", ScalePolicy.DEFAULT_REFERENCE_DP);
    return Math.max(280, Math.min(600, value));
  }

  private int densityPreset() {
    if (preferences != null && "custom".equals(preferences.getString("density_preset", "")))
      return 3;
    int value = referenceWidth();
    return value == 600 ? 0 : value == 500 ? 1 : value == 400 ? 2 : 3;
  }

  private void densityPresets(LinearLayout column) {
    heading(column, "内容密度");
    LinearLayout card = padded(column);
    int selected = densityPreset();
    String[] descriptions = {
      "高 · 显示更多内容", "中 · 内容与字号更均衡", "低 · 文字更大", "自定义 · " + referenceWidth() + " dp"
    };
    card.addView(text(descriptions[selected], 16, ink()));
    TextView hint = text("高档内容更多，低档文字更大。", 13, muted());
    hint.setPadding(0, dp(6), 0, dp(16));
    card.addView(hint);
    MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
    group.setSingleSelection(true);
    group.setSelectionRequired(true);
    String[] labels = {"高", "中", "低", "自定义"};
    String[] keys = {"high", "medium", "low"};
    int[] values = {600, 500, 400};
    int[] ids = new int[4];
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      MaterialButton button =
          new MaterialButton(
              this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
      ids[i] = View.generateViewId();
      button.setId(ids[i]);
      button.setText(labels[i]);
      button.setContentDescription("内容密度 " + labels[i]);
      button.setTextSize(14);
      button.setMinWidth(0);
      button.setMinimumWidth(0);
      button.setPadding(dp(8), 0, dp(8), 0);
      button.setMaxLines(2);
      button.setEnabled(preferences != null);
      group.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
      button.setOnClickListener(
          v -> {
            if (index == 3) {
              customDensity(() -> group.check(ids[densityPreset()]));
            } else if (commit(
                preferences
                    .edit()
                    .putInt("reference_dp", values[index])
                    .putString("density_preset", keys[index]))) {
              showPage(0);
            } else group.check(ids[selected]);
          });
    }
    group.check(ids[selected]);
    card.addView(group, new LinearLayout.LayoutParams(-1, dp(48)));
  }

  private void customDensity(Runnable dismissed) {
    final int[] pending = {280 + Math.round((referenceWidth() - 280) / 10f) * 10};
    LinearLayout body = vertical();
    body.setPadding(dp(24), dp(8), dp(24), dp(8));
    TextView explanation = text("调整目标内容宽度，数值越大，窗口里显示的内容越多。", 14, muted());
    body.addView(explanation);
    TextView amount = text(pending[0] + " dp", 28, primary());
    amount.setGravity(Gravity.CENTER);
    amount.setPadding(0, dp(16), 0, dp(8));
    body.addView(amount);
    Slider slider = new Slider(this);
    slider.setValueFrom(280);
    slider.setValueTo(600);
    slider.setStepSize(10);
    slider.setValue(pending[0]);
    slider.setContentDescription("自定义目标内容宽度");
    slider.setLabelFormatter(value -> Math.round(value) + " dp");
    slider.addOnChangeListener(
        (s, value, fromUser) -> {
          if (fromUser) {
            pending[0] = Math.round(value);
            amount.setText(pending[0] + " dp");
          }
        });
    body.addView(slider, new LinearLayout.LayoutParams(-1, dp(56)));
    LinearLayout ends = new LinearLayout(this);
    ends.addView(text("更大文字", 12, muted()), new LinearLayout.LayoutParams(0, -2, 1));
    ends.addView(text("更多内容", 12, muted()));
    body.addView(ends);
    ScrollView form = new ScrollView(this);
    form.addView(body);
    new MaterialAlertDialogBuilder(this)
        .setTitle("自定义内容密度")
        .setView(form)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "应用",
            (dialog, which) -> {
              if (commit(
                  preferences
                      .edit()
                      .putInt("reference_dp", pending[0])
                      .putString("density_preset", "custom"))) showPage(0);
            })
        .setOnDismissListener(dialog -> dismissed.run())
        .show();
  }

  private void chooseEngine() {
    if (preferences == null) {
      setup();
      return;
    }
    int[] choice = {flag("system_scale_all", false) ? 0 : 1};
    new MaterialAlertDialogBuilder(this)
        .setTitle("缩放方式")
        .setSingleChoiceItems(
            new String[] {"系统统一管理\n普通应用无需勾选，微信等兼容应用仍需作用域", "应用内兼容缩放\n需要对应的 LSPosed 应用作用域"},
            choice[0],
            (d, w) -> choice[0] = w)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "应用",
            (d, w) -> {
              if (commit(preferences.edit().putBoolean("system_scale_all", choice[0] == 0))) {
                showPage(page);
                message("已保存，请重新打开目标应用");
              }
            })
        .show();
  }

  private void gestures(LinearLayout column) {
    LinearLayout master = section(column);
    setting(master, "小窗手势", "随首页总开关生效 · 对全部普通应用解锁", "system_features", true, () -> showPage(page));
    if (flag("system_features", true)) {
      heading(column, "控制条与外观");
      LinearLayout controls = section(column);
      setting(controls, "窗口控制条", "拖动缩放 · 上甩关闭 · 下甩全屏 · 长按调透明度", "system_handle", true, () -> showPage(page));
      if (flag("system_handle", true)) {
        divider(controls);
        setting(controls, "上甩小窗退到后台", "开启后可从最近任务继续；关闭后上甩会直接移除任务", "handle_close_to_back", true);
      }
      divider(controls);
      setting(controls, "顶部无边框", "隐藏顶栏填充，保留上方蓝条拖动与菜单；重开小窗生效", "system_frameless", true);

      heading(column, "迷你窗");
      LinearLayout mini = section(column);
      setting(mini, "上滑挂起", "从底部连续上滑，移向右上角提示区挂起", "system_hang", true);
      divider(mini);
      setting(mini, "实时迷你窗", "保持内容运行，支持自由拖动、贴边收纳与多卡堆叠", "system_live", true, () -> showPage(page));
      if (flag("system_live", true)) {
        divider(mini);
        setting(mini, "迷你窗保持播放", "挂起到右上角后继续播放，适合视频和直播应用", "mini_keep_playback", true, () -> showPage(page));
        if (flag("mini_keep_playback", true)) {
          divider(mini);
          action(mini, R.drawable.ic_apps, "保持播放应用范围", miniPlaybackScopeSummary(), this::chooseMiniPlaybackScope);
        }
      }

      heading(column, "通知与快捷启动");
      LinearLayout entries = section(column);
      setting(entries, "详细通知下拉小窗", "从详细通知横幅任意位置下拉即可小窗打开", "notification_pull_popup", true);
      divider(entries);
      setting(entries, "快速启动器直接小窗",
          "通过 One Hand Operation + 快速启动器点开应用时直接以小窗打开",
          SideGestureHook.PREF_KEY_FAST_ACTION_POPUP, true);
      TextView ohoTip = text("需在 LSPosed 作用域中勾选 One Hand Operation +；长按图标仍可呼出分屏。", 12, muted());
      ohoTip.setPadding(dp(4), dp(4), dp(4), 0);
      column.addView(ohoTip);
    } else {
      TextView offTip = text("开启上方总开关后，可配置控制条、顶部无边框、迷你窗与通知手势。", 13, muted());
      offTip.setPadding(dp(4), dp(6), dp(4), dp(4));
      column.addView(offTip);
    }
    LinearLayout guide = section(column);
    action(guide, R.drawable.ic_gesture, "手势指南", "查看控制条、迷你窗堆叠与贴边操作说明", this::gestureGuide);
  }

  private String miniPlaybackScopeSummary() {
    if (flag("mini_keep_playback_all", false)) return "全部普通应用";
    Set<String> pkgs = names("mini_keep_playback_packages",
        io.github.s24u.popupscale.gestures.MiniPlaybackPolicy.DEFAULT_PACKAGES);
    return "仅已选应用 · " + AppPickerDialog.installedLabels(this, pkgs, "未选择应用");
  }

  private void chooseMiniPlaybackScope() {
    if (preferences == null) {
      setup();
      return;
    }
    int[] choice = {flag("mini_keep_playback_all", false) ? 0 : 1};
    Set<String> current = names("mini_keep_playback_packages",
        io.github.s24u.popupscale.gestures.MiniPlaybackPolicy.DEFAULT_PACKAGES);
    String selectedSummary = AppPickerDialog.installedLabels(this, current, "未选择应用");
    new MaterialAlertDialogBuilder(this)
        .setTitle("保持播放应用范围")
        .setSingleChoiceItems(
            new String[] {
              "全部普通应用\n对所有挂起到右上角的小窗保留播放状态",
              "仅已选视频应用\n当前：" + selectedSummary
            },
            choice[0],
            (d, w) -> choice[0] = w)
        .setNeutralButton("选择应用", (d, w) -> AppPickerDialog.show(
            this, current, "选择保持播放的视频应用", "清空选择", 0, false, picked -> {
              if (commit(preferences.edit()
                  .putBoolean("mini_keep_playback_all", false)
                  .putString("mini_keep_playback_packages", String.join("\n", picked)))) {
                showPage(page);
              }
            }))
        .setNegativeButton("取消", null)
        .setPositiveButton("应用", (d, w) -> {
          if (commit(preferences.edit().putBoolean("mini_keep_playback_all", choice[0] == 0))) {
            showPage(page);
          }
        })
        .show();
  }

  private void more(LinearLayout column) {
    heading(column, "外观");
    LinearLayout appearance = section(column);
    int mode = preferences == null ? 0 : preferences.getInt("ui_appearance", 0);
    action(
        appearance,
        R.drawable.ic_overview,
        "主题",
        new String[] {"跟随系统", "浅色", "深色"}[Math.max(0, Math.min(2, mode))],
        this::chooseAppearance);
    divider(appearance);
    setting(
        appearance, "动态配色", "使用系统壁纸的 Material You 配色", "ui_dynamic_color", true, this::recreate);
    heading(column, "关于");
    LinearLayout about = section(column);
    action(about, R.drawable.ic_overview, "版本与更新日志", "当前版本 " + BuildConfig.VERSION_NAME,
        () -> UpdateLogDialog.show(this));
    divider(about);
    action(about, R.drawable.ic_check, "启用与使用说明", "一次性系统配置及兼容模式", this::setup);
    divider(about);
    action(
        about,
        R.drawable.ic_overview,
        getString(R.string.app_name),
        getString(R.string.app_author),
        this::showAboutAuthorDialog);
  }

  private void setting(
      LinearLayout parent, String title, String detail, String key, boolean fallback) {
    setting(parent, title, detail, key, fallback, null);
  }

  private void setting(
      LinearLayout parent,
      String title,
      String detail,
      String key,
      boolean fallback,
      Runnable after) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(20), dp(16), dp(16), dp(16));
    row.setMinimumHeight(dp(80));
    LinearLayout labels = vertical();
    labels.addView(text(title, 16, ink()));
    TextView sub = text(detail, 13, muted());
    sub.setPadding(0, dp(4), dp(8), 0);
    labels.addView(sub);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    MaterialSwitch toggle = new MaterialSwitch(this);
    toggle.setContentDescription(title);
    toggle.setChecked(flag(key, fallback));
    toggle.setEnabled(preferences != null);
    boolean[] binding = {false};
    toggle.setOnCheckedChangeListener(
        (v, on) -> {
          if (binding[0]) return;
          commit(preferences.edit().putBoolean(key, on));
          if (after != null) after.run();
        });
    row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(48)));
    row.setOnClickListener(
        v -> {
          if (preferences != null) toggle.toggle();
        });
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private void chooseAppearance() {
    if (preferences == null) {
      setup();
      return;
    }
    int old = preferences.getInt("ui_appearance", 0);
    int[] selected = {old};
    new MaterialAlertDialogBuilder(this)
        .setTitle("主题")
        .setSingleChoiceItems(new String[] {"跟随系统", "浅色", "深色"}, old, (d, w) -> selected[0] = w)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "应用",
            (d, w) -> {
              if (commit(preferences.edit().putInt("ui_appearance", selected[0]))) recreate();
            })
        .show();
  }

  private void setup() {
    new MaterialAlertDialogBuilder(this)
        .setTitle("启用与使用")
        .setMessage(
            "1. 在 LSPosed 启用本模块，并保留系统框架、系统界面、One UI 主屏幕和权限控制器的作用域。首次配置后请执行软重启（临时 Root 用户请勿硬重启，以免掉 Root）。\n\n"
                + "2. 回到首页打开总开关即可使用：在「应用缩放」中按需选择或排除需要缩放的应用；微信、抖音、微博如需完整适配，请在 LSPosed 中保留对应应用作用域。无边框、控制条、挂起和迷你窗默认对全部普通应用生效。\n\n"
                + "3. 如需配合 One Hand Operation + 快速启动器点图标直接开小窗，请在 LSPosed 勾选 One Hand Operation + 并重新打开它。")
        .setPositiveButton("知道了", null)
        .show();
  }

  private void showCompatibility() {
    StringBuilder body = new StringBuilder();
    body.append(android.os.Build.MANUFACTURER)
        .append(" ")
        .append(android.os.Build.MODEL)
        .append("\nAndroid ")
        .append(android.os.Build.VERSION.RELEASE)
        .append(" · API ")
        .append(android.os.Build.VERSION.SDK_INT)
        .append("\n固件 ")
        .append(android.os.Build.VERSION.INCREMENTAL)
        .append("\n模块 ")
        .append(BuildConfig.VERSION_NAME)
        .append("\n\n");
    body.append(
        io.github.s24u.popupscale.gestures.RomProfile.supported()
            ? "检测范围：三星 One UI 7 / 8 / 9。已取消机型和固件号绑定。\n"
            : "此系统未纳入当前系统侧适配范围（三星 Android 15–17）。\n");
    body.append("应用入口注入：").append(HookStatus.isActive()).append("\n共享配置可读：")
        .append(preferences != null).append("\n\n").append(CompatibilityStatus.explain(this));
    new MaterialAlertDialogBuilder(this)
        .setTitle("设备兼容性")
        .setMessage(body.toString())
        .setPositiveButton("知道了", null)
        .setNeutralButton(
            "复制报告",
            (dialog, which) -> {
              android.content.ClipboardManager clipboard =
                  getSystemService(android.content.ClipboardManager.class);
              if (clipboard != null)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("设备兼容性", body));
            })
        .show();
  }

  private String compatibilityStatus() {
    return CompatibilityStatus.summary(this, preferences);
  }


  private void gestureGuide() {
    new MaterialAlertDialogBuilder(this)
        .setTitle("小窗手势指南")
        .setMessage(
            "窗口控制条\n"
                + "慢拖向内缩小，达到迷你尺寸后自动挂起；快速向内甩关闭（或退到后台）；快速向外甩全屏；长按控制条可调节窗口透明度。竖屏控制条在下方，横屏在朝向屏幕中央的一侧。\n\n"
                + "迷你窗与堆叠\n"
                + "点击正面卡片展开为标准小窗；拖动或甩动可移动位置；慢拖到左右边缘收纳为贴边图标；拖出上下边缘一半后松手关闭。多个迷你窗会自动堆叠，左右滑动切换卡片，展开其中一张时其余卡片会自动归位右上角。\n\n"
                + "标准小窗\n"
                + "拖顶部横条到左右边缘可贴边收纳，点击图标或向内拖出即可展开。停用模块前，请先恢复已挂起的小窗。")
        .setPositiveButton("知道了", null)
        .show();
  }

  private void editPackages() {
    if (preferences == null) {
      setup();
      return;
    }
    LinearLayout fields = vertical();
    fields.setPadding(dp(24), dp(8), dp(24), 0);
    TextInputEditText selected =
        packageInput(
            fields,
            "仅选应用（每行一个包名）",
            String.join("\n", names("packages", ModuleSettings.DEFAULT_PACKAGES)));
    TextInputEditText excluded =
        packageInput(fields, "排除应用（优先于全部启用）", String.join("\n", names("excluded_packages", "")));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(fields);
    androidx.appcompat.app.AlertDialog dialog =
        new MaterialAlertDialogBuilder(this)
            .setTitle("按包名编辑缩放应用")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.show();
    dialog
        .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              try {
                String a =
                    String.join("\n", ModuleSettings.parsePackages(selected.getText().toString()));
                String b =
                    String.join("\n", ModuleSettings.parsePackages(excluded.getText().toString()));
                if (commit(
                    preferences
                        .edit()
                        .putString("packages", a)
                        .putString("excluded_packages", b))) {
                  dialog.dismiss();
                  message("应用列表已保存");
                }
              } catch (RuntimeException bad) {
                excluded.setError(bad.getMessage());
              }
            });
  }

  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==NotificationPullTestMessage.PERMISSION_REQUEST&&results.length>0
        &&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)NotificationPullTestMessage.begin(this);
  }

  private TextInputEditText packageInput(LinearLayout parent, String title, String value) {
    TextInputLayout box = new TextInputLayout(this);
    box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    box.setHint(title);
    TextInputEditText field = new TextInputEditText(box.getContext());
    field.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    field.setMinLines(2);
    field.setMaxLines(5);
    field.setText(value);
    box.addView(field);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(12);
    parent.addView(box, p);
    return field;
  }
}
