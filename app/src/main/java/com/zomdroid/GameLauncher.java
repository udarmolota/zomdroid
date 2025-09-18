package com.zomdroid;

import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import android.util.Log;

import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.game.GameInstance;

import java.util.ArrayList;
import java.io.File;
import com.zomdroid.C;

public class GameLauncher {
    private static final String TAG = "GameLauncher";
    
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
/*        Os.setenv("LIBGL_LOGSHADERERROR", "1", false);
        Os.setenv("ZINK_DEBUG", "spirv", false);*/

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
        }

        Os.setenv("ZOMDROID_AUDIO_API", LauncherPreferences.requireSingleton().getAudioAPI().name(), false);

        Os.setenv("ZOMDROID_GLES_MAJOR", "2", false);
        Os.setenv("ZOMDROID_GLES_MINOR", "1", false);

        // for debugging GL calls, only supported on GL ES 3.2+ with GL_KHR_debug extension present
/*        Os.setenv("ZOMDROID_DEBUG_GL", "1", false);
        Os.setenv("LIBGL_GLES", "libGLESv3.so", false);
        Os.setenv("ZOMDROID_GLES_MAJOR", "3", true);
        Os.setenv("ZOMDROID_GLES_MINOR", "2", true);*/

        initZomdroidWindow();
        InputNativeInterface.sendJoystickConnected();

        ArrayList<String> jvmArgs = gameInstance.getJvmArgsAsList();
        jvmArgs.add("-Dorg.lwjgl.opengl.libname=" + LauncherPreferences.requireSingleton().getRenderer().libName);
        jvmArgs.add("-Dzomdroid.renderer=" + LauncherPreferences.requireSingleton().getRenderer().name());
        //jvmArgs.add("-XX:+PrintFlagsFinal"); // for debugging
        jvmArgs.add("-XX:ErrorFile=/dev/stdout"); // print jvm crash report to stdout for now

        jvmArgs.add("-Dorg.lwjgl.util.Debug=true");
        jvmArgs.add("-Dorg.lwjgl.util.DebugLoader=true");

        ArrayList<String> args = gameInstance.getArgsAsList();
/*      args.add("-debug");
        args.add("-debuglog=Shader");*/
        
        String javaHomePath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE;
        //String ldLibraryPath = AppStorage.requireSingleton().getLibraryPath() + ":/system/lib64:"
        //        + javaHomePath + "/lib:" + javaHomePath + "/lib/server:" + gameInstance.getJavaLibraryPath();
        
        String depsLibRoot = AppStorage.requireSingleton().getLibraryPath(); // => .../dependencies/libs
        String baseJvmLibs = "/system/lib64:" + javaHomePath + "/lib:" + javaHomePath + "/lib/server";
        String lwjgl336Abs = depsLibRoot + "/android-arm64-v8a/lwjgl-3.3.6";
        String ldLibraryPath;

        boolean build42 = isBuild42(gameInstance);
        if (build42) {
            // if exists
            boolean exists = new File(lwjgl336Abs).isDirectory();
            Log.i(TAG, "Detected Build42=" + true + ", LWJGL336 dir=" + lwjgl336Abs + ", exists=" + exists);

            // if not
            if (exists) {
                ldLibraryPath = lwjgl336Abs + ":" + baseJvmLibs + ":" + gameInstance.getJavaLibraryPath() + ":" + depsLibRoot;
            } else {
                Log.w(TAG, "LWJGL 3.3.6 directory not found, falling back to default order");
                ldLibraryPath = depsLibRoot + ":" + baseJvmLibs + ":" + gameInstance.getJavaLibraryPath();
            }
        } else {
            Log.i(TAG, "Detected Build42=" + false + " (legacy), using default LD path order");
            ldLibraryPath = depsLibRoot + ":" + baseJvmLibs + ":" + gameInstance.getJavaLibraryPath();
        }

        Log.i(TAG, "LD_LIBRARY_PATH=" + ldLibraryPath);        
        
        GameLauncher.startGame(gameInstance.getGamePath(), ldLibraryPath, jvmArgs.toArray(new String[0]),
                gameInstance.getMainClassName(), args.toArray(new String[0]));
    }
    private static boolean isBuild42(com.zomdroid.game.GameInstance gi) {
        String lp = gi.getJavaLibraryPath();
        return lp != null && (lp.contains("lwjgl-3.3.6") || lp.contains("Build 42"));
    }

    public static native int initZomdroidWindow();

    public static native void destroyZomdroidWindow();

    public static native int setSurface(Surface surface, int width, int height);

    public static native void destroySurface();

    static native void startGame(String gameDirPath, String libraryDirPath, String[] jvmArgs, String mainClassName, String[] args);
}
