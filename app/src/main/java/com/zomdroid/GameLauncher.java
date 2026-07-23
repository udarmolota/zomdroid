package com.zomdroid;

import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import android.util.Log;

import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.input.InputControlsView;
import com.zomdroid.game.GameInstance;
import com.zomdroid.BuildConfig;

import java.io.File;
import java.util.ArrayList;

public class GameLauncher {
    public static void launch(GameInstance gameInstance) throws ErrnoException {

/*        // for debug
        Os.setenv("MESA_DEBUG", "1", false);
        Os.setenv("MESA_LOG_LEVEL", "debug", false);
        Os.setenv("ZINK_DEBUG", "validation", false);
        Os.setenv("mesa_glthread", "false", false);
        Os.setenv("GALLIUM_THREAD", "0", false);
        Os.setenv("VK_LOADER_DEBUG", "all", false);
        Os.setenv("VK_DEBUG", "all", false);
        Os.setenv("GALLIUM_DEBUG", "all", false);
        Os.setenv("VK_LOADER_LAYERS_ENABLE", "VK_LAYER_KHRONOS_validation", false);
        Os.setenv("BOX64_LOG", "3", false);
        Os.setenv("BOX64_DYNAREC", "0", false);*/

        //Os.setenv("LIBGL_NOERROR", "1", false);
        //Os.setenv("LIBGL_LOGSHADERERROR", "1", false);
        //Os.setenv("ZINK_DEBUG", "spirv", false);

        Os.setenv("LIBGL_MIPMAP", "1", false);

        Os.setenv("BOX64_LOG", "1", false);
        Os.setenv("BOX64_SHOWBT", "1", false);
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), false);

        Os.setenv("GALLIUM_DRIVER", "zink", false);

        Os.setenv("ZOMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), false);
        Os.setenv("ZOMDROID_RENDERER", LauncherPreferences.requireSingleton().getRenderer().name(), false);
        switch (LauncherPreferences.requireSingleton().getRenderer()) {
            case ZINK_ZFA:
            case ZINK_OSMESA:
                String vulkanDriverName = LauncherPreferences.requireSingleton().getVulkanDriver().libName;
                if (vulkanDriverName != null) {
                    Os.setenv("ZOMDROID_VULKAN_DRIVER_NAME", vulkanDriverName, false);
                }
                break;
            case NG_GL4ES: {
                //Os.setenv("LIBGL_ES", "3", true);
                //Os.setenv("LIBGL_GL", "21", true); // если нужен OpenGL 2.1 для движка
                //Os.setenv("LIBGL_NOBANNER", "0", true);
                //Os.setenv("LIBGL_SILENTSTUB", "0", true); // если хотите убрать шум
                //Os.setenv("LIBGL_FB", "2", true);
                //Os.setenv("LIBGL_FBONOALPHA", "1", true);
                //Os.setenv("LIBGL_SIMPLE_SHADERCONV", "1", true);
                //Os.setenv("LIBGL_DBGSHADERCONV", "15", true);
                // Force SPIRV-Cross path instead of old ConvertShader
                // Without this, esversion stays 200 and shaders go through
                // the old converter that doesn't understand modern GLSL
                //Os.setenv("LIBGL_VGPU_FORCE", "1", true);
                //Os.setenv("LIBGL_VGPU_PRECISION", "1", true);
                // The DECISIVE knob is the real EGL context version, not the GL version the game
                // sees. On a true ES3 context Mali runs NG's internal ES3 paths, which
                // deterministically kill box64/physics at Bullet.init; a 2.1 context yields Mali's
                // ES2 profile and clean ES2 paths (proven playable). So: Qualcomm/Adreno -> ES3.2
                // context, everyone else -> ES2.1 context. We own the context; the lib (RC13+) owns
                // the badge and picks it from the actual context — do NOT set LIBGL_GL here, it
                // would override the lib's decision. override=false keeps manual env overrides.
                boolean isQualcomm = isQualcommGpu();
                Os.setenv("ZOMDROID_GLES_MAJOR", isQualcomm ? "3" : "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", isQualcomm ? "2" : "1", false);
                Os.setenv("LIBGL_ES", "2", false);
                Os.setenv("LIBGL_MIPMAP", "1", false);
                Os.setenv("LIBGL_LOGSHADERERROR", "1", false);
                Os.setenv("LIBGL_VGPU_DUMP", "1", false);
                // DEBUG: red-clear bisection — disabled now that swap/context are
                // confirmed alive; uncomment to mask frames again if needed.
                //Os.setenv("ZOMDROID_DEBUG_RED_CLEAR", "1", false);
                break;
            }
            default: {
                Os.setenv("ZOMDROID_GLES_MAJOR", "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "1", false);
                break;
            }
        }

        Os.setenv("ZOMDROID_AUDIO_API", LauncherPreferences.requireSingleton().getAudioAPI().name(), false);

        if (BuildConfig.DEBUG) {
            //for debugging GL calls, only supported on GL ES 3.2+ with GL_KHR_debug extension present
            Os.setenv("LIBGL_STACKTRACE","1", false);
            Os.setenv("LIBGL_LOGSHADERERROR","1", false);
        }
        initZomdroidWindow();
        InputNativeInterface.sendJoystickConnected();

        // JVM args [variables] from user settings
        ArrayList<String> jvmArgs = gameInstance.getJvmArgsAsList();
        String rawArgs = LauncherPreferences.requireSingleton().getJvmArgs();

        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            String[] splitArgs = rawArgs.trim().split("\\s+");
            for (String arg : splitArgs) {
                jvmArgs.add(arg);
            }
        }

        // Environment variables from user settings
        String rawEnvVars = LauncherPreferences.requireSingleton().getEnvVars();
        if (rawEnvVars != null && !rawEnvVars.trim().isEmpty()) {
            for (String token : rawEnvVars.trim().split("\\s+")) {
                String[] parts = token.split("=", 2);
                if (parts.length == 2) {
                    Os.setenv(parts[0].trim(), parts[1].trim(), true);
                }
            }
        }

        jvmArgs.add("-Dorg.lwjgl.opengl.libname=" + LauncherPreferences.requireSingleton().getRenderer().libName);
        jvmArgs.add("-Dzomdroid.renderer=" + LauncherPreferences.requireSingleton().getRenderer().name());

        if (BuildConfig.DEBUG) {
            jvmArgs.add("-Dorg.lwjgl.util.Debug=true"); //print LWJGL library errors
            //jvmArgs.add("-Dorg.lwjgl.util.DebugLoader=true");
            jvmArgs.add("-XX:+PrintFlagsFinal"); // for debugging
        }

        jvmArgs.add("-XX:ErrorFile=/dev/stdout"); // print jvm crash report to stdout for now


        ArrayList<String> args = gameInstance.getArgsAsList();
        if (BuildConfig.DEBUG) {
            //args.add("-debug");
            //args.add("-debuglog=Shader");
        }
        Log.i("Zomdroid", "JVM ARGS: " + jvmArgs);
        Log.i("Zomdroid", "GAME ARGS: " + args);

        if (BuildConfig.DEBUG || LauncherPreferences.requireSingleton().isDebug()) {
            args.add("-debug");
        }

        if (LauncherPreferences.requireSingleton().getRenderer() == LauncherPreferences.Renderer.NG_GL4ES) {
            args.add("-debuglog=Shader");
        }

        //String javaHomePath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE;
        String home = AppStorage.requireSingleton().getHomePath();

        // Prefer JRE21 when using GL4ES-style renderers (Build 41 tends to rely on that path).
        // This isolates "old GL4ES pipeline" from "new Java 25 runtime" regressions.
        boolean preferJre21ForRenderer = isLegacyRendererNeedingJre21(LauncherPreferences.requireSingleton().getRenderer());
        // ZombieBuddy agent — loaded if jar present in game folder AND enabled in settings
        android.content.SharedPreferences zbPrefs = LauncherPreferences.requireSingleton().getSharedPrefs();

        String instanceName = gameInstance.getName();
        String zombieBuddyPath = gameInstance.getGamePath() + "/" + C.deps.ZOMBIE_BUDDY_JAR;
        boolean zombieBuddyEnabled = zbPrefs.getBoolean("zombiebuddy_enabled_" + instanceName, false);
        if (new File(zombieBuddyPath).exists() && zombieBuddyEnabled) {
            jvmArgs.add("-javaagent:" + zombieBuddyPath + "=policy=allow-all");
            jvmArgs.add("-Dnet.bytebuddy.processor=ASM_ONLY");
            jvmArgs.add("-Dnet.bytebuddy.experimental=true");
            // On JRE25 (ZINK) we do NOT set classfile.version — ByteBuddy must handle
            if (!preferJre21ForRenderer) {
                Log.i("ZombieBuddy", "in GameLauncher ZINK loaded, ZombieBuddy loaded.");
                //jvmArgs.add("-Dnet.bytebuddy.classfile.version=65");
                //jvmArgs.add("-Dnet.bytebuddy.unsupported.classfile.version=69");
            }
        }

        // Try to use dedicated folders if present (jre21 / jre25). If not present, fall back to C.deps.JRE.
        String jreFolder = preferJre21ForRenderer ? C.deps.JRE_21 : C.deps.JRE_25;
        String candidateJavaHomePath = home + "/" + jreFolder;
        String javaHomePath;

        if (new File(candidateJavaHomePath).exists()) {
            javaHomePath = candidateJavaHomePath;
        } else {
            // fallback for setups that still package only one JRE folder (legacy behavior)
            javaHomePath = home + "/" + C.deps.JRE_ROOT;
        }
        if (BuildConfig.DEBUG) {
            Log.i("Zomdroid", "jreFolder: " + jreFolder+", candidateJavaHomePath: "+candidateJavaHomePath+", javaHomePath: "+javaHomePath);
        }
        String ldLibraryPath = AppStorage.requireSingleton().getLibraryPath() + ":/system/lib64:"
                + javaHomePath + "/lib:" + javaHomePath + "/lib/server:" + gameInstance.getJavaLibraryPath();
        //Log.d("zomdroid-main", ldLibraryPath);
        GameLauncher.startGame(gameInstance.getGamePath(), ldLibraryPath, jvmArgs.toArray(new String[0]),
                gameInstance.getMainClassName(), args.toArray(new String[0]));
    }

    private static boolean isLegacyRendererNeedingJre21(LauncherPreferences.Renderer r) {
        boolean result = (r == LauncherPreferences.Renderer.GL4ES) || (r == LauncherPreferences.Renderer.NG_GL4ES);

        if (BuildConfig.DEBUG) {
            Log.i("Zomdroid", "isLegacyRendererNeedingJre21: " + result + ", Renderer: " + r.name());
        }
        return result;
    }

    // Positive-ID Qualcomm/Adreno only (they tolerate the ES3 EGL context). Everything else —
    // MediaTek/Mali, and any unknown, to stay safe — returns false so NG gets the ES2 context.
    // Same GPU split RC13 uses on the lib side; sourced from Android-level info that the GL
    // stack can't hide (GL_RENDERER comes back '<unknown>' through box64/Krypton).
    private static boolean isQualcommGpu() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.toLowerCase();
                if (l.contains("qualcomm") || l.contains("snapdragon")) return true;
                if (l.contains("mediatek") || l.contains("dimensity") || l.contains("helio")) return false;
            }
        } catch (Exception ignored) {}

        String[] fields = { android.os.Build.HARDWARE, android.os.Build.BOARD,
                            android.os.Build.SOC_MODEL, android.os.Build.SOC_MANUFACTURER };
        for (String f : fields) {
            if (f == null) continue;
            String l = f.toLowerCase();
            if (l.contains("qcom") || l.contains("qualcomm") || l.contains("snapdragon")) return true;
        }
        return false;
    }

    public static native int initZomdroidWindow();
    public static native void destroyZomdroidWindow();
    public static native int setSurface(Surface surface, int width, int height);
    public static native void destroySurface();
    static native void startGame(String gameDirPath, String libraryDirPath, String[] jvmArgs, String mainClassName, String[] args);
}
