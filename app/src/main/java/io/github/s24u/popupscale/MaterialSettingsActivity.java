package io.github.s24u.popupscale;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

/** Settings-only Material primitives; no code is used by injected host windows. */
public abstract class MaterialSettingsActivity extends AppCompatActivity {
  protected SharedPreferences preferences;

  @Override
  protected void onCreate(Bundle state) {
    try {
      preferences = ModuleSettings.openShared(this);
    } catch (Throwable e) {
      // Fallback to component launch below when action dispatch fails.
      try {
        preferences = getSharedPreferences(ModuleSettings.PREFS, MODE_PRIVATE);
      } catch (Throwable ignored) {
        preferences = null;
      }
    }
    int mode = preferences == null ? 0 : preferences.getInt("ui_appearance", 0);
    getDelegate()
        .setLocalNightMode(
            mode == 1
                ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == 2
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    if (preferences == null || preferences.getBoolean("ui_dynamic_color", true))
      DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(state);
  }

  int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  int color(int attr) {
    return MaterialColors.getColor(this, attr, getClass().getSimpleName());
  }

  int surface() {
    return color(com.google.android.material.R.attr.colorSurface);
  }

  int ink() {
    return color(com.google.android.material.R.attr.colorOnSurface);
  }

  int muted() {
    return color(com.google.android.material.R.attr.colorOnSurfaceVariant);
  }

  int primary() {
    return color(androidx.appcompat.R.attr.colorPrimary);
  }

  int container() {
    return MaterialColors.layer(surface(), primary(), 0.045f);
  }

  TextView text(String value, int size, int tint) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(tint);
    v.setLineSpacing(dp(2), 1);
    return v;
  }

  LinearLayout vertical() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  void heading(LinearLayout parent, String value) {
    TextView v = text(value, 14, primary());
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    v.setPadding(dp(4), dp(16), 0, dp(10));
    parent.addView(v);
  }

  LinearLayout section(LinearLayout parent) {
    return section(parent, container());
  }

  LinearLayout section(LinearLayout parent, int background) {
    MaterialCardView card = new MaterialCardView(this);
    card.setRadius(dp(24));
    card.setCardElevation(0);
    card.setStrokeWidth(0);
    card.setCardBackgroundColor(background);
    LinearLayout inside = vertical();
    card.addView(inside, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(12);
    parent.addView(card, p);
    return inside;
  }

  LinearLayout padded(LinearLayout parent) {
    LinearLayout v = section(parent);
    v.setPadding(dp(20), dp(18), dp(20), dp(20));
    return v;
  }

  void divider(LinearLayout parent) {
    View v = new View(this);
    v.setBackgroundColor(MaterialColors.layer(surface(), muted(), .12f));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
    p.leftMargin = p.rightMargin = dp(20);
    parent.addView(v, p);
  }

  LinearLayout action(LinearLayout parent, int icon, String title, String subtitle, Runnable run) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(78));
    row.setPadding(dp(18), dp(14), dp(16), dp(14));
    if (icon != 0) {
      ImageView image = new ImageView(this);
      image.setImageDrawable(ContextCompat.getDrawable(this, icon));
      image.setColorFilter(primary());
      row.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));
    }
    LinearLayout labels = vertical();
    labels.addView(text(title, 16, ink()));
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView hint = text(subtitle, 13, muted());
      hint.setPadding(0, dp(4), 0, 0);
      labels.addView(hint);
    }
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = icon == 0 ? 0 : dp(16);
    p.rightMargin = dp(10);
    row.addView(labels, p);
    ImageView arrow = new ImageView(this);
    arrow.setImageResource(R.drawable.ic_chevron);
    arrow.setColorFilter(muted());
    arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
    android.util.TypedValue ripple = new android.util.TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    row.setBackgroundResource(ripple.resourceId);
    row.setOnClickListener(v -> run.run());
    row.setFocusable(true);
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return row;
  }
}
