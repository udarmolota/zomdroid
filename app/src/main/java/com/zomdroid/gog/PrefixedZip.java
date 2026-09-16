package com.zomdroid.gog;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;

/**
 * Opens a ZIP archive that has arbitrary data in front of it, such as a GOG Linux installer: a
 * Makeself shell script with the game's ZIP appended.
 *
 * <p>Commons Compress handles such a preamble for a plain ZIP, but not for a ZIP64 one - and a
 * Project Zomboid installer is well over 4 GB, so it is always ZIP64 (measured with 1.27.1: the
 * same archive opens without the preamble and fails with it). The offsets inside a ZIP are relative
 * to the start of the archive, so the fix is to hide the preamble: {@link #payloadOffset} works out
 * where the archive starts from the end-of-central-directory records, and {@link #open} hands the
 * library a channel view that begins there.
 *
 * <p>Pure Java, no Android types: this is unit-tested on the JVM.
 */
public final class PrefixedZip {
    private PrefixedZip() {}

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    /** A ZIP64 EOCD record without extensible data is exactly this long. */
    private static final int ZIP64_EOCD_SIZE = 56;
    /** EOCD (22 bytes) plus the longest possible archive comment. */
    private static final int MAX_TAIL = 22 + 0xFFFF;

    /**
     * The number of bytes before the ZIP archive in {@code ch}: 0 for a plain ZIP, the length of
     * the shell script for a GOG installer.
     *
     * @throws IOException if the channel does not end in a ZIP end-of-central-directory record
     */
    public static long payloadOffset(SeekableByteChannel ch) throws IOException {
        long size = ch.size();
        if (size < 22) throw new IOException("not a ZIP: shorter than an end-of-central-directory record");
        int tailLen = (int) Math.min(size, MAX_TAIL);
        long tailStart = size - tailLen;
        ByteBuffer tail = readAt(ch, tailStart, tailLen);
        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (tail.getInt(i) == EOCD_SIG) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("not a ZIP: no end-of-central-directory record");
        long eocdAbs = tailStart + eocd;

        long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
        boolean sentinels = cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL
                || (tail.getShort(eocd + 10) & 0xFFFF) == 0xFFFF;

        // A ZIP64 archive is recognised by its locator, which sits right before the EOCD. The
        // sentinel values in the EOCD are not enough: a writer may emit the ZIP64 records for a
        // small archive and still fill the EOCD with the real numbers.
        long locatorAbs = eocdAbs - 20;
        ByteBuffer locator = locatorAbs >= 0 ? readAt(ch, locatorAbs, 20) : null;
        boolean zip64 = locator != null && locator.getInt(0) == ZIP64_LOCATOR_SIG;
        if (!zip64) {
            if (sentinels) throw new IOException("ZIP64 archive without a locator");
            // The central directory ends where the EOCD begins; the archive starts cdSize+cdOffset
            // before that. Anything left over is the preamble.
            return Math.max(0, eocdAbs - (cdOffset + cdSize));
        }

        // The locator points at the ZIP64 EOCD record relative to the archive start. The record
        // itself directly precedes the locator, so its absolute position is known from the file,
        // and the difference is the preamble.
        long recordRel = locator.getLong(8);

        long recordAbs = locatorAbs - ZIP64_EOCD_SIZE;
        if (recordAbs < 0 || readAt(ch, recordAbs, 4).getInt(0) != ZIP64_EOCD_SIG) {
            // A record with extensible data is longer; walk back to its signature.
            recordAbs = -1;
            long scanLen = Math.min(locatorAbs, 1 << 20);
            long scanStart = locatorAbs - scanLen;
            ByteBuffer scan = readAt(ch, scanStart, (int) scanLen);
            for (int i = (int) scanLen - 4; i >= 0; i--) {
                if (scan.getInt(i) == ZIP64_EOCD_SIG) { recordAbs = scanStart + i; break; }
            }
            if (recordAbs < 0) throw new IOException("ZIP64 end-of-central-directory record not found");
        }
        return Math.max(0, recordAbs - recordRel);
    }

    /** Opens {@code file} as a ZIP, skipping whatever precedes the archive. */
    public static ZipFile open(File file) throws IOException {
        FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        try {
            return open(ch);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /**
     * Opens the ZIP that ends at the end of {@code ch}, skipping whatever precedes it. Closing the
     * returned archive closes {@code ch}.
     */
    public static ZipFile open(SeekableByteChannel ch) throws IOException {
        long offset = payloadOffset(ch);
        SeekableByteChannel view = offset == 0 ? ch : new Window(ch, offset);
        return ZipFile.builder().setSeekableByteChannel(view).get();
    }

    private static ByteBuffer readAt(SeekableByteChannel ch, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        ch.position(pos);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new IOException("unexpected end of file at " + pos);
        }
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.clear();
        return buf;
    }

    /** The tail of a channel, from {@code offset} on, presented as a channel of its own. */
    static final class Window implements SeekableByteChannel {
        private final SeekableByteChannel inner;
        private final long offset;

        Window(SeekableByteChannel inner, long offset) throws IOException {
            this.inner = inner;
            this.offset = offset;
            inner.position(offset);
        }

        @Override public int read(ByteBuffer dst) throws IOException { return inner.read(dst); }

        @Override public int write(ByteBuffer src) throws IOException {
            throw new java.nio.channels.NonWritableChannelException();
        }

        @Override public long position() throws IOException { return inner.position() - offset; }

        @Override public SeekableByteChannel position(long newPosition) throws IOException {
            inner.position(newPosition + offset);
            return this;
        }

        @Override public long size() throws IOException { return inner.size() - offset; }

        @Override public SeekableByteChannel truncate(long size) throws IOException {
            throw new java.nio.channels.NonWritableChannelException();
        }

        @Override public boolean isOpen() { return inner.isOpen(); }

        @Override public void close() throws IOException { inner.close(); }
    }
}
