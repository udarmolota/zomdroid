package com.zomdroid.patch;

import java.nio.charset.StandardCharsets;

/**
 * Disables Project Zomboid's Bink video entry point on Android.
 *
 * Build 42.20 only ships an x86_64 libbink64.so. Loading it directly from the ARM64 HotSpot VM
 * fails, and UI panels call LuaManager.GlobalObject.getVideo() every frame, producing a complete
 * NoClassDefFoundError stack trace each time. Returning null is the behavior those callers already
 * handle as "video unavailable", without initializing VideoTexture or touching libbink64.so.
 */
public final class BinkVideoPatcher {
    private static final String TARGET_METHOD = "getVideo";
    private static final String TARGET_DESCRIPTOR =
            "(Ljava/lang/String;II)Lzombie/core/textures/VideoTexture;";

    public static final class Result {
        public int patchedMethods;
        public boolean alreadyPatched;
        public String error;
    }

    private static final ThreadLocal<Result> LAST = ThreadLocal.withInitial(Result::new);

    private BinkVideoPatcher() {}

    public static Result lastResult() {
        return LAST.get();
    }

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

        p += 6;
        p += 2 + u2(b, p) * 2;
        int fieldsCount = u2(b, p);
        p += 2;
        for (int i = 0; i < fieldsCount; i++) p = skipMember(b, p);

        int methodsCount = u2(b, p);
        p += 2;
        for (int i = 0; i < methodsCount; i++) {
            String name = utf8AtIndex(b, cpOffset, u2(b, p + 2));
            String descriptor = utf8AtIndex(b, cpOffset, u2(b, p + 4));
            int attributesCount = u2(b, p + 6);
            int q = p + 8;
            for (int a = 0; a < attributesCount; a++) {
                String attributeName = utf8AtIndex(b, cpOffset, u2(b, q));
                int attributeLength = checkedU4(b, q + 2);
                if (TARGET_METHOD.equals(name)
                        && TARGET_DESCRIPTOR.equals(descriptor)
                        && "Code".equals(attributeName)) {
                    if (isNullReturnBody(b, q)) {
                        result.alreadyPatched = true;
                        return null;
                    }
                    result.patchedMethods = 1;
                    return replaceCodeWithNullReturn(b, q, attributeLength);
                }
                q += 6 + attributeLength;
            }
            p = q;
        }
        return null;
    }

    private static boolean isNullReturnBody(byte[] b, int codeAttribute) {
        int codeLength = checkedU4(b, codeAttribute + 10);
        int code = codeAttribute + 14;
        return codeLength == 2 && u1(b, code) == 0x01 && u1(b, code + 1) == 0xB0;
    }

    private static byte[] replaceCodeWithNullReturn(byte[] b, int oldAttribute,
                                                     int oldAttributeLength) {
        final int newAttributeLength = 14;
        final int newTotalLength = 6 + newAttributeLength;
        int oldTotalLength = 6 + oldAttributeLength;
        byte[] out = new byte[b.length - oldTotalLength + newTotalLength];

        System.arraycopy(b, 0, out, 0, oldAttribute);
        System.arraycopy(b, oldAttribute + oldTotalLength, out,
                oldAttribute + newTotalLength, b.length - oldAttribute - oldTotalLength);

        putU2(out, oldAttribute, u2(b, oldAttribute));
        putU4(out, oldAttribute + 2, newAttributeLength);
        putU2(out, oldAttribute + 6, 1);
        putU2(out, oldAttribute + 8, 3);
        putU4(out, oldAttribute + 10, 2);
        out[oldAttribute + 14] = 0x01;        // aconst_null
        out[oldAttribute + 15] = (byte) 0xB0; // areturn
        putU2(out, oldAttribute + 16, 0);
        putU2(out, oldAttribute + 18, 0);
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

    public static void main(String[] args) throws Exception {
        byte[] input = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
        byte[] output = patch(input);
        Result result = lastResult();
        System.out.println("patchedMethods=" + result.patchedMethods
                + " alreadyPatched=" + result.alreadyPatched + " error=" + result.error);
        if (output != null && args.length > 1) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[1]), output);
        }
    }
}
