# Known issues — triage reference

What we already know, so a bug report is not investigated from scratch. Ordered by how often it
turns up in reports. Each entry: the signature you can grep for in a report, what it really is, and
what to answer. Last updated 2026-09-26.

**Before anything else: ask which mods are installed.** A large share of "the game does" reports are
one specific mod — see "Mods known to cause complaints" below.

---

## 1. Old ByteBuddy in our agent (Java 25 class files)

**Signature (native.log):**
```
java.lang.IllegalArgumentException: Java 25 (69) is not supported by the current version of
Byte Buddy which officially supports Java 24 (68)
```
Often followed by `UnsupportedClassVersionError ... class file version 69.0 ... up to 65.0` when the
instance also runs on JRE 21.

**What it is:** our agent ships **unrelocated ByteBuddy 1.17.4**, which also shadows ZombieBuddy's
1.18.8 when both are loaded. That shadowing is why `InstallerService.replaceZbBetterFpsJars`
substitutes the author's `ZBBetterFPS.jar` with our `.ver21` build. Mod authors have since worked
around it on their side.

**Answer:** known, ours. A class file of version 69 in an instance means something was compiled with
JDK 25 — check what JRE that instance launches with.

---

## 2. Budget PowerVR B-Series (BXM-8-256): Build 41 has no working renderer

**Signature:** hundreds of `TextureFBO.initInternal> glCheckFramebufferStatus =
GL_FRAMEBUFFER_UNSUPPORTED` + `Could not create FBO!`, always in the character texture combiner, so
characters and zombies never appear (character creation shows no model).

**What it is:** unproven, but the best lead is that B41 attaches one renderbuffer as both
`GL_DEPTH_ATTACHMENT` and `GL_STENCIL_ATTACHMENT`, while gl4es silently downgrades
`GL_DEPTH24_STENCIL8` to depth-only on any Imagination GPU.

**Dead ends — do not re-run:** `LIBGL_FBOFORCETEX=0`, `LIBGL_FBOUNBIND=0`, `LIBGL_MIPMAP=0` (all
tested by players, all still failed), BGRA, `GL_COMPRESSED_RGBA`, vendor misdetection. ZINK on the
same hardware crashes or breaks font atlases; NG_GL4ES shows no models on B41.

**Answer:** we have no working renderer for that chip on Build 41 yet.

---

## 3. Broken game packaging (black screen, no TIS splash)

**Signature:**
```
MainScreenState.loadIcons> javax.imageio.IIOException: Can't read input file!
NullPointerException at Checks.check -> GLFWImage.validate -> glfwSetWindowIcon
  -> Display.create -> Core.init -> RenderThread.init
```

**What it is:** the root-level `projectzomboid.png` is missing or its case does not match, so the
window is never created and the render thread never starts. The player sees a black screen with our
on-screen buttons. Always packaging, never the renderer — reporters usually arrive having tried
every renderer and driver.

**Answer:** re-import the game. A GOG `.sh` installer can be zipped **as it is** and fed to the
launcher; our extractor reads the ZIP64 appended to the script. Manual repacking is what breaks it.

---

## 4. Hosting: friends get "Connection failed" (double NAT / CGNAT)

**Signature (`hosting-internet.properties`):** `state=mapped` but `externalAddress` is private —
`10.x`, `100.64–127.x`, `192.168.x`, `172.16–31.x`. The server log shows `*** SERVER STARTED ****`
and **zero** incoming connections.

**What it is:** UPnP opened the port on the player's own router, but that router sits behind the
provider's NAT, so nothing from the internet reaches it.

**Answer:** ZeroTier or Tailscale (what the Chinese testers use), a public IP from the provider, or
forwarding on the upstream router in a two-router home. Our UI does append a warning about a
non-public address, but the status strip still reads "Internet: port mapped" — easy to miss.

---

## 5. NVIDIA Tegra (Switch Lite): invisible characters

**Signature:** 15 of 19 shader programs fail to link; `failed_shaders.txt` shows the compiler
rejecting gl4es's injected int overloads of `min`/`max`/`clamp`.

**Answer:** known, gl4es-side, unfixed.

---

## 6. Memory kills (no crash in the log at all)

**Signature:** no `hs_err`, no Java exception, no FATAL — the log simply stops, and the launcher
process restarts afterwards. `[ZMEM]` lines show RSS climbing past ~5 GB with swap growing into the
gigabytes; `GLALLOC` shows 1.5–4 GB of live textures. G1 logs `Evacuation Failure` when the Java
heap is also full.

**Answer, in order of effect:** turn on **Compressed textures** in the game's options (check
`options.ini`: `textureCompression=false` with `texture2x=true` is the worst case), turn off 3D
ground items, enable Memory saver, add `LIBGL_SHRINK=7`, raise `-Xmx` if the phone has the RAM, and
drop texture packs such as ETO.

---

## 7. Shader compilation freezes on weak Mali

**Signature:** `gl_trace.txt` with tens of `COMPILE`/`LINK` operations over 100 ms, some over one
second, spread across the whole session; `CCACHE session: hits=0 miss=0`.

**What it is:** no cache covers the game's own shader programs. PSA stores only gl4es's
fixed-pipeline programs, and the conversion cache is opt-in (`LIBGL_CONVCACHE=1`) and disabled after
a crash on its hit path. Reinstalling the app does not help — it wipes every cache.

---

## 8. File picker can hard-crash the app

**Signature:** `ActivityNotFoundException` and the whole app dies when the player taps any
import/export button. Happens on devices with no SAF provider installed. 25 launch sites, only 2
guarded. Ours, unfixed.

---

## 9. Report-header traps (read these before trusting a report)

- **Renderer in the header is the setting at export time**, not the one the logged session ran with.
  The run itself is self-describing: `zomdroid.renderer=` and `org.lwjgl.opengl.libname=` in
  `native.log`. Two investigations were nearly derailed by this.
- **Bundled game logs can be from an earlier session** than the launcher log. Check the dates.
- Preset names are only "Build 41", "Build 42" and "Build 42.12+". A report quoting anything else
  (e.g. "42.7+") is paraphrasing.
- `console.txt` / `debuglog.txt` missing entirely usually means the game never ran far enough to
  write them — that absence is itself information.

---

## Mods known to cause complaints

| Mod | Complaint it produces |
|---|---|
| NC_NaturesCall | right stick stops working |
| Buttstroke | push/shove does not work |
| BicycleMod | very slow turning |
| ZomboRut | animations do not play (needs DBFaster50) |
| ETO / texture packs | memory kills on 6–8 GB phones |
| KI5 / Autotsar vehicles | Build 41 interface turns green near modded cars — GL4ES only, workaround: turn reflections off |
