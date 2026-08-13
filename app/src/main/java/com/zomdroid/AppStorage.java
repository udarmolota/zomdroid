package com.zomdroid;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


public class AppStorage {
    private final String HOME_DIR_PATH;
    private final String CACHE_DIR_PATH;
    private final String LIBRARY_DIR_PATH;
    private static AppStorage singleton;

    private AppStorage(Context applicationContext) {
        HOME_DIR_PATH = canonical(applicationContext.getFilesDir());
        CACHE_DIR_PATH = canonical(applicationContext.getCacheDir());
        LIBRARY_DIR_PATH = applicationContext.getApplicationInfo().nativeLibraryDir;
    }

    /**
     * The app's own directories, in the form the runtime resolves them to.
     *
     * <p>Android reaches the same directory under two names - {@code /data/user/0/<pkg>} and
     * {@code /data/data/<pkg>} - and on some devices they are the same directory while on others
     * one resolves to the other. Anything we hand the game is compared against paths the game
     * itself has canonicalised, and comparing two spellings of one directory as strings quietly
     * fails.
     *
     * <p>Project Zomboid does exactly that. To turn an absolute file path into the short name it
     * stores files under, it subtracts the mod folder from the file path - but it canonicalises the
     * folder and not the file. When the two spellings differ, {@code URI.relativize} finds no common
     * prefix, returns its argument untouched, and the "short name" comes back as a full absolute
     * path. Looking that up in the file table returns null, and in multiplayer - the only place this
     * code runs - the two callers react differently: {@code ScriptManager.Load} logs a
     * NullPointerException per script file, while {@code AdvancedAnimator.buildChecksum} throws
     * IllegalStateException("couldn't find ..."), which fails the Lua reload and leaves the player
     * on a dead screen after joining a modded server.
     *
     * <p>Handing over the resolved form makes both sides of that subtraction agree. Where the two
     * names are already the same directory this changes nothing at all - which is also why the bug
     * reproduces on some devices and not others.
     */
    private static String canonical(java.io.File dir) {
        try {
            return dir.getCanonicalPath();
        } catch (java.io.IOException e) {
            return dir.getAbsolutePath();
        }
    }

    public static void init(Context applicationContext) {
        singleton = new AppStorage(applicationContext);
    }

    @Nullable
    public static AppStorage getSingleton() {
        return singleton;
    }

    @NonNull
    public static AppStorage requireSingleton() {
        if (singleton == null) {
            throw new RuntimeException("AppStorage is not initialized");
        }
        return singleton;
    }

    public String getHomePath() {
        return HOME_DIR_PATH;
    }

    private boolean isExternalStorageWritable() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }

    public String getCachePath() {
        return CACHE_DIR_PATH;
    }

    public String getLibraryPath() {
        return LIBRARY_DIR_PATH;
    }
}
