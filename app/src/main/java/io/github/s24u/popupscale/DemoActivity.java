package io.github.s24u.popupscale;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.PopupWindow;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Standard dp/sp controls to distinguish density scaling from clipping and relayout. */
public final class DemoActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView metrics;
    private EditText input;
    private int taps;
    private int dialogTaps;
    private int popupTaps;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            Configuration config = getResources().getConfiguration();
            metrics.setText("资源密度 " + config.densityDpi + " dpi  |  内容 "
                    + config.screenWidthDp + " × " + config.screenHeightDp + " dp\n"
                    + "fontScale " + config.fontScale + "  |  模块 "
                    + (HookStatus.isActive() ? "已注入" : "未注入"));
            handler.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        taps = state == null ? 0 : state.getInt("taps", 0);
        dialogTaps = state == null ? 0 : state.getInt("dialog_taps", 0);
        popupTaps = state == null ? 0 : state.getInt("popup_taps", 0);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        scroll.addView(column);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        text(column, "小窗同步缩放测试", 24);
        metrics = text(column, "", 12);
        text(column, "保持宽高比缩小窗口：标题换行应基本一致，文字与按钮应一起变小。", 16);
        Button tap = new Button(this);
        tap.setText("点击位置测试 · 已点击 " + taps + " 次");
        tap.setOnClickListener(view -> tap.setText("点击位置测试 · 已点击 " + (++taps) + " 次"));
        column.addView(tap);
        Button dialog = new Button(this);
        dialog.setText("普通弹窗测试 · 已点击 " + dialogTaps + " 次");
        dialog.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("普通弹窗缩放测试")
                .setMessage("调整小窗大小时，标题、说明和按钮应一起缩放。弹窗高度应随内容变化，底部按钮应能点击。这个测试不会改变手机权限。")
                .setNegativeButton("取消", null)
                .setPositiveButton("测试点击", (popup, which) -> dialog.setText(
                        "普通弹窗测试 · 已点击 " + (++dialogTaps) + " 次"))
                .show());
        column.addView(dialog);
        Button menu = new Button(this);
        menu.setText("独立菜单测试 · 已点击 " + popupTaps + " 次");
        menu.setOnClickListener(view -> showPopup(menu, false));
        column.addView(menu);
        Button anchored = new Button(this);
        anchored.setText("锚定菜单测试");
        anchored.setOnClickListener(view -> showPopup(anchored, true));
        column.addView(anchored);
        input = new EditText(this);
        input.setId(R.id.demo_input);
        if (state != null) {
            input.setText(state.getString("input_text", ""));
            int selection = Math.max(0, Math.min(input.length(), state.getInt("input_selection", input.length())));
            input.setSelection(selection);
        }
        input.setHint("在这里测试键盘和文字选择");
        column.addView(input);
        for (int index = 1; index <= 20; index++) {
            text(column, String.format(Locale.ROOT, "%02d  同一段文字用于比较窗口缩小前后的排版与可见内容。"
                    + "ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789。", index), 16);
        }
    }
    @Override protected void onResume() { super.onResume(); handler.post(update); }
    @Override protected void onPause() { handler.removeCallbacks(update); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("taps", taps);
        state.putInt("dialog_taps", dialogTaps);
        state.putInt("popup_taps", popupTaps);
        state.putString("input_text", input.getText().toString());
        state.putInt("input_selection", input.getSelectionStart());
        super.onSaveInstanceState(state);
    }
    private void showPopup(Button anchor, boolean dropdown) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(12 * density);
        body.setPadding(padding, padding, padding, padding);
        text(body, "独立菜单缩放测试", 18);
        text(body, "这里只测试显示和点击，不执行外部操作。", 14);
        Button click = new Button(this);
        click.setText("测试菜单点击 · " + popupTaps + " 次");
        body.addView(click);
        PopupWindow popup = new PopupWindow(body, Math.round(180 * density),
                Math.round(200 * density), true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(8 * density);
        popup.setBackgroundDrawable(background);
        popup.setOutsideTouchable(true);
        click.setOnClickListener(view -> {
            click.setText("测试菜单点击 · " + (++popupTaps) + " 次");
            if (!dropdown) anchor.setText("独立菜单测试 · 已点击 " + popupTaps + " 次");
        });
        if (dropdown) popup.showAsDropDown(anchor, 0, 0, Gravity.END);
        else popup.showAtLocation(anchor, Gravity.TOP | Gravity.END,
                Math.round(12 * density), Math.round(80 * density));
    }

    private TextView text(LinearLayout parent, String content, int sp) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(sp);
        view.setPadding(0, 8, 0, 8);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
}
