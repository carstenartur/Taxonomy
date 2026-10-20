package com.taxonomy.exchange.backup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class PortableJsonPayloadsTest {
    private static final int LIMIT = 24_000_000;

    @Test void largeEvidenceFitsOnePortableRecordAndRetainsEveryOriginalCodeUnit() throws Exception {
        String original = "{\"evidence\":\"" + "Quelle 水位 🌊 e\u0301; ".repeat(900_000) + "\"}";
        assertThat(original.length()).isGreaterThan(12_000_000);
        String packed = PortableJsonPayloads.pack(original, LIMIT);
        assertThat(packed.length()).isLessThan(PortableRows.MAX_RECORD_BYTES / 2);
        assertThat(PortableJsonPayloads.unpack(packed, LIMIT)).isEqualTo(original);
        // Code units also survive unmatched surrogate units from a historical Java String.
        String units = "\uD800\u0000\uDC00".repeat(30_000);
        assertThat(PortableJsonPayloads.unpack(PortableJsonPayloads.pack(units, LIMIT), LIMIT)).isEqualTo(units);
        PortableRows.document((path, input) -> {
            byte[] bytes = input.readAllBytes();
            assertThat(bytes.length).isLessThan(PortableRows.MAX_RECORD_BYTES);
            var record = PortableRows.json().readTree(bytes);
            assertThat(PortableJsonPayloads.unpack(record.path("resultJson").asText(), LIMIT)).isEqualTo(original);
            return new com.taxonomy.backup.BackupEntry(path, bytes.length, "0".repeat(64));
        }, "data/analysis/continuations.ndjson", new Evidence(packed));
    }

    @Test void plainLegacySmallAndNullFieldsRemainCompatible() throws Exception {
        String original = "  {\"evidence\":\"original\"}\n";
        assertThat(PortableJsonPayloads.pack(original, LIMIT)).isEqualTo(original);
        assertThat(PortableJsonPayloads.unpack(original, LIMIT)).isEqualTo(original);
        assertThat(PortableJsonPayloads.pack(null, LIMIT)).isNull();
        assertThat(PortableJsonPayloads.unpack(null, LIMIT)).isNull();
    }

    @Test void expandedAndStoredLimitsAreCheckedBeforeDecompression() throws Exception {
        String original = "x".repeat(100_000);
        String packed = PortableJsonPayloads.pack(original, LIMIT);
        assertThatThrownBy(() -> PortableJsonPayloads.pack(original, original.length() - 1)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PortableJsonPayloads.unpack(original, original.length() - 1)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PortableJsonPayloads.unpack(packed, original.length() - 1)).isInstanceOf(IOException.class);
        assertThat(PortableJsonPayloads.unpack(packed, original.length())).isEqualTo(original);
    }

    @Test void versionsCountsChecksumsAndGzipFramingAreVerified() throws Exception {
        String packed = PortableJsonPayloads.pack("original evidence\n".repeat(10_000), LIMIT);
        String[] parts = packed.split(":", 5);
        assertThat(parts).hasSize(5);
        for (String broken : java.util.List.of(
                packed.replace(":v1:", ":v999:"),
                parts[0] + ":v1:0:" + parts[3] + ":" + parts[4],
                parts[0] + ":v1:999999999:" + parts[3] + ":" + parts[4],
                parts[0] + ":v1:" + parts[2] + ":" + "0".repeat(64) + ":" + parts[4],
                packed + ":ignored", packed.substring(0, packed.length() - 4))) {
            assertThatThrownBy(() -> PortableJsonPayloads.unpack(broken, LIMIT)).isInstanceOf(IOException.class);
        }
        byte[] gzip = Base64.getDecoder().decode(parts[4]);
        byte[] trailing = Arrays.copyOf(gzip, gzip.length + 1);
        byte[] concatenated = new byte[gzip.length * 2];
        System.arraycopy(gzip, 0, concatenated, 0, gzip.length);
        System.arraycopy(gzip, 0, concatenated, gzip.length, gzip.length);
        byte[] corruptCrc = gzip.clone(); corruptCrc[corruptCrc.length - 8] ^= 1;
        for (byte[] bytes : java.util.List.of(trailing, concatenated, corruptCrc)) {
            String broken = String.join(":", parts[0], parts[1], parts[2], parts[3], Base64.getEncoder().encodeToString(bytes));
            assertThatThrownBy(() -> PortableJsonPayloads.unpack(broken, LIMIT)).isInstanceOf(IOException.class);
        }
    }

    @Test void boundedCheckpointTransformationPreservesEvidenceAndTheInterruptFlag() throws Exception {
        String original = "retained evidence ".repeat(10_000);
        Thread.currentThread().interrupt();
        try {
            String packed = PortableJsonPayloads.pack(original,LIMIT);
            assertThat(PortableJsonPayloads.unpack(packed,LIMIT)).isEqualTo(original);
            assertThat(PortableJsonPayloads.unpack("{}",LIMIT)).isEqualTo("{}");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    public record Evidence(String resultJson) { }
}
