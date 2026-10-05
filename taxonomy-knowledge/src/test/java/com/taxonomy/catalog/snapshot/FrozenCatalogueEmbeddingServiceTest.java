package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.EmbeddingModelIdentity;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.IdentitySnapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationIdentity;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenCatalogueEmbeddingServiceTest {
    private static final CatalogueSourceIdentity SOURCE = new CatalogueSourceIdentity("repo", "workspace", "branch", "a".repeat(40));
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace("repo", "workspace", "branch", "actor");
    private static final EmbeddingModelIdentity MODEL = new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64),
            Map.of(), "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
    private final RelationProjectionReadService relations = mock(RelationProjectionReadService.class);
    private final LocalEmbeddingService embeddings = mock(LocalEmbeddingService.class);
    private final FrozenCatalogueEmbeddingService service = new FrozenCatalogueEmbeddingService(relations, embeddings);

    @Test
    void freezesCanonicalScopedRelationsAndExactModelWhileReturningOnlyRequestedRoot() throws Exception {
        var identities = new LinkedHashSet<RelationIdentity>();
        identities.add(new RelationIdentity("IP", RelationType.SUPPORTS, "CP"));
        identities.add(new RelationIdentity("CP", RelationType.SUPPORTS, "UA"));
        identities.add(new RelationIdentity("CP", RelationType.REALIZES, "IP"));
        when(relations.readIdentitySnapshot(CONTEXT)).thenReturn(ready(identities));
        when(embeddings.embeddingIdentity()).thenReturn(MODEL);

        var result = service.decorate(CONTEXT, SOURCE, roots(), Set.of("CP"));
        assertThat(result).singleElement().satisfies(cp -> {
            assertThat(cp.embeddings().source()).isEqualTo(SOURCE);
            assertThat(cp.embeddings().relationCommit()).isEqualTo(SOURCE.sourceCommit());
            assertThat(cp.embeddings().model()).isEqualTo(MODEL);
            assertThat(cp.embeddings().nodeTexts()).containsExactlyEntriesOf(Map.of("CP",
                    "Frozen CP.\nDescription CP\nOutgoing: realizes Frozen IP, supports Frozen UA.\nIncoming: supports Frozen IP."));
        });
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsString(result.getFirst()), RootCatalogueSnapshot.class))
                .isEqualTo(result.getFirst());
        verify(relations, times(2)).readIdentitySnapshot(CONTEXT);
        verifyNoMoreInteractions(relations);
    }

    @Test
    void explicitlyEmptyProvenProjectionPreservesEmptyEnrichment() throws Exception {
        when(relations.readIdentitySnapshot(CONTEXT)).thenReturn(ready(Set.of()));
        when(embeddings.embeddingIdentity()).thenReturn(MODEL);
        var result = service.decorate(CONTEXT, SOURCE, roots(), Set.of("CP"));
        assertThat(result.getFirst().embeddings().nodeTexts()).containsEntry("CP", "Frozen CP.\nDescription CP");
    }

    @Test
    void missingStaleFallbackOrMovedProjectionCannotBeFrozenAsEmptyOrCurrent() throws Exception {
        when(embeddings.embeddingIdentity()).thenReturn(MODEL);
        for (IdentitySnapshot invalid : List.of(
                new IdentitySnapshot(ReadModel.LEGACY_FALLBACK, ReadinessState.NOT_BUILT, SOURCE.sourceCommit(), Set.of()),
                new IdentitySnapshot(ReadModel.PROJECTION, ReadinessState.STALE, SOURCE.sourceCommit(), Set.of()),
                new IdentitySnapshot(ReadModel.PROJECTION, ReadinessState.READY, "b".repeat(40), Set.of()))) {
            when(relations.readIdentitySnapshot(CONTEXT)).thenReturn(invalid);
            assertThatThrownBy(() -> service.decorate(CONTEXT, SOURCE, roots(), Set.of("CP")))
                    .isInstanceOf(IllegalStateException.class);
        }
        when(relations.readIdentitySnapshot(CONTEXT)).thenReturn(ready(Set.of()),
                new IdentitySnapshot(ReadModel.PROJECTION, ReadinessState.READY, "b".repeat(40), Set.of()));
        assertThatThrownBy(() -> service.decorate(CONTEXT, SOURCE, roots(), Set.of("CP")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void absentEndpointOrForeignAuthorityCannotReadMutableCatalogueAsFallback() throws Exception {
        when(embeddings.embeddingIdentity()).thenReturn(MODEL);
        when(relations.readIdentitySnapshot(CONTEXT)).thenReturn(ready(Set.of(
                new RelationIdentity("CP", RelationType.SUPPORTS, "absent-fixture-endpoint"))));
        assertThatThrownBy(() -> service.decorate(CONTEXT, SOURCE, roots(), Set.of("CP")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("endpoint");
        var foreign = new CatalogueSourceIdentity("other", "workspace", "branch", SOURCE.sourceCommit());
        assertThatThrownBy(() -> service.decorate(CONTEXT, foreign, roots(), Set.of("CP")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IdentitySnapshot ready(Set<RelationIdentity> identities) {
        return new IdentitySnapshot(ReadModel.PROJECTION, ReadinessState.READY, SOURCE.sourceCommit(), identities);
    }

    private static List<RootCatalogueSnapshot> roots() {
        List<RootCatalogueSnapshot> roots = new ArrayList<>();
        var metadata = new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null);
        for (CatalogueRoot root : CatalogueRoot.values()) {
            var node = new TaxonomyNode();
            node.setCode(root.name()); node.setTaxonomyRoot(root.name()); node.setNameEn("Frozen " + root);
            node.setDescriptionEn("Description " + root); node.setLevel(0);
            roots.add(new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, SOURCE, root.name(),
                    List.of(RootCatalogueSnapshot.Node.capture(node, metadata, false)),
                    new CatalogueOverlayService.OverlayMetadata(false, "frozen", "fixture", "v1", "digest", 1), CatalogueSnapshotServiceTest.provenance(null)));
        }
        return roots;
    }
}
