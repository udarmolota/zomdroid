package com.zomdroid.game;

import com.zomdroid.C;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PresetManager {
    private static final ArrayList<InstallationPreset> presets = new ArrayList<>();

    static {
        presets.add(new InstallationPreset.Builder()
                .setName("Build 42")
                .setBuildVersion("42")
                .setClassPathArray(new String[]{
                        ".",
                        "commons-compress-1.27.1.jar",
                        "commons-io-2.18.0.jar",
                        "istack-commons-runtime.jar",
                        "jassimp.jar",
                        "guava-23.0.jar",
                        "javacord-3.8.0-shaded.jar",
                        "javax.activation-api.jar",
                        "jaxb-api.jar",
                        "jaxb-runtime.jar",
                        "lwjgl.jar",
                        "lwjgl-glfw.jar",
                        "lwjgl-jemalloc.jar",
                        "lwjgl-opengl.jar",
                        "lwjgl_util.jar",
                        "sqlite-jdbc-3.48.0.0.jar",
                        "trove-3.0.3.jar",
                        "uncommons-maths-1.2.3.jar",
                        "imgui-binding-1.86.11-8-g3e33dde.jar",
                        "commons-codec-1.10.jar",
                        "javase-3.2.1.jar",
                        "totp-1.0.jar",
                        "core-3.2.1.jar"
                })
                .setExtraJars(new String[0])
                .setLibraryPathArray(new String[]{
                        C.deps.LIBS_LWJGL_336,
                        C.deps.LIBS_ANDROID_ARM64_v8a
                })
                .setLibraryPathForEmulationArray(new String[]{
                        C.deps.LIBS_LINUX_X86_64
                })
                .setFmodLibraryPath(C.deps.LIBS_FMOD_20224)
                .setExtraJvmArgs(new String[0])
                .setArgs(new String[0])
                .setMainClassName("zombie/gameStates/MainScreenState")
                .setJavaAgentPath(C.deps.JARS_ZOMDROID_AGENT)
                //.setJavaAgentArgs("build=42")
                .build()
        );
        // NEW 42.12 structure
        presets.add(new InstallationPreset.Builder()
                .setName("Build 42.12+")
                .setBuildVersion("42")
                .setClassPathArray(new String[]{
                        ".",
                        "commons-compress-1.27.1.jar",
                        "commons-io-2.18.0.jar",
                        "istack-commons-runtime.jar",
                        "jassimp.jar",
                        "guava-23.0.jar",
                        "javacord-3.8.0-shaded.jar",
                        "javax.activation-api.jar",
                        "jaxb-api.jar",
                        "jaxb-runtime.jar",
                        "lwjgl.jar",
                        "lwjgl-glfw.jar",
                        "lwjgl-jemalloc.jar",
                        "lwjgl-opengl.jar",
                        "lwjgl_util.jar",
                        "sqlite-jdbc-3.48.0.0.jar",
                        "trove-3.0.3.jar",
                        "uncommons-maths-1.2.3.jar",
                        "imgui-binding-1.86.11-8-g3e33dde.jar",
                        "commons-codec-1.10.jar",
                        "javase-3.2.1.jar",
                        "totp-1.0.jar",
                        "core-3.2.1.jar"
                })
                .setExtraJvmArgs(new String[0])
                .setLibraryPathArray(new String[]{
                        C.deps.LIBS_LWJGL_336,
                        C.deps.LIBS_ANDROID_ARM64_v8a
                })
                .setLibraryPathForEmulationArray(new String[]{
                        C.deps.LIBS_LINUX_X86_64
                })
                .setFmodLibraryPath(C.deps.LIBS_FMOD_20309)
                //.setExtraJvmArgs(new String[0])
                .setArgs(new String[0])
                .setMainClassName("zombie/gameStates/MainScreenState")
                .setJavaAgentPath(C.deps.JARS_ZOMDROID_AGENT)
                //.setJavaAgentArgs("build=42")
                .build()
        );

        presets.add(new InstallationPreset.Builder()
                .setName("Build 41")
                .setBuildVersion("41")
                .setClassPathArray(new String[]{
                        ".",
                        "commons-compress-1.18.jar",
                        "istack-commons-runtime.jar",
                        "jassimp.jar",
                        "javacord-2.0.17-shaded.jar",
                        "javax.activation-api.jar",
                        "jaxb-api.jar",
                        "jaxb-runtime.jar",
                        "lwjgl.jar",
                        "lwjgl-glfw.jar",
                        "lwjgl-jemalloc.jar",
                        "lwjgl-opengl.jar",
                        "lwjgl_util.jar",
                        "trove-3.0.3.jar",
                        "uncommons-maths-1.2.3.jar"
                })
                .setExtraJars(new String[]{
                        C.deps.JARS_SQLITE_JDBC_34800
                })
                .setLibraryPathArray(new String[]{
                        C.deps.LIBS_LWJGL_323,
                        C.deps.LIBS_ANDROID_ARM64_v8a                        
                })
                .setLibraryPathForEmulationArray(new String[]{
                        C.deps.LIBS_LINUX_X86_64
                })
                .setFmodLibraryPath(C.deps.LIBS_FMOD_20206)
                .setExtraJvmArgs(new String[0])
                .setArgs(new String[0])
                .setMainClassName("zombie/gameStates/MainScreenState")
                .setJavaAgentPath(C.deps.JARS_ZOMDROID_AGENT)
                //.setJavaAgentArgs("build=41")
                .build()
        );
    }

    public static ArrayList<InstallationPreset> getPresets() {
        return presets;
    }

    /** Indices into {@link #getPresets()}: the order the presets are registered in above. */
    public static final int PRESET_BUILD_42 = 0;
    public static final int PRESET_BUILD_42_12 = 1;
    public static final int PRESET_BUILD_41 = 2;

    /**
     * Which preset a set of game files calls for, from their paths: ZIP entry names, or paths
     * relative to an extracted game folder. Tolerant of wrapper folders, so it works on an archive
     * before the root drill as well as on the flattened folder after it.
     * <pre>
     *   natives/libPZBullet64.so -> Build 42.12+ (the 42.20+ Linux layout)
     *   an android/ folder       -> Build 42.12+
     *   imgui*.jar               -> Build 42
     *   nothing of the above     -> Build 41
     * </pre>
     */
    public static int detectPresetIndex(Iterable<String> paths) {
        boolean imgui = false, android = false, layout4220 = false;
        for (String path : paths) {
            if (isBuild4220NativeLayoutEntry(path)) layout4220 = true;
            if (isAndroidDirEntry(path)) android = true;
            if (isImguiJar(path)) imgui = true;
        }
        if (layout4220 || android) return PRESET_BUILD_42_12;
        return imgui ? PRESET_BUILD_42 : PRESET_BUILD_41;
    }

    /**
     * {@link #detectPresetIndex} on a game folder, looking two levels deep - every marker sits at
     * the root or one folder down. Null if the folder cannot be listed.
     */
    public static InstallationPreset detectFromGameDir(File gameDir) {
        File[] top = gameDir.listFiles();
        if (top == null) return null;
        List<String> paths = new ArrayList<>();
        for (File f : top) {
            paths.add(f.getName());
            if (!f.isDirectory()) continue;
            File[] inner = f.listFiles();
            if (inner == null) continue;
            for (File g : inner) paths.add(f.getName() + "/" + g.getName());
        }
        return presets.get(detectPresetIndex(paths));
    }

    // Matches an "android" directory at ANY depth - tolerant of wrapper folders
    // (e.g. "PZ/android/arm64-v8a/...") so version detection still works on nested zips.
    private static boolean isAndroidDirEntry(String name) {
        String n = name.replace('\\', '/');
        return n.equals("android") || n.startsWith("android/")
                || n.endsWith("/android") || n.contains("/android/");
    }

    // Matches the Linux Bullet library at any wrapper depth. Unlike a generic natives/ match,
    // this cannot be confused with an unrelated dependency directory in the archive.
    private static boolean isBuild4220NativeLayoutEntry(String name) {
        String n = name.replace('\\', '/');
        return n.equals("natives/libPZBullet64.so")
                || n.endsWith("/natives/libPZBullet64.so");
    }

    private static boolean isImguiJar(String name) {
        String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        String baseLower = base.toLowerCase();
        return baseLower.startsWith("imgui") && baseLower.endsWith(".jar");
    }
}
