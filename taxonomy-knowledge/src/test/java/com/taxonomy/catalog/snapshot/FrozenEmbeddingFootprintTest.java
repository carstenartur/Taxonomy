package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.service.NodeEmbeddingText;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Measured worker data footprint, not a full Spring pod or native ONNX model benchmark. */
class FrozenEmbeddingFootprintTest {
    @Test
    @SuppressWarnings("unchecked")
    void measuresRootAndFullCandidateCachesInSeparateFreshJvms() throws Exception {
        var mapper = new ObjectMapper();
        var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), true, "classpath:data/nato-taxonomy.json");
        byte[] workbookBytes;
        try (var input = new ClassPathResource("data/C3_Taxonomy_Catalogue_25AUG2025.xlsx").getInputStream()) {
            workbookBytes = input.readAllBytes();
        }
        TaxonomyService.WorkbookNodes hierarchy;
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            hierarchy = TaxonomyService.readWorkbookNodes(workbook);
        }
        var nodes = hierarchy.nodes();
        overlay.applyAndValidate(nodes, hierarchy.uuidToCode(), "classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
        String workbookHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workbookBytes));
        var source = new CatalogueSourceIdentity("fixture-repository", null, "fixture-branch", "a".repeat(40));
        var provenance = new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, workbookHash, workbookBytes.length),
                new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, overlay.getOverlayMetadata().sha256(), 0),
                new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0));
        List<RootCatalogueSnapshot> snapshots = new ArrayList<>();
        for (TaxonomyNode rootNode : hierarchy.virtualRoots()) {
            String root = rootNode.getCode();
            var selected = nodes.values().stream().filter(node -> root.equals(node.getTaxonomyRoot())).toList();
            Map<String, String> texts = new LinkedHashMap<>();
            selected.forEach(node -> texts.put(node.getCode(), NodeEmbeddingText.buildEnrichedText(node)));
            snapshots.add(new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, root,
                    selected.stream().map(node -> RootCatalogueSnapshot.Node.capture(node,
                            overlay.getNodeMetadata(node.getCode()), overlay.hasParentPatch(node.getCode()))).toList(),
                    overlay.getOverlayMetadata(), provenance).withEmbeddings(new RootEmbeddingSnapshot(
                    RootEmbeddingSnapshot.SCHEMA_VERSION, source, root, source.sourceCommit(), RootEmbeddingSnapshot.TEXT_VERSION,
                    FrozenEmbeddingFootprintProbe.MODEL, texts)));
        }
        Path output = Path.of("target", "frozen-embedding-footprint");
        Files.createDirectories(output);
        Map<String, Object> measurements = new LinkedHashMap<>();
        List<List<RootCatalogueSnapshot>> selections = new ArrayList<>();
        snapshots.forEach(root -> selections.add(List.of(root)));
        selections.add(snapshots);
        for (var selection : selections) {
            String label = selection.size() == 1 ? selection.getFirst().rootCode() : "ALL";
            Path input = output.resolve(label + "-input.json").toAbsolutePath();
            Path result = output.resolve(label + "-result.json").toAbsolutePath();
            Files.writeString(input, mapper.writeValueAsString(selection));
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xms128m", "-Xmx256m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
                    FrozenEmbeddingFootprintProbe.class.getName(), input.toString(), result.toString())
                    .redirectErrorStream(true).redirectOutput(output.resolve(label + "-process.log").toFile());
            process.environment().remove("JDK_JAVA_OPTIONS");
            process.environment().remove("JAVA_TOOL_OPTIONS");
            process.environment().remove("_JAVA_OPTIONS");
            Process running = process.start();
            boolean done = running.waitFor(30, TimeUnit.SECONDS);
            if (!done) running.destroyForcibly();
            assertThat(done).as("fresh JVM %s completed", label).isTrue();
            assertThat(running.exitValue()).as("fresh JVM %s exit; see %s", label, output).isZero();
            Map<String, Object> value = mapper.readValue(Files.readString(result), new TypeReference<>() { });
            assertThat(value.get("jvmInputArguments"))
                    .as("fresh JVM %s uses only the declared measurement options", label)
                    .isEqualTo(List.of("-Xms128m", "-Xmx256m", "-XX:+UseSerialGC"));
            assertThat(((Number) value.get("cachedVectors")).intValue())
                    .isEqualTo(selection.stream().mapToInt(root -> root.nodes().size()).sum());
            measurements.put(label, value);
        }
        Map<String, Object> cp = (Map<String, Object>) measurements.get("CP");
        Map<String, Object> all = (Map<String, Object>) measurements.get("ALL");
        assertThat(((Number) cp.get("cachedVectorPayloadBytes")).longValue())
                .isLessThan(((Number) all.get("cachedVectorPayloadBytes")).longValue());
        assertThat(((Number) cp.get("populatedRetainedHeapDeltaBytes")).longValue())
                .isLessThan(((Number) all.get("populatedRetainedHeapDeltaBytes")).longValue());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("measurement", "Fresh JVM retained heap after GC for frozen worker snapshot binding and actual candidate cache; deterministic 384D fixture vectors, no native model or Spring/broker/database startup claim");
        evidence.put("fixtureRelations", "Explicit empty relation set for footprint fixture only; not production relation provenance or ONNX quality evidence");
        evidence.put("jvmFlags", List.of("-Xms128m", "-Xmx256m", "-XX:+UseSerialGC"));
        evidence.put("workbookSha256", workbookHash);
        evidence.put("overlaySha256", overlay.getOverlayMetadata().sha256());
        evidence.put("measurements", measurements);
        Files.writeString(output.resolve("evidence.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
}
