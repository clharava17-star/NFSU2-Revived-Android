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

The user confirmed on-device that saving `modmenu.ini` now succeeds. The user should grant this access only to the Mod Edition app if they accept the broad external-storage permission. Do not move the config into the Career save directory.

## Telemetry snapshot correction

The previous first pass stopped reading the player car whenever `G_FLOW != 6` and used an inferred gearbox pointer chain plus `car + 0x438` as throttle. That was overly restrictive and did not match the established companion's own player snapshot code.

The current snapshot follows `roam_game.c::local_state()`:
- Player pointer: `SPLIT_PLAYERS_INDEX = 0x008900AC`; Player +4 contains the player Car pointer.
- Speed: Car + `0x42C`.
- RPM: Car + `0x440`.
- Signed gear: `(int8_t)MR32(Car + 0x434)` (-1 reverse, 0 neutral, 1 first, ...).
- Throttle: Car + `0x30` controller pointer, then controller + `0x20C`.

The JNI snapshot now includes 13 values: validity, flow state, speed, RPM, throttle, signed gear, Player* validity, Car* validity, race coordinator state, race type, track ID, career-race flag, and an active-world-race flag. The latter is true only when `G_FLOW == 6` and `G_RACE.state == 4`, matching the definitions/comments in `roam_game.c`. A valid car snapshot is reported even outside an active race; calibration is still blocked until the active-race flag is true.

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

## User-reported telemetry and Stop-button issue

The user's screenshot while the game displayed **Event Results** showed game-flow state `0`. This is not an active driving frame, so the telemetry reader correctly withholds RPM/gear instead of reporting stale values. Calibration now checks for a valid snapshot before starting and explains that the player must continue from the results screen, enter an actual Drag race, and drive before capturing samples.

The user also reported that the calibration overlay's Stop button did not respond. The PopupWindow was originally created non-focusable. The updated implementation creates a touchable/focusable popup, gives Stop a minimum 88dp × 48dp hit target, and offers a second stop route: tapping the HUD **MOD** button while calibration is running calls the activity-owned stop routine. The Stop routine removes the polling callback and dismisses the popup.

## Pause while Mod Menu is open

The MOD HUD action reuses the exact same guest input path as the existing HUD Pause control: DirectInput scan code `0x01` (Escape). The Java helper sends a short key-down pulse and releases it after 140 ms, using the existing `nativeKey` wrapper that the touch controls already use. This delegates the pause state to the game's own pause menu rather than introducing an unverified native JNI entry point.

When the Mod Menu dialog is dismissed (Close, Save settings, All OFF, Back, or calibration start), the same Escape pulse is sent again to resume the game. The temporary native `nativeSetModMenuPaused` bridge was removed after inspecting the built APK and finding that the workflow packages prebuilt game native libraries; it did not contain that newly added JNI symbol. The implementation now uses only the already-present Escape input API.

On-device verification is still required to confirm that this pause toggle freezes gameplay and that the second Escape pulse resumes cleanly on the user's Android device.

## Latest CI failure and retry

The initial Auto Pause build reached the Gradle build step after successful checkout, Java setup, prebuilt native extraction, Rust native builds, and native free-roam companion compilation. It failed because the Gradle 8.9 distribution download returned HTTP 504 from the GitHub release redirect; no APK artifact was produced. The workflow now retries `./gradlew assembleRelease --no-daemon` up to three times to handle transient distribution CDN failures.


Implemented on `feature/mod-menu-v0.2`:
- The existing HUD has a dedicated MOD control wired to `GameActivity.showModMenu()`.
- The panel exposes Master, Auto Shift, Infinite NOS, Race Assist, and Perfect Drift settings.
- Auto Shift target RPM is configurable from 5,000 to 7,500 RPM (default 7,000), aligned with the known NFSU2 1.2 engine RPM range; final per-car calibration is still required.
- Settings are stored separately at `<gameRoot>/mods/NFSU2_ModMenu/modmenu.ini`.
- All options default OFF. Saving uses a temporary file and a backup/rollback path to avoid deleting a prior configuration on a failed write.
- The MOD HUD control remains available when the touch-control layout is hidden.
- Pressing MOD pauses the guest game simulation while the Mod Menu dialog is open; dismissing the dialog resumes gameplay.
- `Yogie Clharava` appears only in the Mod Menu bottom action bar, centered in the gap after `All OFF` and before `Close` / `Save settings`. No watermark is drawn on the normal gameplay HUD.

Implemented as read-only diagnostic groundwork:
- The Android free-roam companion publishes a guarded snapshot of game-flow state, player-car speed, RPM, throttle and raw gearbox value from the matching NFSU2 v1.2 guest address space.
- The Mod Menu exposes a manual **Read live telemetry** action. It does not write to the game. Invalid snapshots now report the observed game-flow value or explain that the car/gearbox pointer is not ready.
- The Mod Menu includes an optional **Start shift calibration** capture. It observes gear increases and the peak RPM seen before each upshift during a manually driven Drag event; it is diagnostic only and does not press Shift or claim to detect the game's Perfect Shift judgement.
- Starting calibration now closes the modal settings dialog and opens a small non-modal status/Stop overlay, so the player can continue using touch shift controls while samples are collected. The overlay updates live and stopping it reports the captured RPM range/average.
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
