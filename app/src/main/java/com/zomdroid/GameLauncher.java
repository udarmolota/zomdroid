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
            /*case NG_GL4ES: {
              Os.unsetenv("GALLIUM_DRIVER");

              Os.setenv("LIBGL_NOBANNER", "1", true);
              Os.setenv("LIBGL_SILENTSTUB", "0", true);
              Os.setenv("LIBGL_STACKTRACE", "1", true);
              Os.setenv("LIBGL_LOGSHADERERROR", "1", true);
              Os.setenv("LIBGL_ES", "2", true);
              Os.setenv("LIBGL_NODEPTHTEX", "1", true);
              Os.setenv("LIBGL_FB", "2", true);
              Os.setenv("LIBGL_FBONOALPHA", "1", true);
              //Os.setenv("LIBGL_GL", "21", true);

              Os.setenv("ZOMDROID_GLES_MAJOR", "2", true);
              Os.setenv("ZOMDROID_GLES_MINOR", "0", true);
              break;
            }*/
            default: {
                Os.setenv("ZOMDROID_GLES_MAJOR", "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "1", false);
                break;
            }
        }

        Os.setenv("ZOMDROID_AUDIO_API", LauncherPreferences.requireSingleton().getAudioAPI().name(), false);

        if (BuildConfig.DEBUG) {
            // for debugging GL calls, only supported on GL ES 3.2+ with GL_KHR_debug extension present
                /*Os.setenv("ZOMDROID_DEBUG_GL", "1", false);
                Os.setenv("ZOMDROID_DEBUG_GL", "1", false);
                Os.setenv("LIBGL_GLES", "libGLESv3.so", false);
                Os.setenv("ZOMDROID_GLES_MAJOR", "3", true);
                Os.setenv("ZOMDROID_GLES_MINOR", "2", true);*/
        }
        initZomdroidWindow();
        InputNativeInterface.sendJoystickConnected();

        ArrayList<String> jvmArgs = gameInstance.getJvmArgsAsList();
        String rawArgs = LauncherPreferences.requireSingleton().getJvmArgs();

        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            String[] splitArgs = rawArgs.trim().split("\\s+");
            for (String arg : splitArgs) {
                jvmArgs.add(arg);
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
            args.add("-debug");
            //args.add("-debuglog=Shader");
            Log.i("Zomdroid", "JVM ARGS: " + jvmArgs);
            Log.i("Zomdroid", "GAME ARGS: " + args);
        }

        //String javaHomePath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE;
        String home = AppStorage.requireSingleton().getHomePath();

        // Prefer JRE21 when using GL4ES-style renderers (Build 41 tends to rely on that path).
        // This isolates "old GL4ES pipeline" from "new Java 25 runtime" regressions.
        boolean preferJre21ForRenderer = isLegacyRendererNeedingJre21(LauncherPreferences.requireSingleton().getRenderer()); // ✅ Added

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
        boolean result = (r == LauncherPreferences.Renderer.GL4ES);

        if (BuildConfig.DEBUG) {
            Log.i("Zomdroid", "isLegacyRendererNeedingJre21: " + result + ", Renderer: " + r.name());
        }
        return result;
    }

    public static native int initZomdroidWindow();
    public static native void destroyZomdroidWindow();
    public static native int setSurface(Surface surface, int width, int height);
    public static native void destroySurface();
    static native void startGame(String gameDirPath, String libraryDirPath, String[] jvmArgs, String mainClassName, String[] args);
}