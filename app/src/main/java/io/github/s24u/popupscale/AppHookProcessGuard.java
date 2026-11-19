package io.github.s24u.popupscale;

import java.util.Properties;

/** System properties are shared even when an application replays load-package with another loader. */
public final class AppHookProcessGuard {
    private static final String KEY = "io.github.s24u.popupscale.app_hooks_installed";
    private AppHookProcessGuard() {}

    public static boolean claim(Properties processProperties) {
        synchronized (processProperties) {
            if ("true".equals(processProperties.getProperty(KEY))) return false;
            processProperties.setProperty(KEY, "true");
            return true;
        }
    }
}
