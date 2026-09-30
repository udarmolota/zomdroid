# Project Viewpoint on ZINK_OSMESA — investigation brief

Date: 2026-09-30. Audience: a reviewer who has not seen the earlier sessions.

## What this is

Project Viewpoint is a first-person 3D mod for Project Zomboid Build 42.21, loaded through
ZombieBuddy. It brings its own deferred renderer and needs desktop OpenGL 4.5 (direct state access).
In Zomdroid only the two ZINK renderers expose that, and only on Snapdragon with Turnip.

One test device so far: Galaxy S25 Ultra (Adreno 830, 12 GB), debug build 1.5.1 of the launcher,
game 42.21, mods: Viewpoint + ZombieBuddy + the community "ZombieBuddy B42.21 Temporary Fix"
(Workshop 3807686870).

**This brief asks for an investigation and a proposal. Do not change code, commit or build an APK;
the maintainer approves each of those separately.**

## Constraint: the mod itself is off limits

Viewpoint's licence forbids modifying, repackaging, redistributing and decompiling it for reuse.
Everything must be solved on the launcher side (GLFW backend, Mesa build, JVM settings). Do not
propose patching or intercepting the mod's own code or its build check.

## What already works

- The mod's game-build check passes: `[Viewpoint] game build: Build 42.21.0, as pinned`. This needs
  the uncommitted launcher change `patch/CoreFromGameJar.java` plus the class-path line in
  `game/GameInstance.java` (the mod hashes the jar `zombie.core.Core` was loaded from).
- On ZINK_OSMESA the mod initialises with no GL error. The context is
  `4.6 (Compatibility Profile) Mesa 25.0.2`, `zink Vulkan 1.4 (Turnip Adreno 830)`.

## Evidence

Logs, copied out of the device:

- `C:\Users\user\Desktop\PZ\viewpoint-logs\native_d.log` — ZINK_OSMESA, the run described below.
- `C:\Users\user\Desktop\PZ\viewpoint-logs\native_c.log` — ZINK_ZFA, aborts at
  `GL45C.nglCreateTextures` ("FATAL ERROR in native method", line 1973).

Useful markers in `native_d.log`: `[Viewpoint]` (the mod's own timing lines), `f:<n>>` (frame
number), `[ZMEM]` (our RSS/swap sample every 30 s), `[info][gc]` (JVM collections).

## Question 1 — why do frames stop at f:476? (highest priority)

Observed: the world loads and renders, then the maintainer moved the mouse and the screen went
black except for the launcher's on-screen buttons. In the log the last frame is `f:476`, about
100 s after start. There is no exception, no abort and no crash file; afterwards `[ZMEM]` shows RSS
falling and swap rising, which looks like a stalled or backgrounded process rather than a dead one.

Known facts that may be related:

- `native_d.log:1932` — the mod calls `glfwRawMouseMotionSupported()`; our backend raises
  `GLFW_FEATURE_UNIMPLEMENTED` and returns false.
- In `app/src/main/cpp/glfw/src/zomdroid_window.c`:
  - `_glfwSetCursorModeZomdroid` is a no-op, so `GLFW_CURSOR_DISABLED` (cursor capture with
    unbounded virtual position) does nothing;
  - `_glfwSetRawMouseMotionZomdroid` and `_glfwRawMouseMotionSupportedZomdroid` are unimplemented;
  - `_glfwSetCursorPosZomdroid` only stores the value; `_glfwCreateCursorZomdroid` returns false
    (the log has two `Error creating GLFW cursor` exceptions from the game itself, lines 1048/1060).

What to establish:

1. Is the stop a deadlock, an endless loop or a render that keeps running but draws black? The
   Java thread states at the moment of the hang would answer it (`kill -3` / `jstack` equivalent
   through `run-as`, the app is debuggable).
2. Does it correlate with mouse input at all, or with something else at that moment (the log shows
   the JVM heap at 1267 of 1382 MB with back-to-back concurrent mark cycles)?
3. If it is mouse-related: what does a first-person mod need from GLFW that we do not provide —
   `GLFW_CURSOR_DISABLED` semantics, relative motion deltas, raw motion — and how should each map
   onto Android input (touch look, physical mouse via pointer capture, gamepad stick)?

Deliverable: the cause, with the evidence, and a proposed change to the GLFW backend. A general
solution is wanted (any first-person mod), not one keyed to Viewpoint.

## Question 2 — where do 45 ms per frame go outside the mod?

After warm-up the mod reports frames of 55–60 ms (16–18 fps), for example:

```
f:409> slow frame on the render thread: 52.5 ms, our passes 10.2 (...), the rest 42.2
       (the game's own drawing, or waiting for the main thread)
f:409> slow frame on the main thread: 54.9 ms, ours 10.9 (...), the rest 44.0
       (the game's update, the collector's pauses)
```

The mod's passes cost 6–15 ms; the remaining ~45 ms is attributed to the game or to waiting. The
very first summary line (`0 fps`, g-buffer 183 ms, shadows 122 ms at f:400) is shader warm-up and
should not be used as the steady state.

What to establish:

1. The baseline: the same save, same place, same renderer (ZINK_OSMESA) **without** Viewpoint, and
   on ZINK_ZFA without it. FPS can be read from the `f:<n>` lines against the log clock or from
   the launcher's performance overlay.
2. Whether the OSMesa path adds a per-frame copy or readback between Mesa's off-screen buffer and
   the Android surface, and how much it costs at the device's resolution. Relevant code: the
   OSMesa context path in `app/src/main/cpp/glfw/src/` and the Mesa build recipe and patches in
   the `zomdroid-dependencies` repository (`patches/mesa/`).
3. Whether the main thread is actually waiting on the render thread (or the reverse), or on GC:
   the heap is nearly full during the run.
4. Whether our `SpinIdle` agent advice (1 ms park in `GameWindow.mainThreadStep` when the step
   took < 100 µs, and in `acquireStateForRendering` when it returned null; disable with
   `-Dzomdroid.idleNanos=0`) contributes here.

Deliverable: a breakdown of the 45 ms with measurements, and which part is ours to fix.

## Question 3 — can ZINK_ZFA be made to work instead of OSMesa?

ZINK_ZFA is the faster of our two ZINK paths, but the mod aborts there on the first GL 4.5 call:
`libzfa.so` exports only the GL functions listed in Mesa's `src/mapi/glapi/gen/static_data.py`
`functions` list, and 181 functions from GL 4.0+ (including `glCreateTextures`) are not in it.
LWJGL 3.4.1 resolves GL functions through `dlsym` and through `glXGetProcAddress`,
`glXGetProcAddressARB`, `eglGetProcAddress` or `OSMesaGetProcAddress`, whichever the library has.
`patches/mesa/0004-timer-query-exports.patch` added three functions to that list for PZ3D; doing
the same for 181 is the brute-force option.

What to establish: the smallest change that makes every GL function Mesa implements resolvable
from `libzfa.so` — a `*GetProcAddress` entry point under a name LWJGL probes, versus extending the
static export list — and what each costs to maintain across Mesa updates.

Deliverable: a recommendation with the patch outline. Answering Question 2 first may make this
unnecessary (if OSMesa's overhead is small) or urgent (if it is the 45 ms).

## Question 4 — memory headroom

`[ZMEM]` during the run: RSS 2.9 GB, available memory about 0.9–1.4 GB on a 12 GB phone. The mod
logs `mesh arena: buffer textures reach 134217728 texels, 1023 MiB of arena` and the JVM heap sits
at ~1267 of 1382 MB.

What to establish: how much of the 2.9 GB is the mod's (compare with the baseline run from
Question 2), whether the 1023 MiB arena is reserved or committed, and whether a larger `-Xmx`
removes the back-to-back GC cycles. The goal is only to know which devices can run it at all
(12 GB only, or 8 GB too); no memory work on the mod is possible or wanted.

## Out of scope

- Non-Snapdragon devices. GL 4.5 exists here only through ZINK on Turnip.
- NG_GL4ES and GL4ES: they cannot expose GL 4.5.
- The ZombieBuddy 42.21 breakage: fixed by the Workshop item above, not by us.
- Anything that alters, repacks or bypasses the mod.

## Order

1 → 2 → 3 → 4. Question 1 decides whether the mod is usable at all; stop and report after it if
the cause turns out to be outside the launcher.
