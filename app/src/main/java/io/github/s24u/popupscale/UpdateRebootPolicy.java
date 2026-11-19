package io.github.s24u.popupscale;

/** Once-per-version update notice; fresh installs keep their existing onboarding. */
public final class UpdateRebootPolicy {
  private UpdateRebootPolicy() {}
  public static boolean shouldRemind(int seen,int current,long firstInstall,long updated,boolean coresCurrent) {
    return current>0 && seen!=current && !coresCurrent &&
        (seen>0 || firstInstall>0 && updated>firstInstall);
  }
}
