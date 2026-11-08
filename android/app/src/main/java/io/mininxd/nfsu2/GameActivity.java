package io.mininxd.nfsu2;

import android.app.NativeActivity;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.Intent;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Toast;
import android.widget.TextView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.CheckBox;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.util.DisplayMetrics;

/** Storage permission gateway for the fixed Internal storage/nfsu2 directory. */
public final class GameActivity extends NativeActivity {
    static {
        System.loadLibrary("nfsu2_android");
        try {
            System.loadLibrary("nfsu2_roam");
        } catch (Throwable ignored) {}
    }
    private boolean requested;
    private boolean foreground;
    private TextView status;
    private PopupWindow statusWindow;
    private PopupWindow controlsWindow;
    private PopupWindow modCalibrationWindow;
    private boolean modCalibrationRunning;
    // Only send Escape when the game is actually in driving mode. Menu/results
    // screens must not receive a synthetic Back press when MOD is opened.
    private boolean modMenuPausedGameplay;
    private Handler modCalibrationHandler;
    private Runnable modCalibrationPoll;
    private DiagnosticOverlay diagnosticOverlay;
    private RacingControlsView touchControls;
    private boolean statusHidden;
    private Runnable statusHideTask;
    private final boolean[] touchKeys=new boolean[256],sentKeys=new boolean[256];
    private boolean tiltLeft,tiltRight,tiltEnabled,tiltCalibrate=true;
    private float tiltFiltered,tiltNeutral,tiltSensitivity=1;
    private boolean tiltInvert;
    private android.hardware.SensorManager tiltSensors;
    private android.hardware.Sensor tiltSensor;
    private final android.hardware.SensorEventListener tiltListener=new android.hardware.SensorEventListener() {
        @Override public void onAccuracyChanged(android.hardware.Sensor sensor,int accuracy) {}
        @Override public void onSensorChanged(android.hardware.SensorEvent event) {
            int rotation=getWindowManager().getDefaultDisplay().getRotation();
            float horizontal=rotation==android.view.Surface.ROTATION_90?-event.values[1]:
                    rotation==android.view.Surface.ROTATION_270?event.values[1]:event.values[0];
            if(tiltCalibrate) {tiltNeutral=horizontal;tiltFiltered=horizontal;tiltCalibrate=false;}
            tiltFiltered+=.2f*(horizontal-tiltFiltered);
            float turn=(tiltFiltered-tiltNeutral)*tiltSensitivity*(tiltInvert?-1:1);
            if(!foreground||!hasWindowFocus()||!tiltEnabled||touchControls==null||!touchControls.isDriving()) {setTiltKeys(false,false);return;}
            setTiltKeys(turn<-(tiltLeft?.5f:.8f),turn>(tiltRight?.5f:.8f));
        }
    };
    private int keyLeft = 0xcb;
    private int keyRight = 0xcd;
    private int keyShiftUp = 0x2a;
    private Handler autoShiftHandler;
    private Runnable autoShiftPoll;
    private int autoShiftPressedScan = -1;

    private void sendCombinedKey(int scan) {
        boolean held=touchKeys[scan]||(scan==keyLeft&&tiltLeft)||(scan==keyRight&&tiltRight);
        if(sentKeys[scan]!=held) {sentKeys[scan]=held;nativeKey(scan,held);}
    }
    private void setTouchKey(int scan,boolean down) {touchKeys[scan]=down;sendCombinedKey(scan);}
    private void setTiltKeys(boolean left,boolean right) {tiltLeft=left;tiltRight=right;sendCombinedKey(keyLeft);sendCombinedKey(keyRight);}
    private void updateTiltSensor() {
        if(tiltSensors==null)return;tiltSensors.unregisterListener(tiltListener);setTiltKeys(false,false);
        if(foreground&&tiltEnabled&&tiltSensor!=null) {
            tiltCalibrate=true;tiltSensors.registerListener(tiltListener,tiltSensor,android.hardware.SensorManager.SENSOR_DELAY_GAME);
        }
    }
    private void showTiltOptions() {
        if(tiltSensor==null) {Toast.makeText(this,"This device does not have an accelerometer",Toast.LENGTH_LONG).show();return;}
        android.content.SharedPreferences options=getSharedPreferences("touch_layouts_v1",MODE_PRIVATE);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),dp(8));
        CheckBox enabled=new CheckBox(this);enabled.setText("Steer by tilting device");enabled.setChecked(tiltEnabled);form.addView(enabled);
        CheckBox invert=new CheckBox(this);invert.setText("Invert steering direction");invert.setChecked(tiltInvert);form.addView(invert);
        TextView label=new TextView(this);label.setText("Sensitivity: "+tiltSensitivity+"×");form.addView(label);
        android.widget.SeekBar sensitivity=new android.widget.SeekBar(this);sensitivity.setMax(30);sensitivity.setProgress(Math.round(tiltSensitivity*10)-10);form.addView(sensitivity);
        sensitivity.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(android.widget.SeekBar bar) {}public void onStopTrackingTouch(android.widget.SeekBar bar) {}
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user) {label.setText("Sensitivity: "+((progress+10)/10f)+"×");}
        });
        TextView help=new TextView(this);help.setText("Hold the device in your playing position when applying. Tilting steers left/right; pedals remain on screen.");form.addView(help);
        new AlertDialog.Builder(this).setTitle("Tilt Steering").setView(form)
                .setPositiveButton("Apply and calibrate",(dialog,which)->{
                    tiltEnabled=enabled.isChecked();tiltInvert=invert.isChecked();tiltSensitivity=(sensitivity.getProgress()+10)/10f;
                    options.edit().putBoolean("tilt.enabled",tiltEnabled).putBoolean("tilt.invert",tiltInvert).putFloat("tilt.sensitivity",tiltSensitivity).apply();
                    updateTiltSensor();
                }).setNegativeButton("Cancel",null).show();
    }
    private android.hardware.input.InputManager inputManager;
    private final android.hardware.input.InputManager.InputDeviceListener gamepadListener=new android.hardware.input.InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) {updateGamepads();}
        @Override public void onInputDeviceRemoved(int id) {updateGamepads();}
        @Override public void onInputDeviceChanged(int id) {updateGamepads();}
    };
    /**
     * Physical gamepads (Bluetooth or USB) drive the game; the touch layer hides while one is connected.
     * Only external devices count: built-in sensors and side keys of several MediaTek phones report
     * themselves as joysticks with X/Y axes, and their resting value used to steer the car by itself.
     * Pads in "keyboard" mode report only KEYBOARD but still have gamepad buttons.
     */
    private void updateGamepads() {
        boolean connected=false;
        java.util.ArrayList<Integer> accepted=new java.util.ArrayList<>();
        StringBuilder seen=new StringBuilder();
        for(int id:android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device=android.view.InputDevice.getDevice(id);
            if(device==null||device.isVirtual())continue;
            int sources=device.getSources();
            boolean buttons=(sources&android.view.InputDevice.SOURCE_GAMEPAD)==android.view.InputDevice.SOURCE_GAMEPAD;
            // Some fingerprint sensors advertise JOYSTICK with only a Z axis.
            boolean stick=(sources&android.view.InputDevice.SOURCE_JOYSTICK)==android.view.InputDevice.SOURCE_JOYSTICK&&
                    device.getMotionRange(android.view.MotionEvent.AXIS_X)!=null&&
                    device.getMotionRange(android.view.MotionEvent.AXIS_Y)!=null;
            boolean keys=(sources&android.view.InputDevice.SOURCE_KEYBOARD)==android.view.InputDevice.SOURCE_KEYBOARD&&
                    device.getKeyboardType()!=android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC&&
                    device.hasKeys(android.view.KeyEvent.KEYCODE_BUTTON_A)[0];
            boolean pad=device.isExternal()&&(buttons||stick||keys);
            seen.append(seen.length()==0?"":"; ").append(device.getName()).append(" [").append(Integer.toHexString(sources))
                    .append(device.isExternal()?" external":" internal").append(pad?" pad":"").append(']');
            if(pad){connected=true;accepted.add(id);}
        }
        android.util.Log.i("NFS","Input devices: "+seen);
        // Only these devices may move the guest's stick or press its keys.
        int[] ids=new int[accepted.size()];for(int i=0;i<ids.length;++i)ids[i]=accepted.get(i);
        nativeGamepadDevices(ids);
        if(touchControls!=null)touchControls.setGamepadConnected(connected);
        nativeGamepadConnected(connected);
    }
    private final Runnable updateControlMode = new Runnable() {
        @Override public void run() {
            if(!foreground||touchControls==null)return;
            touchControls.setGameMode(nativeDrivingControls());
            // Playing on with the pad after a touch revealed the controls hides them again.
            if(nativeGamepadUsed())touchControls.gamepadUsed();
            updateHudForGamepad(touchControls.hiddenByGamepad());
            if (io.mininxd.nfsu2.net.RoamNativeBridge.isLoaded()) {
                try {
                    int[] actionKeys = io.mininxd.nfsu2.net.RoamNativeBridge.nativeGetActionKeys();
                    if (actionKeys != null && actionKeys.length >= 11) {
                        keyLeft = actionKeys[2];
                        keyRight = actionKeys[3];
                        keyShiftUp = actionKeys[6];
                        touchControls.updateActionKeys(actionKeys);
                    }
                } catch (Throwable ignored) {}
            }
            touchControls.postDelayed(this,250);
        }
    };
    private static void nativeKey(int scan, boolean down) {
        com.nfsu2.androidevolved.GameActivity.nativeKey(scan, down);
        try { nativeKeyNav(scan, down); } catch (Throwable ignored) {}
    }
    // Reuse the same Escape scan code as the existing HUD Pause control.
    private void pulseGamePauseKey() {
        nativeKey(0x01, true);
        new Handler(Looper.getMainLooper()).postDelayed(() -> nativeKey(0x01, false), 140);
    }
    private static native void nativeKeyNav(int scan, boolean down);
    private static boolean nativeDrivingControls() { return com.nfsu2.androidevolved.GameActivity.nativeDrivingControls(); }

    private void stopAutoShiftController() {
        Handler handler = autoShiftHandler;
        Runnable poll = autoShiftPoll;
        autoShiftHandler = null;
        autoShiftPoll = null;
        if (handler != null && poll != null) handler.removeCallbacks(poll);
        if (autoShiftPressedScan >= 0) {
            nativeKey(autoShiftPressedScan, false);
            autoShiftPressedScan = -1;
        }
    }

    private void startAutoShiftController() {
        stopAutoShiftController();
        ModMenuConfig.Settings config = ModMenuConfig.load(this);
        if (!config.master || !config.autoPerfectShift
                || config.autoShiftTrackId < 0 || config.autoShiftRaceType < 0
                || !io.mininxd.nfsu2.net.RoamNativeBridge.isLoaded()) {
            return;
        }

        final int targetRpm = config.autoShiftRpm;
        final int armedTrack = config.autoShiftTrackId;
        final int armedRaceType = config.autoShiftRaceType;
        final int shiftScan = keyShiftUp > 0 ? keyShiftUp : 0x2a;
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] pendingGear = {-1};
        final long[] pendingSince = {0L};
        final long[] lastShiftAt = {0L};
        final boolean[] waitForRpmDrop = {false};
        final int[] observedGear = {-1};
        final float[] previousRpm = {-1.0f};
        final long[] previousRpmAt = {0L};
        final Runnable[] loop = new Runnable[1];

        loop[0] = () -> {
            if (autoShiftHandler != handler || autoShiftPoll != loop[0]) return;
            if (!foreground || isFinishing() || isDestroyed()) {
                handler.postDelayed(loop[0], 100);
                return;
            }
            try {
                float[] sample = io.mininxd.nfsu2.net.RoamNativeBridge.nativeGetGameTelemetry();
                boolean matchingEvent = sample != null && sample.length >= 13
                        && sample[0] > 0.5f
                        && Math.round(sample[1]) == 6
                        && Math.round(sample[8]) == 4
                        && sample[12] > 0.5f
                        && Math.round(sample[9]) == armedRaceType
                        && Math.round(sample[10]) == armedTrack;

                if (!matchingEvent) {
                    pendingGear[0] = -1;
                    waitForRpmDrop[0] = false;
                    observedGear[0] = -1;
                    previousRpm[0] = -1.0f;
                    previousRpmAt[0] = 0L;
                } else {
                    int gear = Math.round(sample[5]);
                    float rpm = sample[3];
                    float throttle = sample[4];
                    long now = android.os.SystemClock.elapsedRealtime();

                    // Require a real throttle reading. A missing controller value must
                    // never be interpreted as acceleration merely because the car moves.
                    boolean accelerating = throttle >= 0.25f && throttle <= 1.5f
                            && rpm > 0.0f && gear >= 1 && gear < 6;

                    float rpmRisePerMs = 0.0f;
                    if (observedGear[0] != gear) {
                        observedGear[0] = gear;
                        previousRpm[0] = rpm;
                        previousRpmAt[0] = now;
                    } else if (previousRpm[0] > 0.0f) {
                        long sampleDeltaMs = now - previousRpmAt[0];
                        if (sampleDeltaMs >= 8L && sampleDeltaMs <= 250L) {
                            rpmRisePerMs = (rpm - previousRpm[0]) / (float) sampleDeltaMs;
                        }
                        previousRpm[0] = rpm;
                        previousRpmAt[0] = now;
                    } else {
                        previousRpm[0] = rpm;
                        previousRpmAt[0] = now;
                    }

                    // Predict only one polling interval ahead. This compensates for
                    // the 16 ms sampling cadence without guessing a long input latency.
                    float predictedRpm = rpm + Math.max(0.0f, rpmRisePerMs) * 16.0f;
                    predictedRpm = Math.min(predictedRpm, rpm + 500.0f);

                    if (pendingGear[0] >= 1 && gear > pendingGear[0]) {
                        pendingGear[0] = -1;
                        waitForRpmDrop[0] = true;
                    } else if (pendingGear[0] >= 1 && now - pendingSince[0] > 1200L) {
                        // The game did not accept the input: do not spam Shift-Up.
                        pendingGear[0] = -1;
                        waitForRpmDrop[0] = true;
                    }

                    if (waitForRpmDrop[0] && rpm < targetRpm * 0.82f) {
                        waitForRpmDrop[0] = false;
                    }

                    boolean shiftPointReached = rpm >= targetRpm
                            || (rpm >= targetRpm * 0.90f && predictedRpm >= targetRpm);
                    if (pendingGear[0] < 0 && !waitForRpmDrop[0]
                            && accelerating && shiftPointReached
                            && now - lastShiftAt[0] >= 450L) {
                        pendingGear[0] = gear;
                        pendingSince[0] = now;
                        lastShiftAt[0] = now;
                        nativeKey(shiftScan, true);
                        autoShiftPressedScan = shiftScan;
                        handler.postDelayed(() -> {
                            nativeKey(shiftScan, false);
                            if (autoShiftPressedScan == shiftScan) autoShiftPressedScan = -1;
                        }, 65L);
                    }
                }
            } catch (Throwable error) {
                android.util.Log.w("NFSU2-ModMenu", "Auto Shift telemetry: " + error.getClass().getSimpleName());
            }
            handler.postDelayed(loop[0], 16L);
        };

        autoShiftHandler = handler;
        autoShiftPoll = loop[0];
        handler.post(loop[0]);
    }
    private static boolean nativeGamepadUsed() { return com.nfsu2.androidevolved.GameActivity.nativeGamepadUsed(); }
    private static void nativeTypeText(String value, boolean replace) { com.nfsu2.androidevolved.GameActivity.nativeTypeText(value, replace); }
    private static void nativePointer(float x, float y, int phase) { com.nfsu2.androidevolved.GameActivity.nativePointer(x, y, phase); }
    private static void nativeLanguage(String language) { com.nfsu2.androidevolved.GameActivity.nativeLanguage(language); }
    private static void nativeResolution(int width, int height) { com.nfsu2.androidevolved.GameActivity.nativeResolution(width, height); }
    private static void nativeFrameLimit(int framesPerSecond) {
        try {
            com.nfsu2.androidevolved.GameActivity.nativeFrameLimit(framesPerSecond);
        } catch (Throwable ignored) {}
        try {
            android.system.Os.setenv("NFSU2_FPS", String.valueOf(framesPerSecond), true);
        } catch (Throwable ignored) {}
        try {
            if (io.mininxd.nfsu2.net.RoamNativeBridge.isLoaded()) {
                io.mininxd.nfsu2.net.RoamNativeBridge.nativeSetFrameLimit(framesPerSecond);
            }
        } catch (Throwable ignored) {}
    }
    private static void nativeWidescreen(boolean enabled) { com.nfsu2.androidevolved.GameActivity.nativeWidescreen(enabled); }
    private static void nativeScreenMode(int mode) {
        try { com.nfsu2.androidevolved.GameActivity.nativeScreenMode(mode); }
        catch (Throwable t) { android.util.Log.w("GameActivity", "nativeScreenMode: " + t.getMessage()); }
    }
    private static void nativeDrawDistance(float distance) {
        try {
            com.nfsu2.androidevolved.GameActivity.nativeDrawDistance(distance);
        } catch (Throwable ignored) {}
    }
    private static void nativeOnlineServer(String host) {
        try {
            com.nfsu2.androidevolved.GameActivity.nativeOnlineServer(host);
        } catch (Throwable t) {
            android.util.Log.w("GameActivity", "nativeOnlineServer: " + t.getMessage());
        }
    }
    private static void nativeGamepadConnected(boolean connected) { com.nfsu2.androidevolved.GameActivity.nativeGamepadConnected(connected); }
    private static void nativeGamepadDevices(int[] ids) { com.nfsu2.androidevolved.GameActivity.nativeGamepadDevices(ids); }
    private static void nativeMinimapTop(boolean top) { com.nfsu2.androidevolved.GameActivity.nativeMinimapTop(top); }
    private static void nativeGamepadLayout(boolean playstationNames) {
        try {
            com.nfsu2.androidevolved.GameActivity.nativeGamepadLayout(playstationNames);
            android.util.Log.i("NFSU2", playstationNames
                    ? "Gamepad labels: PS2 Cross Circle Square Triangle L1 L2 L3"
                    : "Gamepad labels: Xbox A B X Y LB RB");
        } catch (UnsatisfiedLinkError missing) {
            android.util.Log.w("NFSU2", "Gamepad layout option needs a newer libnfsu2_android.so");
        }
    }
    private static void nativeSpeedometerPosition(int position) {
        try { com.nfsu2.androidevolved.GameActivity.nativeSpeedometerPosition(position); }
        catch (UnsatisfiedLinkError missing) { android.util.Log.w("NFSU2", "Speedometer position needs a newer libnfsu2_android.so"); }
    }
    // The minimap and speedometer moves make room for the touch controls. Both
    // follow them live: original places while a gamepad hides them, the
    // launcher's when a touch shows them (the game rebuilds its HUD).
    private boolean minimapTop;
    private int speedometerPosition;
    private Boolean hudForGamepad;
    private void updateHudForGamepad(boolean gamepad) {
        if (hudForGamepad != null && hudForGamepad == gamepad) return;
        hudForGamepad = gamepad;
        nativeSpeedometerPosition(gamepad ? 0 : speedometerPosition);
        nativeMinimapTop(minimapTop && !gamepad);
    }
    private static void nativeBackBuffers(int count) { com.nfsu2.androidevolved.GameActivity.nativeBackBuffers(count); }
    private static void nativeCpuTextures(boolean enabled) { com.nfsu2.androidevolved.GameActivity.nativeCpuTextures(enabled); }
    // Older libnfsu2_android.so builds have no region switch: keep the game's region.
    private static void nativeRegion(int region) {
        try { com.nfsu2.androidevolved.GameActivity.nativeRegion(region); }
        catch (UnsatisfiedLinkError missing) { android.util.Log.w("NFSU2", "Region option needs a newer libnfsu2_android.so"); }
    }
    private static void nativeGpuDriver(String hooks, String temp, String directory, String library) {
        try {
            com.nfsu2.androidevolved.GameActivity.nativeGpuDriver(hooks, temp, directory, library);
        } catch (Throwable t) {
            android.util.Log.w("GameActivity", "nativeGpuDriver: " + t.getMessage());
        }
    }
    private void showFrameLimitOptions() {
        GameOptions.Option option=GameOptions.find(GameOptions.FPS);
        String saved=GameOptions.get(this,GameOptions.FPS);int selected=0;
        for(int i=0;i<option.values.length;i++)if(option.values[i].equals(saved))selected=i;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(option.title)
                .setSingleChoiceItems(option.labels,selected,(dialog,which)->{
                    GameOptions.set(this,GameOptions.FPS,option.values[which]);
                    nativeFrameLimit(Integer.parseInt(option.values[which]));dialog.dismiss();
                }).setNegativeButton("Cancel",null).show();
    }
    /**
     * Android 11+ grants all-files access per package. Mod Edition has its own application ID,
     * so permission granted to the original NFSU2 app does not automatically carry over.
     */
    private void stopModCalibration() {
        modCalibrationRunning = false;
        if (modCalibrationHandler != null && modCalibrationPoll != null) {
            modCalibrationHandler.removeCallbacks(modCalibrationPoll);
        }
        if (modCalibrationWindow != null && modCalibrationWindow.isShowing()) {
            modCalibrationWindow.dismiss();
        }
        modCalibrationWindow = null;
        modCalibrationHandler = null;
        modCalibrationPoll = null;
        Toast.makeText(this, "Shift calibration stopped.", Toast.LENGTH_SHORT).show();
    }

    private boolean hasModMenuStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showModMenuStorageAccessDialog() {
        String target = ModMenuConfig.file(this).getAbsolutePath();
        if (Build.VERSION.SDK_INT >= 30 && !hasModMenuStorageAccess()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Allow Mod Edition file access")
                    .setMessage("Android gives this permission separately to each app. The original NFSU2 permission does not automatically apply to Mod Edition. Enable \"Allow access to manage all files\" for NFSU2 Revived Mod Edition, return to the game, then tap Save settings again.\n\nTarget: " + target)
                    .setPositiveButton("Open access settings", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception unavailable) {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception ignored) {
                                Toast.makeText(this, "Open Android Settings > Apps > Special app access > All files access.", Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("Later", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Cannot write Mod Menu settings")
                .setMessage("File access is enabled, but Android still could not write the configuration. Check that the game folder exists and is writable. Career data is not modified.\n\nTarget: " + target)
                .setPositiveButton("OK", null)
                .show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showTextInput() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Name, email or password");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        CheckBox replace = new CheckBox(this);
        replace.setText("Replace current name");replace.setChecked(true);
        LinearLayout form = new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);form.addView(name);form.addView(replace);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Type in game")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Type", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = name.getText().toString();
            if (value.isEmpty() || !value.matches("[A-Za-z0-9 .,=/;_+?:!@#$%&*()-]{1,40}")) {
                name.setError("Up to 40 characters: letters, numbers and . , - _ @ + ! # $ % & * ( ) = ? / ; :");
                return;
            }
            nativeTypeText(value, replace.isChecked());
            dialog.dismiss();
        }));
        dialog.show();
        name.requestFocus();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        GameOptions.syncWidescreenIni(this);
        GamePerformanceBooster.apply(this, getWindow());
        PerformanceCores.bindToPerformanceCores();
        GameLog.init(this);
        io.mininxd.nfsu2.net.NetLog.init(this);
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        tiltSensors=(android.hardware.SensorManager)getSystemService(SENSOR_SERVICE);
        tiltSensor=tiltSensors==null?null:tiltSensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        android.content.SharedPreferences options=getSharedPreferences("touch_layouts_v1",MODE_PRIVATE);
        tiltEnabled=options.getBoolean("tilt.enabled",false);tiltInvert=options.getBoolean("tilt.invert",false);
        tiltSensitivity=options.getFloat("tilt.sensitivity",1);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.view.Display display=getWindowManager().getDefaultDisplay();
        android.view.Display.Mode current=display.getMode();float refresh=60;
        android.view.Display.Mode bestMode=null;
        for(android.view.Display.Mode mode:display.getSupportedModes()) {
            if(mode.getPhysicalWidth()==current.getPhysicalWidth()&&mode.getPhysicalHeight()==current.getPhysicalHeight()) {
                refresh=Math.max(refresh,mode.getRefreshRate());
                if(bestMode==null||mode.getRefreshRate()>bestMode.getRefreshRate()) {
                    bestMode=mode;
                }
            }
        }
        android.view.WindowManager.LayoutParams windowParams=getWindow().getAttributes();
        windowParams.preferredRefreshRate=Math.min(120,refresh);
        if(Build.VERSION.SDK_INT>=23&&bestMode!=null) {
            windowParams.preferredDisplayModeId=bestMode.getModeId();
        }
        try {
            java.lang.reflect.Field minField = windowParams.getClass().getField("preferredMinDisplayRefreshRate");
            minField.setFloat(windowParams, Math.min(60.0f, refresh));
            java.lang.reflect.Field maxField = windowParams.getClass().getField("preferredMaxDisplayRefreshRate");
            maxField.setFloat(windowParams, Math.min(120.0f, refresh));
        } catch (Throwable ignored) {}
        getWindow().setAttributes(windowParams);
        Intent launch = getIntent();
        float distVal = 450.0f;
        try {
            String menuScale = GameOptions.get(this, GameOptions.MENU_BUTTON_SIZE);
            if ("auto".equals(menuScale) || "100".equals(menuScale)) android.system.Os.setenv("NFSU2_MENU_SCALE", "1.0", true);
            else if ("125".equals(menuScale)) android.system.Os.setenv("NFSU2_MENU_SCALE", "1.25", true);
            else if ("150".equals(menuScale)) android.system.Os.setenv("NFSU2_MENU_SCALE", "1.5", true);
            else if ("175".equals(menuScale)) android.system.Os.setenv("NFSU2_MENU_SCALE", "1.75", true);
            else if ("200".equals(menuScale)) android.system.Os.setenv("NFSU2_MENU_SCALE", "2.0", true);
            else android.system.Os.setenv("NFSU2_MENU_SCALE", menuScale, true);

            try {
                android.system.Os.setenv("NFSU2_DATA_DIR", LauncherActivity.gameRoot().getAbsolutePath(), true);
            } catch (Throwable ignored) {}

            String horn = GameOptions.get(this, GameOptions.HORN);
            android.system.Os.setenv("NFSU2_HORN", horn, true);

            boolean hiddenRaces = GameOptions.isCheatEnabled(this, "hidden_races");
            android.system.Os.setenv("NFSU2_SHOW_HIDDEN_RACES", hiddenRaces ? "1" : "0", true);

            android.system.Os.setenv("NFSU2_AUTOSAVE", "1", true);

            android.system.Os.setenv("NFSU2_INTRO", "original", true);
            android.system.Os.setenv("NFSU2_CUTSCENE_BORDERS", "full", true);

            String roamMaxCars = "4"; // default (hidden option)
            android.system.Os.setenv("NFSU2_ROAM_MAX_CARS", roamMaxCars, true);

            String crew = launch.getStringExtra("roamCrew");
            if (crew == null) crew = GameOptions.roamCrew(this);
            if (crew != null && !crew.trim().isEmpty()) {
                android.system.Os.setenv("NFSU2_ROAM_CREW", crew.trim(), true);
            }

            String backend = launch.getStringExtra("graphicsBackend");
            if (backend == null || "auto".equalsIgnoreCase(backend)) backend = GameOptions.getEffectiveGraphicsBackend(this);
            android.system.Os.setenv("NFSU2_RENDERER", backend, true);

            String drawDist = GameOptions.get(this, GameOptions.DRAW_DISTANCE);
            distVal = 450.0f;
            if ("performance".equals(drawDist)) distVal = 320.0f;
            else if ("extended".equals(drawDist)) distVal = 650.0f;
            else if ("original".equals(drawDist)) distVal = 0.0f;
            android.system.Os.setenv("NFSU2_DRAW_DISTANCE", String.valueOf((int)distVal), true);

            android.system.Os.setenv("NFSU2_CROWD", "0", true);
            GraphicsCacheManager.setCrowdSuppression(true);

            // Sync touch control size
            String touchSize = GameOptions.get(this, GameOptions.TOUCH_SIZE);
            getSharedPreferences("touch_layouts_v2", MODE_PRIVATE)
                    .edit().putString("control_size", touchSize).apply();
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            windowParams.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setAttributes(windowParams);
        // Launcher options travel as extras; a direct start (adb, recents) falls back to the saved ones.
        DisplayMetrics metrics = new DisplayMetrics(); getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int dispW = Math.max(metrics.widthPixels, metrics.heightPixels);
        int dispH = Math.min(metrics.widthPixels, metrics.heightPixels);
        int[] size = GameOptions.renderSize(this, dispW, dispH);
        String resPercent = launch.getStringExtra("resolutionPercent");
        if (resPercent == null) resPercent = GameOptions.get(this, GameOptions.RESOLUTION);
        try {
            android.system.Os.setenv("NFSU2_DISPLAY_WIDTH", String.valueOf(dispW), true);
            android.system.Os.setenv("NFSU2_DISPLAY_HEIGHT", String.valueOf(dispH), true);
            android.system.Os.setenv("NFSU2_RES_PERCENT", resPercent, true);
        } catch (Throwable ignored) {}
        nativeResolution(launch.getIntExtra("renderWidth", size[0]), launch.getIntExtra("renderHeight", size[1]));
        String displayLogs = launch.getStringExtra("displayLogs");
        if (displayLogs == null) displayLogs = GameOptions.get(this, GameOptions.DISPLAY_LOGS);
        GameOptions.applyWidescreenPatchFile(this);
        if (GameOptions.usesWidescreenPatch(this))
            GameOptions.syncWidescreenHudDat(this, !"off".equalsIgnoreCase(displayLogs));
        String language = launch.getStringExtra("language");
        if (language == null || "English".equalsIgnoreCase(language)) {
            language = GameOptions.language(this);
        }
        if ("English".equalsIgnoreCase(language)) {
            language = "English UK";
        }
        nativeLanguage(language);
        nativeFrameLimit(launch.getIntExtra("frameCap", Integer.parseInt(GameOptions.get(this, GameOptions.FPS))));
        int screenMode = launch.getIntExtra("widescreenMode", GameOptions.widescreenMode(this));
        nativeWidescreen(true);             // always full screen, never stretched
        nativeScreenMode(screenMode);       // 0=Widescreen HUD edges, 2=4:3 HUD centre
        nativeDrawDistance(distVal);
        String server = launch.getStringExtra("onlineServer");
        String configuredServer = GameOptions.validServer(server) ? server : GameOptions.onlineServer(this);
        boolean useLocalBridge = io.mininxd.nfsu2.net.EahubClassicOnline.shouldUseLocalBridge(configuredServer);
        if (useLocalBridge) {
            // Modern nfsu2-web server: tunnel DirtySock through LocalOnlineBridge WebSocket
            nativeOnlineServer("127.0.0.1");
        } else {
            // Classic server (eahub.eu or custom): gethostbyname in guest_network.inc
            // redirects all *.ea.com DNS lookups to this host directly over plain TCP.
            String eaNationServer = GameOptions.isEahubClassic(configuredServer) ? "eahub.eu" : configuredServer;
            nativeOnlineServer(eaNationServer);
        }
        io.mininxd.nfsu2.net.EahubClassicOnline.logStartup(this, configuredServer, !useLocalBridge);
        minimapTop = launch.getBooleanExtra("minimapTop", GameOptions.get(this, GameOptions.MINIMAP).equals("top"));
        speedometerPosition = launch.getIntExtra("speedometer", GameOptions.speedometerPosition(this));
        updateHudForGamepad(false);
        nativeGamepadLayout(launch.getBooleanExtra("consolePad", GameOptions.isConsolePad(this)));
        nativeBackBuffers(launch.getIntExtra("backBuffers", 2));
        nativeCpuTextures(launch.getBooleanExtra("cpuTextures", false));
        nativeRegion(launch.getIntExtra("region", GameOptions.regionCode(this)));
        String[] loader = launch.getStringArrayExtra("driverLoader");
        if (loader == null || loader.length != 4) loader = GpuDrivers.loaderArguments(this);
        nativeGpuDriver(loader[0], loader[1], loader[2], loader[3]);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xb0000000);
        status.setTextSize(18);
        status.setPadding(24, 16, 24, 16);
        status.setText("NFSU2 Revived\nChecking internal storage/nfsu2…");
        // NativeActivity gives its main window surface to Vulkan; use a separate
        // non-interactive popup surface for the diagnostic text.
        statusWindow = new PopupWindow(status, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        statusWindow.setTouchable(false);
        touchControls = new RacingControlsView(this, this::setTouchKey, this::showTextInput, this::showTiltOptions, this::showFrameLimitOptions, this::showModMenu);
        touchControls.setPointer(GameActivity::nativePointer);
        touchControls.setOnEditingChangeListener(editing -> {
            if (diagnosticOverlay != null) {
                diagnosticOverlay.setSuspended(editing);
            }
        });
        controlsWindow = new PopupWindow(touchControls, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, false);
        controlsWindow.setClippingEnabled(false);
        java.util.ArrayList<String> cheats = launch.getStringArrayListExtra("cheats");
        if (cheats == null) cheats = GameOptions.getActiveCheatCodes(this);
        if (!cheats.isEmpty()) applyCheatCodes(cheats);

        boolean isWs = useLocalBridge;  // only WS-based servers need Adhoc via LocalOnlineBridge
        boolean roamEnabled = launch.getBooleanExtra("roamEnabled", GameOptions.isRoamEnabled(this));
        if (roamEnabled || isWs) {
            String roamServer = launch.getStringExtra("roamServer");
            if (roamServer == null || roamServer.isEmpty()) roamServer = GameOptions.roamServer(this);
            else if (GameOptions.isEahubClassic(roamServer)) roamServer = GameOptions.DEFAULT_ROAM_SERVER;
            String roamRoom = launch.hasExtra("roamRoom") ? launch.getStringExtra("roamRoom") : GameOptions.roamRoom(this);
            if (roamRoom == null) roamRoom = "";
            String roamName = launch.getStringExtra("roamName");
            if (roamName == null || roamName.isEmpty()) roamName = GameOptions.roamName(this);
            boolean ghostMode = launch.getBooleanExtra("roamGhost", GameOptions.isGhostModeEnabled(this));
            AdhocRoamClient.getInstance().start(this, roamServer, roamRoom, roamName, ghostMode);
        }

        diagnosticOverlay = new DiagnosticOverlay(this, displayLogs);
        if (diagnosticOverlay.isEnabled()) {
            diagnosticOverlay.start();
        }
        startCompanionTicker();
    }

    private Thread mCompanionTickerThread;
    private final java.util.concurrent.atomic.AtomicBoolean mCompanionRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    private void startCompanionTicker() {
        // Native roam companion is ticked synchronously per-frame in guest_runtime.cpp at Direct3D9 Present.
    }

    private void stopCompanionTicker() {
    }

    /** Opens the in-game Mod Menu status panel. Runtime cheats remain unavailable until verified hooks are implemented. */
    private void showModMenu() {
        if (isFinishing() || isDestroyed()) return;
        // If sampling is active, stop it first, then continue opening the paused Mod Menu.
        if (modCalibrationRunning) stopModCalibration();
        stopAutoShiftController();
        runOnUiThread(() -> {
            // Capture the event before pausing, so Auto Shift can be scoped to this exact Drag event.
            float[] openedEvent = null;
            try {
                if (io.mininxd.nfsu2.net.RoamNativeBridge.isLoaded()) {
                    openedEvent = io.mininxd.nfsu2.net.RoamNativeBridge.nativeGetGameTelemetry();
                }
            } catch (Throwable ignored) {}
            final boolean[] activeEventAtOpen = {openedEvent != null && openedEvent.length >= 13
                    && openedEvent[0] > 0.5f && Math.round(openedEvent[1]) == 6
                    && Math.round(openedEvent[8]) == 4 && openedEvent[12] > 0.5f};
            final int[] eventTrackAtOpen = {activeEventAtOpen[0] ? Math.round(openedEvent[10]) : -1};
            final int[] eventTypeAtOpen = {activeEventAtOpen[0] ? Math.round(openedEvent[9]) : -1};
            final TextView[] autoShiftScopeLabel = {null};

            // Pause through the same Escape key used by the in-game HUD. Do not
            // inject Escape on event lobby/results screens: there it acts as Back.
            modMenuPausedGameplay = nativeDrivingControls();
            if (modMenuPausedGameplay) {
                pulseGamePauseKey();
            }
            ModMenuConfig.Settings settings = ModMenuConfig.load(this);
            LinearLayout form = new LinearLayout(this);
            form.setOrientation(LinearLayout.VERTICAL);
            form.setPadding(dp(20), dp(8), dp(20), dp(8));

            TextView status = new TextView(this);
            status.setText(modMenuPausedGameplay
                    ? "Gameplay paused while this menu is open. Experimental Auto Shift can run only for an armed event; other gameplay cheats are still pending."
                    : "Not in driving mode: pause was skipped to avoid backing out of a menu/results screen. Start the intended Drag event and open MOD while driving to arm Auto Shift.");
            status.setTextSize(13);
            form.addView(status);

            TextView telemetryText = new TextView(this);
            telemetryText.setText("Live telemetry: tap Read while driving in-game.");
            telemetryText.setTextSize(12);
            telemetryText.setPadding(0, dp(8), 0, dp(2));
            form.addView(telemetryText);

            android.widget.Button readTelemetryButton = new android.widget.Button(this);
            readTelemetryButton.setText("Read live telemetry");
            readTelemetryButton.setAllCaps(false);
            readTelemetryButton.setOnClickListener(button -> {
                try {
                    if (!io.mininxd.nfsu2.net.RoamNativeBridge.isLoaded()) {
                        telemetryText.setText("Telemetry bridge is not loaded.");
                        return;
                    }
                    float[] t = io.mininxd.nfsu2.net.RoamNativeBridge.nativeGetGameTelemetry();
                    if (t == null || t.length < 13) {
                        telemetryText.setText("Telemetry snapshot unavailable or bridge is outdated. Reinstall the latest Mod Edition build.");
                        return;
                    }
                    int flow = Math.round(t[1]);
                    int raceState = Math.round(t[8]);
                    int raceType = Math.round(t[9]);
                    int track = Math.round(t[10]);
                    // Let Read Live Telemetry refresh the event scope as well as the
                    // snapshot. The event may become fully readable after the menu opens.
                    boolean liveActiveEvent = t[0] > 0.5f && flow == 6
                            && raceState == 4 && t[12] > 0.5f && raceType >= 0 && track >= 0;
                    if (liveActiveEvent) {
                        activeEventAtOpen[0] = true;
                        eventTrackAtOpen[0] = track;
                        eventTypeAtOpen[0] = raceType;
                        if (autoShiftScopeLabel[0] != null) {
                            autoShiftScopeLabel[0].setText("Active event detected: race type " + raceType
                                    + " | track " + track + ". Saving Auto Shift will arm it only for this event.");
                        }
                    }
                    String playerPointer = t[6] > 0.5f ? "OK" : "missing";
                    String carPointer = t[7] > 0.5f ? "OK" : "missing";
                    if (t[0] < 0.5f) {
                        String diagnostics = t.length >= 23
                                ? String.format(java.util.Locale.US,
                                        "\nFrame hooks %d | JNI reads %d | base symbol %s"
                                                + "\nGame memory %s | ESP symbol %s | Flow %s | Player slot %s",
                                        Math.round(t[13]), Math.round(t[14]),
                                        t[20] > 0.5f ? "found" : "missing",
                                        t[21] > 0.5f ? "mapped" : "not mapped",
                                        t[22] > 0.5f ? "found" : "missing",
                                        t[17] > 0.5f ? "readable" : "unmapped",
                                        t[18] > 0.5f ? "readable" : "unmapped")
                                : "\nNative diagnostics unavailable.";
                        telemetryText.setText(String.format(java.util.Locale.US,
                                "No valid car snapshot | Flow %d | Player* %s | Car* %s | Race state %s | Type %d | Track %d%s",
                                flow, playerPointer, carPointer,
                                raceState < 0 ? "n/a" : String.valueOf(raceState), raceType, track,
                                diagnostics));
                        return;
                    }
                    int rawGear = Math.round(t[5]);
                    String gearLabel = rawGear < 0 ? "R" : (rawGear == 0 ? "N" : String.valueOf(rawGear));
                    String throttle = t[4] < 0.0f
                            ? "n/a"
                            : String.format(java.util.Locale.US, "%.0f%%", t[4] * 100.0f);
                    String raceLabel = t[12] > 0.5f ? "RACE ACTIVE" : "not in active race";
                    String diagnostics = t.length >= 23
                            ? String.format(java.util.Locale.US,
                                    "\nFrame hooks %d | JNI reads %d | base symbol %s"
                                            + "\nGame memory %s | ESP symbol %s | Flow %s | Player slot %s",
                                    Math.round(t[13]), Math.round(t[14]),
                                    t[20] > 0.5f ? "found" : "missing",
                                    t[21] > 0.5f ? "mapped" : "not mapped",
                                    t[22] > 0.5f ? "found" : "missing",
                                    t[17] > 0.5f ? "readable" : "unmapped",
                                    t[18] > 0.5f ? "readable" : "unmapped")
                            : "";
                    telemetryText.setText(String.format(java.util.Locale.US,
                            "Live: %.0f RPM | Gear %s | %.0f km/h | Throttle %s\nFlow %d | Race state %s | Type %d | Track %d | %s%s",
                            t[3], gearLabel, t[2] * 3.6f, throttle,
                            flow, raceState < 0 ? "n/a" : String.valueOf(raceState),
                            raceType, track, raceLabel, diagnostics));
                } catch (Throwable error) {
                    telemetryText.setText("Telemetry read failed: " + error.getClass().getSimpleName());
                }
            });
            form.addView(readTelemetryButton);

            TextView calibrationText = new TextView(this);
            calibrationText.setText("Calibration runs over the race screen. Gas and game controls must remain usable; accelerate and manually upshift several times, then tap Stop.");
            calibrationText.setTextSize(12);
            calibrationText.setPadding(0, dp(6), 0, dp(2));
            form.addView(calibrationText);

            android.widget.Button calibrationButton = new android.widget.Button(this);
            calibrationButton.setText("Start shift calibration");
            calibrationButton.setAllCaps(false);
            form.addView(calibrationButton);

            final android.os.Handler calibrationHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            modCalibrationHandler = calibrationHandler;
            final boolean[] calibrationRunning = {false};
            final int[] previousGear = {-1};
            final float[] peakRpm = {0f};
            final java.util.ArrayList<Integer> observedShiftPeaks = new java.util.ArrayList<>();
            final Runnable[] calibrationPoll = new Runnable[1];
            final PopupWindow[] calibrationPopup = {null};
            final TextView[] calibrationOverlayText = {null};
            final Runnable stopCalibration = () -> {
                calibrationRunning[0] = false;
                modCalibrationRunning = false;
                calibrationHandler.removeCallbacks(calibrationPoll[0]);
                if (calibrationPopup[0] != null && calibrationPopup[0].isShowing()) {
                    calibrationPopup[0].dismiss();
                }
                calibrationPopup[0] = null;
                modCalibrationWindow = null;
                modCalibrationHandler = null;
                modCalibrationPoll = null;
                calibrationOverlayText[0] = null;
                if (observedShiftPeaks.isEmpty()) {
                    Toast.makeText(this, "No upshift samples captured. Calibration stopped.", Toast.LENGTH_LONG).show();
                } else {
                    java.util.ArrayList<Integer> sortedPeaks = new java.util.ArrayList<>(observedShiftPeaks);
                    java.util.Collections.sort(sortedPeaks);
                    int min = sortedPeaks.get(0);
                    int max = sortedPeaks.get(sortedPeaks.size() - 1);
                    int mid = sortedPeaks.size() / 2;
                    int learnedRpm = sortedPeaks.size() % 2 == 0
                            ? (sortedPeaks.get(mid - 1) + sortedPeaks.get(mid)) / 2
                            : sortedPeaks.get(mid);
                    learnedRpm = Math.max(ModMenuConfig.MIN_SHIFT_RPM,
                            Math.min(ModMenuConfig.MAX_SHIFT_RPM, learnedRpm));
                    ModMenuConfig.Settings learned = ModMenuConfig.load(this);
                    learned.autoShiftRpm = learnedRpm;
                    boolean saved = ModMenuConfig.save(this, learned);
                    String message = String.format(java.util.Locale.US,
                            saved
                                    ? "Calibration saved: %d manual shifts | observed %d–%d RPM | Auto Shift target %d RPM"
                                    : "Captured %d manual shifts | observed %d–%d RPM | settings could not be saved",
                            observedShiftPeaks.size(), min, max, learnedRpm);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
                new Handler(Looper.getMainLooper()).postDelayed(this::startAutoShiftController, 240L);
            };
            calibrationPoll[0] = () -> {
                if (!calibrationRunning[0]) return;
                try {
                    if (!io.mininxd.nfsu2.net.RoamNativeBridge.isLoaded()) {
                        calibrationText.setText("Calibration stopped: telemetry bridge unavailable.");
                        stopCalibration.run();
                        return;
                    }
                    float[] sample = io.mininxd.nfsu2.net.RoamNativeBridge.nativeGetGameTelemetry();
                    if (sample == null || sample.length < 13) {
                        previousGear[0] = -1;
                        peakRpm[0] = 0f;
                        String waiting = "Telemetry bridge is outdated. Reinstall the latest build.";
                        calibrationText.setText(waiting);
                        if (calibrationOverlayText[0] != null) calibrationOverlayText[0].setText(waiting);
                    } else if (sample[0] < 0.5f || sample[12] < 0.5f) {
                        previousGear[0] = -1;
                        peakRpm[0] = 0f;
                        String waiting = sample[0] < 0.5f
                                ? String.format(java.util.Locale.US,
                                    "No car snapshot | Flow %d | Player* %s | Car* %s",
                                    Math.round(sample[1]), sample[6] > 0.5f ? "OK" : "missing",
                                    sample[7] > 0.5f ? "OK" : "missing")
                                : String.format(java.util.Locale.US,
                                    "Car found, waiting for active race | Flow %d | Race state %d | Type %d | Track %d",
                                    Math.round(sample[1]), Math.round(sample[8]),
                                    Math.round(sample[9]), Math.round(sample[10]));
                        calibrationText.setText(waiting);
                        if (calibrationOverlayText[0] != null) calibrationOverlayText[0].setText(waiting);
                    } else {
                        int gear = Math.round(sample[5]);
                        float currentRpm = sample[3];
                        float currentThrottle = sample[4];
                        boolean loadedThrottle = currentThrottle >= 0.25f && currentThrottle <= 1.5f;
                        if (previousGear[0] < 0) {
                            previousGear[0] = gear;
                            peakRpm[0] = loadedThrottle ? currentRpm : 0f;
                        } else if (gear <= 0) {
                            // Reverse/neutral is not a shift sample; start a fresh baseline.
                            previousGear[0] = gear;
                            peakRpm[0] = 0f;
                        } else if (gear > previousGear[0]) {
                            // Only learn a pre-shift peak recorded while the driver was
                            // actually applying throttle; idle/starting-grid changes do not count.
                            if (previousGear[0] >= 1 && peakRpm[0] >= ModMenuConfig.MIN_SHIFT_RPM) {
                                observedShiftPeaks.add(Math.round(peakRpm[0]));
                                while (observedShiftPeaks.size() > 8) observedShiftPeaks.remove(0);
                            }
                            previousGear[0] = gear;
                            peakRpm[0] = loadedThrottle ? currentRpm : 0f;
                        } else if (gear != previousGear[0]) {
                            previousGear[0] = gear;
                            peakRpm[0] = loadedThrottle ? currentRpm : 0f;
                        } else if (loadedThrottle) {
                            // Keep the highest loaded RPM while this gear is engaged.
                            peakRpm[0] = Math.max(peakRpm[0], currentRpm);
                        }
                        int shownThrottle = currentThrottle < 0f ? -1 : Math.round(currentThrottle * 100f);
                        String captureState = !loadedThrottle
                                ? String.format(java.util.Locale.US,
                                    "Waiting for throttle | Gear %d | %.0f RPM | Throttle %s | %d shifts",
                                    gear, currentRpm, shownThrottle < 0 ? "n/a" : shownThrottle + "%",
                                    observedShiftPeaks.size())
                                : String.format(java.util.Locale.US,
                                    "Capturing | Gear %d | %.0f RPM | Throttle %d%% | %d shifts",
                                    gear, peakRpm[0], shownThrottle, observedShiftPeaks.size());
                        calibrationText.setText(captureState);
                        if (calibrationOverlayText[0] != null) calibrationOverlayText[0].setText(captureState);
                        if (!observedShiftPeaks.isEmpty()) {
                            int min = Integer.MAX_VALUE, max = 0, total = 0;
                            for (int value : observedShiftPeaks) {
                                min = Math.min(min, value);
                                max = Math.max(max, value);
                                total += value;
                            }
                            String captured = String.format(java.util.Locale.US,
                                    "Captured %d shifts | peak %d–%d RPM | avg %d RPM | Gear %d | %.0f RPM | Throttle %s",
                                    observedShiftPeaks.size(), min, max, total / observedShiftPeaks.size(),
                                    gear, currentRpm,
                                    shownThrottle < 0 ? "n/a" : shownThrottle + "%");
                            calibrationText.setText(captured);
                            if (calibrationOverlayText[0] != null) calibrationOverlayText[0].setText(captured);
                        }
                    }
                } catch (Throwable error) {
                    calibrationText.setText("Calibration read failed: " + error.getClass().getSimpleName());
                }
                if (calibrationRunning[0]) calibrationHandler.postDelayed(calibrationPoll[0], 20);
            };
            modCalibrationPoll = calibrationPoll[0];

            CheckBox master = new CheckBox(this);
            master.setText("Master Cheat — enable saved mod options");
            master.setChecked(settings.master);
            form.addView(master);

            CheckBox autoShift = new CheckBox(this);
            autoShift.setText("Auto Shift — this Drag event only (experimental RPM trigger)");
            autoShift.setChecked(settings.autoPerfectShift);
            form.addView(autoShift);

            TextView autoShiftScope = new TextView(this);
            if (activeEventAtOpen[0]) {
                autoShiftScope.setText("Active event detected: race type " + eventTypeAtOpen[0]
                        + " | track " + eventTrackAtOpen[0]
                        + ". Saving Auto Shift will arm it only for this event. Open MOD while driving in the Drag event.");
            } else if (settings.autoShiftTrackId >= 0) {
                autoShiftScope.setText("Auto Shift is armed for race type " + settings.autoShiftRaceType
                        + " | track " + settings.autoShiftTrackId
                        + ". To change its event, open MOD while driving in the intended Drag event.");
            } else {
                autoShiftScope.setText("To arm Auto Shift: start a Drag race, wait until the car is driving, then open MOD and save with Auto Shift ON.");
            }
            autoShiftScope.setTextSize(12);
            autoShiftScope.setPadding(0, dp(2), 0, dp(4));
            autoShiftScopeLabel[0] = autoShiftScope;
            form.addView(autoShiftScope);

            TextView rpmLabel = new TextView(this);
            rpmLabel.setText("Auto Shift target: " + settings.autoShiftRpm + " RPM");
            rpmLabel.setPadding(0, dp(4), 0, 0);
            form.addView(rpmLabel);

            android.widget.SeekBar rpm = new android.widget.SeekBar(this);
            rpm.setMax(ModMenuConfig.MAX_SHIFT_RPM - ModMenuConfig.MIN_SHIFT_RPM);
            rpm.setProgress(settings.autoShiftRpm - ModMenuConfig.MIN_SHIFT_RPM);
            rpm.setEnabled(settings.autoPerfectShift);
            rpm.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                    rpmLabel.setText("Auto Shift target: "
                            + (ModMenuConfig.MIN_SHIFT_RPM + progress) + " RPM");
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
            });
            autoShift.setOnCheckedChangeListener((button, checked) -> {
                rpm.setEnabled(checked);
                if (checked && !master.isChecked()) master.setChecked(true);
            });
            form.addView(rpm);

            CheckBox infiniteNos = new CheckBox(this);
            infiniteNos.setText("Infinite NOS — hook pending");
            infiniteNos.setChecked(settings.infiniteNos);
            form.addView(infiniteNos);

            CheckBox raceAssist = new CheckBox(this);
            raceAssist.setText("Race Assist — hook pending");
            raceAssist.setChecked(settings.raceAssist);
            form.addView(raceAssist);

            CheckBox perfectDrift = new CheckBox(this);
            perfectDrift.setText("Perfect Drift — hook pending");
            perfectDrift.setChecked(settings.perfectDrift);
            form.addView(perfectDrift);

            TextView filePath = new TextView(this);
            filePath.setText("Settings file (separate from Career save):\n"
                    + ModMenuConfig.file(this).getAbsolutePath());
            filePath.setTextSize(11);
            filePath.setPadding(0, dp(8), 0, 0);
            form.addView(filePath);

            TextView safety = new TextView(this);
            safety.setText("Auto Shift sends the mapped Shift Up key only when the armed event, gear, RPM target and throttle checks match. Other cheats are not implemented. Settings are separate from Career data; no game memory values are overwritten.");
            safety.setTextSize(12);
            safety.setPadding(0, dp(8), 0, 0);
            form.addView(safety);

            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.addView(form);

            // Custom bottom action bar: All OFF | centered author watermark | Close / Save.
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(dp(8), dp(4), dp(8), dp(4));

            android.widget.Button allOffButton = new android.widget.Button(this);
            allOffButton.setText("All OFF");
            allOffButton.setAllCaps(false);
            allOffButton.setMinWidth(0);
            allOffButton.setPadding(dp(8), 0, dp(8), 0);
            footer.addView(allOffButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView watermark = new TextView(this);
            watermark.setText("Yogie Clharava");
            watermark.setTextSize(11);
            watermark.setTextColor(0x77808080);
            watermark.setGravity(Gravity.CENTER);
            watermark.setTypeface(android.graphics.Typeface.create(
                    "sans-serif", android.graphics.Typeface.BOLD_ITALIC));
            footer.addView(watermark, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.Button closeButton = new android.widget.Button(this);
            closeButton.setText("Close");
            closeButton.setAllCaps(false);
            closeButton.setMinWidth(0);
            closeButton.setPadding(dp(8), 0, dp(8), 0);
            footer.addView(closeButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            android.widget.Button saveButton = new android.widget.Button(this);
            saveButton.setText("Save settings");
            saveButton.setAllCaps(false);
            saveButton.setMinWidth(0);
            saveButton.setPadding(dp(8), 0, dp(8), 0);
            footer.addView(saveButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            root.addView(footer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("NFSU2 Revived — Mod Menu")
                    .setView(root)
                    .create();
            calibrationButton.setOnClickListener(button -> {
                if (calibrationRunning[0]) {
                    stopCalibration.run();
                    return;
                }
                try {
                    if (!io.mininxd.nfsu2.net.RoamNativeBridge.isLoaded()) {
                        calibrationText.setText("Telemetry bridge unavailable. Calibration was not started.");
                        Toast.makeText(this, "Telemetry bridge unavailable.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    float[] initial = io.mininxd.nfsu2.net.RoamNativeBridge.nativeGetGameTelemetry();
                    if (initial == null || initial.length < 13) {
                        String reason = "Telemetry bridge is outdated. Reinstall the latest Mod Edition build.";
                        calibrationText.setText(reason);
                        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (initial[0] < 0.5f) {
                        String reason = String.format(java.util.Locale.US,
                                "No car snapshot | Flow %d | Player* %s | Car* %s",
                                Math.round(initial[1]), initial[6] > 0.5f ? "OK" : "missing",
                                initial[7] > 0.5f ? "OK" : "missing");
                        calibrationText.setText(reason);
                        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (initial[12] < 0.5f) {
                        String reason = String.format(java.util.Locale.US,
                                "Car found, but race is not active | Flow %d | Race state %d | Type %d | Track %d. Start the Drag event and wait until driving begins.",
                                Math.round(initial[1]), Math.round(initial[8]),
                                Math.round(initial[9]), Math.round(initial[10]));
                        calibrationText.setText(reason);
                        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                        return;
                    }
                } catch (Throwable error) {
                    String reason = "Could not read telemetry: " + error.getClass().getSimpleName();
                    calibrationText.setText(reason);
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                    return;
                }
                // Persist the visible Mod Menu switches before leaving the dialog.
                // Previously, starting calibration without first pressing Save left the
                // on-screen checked switches unsaved; stopping calibration then reloaded
                // the old OFF config and Auto Shift appeared to disable itself.
                settings.master = master.isChecked() || autoShift.isChecked();
                settings.autoPerfectShift = autoShift.isChecked();
                settings.autoShiftRpm = ModMenuConfig.MIN_SHIFT_RPM + rpm.getProgress();
                settings.infiniteNos = infiniteNos.isChecked();
                settings.raceAssist = raceAssist.isChecked();
                settings.perfectDrift = perfectDrift.isChecked();
                if (!settings.autoPerfectShift) {
                    settings.autoShiftTrackId = -1;
                    settings.autoShiftRaceType = -1;
                } else if (initial[0] > 0.5f && Math.round(initial[1]) == 6
                        && Math.round(initial[8]) == 4 && initial[12] > 0.5f) {
                    settings.autoShiftTrackId = Math.round(initial[10]);
                    settings.autoShiftRaceType = Math.round(initial[9]);
                    activeEventAtOpen[0] = true;
                    eventTrackAtOpen[0] = settings.autoShiftTrackId;
                    eventTypeAtOpen[0] = settings.autoShiftRaceType;
                }
                if (!ModMenuConfig.save(this, settings)) {
                    String reason = "Could not save Mod Menu settings. Calibration was not started; existing settings were left unchanged.";
                    calibrationText.setText(reason);
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                    return;
                }
                observedShiftPeaks.clear();
                previousGear[0] = -1;
                peakRpm[0] = 0f;
                calibrationRunning[0] = true;
                modCalibrationRunning = true;
                calibrationText.setText("Shift calibration running. Stop here or tap MOD to stop.");
                TextView overlayText = new TextView(this);
                overlayText.setText("Starting shift calibration...");
                overlayText.setTextColor(Color.WHITE);
                overlayText.setTextSize(12);
                overlayText.setPadding(dp(12), dp(8), dp(12), dp(8));
                calibrationOverlayText[0] = overlayText;

                android.widget.Button stopButton = new android.widget.Button(this);
                stopButton.setText("Stop");
                stopButton.setAllCaps(false);
                stopButton.setMinWidth(dp(88));
                stopButton.setMinHeight(dp(48));
                stopButton.setFocusable(false);
                stopButton.setFocusableInTouchMode(false);
                stopButton.setClickable(true);
                stopButton.setOnClickListener(stop -> stopCalibration.run());

                LinearLayout overlay = new LinearLayout(this);
                overlay.setOrientation(LinearLayout.HORIZONTAL);
                overlay.setGravity(Gravity.CENTER_VERTICAL);
                // This HUD must be transparent to gameplay input outside the Stop button.
                // A focusable PopupWindow steals window focus; GameActivity then dismisses
                // its touch-control window in onWindowFocusChanged(false).
                overlay.setFocusable(false);
                overlay.setFocusableInTouchMode(false);
                overlay.setClickable(false);
                overlay.setPadding(dp(6), dp(4), dp(6), dp(4));
                overlay.setBackgroundColor(0xE6222222);
                overlay.addView(overlayText, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                overlay.addView(stopButton, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(24);
                calibrationPopup[0] = new PopupWindow(overlay, Math.min(maxWidth, dp(560)),
                        ViewGroup.LayoutParams.WRAP_CONTENT, false);
                calibrationPopup[0].setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xE6222222));
                calibrationPopup[0].setOutsideTouchable(false);
                calibrationPopup[0].setFocusable(false);
                calibrationPopup[0].setTouchable(true);
                calibrationPopup[0].setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
                calibrationPopup[0].setElevation(dp(8));
                if (Build.VERSION.SDK_INT >= 29) calibrationPopup[0].setTouchModal(false);
                modCalibrationWindow = calibrationPopup[0];
                dialog.dismiss();
                calibrationPopup[0].showAtLocation(getWindow().getDecorView(),
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(18));
                calibrationHandler.post(calibrationPoll[0]);
            });
            dialog.setOnDismissListener(ignored -> {
                if (!calibrationRunning[0]) calibrationHandler.removeCallbacks(calibrationPoll[0]);
                // Resume only if this dialog actually paused an active driving session.
                if (modMenuPausedGameplay) {
                    modMenuPausedGameplay = false;
                    pulseGamePauseKey();
                }
                if (!modCalibrationRunning) {
                    new Handler(Looper.getMainLooper()).postDelayed(this::startAutoShiftController, 240L);
                }
            });
            allOffButton.setOnClickListener(button -> {
                ModMenuConfig.Settings disabled = new ModMenuConfig.Settings();
                if (ModMenuConfig.save(this, disabled)) {
                    Toast.makeText(this, "All Mod Menu options are OFF.", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    showModMenuStorageAccessDialog();
                }
            });
            closeButton.setOnClickListener(button -> dialog.dismiss());
            saveButton.setOnClickListener(button -> {
                settings.master = master.isChecked();
                settings.autoPerfectShift = autoShift.isChecked();
                settings.autoShiftRpm = ModMenuConfig.MIN_SHIFT_RPM + rpm.getProgress();
                if (settings.autoPerfectShift && !settings.master) {
                    // Auto Shift requires Master; persist both switches consistently.
                    settings.master = true;
                    master.setChecked(true);
                }
                boolean autoShiftArmed = false;
                if (!settings.autoPerfectShift) {
                    settings.autoShiftTrackId = -1;
                    settings.autoShiftRaceType = -1;
                } else if (activeEventAtOpen[0]) {
                    settings.autoShiftTrackId = eventTrackAtOpen[0];
                    settings.autoShiftRaceType = eventTypeAtOpen[0];
                    autoShiftArmed = true;
                } else {
                    // Preserve the ON preference even when telemetry cannot identify an event.
                    // No event ID means the controller remains fail-closed and cannot shift.
                    autoShiftArmed = settings.autoShiftTrackId >= 0 && settings.autoShiftRaceType >= 0;
                }
                settings.infiniteNos = infiniteNos.isChecked();
                settings.raceAssist = raceAssist.isChecked();
                settings.perfectDrift = perfectDrift.isChecked();
                if (ModMenuConfig.save(this, settings)) {
                    String savedMessage;
                    if (!settings.autoPerfectShift) {
                        savedMessage = "Mod Menu settings saved. Gameplay cheats remain off.";
                    } else if (autoShiftArmed && activeEventAtOpen[0]) {
                        savedMessage = "Settings saved. Experimental Auto Shift armed for this event at "
                                + settings.autoShiftRpm + " RPM.";
                    } else if (autoShiftArmed) {
                        savedMessage = "Settings saved. Auto Shift preference remains ON and scoped to its saved event.";
                    } else {
                        savedMessage = "Settings saved. Auto Shift preference is ON, but not armed yet because live event telemetry is unavailable.";
                    }
                    Toast.makeText(this, savedMessage, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } else {
                    showModMenuStorageAccessDialog();
                }
            });
            dialog.show();
        });
    }

    private void applyCheatCodes(java.util.List<String> codes) {
        if (codes == null || codes.isEmpty()) return;
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        long[] waves = new long[] { 5000, 7500, 10000, 13000 };
        for (long waveDelay : waves) {
            handler.postDelayed(() -> {
                if (!foreground) return;
                long offset = 0;
                for (String code : codes) {
                    handler.postDelayed(() -> {
                        if (foreground) {
                            nativeTypeText(code, false);
                            android.util.Log.i("NFSU2", "Delivered cheat code: " + code);
                        }
                    }, offset);
                    offset += 250;
                }
            }, waveDelay);
        }
    }
    public void setStatus(String message) {
        if (message != null) {
            String backend = GameOptions.getEffectiveGraphicsBackend(this);
            if ("opengl".equals(backend)) {
                message = message.replace("Vulkan 1.1: presentación activa", "OpenGL ES: presentation active")
                                 .replace("Vulkan 1.1: presentation active", "OpenGL ES: presentation active");
            } else {
                message = message.replace("Vulkan 1.1: presentación activa", "Vulkan 1.1: presentation active");
            }
            message = message
                    .replace("Datos accesibles: memoria interna/nfsu2", "Accessible data: internal storage/nfsu2")
                    .replace("Ejecutando entrada CRT del juego…", "Executing game CRT entry…")
                    .replace("Ejecutando entrada CRT del juego", "Executing game CRT entry…")
                    .replace("Arranque guest detenido:", "Guest boot halted:")
                    .replace("Entrada guest terminada:", "Guest entry finished:")
                    .replace("La GPU no pudo crear el dispositivo grafico", "The GPU could not create the graphics device")
                    .replace("Usa \"Probar driver\" en el launcher para ver que le falta.", "Use \"Check driver\" in the launcher to see what is missing.")
                    .replace("Usa \"Probar driver\"", "Use \"Check driver\"")
                    .replace("para ver que le falta", "to see what is missing");
        }
        final String text = message;
        if (text != null) {
            GameLog.i("Status", text);
            if (text.contains("halted") || text.contains("detenido") || text.contains("Error")
                    || text.contains("failed") || text.contains("terminada") || text.contains("finished")) {
                GameLog.logHalt(this, text);
            }
        }
        runOnUiThread(() -> {
            if (statusHideTask != null) status.removeCallbacks(statusHideTask);
            if (text != null && (text.contains("Executing game CRT entry") || text.contains("Ejecutando entrada CRT"))) {
                status.setText("NFSU2 Revived\n" + text);
                statusHideTask = () -> {statusHidden=true;if(statusWindow.isShowing())statusWindow.dismiss();};
                status.postDelayed(statusHideTask, 3000);
            } else {
                statusHidden=false;
                status.setText("NFSU2 Revived\n" + text);
                if (!statusWindow.isShowing() && hasWindowFocus())
                    statusWindow.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
            }
        });
    }
    private boolean wasBackgrounded = false;

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(!focused) {
            if(touchControls!=null)touchControls.releaseAll();
            setTiltKeys(false,false);
            // A calibration overlay is intentionally non-focusable and must never
            // tear down the gameplay touch-control layer while RPM samples are running.
            if(!modCalibrationRunning) {
                if(controlsWindow!=null)controlsWindow.dismiss();
                if(diagnosticOverlay!=null)diagnosticOverlay.hide();
            }
        }
        if(focused){
            if(Build.VERSION.SDK_INT>=30){
                android.view.WindowInsetsController insets=getWindow().getInsetsController();
                if(insets!=null){insets.hide(android.view.WindowInsets.Type.systemBars());
                    insets.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}
            }else getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            if (wasBackgrounded) {
                showRecoveryDialog();
            }
        }
        if (focused && foreground && statusWindow != null && !statusHidden && !statusWindow.isShowing()) {
            statusWindow.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
        }
        if (focused && foreground && controlsWindow != null && !controlsWindow.isShowing()) {
            controlsWindow.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
        }
        if (focused && foreground && diagnosticOverlay != null) {
            diagnosticOverlay.show();
        }
    }
    // LAN games announce and discover themselves with UDP broadcasts on port 9999;
    // without this lock many devices drop incoming Wi-Fi broadcasts to save power.
    private android.net.wifi.WifiManager.MulticastLock lanBroadcasts;
    private void holdLanBroadcasts(boolean hold) {
        if (lanBroadcasts == null) {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi == null) return;
            lanBroadcasts = wifi.createMulticastLock("nfsu2-lan");
            lanBroadcasts.setReferenceCounted(false);
        }
        if (hold) lanBroadcasts.acquire(); else lanBroadcasts.release();
    }
    @Override protected void onPause() {
        foreground=false;
        stopAutoShiftController();
        stopCompanionTicker();
        holdLanBroadcasts(false);
        updateTiltSensor();
        if(inputManager!=null)inputManager.unregisterInputDeviceListener(gamepadListener);
        if (statusWindow != null) statusWindow.dismiss();
        if (touchControls != null) {touchControls.removeCallbacks(updateControlMode);touchControls.releaseAll();}
        if (controlsWindow != null) controlsWindow.dismiss();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground=true;
        startCompanionTicker();
        new Handler(Looper.getMainLooper()).postDelayed(this::startAutoShiftController, 500L);
        holdLanBroadcasts(true);
        updateTiltSensor();
        if(inputManager!=null){inputManager.registerInputDeviceListener(gamepadListener,null);updateGamepads();}
        if(touchControls!=null) {touchControls.removeCallbacks(updateControlMode);touchControls.post(updateControlMode);}
        boolean allowed = Build.VERSION.SDK_INT >= 30
                ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (!allowed && !requested) {
            requested = true;
            Toast.makeText(this, "Allow file access to read the nfsu2 folder from internal storage", Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        // NativeActivity may stay resumed when a runtime permission dialog closes.
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) recreate();
    }
    @Override public void onBackPressed() {
        // Prevent accidental back gesture from closing the game while steering
    }
    @Override protected void onStop() {
        super.onStop();
        wasBackgrounded = true;
    }

    private void showRecoveryDialog() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Session Interrupted")
                .setMessage("Android OS paused the graphics pipeline while the game was in the background.\n\nTap Quick Restart to reload cleanly in ~1 second.")
                .setPositiveButton("Quick Restart", (dialog, which) -> quickRestart())
                .setNeutralButton("Try Resume", (dialog, which) -> {
                    wasBackgrounded = false;
                    dialog.dismiss();
                })
                .setNegativeButton("Exit to Launcher", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void quickRestart() {
        wasBackgrounded = false;
        Intent intent = getIntent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        if ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                && event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            float z = event.getAxisValue(android.view.MotionEvent.AXIS_Z);
            float rz = event.getAxisValue(android.view.MotionEvent.AXIS_RZ);
            if (Math.abs(z) > 0.15f || Math.abs(rz) > 0.15f) {
                AdhocRoamClient.handleRightStick(z, rz);
            } else {
                AdhocRoamClient.handleRightStick(0f, 0f);
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override protected void onDestroy() {
        stopAutoShiftController();
        super.onDestroy();
        stopCompanionTicker();
        if (diagnosticOverlay != null) diagnosticOverlay.stop();
        AdhocRoamClient.getInstance().stop();
        // Each launch uses a fresh guest machine in the dedicated :game process.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
