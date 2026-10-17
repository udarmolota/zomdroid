# NOTICE

This file records what Zomdroid is, what it does **not** contain, what it changes on the user's own
device, and every third-party component it redistributes, with its licence.

Zomdroid is a non-commercial project. It is not sold, contains no advertising, and has no paid
features. Donations are voluntary and buy nothing.

---

## 1. Not affiliated with The Indie Stone

**Zomdroid is not developed by, endorsed by, or affiliated with The Indie Stone in any way.**

*Project Zomboid*, the Project Zomboid logo and *The Indie Stone* are trademarks or registered
trademarks of The Indie Stone. They are referred to here only to describe what this launcher is
compatible with — nominative use, no claim of ownership or association.

## 2. What Zomdroid does not distribute

Zomdroid ships **no part of Project Zomboid**. Specifically, this repository and the built
application contain:

- no game code, compiled or otherwise — no `.class` files, no `.jar` files belonging to the game;
- no game assets — no textures, models, sounds, maps, scripts or translations;
- no game binaries — the launcher does not carry any `lib*.so` shipped by the game;
- no licence keys, no account credentials, no means of bypassing any purchase or activation.

The user supplies the game themselves, from a copy they legally own, through the platform they
bought it on. Zomdroid reads that copy from the device's own storage. It never redistributes it.

## 3. Changes Zomdroid makes to the user's own copy

To run an x86-64 desktop game on ARM64 Android, the launcher adapts a small number of the game's
class files **in the copy already installed on the user's device**. Nothing is uploaded, published
or shared; the edits exist only on that device. In every case the untouched original is kept beside
the modified file with a `.disabled` suffix, so the change is reversible by deleting one file.

| Class | Change | Why |
|---|---|---|
| `zombie.core.znet.ZNetStatistics` | Two field names removed in game build 42.15 are re-added as unused `public long` fields | The Android build of the game's own RakNet library was never rebuilt and still looks those names up, so joining a statistics-enabled server killed the client |
| `zombie.gameStates.MainScreenState` | `printSpecs()` body emptied | Its hardware inventory walk crashes on Android, on the main menu, before the player can act |
| `zombie.core.opengl.ShaderUnit` | A flag field is flipped to enable the game's own `combineShaderSources` mode | OpenGL ES forbids the multi-unit shader linking the desktop build relies on |

The launcher also renames some of the game's own Android native libraries to `.disabled` when their
exported JNI methods do not match the game's Linux build, so the complete Linux library is used
instead. Again: a rename on the user's device, reversible, nothing redistributed.

## 4. Zomdroid's own licence

Zomdroid is released under the MIT Licence — see [LICENSE](LICENSE).

## 5. Third-party components

### Proprietary

| Component | Where | Rights holder |
|---|---|---|
| **FMOD Studio / FMOD Core** — `app/jars/fmod.jar`, `libfmod.so`, `libfmodstudio.so` (versions 2.02.06, 2.02.24, 2.03.09) | `assets/bundles/libs.tar.xz` | Firelight Technologies Pty Ltd — <https://www.fmod.com/> |

FMOD is **not** open source. It is included because Project Zomboid requires it, and it is subject
to Firelight's own licensing terms, not to Zomdroid's MIT licence. Firelight retains all rights.
Anyone redistributing this project must satisfy FMOD's licence independently.

### GNU licences — source offer

| Component | Version | Licence |
|---|---|---|
| **OpenJDK** (Android build by [PojavLauncherTeam](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch)) | 21.0.1 and 25.0.3 | GPL v2 **with the Classpath Exception** |
| **GNU C Library (glibc)** — `libc.so.6`, `libpthread.so.0` | as shipped | LGPL v2.1 or later |
| **GCC runtime** — `libstdc++.so.6`, `libgcc_s.so.1` | as shipped | GPL v3 with the GCC Runtime Library Exception |
| **MobileGlues** — `libMobileGluesZomDroid.so`, the "astra" fork by [constantinrarinca3-sketch](https://github.com/constantinrarinca3-sketch/MobileGlues) (Willing-Upstairs-580, aka mepetz) of [MobileGL-Dev/MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) | branch `zomdroid-pz-stable-astra-clean-candidate` @ 414c2c9 | LGPL v2.1 |

The complete corresponding source for these components is available from their upstream projects
([OpenJDK](https://github.com/openjdk/jdk), [glibc](https://sourceware.org/glibc/),
[GCC](https://gcc.gnu.org/), [MobileGlues fork](https://github.com/constantinrarinca3-sketch/MobileGlues/tree/zomdroid-pz-stable-astra-clean-candidate))
and, for the Android OpenJDK build, from the PojavLauncherTeam repository linked above. On request we will provide the exact sources corresponding to the binaries
shipped in a given release; open an issue at
<https://github.com/udarmolota/zomdroid/issues>.

These components are dynamically loaded and are not linked into Zomdroid's own code.

### Open-source components

| Component | Version / revision | Licence | Source |
|---|---|---|---|
| Box64 | submodule `zomdroid-box64` | MIT | <https://github.com/ptitSeb/box64> |
| GL4ES | commit `a744af14` | MIT | <https://github.com/ptitSeb/gl4es> |
| Mesa (Zink, Freedreno/Turnip) | `mesa-25.0.2` | MIT | <https://gitlab.freedesktop.org/mesa/mesa> |
| OSMesa, and `libzfa.so` (the Gallium target built with `-Dzfa=true`) | from the same Mesa build | MIT | as above |
| NG-GL4ES / *Krypton Wrapper* | Release 0.4.1 | MIT — © 2013-2016 Ryan Hileman, © 2016-2018 Sebastien Chevalier, © 2025 BZLZHH | <https://github.com/BZLZHH/NG-GL4ES>, a fork of [gl4es](https://github.com/ptitSeb/gl4es) and [gl4es-114-extra](https://github.com/PojavLauncherTeam/gl4es-114-extra); our build: <https://github.com/udarmolota/NG-GL4ES> |
| GLFW | submodule `zomdroid-glfw` | zlib/libpng | <https://github.com/glfw/glfw> |
| LWJGL | 3.2.3, 3.3.6, 3.4.1 | BSD-3-Clause | <https://github.com/LWJGL/lwjgl3> |
| libffi (inside LWJGL builds) | 3.4.8, 3.5.0 | MIT | <https://github.com/libffi/libffi> |
| Open Asset Import Library (assimp) | v5.4.3 | BSD-3-Clause | <https://github.com/assimp/assimp> |
| SPIRV-Cross | as built | Apache-2.0 | <https://github.com/KhronosGroup/SPIRV-Cross> |
| jemalloc | as built | BSD-2-Clause | <https://github.com/jemalloc/jemalloc> |
| SQLite JDBC | 3.48.0.0 | Apache-2.0 | <https://github.com/xerial/sqlite-jdbc> |
| Byte Buddy | 1.17.4 | Apache-2.0 | <https://github.com/raphw/byte-buddy> |
| ANTLR 4 runtime | 4.13.2 | BSD-3-Clause | <https://github.com/antlr/antlr4> |
| Gson | 2.10.1 | Apache-2.0 | <https://github.com/google/gson> |
| Apache Commons IO | 2.18.0 | Apache-2.0 | <https://commons.apache.org/proper/commons-io/> |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | <https://commons.apache.org/proper/commons-compress/> |
| XZ for Java | 1.10 | public domain (0BSD) | <https://tukaani.org/xz/java.html> |
| Bouncy Castle | 1.83 | Bouncy Castle Licence (MIT-style) | <https://www.bouncycastle.org/> |
| Protocol Buffers (Java) | 4.31.1 | BSD-3-Clause | <https://github.com/protocolbuffers/protobuf> |
| zstd-jni | 1.5.7-6 | BSD-2-Clause | <https://github.com/luben/zstd-jni> |
| JavaSteam | 1.8.0 | see project | <https://github.com/Longi94/JavaSteam> |
| liblinkernsbypass | as built | see project | <https://github.com/bylaws/liblinkernsbypass> |
| AndroidX, Material Components | see `gradle/libs.versions.toml` | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| Android NDK runtime — `libc++_shared.so` | NDK | Apache-2.0 with LLVM Exception | <https://llvm.org/> |

Some components carry an Apache-2.0 `NOTICE` of their own; those notices are preserved inside the
artefacts we redistribute and are reproduced by their upstream projects at the links above.

Where a shipped filename does not match the project name: `libjassimp64.so` is assimp,
`libng_gl4es.so` is NG-GL4ES, `libsqlitejdbc.so` is SQLite JDBC's native part, and
`libvulkan_freedreno*.so` / `libvulkan.ad0*.so` / `vulkan.turnip.*.so` are Mesa's Freedreno/Turnip
Vulkan driver built for different Adreno generations.

The MIT licences of GL4ES and NG-GL4ES require their copyright notices to travel with the binaries;
those notices are reproduced in full in the `LICENSE` files of the respective upstream projects
linked above, and the NG-GL4ES notice names all three of its copyright holders as listed in the
table.

### Written by us

| Component | Notes |
|---|---|
| `libBink2x64.so` | **Not** RAD Game Tools' Bink. An empty stub ELF written for this project ([tools/bink-stub/bink_stub.c](tools/bink-stub/bink_stub.c)) that exists only so the game's `System.loadLibrary("Bink2x64")` succeeds instead of filling the log with stack traces. It contains no code from RAD Game Tools and plays no video. |
| `libjniwrapper.so`, `libpthread_wrapper.so` | Written for this project — <https://github.com/udarmolota/zomdroid-dependencies> |
| `zomdroid-agent.jar` | Written for this project; embeds Byte Buddy and the ANTLR runtime, both credited above |

### Bundled third-party mod

`app/src/main/assets/patches/ZBBetterFPS.jar.ver21` is a community Project Zomboid mod, included so
that users who already use it can enable it. It is not our work, it is inactive unless the user
turns it on, and all rights remain with its author. If the author would prefer it not be bundled,
open an issue and it will be removed.

## 6. Inspiration

Zomdroid's approach was informed by [Winlator](https://github.com/brunodev85/winlator),
[Termux](https://github.com/termux/termux-app) and
[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher). No code was taken from them.

---

## Corrections

If you hold rights in anything listed here and believe it is credited wrongly, licensed wrongly, or
should not be present at all, please open an issue at
<https://github.com/udarmolota/zomdroid/issues>. Attribution errors will be corrected and removal
requests acted on.
