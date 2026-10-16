package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.snapshot.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;

import java.time.Instant;
import java.util.*;

/** Authored vectors exercise the real frozen cache and an independent filtered Lucene index. */
final class FrozenEmbeddingPrecisionChecks {
    static void positiveSubPercentCosineRemainsPositive() throws Exception {
        var source = new CatalogueSourceIdentity("fixture-repo", "fixture-workspace", "draft", "fixture-commit");
        var model = new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64), Map.of(), "query: ",
                EmbeddingModelIdentity.INFERENCE_VERSION);
        var metadata = new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null);
        var unused = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        var provenance = new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001",
                Instant.EPOCH, unused, unused, unused);
        var root = node("CP", null); var child = node("fixture-child", "CP");
        var snapshot = new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, "CP",
                List.of(RootCatalogueSnapshot.Node.capture(root, metadata, false),
                        RootCatalogueSnapshot.Node.capture(child, metadata, false)),
                new CatalogueOverlayService.OverlayMetadata(false, "frozen", "fixture", "v1", "digest", 1), provenance);
        snapshot = snapshot.withEmbeddings(new RootEmbeddingSnapshot(RootEmbeddingSnapshot.SCHEMA_VERSION, source,
                "CP", source.sourceCommit(), RootEmbeddingSnapshot.TEXT_VERSION, model,
                Map.of("CP", "root vector", "fixture-child", "child vector")));
        float[] query = new float[384]; query[0] = 1;
        float[] small = new float[384]; small[0] = .00125f; small[1] = 1;
        var service = new LocalEmbeddingService() {
            @Override public float[] embedQuery(String text) { return query.clone(); }
            @Override public float[] embedDocument(String text) { return (text.equals("root vector") ? query : small).clone(); }
        };
        var owner = new CatalogueSnapshotService(null, null, new CatalogueRuntimePolicy("worker", "CP"), null);
        try (var scope = owner.bind(source, Set.of("CP"), List.of(snapshot))) {
            var view = FrozenCatalogueContext.current(); var cache = new FrozenEmbeddingCache();
            var actual = cache.score(view, model, "fixture requirement", view.allNodes(), service, 16);
            var expected = lucene(Map.of("CP", query, "fixture-child", small, "unselected", query),
                    List.of("CP", "fixture-child"), query);
            if (!actual.equals(expected)) throw new AssertionError("Frozen and filtered Lucene scores differ: " + actual + " / " + expected);
            if (!(actual.get("fixture-child") > 0 && actual.get("fixture-child") < 1))
                throw new AssertionError("Positive sub-percent evidence was rounded away: " + actual);
            if (!cache.score(view, model, "fixture requirement", view.allNodes(), service, 16).equals(actual)
                    || cache.statistics().vectors() != 2)
                throw new AssertionError("Cache reuse changed scores or vector count");
        }
    }

    private static TaxonomyNode node(String code, String parent) {
        var node = new TaxonomyNode(); node.setCode(code); node.setTaxonomyRoot("CP");
        node.setParentCode(parent); node.setNameEn("Authored " + code); node.setLevel(parent == null ? 0 : 1);
        return node;
    }

    /** Independent index path; never calls the production cache's score-conversion implementation. */
    static Map<String, Double> lucene(Map<String, float[]> vectors, List<String> selected, float[] query) throws Exception {
        try (var directory = new ByteBuffersDirectory()) {
            try (var writer = new IndexWriter(directory, new IndexWriterConfig())) {
                for (var item : vectors.entrySet()) {
                    var document = new Document();
                    document.add(new StringField("code", item.getKey(), Field.Store.YES));
                    document.add(new KnnFloatVectorField("embedding", item.getValue(), VectorSimilarityFunction.COSINE));
                    writer.addDocument(document);
                }
            }
            try (var reader = DirectoryReader.open(directory)) {
                var searcher = new IndexSearcher(reader);
                var filter = new TermInSetQuery("code", selected.stream().map(BytesRef::new).toList());
                var hits = searcher.search(new KnnFloatVectorQuery("embedding", query, selected.size(), filter), selected.size());
                var result = new LinkedHashMap<String, Double>();
                for (var hit : hits.scoreDocs) result.put(searcher.storedFields().document(hit.doc).get("code"),
                        Math.max(0, Math.min(100, (2.0 * hit.score - 1.0) * 100.0)));
                return result;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        positiveSubPercentCosineRemainsPositive();
        System.out.println("FROZEN_SUB_PERCENT_LUCENE_OK");
    }
}
