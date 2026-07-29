package com.zomdroid.patch;

import java.nio.charset.StandardCharsets;

/**
 * Gives Build 42.20's missing ARM64 Lighting JNI entry point a Java no-op body.
 *
 * The game declares squareSetLightTransmission(), but the bundled ARM64 libLighting64.so does not
 * export it. Falling back to the x86_64 Lighting library is not safe either: getVisibleRooms(int,
 * long[]) later crashes inside the box64 JNI bridge. Replacing just the absent void method lets all
 * other Lighting calls continue to use the native ARM64 library.
 */
public final class LightingTransmissionPatcher {
    public static final String MISSING_JNI_SYMBOL =
            "Java_zombie_iso_LightingJNI_squareSetLightTransmission";

    private static final String TARGET_METHOD = "squareSetLightTransmission";
    private static final String TARGET_DESCRIPTOR = "(FFFFFFFFFFFFFFFFFFFF)V";
    private static final int ACC_NATIVE = 0x0100;

    public static final class Result {
        public int patchedMethods;
        public boolean alreadyPatched;
        public String error;
    }

    private static final ThreadLocal<Result> LAST = ThreadLocal.withInitial(Result::new);

    private LightingTransmissionPatcher() {}

    public static Result lastResult() {
        return LAST.get();
    }

    /** Returns a patched class, or null if the target native declaration was not found. */
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
        int codeNameIndex = 0;
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
                    int length = u2(b, p + 1);
                    if (length == 4
                            && b[p + 3] == 'C'
                            && b[p + 4] == 'o'
                            && b[p + 5] == 'd'
                            && b[p + 6] == 'e') {
                        codeNameIndex = i;
                    }
                    p += 3 + length;
                    break;
                default:
                    throw new IllegalArgumentException("bad constant-pool tag " + tag);
            }
        }
        if (codeNameIndex == 0) throw new IllegalArgumentException("Code constant not found");

        p += 6; // access_flags, this_class, super_class
        p += 2 + u2(b, p) * 2; // interfaces
        int fieldsCount = u2(b, p);
        p += 2;
        for (int i = 0; i < fieldsCount; i++) p = skipMember(b, p);

        int methodsCount = u2(b, p);
        p += 2;
        for (int i = 0; i < methodsCount; i++) {
            int methodStart = p;
            int access = u2(b, p);
            String name = utf8AtIndex(b, cpOffset, u2(b, p + 2));
            String descriptor = utf8AtIndex(b, cpOffset, u2(b, p + 4));
            int attributesCount = u2(b, p + 6);
            int methodEnd = p + 8;
            for (int a = 0; a < attributesCount; a++) {
                methodEnd += 6 + checkedU4(b, methodEnd + 2);
            }

            if (TARGET_METHOD.equals(name) && TARGET_DESCRIPTOR.equals(descriptor)) {
                if ((access & ACC_NATIVE) == 0) {
                    result.alreadyPatched = true;
                    return null;
                }
                byte[] patched = insertVoidCodeAttribute(
                        b, methodStart, methodEnd, access, attributesCount, codeNameIndex);
                result.patchedMethods = 1;
                return patched;
            }
            p = methodEnd;
        }
        return null;
    }

    private static byte[] insertVoidCodeAttribute(byte[] b, int methodStart, int methodEnd,
                                                   int access, int attributesCount,
                                                   int codeNameIndex) {
        // Code_attribute: header(6), max_stack(2), max_locals(2), code_length(4), return(1),
        // exception_table_length(2), attributes_count(2).
        final int insertedLength = 19;
        byte[] out = new byte[b.length + insertedLength];
        System.arraycopy(b, 0, out, 0, methodEnd);
        System.arraycopy(b, methodEnd, out, methodEnd + insertedLength, b.length - methodEnd);

        putU2(out, methodStart, access & ~ACC_NATIVE);
        putU2(out, methodStart + 6, attributesCount + 1);

        int q = methodEnd;
        putU2(out, q, codeNameIndex);
        putU4(out, q + 2, 13);
        putU2(out, q + 6, 0);  // max_stack
        putU2(out, q + 8, 20); // max_locals: twenty float arguments
        putU4(out, q + 10, 1);
        out[q + 14] = (byte) 0xB1; // return
        putU2(out, q + 15, 0);     // exception_table_length
        putU2(out, q + 17, 0);     // attributes_count
        return out;
    }

    private static int skipMember(byte[] b, int p) {
        int attributesCount = u2(b, p + 6);
        int q = p + 8;
        for (int i = 0; i < attributesCount; i++) q += 6 + checkedU4(b, q + 2);
        return q;
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

    private static void putU2(byte[] b, int p, int value) {
        b[p] = (byte) (value >>> 8);
        b[p + 1] = (byte) value;
    }

    private static void putU4(byte[] b, int p, int value) {
        b[p] = (byte) (value >>> 24);
        b[p + 1] = (byte) (value >>> 16);
        b[p + 2] = (byte) (value >>> 8);
        b[p + 3] = (byte) value;
    }

    /** Desktop verifier: input class, optional patched output class. */
    public static void main(String[] args) throws Exception {
        byte[] input = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
        byte[] output = patch(input);
        Result result = lastResult();
        System.out.println("patchedMethods=" + result.patchedMethods + " error=" + result.error);
        if (output != null && args.length > 1) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[1]), output);
        }
    }
}
