# Mod Menu v0.2 build checkpoint

This commit is intentionally used to run CI against the complete current feature branch.

## Required CI checks
- Compile Android release variant for arm64-v8a.
- Include the HUD MOD entry point and persistent configuration panel.
- Use the separate application ID `io.mininxd.nfsu2.modmenu`.
- Use debug signing only for test artifacts when owner-managed private signing secrets are absent.
- Do not publish a GitHub Release unless all four private signing secrets are configured.

## Important artifact provenance
The first successful workflow dispatch was run from commit `32a1b2b5cce0f453f121c2953f0b380b14f7a17b`. It predates the persistent settings panel, separate application ID, and signing hardening added later on this feature branch. That APK is a historical build and must not be installed or distributed as the current Mod Edition.

The next APK is considered a candidate only when its workflow run references this commit or a later feature-branch commit and the package ID and signing details have been inspected. Device testing is still required.
