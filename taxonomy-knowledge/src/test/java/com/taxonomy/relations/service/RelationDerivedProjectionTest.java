package com.taxonomy.relations.service;

import com.taxonomy.workspace.service.DerivedProjection;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import static com.taxonomy.workspace.service.DerivedProjection.Kind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationDerivedProjectionTest {
    final RepositoryContext context = RepositoryContext.workspace("repo", "working-copy", "draft", "actor");
    final String head = "a".repeat(40);
    final RelationProjectionOperationsService relations = mock(RelationProjectionOperationsService.class);
    final BranchGraphVectorStore store = mock(BranchGraphVectorStore.class);
    final BranchGraphVectors vectors = mock(BranchGraphVectors.class);
    final BranchGraphVectorAuthority authority = mock(BranchGraphVectorAuthority.class);
    final RelationDerivedProjection adapter = new RelationDerivedProjection(relations, authority, store, vectors, new ObjectMapper());

    @Test void purgeRepeatsOnlyTheOriginalGenerationAndVerifiesAuthorityAtTheDelete() {
        when(store.currentGeneration(context)).thenReturn("retained", "retained", null, "replacement");
        var original = adapter.capture(context, VECTOR_PURGE, head);
        assertThat(original.generation()).isEqualTo("retained");
        when(store.purge(eq(context), eq("retained"), any())).thenAnswer(call -> {
            call.getArgument(2, Runnable.class).run(); return true;
        });
        Runnable check = mock(Runnable.class);
        var result = adapter.apply(context, VECTOR_PURGE, head, original, "op", check);
        assertThat(adapter.readResult(adapter.describe(result))).isEqualTo(result);
        assertThat(adapter.apply(context, VECTOR_PURGE, head, original, "op", check)).isEqualTo(result);
        assertThatThrownBy(() -> adapter.apply(context, VECTOR_PURGE, head, original, "op", check))
                .isInstanceOf(DerivedProjection.SourceChanged.class);
        verify(store).purge(eq(context), eq("retained"), any());
        verify(check).run();
    }

    @Test void capturedModelAndGenerationAreHandedToTheSameVectorWriterAfterRestart() {
        var source = mock(BranchGraphVectorSource.class);
        when(source.commit()).thenReturn(head);
        when(source.key()).thenReturn("retained-model-key");
        when(authority.capture(context)).thenReturn(new BranchGraphVectorAuthority.Capture(source, null, java.util.Set.of(), "model"));
        when(store.currentGeneration(context)).thenReturn("before");
        var captured = adapter.capture(context, VECTOR_BUILD, head);
        var token = UUID.nameUUIDFromBytes("operation".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var status = new BranchGraphVectors.Status("READY", token, head, "original", 12, 3);
        Runnable check = mock(Runnable.class);
        when(vectors.rebuild(context, head, "retained-model-key", "before", token, check)).thenReturn(status);
        var result = adapter.apply(context, VECTOR_BUILD, head, captured, "operation", check);
        assertThat(result).isEqualTo(new DerivedProjection.Result(token, "original", 12, 3, null, null, null));
        assertThat(adapter.readResult(adapter.describe(result))).isEqualTo(result);
        when(source.commit()).thenReturn("b".repeat(40));
        assertThatThrownBy(() -> adapter.capture(context, VECTOR_BUILD, head)).isInstanceOf(DerivedProjection.HeadChanged.class);
    }

    @Test void GitRelationRebuildRetainsReconciliationCountsAndPreservesTypedServiceFailures() throws Exception {
        var original = adapter.capture(context, RELATION, head);
        var operation = mock(RelationProjectionOperationsService.RebuildOperation.class, RETURNS_DEEP_STUBS);
        when(operation.rebuild().relationCount()).thenReturn(4);
        when(operation.reconciliation().recoveredCount()).thenReturn(2);
        when(operation.reconciliation().supersededCount()).thenReturn(1);
        when(operation.reconciliation().remainingPendingCount()).thenReturn(3);
        when(relations.rebuild(context, head)).thenReturn(operation).thenThrow(new java.io.IOException("unavailable"))
                .thenThrow(new com.taxonomy.workspace.service.BranchHeadConflictException("draft", head, null, "branch deleted"));
        Runnable check = mock(Runnable.class);
        var result = adapter.apply(context, RELATION, head, original, "op", check);
        assertThat(result).isEqualTo(new DerivedProjection.Result(null, null, 0, 4, 2, 1, 3));
        verify(check).run();
        assertThatThrownBy(() -> adapter.apply(context, RELATION, head, original, "op", check))
                .isInstanceOf(DerivedProjection.SourceUnavailable.class);
        assertThatThrownBy(() -> adapter.apply(context, RELATION, head, original, "op", check))
                .isInstanceOf(DerivedProjection.HeadChanged.class);
    }
}
