package com.taxonomy.backup.archive;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.*;

/** Bounds the directory before Commons Compress allocates its entry index. Single-disk, no archive comment. */
final class ZipDirectoryBounds {
    record Directory(long offset, long entries) { }
    private ZipDirectoryBounds() { }

    static Directory inspect(SeekableByteChannel channel, ArchiveLimits limits, ArchiveIO.Guard guard) throws IOException {
        long size = channel.size();
        if (size < 22 || size > limits.maxArchiveBytes()) throw new IOException("Archive size limit or structure invalid");
        ByteBuffer end = read(channel, size - 22, 22);
        if (end.getInt() != 0x06054b50 || u16(end) != 0 || u16(end) != 0) throw invalid();
        int diskCount = u16(end), count = u16(end);
        long length = u32(end), offset = u32(end), boundary = size - 22;
        if (u16(end) != 0 || diskCount != count) throw invalid();
        long entries = count;
        if (size >= 42 && read(channel, size - 42, 4).getInt() == 0x07064b50) {
            ByteBuffer locator = read(channel, size - 42, 20); locator.getInt();
            if (locator.getInt() != 0) throw invalid();
            long recordOffset = locator.getLong(); if (locator.getInt() != 1) throw invalid();
            ByteBuffer zip64 = read(channel, recordOffset, 56);
            if (recordOffset != size - 98 || zip64.getInt() != 0x06064b50 || zip64.getLong() != 44) throw invalid();
            zip64.getShort(); zip64.getShort();
            if (zip64.getInt() != 0 || zip64.getInt() != 0) throw invalid();
            long localEntries = zip64.getLong(); entries = zip64.getLong();
            length = zip64.getLong(); offset = zip64.getLong(); boundary = recordOffset;
            if (localEntries != entries || (count != 65535 && count != entries)) throw invalid();
        } else if (count == 65535 || length == 0xffffffffL || offset == 0xffffffffL) throw invalid();
        if (entries < 1 || entries > limits.maxEntries() + 1L || length < 46 * entries
                || length > entries * (46L + 2048 + 128) || offset < 0 || length > boundary || offset != boundary - length)
            throw new IOException("Archive directory limit or structure invalid");
        long cursor = offset;
        for (long n = 0; n < entries; n++) {
            guard.check(); ByteBuffer header = read(channel, cursor, 46);
            if (header.getInt() != 0x02014b50) throw invalid();
            header.position(28); int name = u16(header), extra = u16(header), comment = u16(header), disk = u16(header);
            if (name < 1 || name > 2048 || extra > 128 || comment != 0 || disk != 0 || cursor > boundary - 46L - name - extra)
                throw new IOException("Archive entry metadata limit exceeded");
            // Replacement decoding must never turn two invalid byte sequences into the same path.
            try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(read(channel, cursor + 46, name)); }
            catch (CharacterCodingException failure) { throw invalid(); }
            cursor += 46L + name + extra;
        }
        if (cursor != boundary) throw invalid();
        channel.position(0); return new Directory(offset, entries);
    }
    static ByteBuffer read(SeekableByteChannel channel, long offset, int length) throws IOException {
        if (offset < 0 || offset > channel.size() - length) throw invalid();
        ByteBuffer bytes = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN); channel.position(offset);
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw invalid();
        return bytes.flip();
    }
    static int u16(ByteBuffer value) { return Short.toUnsignedInt(value.getShort()); }
    static long u32(ByteBuffer value) { return Integer.toUnsignedLong(value.getInt()); }
    static IOException invalid() { return new IOException("Invalid or unsupported ZIP structure"); }
}
