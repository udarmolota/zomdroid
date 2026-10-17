package com.zomdroid;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Does an ARM64 game library take, for each JNI function, as many arguments as the game's Java
 * passes? Checked from the DWARF the library carries (TIS ships its Android builds with full debug
 * info) against the native method descriptors in projectzomboid.jar.
 *
 * Why: the export list alone missed 42.20's Lighting. Its updateTorch() existed by name but took
 * 11 arguments where the game passes 14 (no torch colour), so every argument after the position
 * came from the wrong register - torch and headlight cones turned into circles. It was only caught
 * because the same build also lacked another export. A library with a mismatch is wrong in ways
 * a player sees; its Linux twin through box64 is slower but right.
 *
 * Pure Java, no Android classes: unit-tested on the JVM. Every reader returns null on anything it
 * cannot parse - "unknown" must never be read as "wrong".
 */
public final class JniAbiCheck {
    private JniAbiCheck() {}

    // ---- the result -------------------------------------------------------------------------

    /**
     * JNI functions whose C side takes a different number of arguments than the Java side passes,
     * as "method: C n, Java m"; empty when everything that can be compared matches; null when the
     * library has no readable DWARF or the jar cannot be read.
     */
    public static List<String> mismatches(File library, File gameJar) {
        Map<String, Integer> cParams = dwarfJniParameterCounts(library);
        if (cParams == null || cParams.isEmpty()) return null;
        // Only what the JVM can bind: a function whose exported name is not exactly its JNI name
        // (TIS's FMOD exports a few with C++ type suffixes glued on) is missing, not mismatched -
        // missing exports are the export-list check's business.
        java.util.Set<String> exports = dynamicSymbols(library);
        if (exports == null) return null;
        cParams.keySet().retainAll(exports);
        Map<String, Integer> javaArgs = javaNativeArgumentCounts(gameJar, cParams.keySet());
        if (javaArgs == null) return null;
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cParams.entrySet()) {
            Integer java = javaArgs.get(e.getKey());
            if (java == null) continue;   // not a native the game declares: nothing to compare
            int c = e.getValue() - 2;     // JNIEnv* and jclass / jobject come first
            if (c != java) out.add(methodOf(e.getKey()) + ": C " + c + ", Java " + java);
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static String methodOf(String jniName) {
        int i = jniName.lastIndexOf('_');
        return i < 0 ? jniName : jniName.substring(i + 1);
    }

    // ---- the Java side ----------------------------------------------------------------------

    /** JNI short name -> number of Java arguments, for the classes those names point at. */
    static Map<String, Integer> javaNativeArgumentCounts(File jar, Iterable<String> jniNames) {
        Map<String, String> classOf = new HashMap<>();   // class path -> "" (set of classes)
        for (String name : jniNames) {
            String[] cm = demangle(name);
            if (cm != null) classOf.put(cm[0], "");
        }
        Map<String, Integer> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (String cls : classOf.keySet()) {
                ZipEntry entry = zip.getEntry(cls + ".class");
                if (entry == null) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = readAll(in);
                }
                List<String[]> methods = nativeMethods(bytes);
                Map<String, Integer> sameName = new HashMap<>();
                for (String[] m : methods) sameName.merge(m[0], 1, Integer::sum);
                for (String[] m : methods) {
                    // An overloaded native is exported under its long JNI name: name + "__" + the
                    // mangled argument types. Keyed by the short name it would take the last
                    // overload's count and report the others as mismatches.
                    String key = "Java_" + mangle(cls) + "_" + mangle(m[0]);
                    if (sameName.get(m[0]) > 1) {
                        key += "__" + mangle(m[1].substring(1, m[1].indexOf(')')));
                    }
                    out.put(key, argumentCount(m[1]));
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return out;
    }

    /** {class path, method} from "Java_zombie_iso_LightingJNI_updateTorch", or null. */
    static String[] demangle(String jni) {
        if (!jni.startsWith("Java_")) return null;
        String s = jni.substring(5);
        int overload = s.indexOf("__");
        if (overload >= 0) s = s.substring(0, overload);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '_') { b.append(c); continue; }
            char n = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (n == '1') { b.append('_'); i++; }
            else if (n == '2') { b.append(';'); i++; }
            else if (n == '3') { b.append('['); i++; }
            else if (n == '0' && i + 5 < s.length()) {
                b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16)); i += 5;
            } else b.append('/');
        }
        String full = b.toString();
        int slash = full.lastIndexOf('/');
        return slash <= 0 ? null : new String[] {full.substring(0, slash), full.substring(slash + 1)};
    }

    static String mangle(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '/') b.append('_');
            else if (c == '_') b.append("_1");
            else if (c == ';') b.append("_2");
            else if (c == '[') b.append("_3");
            else if (c < 128 && Character.isLetterOrDigit(c)) b.append(c);
            else b.append(String.format("_0%04x", (int) c));
        }
        return b.toString();
    }

    static int argumentCount(String descriptor) {
        int n = 0;
        int i = descriptor.indexOf('(') + 1;
        int end = descriptor.indexOf(')');
        while (i < end) {
            while (descriptor.charAt(i) == '[') i++;
            if (descriptor.charAt(i) == 'L') i = descriptor.indexOf(';', i);
            i++;
            n++;
        }
        return n;
    }

    /** {name, descriptor} of the ACC_NATIVE methods of a class file. */
    static List<String[]> nativeMethods(byte[] b) {
        ByteBuffer in = ByteBuffer.wrap(b);   // class files are big-endian
        if (in.getInt(0) != 0xCAFEBABE) throw new IllegalArgumentException("not a class file");
        int count = in.getShort(8) & 0xFFFF;
        String[] utf8 = new String[count];
        int p = 10;
        for (int k = 1; k < count; k++) {
            int tag = b[p] & 0xFF;
            switch (tag) {
                case 1: {
                    int len = in.getShort(p + 1) & 0xFFFF;
                    utf8[k] = new String(b, p + 3, len, StandardCharsets.UTF_8);
                    p += 3 + len;
                    break;
                }
                case 3: case 4: p += 5; break;
                case 5: case 6: p += 9; k++; break;
                case 7: case 8: case 16: case 19: case 20: p += 3; break;
                case 9: case 10: case 11: case 12: case 17: case 18: p += 5; break;
                case 15: p += 4; break;
                default: throw new IllegalArgumentException("constant tag " + tag);
            }
        }
        p += 6;                                           // access, this, super
        p += 2 + 2 * (in.getShort(p) & 0xFFFF);           // interfaces
        p = skipMembers(in, p, null, utf8);               // fields
        List<String[]> out = new ArrayList<>();
        skipMembers(in, p, out, utf8);                    // methods
        return out;
    }

    private static int skipMembers(ByteBuffer in, int p, List<String[]> natives, String[] utf8) {
        int n = in.getShort(p) & 0xFFFF;
        p += 2;
        for (int i = 0; i < n; i++) {
            int access = in.getShort(p) & 0xFFFF;
            int name = in.getShort(p + 2) & 0xFFFF;
            int desc = in.getShort(p + 4) & 0xFFFF;
            int attrs = in.getShort(p + 6) & 0xFFFF;
            p += 8;
            for (int a = 0; a < attrs; a++) p += 6 + in.getInt(p + 2);
            if (natives != null && (access & 0x0100) != 0) natives.add(new String[] {utf8[name], utf8[desc]});
        }
        return p;
    }

    // ---- the C side: DWARF ------------------------------------------------------------------

    private static final long MAX_SECTION_BYTES = 128L * 1024 * 1024;

    /**
     * "Java_..." function name -> number of formal parameters of its definition, from the
     * library's .debug_info; null when it has none or it cannot be read.
     */
    static Map<String, Integer> dwarfJniParameterCounts(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            Map<String, byte[]> s = sections(raf, ".debug_info", ".debug_abbrev", ".debug_str",
                    ".debug_line_str", ".debug_str_offsets");
            if (s == null || s.get(".debug_info") == null || s.get(".debug_abbrev") == null) return null;
            return new Dwarf(s).jniParameterCounts();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Names of the defined symbols in .dynsym, or null. */
    static java.util.Set<String> dynamicSymbols(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            Map<String, byte[]> s = sections(raf, ".dynsym", ".dynstr");
            if (s == null || s.get(".dynsym") == null || s.get(".dynstr") == null) return null;
            ByteBuffer sym = ByteBuffer.wrap(s.get(".dynsym")).order(ByteOrder.LITTLE_ENDIAN);
            byte[] names = s.get(".dynstr");
            java.util.Set<String> out = new java.util.HashSet<>();
            for (int off = 0; off + 24 <= sym.capacity(); off += 24) {
                if (sym.getShort(off + 6) == 0) continue;   // undefined: imported, not exported
                String name = cString(names, sym.getInt(off));
                if (name != null && !name.isEmpty()) out.add(name);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, byte[]> sections(RandomAccessFile raf, String... wanted) throws IOException {
        byte[] ident = new byte[16];
        raf.readFully(ident);
        if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') return null;
        if (ident[4] != 2 || ident[5] != 1) return null;   // ELF64 little-endian only
        ByteBuffer hdr = ByteBuffer.wrap(readAt(raf, 0, 64)).order(ByteOrder.LITTLE_ENDIAN);
        long shoff = hdr.getLong(0x28);
        int shentsize = hdr.getShort(0x3A) & 0xFFFF, shnum = hdr.getShort(0x3C) & 0xFFFF,
                shstrndx = hdr.getShort(0x3E) & 0xFFFF;
        if (shoff <= 0 || shnum == 0 || shentsize < 64 || shstrndx >= shnum) return null;
        ByteBuffer sh = ByteBuffer.wrap(readAt(raf, shoff, (long) shnum * shentsize)).order(ByteOrder.LITTLE_ENDIAN);
        int names = shstrndx * shentsize;
        byte[] shstr = readAt(raf, sh.getLong(names + 24), sh.getLong(names + 32));
        Map<String, byte[]> out = new HashMap<>();
        for (int i = 0; i < shnum; i++) {
            int h = i * shentsize;
            String name = cString(shstr, sh.getInt(h));
            for (String w : wanted) {
                if (!w.equals(name)) continue;
                if ((sh.getLong(h + 8) & 0x800) != 0) return null;   // SHF_COMPRESSED: not handled
                out.put(name, readAt(raf, sh.getLong(h + 24), sh.getLong(h + 32)));
            }
        }
        return out;
    }

    private static byte[] readAt(RandomAccessFile raf, long offset, long size) throws IOException {
        if (offset < 0 || size <= 0 || size > MAX_SECTION_BYTES) throw new IOException("section size " + size);
        byte[] buf = new byte[(int) size];
        raf.seek(offset);
        raf.readFully(buf);
        return buf;
    }

    private static String cString(byte[] b, int off) {
        if (b == null || off < 0 || off >= b.length) return null;
        int end = off;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Just enough of DWARF 2-5 to walk the DIE tree and read names. Note: never write
     *  {@code pos += u8()} - Java reads the old pos before u8() advances it. */
    private static final class Dwarf {
        private static final int TAG_SUBPROGRAM = 0x2e, TAG_FORMAL_PARAMETER = 0x05;
        private static final int AT_NAME = 0x03, AT_STR_OFFSETS_BASE = 0x72;

        private final byte[] info, abbrev, str, lineStr, strOffsets;
        private int pos;

        Dwarf(Map<String, byte[]> s) {
            info = s.get(".debug_info");
            abbrev = s.get(".debug_abbrev");
            str = s.get(".debug_str");
            lineStr = s.get(".debug_line_str");
            strOffsets = s.get(".debug_str_offsets");
        }

        private static final class Abbrev {
            int tag;
            boolean children;
            int[] attrs, forms;
            long[] implicit;
        }

        Map<String, Integer> jniParameterCounts() {
            Map<String, Integer> out = new HashMap<>();
            int unit = 0;
            while (unit + 11 <= info.length) {
                pos = unit;
                long length = u32();
                boolean dwarf64 = length == 0xFFFFFFFFL;
                if (dwarf64) length = u64();
                int bodyStart = pos;
                int next = (int) (bodyStart + length);
                if (length <= 0 || next > info.length) break;
                int version = u16();
                int addrSize, unitType = 1;
                long abbrevOff;
                if (version >= 5) {
                    unitType = u8();
                    addrSize = u8();
                    abbrevOff = dwarf64 ? u64() : u32();
                    if (unitType == 2 || unitType == 6) pos += 8 + (dwarf64 ? 8 : 4);   // type units
                    else if (unitType == 4 || unitType == 5) pos += 8;                  // skeleton / split
                } else {
                    abbrevOff = dwarf64 ? u64() : u32();
                    addrSize = u8();
                }
                Map<Long, Abbrev> table = abbrevs((int) abbrevOff);
                walkUnit(next, table, version, addrSize, dwarf64, out);
                unit = next;
            }
            return out;
        }

        private void walkUnit(int end, Map<Long, Abbrev> table, int version, int addrSize,
                              boolean dwarf64, Map<String, Integer> out) {
            long strBase = 8;   // DWARF 5 default: after the .debug_str_offsets header
            int depth = 0;
            String openFunction = null;
            int functionDepth = -1, params = 0;
            boolean first = true;
            while (pos < end) {
                long code = uleb();
                if (code == 0) {
                    depth--;
                    if (openFunction != null && depth <= functionDepth) {
                        if (params > 0) out.merge(openFunction, params, Math::max);
                        openFunction = null;
                    }
                    if (depth < 0) break;
                    continue;
                }
                Abbrev a = table.get(code);
                if (a == null) return;   // malformed: stop this unit, keep what was found
                String name = null;
                for (int i = 0; i < a.attrs.length; i++) {
                    int form = a.forms[i];
                    if (a.attrs[i] == AT_NAME) name = readName(form, version, addrSize, dwarf64, strBase);
                    else if (first && a.attrs[i] == AT_STR_OFFSETS_BASE) strBase = readOffset(form, dwarf64);
                    else skip(form, version, addrSize, dwarf64, a.implicit[i]);
                }
                first = false;
                if (openFunction != null && depth == functionDepth + 1 && a.tag == TAG_FORMAL_PARAMETER) params++;
                if (a.tag == TAG_SUBPROGRAM && name != null && name.startsWith("Java_") && a.children
                        && openFunction == null) {
                    openFunction = name;
                    functionDepth = depth;
                    params = 0;
                }
                if (a.children) depth++;
            }
        }

        private Map<Long, Abbrev> abbrevs(int off) {
            Map<Long, Abbrev> table = new HashMap<>();
            int saved = pos;
            pos = off;
            byte[] src = abbrev;
            while (true) {
                long code = uleb(src);
                if (code == 0) break;
                Abbrev a = new Abbrev();
                a.tag = (int) uleb(src);
                a.children = (src[pos++] & 0xFF) != 0;
                List<int[]> specs = new ArrayList<>();
                List<Long> consts = new ArrayList<>();
                while (true) {
                    int at = (int) uleb(src), form = (int) uleb(src);
                    long c = form == 0x21 ? sleb(src) : 0;
                    if (at == 0 && form == 0) break;
                    specs.add(new int[] {at, form});
                    consts.add(c);
                }
                a.attrs = new int[specs.size()];
                a.forms = new int[specs.size()];
                a.implicit = new long[specs.size()];
                for (int i = 0; i < specs.size(); i++) {
                    a.attrs[i] = specs.get(i)[0];
                    a.forms[i] = specs.get(i)[1];
                    a.implicit[i] = consts.get(i);
                }
                table.put(code, a);
            }
            pos = saved;
            return table;
        }

        private String readName(int form, int version, int addrSize, boolean dwarf64, long strBase) {
            switch (form) {
                case 0x08: {   // string
                    int start = pos;
                    while (info[pos] != 0) pos++;
                    pos++;
                    return new String(info, start, pos - 1 - start, StandardCharsets.UTF_8);
                }
                case 0x0e: return cString(str, (int) readOffset(form, dwarf64));         // strp
                case 0x1f: return cString(lineStr, (int) readOffset(form, dwarf64));     // line_strp
                case 0x1a: return strx(uleb(), strBase, dwarf64);
                case 0x25: return strx(u8(), strBase, dwarf64);
                case 0x26: return strx(u16(), strBase, dwarf64);
                case 0x27: { long v = u16() | ((long) u8() << 16); return strx(v, strBase, dwarf64); }
                case 0x28: return strx(u32(), strBase, dwarf64);
                default:
                    skip(form, version, addrSize, dwarf64, 0);
                    return null;
            }
        }

        private String strx(long index, long base, boolean dwarf64) {
            if (strOffsets == null) return null;
            int at = (int) (base + index * (dwarf64 ? 8 : 4));
            if (at < 0 || at + (dwarf64 ? 8 : 4) > strOffsets.length) return null;
            ByteBuffer bb = ByteBuffer.wrap(strOffsets).order(ByteOrder.LITTLE_ENDIAN);
            long off = dwarf64 ? bb.getLong(at) : bb.getInt(at) & 0xFFFFFFFFL;
            return cString(str, (int) off);
        }

        private long readOffset(int form, boolean dwarf64) {
            switch (form) {
                case 0x0b: return u8();
                case 0x05: return u16();
                case 0x06: return u32();
                case 0x07: return u64();
                default: return dwarf64 ? u64() : u32();   // sec_offset, strp, line_strp
            }
        }

        private void skip(int form, int version, int addrSize, boolean dwarf64, long implicit) {
            int off = dwarf64 ? 8 : 4;
            switch (form) {
                case 0x01: pos += addrSize; break;                          // addr
                case 0x03: { int n = u16(); pos += n; break; }               // block2
                case 0x04: { int n = (int) u32(); pos += n; break; }         // block4
                case 0x05: pos += 2; break;                                  // data2
                case 0x06: pos += 4; break;                                  // data4
                case 0x07: pos += 8; break;                                  // data8
                case 0x08: while (info[pos] != 0) pos++; pos++; break;       // string
                case 0x09: case 0x18: { int n = (int) uleb(); pos += n; break; }   // block, exprloc
                case 0x0a: { int n = u8(); pos += n; break; }                // block1
                case 0x0b: case 0x0c: case 0x11: case 0x25: case 0x29: pos += 1; break;
                case 0x0d: sleb(info); break;                                // sdata
                case 0x0f: case 0x15: case 0x1a: case 0x1b: case 0x22: case 0x23: uleb(); break;
                case 0x10: pos += version <= 2 ? addrSize : off; break;     // ref_addr
                case 0x12: case 0x26: case 0x2a: pos += 2; break;
                case 0x27: case 0x2b: pos += 3; break;
                case 0x13: case 0x1c: case 0x28: case 0x2c: pos += 4; break;
                case 0x14: case 0x20: case 0x24: pos += 8; break;
                case 0x0e: case 0x17: case 0x1d: case 0x1f: pos += off; break;   // strp, sec_offset, ...
                case 0x16: skip((int) uleb(), version, addrSize, dwarf64, 0); break;   // indirect
                case 0x19: case 0x21: break;                                 // flag_present, implicit_const
                case 0x1e: pos += 16; break;                                 // data16
                default: throw new IllegalArgumentException("DWARF form 0x" + Integer.toHexString(form));
            }
        }

        private int u8() { return info[pos++] & 0xFF; }
        private int u16() { int v = (info[pos] & 0xFF) | (info[pos + 1] & 0xFF) << 8; pos += 2; return v; }
        private long u32() {
            long v = (info[pos] & 0xFFL) | (info[pos + 1] & 0xFFL) << 8 | (info[pos + 2] & 0xFFL) << 16
                    | (info[pos + 3] & 0xFFL) << 24;
            pos += 4;
            return v;
        }
        private long u64() { long lo = u32(); return lo | u32() << 32; }
        private long uleb() { return uleb(info); }
        private long uleb(byte[] b) {
            long result = 0;
            int shift = 0;
            while (true) {
                int x = b[pos++] & 0xFF;
                if (shift < 64) result |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return result;
                shift += 7;
            }
        }
        private long sleb(byte[] b) {
            long result = 0;
            int shift = 0, x;
            do {
                x = b[pos++] & 0xFF;
                if (shift < 64) result |= (long) (x & 0x7F) << shift;
                shift += 7;
            } while ((x & 0x80) != 0);
            if (shift < 64 && (x & 0x40) != 0) result |= -1L << shift;
            return result;
        }
    }
}
