package com.zomdroid;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.File;

public class LauncherPreferences {
    private static LauncherPreferences singleton;
    transient private SharedPreferences sharedPreferences;
    transient private Gson gson;

    private float renderScale = 1.f;
    private Renderer renderer = Renderer.GL4ES;
    private VulkanDriver vulkanDriver = VulkanDriver.SYSTEM_DEFAULT;
    private boolean isDebug = false;
    private AudioAPI audioAPI = AudioAPI.AAUDIO;
    private String jvmArgs = "";

    private static final String KEY_TOUCH_CONTROLS = "touch_controls_enabled";

    LauncherPreferences() {
        //if (isKgslSupported()) {
        //    this.renderer = Renderer.ZINK_ZFA;
        //    this.vulkanDriver = VulkanDriver.FREEDRENO;
        //}
    }

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
            } catch (SecurityException ignored) {
                // If access to /dev is restricted, treat as "unknown" (false) for this probe.
                // Consider logging in debug builds.
            }
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
    
    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs != null ? jvmArgs : "";
        saveToPreferences();
    }

    public enum Renderer {
        ZINK_ZFA("libzfa.so"),
        ZINK_OSMESA("libOSMesa.so"),
        GL4ES("libgl4es.so");
        //NG_GL4ES("libng_gl4es.so");

        final String libName;
        Renderer(String libName) {
            this.libName = libName;
        }
    }

    public enum VulkanDriver {
        SYSTEM_DEFAULT(null),
        FREEDRENO("libvulkan_freedreno.so"),
        FREEDRENO_8XX_Expr("libvulkan_freedreno_8xx.so"),
        TURNIP_bbdd688("vulkan.ad07XX_regular.so"),
        TURNIP_bbdd688_8gen2("vulkan.ad07XX.so");

        final String libName;
        VulkanDriver(String libName) {
            this.libName = libName;
        }
    }

    public enum AudioAPI {
        AAUDIO,
        OPENSL
    }

    private boolean touchControlsEnabled = false;

    public boolean isTouchControlsEnabled() {
        return touchControlsEnabled;
    }

    public void setTouchControlsEnabled(boolean enabled) {
        touchControlsEnabled = enabled;
    }

}
