package com.zomdroid.gog;

import android.util.Log;

import com.zomdroid.steam.Cancellable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Pulls one GOG installer onto the phone. Ported from RimDroid (MIT).
 *
 * <p>These are single multi-gigabyte files over a mobile connection, so the transfer is built
 * around being interrupted: bytes land in a {@code .part} file, a restart continues it with an HTTP
 * Range request, and the real name only appears once the file is complete. Nothing half-downloaded
 * can reach {@link GogInstallerExtractor} that way; an extractor fed a truncated installer fails in
 * confusing places, and the user would have no idea the download was the problem.
 *
 * <p>The CDN link is resolved fresh on every attempt rather than stored: GOG signs it, binds it to
 * the account and expires it, so a link kept from an hour ago is useless and a link written to a
 * log is a credential leak.
 *
 * <p>Blocks on network I/O: call from a worker thread.
 */
public final class GogDownloader implements Cancellable {
    private static final String TAG = "Zomdroid/GOG";

    /** Report no more often than this; a UI update per chunk is pure jank. */
    private static final long PROGRESS_INTERVAL_MS = 500;

    private static final int BUFFER = 1 << 16;

    public interface Listener {
        /** @param percent -1 when the server would not say how big the file is. */
        void onProgress(long doneBytes, long totalBytes, int percent);
        void onLog(String message);
    }

    private volatile boolean cancelled;

    @Override public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() { return cancelled; }

    /**
     * The finished installer for {@code file} if an earlier download completed it, else null. Lets
     * a caller skip what a real transfer needs (a worker thread, the keep-alive notification) when
     * there is nothing left to fetch.
     */
    public static File completed(GogLibrary.File file, File destDir) {
        File finished = target(file, destDir);
        return finished.isFile() && finished.length() > 0 ? finished : null;
    }

    /** Where {@code file} lands in {@code destDir} once complete; also its identity in the queue. */
    public static File target(GogLibrary.File file, File destDir) {
        return new File(destDir, safeName(file));
    }

    /**
     * Download {@code file} into {@code destDir}, resuming if a previous attempt left a part file.
     *
     * @return the finished file
     * @throws IOException on a network or disk failure, or if cancelled
     */
    public File download(GogLibrary.File file, File destDir, Listener listener) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs())
            throw new IOException("cannot create " + destDir);

        File already = completed(file, destDir);
        if (already != null) {
            listener.onLog("already downloaded: " + already.getName()
                    + " (" + mib(already.length()) + ")");
            return already;
        }

        File finished = target(file, destDir);
        File part = new File(destDir, finished.getName() + ".part");

        long have = part.isFile() ? part.length() : 0;
        String url = GogLibrary.resolveDownload(file);   // fresh every attempt; see the class note

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            if (have > 0) {
                conn.setRequestProperty("Range", "bytes=" + have + "-");
                listener.onLog("resuming at " + mib(have));
            }

            int code = conn.getResponseCode();
            // 206 = the server honoured the Range and is sending the rest. 200 = it ignored it and
            // is sending the whole file, so whatever was there is worthless and the part file restarts.
            boolean append = (code == HttpURLConnection.HTTP_PARTIAL);
            if (code != HttpURLConnection.HTTP_OK && !append)
                throw new IOException("download returned HTTP " + code + " for " + file.name);
            if (have > 0 && !append) {
                listener.onLog("server ignored the resume request - starting over");
                have = 0;
            }

            long remaining = conn.getContentLengthLong();          // -1 when not advertised
            long total = remaining < 0 ? -1 : remaining + have;

            long done = have;
            long lastReport = 0;
            try (InputStream in = conn.getInputStream();
                 BufferedOutputStream out = new BufferedOutputStream(
                         new FileOutputStream(part, append), BUFFER)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new IOException("cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now;
                        listener.onProgress(done, total, percent(done, total));
                    }
                }
            }

            if (total > 0 && done != total)
                throw new IOException("short read: got " + mib(done) + " of " + mib(total));

            // Only now does it get the real name; see the class note on truncated installers.
            if (finished.exists() && !finished.delete())
                throw new IOException("cannot replace " + finished);
            if (!part.renameTo(finished))
                throw new IOException("cannot rename " + part.getName());

            listener.onProgress(done, total, 100);
            listener.onLog("downloaded " + finished.getName() + " (" + mib(done) + ")");
            Log.i(TAG, "downloaded " + finished.getName() + " " + done + " bytes");
            return finished;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * A file name safe for the public Download folder. GOG's own names carry characters a FAT-ish
     * volume will not take, and the name is shown to the user in a file manager.
     */
    private static String safeName(GogLibrary.File file) {
        String base = file.name == null || file.name.trim().isEmpty() ? "gog_download" : file.name.trim();
        // The version belongs in the name. Without it a newer build of the same game produces the
        // same filename, the "already downloaded" short-circuit above matches the OLD file, and the
        // user silently installs the previous version believing they fetched the new one.
        if (file.version != null && !file.version.trim().isEmpty())
            base = base + "_" + file.version.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|]+", "_").replaceAll("\\s+", "_");
        if (base.length() > 80) base = base.substring(0, 80);
        // GOG's Linux installers are shell archives; keep the extension so the extractor's sniff
        // and the user's file manager both see what it is.
        return base.toLowerCase().endsWith(".sh") ? base : base + ".sh";
    }

    private static int percent(long done, long total) {
        if (total <= 0) return -1;
        long p = done * 100 / total;
        return (int) Math.max(0, Math.min(100, p));
    }

    private static String mib(long bytes) {
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
