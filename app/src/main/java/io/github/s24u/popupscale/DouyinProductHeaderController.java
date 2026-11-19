package io.github.s24u.popupscale;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Optional in-process compatibility for Douyin's commerce-owned navigation overlay. */
final class DouyinProductHeaderController {
  private static final String PAGE =
      "com.bytedance.android.shopping.prepurchase.business.pdp.ProductDetailV2Activity";
  private static final String SLOT =
      "com.bytedance.android.shopping.prepurchase.arch.standard.component.dynamic_feature.slice.BizSliceFrameLayout";
  private final WeakHashMap<Activity, Entry> entries = new WeakHashMap<>();
  private final BooleanSupplier enabled;
  private boolean failureLogged;

  DouyinProductHeaderController(BooleanSupplier enabled) {
    this.enabled = enabled;
  }

  void watch(Activity activity) {
    if (activity == null || !PAGE.equals(activity.getClass().getName()) || activity.isDestroyed())
      return;
    Entry entry = entries.get(activity);
    if (entry == null) {
      entry = new Entry(activity, activity.getWindow().getDecorView());
      entries.put(activity, entry);
      entry.root.get().getViewTreeObserver().addOnGlobalLayoutListener(entry.listener);
    }
    Entry current = entry;
    activity.getWindow().getDecorView().post(() -> update(current));
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
      View header = entry.header.get();
      if (header == null || !header.isAttachedToWindow()) {
        int id = owner.getResources().getIdentifier("2u1", "id", "com.ss.android.ugc.aweme");
        header = id == 0 ? null : root.findViewById(id);
        if (header == null || !header.getClass().getName().startsWith(SLOT))
          header = findSlot(root, root.getWidth(), root.getHeight(), 0, new int[] {512});
        if (header == null || !header.getClass().getName().startsWith(SLOT)) return;
        restore(entry);
        entry.header = new WeakReference<>(header);
        entry.originalY = header.getTranslationY();
      }
      float density = owner.getResources().getDisplayMetrics().density;
      if (!(header instanceof ViewGroup)
          || header.getWidth() < root.getWidth() * .9f
          || Math.abs(header.getTop()) > 8 * density) {
        restore(entry);
        return;
      }
      int first = firstControl((ViewGroup) header, 0, Math.round(24 * density), 0, new int[] {256});
      int amount =
          CommerceHeaderGeometry.trim(first, header.getHeight(), root.getHeight(), density);
      float target = entry.originalY - amount;
      if (Math.abs(header.getTranslationY() - target) > .5f) header.setTranslationY(target);
      if (amount != entry.amount) {
        entry.amount = amount;
        android.util.Log.i(
            "PopupScale",
            "commerce header gap trim="
                + amount
                + " first="
                + first
                + " height="
                + header.getHeight());
      }
    } catch (Throwable error) {
      restore(entry);
      if (!failureLogged) {
        failureLogged = true;
        android.util.Log.w(
            "PopupScale",
            "commerce header compatibility skipped: " + error.getClass().getSimpleName());
      }
    }
  }

  private static View findSlot(View view, int width, int height, int depth, int[] remaining) {
    if (depth > 14 || --remaining[0] < 0 || view.getVisibility() != View.VISIBLE) return null;
    if (view.getClass().getName().startsWith(SLOT)
        && view.getTop() == 0
        && view.getWidth() >= width * .9f
        && view.getHeight() > 0
        && view.getHeight() < height / 2) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = findSlot(group.getChildAt(i), width, height, depth + 1, remaining);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static int firstControl(
      ViewGroup group, int offset, int minimum, int depth, int[] remaining) {
    if (depth > 10 || --remaining[0] < 0) return Integer.MAX_VALUE;
    int first = Integer.MAX_VALUE;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child.getVisibility() != View.VISIBLE || child.getAlpha() < .05f) continue;
      int top = offset + Math.round(child.getY());
      if (child.isClickable() && child.getWidth() >= minimum && child.getHeight() >= minimum)
        first = Math.min(first, top);
      if (child instanceof ViewGroup)
        first =
            Math.min(first, firstControl((ViewGroup) child, top, minimum, depth + 1, remaining));
    }
    return first;
  }

  private static void restore(Entry entry) {
    View header = entry.header.get();
    if (header != null && entry.amount != 0) header.setTranslationY(entry.originalY);
    entry.amount = 0;
  }

  private final class Entry {
    final WeakReference<Activity> owner;
    final WeakReference<View> root;
    WeakReference<View> header = new WeakReference<>(null);
    float originalY;
    int amount;
    final ViewTreeObserver.OnGlobalLayoutListener listener = () -> update(this);

    Entry(Activity activity, View view) {
      owner = new WeakReference<>(activity);
      root = new WeakReference<>(view);
    }
  }
}
