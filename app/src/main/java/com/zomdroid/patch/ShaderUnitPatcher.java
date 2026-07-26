package com.zomdroid.patch;

/**
 * Universal in-place patcher for Project Zomboid's zombie/core/opengl/ShaderUnit.class.
 *
 * Forces the "combine shader sources" flag to TRUE so PZ concatenates its shader
 * include-units into a single compilation unit per stage. GLES allows only one unit
 * per stage (single main()), so without this flag every hueShift/bodyMask-style shader
 * fails to link and characters/zombies render invisible.
 *
 * Works on EVERY game version without pre-made replacement files, because it finds the
 * field BY NAME in the constant pool of the class it is given and both patch shapes are
 * size-preserving (no offsets shift, no StackMapTable recomputation needed):
 *
 *   Family A (42.8 .. 42.18 era, field "bCombineShaderSources", initialized in clinit):
 *       <clinit>: iconst_0; putstatic #F   -->   iconst_1; putstatic #F      (1 byte)
 *
 *   Family B (42.19+, field renamed "combineShaderSources", no clinit init, read at
 *   use sites):
 *       any method: getstatic #F (B2 xx xx) --> iconst_1; nop; nop (04 00 00) (3 bytes)
 *
 * If neither field name exists (a future rewrite), the class is returned UNTOUCHED and
 * the caller gets patchedSites == 0 — log it and move on, the game will still run.
 *
 * Bytecode is walked instruction-by-instruction (with wide/tableswitch/lookupswitch
 * handling), so opcode-lookalike bytes inside operands can never be mispatched.
 *
 * Two integration modes:
 *   1) Installer, on-disk:      byte[] out = ShaderUnitPatcher.patch(bytes);
 *   2) zomdroid-agent, in-memory (recommended; game files stay pristine):
 *        inst.addTransformer((loader, name, cls, pd, buf) ->
 *            "zombie/core/opengl/ShaderUnit".equals(name) ? ShaderUnitPatcher.patch(buf) : null);
 *
 * Desktop self-test: java com.zomdroid.patch.ShaderUnitPatcher in.class out.class
 */
public final class ShaderUnitPatcher {

    private static final String[] FIELD_NAMES = { "bCombineShaderSources", "combineShaderSources" };

    /** Result of the last patch() call on this thread (simple diagnostics without a logger dep). */
    public static final class Result {
        public String family = "none";   // "clinit-flip" | "getstatic-replace" | "none"
        public int patchedSites = 0;
        public String fieldName = null;
    }
    private static final ThreadLocal<Result> LAST = ThreadLocal.withInitial(Result::new);
    public static Result lastResult() { return LAST.get(); }

    /** Returns a patched copy, or null if nothing was changed (unknown layout / already patched). */
    public static byte[] patch(byte[] classFile) {
        try {
            return patchInner(classFile.clone());
        } catch (RuntimeException e) {
            // Malformed/unexpected class: never break game startup over the patch.
            Result r = new Result();
            r.family = "parse-error: " + e.getMessage();
            LAST.set(r);
            return null;
        }
    }

    // ---------------------------------------------------------------- class file walk

    private static byte[] patchInner(byte[] b) {
        Result res = new Result();
        LAST.set(res);

        if (u4(b, 0) != 0xCAFEBABEL) throw new RuntimeException("not a class file");
        int cpCount = u2(b, 8);
        int[] cpOffset = new int[cpCount];   // offset of each constant-pool entry (tag byte)
        int p = 10;
        for (int i = 1; i < cpCount; i++) {
            cpOffset[i] = p;
            int tag = b[p] & 0xFF;
            switch (tag) {
                case 7: case 8: case 16: case 19: case 20: p += 3; break;          // Class,String,MethodType,Module,Package
                case 15: p += 4; break;                                             // MethodHandle
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: p += 5; break; // int,float,refs,NameAndType,Dynamic
                case 5: case 6: p += 9; i++; break;                                 // long,double take two slots
                case 1: p += 3 + u2(b, p + 1); break;                               // Utf8
                default: throw new RuntimeException("bad cp tag " + tag + " @" + p);
            }
        }

        // Utf8 indices of the two known field names
        int nameUtf = -1;
        for (int i = 1; i < cpCount; i++) {
            if (cpOffset[i] == 0) continue;
            if ((b[cpOffset[i]] & 0xFF) != 1) continue;
            String s = utf8(b, cpOffset[i]);
            for (String want : FIELD_NAMES) {
                if (want.equals(s)) { nameUtf = i; res.fieldName = want; }
            }
            if (nameUtf == i) break;
        }
        if (nameUtf < 0) return null; // future rewrite: flag gone — leave untouched

        // NameAndType entries whose name is that Utf8; then Fieldrefs pointing at them
        boolean[] natMatch = new boolean[cpCount];
        for (int i = 1; i < cpCount; i++) {
            if (cpOffset[i] == 0 || (b[cpOffset[i]] & 0xFF) != 12) continue;
            if (u2(b, cpOffset[i] + 1) == nameUtf) natMatch[i] = true;
        }
        boolean[] fieldRef = new boolean[cpCount];
        int refs = 0;
        for (int i = 1; i < cpCount; i++) {
            if (cpOffset[i] == 0 || (b[cpOffset[i]] & 0xFF) != 9) continue;
            if (natMatch[u2(b, cpOffset[i] + 3)]) { fieldRef[i] = true; refs++; }
        }
        if (refs == 0) return null;

        // Skip to methods: header, interfaces, fields
        p += 6;                       // access, this, super
        p += 2 + u2(b, p) * 2;        // interfaces
        int fieldsCount = u2(b, p); p += 2;
        for (int i = 0; i < fieldsCount; i++) p = skipMember(b, p);

        // Collect every method's Code segment first, then patch in two ORDERED passes:
        // Family A alone if it applies (matches the field-proven 42.8-42.12.x artifacts
        // byte-for-byte); Family B only when no clinit init exists (42.19+ layout).
        int methodsCount = u2(b, p); p += 2;
        int[] codeOff = new int[methodsCount];
        int[] codeLen = new int[methodsCount];
        boolean[] isClinit = new boolean[methodsCount];
        for (int m = 0; m < methodsCount; m++) {
            String mName = utf8(b, cpOffset[u2(b, p + 2)]);
            isClinit[m] = "<clinit>".equals(mName);
            int attrCount = u2(b, p + 6);
            int q = p + 8;
            for (int a = 0; a < attrCount; a++) {
                String aName = utf8(b, cpOffset[u2(b, q)]);
                long aLen = u4(b, q + 2);
                if ("Code".equals(aName)) {
                    codeLen[m] = (int) u4(b, q + 6 + 4);
                    codeOff[m] = q + 6 + 8;
                }
                q += 6 + aLen;
            }
            p = q;
        }

        int flips = 0;
        for (int m = 0; m < methodsCount; m++)
            if (isClinit[m] && codeOff[m] != 0)
                flips += patchClinit(b, codeOff[m], codeLen[m], fieldRef);
        if (flips > 0) { res.family = "clinit-flip"; res.patchedSites = flips; return b; }

        int reads = 0;
        for (int m = 0; m < methodsCount; m++)
            if (codeOff[m] != 0)
                reads += replaceGetstatic(b, codeOff[m], codeLen[m], fieldRef);
        if (reads > 0) { res.family = "getstatic-replace"; res.patchedSites = reads; return b; }
        return null; // field exists but no patchable site (e.g. already-patched file)
    }

    /** Family A: iconst_0 immediately before putstatic <our field> inside clinit. */
    private static int patchClinit(byte[] b, int code, int len, boolean[] fieldRef) {
        int n = 0, pc = 0, prevOp = -1, prevPc = -1;
        while (pc < len) {
            int op = b[code + pc] & 0xFF;
            if (op == 0xB3 && prevOp == 0x03 && fieldRef[u2(b, code + pc + 1)]) {
                b[code + prevPc] = 0x04; // iconst_0 -> iconst_1
                n++;
            }
            prevOp = op; prevPc = pc;
            pc += insnLen(b, code, pc, len);
        }
        return n;
    }

    /** Family B: every getstatic <our field> anywhere becomes iconst_1; nop; nop. */
    private static int replaceGetstatic(byte[] b, int code, int len, boolean[] fieldRef) {
        int n = 0, pc = 0;
        while (pc < len) {
            int op = b[code + pc] & 0xFF;
            int il = insnLen(b, code, pc, len);
            if (op == 0xB2 && fieldRef[u2(b, code + pc + 1)]) {
                b[code + pc] = 0x04; b[code + pc + 1] = 0x00; b[code + pc + 2] = 0x00;
                n++;
            }
            pc += il;
        }
        return n;
    }

    // ------------------------------------------------------------------ jvm plumbing

    private static int skipMember(byte[] b, int p) {
        int attrCount = u2(b, p + 6);
        int q = p + 8;
        for (int a = 0; a < attrCount; a++) q += 6 + u4(b, q + 2);
        return q;
    }

    /** Instruction length at pc (standard opcodes + wide/tableswitch/lookupswitch). */
    private static int insnLen(byte[] b, int code, int pc, int len) {
        int op = b[code + pc] & 0xFF;
        switch (op) {
            case 0xAA: { // tableswitch
                int pad = 3 - (pc & 3);
                int base = code + pc + 1 + pad;
                long lo = u4(b, base + 4), hi = u4(b, base + 8);
                return 1 + pad + 12 + (int) (hi - lo + 1) * 4;
            }
            case 0xAB: { // lookupswitch
                int pad = 3 - (pc & 3);
                int base = code + pc + 1 + pad;
                long npairs = u4(b, base + 4);
                return 1 + pad + 8 + (int) npairs * 8;
            }
            case 0xC4: // wide
                return ((b[code + pc + 1] & 0xFF) == 0x84) ? 6 : 4;
            default: {
                int l = OP_LEN[op];
                if (l == 0) throw new RuntimeException("unknown opcode 0x" + Integer.toHexString(op) + " @pc " + pc);
                return l;
            }
        }
    }

    private static final int[] OP_LEN = new int[256];
    static {
        for (int i = 0x00; i <= 0x0F; i++) OP_LEN[i] = 1;
        OP_LEN[0x10] = 2; OP_LEN[0x11] = 3; OP_LEN[0x12] = 2; OP_LEN[0x13] = 3; OP_LEN[0x14] = 3;
        for (int i = 0x15; i <= 0x19; i++) OP_LEN[i] = 2;
        for (int i = 0x1A; i <= 0x35; i++) OP_LEN[i] = 1;
        for (int i = 0x36; i <= 0x3A; i++) OP_LEN[i] = 2;
        for (int i = 0x3B; i <= 0x83; i++) OP_LEN[i] = 1;
        OP_LEN[0x84] = 3;
        for (int i = 0x85; i <= 0x98; i++) OP_LEN[i] = 1;
        for (int i = 0x99; i <= 0xA8; i++) OP_LEN[i] = 3;
        OP_LEN[0xA9] = 2;
        for (int i = 0xAC; i <= 0xB1; i++) OP_LEN[i] = 1;
        for (int i = 0xB2; i <= 0xB8; i++) OP_LEN[i] = 3;
        OP_LEN[0xB9] = 5; OP_LEN[0xBA] = 5; OP_LEN[0xBB] = 3; OP_LEN[0xBC] = 2; OP_LEN[0xBD] = 3;
        OP_LEN[0xBE] = 1; OP_LEN[0xBF] = 1; OP_LEN[0xC0] = 3; OP_LEN[0xC1] = 3; OP_LEN[0xC2] = 1; OP_LEN[0xC3] = 1;
        OP_LEN[0xC5] = 4; OP_LEN[0xC6] = 3; OP_LEN[0xC7] = 3; OP_LEN[0xC8] = 5; OP_LEN[0xC9] = 5;
    }

    private static int u2(byte[] b, int p) { return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); }
    private static long u4(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }
    private static String utf8(byte[] b, int off) {
        int len = u2(b, off + 1);
        return new String(b, off + 3, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ desktop test

    public static void main(String[] args) throws Exception {
        byte[] in = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
        byte[] out = patch(in);
        Result r = lastResult();
        System.out.println("family=" + r.family + " sites=" + r.patchedSites + " field=" + r.fieldName);
        if (out != null && args.length > 1) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[1]), out);
            System.out.println("written: " + args[1]);
        } else if (out == null) {
            System.out.println("untouched");
        }
    }
}
