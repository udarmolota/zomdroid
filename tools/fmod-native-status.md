# Native FMOD integration experiment — 2026-09-12

Enable with environment variable `ZOMDROID_NATIVE_FMOD=1` (not a JVM argument). Unset or any other value keeps the existing box64 integration. APK not built; game test pending.

## Evidence
- TIS ARM64 integration from 42.20.0 and 42.20.4 is byte-identical (SHA256 1F5BDE1E6FC70B46DD192351164FD44B9D086B13600D4CAEB6237D4357BFE7C0).
- Linux and ARM64 in 42.20.4 export the same 437 Java JNI names. This does not prove semantic equivalence.
- ARM64 dependencies are Android libfmod.so/libfmodstudio.so, libc/libm/libdl. Integration was built for FMOD 2.03.09; current phone log reports 2.03.09 runtime too.
- No own JNI_OnLoad in the integration. A standalone device dlopen/dlsym probe confirmed JNI_OnLoad resolves to dependency libfmod.so, while the Java System_Create entry resolves to libfmodintegration64.so. This is a concrete initialization hazard, not yet proof of the original historical failure.
- Probe also resolved globalSystem and the core setOutput symbol. Disassembly confirms globalSystem is populated by Studio::System::getCoreSystem.

## Changes
- Opt-in native loading with ordinary box64 fallback on dlopen/compatibility rejection.
- Reject integration with its own JNI_OnLoad rather than suppress an unknown future initializer.
- Suppress dependency JNI_OnLoad/JNI_OnUnload only for the native integration handle. Android FMOD libraries are already initialized on ART.
- Wrap System_Create to attach the caller to ART while retaining HotSpot JNIEnv for the integration.
- Preserve AAudio/OpenSL selection after System_Create and before the separate System_Init, via globalSystem and core setOutput.
- Preserve the existing getAudioDevices stub in native mode.

Native Gradle compilation and mergeDebugNativeLibs succeeded. Standalone probe succeeded on the connected phone; it deliberately does not initialize audio or exercise JNI callbacks.

## Game acceptance
1. Run from Studio; enable environment flag and keep macOS libraries enabled.
2. Confirm `[linker] android/arm64-v8a/libfmodintegration64.so loaded natively`, `[fmod-native]` initializer suppression and System_Create success.
3. Verify music, world sounds, combat, multiple emitters, pause/resume and clean exit/relaunch.
4. Test both selectable audio APIs; verify logged output result is zero.
5. Multiplayer VOIP transmit/receive is separate and mandatory before general rollout: native callbacks now run directly rather than through box64 callback bridges/stubs.
6. Missing native file / flag off must keep working via box64.

No runtime fallback is promised after native initialization starts. If the experiment fails, remove the flag and restart. Box64 still initializes eagerly; this change alone does not eliminate its startup cost.
