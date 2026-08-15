package com.zomdroid.game;

import android.app.ActivityManager;
import android.content.Context;

import com.zomdroid.GpuInfo;
import com.zomdroid.LauncherPreferences;
import com.zomdroid.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings combinations we hand out, in one place.
 *
 * <p>Renderer, texture shrinking, Java arguments, resolution scale and the memory saver are not
 * independent choices - they only work as sets, and the sets differ per game build. Spread across
 * five screens they drift: for two weeks after 1.4.7 our own dialogs still called NG_GL4ES
 * "experimental" and announced ZINK as the renderer that had been selected, while the combination
 * that actually worked for everyone was NG_GL4ES with shrinking. People read the old wording,
 * stayed on ZINK and arrived with "Build 42 lags and crashes after a few minutes".
 *
 * <p>So every place that sets these values goes through here: the dialog after an instance is
 * installed, and the buttons in Settings. One definition, no second version to forget.
 *
 * <h3>Why the default is not the best renderer</h3>
 *
 * <p>ZINK draws a cleaner picture and is genuinely lighter on the GPU. It also only works on
 * Adreno, wants a driver picked by hand, and needs ANGLE on Mali. NG_GL4ES is heavier, but has no
 * preconditions at all and its texture shrinking buys the performance back - at the cost of a
 * faint grid of seams on the ground. Chosen by "how likely is someone to reach a playable state
 * without help", the heavier renderer wins everywhere except Adreno, where ZINK needs nothing
 * special.
 */
public enum SuggestedPreset {

    /** Build 42 on Adreno, where ZINK works with no setup beyond a driver. */
    BUILD_42_QUALITY(R.string.preset_name_b42, LauncherPreferences.Renderer.ZINK_ZFA,
            null, LauncherPreferences.BUILD_42_JVM_ARGS, Boolean.FALSE),

    /** Build 42 everywhere else, and the fallback offered on Adreno when ZINK misbehaves. */
    BUILD_42_COMPATIBILITY(R.string.preset_name_b42_compat, LauncherPreferences.Renderer.NG_GL4ES,
            SuggestedPreset.SHRINK_BALANCED, LauncherPreferences.BUILD_42_JVM_ARGS, null),

    /**
     * Build 41. NG_GL4ES does not run on it at all, and ZINK would need an Adreno GPU and a driver,
     * so GL4ES - which needs nothing - is the one that works for everyone. The Build 42 Java
     * arguments are deliberately not reused: their 2 GB heap cap buys nothing here and can push a
     * 4 GB phone into the low-memory killer. Shrinking is cleared rather than left alone, because
     * GL4ES honours LIBGL_SHRINK too and Build 41 is light enough not to need the picture damage.
     */
    BUILD_41(R.string.preset_name_b41, LauncherPreferences.Renderer.GL4ES,
            null, LauncherPreferences.DEFAULT_JVM_ARGS, Boolean.FALSE);

    /**
     * Shrinks only textures above 512 and skips empty ones. Mode 1 shrinks everything including
     * empty textures, which is where the crash reports come from - it stays a manual choice and
     * never goes into a one-tap preset. See the note in SettingsFragment: the modes are strategies,
     * not a scale.
     */
    public static final String SHRINK_BALANCED = "7";
    public static final String SHRINK_KEY = "LIBGL_SHRINK";

    /** Field-proven on every device we have data from; also what the reports run at. */
    private static final float RENDER_SCALE = 0.60f;

    /** The memory saver helps at or below this much RAM and is pointless above it. */
    private static final long MEMORY_SAVER_MAX_GB = 8;

    private final int labelRes;
    private final LauncherPreferences.Renderer renderer;
    private final String shrink;      // null clears LIBGL_SHRINK
    private final String jvmArgs;
    private final Boolean memorySaver; // null decides by installed RAM

    SuggestedPreset(int labelRes, LauncherPreferences.Renderer renderer, String shrink,
                    String jvmArgs, Boolean memorySaver) {
        this.labelRes = labelRes;
        this.renderer = renderer;
        this.shrink = shrink;
        this.jvmArgs = jvmArgs;
        this.memorySaver = memorySaver;
    }

    public int getLabelRes() {
        return labelRes;
    }

    /**
     * Which preset a freshly installed instance should get. The build decides everything except
     * the Build 42 renderer, which depends on whether the GPU can run ZINK unaided.
     *
     * @param presetName the installation preset name, e.g. "Build 42.12+"
     * @param gpuVendor  as reported by InstallerService, e.g. "QUALCOMM"; may be null
     */
    public static SuggestedPreset forInstall(String presetName, String gpuVendor) {
        if (presetName == null || !presetName.startsWith("Build 42")) return BUILD_41;
        return forBuild42(gpuVendor);
    }

    public static SuggestedPreset forBuild42(String gpuVendor) {
        return QUALCOMM.equals(gpuVendor) ? BUILD_42_QUALITY : BUILD_42_COMPATIBILITY;
    }

    public static final String QUALCOMM = "QUALCOMM";
    public static final String MEDIATEK = "MEDIATEK";

    /**
     * Which SoC family this is, or null when it is neither. Only the two we have renderer evidence
     * for are named; everything else is deliberately "unknown" rather than guessed, because the
     * answer only decides between ZINK and NG_GL4ES and NG_GL4ES is the safe side of that.
     *
     * <p>Lives here rather than in the install screen because Settings needs the same answer, and
     * two copies of a detector like this drift.
     */
    public static String detectGpuVendor() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.contains("qualcomm") || lower.contains("snapdragon")) return QUALCOMM;
                if (lower.contains("mediatek") || lower.contains("dimensity")
                        || lower.contains("helio")) return MEDIATEK;
            }
        } catch (Exception ignored) {}

        String[] buildFields = {
                android.os.Build.HARDWARE,
                android.os.Build.BOARD,
                android.os.Build.SOC_MODEL,      // API 31+
                android.os.Build.SOC_MANUFACTURER // API 31+
        };
        for (String field : buildFields) {
            if (field == null) continue;
            String lower = field.toLowerCase(Locale.US);
            if (lower.contains("qcom") || lower.contains("qualcomm")
                    || lower.contains("snapdragon")) return QUALCOMM;
            if (lower.contains("mt") || lower.contains("mediatek")
                    || lower.contains("dimensity") || lower.contains("helio")) return MEDIATEK;
        }
        return null;
    }

    /** True where ZINK is the Build 42 default, i.e. where the compatibility set is worth offering separately. */
    public static boolean hasCompatibilityAlternative(String gpuVendor) {
        return forBuild42(gpuVendor) == BUILD_42_QUALITY;
    }

    /** Write this combination into the launcher preferences. */
    public void apply(Context context) {
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();
        prefs.setRenderer(renderer);
        prefs.setEnvVars(withShrink(prefs.getEnvVars(), shrink));
        prefs.setJvmArgs(jvmArgs);
        prefs.setRenderScale(RENDER_SCALE);
        prefs.setMemorySaver(resolveMemorySaver(context));
        LauncherPreferences.VulkanDriver driver = resolveDriver();
        if (driver != null) prefs.setVulkanDriver(driver);
        prefs.saveToPreferences();
    }

    /**
     * The Vulkan driver this preset wants, or null to leave the current choice alone.
     *
     * <p>Only ZINK reads it, and only Adreno has a Turnip build to read. Leaving it at System was
     * the missing half of "Snapdragon detected, ZINK has been selected": ZINK on the stock Adreno
     * driver black-screens on plenty of phones, and we papered over that by asking people to go and
     * pick Freedreno by hand. The GPU says which one - see {@link GpuInfo}.
     */
    private LauncherPreferences.VulkanDriver resolveDriver() {
        if (renderer != LauncherPreferences.Renderer.ZINK_ZFA
                && renderer != LauncherPreferences.Renderer.ZINK_OSMESA) return null;
        // Never overwrite a driver someone imported themselves - that is a deliberate act, usually
        // after a bad experience with everything we ship.
        if (LauncherPreferences.requireSingleton().getVulkanDriver()
                == LauncherPreferences.VulkanDriver.CUSTOM_DRIVER) return null;
        return GpuInfo.query().recommendedDriver();
    }

    /**
     * What applying this would change, one line per setting, already worded for a person. Empty
     * when the current settings already match - the caller can then say so instead of showing an
     * empty confirmation.
     */
    public List<String> describeChanges(Context context) {
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();
        List<String> changes = new ArrayList<>();

        if (prefs.getRenderer() != renderer)
            changes.add(context.getString(R.string.preset_change_renderer,
                    prefs.getRenderer().name(), renderer.name()));

        String currentShrink = readShrink(prefs.getEnvVars());
        if (!equal(currentShrink, shrink))
            changes.add(context.getString(R.string.preset_change_shrink,
                    shrinkLabel(context, currentShrink), shrinkLabel(context, shrink)));

        if (!jvmArgs.equals(prefs.getJvmArgs()))
            changes.add(context.getString(R.string.preset_change_jvm_args));

        if (Math.abs(prefs.getRenderScale() - RENDER_SCALE) > 0.001f)
            changes.add(context.getString(R.string.preset_change_render_scale,
                    percent(prefs.getRenderScale()), percent(RENDER_SCALE)));

        LauncherPreferences.VulkanDriver driver = resolveDriver();
        if (driver != null && prefs.getVulkanDriver() != driver)
            changes.add(context.getString(R.string.preset_change_vulkan_driver,
                    prefs.getVulkanDriver().name(), driver.name()));

        boolean saver = resolveMemorySaver(context);
        if (prefs.isMemorySaver() != saver)
            changes.add(context.getString(saver
                    ? R.string.preset_change_memory_saver_on
                    : R.string.preset_change_memory_saver_off));

        return changes;
    }

    private boolean resolveMemorySaver(Context context) {
        if (memorySaver != null) return memorySaver;
        return totalRamGb(context) <= MEMORY_SAVER_MAX_GB;
    }

    /**
     * Installed RAM, rounded up. MemTotal is what the kernel manages, so an 8 GB device reports
     * about 7.4 - rounding up is what makes the comparison mean what the marketing number says.
     * Returns a large value when unavailable, so an unknown device does not get the saver forced on.
     */
    private static long totalRamGb(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return Long.MAX_VALUE;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        if (info.totalMem <= 0) return Long.MAX_VALUE;
        return Math.round(Math.ceil(info.totalMem / (1024.0 * 1024.0 * 1024.0)));
    }

    // -------------------- LIBGL_SHRINK inside the free-text env vars --------------------

    /** The LIBGL_SHRINK value currently set, or null. */
    public static String readShrink(String envVars) {
        if (envVars == null) return null;
        for (String token : envVars.trim().split("\\s+")) {
            if (token.startsWith(SHRINK_KEY + "=")) return token.substring(SHRINK_KEY.length() + 1);
        }
        return null;
    }

    /** Replace or drop LIBGL_SHRINK, leaving every other variable exactly where it was. */
    public static String withShrink(String envVars, String value) {
        StringBuilder out = new StringBuilder();
        if (envVars != null) {
            for (String token : envVars.trim().split("\\s+")) {
                if (token.isEmpty() || token.startsWith(SHRINK_KEY + "=")) continue;
                if (out.length() > 0) out.append(' ');
                out.append(token);
            }
        }
        if (value != null) {
            if (out.length() > 0) out.append(' ');
            out.append(SHRINK_KEY).append('=').append(value);
        }
        return out.toString();
    }

    private static String shrinkLabel(Context context, String value) {
        if (value == null) return context.getString(R.string.settings_texture_shrink_none);
        if (SHRINK_BALANCED.equals(value)) return context.getString(R.string.settings_texture_shrink_balanced);
        return SHRINK_KEY + "=" + value; // a mode typed by hand - show it verbatim
    }

    private static String percent(float scale) {
        return String.format(Locale.US, "%d%%", Math.round(scale * 100));
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
