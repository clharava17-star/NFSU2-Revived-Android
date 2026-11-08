## Exact per-gear Perfect Shift window — current Auto Shift implementation

The user's video showed **Over Rev!** and **Short Shift!** with the earlier generic threshold. That threshold was not close enough to the game's Perfect Shift scoring rule. The current controller uses the game's own per-gear shift point and the same `RateShift` formula documented from the NFSU2 physics implementation.

### Live drivetrain data read-only

For the player car in physics movement mode:
- `Car + 0x34` → `PhysicsMover*`
- `PhysicsMover + 0x4C` → `DriveTrain*`
- `DriveTrain + 0x84` → `shiftUp[9]`, indexed by game Gear enum (0=R, 1=N, 2=1st…)
- `DriveTrain + 0xE4` → `perfectRangeMul`
- `DriveTrain + 0xF0` → `perfType`
- `PerfectShiftMaxRPMDiff` table at guest VA `0x007FBF60` (3×9 floats)
- `DriveTrain + 0x68` / `+0x6C` → last shift bonus / RPM used for scoring

The active range is `R = PerfectShiftMaxRPMDiff[perfType*9 + gear] * perfectRangeMul`. The documented `RateShift` rule awards Perfect (`1.0`) only when RPM is above the per-gear `shiftUp[gear]` point by less than 5% of `R`. Thus the green window is:

`shiftUp[gear] < RPM < shiftUp[gear] + 0.05 * R`

The controller aims at the middle of this narrow interval, `shiftUp + 0.025 * R`, and estimates the RPM at the next game physics frame from the observed frame-to-frame RPM rise. It polls telemetry every 4 ms, tracks actual RPM changes rather than assuming each poll is a new physics frame, and sends a short Shift-Up pulse only when the current/next-frame estimate enters the Perfect window. It verifies the gear increase before re-arming for the next gear. The controller also reads the current forward gear count and does not request an upshift from the final gear.

The JNI snapshot now has 33 fields. Fields [27–30] carry the active gear's shiftUp RPM, RateShift range R, forward gear count and current DriveTrain gear. Fields [31–32] expose the game's last shift bonus and RPM-at-shift for diagnostics. The Mod Menu's Read Live Telemetry displays the per-gear window and last recorded bonus.

These are read-only reads from the active DriveTrain. The companion does not write game physics, shift points, career data, or scoring values. If the player car is not in physics mode, the DriveTrain pointer is unavailable, the per-gear range is zero, or telemetry/event checks fail, Auto Shift remains idle.

### Why this should correct the video result

The earlier RPM trigger used a broad base-engine threshold and could request a shift before the gear's Perfect interval, which matches the user's **Short Shift!** result. The new trigger uses the exact per-gear shift point and Perfect range used by `RateShift`, including the current vehicle's tuned drivetrain and drag difficulty multiplier. It also aims inside the scoring interval rather than at the redline.

The physics reverse-engineering notes identify the scoring fields as `shiftBonus` and `rpmAtShift`; a Perfect result is recorded as 1.0. The new telemetry exposes those fields so device testing can verify the game's own score instead of relying only on the gauge.

This is a source-based implementation of the documented RateShift rule, but a successful build is not yet proof that an Android game session will always score Perfect. On-device validation is required, and timing may still need adjustment for frame pacing or input latency.

---

## Perfect Shift timing correction — use the active Drag physics threshold

The user's supplied Drag video shows the game reporting **Short Shift!** after an automatic upshift. The previous build's 90% fraction of the base/vehicle RPM range was too early for this car's active setup. That 90% formula is superseded and is no longer used by the controller.

The active physics block is reached through the game object graph:

- Player Car + `0x10` → `RideInfo*`
- `RideInfo + 0x10` → `RidePhysicsInfo*`
- `RidePhysicsInfo + 0x30` → inline tuned `CarPhysicsInfo` (CPI)

The NFSU2 physics reverse-engineering reference documents `RideInfo+0x10 = RidePhysicsInfo`, `GetCarPhysicsInfo() → RPI+0x30`, and rebuilds that block after installed performance parts, junkman, custom tuning and race-mode modifiers. The CPI fields used here are:
- `CPI + 0x1F0`: active idle RPM
- `CPI + 0x1F4`: active redline/shift RPM
- `CPI + 0x1F8`: active maximum RPM

The NFSU2 drag simulation model uses `CPI+0x1F4 - 100 RPM` as its gear shift threshold. The companion now reads this *live tuned CPI* value with guarded memory checks and publishes it as telemetry field [26]. It also reports active idle/max RPM in fields [24–25]. Auto Shift now predicts the next 16 ms sample and sends Shift-Up as the live RPM approaches/crosses this per-car threshold. It resets its pending state when the car type changes and fails closed when the active CPI cannot be read safely.

This is a materially better shift target than a generic 90% of base engine range and should reduce premature Short Shift results. It is still not proof that the game's brief green Perfect Shift window is exactly identical to this dyno-model threshold. The next device test must check the game's own result text/color (Short / Good / Perfect / OverRev) across several cars and installed setups. Do not claim guaranteed Perfect Shift until those tests pass.

---

# Universal vehicle-adaptive Auto Shift — current implementation

This section supersedes the older calibration/RPM-slider notes below. Auto Shift no longer requires manual calibration for each vehicle and no longer stores one fixed RPM target in the Mod Menu.

## Vehicle-specific engine range

The Android companion now reads the currently driven car's own `CarTypeInfo` entry from guest memory:

- `G_CARTYPES`: guest address `0x008A1CCC`
- Current car's `CarTypeInfo*`: car object + `0x0C`
- `CarTypeInfo` stride: `0x890` bytes
- Engine idle RPM: `CarTypeInfo + 0x300`
- Engine maximum/most RPM: `CarTypeInfo + 0x308`

These offsets and the `CarTypeInfo` stride match the existing `roam_game.c` use of `eng_idle` / `eng_max` for a car type. Reads are guarded by guest committed-memory checks and range validation.

The native telemetry snapshot is now 26 floats. Fields [0..22] keep their existing meanings; [23] is the active car type index, [24] is that type's idle RPM, and [25] is that type's maximum RPM. The JNI getter returns all 26 fields in one mutex-protected copy.

## Automatic shift point

The Java controller derives the target from the currently sampled vehicle on every poll:

`targetRPM = idleRPM + (maxRPM - idleRPM) * 0.90`

The earlier 90% engine-range fraction was too early in the user's video and has been superseded by the live tuned Drag CPI threshold described at the top of this file.

The existing one-sample RPM-rise prediction, throttle validation, forward-gear validation, exact armed-event check, Shift-Up key pulse, gear-change confirmation, and fail-closed behavior remain in place. If the current car's engine range cannot be read safely, Auto Shift does not issue a shift input.

## User-facing behavior

The Mod Menu now has one Auto Shift toggle for the armed Drag event. The RPM slider and Start Shift Calibration button have been removed. The menu explains that vehicle engine data is detected automatically; Read Live Telemetry displays the detected car type, idle RPM, maximum RPM, and derived shift point.

Auto Shift remains scoped to the saved Drag event type/track, as before. That is event arming, not per-car configuration: the same enabled option adapts to each vehicle used in that event. The controller is not claimed to run outside its armed event.

## Acceptance test still required

The new build must be tested on-device with at least two different player cars:

1. Arm Auto Shift once for a Drag event and leave Master + Auto Shift enabled.
2. Read telemetry and note the detected car type, idle RPM, maximum RPM, and calculated target.
3. Run the Drag event in the first car and confirm the automatic upshift is requested and the gear actually increases.
4. Change to a different car without editing any RPM setting. Confirm the reported car type / RPM range / target changes automatically and Auto Shift continues to request shifts.
5. Confirm the setting remains enabled and no shift input is sent when throttle is released, engine telemetry is invalid, or the active event does not match.

A successful APK build proves compilation and packaging only. The active CPI threshold is a better Drag shift reference, but the game's green Perfect Shift judgement must still be verified by its on-screen result.

---

# NFSU2 Revived Mod Menu — implementation notes

Target base: current `main` branch (v0.15.17 at the time of audit).
Working branch: `feature/mod-menu-v0.2`.

## Auto Shift RPM learning from manual calibration

When the player starts Shift Calibration during a valid in-world race and manually upshifts, the companion samples RPM while each gear is held and records the pre-upshift peak. On Stop, the app sorts the captured peaks, calculates their median (to reduce outlier influence), clamps it to the configured safe RPM range, and persists it as `auto_shift_rpm` in `modmenu.ini`. It then restarts the Auto Shift controller if the saved Master/Auto Shift/event scope allow it. This makes the RPM trigger adapt to the observed car/manual shift style. It is still an RPM-based approximation; it does not yet prove the game's green Perfect Shift judgement window.

## Auto Shift event arming from live read

The Mod Menu originally captured the active race ID only once, before opening the dialog. If the JNI memory snapshot became valid a fraction later (or the first read happened after the UI opened), the Read button showed telemetry but the Save action still used the stale initial event fields. The Read button now refreshes the mutable event scope whenever a valid in-world race snapshot is read (Flow 6, race coordinator state 4, active-world-race flag set, valid car snapshot). The event type and track shown in the scope label are the same values persisted when Auto Shift is saved. The controller remains restricted to the saved event IDs.

## Auto Shift — native runtime binding

Static inspection of the APK confirmed the game runtime exports `g_mem_base`, `g_esp`, `g_eax`, `g_ecx`, `g_edx`, and `recomp_lookup`, while the companion library previously had no ELF `DT_NEEDED` dependency on `libnfsu2_android.so`. Depending on Android linker namespace rules, `dlsym(RTLD_DEFAULT)` / `RTLD_NOLOAD` can fail to find symbols loaded through a separate Java class-loader namespace.

The companion now declares the exported game symbols directly and the CI link step adds `-L.../jniLibs/arm64-v8a -Wl,--no-as-needed -lnfsu2_android -Wl,--as-needed -Wl,-z,defs`. This creates a load-time ELF dependency on the exact packaged game runtime. The loader must resolve these references against that runtime before the companion can load; `g_mem_base` and `g_esp` therefore cannot silently point at a second copy. `guest_is_readable` remains optional and is resolved dynamically if present. The 26-value snapshot distinguishes the memory symbol from the mapped image, reports each JNI refresh request, and includes the current car type's idle/max RPM range.

This change only fixes the read-only telemetry access path. Auto Shift still sends no shift input until it sees a valid car snapshot and the intended active Drag event. The gear-up input uses the existing game action scan code; the RPM threshold remains experimental and is not claimed to reproduce the game's green Perfect Shift judgement.

## Root-cause repair for empty Drag telemetry
### APK inspection: why the previous diagnostic still showed zero

The actual diagnostic APK was extracted and inspected with `readelf` and `strings`. Its `libnfsu2_android.so` has no `DT_NEEDED` entry for `libnfsu2_roam.so`, and no `libnfsu2_roam.so` / `nfs_roam_frame_tick` string. The GitHub Actions workflow only extracts this prebuilt game runtime from `prebuilt/lib_arm64.tar.gz`, then rebuilds `libnfsu2_roam.so` and packages the JNI libraries with Gradle; it does not run the Android CMake target. Therefore edits to `guest_runtime.cpp` and `CMakeLists.txt` are not incorporated into the current APK's main game library. This directly explains `Frame hooks 0 | live samples 0`: the packaged main runtime never calls the companion frame hook.

The shipped `libnfsu2_android.so` does export the required dynamic symbols `g_mem_base`, `g_esp`, `g_eax`, `g_ecx`, `g_edx`, and `recomp_lookup`. The practical telemetry path for this workflow is therefore an on-demand read from the companion JNI: `RoamNativeBridge.nativeGetGameTelemetry()` now calls `mod_telemetry_refresh()` before returning its snapshot. That function resolves the game memory/stack symbols, updates guest-memory/ESP diagnostics, and refreshes the read-only snapshot. The Java Read Live Telemetry action and Auto Shift poller call this getter, so both receive fresh data without needing to rebuild the 159 MB prebuilt game runtime.

The `Frame hooks` counter may correctly remain zero in this APK; the `live samples` counter must increment on every JNI snapshot request. The next device acceptance test is:
- `live samples` increases after tapping Read;
- `memory ready` becomes true once the game image is mapped;
- flow/player/race address readability is then reported independently;
- if Player/Car remain missing, inspect the exact version-specific guest addresses next;
- only when a valid active Drag snapshot is present should Auto Shift be tested.

The runtime-source/CMake direct-link experiment was reverted from the current workflow path because the APK pipeline packages the prebuilt runtime. The native companion's on-demand JNI refresh is the change actually rebuilt by CI. No Auto Shift feature should be described as Perfect Shift until the green shift judgement/timing window has been mapped and verified.


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

The JNI snapshot now includes 26 values: the original 23 telemetry/diagnostic fields plus active car type index, current car type idle RPM, and current car type maximum RPM. The latter is true only when `G_FLOW == 6` and `G_RACE.state == 4`, matching the definitions/comments in `roam_game.c`. A valid car snapshot is reported even outside an active race; Auto Shift remains blocked until the saved event is active.

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

The drag shift judgement itself is not yet fully mapped in this Android build. The current controller reads the active tuned CarPhysicsInfo redline threshold (CPI+0x1F4-100), including installed performance and race-mode physics, rather than using the old 90% CarTypeInfo approximation. This improves the shift target but still requires in-game result validation. See [NFSU2 Drag shift overview](https://nfs.fandom.com/wiki/Need_for_Speed%3A_Underground_2/Drag) and [Prima guide excerpt](https://www.scribd.com/document/343113449/Need-for-Speed-Underground-2-Prima-Official-Guide).

## Universal Auto Shift controller (event-scoped)

The Android controller uses the mapped Shift Up scan code and read-only telemetry. It is armed for a saved event type/track, but it is not tied to a vehicle name or vehicle-specific RPM setting. The active vehicle's tuned Drag physics block is read dynamically and the target is recalculated when the physics/car changes. The controller requires the same active event, a readable tuned CPI threshold, a valid forward gear, positive RPM, and real throttle input. It emits a short Shift-Up pulse only at the dynamic shift point, then waits for a confirmed gear increase or timeout and RPM-drop hysteresis before another pulse. It stops on pause/background, menu open, or Activity destruction.

This is an RPM-triggered upshift targeting the documented RateShift Perfect interval from the active DriveTrain. The exact Android input timing still requires on-device validation. Infinite NOS, Race Assist, and Perfect Drift remain out of scope.

## Historical calibration HUD issue (superseded)

The user's screenshot while the game displayed **Event Results** showed game-flow state `0`. This is not an active driving frame, so the telemetry reader correctly withholds RPM/gear instead of reporting stale values. The current Auto Shift controller still requires the saved active Drag event before it may issue a shift.

The earlier manual calibration popup has been removed. Auto Shift now derives its threshold from the active car's engine range, so no Stop overlay or manual per-car calibration is needed.

## Pause while Mod Menu is open

The MOD HUD action reuses the exact same guest input path as the existing HUD Pause control: DirectInput scan code `0x01` (Escape). The Java helper sends a short key-down pulse and releases it after 140 ms, using the existing `nativeKey` wrapper that the touch controls already use. This delegates the pause state to the game's own pause menu rather than introducing an unverified native JNI entry point.

When the Mod Menu dialog is dismissed (Close, Save settings, All OFF, Back, or calibration start), the same Escape pulse is sent again to resume the game. The temporary native `nativeSetModMenuPaused` bridge was removed after inspecting the built APK and finding that the workflow packages prebuilt game native libraries; it did not contain that newly added JNI symbol. The implementation now uses only the already-present Escape input API.

On-device verification is still required to confirm that this pause toggle freezes gameplay and that the second Escape pulse resumes cleanly on the user's Android device.

## Latest CI failure and retry

The initial Auto Pause build reached the Gradle build step after successful checkout, Java setup, prebuilt native extraction, Rust native builds, and native free-roam companion compilation. It failed because the Gradle 8.9 distribution download returned HTTP 504 from the GitHub release redirect; no APK artifact was produced. The workflow now retries `./gradlew assembleRelease --no-daemon` up to three times to handle transient distribution CDN failures.


Implemented on `feature/mod-menu-v0.2`:
- The existing HUD has a dedicated MOD control wired to `GameActivity.showModMenu()`.
- The panel exposes Master, event-scoped experimental Auto Shift, Infinite NOS, Race Assist, and Perfect Drift settings.
- Auto Shift target RPM is calculated from the active vehicle's own idle/max RPM range; no fixed RPM slider is present.
- Settings are stored separately at `<gameRoot>/mods/NFSU2_ModMenu/modmenu.ini`.
- All options default OFF. Saving uses a temporary file and a backup/rollback path to avoid deleting a prior configuration on a failed write.
- The MOD HUD control remains available when the touch-control layout is hidden.
- Pressing MOD pauses the guest game simulation while the Mod Menu dialog is open; dismissing the dialog resumes gameplay.
- `Yogie Clharava` appears only in the Mod Menu bottom action bar, centered in the gap after `All OFF` and before `Close` / `Save settings`. No watermark is drawn on the normal gameplay HUD.

Implemented as read-only diagnostic groundwork:
- The Android free-roam companion publishes a guarded snapshot of game-flow state, player-car speed, RPM, throttle and raw gearbox value from the matching NFSU2 v1.2 guest address space.
- The Mod Menu exposes a manual **Read live telemetry** action. It does not write to the game. Invalid snapshots now report the observed game-flow value or explain that the car/gearbox pointer is not ready.
- The previous Mod Menu included a manual **Start shift calibration** capture. It has been removed from the current implementation; Auto Shift now reads each vehicle's own engine RPM range.
- Starting calibration now closes the modal settings dialog and opens a small non-modal status/Stop overlay, so the player can continue using touch shift controls while samples are collected. The overlay updates live and stopping it reports the captured RPM range/average.
- The snapshot is copied under a mutex; pointers are checked against committed guest-memory ranges before dereference.
- The raw gear counter is interpreted as 0 reverse, 1 neutral, 2 first gear, and so on.

Not implemented:
- The experimental Auto Shift controller can send the mapped Shift Up key in its specifically armed event when live tuned Drag threshold/throttle/gear conditions match. This is an RPM-triggered prototype, not a confirmed Perfect Shift implementation.
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

The reference suggests a better long-term shape for Auto Shift: evaluate a validated shift condition from the game execution/frame context, then submit a shift-up input through the port's existing input path. NFSU2's current build is an x86 guest executable running inside the Android recompiler; its own guest addresses and state must be mapped independently. The current Java controller remains an experimental RPM-triggered implementation using the active tuned CPI shift threshold until its timing is verified in the actual Drag event. The reference APK does not prove that the NFSU2 green Perfect Shift window is mapped.

The reference's pause symbols are not needed for the already-working NFSU2 MOD pause/resume path. Keep the existing guest pause/input path unchanged.

