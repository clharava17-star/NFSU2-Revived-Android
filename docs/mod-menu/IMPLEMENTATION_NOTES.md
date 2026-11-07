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

## Current status — v0.2 prototype

Implemented on `feature/mod-menu-v0.2`:
- The existing HUD has a dedicated MOD control wired to `GameActivity.showModMenu()`.
- The panel exposes Master, Auto Shift, Infinite NOS, Race Assist, and Perfect Drift settings.
- Auto Shift target RPM is configurable from 7,000 to 11,000 RPM.
- Settings are stored separately at `<gameRoot>/mods/NFSU2_ModMenu/modmenu.ini`.
- All options default OFF. Saving uses a temporary file and a backup/rollback path to avoid deleting a prior configuration on a failed write.
- The MOD HUD control remains available when the touch-control layout is hidden.
- A low-opacity `Yogie Clharava` watermark is drawn at the bottom-center of the touch HUD and in the Mod Menu panel footer.

Not implemented:
- No cheat currently changes live gameplay.
- Auto Shift / Perfect Shift has no verified shift-judgement hook.
- Infinite NOS has no verified race NOS hook.
- Race Assist has no verified opponent or race-result hook.
- Perfect Drift has no verified drift scoring or vehicle-control hook.

Validation:
- Source integration points and branch contents were inspected through GitHub.
- The PR has no reported GitHub Actions run/status for the latest commit yet; APK compilation and device testing remain outstanding.
- Do not merge this draft or distribute it as a functioning cheat APK until the Android build and on-device regression tests pass.
