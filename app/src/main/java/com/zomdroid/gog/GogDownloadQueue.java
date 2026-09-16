package com.zomdroid.gog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zomdroid.DownloadKeepAliveService;
import com.zomdroid.R;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide queue of GOG downloads. Ported from RimDroid (MIT).
 *
 * <p>It lives outside any screen for the reason {@link com.zomdroid.steam.SteamDownloadState} does:
 * a transfer of gigabytes easily outlasts the fragment that started it, and the user must be able to
 * leave the GOG screen, come back, and find the progress where they left it. Holding the state here
 * is also what stops a second tap from starting a second transfer into the same part file: a fresh
 * fragment would have no idea a download was already running, and two writers appending to one
 * {@code .part} silently corrupt it.
 *
 * <p>Files download one at a time, in the order asked for. The keep-alive notification is held from
 * the first request until the queue drains: one notification for the whole batch.
 */
public final class GogDownloadQueue {
    private static final String TAG = "Zomdroid/GOG";

    private static final GogDownloadQueue INSTANCE = new GogDownloadQueue();
    public static GogDownloadQueue get() { return INSTANCE; }
    private GogDownloadQueue() {}

    public enum Phase { QUEUED, RUNNING, DONE, FAILED }

    /** One requested file and how far it has got. Written by the worker, read by the UI. */
    public static final class Entry {
        public final GogLibrary.File file;
        public final File target;
        final File destDir;
        public volatile Phase phase = Phase.QUEUED;
        /** While RUNNING: 0-100, or -1 as long as the server has not said how big the file is. */
        public volatile int percent = -1;
        /** When FAILED: why. */
        public volatile Throwable failure;

        Entry(GogLibrary.File file, File destDir) {
            this.file = file;
            this.destDir = destDir;
            this.target = GogDownloader.target(file, destDir);
        }
    }

    /** Told, on the main thread, that something changed; re-read whatever you show. */
    public interface Listener { void onQueueChanged(); }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Every file asked for since the process started, by target path. */
    private final Map<String, Entry> entries = new HashMap<>();
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private boolean running;
    private Context appCtx;
    /** Main thread only. */
    private Listener listener;

    /** The state of {@code file}, or null if it has not been asked for since the app started. */
    public synchronized Entry entryFor(GogLibrary.File file, File destDir) {
        return entries.get(GogDownloader.target(file, destDir).getAbsolutePath());
    }

    /**
     * Queue {@code file} for download into {@code destDir}. A file already queued or downloading
     * is left alone: the guard against two transfers into one part file. Call from the UI thread
     * while the app is on screen: the keep-alive service may only be started from the foreground.
     */
    public synchronized void enqueue(Context ctx, GogLibrary.File file, File destDir) {
        String key = GogDownloader.target(file, destDir).getAbsolutePath();
        Entry known = entries.get(key);
        if (known != null && (known.phase == Phase.QUEUED || known.phase == Phase.RUNNING)) return;

        Entry e = new Entry(file, destDir);
        entries.put(key, e);
        pending.add(e);
        if (!running) {
            running = true;
            appCtx = ctx.getApplicationContext();
            holdKeepAlive(true);
            new Thread(this::drain, "zd-gog-download").start();
        }
        changed();
    }

    /** Main thread. One listener at a time: the GOG screen that is currently shown. */
    public void setListener(Listener l) { listener = l; }

    /** Main thread. Only clears {@code l}'s own registration, never a newer screen's. */
    public void clearListener(Listener l) { if (listener == l) listener = null; }

    private void drain() {
        while (true) {
            Entry e;
            synchronized (this) {
                e = pending.poll();
                if (e == null) {
                    running = false;
                    holdKeepAlive(false);
                    return;
                }
                e.phase = Phase.RUNNING;
            }
            changed();
            try {
                new GogDownloader().download(e.file, e.destDir, new GogDownloader.Listener() {
                    @Override public void onProgress(long done, long total, int percent) {
                        e.percent = percent;
                        changed();
                    }
                    // "resuming at …", "server ignored the resume request …": worth having in the
                    // log when a download misbehaves, too technical for the card.
                    @Override public void onLog(String message) { Log.i(TAG, message); }
                });
                e.phase = Phase.DONE;
            } catch (Throwable t) {
                Log.e(TAG, "download failed: " + e.target.getName(), t);
                e.failure = t;
                e.phase = Phase.FAILED;
            }
            changed();
        }
    }

    /**
     * The keep-alive notification. Without it Android destroys a backgrounded app's connections
     * five seconds after it leaves the screen ("Destroyed live tcp sockets for uids=…" in the
     * device log, then the read dies with "Software caused connection abort"). Always called with
     * the queue's lock held, so the stop that ends one batch can never overtake the start of the
     * next.
     */
    private void holdKeepAlive(boolean on) {
        try {
            if (on) DownloadKeepAliveService.start(appCtx, appCtx.getString(R.string.gog_keepalive));
            else DownloadKeepAliveService.stop(appCtx);
        } catch (RuntimeException ex) {
            // Refused by the system. The download still works for as long as the app stays on screen.
            Log.w(TAG, "keep-alive " + (on ? "start" : "stop") + " refused: " + ex);
        }
    }

    private void changed() {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onQueueChanged();
        });
    }
}
