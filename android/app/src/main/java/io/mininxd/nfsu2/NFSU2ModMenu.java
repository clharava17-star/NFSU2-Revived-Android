package io.mininxd.nfsu2;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import io.mininxd.nfsu2.net.RoamNativeBridge;

/** External-file-backed, offline-only NFSU2 Revived Mod Menu. */
final class NFSU2ModMenu {
    private static final int FLAG_AUTO_SHIFT = 1;
    private static final int FLAG_INFINITE_NOS = 2;
    private final Activity activity;
    private final File folder;
    private final File configFile;

    private boolean masterEnabled;
    private boolean autoShift;
    private boolean infiniteNos;
    private boolean raceAssist;
    private int shiftUpScan = 0x2a;

    NFSU2ModMenu(Activity activity) {
        this.activity = activity;
        this.folder = new File(LauncherActivity.gameRoot(), "mods/NFSU2_ModMenu");
        this.configFile = new File(folder, "modmenu.ini");
        readConfig();
    }

    File getConfigFile() { return configFile; }

    void loadAndApply() {
        readConfig();
        applyNative();
    }

    void suspend() {
        try {
            RoamNativeBridge.nativeSetModCheats(0, shiftUpScan);
        } catch (Throwable ignored) {}
    }

    private void readConfig() {
        masterEnabled = false; autoShift = false; infiniteNos = false; raceAssist = false;
        try {
            if (!folder.exists() && !folder.mkdirs()) return;
            if (!configFile.isFile()) { writeConfig(); return; }
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(configFile);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
            masterEnabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
            autoShift = Boolean.parseBoolean(p.getProperty("auto_shift", "false"));
            infiniteNos = Boolean.parseBoolean(p.getProperty("infinite_nos", "false"));
            raceAssist = Boolean.parseBoolean(p.getProperty("race_assist", "false"));
        } catch (Throwable t) {
            Toast.makeText(activity, "Mod Menu: gagal membaca modmenu.ini", Toast.LENGTH_SHORT).show();
        }
        refreshShiftKey();
    }

    private void refreshShiftKey() {
        try {
            int[] keys = RoamNativeBridge.nativeGetActionKeys();
            if (keys != null && keys.length > 6 && keys[6] > 0 && keys[6] < 256) shiftUpScan = keys[6];
        } catch (Throwable ignored) {}
    }

    private void writeConfig() {
        try {
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("folder tidak dapat dibuat");
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(masterEnabled));
            p.setProperty("auto_shift", Boolean.toString(autoShift));
            p.setProperty("infinite_nos", Boolean.toString(infiniteNos));
            p.setProperty("race_assist", Boolean.toString(raceAssist));
            p.setProperty("perfect_drift", "false");
            p.setProperty("schema_version", "2");
            try (FileOutputStream out = new FileOutputStream(configFile);
                 OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                p.store(writer, "NFSU2 Revived Mod Menu - offline settings");
            }
        } catch (Throwable t) {
            Toast.makeText(activity, "Tidak bisa menulis " + configFile.getPath(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyNative() {
        refreshShiftKey();
        int flags = 0;
        if (masterEnabled) {
            if (autoShift || raceAssist) flags |= FLAG_AUTO_SHIFT;
            if (infiniteNos || raceAssist) flags |= FLAG_INFINITE_NOS;
        }
        try {
            if (RoamNativeBridge.isLoaded()) RoamNativeBridge.nativeSetModCheats(flags, shiftUpScan);
        } catch (Throwable t) {
            Toast.makeText(activity, "Mod Menu native bridge belum tersedia di APK ini", Toast.LENGTH_LONG).show();
        }
    }

    void show() {
        readConfig();
        applyNative();
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        body.setPadding(pad, dp(8), pad, dp(4));

        TextView path = new TextView(activity);
        path.setText("External config: " + configFile.getPath());
        path.setTextColor(0xffb7c58f);
        path.setTextSize(11);
        body.addView(path);

        CheckBox master = check("Master Cheat (ON/OFF)", masterEnabled); body.addView(master);
        CheckBox shift = check("Smart Perfect Shift – Drag", autoShift); body.addView(shift);
        CheckBox nos = check("Infinite NOS – all races", infiniteNos); body.addView(nos);
        CheckBox assist = check("Race Assist (Smart Shift + Infinite NOS)", raceAssist); body.addView(assist);

        CheckBox drift = check("Perfect Drift (belum tersedia)", false);
        drift.setEnabled(false);
        body.addView(drift);
        TextView driftNote = new TextView(activity);
        driftNote.setText("Belum diaktifkan pada build pengembangan ini. Belum ada perubahan handling atau skor drift.");
        driftNote.setTextColor(0xffffcc80);
        driftNote.setTextSize(11);
        driftNote.setPadding(dp(32), 0, 0, dp(4));
        body.addView(driftNote);

        TextView note = new TextView(activity);
        note.setText("Smart Perfect Shift membaca titik upshift dan jendela shift dari drivetrain aktif, sehingga mengikuti mobil, gigi, rasio transmisi, dan tuning tanpa set RPM per mobil. Fitur ini hanya aktif pada Career Drag. Race Assist menggabungkan Smart Perfect Shift dan Infinite NOS; hasil balapan tetap bergantung pada kondisi permainan.");
        note.setTextColor(0xffbdbdbd);
        note.setTextSize(11);
        note.setPadding(0, dp(8), 0, 0);
        body.addView(note);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("NFSU2 REVIVED – MOD MENU")
                .setView(body)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save & Apply", (dialog, which) -> {
                    masterEnabled = master.isChecked();
                    autoShift = shift.isChecked();
                    infiniteNos = nos.isChecked();
                    raceAssist = assist.isChecked();
                    writeConfig();
                    applyNative();
                    Toast.makeText(activity, "Mod Menu settings saved", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox box = new CheckBox(activity);
        box.setText(label); box.setTextColor(Color.WHITE); box.setTextSize(14); box.setChecked(checked);
        return box;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
