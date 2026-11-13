package com.taxonomy.relations.service;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.EmbeddingModelIdentity;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState.*;
import static com.taxonomy.relations.service.RelationProjectionReadService.ReadModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchGraphVectorAuthorityTest {
    private final RepositoryContext context = RepositoryContext.workspace("repo", "copy", "draft", "member");
    private static final String HEAD = "1".repeat(40);
    private static final String OTHER = "2".repeat(40);
    private static final RelationProjectionReadService.RelationIdentity EDGE =
            new RelationProjectionReadService.RelationIdentity("CP", RelationType.COMMUNICATES_WITH, "IP");
    private final RelationProjectionReadService relations = mock(RelationProjectionReadService.class);
    private final RelationBranchProjectionReadinessService readiness = mock(RelationBranchProjectionReadinessService.class);
    private final CatalogueSourceJournal catalogue = mock(CatalogueSourceJournal.class);
    private final CatalogueSnapshotService catalogues = mock(CatalogueSnapshotService.class);
    private final LocalEmbeddingService models = mock(LocalEmbeddingService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final BranchGraphVectorAuthority authority =
            new BranchGraphVectorAuthority(relations, readiness, catalogue, catalogues, models, json);
    private final EmbeddingModelIdentity model = new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64),
            Map.of(), "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
    private final CatalogueSourceJournal.Snapshot edition = catalogueEdition("11");

    @BeforeEach void readyBranch() throws Exception {
        when(relations.readIdentitySnapshot(context)).thenReturn(
                new RelationProjectionReadService.IdentitySnapshot(PROJECTION, READY, HEAD, Set.of(EDGE)));
        when(readiness.readCurrentHead(context)).thenReturn(HEAD);
        when(catalogue.current()).thenReturn(edition);
        when(catalogue.lockForCapture()).thenReturn(edition);
        when(catalogues.currentDefinition()).thenReturn(CatalogueDefinition.standard());
        when(models.embeddingIdentity()).thenReturn(model);
    }

    @Test void pinsTheSelectedCommitCatalogueModelAndCompleteRelationSetAtPublication() throws Exception {
        var capture = authority.capture(context);
        var expected = BranchGraphVectorSource.from(context, HEAD, edition.id(),
                StableIdentityHash.sha256(json.writeValueAsString(CatalogueDefinition.standard().spec())), model.indexKey(), Set.of(EDGE));
        assertThat(capture.source()).isEqualTo(expected);
        assertThat(capture.modelIdentity()).isEqualTo(json.writeValueAsString(model));
        assertThat(capture.relations()).containsExactly(EDGE);
        authority.requireHead(context, HEAD);
        authority.verifyPublication(context, expected);
        verify(catalogue).lockForCapture();
        verify(relations, times(2)).readIdentitySnapshot(context);
    }

    static Stream<RelationProjectionReadService.IdentitySnapshot> unavailableProjections() {
        return Stream.of(
            new RelationProjectionReadService.IdentitySnapshot(LEGACY_FALLBACK, NOT_BUILT, null, Set.of(EDGE)),
            new RelationProjectionReadService.IdentitySnapshot(PROJECTION, STALE, HEAD, Set.of()),
            new RelationProjectionReadService.IdentitySnapshot(PROJECTION, READY, HEAD, Set.of()),
            new RelationProjectionReadService.IdentitySnapshot(PROJECTION, READY, OTHER, Set.of(EDGE)));
    }

    @ParameterizedTest @MethodSource("unavailableProjections")
    void rejectsNonAuthoritativeOrEmptyProjectionBeforeOpeningTheModel(RelationProjectionReadService.IdentitySnapshot projection) throws Exception {
        when(relations.readIdentitySnapshot(context)).thenReturn(projection);
        assertThatThrownBy(() -> authority.capture(context)).isInstanceOf(SearchUnavailableException.class);
        verify(models, never()).embeddingIdentity();
    }

    @Test void missingCatalogueOrUnavailableModelCannotAuthorizeSearch() throws Exception {
        when(catalogue.current()).thenReturn(null);
        assertThatThrownBy(() -> authority.capture(context)).isInstanceOf(SearchUnavailableException.class);
        when(catalogue.current()).thenReturn(edition);
        when(models.embeddingIdentity()).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> authority.capture(context)).isInstanceOf(SearchUnavailableException.class);
    }

    @Test void rejectsChangedLockedEditionOrBranchHeadBeforePublishing() {
        var source = authority.capture(context).source();
        when(catalogue.lockForCapture()).thenReturn(null, catalogueEdition("22"), edition);
        assertThatThrownBy(() -> authority.verifyPublication(context, source)).isInstanceOf(BranchGraphVectorStore.GenerationChanged.class);
        assertThatThrownBy(() -> authority.verifyPublication(context, source)).isInstanceOf(BranchGraphVectorStore.GenerationChanged.class);
        when(readiness.readCurrentHead(context)).thenReturn(OTHER);
        assertThatThrownBy(() -> authority.requireHead(context, HEAD))
                .isInstanceOf(RelationProjectionOperationsService.RebuildHeadConflictException.class);
        assertThatThrownBy(() -> authority.verifyPublication(context, source)).isInstanceOf(RelationProjectionOperationsService.RebuildHeadConflictException.class);
    }

    @Test void differentModelOnTheSameBranchPreventsPublication() throws Exception {
        var original = authority.capture(context).source();
        var replacement = new EmbeddingModelIdentity(model.modelSha256(), "c".repeat(64),
                Map.of(), "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
        when(models.embeddingIdentity()).thenReturn(replacement);
        assertThatThrownBy(() -> authority.verifyPublication(context, original)).isInstanceOf(BranchGraphVectorStore.GenerationChanged.class);
    }

    @Test void GitMovingBetweenLockedHeadAndSourceCaptureRetainsItsTypedPrecondition() {
        var accepted = authority.capture(context).source();
        when(readiness.readCurrentHead(context)).thenReturn(HEAD, OTHER, OTHER);
        assertThatThrownBy(() -> authority.verifyPublication(context, accepted))
                .isInstanceOfSatisfying(RelationProjectionOperationsService.RebuildHeadConflictException.class,
                        moved -> assertThat(moved.getActualHeadCommit()).isEqualTo(OTHER));
    }

    private static CatalogueSourceJournal.Snapshot catalogueEdition(String suffix) {
        var input = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        return new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-0000000000" + suffix, Instant.EPOCH, input, input, input);
    }
}
