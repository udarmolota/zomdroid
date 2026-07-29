package com.zomdroid.patch;

import java.nio.charset.StandardCharsets;

/**
 * Removes Build 42.20+'s direct System.loadLibrary("fmod"/"fmodstudio") calls from
 * fmod/javafmodJNI.init().
 *
 * Zomdroid has already loaded the Android FMOD libraries on ART and initialized them with an
 * Android Context before the game JVM starts. Loading either library again from HotSpot invokes
 * FMOD's Android JNI_OnLoad against the wrong VM, where org.fmod.FMOD is intentionally absent.
 * The x86_64 fmodintegration64 load is left untouched and continues through the existing box64
 * JNI bridge.
 *
 * The patch is size preserving:
 *
 *   ldc "fmod"; invokestatic System.loadLibrary
 *       becomes
 *   ldc "fmod"; pop; nop; nop
 *
 * Keeping instruction sizes and stack effects unchanged means branch offsets and stack-map frames
 * remain valid. Constant-pool identities are resolved instead of relying on version-specific byte
 * offsets.
 */
public final class FmodLoadPatcher {
    public static final class Result {
        public int patchedCalls;
        public String error;
    }

    private static final ThreadLocal<Result> LAST = ThreadLocal.withInitial(Result::new);

    private FmodLoadPatcher() {}

    public static Result lastResult() {
        return LAST.get();
    }

    /** Returns a patched copy, or null when the class is unknown/already patched. */
    public static byte[] patch(byte[] classFile) {
        Result result = new Result();
        LAST.set(result);
        try {
            return patchInner(classFile.clone(), result);
        } catch (RuntimeException e) {
            result.error = e.getMessage();
            return null;
        }
    }

    private static byte[] patchInner(byte[] b, Result result) {
        if (u4(b, 0) != 0xCAFEBABEL) throw new IllegalArgumentException("not a class file");

        int cpCount = u2(b, 8);
        int[] cpOffset = new int[cpCount];
        int p = 10;
        for (int i = 1; i < cpCount; i++) {
            cpOffset[i] = p;
            int tag = u1(b, p);
            switch (tag) {
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    p += 3;
                    break;
                case 15:
                    p += 4;
                    break;
                case 3:
                case 4:
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    p += 5;
                    break;
                case 5:
                case 6:
                    p += 9;
                    i++;
                    break;
                case 1:
                    p += 3 + u2(b, p + 1);
                    break;
                default:
                    throw new IllegalArgumentException("bad constant-pool tag " + tag);
            }
        }

        boolean[] targetStrings = new boolean[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int off = cpOffset[i];
            if (off == 0 || u1(b, off) != 8) continue;
            String value = utf8AtIndex(b, cpOffset, u2(b, off + 1));
            targetStrings[i] = "fmod".equals(value) || "fmodstudio".equals(value);
        }

        boolean[] systemClasses = new boolean[cpCount];
        boolean[] loadLibraryNameAndTypes = new boolean[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int off = cpOffset[i];
            if (off == 0) continue;
            if (u1(b, off) == 7) {
                systemClasses[i] = "java/lang/System".equals(
                        utf8AtIndex(b, cpOffset, u2(b, off + 1)));
            } else if (u1(b, off) == 12) {
                String name = utf8AtIndex(b, cpOffset, u2(b, off + 1));
                String descriptor = utf8AtIndex(b, cpOffset, u2(b, off + 3));
                loadLibraryNameAndTypes[i] = "loadLibrary".equals(name)
                        && "(Ljava/lang/String;)V".equals(descriptor);
            }
        }

        boolean[] loadLibraryMethods = new boolean[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int off = cpOffset[i];
            if (off == 0 || u1(b, off) != 10) continue;
            loadLibraryMethods[i] = systemClasses[u2(b, off + 1)]
                    && loadLibraryNameAndTypes[u2(b, off + 3)];
        }

        p += 6;
        p += 2 + u2(b, p) * 2;
        int fieldsCount = u2(b, p);
        p += 2;
        for (int i = 0; i < fieldsCount; i++) p = skipMember(b, p);

        int methodsCount = u2(b, p);
        p += 2;
        for (int i = 0; i < methodsCount; i++) {
            String methodName = utf8AtIndex(b, cpOffset, u2(b, p + 2));
            String descriptor = utf8AtIndex(b, cpOffset, u2(b, p + 4));
            int attributesCount = u2(b, p + 6);
            int q = p + 8;
            for (int a = 0; a < attributesCount; a++) {
                String attributeName = utf8AtIndex(b, cpOffset, u2(b, q));
                int attributeLength = checkedU4(b, q + 2);
                if ("init".equals(methodName) && "()V".equals(descriptor)
                        && "Code".equals(attributeName)) {
                    int codeLength = checkedU4(b, q + 10);
                    patchCode(b, q + 14, codeLength, targetStrings, loadLibraryMethods, result);
                }
                q += 6 + attributeLength;
            }
            p = q;
        }

        return result.patchedCalls == 0 ? null : b;
    }

    private static void patchCode(byte[] b, int code, int length, boolean[] targetStrings,
                                  boolean[] loadLibraryMethods, Result result) {
        int pc = 0;
        int previousOpcode = -1;
        int previousConstant = -1;
        while (pc < length) {
            int opcode = u1(b, code + pc);
            int instructionLength = instructionLength(b, code, pc);

            if (opcode == 0xB8
                    && loadLibraryMethods[u2(b, code + pc + 1)]
                    && (previousOpcode == 0x12 || previousOpcode == 0x13)
                    && previousConstant >= 0
                    && targetStrings[previousConstant]) {
                b[code + pc] = 0x57;     // pop
                b[code + pc + 1] = 0x00; // nop
                b[code + pc + 2] = 0x00; // nop
                result.patchedCalls++;
            }

            previousOpcode = opcode;
            if (opcode == 0x12) {
                previousConstant = u1(b, code + pc + 1);
            } else if (opcode == 0x13) {
                previousConstant = u2(b, code + pc + 1);
            } else {
                previousConstant = -1;
            }
            pc += instructionLength;
        }
    }

    private static int skipMember(byte[] b, int p) {
        int attributesCount = u2(b, p + 6);
        int q = p + 8;
        for (int i = 0; i < attributesCount; i++) q += 6 + checkedU4(b, q + 2);
        return q;
    }

    private static int instructionLength(byte[] b, int code, int pc) {
        int opcode = u1(b, code + pc);
        if (opcode == 0xAA) {
            int padding = 3 - (pc & 3);
            int base = code + pc + 1 + padding;
            long low = u4(b, base + 4);
            long high = u4(b, base + 8);
            return 1 + padding + 12 + (int) (high - low + 1) * 4;
        }
        if (opcode == 0xAB) {
            int padding = 3 - (pc & 3);
            int base = code + pc + 1 + padding;
            return 1 + padding + 8 + checkedU4(b, base + 4) * 8;
        }
        if (opcode == 0xC4) return u1(b, code + pc + 1) == 0x84 ? 6 : 4;
        int length = OPCODE_LENGTH[opcode];
        if (length == 0) {
            throw new IllegalArgumentException("unknown opcode 0x"
                    + Integer.toHexString(opcode) + " at " + pc);
        }
        return length;
    }

    private static final int[] OPCODE_LENGTH = new int[256];
    static {
        for (int i = 0x00; i <= 0x0F; i++) OPCODE_LENGTH[i] = 1;
        OPCODE_LENGTH[0x10] = 2;
        OPCODE_LENGTH[0x11] = 3;
        OPCODE_LENGTH[0x12] = 2;
        OPCODE_LENGTH[0x13] = 3;
        OPCODE_LENGTH[0x14] = 3;
        for (int i = 0x15; i <= 0x19; i++) OPCODE_LENGTH[i] = 2;
        for (int i = 0x1A; i <= 0x35; i++) OPCODE_LENGTH[i] = 1;
        for (int i = 0x36; i <= 0x3A; i++) OPCODE_LENGTH[i] = 2;
        for (int i = 0x3B; i <= 0x83; i++) OPCODE_LENGTH[i] = 1;
        OPCODE_LENGTH[0x84] = 3;
        for (int i = 0x85; i <= 0x98; i++) OPCODE_LENGTH[i] = 1;
        for (int i = 0x99; i <= 0xA8; i++) OPCODE_LENGTH[i] = 3;
        OPCODE_LENGTH[0xA9] = 2;
        for (int i = 0xAC; i <= 0xB1; i++) OPCODE_LENGTH[i] = 1;
        for (int i = 0xB2; i <= 0xB8; i++) OPCODE_LENGTH[i] = 3;
        OPCODE_LENGTH[0xB9] = 5;
        OPCODE_LENGTH[0xBA] = 5;
        OPCODE_LENGTH[0xBB] = 3;
        OPCODE_LENGTH[0xBC] = 2;
        OPCODE_LENGTH[0xBD] = 3;
        OPCODE_LENGTH[0xBE] = 1;
        OPCODE_LENGTH[0xBF] = 1;
        OPCODE_LENGTH[0xC0] = 3;
        OPCODE_LENGTH[0xC1] = 3;
        OPCODE_LENGTH[0xC2] = 1;
        OPCODE_LENGTH[0xC3] = 1;
        OPCODE_LENGTH[0xC5] = 4;
        OPCODE_LENGTH[0xC6] = 3;
        OPCODE_LENGTH[0xC7] = 3;
        OPCODE_LENGTH[0xC8] = 5;
        OPCODE_LENGTH[0xC9] = 5;
    }

    private static String utf8AtIndex(byte[] b, int[] cpOffset, int index) {
        int off = cpOffset[index];
        if (off == 0 || u1(b, off) != 1) throw new IllegalArgumentException("expected Utf8");
        int length = u2(b, off + 1);
        return new String(b, off + 3, length, StandardCharsets.UTF_8);
    }

    private static int checkedU4(byte[] b, int p) {
        long value = u4(b, p);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("oversized class item");
        return (int) value;
    }

    private static int u1(byte[] b, int p) {
        return b[p] & 0xFF;
    }

    private static int u2(byte[] b, int p) {
        return (u1(b, p) << 8) | u1(b, p + 1);
    }

    private static long u4(byte[] b, int p) {
        return ((long) u1(b, p) << 24)
                | ((long) u1(b, p + 1) << 16)
                | ((long) u1(b, p + 2) << 8)
                | u1(b, p + 3);
    }

    /** Small desktop verifier: input class, optional patched output class. */
    public static void main(String[] args) throws Exception {
        byte[] input = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
        byte[] output = patch(input);
        Result result = lastResult();
        System.out.println("patchedCalls=" + result.patchedCalls + " error=" + result.error);
        if (output != null && args.length > 1) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[1]), output);
        }
    }
}
