# PZ3D on ZINK_ZFA: missing timer-query entry points in libzfa.so

Review request, 2026-09-27. Please check the diagnosis and the proposed fix before anything is
built. Nothing below is committed yet except where a commit id is given.

## Review result

Reviewed against the Mesa 25.0.2 source archive and LWJGL 3.4.1 source. The diagnosis is correct,
and adding the three names to `static_data.functions` is sufficient for the narrow PZ3D fix.

- `gl_XML.py` uses membership in `static_data.functions` to mark an entry point as static.
  `mapi_abi.py` then makes that entry point non-hidden and emits the public dispatch stub. The
  functions already have dispatch slots 731-733, so no offset, `static_dispatch`, or other ABI
  change is required.
- The change does not alter the first 407 ABI-mandated GLX slots or renumber any slot. It only
  exposes three existing dispatch stubs, so adding these names is low risk.
- A registry diff found 181 core OpenGL 3.0-4.6 names absent from `static_data.functions`, but these
  timer-query functions are the **only three missing through OpenGL 3.3**. The rest begin at
  OpenGL 4.0: 27 from 4.0, 27 from 4.1, 1 from 4.2, 3 from 4.3, 116 from 4.5, and 4 from 4.6.
  Therefore exporting just these three completes the core 3.3 surface used by PZ3D. Manually
  adding all 181 names is not recommended; a resolver is the better general solution.
- Mesa 25.0.2 names the internal resolver `_mesa_glapi_get_proc_address`, not
  `_glapi_get_proc_address`. Its generated lookup table contains hidden/dynamic entry points too,
  so it can solve this class of problem comprehensively.
- A public `zfaGetProcAddress` alone would not be used by LWJGL. On Linux, LWJGL 3.4.1 probes the
  loaded library for `glXGetProcAddress`, `glXGetProcAddressARB`, `eglGetProcAddress`, and then
  `OSMesaGetProcAddress`, before falling back to direct symbol lookup. A later resolver change must
  either expose one of those names (preferably as a thin alias around a ZFA-named function) or
  explicitly teach LWJGL about `zfaGetProcAddress`. It should also be wired into Zomdroid GLFW's
  `getProcAddressZfa`, which currently performs direct symbol lookup only.
- The same LWJGL source confirms that ZINK_OSMESA should resolve the functions through
  `OSMesaGetProcAddress` when `libOSMesa.so` is the library loaded by
  `org.lwjgl.opengl.libname`; this remains worth validating on-device as a workaround.

Recommendation: ship the three-name static export patch first and test PZ3D. Design the resolver
as a separate follow-up so the narrow fix is not coupled to a change in resolution of every GL
entry point.

## Background

PZ3D (Steam Workshop 3807334881) is a first/third-person camera mod for Build 42. It runs on
ZombieBuddy, a Java agent that patches game classes at runtime. Players report "it does nothing"
on Zomdroid. Two separate problems stood in the way.

1. **Solved: Byte Buddy collision.** Our `zomdroid-agent.jar` shipped Byte Buddy 1.17.4 under its
   original package names and, being on the system class path through `-javaagent`, shadowed the
   newer Byte Buddy inside ZombieBuddy. 1.17.4 cannot read Java 25 class files, so every PZ3D patch
   failed with `Unsupported class file major version 69`.
   Fixed in `zomdroid-dependencies` (commits 71f2314, cb1a57a, c143ecb): Byte Buddy 1.17.7,
   relocated to `com.zomdroid.shaded.net.bytebuddy` (ANTLR too), `META-INF/versions/**` excluded,
   Multi-Release off, and the agent forces `net.bytebuddy.processor=ASM_ONLY` (relocated key) in
   its first static initialiser. The launcher's `app/src/coopAgent` imports were moved to the
   relocated names (uncommitted); `testCoopAgent`, `testCoopInternet` and
   `testServerAbsoluteLuaFiles` pass. After this, PZ3D installs all its patches and Insert enters
   3D mode (`[PZ3D] Enter ...` in the log).

2. **Open: the crash this brief is about.** On ZINK_ZFA (Adreno 830, Turnip, Mesa 25.0.2, context
   reports `OpenGL version: 4.6 (Compatibility Profile) Mesa 25.0.2`), entering 3D kills the JVM:

   ```
   FATAL ERROR in native method: Thread[#3,main,5,main]: No context is current or a function
   that is not available in the current context was called. The JVM will abort execution.
       at org.lwjgl.opengl.GL33C.glQueryCounter(Native Method)
       at org.lwjgl.opengl.GL33.glQueryCounter(GL33.java:277)
       at com.pavelvoronin.pz3d.ShadowBudget.begin(ShadowBudget.java:30)
       at com.pavelvoronin.pz3d.ShadowMap.render(ShadowMap.java:124)
       ...
       at zombie.core.opengl.RenderThread.renderLoop(Unknown Source)
   ```

   (On NG_GL4ES the mod does not crash but fails differently and exits 3D: our shader converter
   turns an integer varying into `varying int`, `scene.vert: ERROR: 0:38: 'assign' : cannot
   convert from 'float' to 'varying int'`. NG changes are on hold; out of scope here.)

## Diagnosis

- `libzfa.so` (from `libs.tar.xz`, built by `build-mesa.sh` with `patches/mesa/0001.patch`)
  exports 2002 GL symbols and **no GetProcAddress-style function** (`zfa.h` declares only
  `zfaCreateContext/DestroyContext/MakeCurrent/SwapBuffers/FlushFront`). LWJGL therefore resolves
  every GL function by `dlsym` on the library named in `-Dorg.lwjgl.opengl.libname`.
- `llvm-nm -D --defined-only libzfa.so`: `glBeginQuery`, `glGenQueries`, `glGetInteger64v`,
  `glDispatchCompute`, `glBufferStorage`, `glDebugMessageCallback`, `glGetProgramBinary` etc. are
  present; **`glQueryCounter`, `glGetQueryObjecti64v`, `glGetQueryObjectui64v` are absent.**
- Why: the target links `libglapi_static` whole, and Mesa exports a static entry point only for
  names listed in `src/mapi/glapi/gen/static_data.py` → `functions` (checked in `gl_XML.py`:
  `if name in static_data.functions: self.static_entry_points.append(name)`). The three
  GL_ARB_timer_query functions (`gl_API.xml` category 85) have dispatch offsets 731-733 in
  `static_data.offsets` but are not in `static_data.functions`. Upstream Linux reaches them through
  `glXGetProcAddress`; libzfa offers no such path.
- `libOSMesa.so` (ZINK_OSMESA) also lacks the three exports but does export
  `OSMesaGetProcAddress`. If LWJGL's Linux function provider falls back to `OSMesaGetProcAddress`
  (to be confirmed for our LWJGL 3.4.1 build), ZINK_OSMESA may already work with PZ3D.

## Proposed fix

Add the three names to `static_data.functions` in our Mesa patch (new hunk in
`patches/mesa/0001.patch` or a separate `patches/mesa/0004-timer-query-exports.patch`), keeping the
list's alphabetical order:

```
"GetQueryObjecti64v",
"GetQueryObjectui64v",
"QueryCounter",
```

Then rebuild Mesa in CI and replace `libzfa.so` (and `libOSMesa.so`, same generator) inside
`app/src/main/assets/bundles/libs.tar.xz`.

Why this one: it uses the same mechanism that already exports the other ~2000 functions, adds no
new code path, and cannot change behaviour for anything that already resolves.

## Alternatives considered

- **Export a GetProcAddress from libzfa** (`zfaGetProcAddress` →
  `_mesa_glapi_get_proc_address`, plus an alias under a name LWJGL probes). Fixes the whole class of
  "function exists in Mesa but is not a static export" at once, not only these three. LWJGL 3.4.1
  probes `glXGetProcAddress`, `glXGetProcAddressARB`, `eglGetProcAddress`, and
  `OSMesaGetProcAddress`; it does not know `zfaGetProcAddress`. Our GLFW ZFA backend would also
  need to call the resolver instead of only doing direct symbol lookup. This is riskier for the
  immediate fix because it changes how every function is resolved.
- **Tell PZ3D users to pick ZINK_OSMESA.** No rebuild, if the OSMesa path works; but ZINK_ZFA is
  our preferred ZINK and the export gap would stay for the next mod.

## Questions for the reviewer

1. Is `static_data.functions` really the only thing that decides the static exports of a
   `libglapi_static` link in Mesa 25.0.2, or can the generated `glapi_mapi_tmp.h` / stubs need
   more (e.g. a `static_dispatch` attribute, or `mapi_abi.py` changes)?
2. Should we go further and export a GetProcAddress from libzfa now? If yes, which name.
3. Any risk in adding names after offset 407 (the GLX ABI block) to the export list?
4. Are other commonly used core 3.x/4.x functions missing from `static_data.functions` that we
   should add in the same change? A full diff of `gl_API.xml` core functions against the export
   list would answer that.

## How to verify

- After the Mesa rebuild: `llvm-nm -D --defined-only libzfa.so | grep -E ' gl(QueryCounter|GetQueryObjecti64v|GetQueryObjectui64v)$'`
  must list all three.
- On an Adreno device with ZINK_ZFA, Build 42.20, ZombieBuddy enabled and PZ3D active: press
  Insert in game. Expected: 3D view, no `FATAL ERROR in native method`.
- Regression: a normal Build 42 session on ZINK_ZFA without mods (menu, load, walk, drive) and one
  on ZINK_OSMESA.
