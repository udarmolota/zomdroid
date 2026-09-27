# PZ3D on NG_GL4ES: what the mod needs that NG does not give yet

For the NG_GL4ES side, 2026-09-27. Question: can NG provide this? Nothing here is a request to
change NG right away; it is the list of gaps, with evidence, so the cost can be judged.

## Context

PZ3D (Steam Workshop 3807334881, version 0.2.2 tested) is a first/third-person camera mod for
Project Zomboid Build 42. It runs through ZombieBuddy (a Java agent) and draws its own 3D scene with
LWJGL on top of the game's context. Device used: Galaxy S25 Ultra, Adreno 830, NG_GL4ES
`RC54-CONVMEMO-BUDGET`, ES 3.2 context, NG reports `OpenGL version: 3.1 Krypton Wrapper Release
0.4.1`. On ZINK (Mesa, OpenGL 4.6) the mod enters 3D and renders, so the mod itself works on
Android; on NG it fails as below.

## 1. Shader conversion (the failure seen today)

Every PZ3D shader is `#version 330 core` (38 files, all under `shaders/` in the mod jar). On NG
the first program fails to compile and PZ3D leaves 3D mode:

```
[PZ3D] Failure in OpenGL
java.lang.IllegalStateException: scene.vert: ERROR: 0:38: 'assign' :  cannot convert from 'float' to 'varying int'
ERROR: 1 compilation errors.  No code generated.
```

Line 38 is in the converted source, not the original. The original `scene.vert`:

```glsl
#version 330 core
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec3 color;
uniform mat4 viewProjection;
uniform vec3 meshOrigin;
uniform vec2 viewportSize;
out vec3 worldPosition;
out vec3 worldNormal;
out vec3 material;
noperspective out float cableCoverage;
flat out int isCable;
out float wireProgress;
flat out float wireSupports;
void main(){
    worldPosition=position-meshOrigin;worldNormal=normal;material=color;
    cableCoverage=1.0;isCable=color.b<0.0?1:0;wireProgress=color.r;wireSupports=color.g;
    ...
}
```

What the converter appears to do: turn `flat out int isCable` into a legacy `varying int` (not
valid in any GLSL ES; integer varyings exist only in ES 3.00+ and must be `flat`), and the int
assignment then fails. Features the mod's shaders rely on, all expressible in GLSL ES 3.00/3.20:

- `flat` qualifier on **integer and float** varyings (`flat out int`, `flat out float`,
  `flat in ...`) — must be kept as `flat out/in`, not lowered to `varying`.
- `noperspective` on a varying — no core ES equivalent; Adreno exposes
  `GL_NV_shader_noperspective_interpolation` (seen in the device's GLES extension list), else it
  can fall back to smooth interpolation.
- `layout(location=N) in` vertex attributes — valid in ES 3.00.
- Plain `in`/`out` everywhere (330 core style), fragment outputs presumably via `out vec4`.

Files using `flat`: ground, scene, shadow_ground, stars, tree (vert/frag pairs), tree_shadow.frag.
The shaders can be read straight from the mod jar (`PZ3D-0.2.2.jar`, folder `shaders/`); a copy is
at `scratchpad/pz3djar/x/shaders` on the dev PC.

## 2. GL entry points beyond 3.1

Every OpenGL call PZ3D makes through LWJGL, grouped by the version LWJGL files it under
(114 distinct calls; below only 3.0 and up):

- **GL30**: BindFramebuffer, BindRenderbuffer, BindVertexArray, BlitFramebuffer,
  CheckFramebufferStatus, ClearBufferfv, DeleteFramebuffers, DeleteRenderbuffers,
  DeleteVertexArrays, FramebufferRenderbuffer, FramebufferTexture2D, FramebufferTextureLayer,
  GenFramebuffers, GenRenderbuffers, GenVertexArrays, GenerateMipmap, MapBufferRange,
  RenderbufferStorage
- **GL31**: CopyBufferSubData, DrawArraysInstanced, DrawElementsInstanced, TexBuffer
- **GL32**: ClientWaitSync, DeleteSync, FenceSync, FramebufferTexture, MultiDrawElementsBaseVertex
- **GL33**: QueryCounter, GetQueryObjectui64, VertexAttribDivisor
- **GL41**: GetProgramBinary, ProgramBinary, ProgramParameteri (likely optional, it also calls
  `GL.getCapabilities()`)
- **GL43**: CopyImageSubData (likely optional)
- **ARB_buffer_storage**: BufferStorage (likely optional)

Important LWJGL detail: LWJGL fills a function's address only when the context reports the
matching core version **or** advertises the matching ARB extension. With NG reporting 3.1, the
GL32/GL33 functions stay NULL even if NG could serve them, and the first call aborts the JVM with
`No context is current or a function that is not available in the current context was called`
(exactly what happened on our ZINK_ZFA build for `glQueryCounter`, for a different reason). So NG
would need either to report 3.3, or to advertise the ARB extensions that carry these functions:

- `GL_ARB_sync` (FenceSync, ClientWaitSync, DeleteSync) — ES 3.0 core.
- `GL_ARB_draw_elements_base_vertex` (MultiDrawElementsBaseVertex) — ES 3.2 core /
  `GL_EXT_draw_elements_base_vertex`, which NG already detects.
- `GL_ARB_instanced_arrays` (VertexAttribDivisor) — ES 3.0 core.
- `GL_ARB_geometry_shader4` or 3.2 for FramebufferTexture — ES 3.2 core.
- `GL_ARB_timer_query` (QueryCounter, GetQueryObjecti64v, GetQueryObjectui64v) — no ES core
  equivalent, but NG already detects `GL_EXT_disjoint_timer_query`, whose `glQueryCounterEXT`,
  `glGetQueryObjecti64vEXT` and `glGetQueryObjectui64vEXT` take the same enums
  (`GL_TIMESTAMP` 0x8E28, `GL_TIME_ELAPSED` 0x88BF). Mapping the three onto the EXT functions
  would cover it. PZ3D uses them for a shadow-rendering time budget (`ShadowBudget.begin`).

## Also worth knowing

- The mod's author asks for at least 4 GB of Java heap. That is a hard limit on most phones
  regardless of the renderer.
- Our side is done: the Byte Buddy collision that made every PZ3D patch fail is fixed in
  zomdroid-dependencies (c143ecb), and the ZINK_ZFA export gap is being fixed in Mesa (a4180ed).
