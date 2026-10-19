package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ScenarioJsonFingerprintTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void streamingRetainsTheExistingFingerprintForEveryJsonValueAndCodeUnit() {
        var root = JSON.createObjectNode();
        root.put("source", "Quelle 水位 🌊 e\u0301 \" \\ \n \u0000 \uD800 x \uDC00");
        root.put("floating", -0.0); root.put("integer", Long.MAX_VALUE);
        root.put("decimal", new java.math.BigDecimal("1.2300000000000000000000000000E+101"));
        root.putNull("absent"); root.put("binary", new byte[]{0, 1, -1});
        root.putArray("ordered").add(true).add(false).add(7).addObject().put("leaf", "retained");
        for (var value : java.util.List.of(root, root.get("source"), root.get("floating"), root.get("decimal"),
                root.get("integer"), root.get("absent"), root.get("binary"), root.get("ordered"))) {
            assertThat(ScenarioJsonFingerprint.sha256(value)).isEqualTo(ScenarioLlmPlayback.sha256(value.toString()));
        }
    }

    @Test void actualAuthoredFixtureAndAnyEvidenceMutationRemainBoundToTheSameFullContent() throws Exception {
        var fixture = ScenarioLlmPlayback.flood().fixture();
        String original = ScenarioLlmPlayback.sha256(fixture.toString());
        assertThat(ScenarioJsonFingerprint.sha256(fixture)).isEqualTo(original);
        var changed = fixture.deepCopy();
        ((tools.jackson.databind.node.ObjectNode)changed).put("newEvidence", "a changed field");
        assertThat(ScenarioJsonFingerprint.sha256(changed)).isNotEqualTo(original);
        assertThat(ScenarioJsonFingerprint.sha256(changed)).isEqualTo(ScenarioLlmPlayback.sha256(changed.toString()));
    }

    @Test void repeatedLateSizedSnapshotsDoNotAllocateFullSerializedCopiesForTheirHashes() {
        var snapshot = JSON.valueToTree(Map.of("summary", Map.of("id", "immutable"), "analysis",
                Map.of("reasons", Map.of("CP", "Original source — 水位 🌊; quoted evidence.\n".repeat(300_000)))));
        String expected = ScenarioLlmPlayback.sha256(snapshot.toString());
        assertThat(ScenarioJsonFingerprint.sha256(snapshot)).isEqualTo(expected);
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        assertThat(bean.isThreadAllocatedMemorySupported()).isTrue();
        boolean enabled = bean.isThreadAllocatedMemoryEnabled();
        if (!enabled) bean.setThreadAllocatedMemoryEnabled(true);
        try {
            // Warm both serializer paths before counting allocations. No GC or heap/guard override.
            ScenarioJsonFingerprint.sha256(snapshot); ScenarioLlmPlayback.sha256(snapshot.toString());
            long before = bean.getCurrentThreadAllocatedBytes();
            for (int i=0;i<3;i++) assertThat(ScenarioJsonFingerprint.sha256(snapshot)).isEqualTo(expected);
            long streaming = bean.getCurrentThreadAllocatedBytes()-before;
            before = bean.getCurrentThreadAllocatedBytes();
            for (int i=0;i<3;i++) assertThat(ScenarioLlmPlayback.sha256(snapshot.toString())).isEqualTo(expected);
            long materialized = bean.getCurrentThreadAllocatedBytes()-before;
            assertThat(streaming).as("Bounded streaming fingerprints must retain full evidence without whole-document copies")
                    .isLessThan(materialized/10);
        } finally { if (!enabled) bean.setThreadAllocatedMemoryEnabled(false); }
    }
}
