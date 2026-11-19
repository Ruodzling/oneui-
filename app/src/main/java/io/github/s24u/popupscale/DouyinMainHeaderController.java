package io.github.s24u.popupscale;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Reversible removal of the app-owned status spacer above the main-tab navigation. */
final class DouyinMainHeaderController {
  private static final String MAIN = "com.ss.android.ugc.aweme.main.MainActivity";
  private static final String SPLASH = "com.ss.android.ugc.aweme.splash.SplashActivity";
  private static final String TITLE =
      "com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBarContainer";
  private static final String PROFILE =
      "com.bytedance.highperformanceview.layout.MeasureOnceRelativeLayout2";
  private final WeakHashMap<Activity, Entry> entries = new WeakHashMap<>();
  private final BooleanSupplier enabled;
  private boolean failureLogged;

  DouyinMainHeaderController(BooleanSupplier enabled) {
    this.enabled = enabled;
  }

  void watch(Activity activity) {
    if (activity == null
        || activity.isDestroyed()
        || !(MAIN.equals(activity.getClass().getName())
            || SPLASH.equals(activity.getClass().getName()))) return;
    Entry entry = entries.get(activity);
    View root = activity.getWindow().getDecorView();
    if (entry == null) {
      entry = new Entry(activity, root);
      entries.put(activity, entry);
      root.getViewTreeObserver().addOnGlobalLayoutListener(entry.listener);
    }
    Entry current = entry;
    root.post(() -> update(current));
  }

  void forget(Activity activity) {
    Entry entry = entries.remove(activity);
    if (entry == null) return;
    View root = entry.root.get();
    if (root != null && root.getViewTreeObserver().isAlive())
      root.getViewTreeObserver().removeOnGlobalLayoutListener(entry.listener);
    restore(entry);
  }

  private void update(Entry entry) {
    try {
      Activity owner = entry.owner.get();
      View root = entry.root.get();
      if (owner == null || root == null || owner.isDestroyed()) return;
      Object window =
          XposedHelpers.getObjectField(
              owner.getResources().getConfiguration(), "windowConfiguration");
      if (!enabled.getAsBoolean()
          || !Integer.valueOf(5).equals(XposedHelpers.callMethod(window, "getWindowingMode"))) {
        restore(entry);
        return;
      }
      if (entry.statusId == 0) {
        entry.statusId =
            owner.getResources().getIdentifier("status_bar", "id", "com.ss.android.ugc.aweme");
        entry.titleId =
            owner.getResources().getIdentifier("title_bar", "id", "com.ss.android.ugc.aweme");
      }
      // Both anchors are native ID lookups, not a per-frame scan of feed/commerce contents.
      View status = entry.statusId == 0 ? null : root.findViewById(entry.statusId);
      if (status != null && status.getParent() instanceof LinearLayout) {
        LinearLayout host = (LinearLayout) status.getParent();
        for (int i = 0; i < host.getChildCount(); i++) {
          View title = host.getChildAt(i);
          if (TITLE.equals(title.getClass().getName())) {
            normalize(entry, host, status, title, root.getWidth());
            break;
          }
        }
      }
      View title = entry.titleId == 0 ? null : root.findViewById(entry.titleId);
      if (title != null
          && PROFILE.equals(title.getClass().getName())
          && title.getParent() instanceof ViewGroup) {
        ViewGroup host = (ViewGroup) title.getParent();
        View profileStatus = host.findViewById(entry.statusId);
        if (profileStatus != null
            && profileStatus != title
            && profileStatus.getParent() instanceof ViewGroup) {
          ViewGroup slot = (ViewGroup) profileStatus.getParent();
          if (slot.getParent() == host && !slot.isClickable())
            normalize(entry, host, slot, title, root.getWidth());
        }
      }
      entry.headers.removeIf(header -> header.host.get() == null);
    } catch (Throwable error) {
      restore(entry);
      if (!failureLogged) {
        failureLogged = true;
        android.util.Log.w(
            "PopupScale", "main header compatibility skipped: " + error.getClass().getSimpleName());
      }
    }
  }

  private static void normalize(Entry entry, ViewGroup host, View slot, View title, int width) {
    if (slot.getClass() != View.class && !(slot instanceof android.widget.FrameLayout)) return;
    if (slot.isClickable() || slot.getTop() != 0 || host.getTop() != 0) return;
    Header existing = null;
    for (Header header : entry.headers) if (header.host.get() == host) existing = header;
    if (existing != null && existing.applied && slot.getLayoutParams().height == 0) {
      existing.apply();
      return;
    }
    int gap =
        DouyinHeaderGeometry.gap(
            slot.getHeight(),
            title.getTop(),
            title.getHeight(),
            host.getHeight(),
            host.getWidth(),
            width,
            host.getResources().getDisplayMetrics().density);
    if (gap == 0 || slot.getLayoutParams() == null || host.getLayoutParams() == null) return;
    if (existing != null) {
      // Native layout wrote a fresh geometry (for example a density change). Capture that baseline.
      entry.headers.remove(existing);
    }
    Header header = new Header(host, slot, title, gap);
    entry.headers.add(header);
    header.apply();
    android.util.Log.i(
        "PopupScale",
        "main header status spacer removed="
            + gap
            + " row="
            + title.getHeight()
            + " profile="
            + !(host instanceof LinearLayout));
  }

  private static void restore(Entry entry) {
    for (Header header : entry.headers) header.restore();
    entry.headers.clear();
  }

  private final class Entry {
    final WeakReference<Activity> owner;
    final WeakReference<View> root;
    final ArrayList<Header> headers = new ArrayList<>();
    int statusId, titleId;
    final ViewTreeObserver.OnGlobalLayoutListener listener = () -> update(this);

    Entry(Activity activity, View view) {
      owner = new WeakReference<>(activity);
      root = new WeakReference<>(view);
    }
  }

  private static final class Header {
    final WeakReference<ViewGroup> host;
    final WeakReference<View> slot, title;
    final int slotHeight, margin, padding, gap;
    int hostHeight, appliedHeight;
    final int appliedMargin, appliedPadding;
    boolean applied;

    Header(ViewGroup host, View slot, View title, int gap) {
      this.host = new WeakReference<>(host);
      this.slot = new WeakReference<>(slot);
      this.title = new WeakReference<>(title);
      this.gap = gap;
      slotHeight = slot.getLayoutParams().height;
      hostHeight = host.getLayoutParams().height;
      ViewGroup.LayoutParams lp = title.getLayoutParams();
      margin =
          lp instanceof ViewGroup.MarginLayoutParams
              ? ((ViewGroup.MarginLayoutParams) lp).topMargin
              : 0;
      padding = host.getPaddingTop();
      appliedHeight = hostHeight > 0 ? Math.max(title.getHeight(), hostHeight - gap) : hostHeight;
      appliedMargin = Math.abs(margin - gap) <= 2 ? 0 : margin;
      appliedPadding = Math.abs(padding - gap) <= 2 ? 0 : padding;
    }

    void apply() {
      ViewGroup parent = host.get();
      View spacer = slot.get();
      View row = title.get();
      if (parent == null || spacer == null || row == null) return;
      // A density/size change may remeasure the title or rewrite the native container height.
      // Keep the actual row height instead of pinning the pixel height from the first frame.
      if (applied && parent.getLayoutParams().height != appliedHeight)
        hostHeight = parent.getLayoutParams().height;
      if (hostHeight > 0 && row.getHeight() > 0) appliedHeight = row.getHeight();
      applied = true;
      height(spacer, 0);
      height(parent, appliedHeight);
      margin(row, appliedMargin);
      if (parent.getPaddingTop() != appliedPadding)
        parent.setPadding(
            parent.getPaddingLeft(),
            appliedPadding,
            parent.getPaddingRight(),
            parent.getPaddingBottom());
      parent.requestLayout();
    }

    void restore() {
      if (!applied) return;
      ViewGroup parent = host.get();
      View spacer = slot.get();
      View row = title.get();
      if (spacer != null && spacer.getLayoutParams().height == 0) height(spacer, slotHeight);
      if (parent != null) {
        if (parent.getLayoutParams().height == appliedHeight) height(parent, hostHeight);
        if (parent.getPaddingTop() == appliedPadding && padding != appliedPadding)
          parent.setPadding(
              parent.getPaddingLeft(),
              padding,
              parent.getPaddingRight(),
              parent.getPaddingBottom());
      }
      if (row != null
          && row.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
          && ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).topMargin == appliedMargin)
        margin(row, margin);
      applied = false;
    }

    private static void height(View view, int height) {
      ViewGroup.LayoutParams lp = view.getLayoutParams();
      if (lp.height != height) {
        lp.height = height;
        view.setLayoutParams(lp);
      }
    }

    private static void margin(View view, int margin) {
      if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
      ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
      if (lp.topMargin != margin) {
        lp.topMargin = margin;
        view.setLayoutParams(lp);
      }
    }
  }
}
