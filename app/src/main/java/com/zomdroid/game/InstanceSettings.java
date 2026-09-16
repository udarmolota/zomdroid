package com.zomdroid.game;

import android.content.SharedPreferences;

import com.zomdroid.LauncherPreferences;
import com.zomdroid.LauncherPreferences.Renderer;
import com.zomdroid.LauncherPreferences.VulkanDriver;

/**
 * Launch settings owned by one game instance: renderer, Vulkan driver, JVM arguments, environment
 * variables, render scale, debug, memory saver, quick-save backup and the on-screen controls
 * toggles. Each instance keeps its own, so a Build 41 instance can sit on GL4ES with modest JVM
 * arguments while a Build 42 one runs ZINK with a 2 GB heap.
 *
 * <p>Stored as individual keys in the same {@link SharedPreferences} file the launcher already
 * uses, under an {@code inst:<name>:} prefix. {@link LauncherPreferences} itself is one Gson blob
 * under a single key, so the two never collide.
 *
 * <p><b>Every getter falls back to the corresponding global value</b>, which is what makes this
 * change need no migration: an instance that has never been configured reads exactly what the user
 * sees today, and a brand-new instance starts from the same defaults. The global values stay in
 * {@code LauncherPreferences} as those fallbacks — they are no longer read on the launch path.
 *
 * <p>Only the app theme is genuinely global; it lives in {@code LauncherPreferences} and is edited
 * in {@code AppSettingsFragment}. The audio API is no longer a setting at all — AAudio is the only
 * backend.
 *
 * <p>The instance name is the key, and instances cannot be renamed. {@link #forget(String)} drops a
 * deleted instance's keys, so a later instance reusing the name does not inherit a dead one's
 * settings.
 */
public class InstanceSettings {

    private static final String PREFIX = "inst:";

    /** One of the three macOS libraries ("lighting", "pathfind", "popman"): on unless the player
     *  turned it off under "Libraries in use". Only meaningful while that library is installed -
     *  there is no switch above the three any more (her call, 2026-09-16). */
    public boolean isMacosModuleEnabled(String module) {
        return prefs.getBoolean(keyPrefix + "macos_module_" + module, true);
    }

    public void setMacosModuleEnabled(String module, boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "macos_module_" + module, enabled).apply();
    }

    /** An install is the player's request to use what it installed: every library it verified
     *  gets its switch turned on again, whatever was chosen for the copy it replaced. */
    public void enableMacosModules(java.util.Collection<String> libraryNames) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String name : libraryNames) {
            String module = com.zomdroid.steam.MacosLibraries.MODULE_KEYS.get(name);
            if (module != null) editor.putBoolean(keyPrefix + "macos_module_" + module, true);
        }
        editor.apply();
    }

    /** The game's own ARM64 fmodintegration instead of the x86_64 one through box64. On by
     * default (her call, 2026-09-12): the linker falls back to box64 on its own when the library
     * fails its checks, and the switch is the way out for "loads but sounds wrong". */
    public boolean isNativeFmodEnabled() {
        return prefs.getBoolean(keyPrefix + "native_fmod", true);
    }

    public void setNativeFmodEnabled(boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "native_fmod", enabled).apply();
    }

    public boolean isCoopHostingEnabled() {
        return prefs.getBoolean(keyPrefix + "coop_hosting", false);
    }

    public void setCoopHostingEnabled(boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "coop_hosting", enabled).apply();
    }

    private final LauncherPreferences global;
    private final SharedPreferences prefs;
    private final String keyPrefix;

    public InstanceSettings(String instanceName) {
        this.global = LauncherPreferences.requireSingleton();
        this.prefs = global.getSharedPrefs();
        this.keyPrefix = PREFIX + (instanceName != null ? instanceName : "") + ":";
    }

    /** Removes every stored setting of a deleted instance. */
    public static void forget(String instanceName) {
        if (instanceName == null || instanceName.isEmpty()) return;
        LauncherPreferences global = LauncherPreferences.getSingleton();
        if (global == null) return;

        SharedPreferences prefs = global.getSharedPrefs();
        String prefix = PREFIX + instanceName + ":";
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.apply();
    }

    // --- Renderer ---------------------------------------------------------------------------

    public Renderer getRenderer() {
        String name = prefs.getString(keyPrefix + "renderer", null);
        if (name == null) return global.getRenderer();
        try {
            return Renderer.valueOf(name);
        } catch (IllegalArgumentException e) {
            return global.getRenderer(); // a renderer we dropped between releases
        }
    }

    public void setRenderer(Renderer renderer) {
        prefs.edit().putString(keyPrefix + "renderer", renderer.name()).apply();
    }

    // --- Vulkan driver ----------------------------------------------------------------------

    public VulkanDriver getVulkanDriver() {
        String name = prefs.getString(keyPrefix + "vulkan_driver", null);
        if (name == null) return global.getVulkanDriver();
        try {
            return VulkanDriver.valueOf(name);
        } catch (IllegalArgumentException e) {
            return global.getVulkanDriver();
        }
    }

    public void setVulkanDriver(VulkanDriver driver) {
        prefs.edit().putString(keyPrefix + "vulkan_driver", driver.name()).apply();
    }

    // --- JVM arguments and environment ------------------------------------------------------

    public String getJvmArgs() {
        return prefs.getString(keyPrefix + "jvm_args", global.getJvmArgs());
    }

    public void setJvmArgs(String jvmArgs) {
        prefs.edit().putString(keyPrefix + "jvm_args", jvmArgs != null ? jvmArgs : "").apply();
    }

    public String getEnvVars() {
        return prefs.getString(keyPrefix + "env_vars", global.getEnvVars());
    }

    public void setEnvVars(String envVars) {
        prefs.edit().putString(keyPrefix + "env_vars", envVars != null ? envVars : "").apply();
    }

    // --- Rendering --------------------------------------------------------------------------

    public float getRenderScale() {
        return prefs.getFloat(keyPrefix + "render_scale", global.getRenderScale());
    }

    public void setRenderScale(float renderScale) {
        prefs.edit()
                .putFloat(keyPrefix + "render_scale", Math.clamp(renderScale, 0.25f, 1.f))
                .apply();
    }

    // --- Toggles ----------------------------------------------------------------------------

    public boolean isDebug() {
        return prefs.getBoolean(keyPrefix + "debug", global.isDebug());
    }

    public void setDebug(boolean debug) {
        prefs.edit().putBoolean(keyPrefix + "debug", debug).apply();
    }

    public boolean isMemorySaver() {
        return prefs.getBoolean(keyPrefix + "memory_saver", global.isMemorySaver());
    }

    public void setMemorySaver(boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "memory_saver", enabled).apply();
    }

    public boolean isQuickSaveBackup() {
        return prefs.getBoolean(keyPrefix + "quick_save_backup", global.isQuickSaveBackup());
    }

    public void setQuickSaveBackup(boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "quick_save_backup", enabled).apply();
    }

    // --- On-screen controls -----------------------------------------------------------------
    // The layout itself is already per-instance (instances/<name>/game/controls/controls.json);
    // these two flags were the last global part of it.

    public boolean isTouchControlsEnabled() {
        return prefs.getBoolean(keyPrefix + "touch_controls", global.isTouchControlsEnabled());
    }

    public void setTouchControlsEnabled(boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "touch_controls", enabled).apply();
    }

    public boolean isVibrateOnTouch() {
        return prefs.getBoolean(keyPrefix + "vibrate_on_touch", global.isVibrateOnTouch());
    }

    public void setVibrateOnTouch(boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "vibrate_on_touch", enabled).apply();
    }

    // --- Texture compression (ETC2) ---------------------------------------------------------
    // NG_GL4ES and both ZINK variants can keep large textures as ETC2: a quarter of the memory,
    // encoded once and cached on disk. On by default - the devices it is for (6-8 GB, killed
    // by the low-memory killer mid-game) are exactly the ones whose owners never type an env
    // var. The switch is the way out if a device shows artefacts or its disk is tight. No global
    // counterpart: the setting is newer than the per-instance move, nothing to fall back to.

    public boolean isTextureCompression() {
        return prefs.getBoolean(keyPrefix + "texture_compression", true);
    }

    public void setTextureCompression(boolean enabled) {
        prefs.edit().putBoolean(keyPrefix + "texture_compression", enabled).apply();
    }
}
