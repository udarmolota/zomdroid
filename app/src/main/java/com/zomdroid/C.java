package com.zomdroid;

public class C {
    public static final String STORAGE_PROVIDER_AUTHORITY = "com.zomdroid.STORAGE_PROVIDER_AUTHORITY";

    /**
     * On-disk cache of ETC2-compressed textures, one file per texture, named by content hash.
     * Written by NG_GL4ES and by our Mesa build for ZINK (same encoder, same hash, same store).
     * Shared by every instance on purpose - the addressing is by content, so two instances
     * using the same texture share one entry. Safe to delete whole or in part at any time: the
     * renderer re-encodes what it misses. Its size ceiling and eviction are the library's business
     * (LRU, LIBGL_ETC2CACHE_MB), not the launcher's.
     *
     * <p>Relative to {@link AppStorage#getHomePath()}.
     */
    public static final String NGG_ETC2_CACHE_DIR = "ngg_etc2cache";

    /**
     * Mesa's on-disk shader cache for both ZINK renderers (zink + turnip pipelines). Mesa keys
     * entries by driver build and ignores stale ones itself; safe to delete while the game is
     * closed. Relative to {@link AppStorage#getHomePath()}.
     */
    public static final String ZINK_SHADER_CACHE_DIR = "zink_shadercache";

    public static class deps {
        public static final String ROOT = "dependencies";
        // We keep multiple JRE/LIBS versions side-by-side to support different game builds.
        // NOTE: This avoids breaking PZ Build 41 when the launcher ships Java 25 for Build 42+.
        public static final String JRE_ROOT = ROOT + "/jre";
        public static final String JRE_21 = JRE_ROOT + "21";
        public static final String JRE_25 = JRE_ROOT + "25";
        public static final String LIBS = ROOT + "/libs";
        public static final String JARS = ROOT + "/jars";
        public static final String LIBS_LINUX_X86_64 = LIBS + "/linux-x86_64";
        public static final String LIBS_ANDROID_ARM64_v8a = LIBS + "/android-arm64-v8a";
        public static final String LIBS_LWJGL_323 = LIBS_ANDROID_ARM64_v8a + "/lwjgl-3.2.3";
        public static final String LIBS_LWJGL_336 = LIBS_ANDROID_ARM64_v8a + "/lwjgl-3.3.6";
        // Build 42.20 moved to LWJGL 3.4.1. The natives are ours: LWJGL publishes no Android
        // target and its linux-arm64 binaries are glibc, so these are cross-compiled for bionic
        // by build-lwjgl-3.4.1.sh in the zomdroid-dependencies repo.
        public static final String LIBS_LWJGL_341 = LIBS_ANDROID_ARM64_v8a + "/lwjgl-3.4.1";
        public static final String LIBS_FMOD_20206 = LIBS_ANDROID_ARM64_v8a + "/fmod-2.02.06";
        public static final String LIBS_FMOD_20224 = LIBS_ANDROID_ARM64_v8a + "/fmod-2.02.24";
        public static final String LIBS_FMOD_20309 = LIBS_ANDROID_ARM64_v8a + "/fmod-2.03.09";
        public static final String JARS_SQLITE_JDBC_34800 = JARS + "/sqlite-jdbc-3.48.0.0.jar";
        public static final String JARS_ZOMDROID_AGENT = JARS + "/zomdroid-agent.jar";

        // ZombieBuddy and ZBBetterFPS jars are stored per-instance in game/ folder.
        // Use gameInstance.getGamePath() + "/" + filename to get the full path.
        public static final String ZOMBIE_BUDDY_JAR = "ZombieBuddy.jar";
        public static final String ZB_BETTER_FPS_JAR = "ZBBetterFPS.jar";

        // Custom user-supplied Vulkan driver. Stored directly in LIBS_ANDROID_ARM64_v8a.
        public static final String CUSTOM_DRIVER_FILENAME = "custom_driver.so";
        public static final String CUSTOM_DRIVER = LIBS_ANDROID_ARM64_v8a + "/" + CUSTOM_DRIVER_FILENAME;
    }

    public static class assets {
        public static final String BUNDLES = "bundles";
        public static final String BUNDLES_JRE21 = BUNDLES + "/jre21.tar.xz";
        public static final String BUNDLES_JRE25 = BUNDLES + "/jre25.tar.xz";
        public static final String BUNDLES_LIBS = BUNDLES + "/libs.tar.xz";
        public static final String BUNDLES_JARS = BUNDLES + "/jars.tar";
        public static final String DEFAULT_CONTROLS = "default_controls.json";
    }

    public static class shprefs {
        public static final String NAME = "com.zomdroid.PREFS";

        public static class keys {
            public static final String LAUNCHER_VERSION = "launcherVersion";
            public static final String INPUT_CONTROLS = "inputControls";
            public static final String GAME_INSTANCES = "gameInstances";
            public static final String LAUNCHER_PREFS = "launcherPrefs";
            public static final String INSTALLED_BUNDLES = "installedBundles";
            public static final String ARE_DEPENDENCIES_INSTALLED = "areDependenciesInstalled";
            public static final String IS_LEGAL_NOTICE_ACCEPTED = "isLegalNoticeAccepted";
            // versionName the release notes were last shown for (or recorded silently on a fresh
            // install). Differing from BuildConfig.VERSION_NAME means an update happened.
            public static final String RELEASE_NOTES_SHOWN_FOR = "releaseNotesShownFor";
        }
    }
}