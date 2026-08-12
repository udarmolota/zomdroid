package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Restores, at every launch, the two field names the stale Android RakNet still looks up.
 *
 * Build 42.15 renamed two fields of {@code zombie.core.znet.ZNetStatistics}
 * ({@code BPSLimitByCongestionControl} and {@code BPSLimitByOutgoingBandwidthLimit} became
 * {@code bpsLimit...}) and rebuilt the <i>Linux</i> libRakNet64.so to match. The Android arm64
 * build was never rebuilt — it is byte-identical from 42.12.1 through 42.20 — so its JNI lookup
 * still asks for the old names, fails, and kills MainThread with a NoSuchFieldError on the first
 * network-statistics tick after joining a server. Only servers with a non-zero
 * {@code MultiplayerStatisticsPeriod} ever take that path, which is why most servers look fine
 * and a few kill every Android client. Confirmed fixed live 2026-08-11.
 *
 * {@code InstallerService} swaps in a pre-patched class at instance creation, but that never
 * reaches instances created by an older launcher. This applier covers them: it re-adds the two
 * old names as plain {@code public long} fields by constant-pool surgery on the class file
 * itself — append two UTF8 entries and two field_info records, bump both counts, touch nothing
 * else. The native writes into the restored fields, nothing reads them, and every other statistic
 * keeps working because no other field name changed.
 *
 * Idempotent by content: a class that already carries the old name (42.12 and earlier, the
 * installer's asset, or a previous run of this applier) is left alone.
 */
public final class ZNetStatisticsPatchApplier {
    private static final String LOG_TAG = ZNetStatisticsPatchApplier.class.getSimpleName();

    private static final String CLASS_REL_PATH = "zombie/core/znet/ZNetStatistics.class";
    private static final String[] RESTORED_FIELDS = {
            "BPSLimitByCongestionControl",
            "BPSLimitByOutgoingBandwidthLimit"
    };
    private static final int ACC_PUBLIC = 0x0001;

    private ZNetStatisticsPatchApplier() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        File target = new File(gameInstance.getGamePath(), CLASS_REL_PATH);
        if (!target.exists()) return;

        try {
            byte[] original = Files.readAllBytes(target.toPath());
            if (containsAscii(original, RESTORED_FIELDS[1])) {
                return; // pre-42.15 class, the installer's swapped asset, or already patched here
            }

            byte[] patched = addLongFields(original, RESTORED_FIELDS);

            // Keep the same backup name the installer-time swap uses, so either path finding the
            // backup present means the work is done and the original is recoverable.
            File backup = new File(target.getAbsolutePath() + ".disabled");
            if (!backup.exists()) {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), patched);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            Log.i(LOG_TAG, "Restored legacy RakNet statistics fields in ZNetStatistics ("
                    + original.length + " -> " + patched.length + " bytes)");
        } catch (Exception e) {
            // A partially applied patch is worse than the original crash staying diagnosable.
            Log.e(LOG_TAG, "Failed to patch ZNetStatistics, leaving the class untouched", e);
        }
    }

    // ---- class-file surgery ------------------------------------------------------------------

    /**
     * Returns a copy of the class file with the given {@code public long} fields appended.
     * Pure constant-pool arithmetic; mirrors the offline script that produced the installer asset.
     */
    static byte[] addLongFields(byte[] classFile, String[] fieldNames) throws IOException {
        if (classFile.length < 10 || readU4(classFile, 0) != 0xCAFEBABEL) {
            throw new IOException("not a class file");
        }

        int cpCount = readU2(classFile, 8);
        int off = 10;
        int descriptorIndex = -1; // existing CONSTANT_Utf8 "J", if any

        for (int i = 1; i < cpCount; ) {
            int tag = classFile[off] & 0xFF;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    int len = readU2(classFile, off + 1);
                    if (len == 1 && classFile[off + 3] == 'J') descriptorIndex = i;
                    off += 3 + len;
                    break;
                }
                case 7: case 8: case 16: case 19: case 20: off += 3; break;
                case 15: off += 4; break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: off += 5; break;
                case 5: case 6: off += 9; i++; break; // long/double take two slots
                default: throw new IOException("unknown constant pool tag " + tag + " at " + off);
            }
            i++;
        }
        int poolEnd = off;

        // access_flags, this_class, super_class, interfaces_count, interfaces[]
        int interfacesCount = readU2(classFile, poolEnd + 6);
        int fieldsCountOff = poolEnd + 8 + interfacesCount * 2;
        int fieldsCount = readU2(classFile, fieldsCountOff);

        java.io.ByteArrayOutputStream addedPool = new java.io.ByteArrayOutputStream();
        int nextIndex = cpCount;
        int[] nameIndexes = new int[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            writeUtf8Entry(addedPool, fieldNames[i]);
            nameIndexes[i] = nextIndex++;
        }
        if (descriptorIndex < 0) {
            writeUtf8Entry(addedPool, "J");
            descriptorIndex = nextIndex++;
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(classFile.length + 128);
        out.write(classFile, 0, 8);
        writeU2(out, nextIndex);                         // new constant_pool_count
        out.write(classFile, 10, poolEnd - 10);
        addedPool.writeTo(out);
        out.write(classFile, poolEnd, fieldsCountOff - poolEnd);
        writeU2(out, fieldsCount + fieldNames.length);   // new fields_count
        for (int nameIndex : nameIndexes) {              // our fields go first
            writeU2(out, ACC_PUBLIC);
            writeU2(out, nameIndex);
            writeU2(out, descriptorIndex);
            writeU2(out, 0);                             // attributes_count
        }
        out.write(classFile, fieldsCountOff + 2, classFile.length - fieldsCountOff - 2);
        return out.toByteArray();
    }

    private static boolean containsAscii(byte[] haystack, String text) {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static int readU2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long readU4(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeU2(java.io.ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeUtf8Entry(java.io.ByteArrayOutputStream out, String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        out.write(1); // CONSTANT_Utf8
        writeU2(out, raw.length);
        out.write(raw, 0, raw.length);
    }
}
