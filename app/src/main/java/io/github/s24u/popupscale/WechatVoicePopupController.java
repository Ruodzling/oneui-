package io.github.s24u.popupscale;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Only the verified voice-recorder PopupWindow; keeps its Window and native recording flow intact.
 */
final class WechatVoicePopupController {
  private final WeakHashMap<PopupWindow, State> states = new WeakHashMap<>();
  private final WeakHashMap<ZoomFrameLayout, Boolean> activeFrames = new WeakHashMap<>();
  private boolean boundsAttempted;
  private int rootId, waveId, cancelId, footerId;

  WindowManager.LayoutParams prepare(
      PopupWindow popup,
      WindowManager.LayoutParams attributes,
      Activity owner,
      FrameLayout decor,
      CanvasController canvases,
      boolean enabled,
      int referenceDp,
      boolean logging) {
    if (!"com.tencent.mm".equals(owner.getPackageName())) return null;
    View content = popup.getContentView();
    if (content == null) return null;
    if (rootId == 0) {
      rootId = id(owner, "k_r");
      waveId = id(owner, "k_m");
      cancelId = id(owner, "k_p");
      footerId = id(owner, "ve7");
    }
    View root = rootId == 0 ? null : content.findViewById(rootId);
    View wave = waveId == 0 ? null : content.findViewById(waveId);
    boolean layout =
        root instanceof ViewGroup
            && wave != null
            && "com.tencent.mm.transvoice.ui.SoundWaveView".equals(wave.getClass().getName())
            && cancelId != 0
            && content.findViewById(cancelId) instanceof ViewGroup
            && footerId != 0
            && content.findViewById(footerId) != null;
    View host = owner.getWindow().getDecorView();
    float density = owner.getResources().getDisplayMetrics().density;
    State state = states.get(popup);
    ZoomFrameLayout frame = state == null ? null : state.frame.get();
    boolean known = frame != null && frame.getParent() == decor;
    if (!WechatVoicePolicy.accepts(
            owner.getPackageName(),
            layout,
            attributes.width,
            attributes.height,
            host.getWidth(),
            host.getHeight(),
            density)
        && !known) return null;
    int mode = canvases.windowMode(owner);
    boolean zoom = enabled && mode == 5 && layout;
    if (frame == null || frame.getParent() != decor) {
      if (!zoom || decor.getChildCount() == 0) return attributes;
      frame = new ZoomFrameLayout(owner);
      while (decor.getChildCount() > 0) {
        View child = decor.getChildAt(0);
        ViewGroup.LayoutParams original = child.getLayoutParams();
        decor.removeViewAt(0);
        FrameLayout.LayoutParams params =
            original instanceof FrameLayout.LayoutParams
                ? new FrameLayout.LayoutParams((FrameLayout.LayoutParams) original)
                : original instanceof ViewGroup.MarginLayoutParams
                    ? new FrameLayout.LayoutParams((ViewGroup.MarginLayoutParams) original)
                    : new FrameLayout.LayoutParams(original);
        frame.canvas().addView(child, params);
      }
      decor.addView(frame, new FrameLayout.LayoutParams(-1, -1));
      state = new State(owner, frame);
      states.put(popup, state);
    }
    frame.configure(zoom, canvases.canvasDensity(owner, 600), referenceDp, logging);
    activeFrames.put(frame, zoom);
    state.x = attributes.x;
    state.y = attributes.y;
    if (logging)
      Log.i(
          "PopupScale",
          "wechat voice overlay mode="
              + mode
              + " host="
              + host.getWidth()
              + "x"
              + host.getHeight()
              + " window="
              + attributes.width
              + "x"
              + attributes.height
              + " canvasDp="
              + referenceDp
              + " zoom="
              + zoom);
    installBounds(owner.getClassLoader());
    // Preserve MATCH_PARENT, position, clipping, focus, insets and every native recording callback.
    return attributes;
  }

  void updateForActivity(Activity owner) {
    for (PopupWindow popup : new ArrayList<>(states.keySet())) {
      State state = states.get(popup);
      if (state != null && state.owner.get() == owner && popup.isShowing())
        popup.update(state.x, state.y, popup.getWidth(), popup.getHeight(), true);
    }
  }

  void forget(Activity owner) {
    states
        .entrySet()
        .removeIf(
            entry -> {
              State state = entry.getValue();
              if (state.owner.get() != owner) return false;
              ZoomFrameLayout frame = state.frame.get();
              if (frame != null) activeFrames.remove(frame);
              return true;
            });
  }

  private static int id(Activity owner, String name) {
    return owner.getResources().getIdentifier(name, "id", "com.tencent.mm");
  }

  private void installBounds(ClassLoader loader) {
    if (boundsAttempted) return;
    boundsAttempted = true;
    try {
      // Verified 8.0.79 helper builds an on-screen rect using unscaled width/height.
      Class<?> type = Class.forName("com.tencent.mm.pluginsdk.ui.chat.t5", false, loader);
      Method method = type.getDeclaredMethod("l", View.class);
      if (method.getReturnType() != Rect.class || !Modifier.isStatic(method.getModifiers())) return;
      XposedBridge.hookMethod(
          method,
          new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam event) {
              if (event.hasThrowable() || !(event.args[0] instanceof View)) return;
              View view = (View) event.args[0];
              float scale = voiceScale(view);
              if (!Float.isFinite(scale) || !(event.getResult() instanceof Rect)) return;
              try {
                // Preserve native origin and animation semantics. Only account for our
                // additional ancestor canvas in the width/height returned by this helper.
                Rect original = (Rect) event.getResult();
                Rect result =
                    new Rect(
                        original.left,
                        original.top,
                        original.left + PopupGeometry.size(view.getWidth(), scale),
                        original.top + PopupGeometry.size(view.getHeight(), scale));
                event.setResult(result);
              } catch (Throwable error) {
                Log.w(
                    "PopupScale",
                    "voice visual bounds skipped: " + error.getClass().getSimpleName());
              }
            }
          });
    } catch (Throwable error) {
      Log.i(
          "PopupScale",
          "voice visual bounds helper unavailable: " + error.getClass().getSimpleName());
    }
  }

  private float voiceScale(View view) {
    if (!view.isAttachedToWindow() || view.getWidth() <= 0 || view.getHeight() <= 0)
      return Float.NaN;
    View candidate = view;
    for (int depth = 0; depth < 32 && candidate != null; depth++) {
      if (candidate instanceof ZoomFrameLayout
          && Boolean.TRUE.equals(activeFrames.get(candidate))
          && Math.abs(((ZoomFrameLayout) candidate).contentScale() - 1f) > .001f)
        return ((ZoomFrameLayout) candidate).contentScale();
      ViewParent parent = candidate.getParent();
      candidate = parent instanceof View ? (View) parent : null;
    }
    return Float.NaN;
  }

  private static final class State {
    final WeakReference<Activity> owner;
    final WeakReference<ZoomFrameLayout> frame;
    int x, y;

    State(Activity activity, ZoomFrameLayout view) {
      owner = new WeakReference<>(activity);
      frame = new WeakReference<>(view);
    }
  }
}
