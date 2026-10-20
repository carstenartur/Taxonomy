package com.taxonomy.exchange.backup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Base64;

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

    @Test void partialConsumersCannotAcceptInvalidHashesOrUnconsumedGzipTails() throws Exception {
        String packed = PortableJsonPayloads.pack("Complete evidence 水位\n".repeat(20_000), LIMIT);
        String[] parts = packed.split(":", 5);
        byte[] gzip = Base64.getDecoder().decode(parts[4]);
        byte[] trailing = Arrays.copyOf(gzip, gzip.length + 1);
        byte[] joined = new byte[gzip.length * 2];
        System.arraycopy(gzip, 0, joined, 0, gzip.length);
        System.arraycopy(gzip, 0, joined, gzip.length, gzip.length);
        byte[] badCrc = gzip.clone(); badCrc[badCrc.length - 8] ^= 1;
        byte[] badSize = gzip.clone(); badSize[badSize.length - 4] ^= 1;
        for (byte[] broken : java.util.List.of(trailing, joined, badCrc, badSize)) {
            String framed = String.join(":", parts[0], parts[1], parts[2], parts[3], Base64.getEncoder().encodeToString(broken));
            assertThatThrownBy(() -> PortableJsonPayloads.read(framed, LIMIT, reader -> reader.read()))
                    .isInstanceOf(IOException.class);
        }
        for (String broken : java.util.List.of(
                packed.replace(":v1:", ":v9:"),
                String.join(":", parts[0], parts[1], "0" + parts[2], parts[3], parts[4]),
                String.join(":", parts[0], parts[1], parts[2], "0".repeat(64), parts[4]),
                packed + ":ignored", packed.substring(0, packed.length() - 4))) {
            assertThatThrownBy(() -> PortableJsonPayloads.read(broken, LIMIT, reader -> {
                reader.close(); // JSON parsers may close their supplied input after the root value.
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
        String emptyHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        String broken = String.join(":", fields[0], fields[1], fields[2], emptyHash, fields[4]);
        assertThatThrownBy(() -> PortableJsonPayloads.read(broken, LIMIT, reader -> {
            try { reader.transferTo(Writer.nullWriter()); }
            catch (IOException ignored) { /* A caller cannot turn a failed validation into accepted data. */ }
            return "candidate";
        })).isInstanceOf(IOException.class);
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

    private static String readAll(String stored) throws IOException {
        return PortableJsonPayloads.read(stored, LIMIT, reader -> {
            var output = new StringWriter(); reader.transferTo(output); return output.toString();
        });
    }
}
