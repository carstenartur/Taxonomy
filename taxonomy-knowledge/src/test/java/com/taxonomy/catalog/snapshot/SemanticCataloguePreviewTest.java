package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SemanticCataloguePreviewTest {
    private final LocalEmbeddingService model = mock(LocalEmbeddingService.class);
    private final SemanticCataloguePreview service = new SemanticCataloguePreview(model);
    private final List<RootCatalogueSnapshot> rows = List.of(CatalogueSnapshotServiceTest.snapshot(
            new CatalogueSourceIdentity("repo", "work", "main", "one"), "UA", "Office", false));
    private final CataloguePreviewIndex index = new CataloguePreviewIndex(rows);

    @AfterEach void close() {
        service.stop();
    }

    @Test void scoresEachEditedQueryAgainstOneCompleteGenerationAndDiscardsChangedModel() throws Exception {
        when(model.isAvailable()).thenReturn(true);
        var identity = new AtomicReference<>("model A");
        when(model.embeddingIndexKey()).thenAnswer(call -> identity.get());
        var build = new CountDownLatch(1);
        when(model.embedDocument(anyString())).thenAnswer(call -> {
            assertThat(build.await(5, TimeUnit.SECONDS)).isTrue();
            String text = call.getArgument(0);
            return text.startsWith("Office") ? axis(0) : axis(1);
        });
        when(model.embedQuery("first")).thenReturn(axis(0));
        when(model.embedQuery("edited")).thenReturn(axis(1));

        assertThat(service.query(index, rows, "first", Set.of())).isEmpty();
        assertThat(service.query(index, rows, "edited", Set.of())).isEmpty();
        verify(model, never()).embedQuery(anyString());
        build.countDown();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(service.query(index, rows, "first", Set.of())).hasValueSatisfying(result ->
                        assertThat(result.matches()).extracting(CataloguePreviewIndex.Match::code).containsExactly("UA")));
        var edited = service.query(index, rows, "edited", Set.of()).orElseThrow();
        assertThat(edited.matches()).extracting(CataloguePreviewIndex.Match::code).containsExactly("shared-node");
        assertThat(edited.ancestors()).extracting(CataloguePreviewIndex.Match::code).containsExactly("UA");
        verify(model, times(2)).embedDocument(anyString());
        identity.set("model B");
        assertThat(service.query(index, rows, "edited", Set.of())).isEmpty();
        verify(model).embedQuery("edited");
    }

    @Test void failedPreparationCannotPublishPartialVectorsOrImmediatelyRetryOnEveryKeystroke() throws Exception {
        when(model.isAvailable()).thenReturn(true);
        when(model.embeddingIndexKey()).thenReturn("model");
        when(model.embedDocument(anyString())).thenReturn(axis(0)).thenThrow(new IllegalStateException());
        assertThat(service.query(index, rows, "first", Set.of())).isEmpty();
        verify(model, timeout(3000).times(2)).embedDocument(anyString());
        assertThat(service.query(index, rows, "second", Set.of())).isEmpty();
        verify(model, never()).embedQuery(anyString());
    }

    @Test void rejectsInvalidQueryWithoutReportingAnEmptySemanticAssessment() throws Exception {
        when(model.isAvailable()).thenReturn(true);
        when(model.embeddingIndexKey()).thenReturn("model");
        when(model.embedDocument(anyString())).thenReturn(axis(0));
        when(model.embedQuery("ready")).thenReturn(axis(0));
        when(model.embedQuery("invalid")).thenReturn(new float[384]);
        service.prepare(index, rows);
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.query(index, rows, "ready", Set.of()).isPresent());
        assertThat(service.query(index, rows, "invalid", Set.of())).isEmpty();
    }

    private static float[] axis(int i) {
        float[] vector = new float[384];
        vector[i] = 1;
        return vector;
    }

    @Test void disabledModelDoesNoWork() throws Exception {
        assertThat(service.query(index, rows, "first", Set.of())).isEmpty();
        verify(model).isAvailable();
        verifyNoMoreInteractions(model);
    }
}
