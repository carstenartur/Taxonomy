package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.service.EmbeddingModelIdentity;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Fresh JVM probe of actual snapshot binding and production candidate cache; fixture vectors require no model download. */
public final class FrozenEmbeddingFootprintProbe {
    private static Object retained;

    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var type = new TypeReference<List<RootCatalogueSnapshot>>() { };
        mapper.readValue("[]", type);
        var embeddings = new FixtureVectors();
        long base = heap();
        long start = System.nanoTime();
        List<RootCatalogueSnapshot> snapshots = mapper.readValue(Files.readString(Path.of(args[0])), type);
        Set<String> roots = snapshots.stream().map(RootCatalogueSnapshot::rootCode).collect(Collectors.toSet());
        var service = new CatalogueSnapshotService(null, null,
                new CatalogueRuntimePolicy("worker", String.join(",", roots)), null);
        try (var ignored = service.bind(snapshots.getFirst().source(), roots, snapshots)) {
            retained = List.of(snapshots, embeddings, FrozenCatalogueContext.current());
            long snapshotHeap = heap();
            populate(embeddings);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            long populatedHeap = heap();
            var stats = embeddings.frozenCacheStatistics();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("roots", roots.stream().sorted().toList());
            result.put("nodes", snapshots.stream().mapToInt(root -> root.nodes().size()).sum());
            result.put("baselineHeapBytes", base);
            result.put("snapshotRetainedHeapDeltaBytes", snapshotHeap - base);
            result.put("populatedRetainedHeapDeltaBytes", populatedHeap - base);
            result.put("cachedVectors", stats.vectors());
            result.put("cachedVectorPayloadBytes", stats.vectorBytes());
            result.put("storage", "bounded candidate vector cache; no EntityManager or Lucene directory configured");
            result.put("bindAndPopulateMillis", elapsed);
            result.put("jvm", System.getProperty("java.runtime.version"));
            result.put("jvmInputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
            Files.writeString(Path.of(args[1]), mapper.writeValueAsString(result));
        }
        if (retained == null) throw new AssertionError("Probe must retain the measured worker state");
    }

    private static void populate(LocalEmbeddingService service) {
        service.validateFrozenModel();
        service.scoreFrozenNodes("fixture requirement", FrozenCatalogueContext.current().allNodes());
    }

    private static long heap() throws Exception {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(40);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static final EmbeddingModelIdentity MODEL = new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64), Map.of(),
            "fixture query: ", EmbeddingModelIdentity.INFERENCE_VERSION);

    private static final class FixtureVectors extends LocalEmbeddingService {
        @Override public boolean isAvailable() { return true; }
        @Override public EmbeddingModelIdentity embeddingIdentity() { return MODEL; }
        @Override public float[] embedQuery(String text) { return embed("fixture query: " + text); }
        @Override public float[] embed(String text) {
            var random = new java.util.Random(text.hashCode());
            var vector = new float[384];
            for (int i = 0; i < vector.length; i++) vector[i] = random.nextFloat();
            return vector;
        }
    }
}
