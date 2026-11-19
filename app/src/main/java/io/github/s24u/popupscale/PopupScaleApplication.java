package io.github.s24u.popupscale;

public final class PopupScaleApplication extends android.app.Application {
  @Override public void onCreate() {
    super.onCreate();
    ModuleSettings.openShared(this);
  }
}
