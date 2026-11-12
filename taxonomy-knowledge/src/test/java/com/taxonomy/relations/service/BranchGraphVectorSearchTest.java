package com.taxonomy.relations.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchGraphVectorSearchTest {
    static final String HEAD = "1".repeat(40);
    final RepositoryContext context = RepositoryContext.centralRead("alpha","draft","alice");
    final BranchGraphVectorSource source = new BranchGraphVectorSource("alpha",null,"draft",HEAD,"edition",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),1);
    final BranchGraphVectorAuthority authority = mock(BranchGraphVectorAuthority.class);
    final BranchGraphVectorStore store = mock(BranchGraphVectorStore.class);
    final LocalEmbeddingService embeddings = mock(LocalEmbeddingService.class);
    final BranchGraphVectors branches = new BranchGraphVectors(store, authority, null, null, embeddings, JsonMapper.builder().build());

    @Test void nonemptyGraphReadsTheNativeBranchWithoutTouchingTheGlobalEntityManager() throws Exception {
        var sourceGate = mock(GraphSearchAuthorityService.class);
        var selected = new RelationProjectionReadService.CountResult(RelationProjectionReadService.ReadModel.PROJECTION,
                RelationBranchProjectionReadinessService.ReadinessState.READY, HEAD, 1);
        when(sourceGate.inspectForBranchSelection(context)).thenReturn(selected);
        var graph = new GraphSearchService(embeddings, sourceGate, branches);
        var captured = new BranchGraphVectorAuthority.Capture(source, null, Set.of(), "fixture-model");
        when(authority.capture(context)).thenReturn(captured);
        var query = vector();
        when(embeddings.embedQuery("medic")).thenReturn(query);
        when(store.read(context, source)).thenReturn(generation());
        var ready = new BranchGraphVectorStore.Status(BranchGraphVectorStore.State.READY,"gen",HEAD,source.modelKey(),1,1);
        when(store.status(context, source)).thenReturn(ready);
        var response = graph.graphSearch("medic",1, context);
        assertThat(response.getMatchedNodes()).extracting("code").containsExactly("CP-test");
        assertThat(response.getRelationCountByRoot()).containsEntry("CP",1L);
        assertThat(response.getTopRelationTypes()).containsEntry("SUPPORTS",1L);
        verify(sourceGate, never()).inspect(any());
    }

    @Test void anUnavailableOrReplacedGenerationCannotEscapeThroughGlobalFallback() throws Exception {
        var gate = mock(GraphSearchAuthorityService.class);
        when(gate.inspectForBranchSelection(context)).thenReturn(new RelationProjectionReadService.CountResult(
                RelationProjectionReadService.ReadModel.PROJECTION, RelationBranchProjectionReadinessService.ReadinessState.READY, HEAD, 1));
        var graph = new GraphSearchService(embeddings, gate, branches);
        var captured = new BranchGraphVectorAuthority.Capture(source, null, Set.of(), "fixture-model");
        when(authority.capture(context)).thenReturn(captured);
        when(store.read(context, source)).thenThrow(new SearchUnavailableException());
        assertThatThrownBy(() -> graph.graphSearch("medic",1,context)).isInstanceOf(SearchUnavailableException.class);
        verifyNoInteractions(embeddings);
        doReturn(generation()).when(store).read(context,source);
        when(embeddings.embedQuery("medic")).thenReturn(vector());
        when(store.status(context,source)).thenReturn(new BranchGraphVectorStore.Status(
                BranchGraphVectorStore.State.READY,"successor",HEAD,source.modelKey(),1,1));
        assertThatThrownBy(() -> graph.graphSearch("medic",1,context)).isInstanceOf(SearchUnavailableException.class);
        verify(gate, never()).inspect(any());
    }

    private BranchGraphVectorStore.Generation generation() throws Exception {
        try (var index = new BranchGraphVectorIndex.Builder(source.key())) {
            index.node("{\"code\":\"CP-test\"}","medical support",vector());
            index.relation("CP-test","IP-test","CP","SUPPORTS","supports infrastructure", vector());
            return new BranchGraphVectorStore.Generation("gen",index.finish());
        }
    }

    static float[] vector() { float[] values = new float[384]; values[0] = 1; return values; }
}
