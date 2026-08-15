package com.zomdroid;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What GPU this phone has, and which of our bundled Vulkan drivers suits it.
 *
 * <p>Ported from RimDroid, where it has been in the field long enough to have earned its odd cases.
 * A vendor check through /proc/cpuinfo only says "Qualcomm"; this spins up a throwaway 1x1 offscreen
 * EGL context and reads {@code GL_RENDERER} from the phone's own driver - "Adreno (TM) 830",
 * "Mali-G720" - which carries the exact model number. Turnip builds are per Adreno series, so the
 * series is the whole question.
 *
 * <p>This matters because ZINK on the system Vulkan driver black-screens a lot of Adreno phones,
 * and until now a new instance was handed ZINK with the driver left at System - the combination we
 * then explained away in a hint asking people to go and pick Freedreno by hand.
 */
public final class GpuInfo {

    private static final Pattern ADRENO_MODEL_PATTERN = Pattern.compile(
            "(?i)\\badreno(?:\\s*\\(tm\\))?[^0-9]{0,24}(\\d{3,4})\\b");
    private static volatile GpuInfo cached;

    /** Raw GL_RENDERER, e.g. "Adreno (TM) 830"; null when the probe failed. */
    @Nullable public final String renderer;
    /** Raw GL_VENDOR, e.g. "Qualcomm"; null when the probe failed. */
    @Nullable public final String vendor;
    /** Adreno series (6, 7, 8...) or 0 when this is not an Adreno. */
    public final int adrenoSeries;
    /** Full Adreno model, e.g. 644, 735, 830, or 0 when this is not an Adreno. */
    public final int adrenoModel;

    private GpuInfo(@Nullable String renderer, @Nullable String vendor, int adrenoSeries, int adrenoModel) {
        this.renderer = renderer;
        this.vendor = vendor;
        this.adrenoSeries = adrenoSeries;
        this.adrenoModel = adrenoModel;
    }

    /** Cheap - a 1x1 pbuffer context - and cached. Not for the very first frame. */
    @NonNull
    public static GpuInfo query() {
        GpuInfo result = cached;
        if (result != null) return result;
        synchronized (GpuInfo.class) {
            result = cached;
            if (result != null) return result;
            result = queryUncached();
            // A failed probe can be transient, so only a real answer is cached: a later launch or a
            // trip through Settings still gets a chance to identify the GPU.
            if (result.renderer != null && !result.renderer.trim().isEmpty()) cached = result;
            return result;
        }
    }

    @NonNull
    private static GpuInfo queryUncached() {
        String r = null, v = null;
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
                int[] cfgAttr = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_NONE};
                EGLConfig[] cfgs = new EGLConfig[1];
                int[] num = new int[1];
                if (EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0) && num[0] > 0) {
                    int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
                    ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                    int[] surfAttr = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
                    surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], surfAttr, 0);
                    if (ctx != EGL14.EGL_NO_CONTEXT && surf != EGL14.EGL_NO_SURFACE
                            && EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                        r = GLES20.glGetString(GLES20.GL_RENDERER);
                        v = GLES20.glGetString(GLES20.GL_VENDOR);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Any EGL failure leaves the renderer null, which recommends the system driver.
        } finally {
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignored) {}
        }
        int model = parseAdrenoModel(r);
        int series = model > 0 ? (model / (model >= 1000 ? 1000 : 100)) : 0; // 644→6, 735→7, 830→8
        return new GpuInfo(r, v, series, model);
    }

    /** "Adreno (TM) 830" → 830. */
    static int parseAdrenoModel(@Nullable String renderer) {
        if (renderer == null) return 0;
        Matcher m = ADRENO_MODEL_PATTERN.matcher(renderer);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    public boolean isAdreno() {
        return adrenoModel > 0;
    }

    /**
     * The driver to start on. Non-Adreno keeps the system driver - Turnip is an Adreno driver and
     * there is nothing to offer a Mali or an Xclipse.
     *
     * <p>The shape of this table is RimDroid's, but the entries are ours: the two projects ship
     * different Turnip builds under similar names, so their exact recommendations could not be
     * copied across. Where we have no build matching what their field data calls for, the choice
     * falls back to the closest one we do ship rather than to something untested.
     */
    @NonNull
    public LauncherPreferences.VulkanDriver recommendedDriver() {
        switch (adrenoSeries) {
            case 8:
                // 840 has its own build; the rest of 8xx share one.
                return adrenoModel == 840
                        ? LauncherPreferences.VulkanDriver.FREEDRENO_840
                        : LauncherPreferences.VulkanDriver.FREEDRENO_8XX;
            case 7:
                if (adrenoModel == 740) return LauncherPreferences.VulkanDriver.TURNIP_740;
                if (adrenoModel == 710) return LauncherPreferences.VulkanDriver.TURNIP_710;
                return LauncherPreferences.VulkanDriver.TURNIP_7XX;
            case 6:
                // a6xx is split, and the legacy ad06XX build is the wrong answer on both halves of
                // it. RimDroid's field notes: on newer high a6xx (>=630) it hangs Zink at context
                // creation (Adreno 644 / Snapdragon 7 Gen 1), and on the old budget parts (a610,
                // a619) it present-blacks - the game loads, swaps exactly one buffer and freezes.
                // The 7xx build ran on both, with artifacts on the old ones. So that is the pick,
                // and ad06XX stays available as a manual choice for anyone it does suit.
                return LauncherPreferences.VulkanDriver.TURNIP_7XX;
            default:
                return LauncherPreferences.VulkanDriver.SYSTEM_DEFAULT;
        }
    }

    /** For dialogs and log headers, e.g. "Adreno (TM) 830". */
    @NonNull
    public String displayName() {
        return renderer != null ? renderer : "unknown GPU";
    }
}
