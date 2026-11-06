package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in companion to the existing product drivers; it validates operator evidence, not live product execution. */
@EnabledIfSystemProperty(named = "taxonomy.sparx.product.evidence", matches = "true")
class SparxQeaProductEvidenceIT {
    @Test void validatesExactNativeFilesAndRecordsTheProvenanceOfOptionalEaObservations() throws Exception {
        Path output = PackagedPluginSupport.repository().resolve("taxonomy-build/target/sparx-operator-evidence.json");
        Files.createDirectories(output.getParent());
        Files.deleteIfExists(output);
        String supplied = System.getProperty("taxonomy.sparx.product.manifest");
        assertNotNull(supplied, "An explicitly enabled evidence run requires taxonomy.sparx.product.manifest");
        assertFalse(supplied.isBlank(), "Operator manifest path must be explicit");
        var report = SparxQeaOperatorEvidence.validate(Path.of(supplied));
        assertEquals("NOT_EXECUTED", report.path("realProductStatus").asText(), "This harness must never claim it executed a proprietary product");
        Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    }
}
