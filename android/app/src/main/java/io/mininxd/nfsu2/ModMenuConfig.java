package io.mininxd.nfsu2;

import android.content.Context;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * External Mod Menu settings stored beside the game's data, never in the Career save.
 * The current milestone persists options only; native cheat handlers are added separately.
 */
final class ModMenuConfig {
    static final int MIN_SHIFT_RPM = 5000;
    static final int MAX_SHIFT_RPM = 7500;
    static final int DEFAULT_SHIFT_RPM = 7000;

    static final class Settings {
        boolean master;
        boolean autoPerfectShift;
        int autoShiftRpm = DEFAULT_SHIFT_RPM;
        boolean infiniteNos;
        boolean raceAssist;
        boolean perfectDrift;
    }

    private ModMenuConfig() {}

    static File file(Context context) {
        File root = LauncherActivity.gameRoot();
        return new File(new File(new File(root, "mods"), "NFSU2_ModMenu"), "modmenu.ini");
    }

    static Settings load(Context context) {
        Settings settings = new Settings();
        File file = file(context);
        if (!file.isFile()) return settings;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")
                        || trimmed.startsWith("[")) continue;
                int equals = trimmed.indexOf('=');
                if (equals <= 0) continue;
                String key = trimmed.substring(0, equals).trim();
                String value = trimmed.substring(equals + 1).trim();
                switch (key) {
                    case "master": settings.master = parseBoolean(value, false); break;
                    case "auto_perfect_shift": settings.autoPerfectShift = parseBoolean(value, false); break;
                    case "auto_shift_rpm":
                        try {
                            int rpm = Integer.parseInt(value);
                            settings.autoShiftRpm = Math.max(MIN_SHIFT_RPM, Math.min(MAX_SHIFT_RPM, rpm));
                        } catch (NumberFormatException ignored) {}
                        break;
                    case "infinite_nos": settings.infiniteNos = parseBoolean(value, false); break;
                    case "race_assist": settings.raceAssist = parseBoolean(value, false); break;
                    case "perfect_drift": settings.perfectDrift = parseBoolean(value, false); break;
                    default: break;
                }
            }
        } catch (Exception ignored) {
            // A malformed optional config must never prevent the game from starting.
        }
        return settings;
    }

    static boolean save(Context context, Settings settings) {
        File target = file(context);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File temp = new File(parent, target.getName() + ".tmp");
        String data =
                "# NFSU2 Revived Mod Menu configuration\n"
                + "# This file is separate from Career Mode save data.\n"
                + "version=2\n"
                + "master=" + onOff(settings.master) + "\n"
                + "auto_perfect_shift=" + onOff(settings.autoPerfectShift) + "\n"
                + "auto_shift_rpm=" + Math.max(MIN_SHIFT_RPM, Math.min(MAX_SHIFT_RPM, settings.autoShiftRpm)) + "\n"
                + "infinite_nos=" + onOff(settings.infiniteNos) + "\n"
                + "race_assist=" + onOff(settings.raceAssist) + "\n"
                + "perfect_drift=" + onOff(settings.perfectDrift) + "\n";
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            writer.write(data);
            writer.flush();
        } catch (Exception error) {
            // Best-effort cleanup; the previous configuration remains untouched.
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        // Preserve the previous settings file until the new file has been renamed into place.
        File backup = new File(parent, target.getName() + ".bak");
        if (backup.exists() && !backup.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            if (hadTarget) {
                // Best-effort rollback; never delete the only existing copy.
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        if (hadTarget) {
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
        }
        return true;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return fallback;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
