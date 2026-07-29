package com.zomdroid;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

// Minimal reader for the JNI entry points an ELF64 shared library exports.
//
// Used to check whether the Android build of a game library — the ones Project Zomboid ships in
// android/arm64-v8a/ — really provides everything its own Linux x86_64 build does. Only the ELF
// header, the section header table and .dynsym/.dynstr are read, so a 10 MB library costs a few
// hundred KB of I/O rather than its full size.
public final class ElfSymbols {
    private static final String LOG_TAG = "ElfSymbols";

    private static final int EI_NIDENT = 16;
    private static final int ELFCLASS64 = 2;
    private static final int ELFDATA2LSB = 1;
    private static final int SHT_DYNSYM = 11;
    private static final int SHDR_SIZE = 64;      // sizeof(Elf64_Shdr)
    private static final int SYM_ENTRY_SIZE = 24; // sizeof(Elf64_Sym)
    private static final long MAX_SECTION_BYTES = 64L * 1024 * 1024;

    private ElfSymbols() {}

    // Names of *defined* symbols starting with "Java_". Returns null when the file is not a
    // little-endian ELF64 or cannot be parsed — callers must treat null as "unknown" and leave the
    // library alone, never as "exports nothing".
    public static Set<String> readExportedJniSymbols(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] ident = new byte[EI_NIDENT];
            raf.readFully(ident);
            if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') return null;
            // Both the x86_64 Linux libs and the arm64 Android ones are ELF64 little-endian, so a
            // single layout covers the comparison; anything else is not ours to judge.
            if (ident[4] != ELFCLASS64 || ident[5] != ELFDATA2LSB) return null;

            long shoff = readLong(raf, 0x28);      // e_shoff
            int shentsize = readUShort(raf, 0x3A); // e_shentsize
            int shnum = readUShort(raf, 0x3C);     // e_shnum
            if (shoff <= 0 || shnum <= 0 || shentsize < SHDR_SIZE) return null;

            byte[] shdrs = readAt(raf, shoff, (long) shnum * shentsize);
            if (shdrs == null) return null;
            ByteBuffer sh = ByteBuffer.wrap(shdrs).order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < shnum; i++) {
                int shdr = i * shentsize;
                if (sh.getInt(shdr + 4) != SHT_DYNSYM) continue; // sh_type

                int strIdx = sh.getInt(shdr + 40); // sh_link -> the matching .dynstr
                if (strIdx <= 0 || strIdx >= shnum) return null;
                int strHdr = strIdx * shentsize;

                byte[] symtab = readAt(raf, sh.getLong(shdr + 24), sh.getLong(shdr + 32));
                byte[] strtab = readAt(raf, sh.getLong(strHdr + 24), sh.getLong(strHdr + 32));
                if (symtab == null || strtab == null) return null;
                return collectJniNames(symtab, strtab);
            }
            return null; // no .dynsym
        } catch (IOException | RuntimeException e) {
            Log.w(LOG_TAG, "Cannot read symbols from " + file.getName() + ": " + e);
            return null;
        }
    }

    private static Set<String> collectJniNames(byte[] symtab, byte[] strtab) {
        ByteBuffer sym = ByteBuffer.wrap(symtab).order(ByteOrder.LITTLE_ENDIAN);
        Set<String> names = new HashSet<>();
        for (int off = 0; off + SYM_ENTRY_SIZE <= symtab.length; off += SYM_ENTRY_SIZE) {
            if (sym.getShort(off + 6) == 0) continue; // st_shndx == SHN_UNDEF: imported, not exported
            String name = stringAt(strtab, sym.getInt(off)); // st_name
            if (name != null && name.startsWith("Java_")) names.add(name);
        }
        return names;
    }

    private static String stringAt(byte[] strtab, int offset) {
        if (offset < 0 || offset >= strtab.length) return null;
        int end = offset;
        while (end < strtab.length && strtab[end] != 0) end++;
        return new String(strtab, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static byte[] readAt(RandomAccessFile raf, long offset, long size) throws IOException {
        if (offset <= 0 || size <= 0 || size > MAX_SECTION_BYTES) return null;
        byte[] buf = new byte[(int) size];
        raf.seek(offset);
        raf.readFully(buf);
        return buf;
    }

    private static long readLong(RandomAccessFile raf, long offset) throws IOException {
        byte[] b = new byte[8];
        raf.seek(offset);
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static int readUShort(RandomAccessFile raf, long offset) throws IOException {
        byte[] b = new byte[2];
        raf.seek(offset);
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }
}
