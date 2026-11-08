# NFSU2 Revived Mod Menu — implementation notes

Target base: current `main` branch (v0.15.17 at the time of audit).
Working branch: `feature/mod-menu-v0.2`.

## Verified integration points

- `android/app/src/main/java/io/mininxd/nfsu2/GameActivity.java`: game activity owns the touch-controls overlay and forwards scan-code input to the native game.
- `android/app/src/main/java/io/mininxd/nfsu2/RacingControlsView.java`: custom in-game touch HUD view; candidate location for a compact MOD entry point.
- `android/app/src/main/java/io/mininxd/nfsu2/GameOptions.java`: existing persisted cheat preferences and cheat-code activation list.
- `android/app/src/main/cpp/roam/roam_android.c` and `roam_game.c`: free-roam companion implementation. These are not automatically proof of access to the original race simulation's live state.

## Important implementation boundary

The existing free-roam companion data must not be assumed to expose the original game's drag-race timing, NOS depletion, drift score, or gear-shift judgement. Do not implement "Perfect Shift", infinite NOS, or perfect drift by guessing memory offsets or writing arbitrary process memory. First identify a stable, version-specific game interface or supported native hook and prove it in a controlled test.

## Proposed staged delivery

1. UI-only prototype: add a MOD button and panel to the existing HUD without changing driving input, game assets, graphics settings, or Career save files.
2. Persistent toggles: store mod preferences separately from Career save data; all features default OFF.
3. Read-only telemetry: establish safe, verified access to gear, RPM, NOS and drift state for the current game build.
4. Auto Shift: only after telemetry and shift-input timing are validated in a test event; provide an OFF switch and fail-safe.
5. NOS and drift assists: only after the correct game-state interfaces are identified and validated.
6. Build ARM64 APK and test installation, startup, graphics, textures, Career load/save, and rollback.

## Acceptance criteria

- The original `main` branch remains untouched.
- Changes are isolated to this feature branch and reviewed before merge.
- No Career save data is rewritten by the mod menu.
- Graphics, textures, controls, and online/multiplayer behaviour remain unchanged when all mod toggles are OFF.
- No cheat is marked complete until it is built and tested in the actual Android game.
- Release signing uses a private key controlled by the owner; never commit signing keys or passwords.

## App identity

The Mod Edition uses a distinct Android application ID, `io.mininxd.nfsu2.modmenu`, and a distinct launcher label. This avoids replacing the original NFSU2 Revived installation during prototype testing. The game-data root remains the existing external-storage `NFSU2` / `nfsu2` folder resolved by `LauncherActivity.gameRoot()`; make a backup before testing both installations against the same career files.

## Signing security

The upstream public branch contains a tracked Android release keystore and hard-coded signing credentials in its Gradle build file. Treat that signing material as compromised; do not reuse it for Mod Edition or future production updates.

This feature branch removes the tracked `android/app/release.jks` from its proposed changes and changes Gradle to read a private keystore path and passwords from environment variables. GitHub Actions accepts these repository secrets:
- `NFSU2_RELEASE_KEYSTORE_BASE64`
- `NFSU2_RELEASE_STORE_PASSWORD`
- `NFSU2_RELEASE_KEY_ALIAS`
- `NFSU2_RELEASE_KEY_PASSWORD`

When those secrets are absent, the workflow uses the generated Android debug key for test builds only. A debug-signed test APK is not a production update for an existing installation. The fork's `main` branch and earlier Git history still retain upstream content until this draft PR is reviewed and merged; removing a file from the current tree does not erase its historic blob.

## Android storage permission diagnostic

The user observed a failed write to `/storage/emulated/0/NFSU2/mods/NFSU2_ModMenu/modmenu.ini` on the separate Mod Edition package. Android 11+ grants `MANAGE_EXTERNAL_STORAGE` per application package; it is not inherited from the original NFSU2 Revived package. The Mod Menu now checks `Environment.isExternalStorageManager()` and, when missing, offers a direct route to that Mod Edition app's special-access settings. After granting access, the user must return to the game and tap **Save settings** again. If access is already granted but the write still fails, the dialog displays the exact configuration path for diagnosis.

The user should grant this access only to the Mod Edition app if they accept the broad external-storage permission. Do not move the config into the Career save directory.

## Telemetry source and limits

The current read-only snapshot mirrors the documented NFSU2 v1.2 US player-car layout used by the Android port:
- Player pointer at guest VA `0x8900AC`; player + `0x04` points to the car mirror.
- Mirror offsets: speed `+0x42C`, throttle `+0x438`, RPM `+0x440`.
- Gearbox snapshot: mirror + `0x34` -> `+0x18`; raw gear at gearbox + `0x34` (0 reverse, 1 neutral, 2 first gear, etc.).
- Game-flow state at `0x8654A4`; the snapshot only reports valid while the flow state is gameplay.
- All guest pointers/ranges are checked with the runtime's committed-memory guard before reading. The UI asks for a mutex-protected copy; the Mod Menu does not write these values.

Reference reverse-engineering notes for the same 4,800,512-byte v1.2 US executable:
- [FRSModLoader Game.h](https://github.com/chrystianfarias/FRSModLoader/blob/main/src/game/Game.h)
- [FRSModLoader Vehicle.h](https://github.com/chrystianfarias/FRSModLoader/blob/main/src/game/Vehicle.h)
- [FRSModLoader NOTES.md — telemetry and shift notes](https://github.com/chrystianfarias/FRSModLoader/blob/main/NOTES.md)

The drag shift judgement itself is not yet mapped in this Android build. Public drag references describe a brief green "Perfect Shift" indicator and say the useful shift point varies by car and installed performance parts; therefore a fixed RPM threshold alone is not proof of a perfect shift. The target-RPM slider is only a calibration setting until the actual shift window / result field is identified and validated in-game. See [NFSU2 Drag shift overview](https://nfs.fandom.com/wiki/Need_for_Speed%3A_Underground_2/Drag) and [Prima guide excerpt](https://www.scribd.com/document/343113449/Need-for-Speed-Underground-2-Prima-Official-Guide).

## Current status — v0.2 prototype

Implemented on `feature/mod-menu-v0.2`:
- The existing HUD has a dedicated MOD control wired to `GameActivity.showModMenu()`.
- The panel exposes Master, Auto Shift, Infinite NOS, Race Assist, and Perfect Drift settings.
- Auto Shift target RPM is configurable from 5,000 to 7,500 RPM (default 7,000), aligned with the known NFSU2 1.2 engine RPM range; final per-car calibration is still required.
- Settings are stored separately at `<gameRoot>/mods/NFSU2_ModMenu/modmenu.ini`.
- All options default OFF. Saving uses a temporary file and a backup/rollback path to avoid deleting a prior configuration on a failed write.
- The MOD HUD control remains available when the touch-control layout is hidden.
- `Yogie Clharava` appears only in the Mod Menu bottom action bar, centered in the gap after `All OFF` and before `Close` / `Save settings`. No watermark is drawn on the normal gameplay HUD.

Implemented as read-only diagnostic groundwork:
- The Android free-roam companion publishes a guarded snapshot of game-flow state, player-car speed, RPM, throttle and raw gearbox value from the matching NFSU2 v1.2 guest address space.
- The Mod Menu exposes a manual **Read live telemetry** action. It does not write to the game. Invalid snapshots now report the observed game-flow value or explain that the car/gearbox pointer is not ready.
- The Mod Menu includes an optional **Start shift calibration** capture. It observes gear increases and the peak RPM seen before each upshift during a manually driven Drag event; it is diagnostic only and does not press Shift or claim to detect the game's Perfect Shift judgement.
- The snapshot is copied under a mutex; pointers are checked against committed guest-memory ranges before dereference.
- The raw gear counter is interpreted as 0 reverse, 1 neutral, 2 first gear, and so on.

Not implemented:
- No cheat currently changes live gameplay.
- Auto Shift / Perfect Shift still needs a verified drag-event gate and exact in-game shift timing window. Do not assume a generic RPM threshold equals the game's Perfect Shift judgement.
- Infinite NOS has no verified race NOS hook.
- Race Assist has no verified opponent or race-result hook.
- Perfect Drift has no verified drift scoring or vehicle-control hook.

Validation:
- Source integration points and branch contents were inspected through GitHub.
- GitHub Actions build `42421116847` succeeded for the earlier telemetry/footer baseline. The shift-calibration addition is a newer commit and requires its own fresh build and on-device test.
- Do not merge this draft or distribute it as a functioning cheat APK until the Android build and on-device regression tests pass.
