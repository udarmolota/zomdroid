package com.zomdroid;

public class C {
    public static final String STORAGE_PROVIDER_AUTHORITY = "com.zomdroid.STORAGE_PROVIDER_AUTHORITY";

    public static class deps {
        public static final String ROOT = "dependencies";
        // We keep multiple JRE/LIBS versions side-by-side to support different game builds.
        // NOTE: This avoids breaking PZ Build 41 when the launcher ships Java 25 for Build 42+.
        public static final String JRE_ROOT = ROOT + "/jre";
        public static final String JRE_21 = JRE_ROOT + "21"; // <-- NEW
        public static final String JRE_25 = JRE_ROOT + "25"; // <-- NEW
        public static final String LIBS = ROOT + "/libs";
        public static final String JARS = ROOT + "/jars";
        public static final String LIBS_LINUX_X86_64 = LIBS + "/linux-x86_64";
        public static final String LIBS_ANDROID_ARM64_v8a = LIBS + "/android-arm64-v8a";
        public static final String LIBS_LWJGL_323 = LIBS_ANDROID_ARM64_v8a + "/lwjgl-3.2.3";
        public static final String LIBS_LWJGL_336 = LIBS_ANDROID_ARM64_v8a + "/lwjgl-3.3.6";
        public static final String LIBS_FMOD_20206 = LIBS_ANDROID_ARM64_v8a + "/fmod-2.02.06";
        public static final String LIBS_FMOD_20224 = LIBS_ANDROID_ARM64_v8a + "/fmod-2.02.24";
        public static final String LIBS_FMOD_20309 = LIBS_ANDROID_ARM64_v8a + "/fmod-2.03.09";
        public static final String JARS_SQLITE_JDBC_34800 = JARS + "/sqlite-jdbc-3.48.0.0.jar";
        public static final String JARS_ZOMDROID_AGENT = JARS + "/zomdroid-agent.jar";
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
        }
    }
}