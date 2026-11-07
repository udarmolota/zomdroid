package com.zomdroid;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * What a mod import is doing right now, on disk: the archive, the stage, the archive entry being
 * written and how far it got. A big import (1-5 GB) died with no message at all - an
 * OutOfMemoryError slipped past the Exception catch, or the system killed the process mid-way
 * (report 21102026_0027) - and nothing was left to tell which. Now a failure writes its error here,
 * and a process that dies before it can is caught on the next start: the journal still says
 * "running" from another process, and the launcher says where it stopped. The file also goes into
 * bug reports.
 */
public final class ImportJournal {
    private static final String TAG = "ImportJournal";
    public static final String FILE = "import-journal.properties";
    /** Writes during extraction at most this often; the last entry before a kill is what matters. */
    private static final long WRITE_EVERY_MS = 1000;

    private final File file;
    private final Properties p = new Properties();
    private long lastWrite;

    private ImportJournal(File file) {
        this.file = file;
    }

    public static File file(@NonNull Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    /** Starts a journal for one import, replacing the last one. */
    public static ImportJournal start(@NonNull Context context, String task, @Nullable String archive, long archiveBytes) {
        ImportJournal j = new ImportJournal(file(context));
        j.p.setProperty("task", task);
        j.p.setProperty("archive", archive == null ? "?" : archive);
        j.p.setProperty("archiveBytes", String.valueOf(archiveBytes));
        j.p.setProperty("pid", String.valueOf(android.os.Process.myPid()));
        j.p.setProperty("startedAt", String.valueOf(System.currentTimeMillis()));
        j.p.setProperty("status", "running");
        j.p.setProperty("stage", "start");
        j.save();
        return j;
    }

    public synchronized void stage(String stage) {
        p.setProperty("stage", stage);
        save();
    }

    /** The archive entry being written; throttled, except for big entries, which are the suspects. */
    public synchronized void entry(String name, long size, long bytesDone) {
        p.setProperty("entry", name);
        p.setProperty("entryBytes", String.valueOf(size));
        p.setProperty("bytesDone", String.valueOf(bytesDone));
        long now = System.currentTimeMillis();
        if (now - lastWrite >= WRITE_EVERY_MS || size > 64L * 1024 * 1024) save();
    }

    public synchronized void done() {
        p.setProperty("status", "done");
        save();
    }

    public synchronized void failed(Throwable t) {
        p.setProperty("status", "failed");
        p.setProperty("error", String.valueOf(t));
        save();
    }

    private void save() {
        lastWrite = System.currentTimeMillis();
        p.setProperty("updatedAt", String.valueOf(lastWrite));
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(out, "Zomdroid mod import");
        } catch (IOException e) {
            Log.w(TAG, "cannot write the import journal", e);
        }
    }

    /**
     * An import that a dead process left "running": its last stage and entry, for the launcher to
     * show once. Null when there is none. The journal is marked so it is shown only once.
     */
    @Nullable
    public static Properties takeInterrupted(@NonNull Context context) {
        File f = file(context);
        if (!f.isFile()) return null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        if (!"running".equals(p.getProperty("status"))) return null;
        if (String.valueOf(android.os.Process.myPid()).equals(p.getProperty("pid"))) return null;   // still ours
        p.setProperty("status", "interrupted");
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "Zomdroid mod import");
        } catch (IOException ignored) {
        }
        return p;
    }
}
