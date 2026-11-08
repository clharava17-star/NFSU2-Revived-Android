# NFSU2 Revived Mod Menu — implementation notes

Target base: current `main` branch (v0.15.17 at the time of audit).
Working branch: `feature/mod-menu-v0.2`.

## Root-cause repair for empty Drag telemetry
### Frame-hook linker namespace fix

The user's diagnostic screenshot from the diagnostic APK reports `Hooks 0 | samples 0 | memory missing | ESP idle`, with all guest global addresses unmapped. This places the failure before Player/Car pointer traversal: `nfs_roam_frame_tick()` is not being executed (or its symbol is not being reached at all).

The runtime previously resolved the companion with `dlopen("libnfsu2_roam.so")` and `dlsym()` from the NativeActivity D3D9 Present path, while Java separately loads the same library through `System.loadLibrary()`. Android linker namespaces can make a class-loader-loaded library invisible to a different native caller namespace even within the same process. The new build links the imported `libnfsu2_roam.so` directly as a dependency of `nfsu2_android` in CMake and calls the exported `nfs_roam_frame_tick()` directly from `guest_runtime.cpp`. This removes runtime symbol-discovery ambiguity and ensures both the Java JNI telemetry reader and game-frame hook use the same shared-library instance.

This change must be built and the device diagnostic rechecked. Acceptance: `Hooks` and `samples` become non-zero during gameplay; only then proceed to confirm guest memory base and the version-specific flow/player/race addresses. It is not yet proof that those game addresses are correct or that Auto Shift works.


The user confirmed the car is physically at the Drag start/finish line while the Mod Menu still reported `Flow 0 | Player* missing | Car* missing | Type -1 | Track -1`. The previous telemetry path incorrectly required a non-zero guest ESP before even reading committed guest memory. The companion's Direct3D Present hook can be called at a point where the guest stack is idle, even though its memory image is already mapped. This short-circuited the update and left the static initial snapshot visible.

The fix separates:
- **Guest memory readiness**: mapped `g_mem_base`; sufficient for guarded, read-only address reads.
- **Guest stack readiness**: non-zero `g_esp`; still required before any companion operation that invokes lifted game functions.

The frame hook now increments diagnostic counters, publishes telemetry whenever guest memory is mapped, and returns before free-roam guest-function calls if the ESP is not live. The 20-value snapshot includes frame-hook count, telemetry-update count, memory/ESP readiness, and committed-address checks for the flow, player slot, and race globals. These diagnostics are displayed in the Mod Menu so the next run can distinguish a missing Present hook from an unmapped/incorrect game-data address. The C read paths remain guarded by the guest committed-memory check.

Auto Shift Save no longer discards the user's ON preference merely because event telemetry was unavailable when the menu was opened. It persists ON with a clear **not armed yet** message, while the controller remains fail-closed until a valid event track/type has actually been identified. It does not send shift inputs without those event identifiers.

No APK should be distributed based solely on a successful build; the new diagnostics must be read on-device first. Do not describe the prototype as Perfect Shift until the actual green shift judgement is mapped and verified.

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

## Experimental Auto Shift prototype (event-scoped)

The Android controller currently uses the already-mapped Shift Up scan code and the read-only telemetry snapshot. The option can be armed only from an active in-world event: the menu captures the current race type and track ID before pausing; the values are persisted separately in `modmenu.ini`. When enabled with Master Cheat, the controller polls telemetry, requires the same flow/race state/type/track, a valid player-car snapshot, a forward gear (1–6), and throttle. It emits a short Shift Up key pulse when the configured RPM threshold is reached, enforces a 600 ms cooldown, and waits for a gear change or timeout plus RPM drop before another pulse. It stops on pause/background, menu open, or Activity destruction.

This is an **experimental RPM-triggered upshift**, not a verified NFSU2 Perfect Shift implementation. The exact green shift window and event-type discriminator for Drag are not yet confirmed, and the user's on-device result must be tested in the exact Drag event before calling it Perfect Shift. Do not arm it for an event unless the user is in the intended Drag race and telemetry is reporting valid data. Infinite NOS, Race Assist, and Perfect Drift remain pending.

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
- The panel exposes Master, event-scoped experimental Auto Shift, Infinite NOS, Race Assist, and Perfect Drift settings.
- Auto Shift target RPM is configurable from 5,000 to 11,000 RPM (default 7,000).
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
- The experimental Auto Shift controller can send the mapped Shift Up key in its specifically armed event when configured RPM/throttle/gear conditions match. This is an RPM-triggered prototype, not a confirmed Perfect Shift implementation.
- Exact NFSU2 Drag event identifier and green Perfect Shift timing window still require on-device validation. Do not claim it is “always Perfect”.
- Infinite NOS has no verified race NOS hook.
- Race Assist has no verified opponent or race-result hook.
- Perfect Drift has no verified drift scoring or vehicle-control hook.

Validation:
- Source integration points and branch contents were inspected through GitHub.
- GitHub Actions run `42469535739` built native Rust and `libnfsu2_roam.so` successfully, but failed only when Gradle 8.9 distribution download returned HTTP 504 after three attempts. The wrapper now uses the Huawei Cloud Gradle 8.9 mirror; current build run `42469806108` is testing that change.
- Do not merge this draft or distribute it until the Gradle build, ARM64 APK, and on-device Auto Shift regression test pass.

## Reference APK audit — NFS Most Wanted Android (user-supplied)

The user supplied `com.racas.mostwanted.apk` and a screenshot of its Indonesian cheat menu. The APK is a 33,780,676-byte Android package containing an ARM64 `libmain.so` (30,160,696 bytes), SDL3, and a small Java DEX. The native ELF dynamic symbol table is present and exposes the following named symbols (confirmed with `readelf -Ws`):

- `game::DragShiftFrame(X86Context&)` — function symbol at `0x08e1640`, size 696 bytes. This is a strong indication that the reference port integrates Drag shifting at a game-frame/context boundary rather than relying only on a Java timer.
- `game::ApplyCheats()` — function symbol at `0x08e0d7c`, size 444 bytes.
- `game::g_focus_paused` — one-byte weak object symbol at `0x1cd033e`.
- `audio::SetPaused(bool)` — function symbol at `0x08f80a8`, size 96 bytes.
- Named cheat identifiers include `cheat_drag_shift`, `cheat_nos`, `cheat_speedbreaker`, `cheat_unlock_all`, `cheat_no_police`, `cheat_no_traffic`, and `cheat_cash`.

These findings are from symbol metadata and strings only. They do not establish the complete function semantics, exact memory layout, or safe hook addresses for NFSU2. Do not copy these offsets, symbols, or binary code into NFSU2: the two games have different executable layouts and runtime implementations.

### Consequence for NFSU2

The reference suggests a better long-term shape for Auto Shift: evaluate a validated shift condition from the game execution/frame context, then submit a shift-up input through the port's existing input path. NFSU2's current build is an x86 guest executable running inside the Android recompiler; its own guest addresses and state must be mapped independently. The current Java controller remains an explicitly experimental RPM-triggered prototype until its timing is verified in the actual Drag event. The reference APK does not prove that the NFSU2 green Perfect Shift window is mapped.

The reference's pause symbols are not needed for the already-working NFSU2 MOD pause/resume path. Keep the existing guest pause/input path unchanged.

