# Mach-O integration status — 2026-09-12

Follow-up to `macos-eh-handoff.md`. No APK assembled, no commit or push.

## Implemented

- Detect local `JNIEnv_` helpers through bounded LC_SYMTAB parsing, not only exports.
- Refuse wrapped-JNI libraries when wrapper initialization fails. Reject JNI entries without a usable signature instead of returning an incompatible raw entry.
- Identify wrapped environments by their table pointer without reading beyond a real JNIEnv object.
- Wrap GetJavaVM and its GetEnv/AttachCurrentThread/AttachCurrentThreadAsDaemon results; preserve JVMTI interfaces, JNI failures and detach behavior.
- Preload/cache macOS PathFind after entering the game directory and before starting the JVM. Only successful loading enables Pathfind.UseNativeCode; Java prepares false first. Option replacement uses a temporary file and rename.
- Rejected macOS PopMan/PathFind bypass TIS ARM and fall back to Linux through box64. Bullet/Clipper remain outside the Mach-O routing map.
- Ensure CMake builds the separate EH runtime and validates the required static archives.

## Verified

Gradle externalNativeBuildDebug, mergeDebugNativeLibs, compileDebugJavaWithJavac and processDebugResources succeeded. The merged native packaging inputs contain libzomdroid_macho_eh.so and libc++_shared.so. This is not an APK inspection.

`tools/test-macho-integration.ps1` ran successfully on the connected phone (RFGYB08X13B), using the Gradle-merged EH runtime: zero failures. It covers JNIEnv/JavaVM wrappers (including a worker thread), atomic PathFind options, eleven throw/catch cases, real PopMan/PathFind loading with exceptions and wrapped JNIEnv, and construction of eighteen Lighting bridge variants. Bridge construction is not full gameplay execution.

The test script requires the existing EH probe dylibs via ProbeDirectory; it does not download or distribute game binaries.

## Next: manual game test

Build/run from Studio, use B42.20+ with the downloaded macOS libraries enabled. Use a disposable world or back up the save first.

1. Check logs for PopMan and PathFind with `exceptions on, JNIEnv wrapped` and successful native PathFind preflight.
2. Walk routes involving placement of items on a table.
3. Save, leave and reload; check character, items and zombie population state.
4. Export the log after the test.

Not yet proven: gameplay save/load roundtrip, real PopMan worker callbacks, and all exception paths. No catch boundary was added around JNI; the handoff explicitly defers it until evidence requires it. Exceptions thrown from Android C++ shims remain a known separate-runtime limitation.
