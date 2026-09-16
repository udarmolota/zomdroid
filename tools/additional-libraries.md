# Additional library downloads

## Sources and UI

Instance settings contain one **Additional libraries** card. The applicable pack is selected
from the instance, not from a user-selectable depot number:

- B42.20+: the existing macOS pack, six dylibs, current public macOS depot 108602.
  Default-off per-instance switch; the Mach-O native-loader contract remains unchanged.
- B41 (`Build 41` preset): multiplayer ARM64 files from Linux depot 108603,
  manifest **249541819024555413** supplied by Inna on 2026-09-11 as the B42.12 source.
  This is pinned, never replaced by public/latest or a guessed legacy branch. Authorization
  requests use public with that explicit manifest. Old-manifest access still needs an
  authenticated device test with an owning Steam account.

MP selects only `android/arm64-v8a/libRakNet64.so` and
`android/arm64-v8a/libZNetNoSteam64.so`, with an optional `projectzomboid/` depot prefix.
The desktop copies with identical names are rejected. Both downloads use the same login,
progress, cancellation, resume, Steam SHA-1 verification and generated SHA-256 metadata.
MP additionally verifies ELF64 little-endian AArch64 shared-library headers before publishing.

## MP installation

Unlike the Mach-O loader, the existing B41 native loader already looks in
`<game>/android/arm64-v8a`. Keep that contract: no native code changes or new runtime flags.
MP has a download button and status, not an enable switch. Successful installation connects
the files automatically on the next game launch, just like the existing ZIP installation.

Files download to `<game>/.mp-b41-download-<depot>-<manifest>` first. Only after both files
are verified, copy unrelated existing native files into the stage, move the current native
directory to `<game>/android/.zomdroid-mp-previous`, then publish the stage. Keep that backup
until the next replacement. On failure restore it; at B41 launch also recover an interrupted
directory rename. Only the two requested libraries and `zomdroid-mp-manifest.json` are replaced.
The original ZIP import remains unchanged. A manually installed pair gets a distinct status:
present, but source version unknown (not falsely labelled the verified Steam pack).

An earlier suggestion to keep MP in a completely separate active directory was not used:
it would require changing the native loader being developed in parallel. Instead existing
native files are preserved and the replaced pair remains recoverable in the backup.

## Checks and pending integration

Unit tests cover exact path filtering, the pinned source, architecture rejection, preservation
of other native files, backup recovery and foreign staging-directory rejection. The original
macOS tests remain in the same test run. No APK build, commits or pushes are part of this step.
Still test from the device: Steam login -> two-file download -> B41 MP connection, plus a
macOS download smoke test after the shared-downloader refactor. No proprietary binaries are
added to the repository or APK.
