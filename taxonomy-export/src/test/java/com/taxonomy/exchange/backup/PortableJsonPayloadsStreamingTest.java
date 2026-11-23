package com.taxonomy.exchange.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.*;

class PortableJsonPayloadsStreamingTest {
    private static final int LIMIT = 24_000_000;

    @Test void everyCodeUnitAndLegacyFieldSurvivesTheStreamingBoundary() throws Exception {
        String original = "Quelle 水位 🌊 e\u0301 \uD800\u0000\uDC00\n".repeat(400_000);
        String packed = PortableJsonPayloads.pack(original, LIMIT);
        assertThat(readAll(packed)).isEqualTo(original);
        assertThat(readAll("  {\"original\":true}\n")).isEqualTo("  {\"original\":true}\n");
        String absent = PortableJsonPayloads.read(null, LIMIT, reader -> "unexpected");
        assertThat(absent).isNull();
        assertThat(readAll("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 8191, 8192, 8193})
    void streamingReadsKeepOffsetsLengthsAndExactCodeUnits(int chunk) throws Exception {
        String original = boundaryText();
        String packed = PortableJsonPayloads.pack(original, LIMIT);
        String decoded = PortableJsonPayloads.read(packed, LIMIT, reader -> readInChunks(reader, chunk, chunk));
        assertThat(decoded).isEqualTo(original);
    }

    @Test void aUnitSplitAcrossAnOddInflateBoundaryIsReadExactly() throws Exception {
        String original = boundaryText();
        try (Reader reader = ownedPackedReader(PortableJsonPayloads.pack(original, LIMIT))) {
            // Simulate a one-byte refill with actual inflated data and all corresponding integrity state.
            byte[] buffer = (byte[]) packedField(reader, "buffer").get(reader);
            Inflater inflater = (Inflater) packedField(reader, "inflater").get(reader);
            assertThat(inflater.inflate(buffer, 0, 1)).isEqualTo(1);
            ((CRC32) packedField(reader, "crc").get(reader)).update(buffer, 0, 1);
            ((MessageDigest) packedField(reader, "digest").get(reader)).update(buffer, 0, 1);
            packedField(reader, "total").setLong(reader, 1);
            packedField(reader, "available").setInt(reader, 1);
            assertThat(readInChunks(reader, 3, 8193)).isEqualTo(original);
        }
    }

    @Test void closingTheOwnerEndsTheInflaterAndRejectsFurtherReads() throws Exception {
        try (Reader reader = ownedPackedReader(PortableJsonPayloads.pack(boundaryText(), LIMIT))) {
            Inflater inflater = (Inflater) packedField(reader, "inflater").get(reader);
            char[] target = new char[3];
            assertThat(reader.read(target, 0, 0)).isZero();
            assertThat(reader.read(target, 0, 3)).isEqualTo(3);
            reader.close();
            reader.close();
            assertThatThrownBy(() -> reader.read(target, 0, 0)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.read(target, 0, 3)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> inflater.inflate(new byte[1])).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void partialConsumersCannotAcceptInvalidHashesOrUnconsumedGzipTails() throws Exception {
        String original = "Complete evidence 水位\n".repeat(20_000);
        String packed = PortableJsonPayloads.pack(original, LIMIT);
        String[] fields = packed.split(":", 5);
        byte[] gzip = Base64.getDecoder().decode(fields[4]);
        byte[] trailing = Arrays.copyOf(gzip, gzip.length + 1);
        System.arraycopy(gzip, gzip.length - 8, trailing, trailing.length - 8, 8);
        trailing[gzip.length - 8] = 0x5a;
        byte[] truncated = Arrays.copyOf(gzip, gzip.length - 2);
        System.arraycopy(gzip, gzip.length - 8, truncated, truncated.length - 8, 8);
        byte[] joined = Arrays.copyOf(gzip, gzip.length * 2);
        System.arraycopy(gzip, 0, joined, gzip.length, gzip.length);
        byte[] badCrc = gzip.clone();
        badCrc[badCrc.length - 8] ^= 1;
        byte[] shorter = gzip.clone();
        setGzipSize(shorter, (original.length() - 1) * Character.BYTES);
        byte[] longer = gzip.clone();
        setGzipSize(longer, (original.length() + 1) * Character.BYTES);
        byte[] units = original.getBytes(StandardCharsets.UTF_16BE);
        byte[] odd = Arrays.copyOf(units, units.length + 1);
        var compressed = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(compressed)) {
            output.write(odd);
        }
        byte[] oddGzip = compressed.toByteArray();
        setGzipSize(oddGzip, odd.length + 1);
        String oddHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(odd));

        for (String broken : List.of(
                frame(original.length(), fields[3], trailing),
                frame(original.length(), fields[3], truncated),
                frame(original.length(), fields[3], joined),
                frame(original.length(), fields[3], badCrc),
                frame(original.length() - 1, fields[3], shorter),
                frame(original.length() + 1, fields[3], longer),
                frame(original.length() + 1, oddHash, oddGzip),
                frame(original.length(), "0".repeat(64), gzip))) {
            int[] parserReturns = {0};
            assertThatThrownBy(() -> PortableJsonPayloads.read(broken, LIMIT, reader -> {
                char[] prefix = new char[8193];
                assertThat(reader.read(prefix)).isEqualTo(prefix.length);
                assertThat(new String(prefix)).isEqualTo(original.substring(0, prefix.length));
                parserReturns[0]++;
                return "early result";
            })).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> PortableJsonPayloads.read(broken, LIMIT, reader -> {
                assertThat(reader.read()).isEqualTo('C');
                reader.close(); // JSON parsers may close their supplied input after the root value.
                parserReturns[0]++;
                return "closed result";
            })).isInstanceOf(IOException.class);
            assertThat(parserReturns[0]).as("both parsers returned before the tail failed validation").isEqualTo(2);
        }
        byte[] badSize = gzip.clone();
        badSize[badSize.length - 4] ^= 1;
        for (String broken : List.of(
                packed.replace(":v1:", ":v9:"),
                String.join(":", fields[0], fields[1], "0" + fields[2], fields[3], fields[4]),
                frame(original.length(), fields[3], badSize),
                packed + ":ignored", packed.substring(0, packed.length() - 4))) {
            assertThatThrownBy(() -> PortableJsonPayloads.read(broken, LIMIT, reader -> {
                reader.close();
                return "candidate";
            })).isInstanceOf(IOException.class);
        }
    }

    @Test void ownerBoundsAndInterruptsRemainAuthoritativeForStreamingReaders() throws Exception {
        String original = "Original evidence 水位\n".repeat(10_000);
        String packed = PortableJsonPayloads.pack(original, LIMIT);
        assertThatThrownBy(() -> PortableJsonPayloads.read(packed, original.length() - 1, reader -> reader.read()))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PortableJsonPayloads.read(original, original.length() - 1, reader -> reader.read()))
                .isInstanceOf(IOException.class);
        Thread.currentThread().interrupt();
        try {
            assertThat(readAll(packed)).isEqualTo(original);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void aConsumerCannotSwallowAnIntegrityFailureAndAcceptTheNextRead() throws Exception {
        String packed = PortableJsonPayloads.pack("Complete evidence 水位\n".repeat(20_000), LIMIT);
        String[] fields = packed.split(":", 5);
        String emptyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        String broken = String.join(":", fields[0], fields[1], fields[2], emptyHash, fields[4]);
        Throwable[] parserFailure = {null};
        Throwable ownerFailure = catchThrowable(() -> PortableJsonPayloads.read(broken, LIMIT, reader -> {
            parserFailure[0] = catchThrowable(() -> reader.transferTo(Writer.nullWriter()));
            assertThat(parserFailure[0]).isInstanceOf(IOException.class);
            char[] target = new char[3];
            assertThat(reader.read(target, 0, 0)).isZero();
            assertThatThrownBy(() -> reader.read(target)).isSameAs(parserFailure[0]);
            return "candidate";
        }));
        assertThat(ownerFailure).isInstanceOf(IOException.class).isSameAs(parserFailure[0]);
    }

    @Test void fullIntegrityValidationDoesNotAllocateAnExpandedDocumentCopy() throws Exception {
        String original = "Full retained evidence 水位 🌊\n".repeat(500_000);
        String packed = PortableJsonPayloads.pack(original, LIMIT);
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        assertThat(bean.isThreadAllocatedMemorySupported()).isTrue();
        boolean enabled = bean.isThreadAllocatedMemoryEnabled();
        if (!enabled) bean.setThreadAllocatedMemoryEnabled(true);
        try {
            PortableJsonPayloads.read(packed, LIMIT, reader -> reader.transferTo(Writer.nullWriter()));
            PortableJsonPayloads.unpack(packed, LIMIT);
            long before = bean.getCurrentThreadAllocatedBytes();
            long consumed = PortableJsonPayloads.read(packed, LIMIT, reader -> reader.transferTo(Writer.nullWriter()));
            assertThat(consumed).isEqualTo((long) original.length());
            long streamed = bean.getCurrentThreadAllocatedBytes() - before;
            before = bean.getCurrentThreadAllocatedBytes();
            assertThat(PortableJsonPayloads.unpack(packed, LIMIT).length()).isEqualTo(original.length());
            long materialized = bean.getCurrentThreadAllocatedBytes() - before;
            assertThat(streamed).as("Complete bounded decode and validation use fixed buffers instead of expanded text copies")
                    .isLessThan(materialized / 10);
        } finally { if (!enabled) bean.setThreadAllocatedMemoryEnabled(false); }
    }

    private static String boundaryText() {
        return " \r\n\"水🌊\uD800x\uDC00e\u0301\"\n".repeat(6_000);
    }

    private static String readInChunks(Reader reader, int offset, int chunk) throws IOException {
        char sentinel = '\uffff';
        char[] target = new char[offset + chunk + 1];
        var output = new StringBuilder();
        Arrays.fill(target, sentinel);
        assertThat(reader.read(target, target.length, 0)).isZero();
        int count;
        while ((count = reader.read(target, offset, chunk)) != -1) {
            assertThat(count).isBetween(1, chunk);
            assertThat(target[offset - 1]).isEqualTo(sentinel);
            assertThat(target[offset + chunk]).isEqualTo(sentinel);
            output.append(target, offset, count);
        }
        assertThat(reader.read(target, offset, chunk)).isEqualTo(-1);
        return output.toString();
    }

    private static String frame(int characters, String hash, byte[] gzip) {
        return "taxonomy-json-payload:v1:" + characters + ":" + hash + ":" + Base64.getEncoder().encodeToString(gzip);
    }

    private static void setGzipSize(byte[] gzip, int size) {
        ByteBuffer.wrap(gzip).order(ByteOrder.LITTLE_ENDIAN).putInt(gzip.length - 4, size);
    }

    // Only private boundary and resource ownership tests need the real reader behind the borrowed view.
    private static Reader ownedPackedReader(String stored) throws Exception {
        var parse = PortableJsonPayloads.class.getDeclaredMethod("frame", String.class, int.class);
        parse.setAccessible(true);
        var frame = parse.invoke(null, stored, LIMIT);
        var type = Class.forName(PortableJsonPayloads.class.getName() + "$PackedReader");
        var constructor = type.getDeclaredConstructor(frame.getClass());
        constructor.setAccessible(true);
        return (Reader) constructor.newInstance(frame);
    }

    private static Field packedField(Reader reader, String name) throws Exception {
        Field field = reader.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String readAll(String stored) throws IOException {
        return PortableJsonPayloads.read(stored, LIMIT, reader -> {
            var output = new StringWriter(); reader.transferTo(output); return output.toString();
        });
    }
}
