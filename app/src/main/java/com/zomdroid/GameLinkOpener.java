package com.zomdroid;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Opens the links the game's menus try to open (news, Discord, Steam pages) in the Android
 * browser. The coop agent's OpenUrl writes each URL to {@link #file()}; this polls for it, like
 * HostingOffNotice does for its marker. Only http(s) links are opened.
 */
final class GameLinkOpener implements AutoCloseable {
    private static final String LOG_TAG = "GameLinkOpener";
    private static final long POLL_MS = 1000;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File file = file();
    private final Runnable poll = this::poll;

    GameLinkOpener(Activity activity) {
        this.activity = activity;
        //noinspection ResultOfMethodCallIgnored
        file.delete(); // a link left from a previous run is not opened at start
        main.postDelayed(poll, POLL_MS);
    }

    /** Where the agent writes the URL; passed to the game as -Dzomdroid.openUrlFile. */
    static File file() {
        return new File(AppStorage.requireSingleton().getCachePath(), "game-open-url");
    }

    private void poll() {
        if (file.isFile()) {
            String url = null;
            try {
                url = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                Log.w(LOG_TAG, "Cannot read " + file, e);
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (url != null) open(url);
        }
        main.postDelayed(poll, POLL_MS);
    }

    private void open(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            Log.w(LOG_TAG, "Not opening a non-web link from the game: " + url);
            return;
        }
        if (activity.isFinishing()) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            Log.i(LOG_TAG, "Opened " + url);
        } catch (ActivityNotFoundException e) {
            Log.w(LOG_TAG, "No browser for " + url);
        }
    }

    @Override public void close() {
        main.removeCallbacks(poll);
    }
}
