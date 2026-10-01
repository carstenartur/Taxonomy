package com.taxonomy.exchange.backup;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PortableRowsInstantTest {
    public record Receipt(Instant recordedAt) { }
    public record Evidence(List<Receipt> receipts) { }
    @Test void nestedReceiptInstantsArePortableUtcStringsIncludingNanoseconds() throws Exception {
        var timestamps = List.of(Instant.parse("1969-12-31T23:59:59.123456789Z"), Instant.parse("2026-10-01T01:02:03Z"));
        var values = new ArrayList<String>();
        PortableRows.document((path, input) -> {
                    byte[] bytes = input.readAllBytes(); values.add(new String(bytes, StandardCharsets.UTF_8));
                    return new com.taxonomy.backup.BackupEntry(path, bytes.length, "0".repeat(64));
                },
                "data/interop/receipt.json", new Evidence(timestamps.stream().map(Receipt::new).toList()));
        var tree = PortableRows.json().readTree(values.getFirst());
        assertThat(tree.path("receipts").get(0).path("recordedAt").asText()).isEqualTo(timestamps.get(0).toString());
        assertThat(tree.path("receipts").get(1).path("recordedAt").asText()).isEqualTo(timestamps.get(1).toString());
    }
}
