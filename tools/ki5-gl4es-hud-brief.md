# Brief for Codex: B41 + GL4ES, the HUD turns green or vanishes near KI5 / Autotsar vehicles

**Status (2026-09-14):** reproduced on our own Samsung. Root cause **not** found yet. Your gl4es fix
`d929043` (`zomdroid-dependencies/patches/gl4es/0004-pz-skin-attrib.patch`, app `136fe60`) is active
on the KI5 programs and does not prevent it.

## Symptom

- Build 41 + GL4ES only.
- **Mali** (reporter: Samsung A34, SM-A346M, Dimensity): the HUD "disappears or becomes corrupted"
  while a KI5 vehicle or an animated Autotsar vehicle (fuel tank, towable house) is on screen. The
  world, the character and the vehicle keep rendering, and the vehicle stays usable. Moving away
  restores the HUD.
- **Adreno** (our S25 Ultra, SM-S938B): the map and the HUD buttons turn **green**; the character
  looks normal. It appears as soon as the vehicle is in view (right after spawning it). Getting in
  and out changes nothing.
- **ZINK_ZFA / ZINK_OSMESA:** no UI problem (Redmi Note 9S, Adreno 618,
  `zomdroid_report_22082026_1928`). The Mali reporter cannot use ZINK: on Mali, ZINK needs ANGLE.
- The reporter confirms that on 1.4.9 the old full-screen freeze (image frozen, audio alive) is
  gone. Only the HUD problem is left. Plain Autotsar content is fine; only its animated vehicles
  trigger it.

Reports: `C:\Users\user\Desktop\PZ\zomdroid_report_09092026_1331.zip` (1.4.9, after your fix) and
`zomdroid_report_06092026_0035.zip` (before it).

## Reproduction on our device (ready to use)

- **Device and build:** Samsung S25 Ultra `RFGYB08X13B` with the debug APK. The debug build already
  runs the game with `-debug` (`GameLauncher`, `BuildConfig.DEBUG`).
- **Instance:** **`Project Zomboid 41 `** (note the trailing space), renderer GL4ES, no env vars.
- **Mods installed there** (B41 parts only: `mod.info`, `preview.png`, `media`): `damnlib`
  (workshop 3171167894) and `92amgeneralM998` (2642541073). Both are enabled in
  `Zomboid/mods/default.txt`; the original is kept as `default.txt.bak-ki5`. Sources are in
  `zomdroid_v1.2.3/build/ki5_sources/`.
- **Steps:**
  1. Start a new game.
  2. Long-press the ground and pick debug "Spawn Vehicle".
  3. Choose the first entry, `Base.92amgeneralM998`. The HUD and the map turn green at once.
- **Logs from that session:**
  - In logcat the `damn_*` shaders are built at 17:15:15, and in the same second you get
    `LIBGL: PZ skin attributes program=154/160/168/174 indices=6 weights=7`.
  - The game log is `files/instances/Project Zomboid 41 /Zomboid/console.txt`.
  - gl4es stdout also goes to `files/instances/Project Zomboid 41 /game/native.log`.

## Established (no need to re-check)

1. **Not the agent's GLSL converter.** The `texture2D` and UTF-8 BOM parse errors are noise.
   `ShaderUnit.preprocessForGLSLES` rebuilds its output from the full token list and copies the text
   between tokens verbatim, so error recovery drops nothing. Stock vehicle shaders get the same
   errors and render fine.
2. **Not Lua.** The four `attempted index ... of non-table` errors are thrown at mod load
   (`LuaManager.LoadDirBase`), and nothing is logged at the moment the UI breaks. The
   `no such mesh ...WI` warnings are about world items and are unrelated.
3. **gl4es slot layout.** With maxvattrib > 8, `src/gl/buffers.h` uses the ARB map:
   - VERTEX 0, NORMAL 2, **COLOR 3**, SECONDARY 4, FOGCOORD 5;
   - slots 6 and 7 are free;
   - MULTITEXCOORD0..15 are 8..23.

   A generic attribute with index N writes the same `glstate->vao->vertexattrib[N]` as the
   conventional array with that number. In `vertexattrib.c`, pointer, enable and disable all touch
   `vertexattrib[index]` directly.
4. **Your patch applies to KI5.** `damnlib/media/shaders/damn_vehicle*.vert` contain `gl_Vertex`,
   `gl_Normal`, `MatrixPalette`, `boneIndices` and `boneWeights`. So do all stock skinned B41 shaders
   (`aim_outline_solid`, `basicEffect`, `clothingEnvMap`, `vehicle*`), except the debug-only
   `vehicle_wireframe.vert`.
5. **The green is PZ's own colour.** `ModelManager.RenderSkyBox` does this:
   - wraps the pass in `glPushClientAttrib(-1)` and `glPushAttrib(0xFFFFF)`;
   - draws the sky quad in immediate mode (`glBegin(GL_QUADS)`) with
     `glColor4f(0.13f, 0.96f, 0.13f, 1f)`;
   - finishes with `glPopAttrib` and then `glPopClientAttrib`, in the correct order.

   It is issued from `SkyBox.render()` through `SpriteRenderer.drawSkyBox`, so it belongs to normal
   sky rendering and is not specific to vehicles. The HUD is therefore being drawn with the sky's
   colour instead of its own per-vertex colours.
6. **gl4es attribute stack** (`src/gl/stack.c`):
   - `glPushAttrib(GL_CURRENT_BIT)` saves the current colour with `glGetFloatv(GL_CURRENT_COLOR)`,
     and `glPopAttrib` restores it with `glColor4f`.
   - `glPopClientAttrib` restores the whole `vertexattrib[]` with one raw `memcpy` (pointers,
     buffers, enable flags, current values), plus the client-active texture unit. It does not
     touch any derived or cached array state.
7. **PZ B41 UI path** (`SpriteRenderer$RingBuffer`):
   - It enables `GL_VERTEX_ARRAY`, `GL_COLOR_ARRAY` and `GL_TEXTURE_COORD_ARRAY` **once**.
   - After that, `StateRun` only re-points the arrays for each batch:
     `glColorPointer(4, GL_UNSIGNED_BYTE, 32, 28)`, texcoords on units 0 and 1, and the generic
     attribute `a_wallShadeColor` for `IsoGridSquare$CircleStencilShader` and
     `NoCircleStencilShader` (enabled and disabled per batch).
   - It never re-enables the colour array. So anything that turns slot 3 off mid-frame makes the
     rest of the HUD use the current colour.
8. **PZ B41 model path** (`VertexBufferObject.Draw(Vbo, VertexFormat, Shader, int)`):
   - It sets up vertex, normal, colour (only if the vertex format has one) and texcoords. For each
     UV set it switches `glActiveTexture` and `glClientActiveTexture` to the next unit.
   - It sets the bone weights and indices with `glVertexAttribPointer(shader.BoneWeightsAttrib /
     BoneIndicesAttrib)`, then calls `glDrawElements`.
   - Afterwards it disables **only** `GL_NORMAL_ARRAY` and the two bone attributes.
   - Related paths: `SoftwareSkinnedModelAnim` does the same but never disables the bone
     attributes, and `Particles` uses a hard-coded generic index 0 (= VERTEX).
9. **How KI5 differs from stock skinned models.**
   - `damn_vehicle_shader.vert` reads `gl_MultiTexCoord1` (`texCoords1`) and writes reflection
     coordinates to `gl_TexCoord[0]`.
   - The skinned vertex format (`VertexPositionNormalTangentTextureSkin`) has only one UV set, so
     slot 9 is fed from whatever array state was left behind.
   - Characters (`basicEffect`) read only `gl_MultiTexCoord0`.
   - Vehicle reflections sample the sky textures (`Model`: `TextureReflectionA/B` from `SkyBox`,
     gated by `Core.getPerfReflectionsOnLoad()`). In the logs, stock B41 vehicles are drawn with the
     `*_noreflect` shaders.

## Working hypothesis

Something reached only through the KI5/Autotsar vehicle path leaves gl4es either with the colour
slot (3) disabled or pointing at the wrong data, or with a stale current colour. On real GL (ZINK)
the same calls are harmless. Two concrete suspects:

- **A generic-attribute write or disable** landing on a conventional slot (0, 2, 3, 8 or 9). The
  reflective damn shader reading `gl_MultiTexCoord1` is the one input that stock skinned models do
  not have.
- **Stale derived array state in gl4es after `glPopClientAttrib`'s raw `memcpy`.** This covers the
  `vao->vert/color/normal/tex` copies used by `drawing.c` and the buffer bindings. The next UI draw
  could then reuse the sky quad's immediate-mode colour data, which is exactly that green.

## Suggested next steps

1. **Cheap A/B checks on the Samsung, no build needed:**
   - Turn off the game's reflections option (`Core.getPerfReflectionsOnLoad`) and spawn the M998
     again. If the HUD stays normal, the reflective path is involved.
   - Set `LIBGL_PZ_SKINATTRIB=0` in the instance env vars and see whether the symptom changes
     (green vs vanishing). The env field is in the instance settings, pref key
     `inst:Project Zomboid 41 :env_vars`. Do not ask Inna to type env vars: set the pref with the
     app stopped.
2. **Diagnostic gl4es build:** a744af14 + `patches/gl4es/*.patch` + a small logging patch,
   throttled to the first N events per (program, slot, operation). It should log:
   - every change of `vertexattrib[ATT_COLOR]` `.enabled`, `.pointer` and `.buffer`, with the
     caller (client-state call, generic call, pop, immediate mode) and the current program;
   - every generic write (pointer, enable, disable, `glVertexAttrib4f`) to slots 0, 2, 3, 8 and 9;
   - the colour slot and the current colour right before and after `glPopClientAttrib` and
     `glPopAttrib`;
   - at the first FPE draws after a skinned draw: whether the colour array is enabled, its
     pointer and buffer, and the current colour.
3. **Deploy only the `.so` to the Samsung, no APK.**
   - Target: `files/dependencies/libs/android-arm64-v8a/libgl4es.so` (app-private, reached with
     `run-as com.zomdroid`).
   - Back up the original first. The launcher does not re-verify this file.
   - Transfer rules on this Windows machine:
     - never pipe a binary through `adb shell ... < file`, because it gets truncated;
     - `run-as` cannot read `/data/local/tmp` (SELinux);
     - what works is base64 text over stdin:
       `adb shell "run-as com.zomdroid sh -c 'base64 -d > target.new'" < lib.b64`. Then check the
       size and md5, `mv` the file into place and `chmod 600` it.
4. **Build environment.**
   - CI: NDK r27c, API 30, arm64-v8a, Release, `zomdroid-dependencies/build-gl4es.sh`. It builds
     upstream `ptitSeb/gl4es` `a744af14d4afbda77bf472bc53f43b9ceba39cc0` with
     `git apply patches/gl4es/*.patch`.
   - Local: NDK 27.x and cmake 3.22.1 under `%LOCALAPPDATA%\Android\Sdk`.
   - Upstream clones that contain a744af14 are in `zomdroid_v1.2.3/build/gl4es_diag_src` and
     `build/powervr_gl4es_diag`. Their HEAD is a later upstream commit, so use a worktree at a744af14.
5. **Fix direction once the culprit is known.** Either keep the FPE/UI conventional state safe
   from user-program generic attributes, or invalidate gl4es's derived array state after
   `glPopClientAttrib`. Two ways to do the first:
   - bind user generic attributes away from slots 0-5 and 8+ in every program, not just PZ skinned
     ones;
   - save and restore the conventional slots around user-program draws.

   The fix must not regress stock B41 characters or vehicles. The bug has only been reported on B41.

## Constraints

- Inna approves each step separately. Nothing below happens without her explicit OK: no commit, no
  push, no APK, no `.so` build and no library swap on the device.
- Don't suggest ZINK to the Mali reporter, and don't send reporters A/B test protocols. All
  experiments run on our Samsung.
