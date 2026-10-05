package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.snapshot.*;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenLocalEmbeddingTest {
    static final CatalogueSourceIdentity SOURCE = new CatalogueSourceIdentity("repo", "workspace", "branch", "commit");
    static final EmbeddingModelIdentity MODEL = new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64),
            Map.of(), "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);

    @Test
    void frozenScoringMatchesExistingFilteredLuceneCosineForTheSameVectors() throws Exception {
        var embeddings = new FixtureEmbeddings();
        var cp = snapshot(SOURCE, "CP", MODEL, "Capability text", "Outgoing: supports frozen target.");
        var scores = score(embeddings, cp);
        Map<String, float[]> vectors = Map.of("CP", embeddings.vector("Capability text"),
                "shared-node", embeddings.vector("Outgoing: supports frozen target."),
                "unrelated-node", embeddings.vector("unrelated"));
        assertThat(scores).isEqualTo(lucene(vectors, List.of("CP", "shared-node"), embeddings.vector("query: requirement")));
        assertThat(embeddings.texts).containsExactly("query: requirement", "Capability text", "Outgoing: supports frozen target.");
        score(embeddings, cp);
        assertThat(embeddings.texts).containsExactly("query: requirement", "Capability text", "Outgoing: supports frozen target.", "query: requirement");
    }

    @Test
    void concurrentOverlappingCodesCannotReuseDifferentRootsSourcesOrModelConfigurations() throws Exception {
        var embeddings = new FixtureEmbeddings();
        var cp = snapshot(SOURCE, "CP", MODEL, "CP root", "same text");
        var ip = snapshot(SOURCE, "IP", MODEL, "IP root", "same text");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cpResult = executor.submit(() -> score(embeddings, cp));
            var ipResult = executor.submit(() -> score(embeddings, ip));
            assertThat(cpResult.get()).containsOnlyKeys("CP", "shared-node");
            assertThat(ipResult.get()).containsOnlyKeys("IP", "shared-node");
        }
        assertThat(embeddings.texts.stream().filter("same text"::equals)).hasSize(2);
        for (var changedSource : List.of(
                new CatalogueSourceIdentity("other-repo", "workspace", "branch", "commit"),
                new CatalogueSourceIdentity("repo", "other-workspace", "branch", "commit"),
                new CatalogueSourceIdentity("repo", "workspace", "other-branch", "commit"),
                new CatalogueSourceIdentity("repo", "workspace", "branch", "other-commit"))) {
            score(embeddings, snapshot(changedSource, "CP", MODEL, "CP root", "same text"));
        }
        assertThat(embeddings.texts.stream().filter("same text"::equals)).hasSize(6);
        var changedModel = new EmbeddingModelIdentity("c".repeat(64), MODEL.tokenizerSha256(), Map.of(),
                "new-prefix: ", EmbeddingModelIdentity.INFERENCE_VERSION);
        embeddings.identity = changedModel;
        ReflectionTestUtils.setField(embeddings, "queryPrefix", "new-prefix: ");
        score(embeddings, snapshot(SOURCE, "CP", changedModel, "CP root", "same text"));
        assertThat(embeddings.texts.stream().filter("same text"::equals)).hasSize(7);
        assertThat(embeddings.texts).contains("new-prefix: requirement");
        assertThat(embeddings.frozenCacheStatistics().vectors()).isEqualTo(14);
    }

    @Test
    void modelMismatchMissingEvidenceForeignCandidatesAndInferenceFailuresNeverBecomeZeroScores() throws Exception {
        var embeddings = new FixtureEmbeddings();
        var cp = snapshot(SOURCE, "CP", MODEL, "CP root", "same text");
        embeddings.identity = new EmbeddingModelIdentity("d".repeat(64), MODEL.tokenizerSha256(), Map.of(),
                "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
        assertThatThrownBy(() -> score(embeddings, cp)).hasMessageContaining("model");
        assertThat(embeddings.texts).isEmpty();
        embeddings.identity = MODEL;
        assertThatThrownBy(() -> score(embeddings, cp.withEmbeddings(null))).hasMessageContaining("embedding");
        try (var ignored = bind(cp)) {
            var foreign = node("shared-node", "IP", "IP", "Injected caller text");
            assertThatThrownBy(() -> embeddings.scoreFrozenNodes("requirement", List.of(foreign)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        embeddings.fail = true;
        assertThatThrownBy(() -> score(embeddings, cp)).isInstanceOf(IllegalStateException.class);
        assertThat(embeddings.frozenCacheStatistics().vectors()).isZero();
    }

    @Test
    void preflightRejectsActiveTransactionsAndMissingEvidenceBeforeInference() {
        var embeddings = new FixtureEmbeddings();
        var cp = snapshot(SOURCE, "CP", MODEL, "CP root", "same text");
        try (var ignored = bind(cp.withEmbeddings(null))) {
            assertThatThrownBy(embeddings::validateFrozenModel).hasMessageContaining("embedding");
        }
        try (var ignored = bind(cp)) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(embeddings::validateFrozenModel).hasMessageContaining("transaction");
                assertThatThrownBy(() -> embeddings.scoreFrozenNodes("requirement", FrozenCatalogueContext.current().allNodes()))
                        .hasMessageContaining("transaction");
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
        assertThat(embeddings.texts).isEmpty();
    }

    @Test
    void boundedCacheEvictsOldEntriesAndReembedsWithoutChangingScores() throws Exception {
        var embeddings = new FixtureEmbeddings();
        ReflectionTestUtils.setField(embeddings, "frozenCacheMaxVectors", 1);
        var cp = snapshot(SOURCE, "CP", MODEL, "CP root", "same text");
        var first = score(embeddings, cp);
        assertThat(embeddings.frozenCacheStatistics().vectors()).isEqualTo(1);
        assertThat(embeddings.frozenCacheStatistics().vectorBytes()).isEqualTo(384L * Float.BYTES);
        assertThat(score(embeddings, cp)).isEqualTo(first);
        assertThat(embeddings.texts.stream().filter("same text"::equals)).hasSize(2);
    }

    static Map<String, Integer> score(FixtureEmbeddings service, RootCatalogueSnapshot snapshot) {
        try (var ignored = bind(snapshot)) {
            return service.scoreFrozenNodes("requirement", FrozenCatalogueContext.current().allNodes());
        }
    }

    static FrozenCatalogueContext.Scope bind(RootCatalogueSnapshot snapshot) {
        var repository = mock(TaxonomyNodeRepository.class);
        var overlay = new CatalogueOverlayService(new ObjectMapper(), new DefaultResourceLoader(), false, "unused");
        return new CatalogueSnapshotService(repository, overlay, new CatalogueRuntimePolicy("worker", snapshot.rootCode()), mock(CatalogueSourceJournal.class))
                .bind(snapshot.source(), Set.of(snapshot.rootCode()), List.of(snapshot));
    }

    static RootCatalogueSnapshot snapshot(CatalogueSourceIdentity source, String root,
                                           EmbeddingModelIdentity model, String rootText, String childText) {
        var metadata = new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null);
        var snapshot = new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, root,
                List.of(RootCatalogueSnapshot.Node.capture(node(root, root, null, "root"), metadata, false),
                        RootCatalogueSnapshot.Node.capture(node("shared-node", root, root, "child"), metadata, false)),
                new CatalogueOverlayService.OverlayMetadata(false, "frozen", "fixture", "v1", "digest", 1), provenance());
        return snapshot.withEmbeddings(new RootEmbeddingSnapshot(RootEmbeddingSnapshot.SCHEMA_VERSION, source,
                root, source.sourceCommit(), RootEmbeddingSnapshot.TEXT_VERSION, model,
                Map.of(root, rootText, "shared-node", childText)));
    }

    static CatalogueSourceJournal.Snapshot provenance() {
        var input = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        return new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH, input, input, input);
    }

    static TaxonomyNode node(String code, String root, String parent, String title) {
        var node = new TaxonomyNode();
        node.setCode(code); node.setTaxonomyRoot(root); node.setParentCode(parent); node.setNameEn(title);
        node.setLevel(parent == null ? 0 : 1);
        return node;
    }

    static class FixtureEmbeddings extends LocalEmbeddingService {
        final List<String> texts = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile EmbeddingModelIdentity identity = MODEL;
        boolean fail;
        FixtureEmbeddings() {
            ReflectionTestUtils.setField(this, "embeddingEnabled", true);
            ReflectionTestUtils.setField(this, "queryPrefix", "query: ");
            ReflectionTestUtils.setField(this, "catalogueRuntimePolicy", new CatalogueRuntimePolicy("worker", "CP,IP"));
        }
        @Override public EmbeddingModelIdentity embeddingIdentity() { return identity; }
        @Override public float[] embed(String text) {
            if (fail) throw new IllegalStateException("Fixture inference failed");
            texts.add(text);
            return vector(text);
        }
        float[] vector(String text) {
            var random = new java.util.Random(text.hashCode());
            var vector = new float[384];
            for (int i = 0; i < vector.length; i++) vector[i] = random.nextFloat();
            return vector;
        }
    }

    static Map<String, Integer> lucene(Map<String, float[]> vectors, List<String> selected, float[] query) throws Exception {
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
                var result = new LinkedHashMap<String, Integer>();
                for (var hit : hits.scoreDocs) {
                    result.put(searcher.storedFields().document(hit.doc).get("code"),
                            Math.max(0, Math.min(100, (int) Math.round((2.0 * hit.score - 1.0) * 100.0))));
                }
                return result;
            }
        }
    }
}
