package com.zomdroid.gog;

import com.zomdroid.TaskProgressListener;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Extracts Project Zomboid from GOG's DRM-free Linux installer, the {@code .sh} file GOG offers for
 * download. It is a Makeself self-extracting archive: a shell script with a MojoSetup runtime, and
 * the game data appended as a plain ZIP whose entries live under {@code data/noarch/game/}. A ZIP
 * is located from its end, so the archive is read in place through {@link PrefixedZip}; the script
 * in front of it is never run.
 *
 * <p>Two shapes arrive here:
 * <ul>
 *   <li>a bare {@code .sh}: what the in-app GOG downloader fetches into Downloads/zomdroid and
 *       hands over by path;</li>
 *   <li>a {@code .zip} holding one or more {@code .sh}: what a player picks in the new-instance
 *       screen after downloading on a PC. File managers do not reliably hand a bare {@code .sh} to
 *       apps, and the picker is ZIP-only anyway. The installers are spooled out of the ZIP one at a
 *       time, because a ZIP reader needs random access and a stream cannot give it, and deleted
 *       after.</li>
 * </ul>
 * Only the {@code data/noarch/game/} subtree is taken, with that prefix stripped. The result is the
 * same tree a Steam ZIP gives, so the installer's root drill, layout normalisation and patches run
 * unchanged afterwards.
 *
 * <p>Pure Java, no Android types: unit-tested on the JVM.
 */
public final class GogInstallerExtractor {
    private GogInstallerExtractor() {}

    /** The archive kinds the installer service tells apart; passed along as an intent extra. */
    public static final String KIND_GAME_ZIP = "GAME_ZIP";
    public static final String KIND_INSTALLER = "GOG_INSTALLER";
    public static final String KIND_BUNDLE = "GOG_BUNDLE";

    /** GOG puts the game under this segment; the prefix up to and including it is stripped. */
    private static final String GAME_MARKER = "/game/";
    private static final int HEAD_BYTES = 4096;
    private static final int BUFFER = 1 << 16;

    /** A Makeself installer starts with a shell shebang and names Makeself or MojoSetup in its header. */
    public static boolean isMakeselfInstaller(byte[] head) {
        if (head == null) return false;
        String s = new String(head, StandardCharsets.ISO_8859_1);
        return s.startsWith("#!/bin/sh")
                && (s.contains("Makeself") || s.contains("makeself") || s.contains("mojosetup"));
    }

    public static boolean isMakeselfInstaller(File file) {
        if (file == null || !file.isFile()) return false;
        try (InputStream in = new FileInputStream(file)) {
            return isMakeselfInstaller(readHead(in));
        } catch (IOException e) {
            return false;
        }
    }

    /** The first {@value #HEAD_BYTES} bytes of a stream, fewer if it is shorter. */
    public static byte[] readHead(InputStream in) throws IOException {
        byte[] head = new byte[HEAD_BYTES];
        int n = 0;
        while (n < head.length) {
            int r = in.read(head, n, head.length - n);
            if (r < 0) break;
            n += r;
        }
        return n == head.length ? head : Arrays.copyOf(head, n);
    }

    /**
     * Whether ZIP entry names describe a bundle of installers rather than game files: at least one
     * {@code .sh}, and nothing else but folders.
     */
    public static boolean isInstallerBundle(Iterable<String> entryNames) {
        boolean sh = false;
        for (String name : entryNames) {
            if (name.endsWith("/")) continue;
            if (isShellScript(name)) sh = true;
            else return false;
        }
        return sh;
    }

    /**
     * Extracts the game payload of one installer into {@code gameDir}. Progress is reported as the
     * share of entries done.
     */
    public static void extractInstaller(File installer, File gameDir, TaskProgressListener listener)
            throws IOException {
        if (!gameDir.isDirectory() && !gameDir.mkdirs())
            throw new IOException("Failed to create directory " + gameDir);
        String canonBase = gameDir.getCanonicalPath() + File.separator;
        try (ZipFile zf = PrefixedZip.open(installer)) {
            String prefix = detectGamePrefix(zf);
            if (prefix == null)
                throw new IOException(installer.getName()
                        + ": no game payload found (expected entries under data/noarch/game/)");
            List<ZipArchiveEntry> wanted = new ArrayList<>();
            for (Enumeration<ZipArchiveEntry> en = zf.getEntries(); en.hasMoreElements(); ) {
                ZipArchiveEntry e = en.nextElement();
                if (!e.getName().startsWith(prefix)) continue;
                String rel = e.getName().substring(prefix.length());
                // A symlink entry would come out as a file holding its target's path.
                if (rel.isEmpty() || shouldSkip(rel) || e.isUnixSymlink()) continue;
                wanted.add(e);
            }
            if (wanted.isEmpty()) throw new IOException(installer.getName() + ": the game payload is empty");

            byte[] buf = new byte[BUFFER];
            int done = 0;
            for (ZipArchiveEntry e : wanted) {
                File out = new File(gameDir, e.getName().substring(prefix.length()));
                // The archive names the files; never let one of them climb out of the game folder.
                if (!(out.getCanonicalPath() + (e.isDirectory() ? File.separator : "")).startsWith(canonBase))
                    throw new IOException("Path traversal in installer: " + e.getName());
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs())
                        throw new IOException("Failed to create directory " + out);
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                        throw new IOException("Failed to create directory " + parent);
                    long written = 0;
                    try (InputStream is = zf.getInputStream(e);
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(out), BUFFER)) {
                        int len;
                        while ((len = is.read(buf)) != -1) {
                            os.write(buf, 0, len);
                            written += len;
                        }
                    }
                    // A short file here means a truncated download. Fail where the cause is still
                    // visible rather than at game launch, the same rule as FileUtils applies.
                    if (e.getSize() >= 0 && written != e.getSize()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        throw new IOException("Truncated extraction of " + e.getName() + ": wrote "
                                + written + " of " + e.getSize() + " bytes");
                    }
                }
                done++;
                if (listener != null) listener.onProgressUpdate(null, (int) (done * 100L / wanted.size()), 100);
            }
        }
    }

    /**
     * Extracts every installer found inside a ZIP, in archive order. {@code zipSize} drives the
     * progress of the spooling and may be -1 when unknown; {@code cacheDir} holds each spooled
     * installer until it is done and is left empty.
     */
    public static void extractBundle(InputStream zip, long zipSize, File gameDir, File cacheDir,
                                     TaskProgressListener listener) throws IOException {
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs())
            throw new IOException("Failed to create directory " + cacheDir);
        List<File> spooled = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            ZipArchiveInputStream zis = new ZipArchiveInputStream(new BufferedInputStream(zip, 1 << 20));
            ZipArchiveEntry e;
            byte[] buf = new byte[BUFFER];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory() || !isShellScript(e.getName())) continue;
                File tmp = new File(cacheDir, "gog-installer-" + spooled.size() + ".sh");
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), BUFFER)) {
                    int len;
                    while ((len = zis.read(buf)) != -1) {
                        os.write(buf, 0, len);
                        if (listener != null && zipSize > 0)
                            listener.onProgressUpdate(null, (int) (zis.getBytesRead() * 100 / zipSize), 100);
                    }
                }
                spooled.add(tmp);
                names.add(baseName(e.getName()));
            }
            if (spooled.isEmpty()) throw new IOException("No .sh installer found in the archive");
            for (int i = 0; i < spooled.size(); i++) {
                File sh = spooled.get(i);
                if (!isMakeselfInstaller(sh))
                    throw new IOException(names.get(i) + " is not a GOG Linux installer");
                extractInstaller(sh, gameDir, listener);
                //noinspection ResultOfMethodCallIgnored
                sh.delete();
            }
        } finally {
            for (File f : spooled) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** Copies a stream to {@code target}, reporting progress against {@code size} when it is known. */
    public static void spool(InputStream in, long size, File target, TaskProgressListener listener)
            throws IOException {
        byte[] buf = new byte[BUFFER];
        long done = 0;
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
            int len;
            while ((len = in.read(buf)) != -1) {
                os.write(buf, 0, len);
                done += len;
                if (listener != null && size > 0) listener.onProgressUpdate(null, (int) (done * 100 / size), 100);
            }
        }
    }

    /** The prefix up to and including {@code /game/}, from the first entry that has it; null if none. */
    private static String detectGamePrefix(ZipFile zf) {
        for (Enumeration<ZipArchiveEntry> en = zf.getEntries(); en.hasMoreElements(); ) {
            String n = en.nextElement().getName();
            int idx = n.indexOf(GAME_MARKER);
            if (idx >= 0) return n.substring(0, idx + GAME_MARKER.length());
        }
        return null;
    }

    /** GOG's own bookkeeping inside the game folder: catalogue id, hash database, icon. */
    private static boolean shouldSkip(String rel) {
        return baseName(rel).toLowerCase(Locale.ROOT).startsWith("goggame-");
    }

    private static boolean isShellScript(String path) {
        return baseName(path).toLowerCase(Locale.ROOT).endsWith(".sh");
    }

    private static String baseName(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}
