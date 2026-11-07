package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lets {@code zombie.core.Core} load from the game's own {@code projectzomboid.jar} instead of the
 * unpacked copy next to it.
 *
 * <p>On PC, Build 42.12+ runs from that jar. We unpack it at install and run from the folder,
 * because our fixes replace individual class files on disk. A mod that asks where the game was
 * loaded from therefore gets a directory: Project Viewpoint hashes the file {@code Core} came from
 * to confirm the game build, fails with "Is a directory" and switches itself off (2026-09-30).
 *
 * <p>The jar is still in the game folder, untouched. {@link GameInstance#getJvmArgsAsList()} puts it
 * at the end of the class path, and this class hides the one unpacked file, so the JVM finds
 * {@code Core} in the jar and every other class in the folder as before. Nothing is faked: the mod
 * sees the real jar of the real build. {@code Core} is not among the classes we patch on disk, and
 * agents that retransform it at run time do not change where it was loaded from.
 *
 * <p>The unpacked file is renamed, not deleted, and only when it is byte-for-byte the jar's entry.
 * If the jar ever goes missing the file is put back.
 */
public final class CoreFromGameJar {
    private static final String LOG_TAG = "CoreFromGameJar";
    private static final String CLASS_REL_PATH = "zombie/core/Core.class";
    private static final String HIDDEN_SUFFIX = ".zomdroid-from-jar";

    private CoreFromGameJar() {}

    public static void apply(GameInstance gameInstance) {
        File gameDir = new File(gameInstance.getGamePath());
        File jar = new File(gameDir, "projectzomboid.jar");
        File loose = new File(gameDir, CLASS_REL_PATH);
        File hidden = new File(gameDir, CLASS_REL_PATH + HIDDEN_SUFFIX);

        // No jar to load from (Build 41, older 42, or a jar removed by hand): undo. Same for a
        // signed jar (42.22+): the JVM refuses a package whose classes come from a signed jar and
        // an unsigned folder at once - "signer information does not match" - so Core from the
        // jar next to the unpacked rest of zombie.core kills the game before its first log line.
        boolean signed = jar.isFile() && isSigned(jar);
        if (!jar.isFile() || signed) {
            if (!loose.isFile() && hidden.isFile()) {
                if (hidden.renameTo(loose)) {
                    if (signed) Log.i(LOG_TAG, "Signed game jar: zombie.core.Core loads from the game folder again");
                } else {
                    Log.w(LOG_TAG, "Could not restore " + loose);
                }
            }
            return;
        }
        if (!loose.isFile()) return; // already hidden, or never unpacked

        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(CLASS_REL_PATH);
            if (entry == null) return;
            if (entry.getSize() != loose.length() || entry.getCrc() != crc32(loose)) {
                Log.i(LOG_TAG, "Unpacked Core.class differs from the jar's; left in place");
                return;
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Could not read " + jar + "; Core.class left in place", e);
            return;
        }

        //noinspection ResultOfMethodCallIgnored
        hidden.delete();
        if (loose.renameTo(hidden)) {
            Log.i(LOG_TAG, "zombie.core.Core now loads from projectzomboid.jar");
        } else {
            Log.w(LOG_TAG, "Could not hide " + loose);
        }
    }

    /**
     * Whether a jar carries a signature (META-INF/*.SF). Classes of one package must then all
     * come from that jar or none of them may: run from the unpacked folder and never let a single
     * class fall through to the jar.
     */
    public static boolean isSigned(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("META-INF/") && name.indexOf('/', 9) < 0
                        && name.toUpperCase(java.util.Locale.ROOT).endsWith(".SF")) return true;
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Could not read " + jar, e);
        }
        return false;
    }

    private static long crc32(File file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) crc.update(buffer, 0, read);
        }
        return crc.getValue();
    }
}
