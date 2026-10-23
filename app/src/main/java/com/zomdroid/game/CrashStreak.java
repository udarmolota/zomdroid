package com.zomdroid.game;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.zomdroid.C;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Counts the game's crashes in a row, per instance, from the console.txt the last run left. A run
 * counts as a crash when the log carries a fatal exception (GameWindow.uncaughtException - the
 * broken-font crash; GameLoadingState failing - a mod or a library from another game build) or
 * never reaches the normal "GameThread exited" (killed, or a native crash). A clean exit resets the
 * count. Every log is judged once, by its modification time.
 *
 * <p>Swiping the app away mid-game also leaves no "GameThread exited" and counts; the launcher only
 * suggests a check after two in a row, and the player can always start as is.
 */
public final class CrashStreak {
    private static final String KEY_STREAK = "crash_streak_";
    private static final String KEY_CHECKED = "crash_checked_";
    /** The end of the log is where a run ends: enough to see the exit or the fatal trace. */
    private static final int TAIL_BYTES = 512 * 1024;

    private CrashStreak() {
    }

    /** Judges the last run if not done yet and returns the crashes in a row so far. */
    public static int update(@NonNull Context context, @NonNull GameInstance instance) {
        SharedPreferences prefs = context.getSharedPreferences(C.shprefs.NAME, Context.MODE_PRIVATE);
        String name = instance.getName();
        int streak = prefs.getInt(KEY_STREAK + name, 0);
        File log = new File(instance.getHomePath(), "Zomboid/console.txt");
        if (!log.isFile()) return streak;
        long mtime = log.lastModified();
        if (mtime == prefs.getLong(KEY_CHECKED + name, 0)) return streak;
        streak = crashed(log) ? streak + 1 : 0;
        prefs.edit().putInt(KEY_STREAK + name, streak).putLong(KEY_CHECKED + name, mtime).apply();
        return streak;
    }

    private static boolean crashed(File log) {
        String tail;
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long len = f.length();
            int n = (int) Math.min(len, TAIL_BYTES);
            byte[] buf = new byte[n];
            f.seek(len - n);
            f.readFully(buf);
            tail = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return false;   // unreadable: say nothing rather than accuse the run
        }
        return !tail.contains("GameThread exited")
                || tail.contains("GameWindow.uncaughtException")
                || tail.contains("GameLoadingState$1.run> Exception thrown");
    }
}
