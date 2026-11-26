package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService.NodeMetadata;
import com.taxonomy.catalog.service.CatalogueOverlayService.OverlayMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewVectorStoreTest {
    @TempDir Path directory;

    @Test
    void coldInstanceReadsExactDocumentVectorBitsInCanonicalOrder() {
        var bp = root("BP", "Collect materials");
        var ip = root("IP", "Collection report");
        var first = PreviewVectorStore.manifest(List.of(ip, bp), "model-A");
        var restart = PreviewVectorStore.manifest(List.of(bp, ip), "model-A");
        assertThat(first.key()).isEqualTo(restart.key());
        assertThat(first.documents().get("IP")).isEqualTo("Collection report.\nKommunaler Bericht");
        var vectors = Map.of("BP", vector(1f), "IP", vector(0.75f));
        new PreviewVectorStore(directory).write(first, vectors);
        var restored = new PreviewVectorStore(directory).read(restart).orElseThrow();
        assertThat(restored.get("BP")).isEqualTo(vectors.get("BP"));
        assertThat(restored.get("IP")).isEqualTo(vectors.get("IP"));
        assertThat(new PreviewVectorStore(directory).read(PreviewVectorStore.manifest(List.of(ip, bp), "model-B"))).isEmpty();
        assertThat(new PreviewVectorStore(directory).read(PreviewVectorStore.manifest(List.of(root("IP", "Changed"), bp), "model-A"))).isEmpty();
    }

    @Test
    void longEnglishDescriptionPreservesEarlyGermanEvidenceAndSingleLanguageText() {
        var base = root("BP", "Payroll");
        var detached = base.nodes().getFirst().detached();
        detached.setNameDe("Entgeltabrechnung");
        detached.setDescriptionEn("municipal ".repeat(140) + "last-token");
        detached.setDescriptionDe("Abrechnung für tarifliche Beschäftigte");
        var bilingual = withNode(base, detached);
        String text = PreviewVectorStore.manifest(List.of(bilingual), "model-A").documents().get("BP");
        assertThat(text).startsWith("Payroll.\nEntgeltabrechnung\n");
        assertThat(text.split("\\s+")).containsSubsequence("Abrechnung", "für", "tarifliche", "Beschäftigte");
        assertThat(text.substring(0, text.indexOf("Abrechnung für")).split("\\s+")).hasSizeLessThan(25);
        assertThat(text).endsWith("last-token");
        detached.setNameDe(null);
        detached.setDescriptionDe(null);
        assertThat(PreviewVectorStore.manifest(List.of(withNode(base, detached)), "model-A").documents().get("BP"))
                .isEqualTo(com.taxonomy.catalog.service.NodeEmbeddingText.globalReferenceText(detached));
    }

    private static RootCatalogueSnapshot withNode(RootCatalogueSnapshot source, TaxonomyNode node) {
        return new RootCatalogueSnapshot(2, source.source(), source.rootCode(),
                List.of(RootCatalogueSnapshot.Node.capture(node, source.nodes().getFirst().metadata(), false)),
                source.overlayMetadata(), source.catalogueProvenance());
    }

    @Test
    void corruptTruncatedAndTrailingBytesAreCacheMissesAndReplaceable() throws Exception {
        var manifest = PreviewVectorStore.manifest(List.of(root("BP", "Task")), "model-A");
        var store = new PreviewVectorStore(directory);
        var rows = Map.of("BP", vector(1f));
        store.write(manifest, rows);
        Path file;
        try (var files = Files.list(directory)) { file = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        var original = Files.readAllBytes(file);
        var corrupt = original.clone();
        corrupt[111] ^= 1;
        for (var invalid : List.of(corrupt, java.util.Arrays.copyOf(original, original.length - 1),
                java.util.Arrays.copyOf(original, original.length + 1))) {
            Files.write(file, invalid);
            assertThat(store.read(manifest)).isEmpty();
            store.write(manifest, rows);
            assertThat(Files.readAllBytes(file)).isEqualTo(original);
        }
    }

    @Test
    void incompleteFailedAndDisabledWritesLeaveUsableGenerationIntact() throws Exception {
        var manifest = PreviewVectorStore.manifest(List.of(root("BP", "Task")), "model-A");
        var store = new PreviewVectorStore(directory);
        var rows = Map.of("BP", vector(0.5f));
        store.write(manifest, rows);
        store.write(manifest, Map.of("BAD", vector(1f)));
        for (float[] invalid : List.of(vector(Float.NaN), vector(Float.POSITIVE_INFINITY), vector(0), new float[17])) {
            store.write(manifest, Map.of("BP", invalid));
            assertThat(store.read(manifest).orElseThrow().get("BP")).isEqualTo(rows.get("BP"));
        }
        Path blocked = Files.createFile(directory.resolve("regular-file"));
        for (var unavailable : List.of(new PreviewVectorStore(blocked), new PreviewVectorStore(null))) {
            unavailable.write(manifest, rows);
            assertThat(unavailable.read(manifest)).isEmpty();
        }
        try (var files = Files.list(directory)) {
            assertThat(files.map(Path::toString)).noneMatch(name -> name.endsWith(".part"));
        }
    }

    @Test
    void simultaneousPublishersLeaveOneWholeGenerationAndOnlyTwoDataSlotNames() throws Exception {
        var roots = List.of(root("IP", "Form"));
        var store = new PreviewVectorStore(directory);
        var manifests = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> PreviewVectorStore.manifest(roots, "model-" + i)).toList();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, manifests.size()).mapToObj(i -> executor.submit(() -> {
                start.await();
                store.write(manifests.get(i), Map.of("IP", vector(i + 1f)));
                return true;
            })).toList();
            start.countDown();
            for (var job : jobs) assertThat(job.get()).isTrue();
        }
        long visible = 0;
        for (int i = 0; i < manifests.size(); i++) {
            var restored = store.read(manifests.get(i));
            if (restored.isPresent()) {
                assertThat(restored.get().get("IP")).isEqualTo(vector(i + 1f));
                visible++;
            }
        }
        assertThat(visible).isBetween(1L, 2L);
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString())).allMatch(name -> name.matches("taxonomy-preview-v1-[01]\\.(lock|bin)"));
        }
    }

    private static float[] vector(float first) {
        float[] vector = new float[384];
        vector[0] = first;
        return vector;
    }

    private static RootCatalogueSnapshot root(String code, String title) {
        var node = new TaxonomyNode();
        node.setCode(code); node.setNameEn(title); node.setTaxonomyRoot(code); node.setNameDe("Kommunaler Bericht");
        var metadata = new NodeMetadata("CATEGORY", List.of(), 1, false, null);
        return new RootCatalogueSnapshot(2, new CatalogueSourceIdentity("repo", null, "main", "v1"), code,
                List.of(RootCatalogueSnapshot.Node.capture(node, metadata, false)),
                new OverlayMetadata(false, "preview", "test", "v1", null, 0), CatalogueSnapshotServiceTest.provenance(null));
    }
}
