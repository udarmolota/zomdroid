package com.zomdroid.game;

import com.zomdroid.C;

import java.util.ArrayList;

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
                        C.deps.LIBS_ANDROID_ARM64_v8a,
                        C.deps.LIBS_LWJGL_336                                                
                })
                .setLibraryPathForEmulationArray(new String[]{
                        C.deps.LIBS_LINUX_X86_64
                })
                .setFmodLibraryPath(C.deps.LIBS_FMOD_20224)
                .setExtraJvmArgs(new String[0])
                .setArgs(new String[]{
                        "-novoip"
                })
                .setMainClassName("zombie/gameStates/MainScreenState")
                .setJavaAgentPath(C.deps.JARS_ZOMDROID_AGENT)
                //.setJavaAgentArgs("build=42")
                .build()
        );
        // NEW 42.13
        presets.add(new InstallationPreset.Builder()
                .setName("Build 42.13")
                .setBuildVersion("42")
                .setClassPathArray(new String[]{
                        ".",
                        "projectzomboid.jar",
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
                        C.deps.LIBS_ANDROID_ARM64_v8a,
                        C.deps.LIBS_LWJGL_336                                                
                })
                .setLibraryPathForEmulationArray(new String[]{
                        C.deps.LIBS_LINUX_X86_64
                })
                .setFmodLibraryPath(C.deps.LIBS_FMOD_20224)
                .setExtraJvmArgs(new String[0])
                .setArgs(new String[]{
                        "-novoip"
                })
                .setMainClassName("zombie.gameStates.MainScreenState")
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
                .setArgs(new String[]{
                        "-novoip"
                })
                .setMainClassName("zombie/gameStates/MainScreenState")
                .setJavaAgentPath(C.deps.JARS_ZOMDROID_AGENT)
                //.setJavaAgentArgs("build=41")
                .build()
        );
    }

    public static ArrayList<InstallationPreset> getPresets() {
        return presets;
    }
}
