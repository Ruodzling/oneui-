package io.github.s24u.popupscale;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SettingsDefaultsChecks {
    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Map<String, Object> fresh = SettingsDefaults.missing(Map.of());
        check(Boolean.FALSE.equals(fresh.get("enabled")), "fresh install waits for master toggle");
        check(Boolean.FALSE.equals(fresh.get("diagnostic")) && Boolean.FALSE.equals(fresh.get("system_observe")), "no diagnostic-only defaults");
        for (String key : new String[]{"all_apps", "system_scale_all", "system_features", "system_handle", "system_hang", "system_live"})
            check(Boolean.TRUE.equals(fresh.get(key)), "ready-to-use " + key);
        check(Integer.valueOf(500).equals(fresh.get("reference_dp")) && "medium".equals(fresh.get("density_preset")), "500dp medium");
        check(!SettingsDefaults.gesturesEnabled(false, true), "master off also gates gestures");
        check(SettingsDefaults.gesturesEnabled(true, true), "master alone activates fresh interactions");
        check(!SettingsDefaults.gesturesEnabled(true, false), "retain explicit gesture opt-out");
        check(SettingsDefaults.missing(fresh).isEmpty(), "initialization is idempotent");
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("enabled", true);
        legacy.put("diagnostic", true);
        legacy.put("system_scale_all", false);
        legacy.put("system_live", false);
        legacy.put("all_apps", false);
        legacy.put("reference_dp", 570);
        legacy.put("packages", "org.example.reader");
        legacy.put("excluded_packages", "flar2.devcheck");
        legacy.put("ui_appearance", 2);
        Map<String, Object> snapshot = new LinkedHashMap<>(legacy);
        Map<String, Object> added = SettingsDefaults.missing(legacy);
        check(legacy.equals(snapshot), "planning never mutates saved settings");
        for (String key : legacy.keySet()) check(!added.containsKey(key), "preserve saved " + key);
        check("custom".equals(added.get("density_preset")), "legacy non-preset width stays custom");
        legacy.putAll(added);
        check(SettingsDefaults.missing(legacy).isEmpty(), "upgrade fills only missing values once");
        for (int width : new int[]{400,500,600}) {
            String preset = width == 400 ? "low" : width == 500 ? "medium" : "high";
            check(preset.equals(SettingsDefaults.missing(Map.of("reference_dp", width)).get("density_preset")), "infer old preset " + width);
        }
        check(!SettingsDefaults.missing(Map.of("reference_dp", 500, "density_preset", "custom")).containsKey("density_preset"), "explicit custom500 stays custom");
        check(!SettingsDefaults.flag("diagnostic", true) && SettingsDefaults.flag("system_scale_all", false), "all readers share current defaults");
        System.out.println("PASS: fresh one-switch profile, master gating, upgrade opt-outs/custom density, idempotent defaults and unchanged saved values.");
    }
}
