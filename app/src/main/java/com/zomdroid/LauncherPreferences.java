package com.zomdroid;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.gson.Gson;

import java.io.File;

public class LauncherPreferences {
    private static LauncherPreferences singleton;
    transient private SharedPreferences sharedPreferences;
    transient private Gson gson;

    // The default every install starts with, and what the Reset button restores. Deliberately
    // free of any absolute number: heap size and GC thread counts are left to JVM ergonomics, so
    // this is correct on a 4 GB phone (~900 MB heap, threads = cores) and on a 12 GB one (~2.7 GB)
    // alike. Only garbage-collector *behaviour* is set, which does not depend on the device —
    // and MinHeapFreeRatio/MaxHeapFreeRatio, which hand memory back to the system, matter most
    // exactly on the weak devices.
    //
    // No explicit -XX:+UseG1GC: G1 has been HotSpot's default collector for years, so the flag
    // was redundant on most devices — and a tester reported the game failing to start with it set
    // explicitly. G1 reserves address space for the whole max heap up front, which can behave
    // differently from the JVM's own ergonomic collector choice under an Android app's stricter
    // memory limits; safer to let the JVM decide.
    // -XX:-OmitStackTraceInFastThrow is a diagnostics flag, not a performance one: by default the
    // JIT strips the stack trace off an exception that keeps being thrown from the same place, so
    // the most frequent (and usually most interesting) crash in a bug report arrives as a bare
    // "java.lang.NullPointerException" with no frames at all. Costs nothing unless something is
    // actually throwing.
    public static final String DEFAULT_JVM_ARGS =
            "-XX:MaxGCPauseMillis=120 -XX:+UseStringDeduplication"
                    + " -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20"
                    + " -XX:-OmitStackTraceInFastThrow";

    // Field-proven set for Build 42, applied by the button in Settings → Advanced. Adds the
    // absolute numbers on top of the defaults above: a capped heap (lower than the ergonomic
    // choice on purpose — it leaves room for box64, the renderer and textures, which is what lets
    // NG_GL4ES start at all) and GC thread counts that keep two cores free for render and audio.
    // Validated on a 12 GB / 8-core device with Build 42.20; do not hand it out for Build 41,
    // where the 2 GB ceiling buys nothing and can push a 4 GB phone into lmkd. No explicit
    // -XX:+UseG1GC — see DEFAULT_JVM_ARGS above.
    // SoftMaxHeapSize=1536M (75% of the 2048M cap): G1 treats it as a target to stay under
    // rather than a hard limit, so the heap can still grow to Xmx under load spikes but idles
    // smaller — the second half of the memory treatment for Build 42, field-tested alongside
    // the Memory saver toggle (LIBGL_TEXBUDGET).
    public static final String BUILD_42_JVM_ARGS =
            "-Xms512M -Xmx2048M -XX:SoftMaxHeapSize=1536M -XX:MaxGCPauseMillis=120"
                    + " -XX:ParallelGCThreads=6 -XX:ConcGCThreads=2 -XX:+UseStringDeduplication"
                    + " -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20"
                    + " -XX:-OmitStackTraceInFastThrow";

    // The Build 42 preset exactly as it shipped in 1.4.7, kept only so a bug report can tell
    // "pressed the button, on the older build" apart from "typed something by hand". The stored
    // JSON value wins over the constants above, so updating the app does NOT update an already
    // applied preset — without this entry every pre-1.4.7v2 player reads as "custom" and the
    // report loses the one fact worth knowing about them.
    private static final String BUILD_42_JVM_ARGS_147 =
            "-Xms512M -Xmx2048M -XX:MaxGCPauseMillis=120 -XX:ParallelGCThreads=6"
                    + " -XX:ConcGCThreads=2 -XX:+UseStringDeduplication"
                    + " -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20";

    /**
     * Short tag for the bug report: which of our presets the stored JVM args correspond to.
     * Whitespace-insensitive, so a stray double space does not read as "custom".
     */
    public static String describeJvmArgsPreset(String args) {
        String a = squashWhitespace(args);
        if (a.isEmpty()) return "none";
        if (a.equals(squashWhitespace(DEFAULT_JVM_ARGS))) return "default";
        if (a.equals(squashWhitespace(BUILD_42_JVM_ARGS))) return "Build 42 preset";
        if (a.equals(squashWhitespace(BUILD_42_JVM_ARGS_147)))
            return "Build 42 preset, 1.4.7 revision - re-apply";
        return "custom";
    }

    public static String squashWhitespace(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private float renderScale = 0.65f;
    private Renderer renderer = Renderer.GL4ES;
    private VulkanDriver vulkanDriver = VulkanDriver.SYSTEM_DEFAULT;
    private boolean isDebug = false;
    // Memory saver (Settings → Advanced): when on, the launcher exports LIBGL_TEXBUDGET=800 for
    // NG_GL4ES — a live-texture budget in MB past which new large textures load at half
    // resolution. Off by default: it visibly degrades tiles and 12 GB devices don't need it.
    private boolean memorySaver = false;
    private AudioAPI audioAPI = AudioAPI.AAUDIO;
    // Not seeded at instance creation any more — this IS the default, so it applies to everyone
    // from the first launch. Users who already have their own value keep it: their stored JSON
    // overrides this initializer.
    private String jvmArgs = DEFAULT_JVM_ARGS;

    // GitHub-release update check: the raw tag_name last seen (e.g. "v1.4.7"), and the day
    // (yyyyMMdd) the daily background check last ran — so a phone with no internet retries at
    // most once a day instead of on every launch.
    private String latestSeenTag = "";
    private String updateCheckDay = "";

    private static final String KEY_TOUCH_CONTROLS = "touch_controls_enabled";

    LauncherPreferences() {}

    private static boolean isKgslSupported() {
        String[] paths = {
                "/dev/kgsl-3d0",
                "/dev/kgsl/kgsl-3d0"
        };

        for (String p : paths) {
            try {
                File f = new File(p);
                if (f.exists()) {
                    return true;
                }
            } catch (SecurityException ignored) {}
        }
        return false;
    }

    public static void init(@NonNull Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        String json = sharedPreferences.getString(C.shprefs.keys.LAUNCHER_PREFS, null);
        LauncherPreferences launcherPreferences;
        Gson gson = new Gson();
        if (json == null) {
            launcherPreferences = new LauncherPreferences();
        } else {
            launcherPreferences = gson.fromJson(json, LauncherPreferences.class);
        }
        launcherPreferences.sharedPreferences = sharedPreferences;
        launcherPreferences.gson = gson;
        // One-time migration only for the old launcher-provided default. Custom user values,
        // including a deliberately empty field, remain untouched.

        // OPENSL is retired — see the AudioAPI enum. Healed here rather than in the getter so the
        // field is correct for every reader, including anything that serializes the object before
        // a getter has run. Users who had picked it keep every other setting untouched and need to
        // do nothing; the corrected value reaches disk on the next saveToPreferences().
        if (launcherPreferences.audioAPI != AudioAPI.AAUDIO) {
            launcherPreferences.audioAPI = AudioAPI.AAUDIO;
        }
        singleton = launcherPreferences;
    }

    @Nullable
    public static LauncherPreferences getSingleton() {
        return singleton;
    }

    @NonNull
    public static LauncherPreferences requireSingleton() {
        if (singleton == null) {
            throw new RuntimeException("LauncherPreferences is not initialized");
        }
        return singleton;
    }

    public SharedPreferences getSharedPrefs() {
        return sharedPreferences;
    }

    public void saveToPreferences() {
        String json = gson.toJson(this);
        this.sharedPreferences
                .edit()
                .putString(C.shprefs.keys.LAUNCHER_PREFS, json)
                .apply();
    }

    public float getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(float renderScale) {
        this.renderScale = Math.clamp(renderScale, 0.25f, 1.f);
        saveToPreferences();
    }

    public Renderer getRenderer() {
        return renderer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
        saveToPreferences();
    }

    public VulkanDriver getVulkanDriver() {
        return vulkanDriver;
    }

    public void setVulkanDriver(VulkanDriver vulkanDriver) {
        this.vulkanDriver = vulkanDriver;
        saveToPreferences();
    }

    public boolean isDebug() {
        return isDebug;
    }

    public boolean isMemorySaver() {
        return memorySaver;
    }

    public void setMemorySaver(boolean enabled) {
        this.memorySaver = enabled;
        saveToPreferences();
    }

    public void setDebug(boolean debug) {
        isDebug = debug;
        saveToPreferences();
    }

    public AudioAPI getAudioAPI() {
        return audioAPI;
    }

    public void setAudioAPI(AudioAPI audioAPI) {
        this.audioAPI = audioAPI;
        saveToPreferences();
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public String getLatestSeenTag() {
        return latestSeenTag;
    }

    public void setLatestSeenTag(String tag) {
        this.latestSeenTag = tag != null ? tag : "";
        saveToPreferences();
    }

    public String getUpdateCheckDay() {
        return updateCheckDay;
    }

    public void setUpdateCheckDay(String day) {
        this.updateCheckDay = day != null ? day : "";
        saveToPreferences();
    }


    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs != null ? jvmArgs : "";
        saveToPreferences();
    }

    private String envVars = "";

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
        saveToPreferences();
    }

    public enum Renderer {
        ZINK_ZFA("libzfa.so"),
        ZINK_OSMESA("libOSMesa.so"),
        NG_GL4ES("libng_gl4es.so"),
        GL4ES("libgl4es.so");

        final String libName;
        Renderer(String libName) {
            this.libName = libName;
        }
    }

    public enum VulkanDriver {
        SYSTEM_DEFAULT(null),
        FREEDRENO("libvulkan_freedreno.so"),
        FREEDRENO_8XX("libvulkan_freedreno_8xx.so"),
        FREEDRENO_840("libvulkan_freedreno_840.so"),
        TURNIP_7XX("libvulkan.ad07XX_regular.so"),
        TURNIP_740("libvulkan.ad07XX.so"),
        TURNIP_710("vulkan.turnip.710.so"),
        Turnip_6XX("vulkan.ad06XX.so"),
        CUSTOM_DRIVER(C.deps.CUSTOM_DRIVER_FILENAME);

        final String libName;
        VulkanDriver(String libName) {
            this.libName = libName;
        }
    }

    // OPENSL is retired: not offered in Settings any more, and coerced back to AAUDIO in init().
    // The constant stays only so Gson can still parse a value stored by an older version.
    //
    // Two crash reports (2026-08-02/03, Redmi Note 10 Pro and POCO X6 5G) died at the same
    // instruction inside FMOD 2.03.09 — libfmod.so+0x1536c8, FMOD::System::recordStart — while the
    // game was starting voice chat. Both ran OpenSL ES; both had a capture device advertising
    // 16000 Hz mono, where every AAudio device in our reports advertises 48000. None of the nine
    // healthy reports used OpenSL. AAudio has existed since API 26 and our minSdk is 30, so the
    // usual reason to keep an OpenSL fallback — old Android — never applied here.
    public enum AudioAPI {
        AAUDIO,
        OPENSL
    }

    public enum ThemeMode {
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "System default"),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO, "Light"),
        DARK(AppCompatDelegate.MODE_NIGHT_YES, "Dark");

        public final int nightMode;
        private final String label;
        ThemeMode(int nightMode, String label) { this.nightMode = nightMode; this.label = label; }

        @Override public String toString() { return label; }
    }

    private ThemeMode themeMode = ThemeMode.SYSTEM;

    public ThemeMode getThemeMode() { return themeMode != null ? themeMode : ThemeMode.SYSTEM; }
    public void setThemeMode(ThemeMode mode) { themeMode = mode; saveToPreferences(); }

    private boolean touchControlsEnabled = false;

    public boolean isTouchControlsEnabled() {
        return touchControlsEnabled;
    }

    public void setTouchControlsEnabled(boolean enabled) {
        touchControlsEnabled = enabled;
    }

    private boolean vibrateOnTouch = false;

    public boolean isVibrateOnTouch() {
        return vibrateOnTouch;
    }

    public void setVibrateOnTouch(boolean enabled) {
        vibrateOnTouch = enabled;
        saveToPreferences();
    }
}
