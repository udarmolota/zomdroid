package com.zomdroid.optimization;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs PZ Optimization's loose class release into one game instance while preserving the
 * instance's original files outside {@code game/}. The upstream archive is self-describing through
 * {@code pzopt-files.txt}; requiring that list to match the archive also prevents wrapper folders
 * and accidental extraction of unrelated files.
 *
 * <p>Based on the installer written by constantinrarinca3-sketch for his Zomdroid fork
 * (MIT, branch pzopt-installer-apk). Added here: the release can also sit in the pzopt-classes
 * folder of a Steam Workshop download.
 */
public final class PzOptInstaller {
    private static final String RELEASE_MANIFEST = "pzopt-files.txt";
    private static final String BUILD_MARKER = "pzopt/BuildInfo.class";
    private static final String BUILD_INFO = "pzopt/build-info.properties";
    private static final String BACKUP_MANIFEST = "manifest.tsv";
    private static final String BACKUP_HEADER = "ZOMDROID_PZOPT_BACKUP_V1";
    private static final String ORIGINALS_DIR = "original";
    private static final int MAX_FILES = 2048;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;

    private PzOptInstaller() {
    }

    public static Result install(Path archive, Path gameDir, Path stateDir) throws IOException {
        requireGameDirectory(gameDir);
        Path backupManifest = stateDir.resolve(BACKUP_MANIFEST);
        boolean managedInstall = Files.isRegularFile(backupManifest);
        if (Files.exists(stateDir) && !managedInstall) {
            throw new IOException("PZ Optimization backup state is incomplete; remove the test "
                    + "instance and create a clean one");
        }
        if (!managedInstall && Files.exists(safeResolve(gameDir, BUILD_MARKER))) {
            throw new IOException("PZ Optimization is already installed outside the launcher; "
                    + "use a clean instance so vanilla files are not backed up as originals");
        }

        Path stateParent = stateDir.toAbsolutePath().normalize().getParent();
        if (stateParent == null) throw new IOException("Invalid backup directory");
        Files.createDirectories(stateParent);
        Path staging = Files.createTempDirectory(stateParent, ".pzopt-stage-");
        try {
            Release release = extractAndValidate(archive, staging);
            validateTargetRevision(gameDir, release);
            LinkedHashMap<String, Boolean> tracked = managedInstall
                    ? readBackupManifest(backupManifest)
                    : new LinkedHashMap<>();
            int originalsBackedUp = prepareBackups(gameDir, stateDir, tracked, release.files,
                    managedInstall);

            // Persist the complete restore map before changing a game file. If Android kills the
            // service during extraction, Restore can still recover the vanilla instance.
            writeBackupManifest(stateDir, tracked);
            try {
                restorePathsMissingFromRelease(gameDir, stateDir, tracked, release.files);
                for (String relative : release.files) {
                    copyReplacing(safeResolve(staging, relative), safeResolve(gameDir, relative));
                }
            } catch (IOException installFailure) {
                try {
                    restoreTrackedFiles(gameDir, stateDir, tracked);
                } catch (IOException rollbackFailure) {
                    installFailure.addSuppressed(rollbackFailure);
                }
                throw installFailure;
            }
            return new Result(release.files.size(), originalsBackedUp);
        } finally {
            deleteTree(staging);
        }
    }

    public static Result restore(Path gameDir, Path stateDir) throws IOException {
        requireGameDirectory(gameDir);
        Path manifest = stateDir.resolve(BACKUP_MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("No launcher-managed PZ Optimization backup exists for this instance");
        }
        LinkedHashMap<String, Boolean> tracked = readBackupManifest(manifest);

        // Validate the whole backup before changing the first game file.
        for (Map.Entry<String, Boolean> entry : tracked.entrySet()) {
            if (entry.getValue()
                    && !Files.isRegularFile(safeResolve(stateDir.resolve(ORIGINALS_DIR), entry.getKey()))) {
                throw new IOException("Missing vanilla backup: " + entry.getKey());
            }
        }

        restoreTrackedFiles(gameDir, stateDir, tracked);
        deleteTree(stateDir);
        // The mod's own ahead-of-time class cache, written while it runs: never in the release, so
        // never tracked, and useless without the mod. Its folder goes too once it is empty.
        Path modDir = gameDir.resolve("pzopt");
        deleteTree(modDir.resolve("aot"));
        if (Files.isDirectory(modDir)) deleteEmptyParents(modDir, gameDir);
        return new Result(tracked.size(), countOriginals(tracked));
    }

    public static boolean hasManagedInstall(Path stateDir) {
        return Files.isRegularFile(stateDir.resolve(BACKUP_MANIFEST));
    }

    private static Release extractAndValidate(Path archive, Path staging) throws IOException {
        if (!Files.isRegularFile(archive)) throw new IOException("PZ Optimization ZIP is missing");

        LinkedHashSet<String> archiveFiles = new LinkedHashSet<>();
        long totalBytes = 0L;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String prefix = releaseRoot(zip);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                String relative = validateRelativePath(entry.getName().substring(prefix.length()));
                if (!archiveFiles.add(relative)) {
                    throw new IOException("Duplicate ZIP entry: " + relative);
                }
                if (archiveFiles.size() > MAX_FILES) {
                    throw new IOException("PZ Optimization ZIP contains too many files");
                }
                if (entry.getSize() > MAX_FILE_BYTES) {
                    throw new IOException("PZ Optimization file is too large: " + relative);
                }

                Path destination = safeResolve(staging, relative);
                Files.createDirectories(destination.getParent());
                long copied;
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(destination,
                             StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    copied = copyLimited(in, out, MAX_FILE_BYTES, relative);
                }
                totalBytes += copied;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new IOException("PZ Optimization ZIP expands beyond 256 MiB");
                }
            }
        }

        if (!archiveFiles.contains(RELEASE_MANIFEST) || !archiveFiles.contains(BUILD_MARKER)
                || !archiveFiles.contains(BUILD_INFO)) {
            throw new IOException("This is not a complete PZ Optimization classes release");
        }

        LinkedHashSet<String> declaredFiles = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(
                safeResolve(staging, RELEASE_MANIFEST), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String relative = validateRelativePath(line);
                if (!declaredFiles.add(relative)) {
                    throw new IOException("Duplicate path in pzopt-files.txt: " + relative);
                }
            }
        }
        if (!declaredFiles.equals(archiveFiles)) {
            throw new IOException("pzopt-files.txt does not match the ZIP contents");
        }
        Properties buildInfo = new Properties();
        try (InputStream in = Files.newInputStream(safeResolve(staging, BUILD_INFO))) {
            buildInfo.load(in);
        }
        String expectedJarHash = buildInfo.getProperty("jar.sha256", "").trim();
        if (!expectedJarHash.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("PZ Optimization build metadata has no valid game hash");
        }
        String revision = buildInfo.getProperty("revision", "unknown").trim();
        return new Release(Collections.unmodifiableSet(archiveFiles),
                expectedJarHash.toLowerCase(java.util.Locale.ROOT), revision);
    }

    /**
     * Where the release sits in the zip: at the root (a GitHub release zip), or in the pzopt-classes folder of the
     * Steam Workshop item (mods/PZ_Optimization/42/pzopt-classes/, the release unpacked, its manifest included), as
     * Zomdroid's Steam downloader saves it. Everything outside that folder (the PC installers, posters) is ignored.
     */
    private static String releaseRoot(ZipFile zip) throws IOException {
        if (zip.getEntry(RELEASE_MANIFEST) != null) return "";
        String found = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.endsWith("pzopt-classes/" + RELEASE_MANIFEST)) {
                if (found != null) throw new IOException("The ZIP holds more than one PZ Optimization release");
                found = name.substring(0, name.length() - RELEASE_MANIFEST.length());
            }
        }
        return found != null ? found : "";
    }

    private static void validateTargetRevision(Path gameDir, Release release) throws IOException {
        Path gameJar = safeResolve(gameDir, "projectzomboid.jar");
        if (!Files.isRegularFile(gameJar)) {
            throw new IOException("projectzomboid.jar is missing; cannot verify the PZ Optimization target");
        }
        String actualHash = sha256(gameJar);
        if (!release.expectedJarHash.equals(actualHash)) {
            throw new IOException("PZ Optimization targets a different Project Zomboid revision"
                    + " (release " + release.revision + ")");
        }
    }

    private static int prepareBackups(Path gameDir, Path stateDir,
                                      LinkedHashMap<String, Boolean> tracked,
                                      Set<String> releaseFiles,
                                      boolean managedInstall) throws IOException {
        Path destinationState = stateDir;
        Path temporaryState = null;
        if (!managedInstall) {
            Path parent = stateDir.toAbsolutePath().normalize().getParent();
            temporaryState = Files.createTempDirectory(parent, ".pzopt-backup-");
            destinationState = temporaryState;
        }

        int backedUp = 0;
        try {
            Path originals = destinationState.resolve(ORIGINALS_DIR);
            for (String relative : releaseFiles) {
                if (tracked.containsKey(relative)) continue;
                Path gameFile = safeResolve(gameDir, relative);
                boolean existed = Files.isRegularFile(gameFile);
                if (Files.exists(gameFile) && !existed) {
                    throw new IOException("Cannot replace non-file game path: " + relative);
                }
                tracked.put(relative, existed);
                if (existed) {
                    copyReplacing(gameFile, safeResolve(originals, relative));
                    backedUp++;
                }
            }
            writeBackupManifest(destinationState, tracked);
            if (temporaryState != null) moveDirectory(temporaryState, stateDir);
            return backedUp;
        } finally {
            if (temporaryState != null && Files.exists(temporaryState)) deleteTree(temporaryState);
        }
    }

    private static void restorePathsMissingFromRelease(Path gameDir, Path stateDir,
                                                       Map<String, Boolean> tracked,
                                                       Set<String> releaseFiles) throws IOException {
        List<String> paths = new ArrayList<>(tracked.keySet());
        Collections.reverse(paths);
        for (String relative : paths) {
            if (!releaseFiles.contains(relative)) restoreOne(gameDir, stateDir, relative,
                    tracked.get(relative));
        }
    }

    private static void restoreTrackedFiles(Path gameDir, Path stateDir,
                                            Map<String, Boolean> tracked) throws IOException {
        List<String> paths = new ArrayList<>(tracked.keySet());
        Collections.reverse(paths);
        IOException firstFailure = null;
        for (String relative : paths) {
            try {
                restoreOne(gameDir, stateDir, relative, tracked.get(relative));
            } catch (IOException e) {
                if (firstFailure == null) firstFailure = e;
                else firstFailure.addSuppressed(e);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private static void restoreOne(Path gameDir, Path stateDir, String relative,
                                   boolean hadOriginal) throws IOException {
        Path gameFile = safeResolve(gameDir, relative);
        if (hadOriginal) {
            copyReplacing(safeResolve(stateDir.resolve(ORIGINALS_DIR), relative), gameFile);
        } else {
            Files.deleteIfExists(gameFile);
            deleteEmptyParents(gameFile.getParent(), gameDir);
        }
    }

    private static LinkedHashMap<String, Boolean> readBackupManifest(Path manifest)
            throws IOException {
        LinkedHashMap<String, Boolean> tracked = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            if (!BACKUP_HEADER.equals(reader.readLine())) {
                throw new IOException("Unsupported PZ Optimization backup format");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 3 || line.charAt(1) != '\t') {
                    throw new IOException("Invalid PZ Optimization backup manifest");
                }
                boolean original;
                if (line.charAt(0) == 'O') original = true;
                else if (line.charAt(0) == 'A') original = false;
                else throw new IOException("Invalid PZ Optimization backup entry");
                String relative = validateRelativePath(line.substring(2));
                if (tracked.put(relative, original) != null) {
                    throw new IOException("Duplicate PZ Optimization backup entry: " + relative);
                }
            }
        }
        return tracked;
    }

    private static void writeBackupManifest(Path stateDir, Map<String, Boolean> tracked)
            throws IOException {
        Files.createDirectories(stateDir);
        Path temporary = stateDir.resolve(BACKUP_MANIFEST + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writer.write(BACKUP_HEADER);
            writer.newLine();
            for (Map.Entry<String, Boolean> entry : tracked.entrySet()) {
                writer.write(entry.getValue() ? "O\t" : "A\t");
                writer.write(entry.getKey());
                writer.newLine();
            }
        }
        moveReplacing(temporary, stateDir.resolve(BACKUP_MANIFEST));
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path destination = normalizedRoot.resolve(validateRelativePath(relative)).normalize();
        if (!destination.startsWith(normalizedRoot)) {
            throw new IOException("Path leaves the target directory: " + relative);
        }
        Path cursor = destination;
        while (cursor != null && !cursor.equals(normalizedRoot)) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Symbolic links are not allowed in install paths: " + relative);
            }
            cursor = cursor.getParent();
        }
        return destination;
    }

    private static String validateRelativePath(String value) throws IOException {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0
                || value.indexOf('\\') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\t') >= 0
                || value.startsWith("/")) {
            throw new IOException("Unsafe ZIP path: " + value);
        }
        Path relative = Path.of(value).normalize();
        String normalized = relative.toString().replace(File.separatorChar, '/');
        if (relative.isAbsolute() || relative.startsWith("..") || !normalized.equals(value)) {
            throw new IOException("Unsafe ZIP path: " + value);
        }
        return normalized;
    }

    private static long copyLimited(InputStream in, OutputStream out, long limit, String name)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("PZ Optimization file is too large: " + name);
            out.write(buffer, 0, count);
        }
        return total;
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[128 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest()) value.append(String.format("%02x", b));
        return value.toString();
    }

    private static void copyReplacing(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".pzopt-copy-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveReplacing(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    private static void requireGameDirectory(Path gameDir) throws IOException {
        if (!Files.isDirectory(gameDir)) throw new IOException("Game directory does not exist");
    }

    private static int countOriginals(Map<String, Boolean> tracked) {
        int count = 0;
        for (boolean original : tracked.values()) if (original) count++;
        return count;
    }

    private static void deleteEmptyParents(Path start, Path stop) throws IOException {
        Path normalizedStop = stop.toAbsolutePath().normalize();
        Path current = start;
        while (current != null && current.toAbsolutePath().normalize().startsWith(normalizedStop)
                && !current.toAbsolutePath().normalize().equals(normalizedStop)) {
            try {
                Files.delete(current);
            } catch (java.nio.file.DirectoryNotEmptyException e) {
                break;
            }
            current = current.getParent();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] ordered = paths.sorted(Collections.reverseOrder()).toArray(Path[]::new);
            IOException first = null;
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    if (first == null) first = e;
                    else first.addSuppressed(e);
                }
            }
            if (first != null) throw first;
        }
    }

    private static final class Release {
        final Set<String> files;
        final String expectedJarHash;
        final String revision;

        Release(Set<String> files, String expectedJarHash, String revision) {
            this.files = files;
            this.expectedJarHash = expectedJarHash;
            this.revision = revision;
        }
    }

    public static final class Result {
        public final int fileCount;
        public final int originalFileCount;

        Result(int fileCount, int originalFileCount) {
            this.fileCount = fileCount;
            this.originalFileCount = originalFileCount;
        }
    }
}
