package com.taxonomy.portfolio.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.Scope;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProductView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.inventory.InventoryComparisonService.MapCandidateRequest;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Retained evidence operations must preserve identity, grants and explicit application. */
class InventoryComparisonEvidenceTest {
    private static final String ACTOR = "alice";
    private static final WorkspaceContext CONTEXT =
            new WorkspaceContext(ACTOR, "ws", "draft", "repo");
    private static final Scope SCOPE =
            new Scope("repo", "v2|r4:repo|s12:WORKSPACE:ws|b5:draft", "draft", ACTOR);
    private static final String RATIONALE = "Verified vendor identifier";
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");

    private final ProductCatalogService products = mock(ProductCatalogService.class);
    private final PortfolioAnalysisPersistenceService snapshots =
            mock(PortfolioAnalysisPersistenceService.class);
    private final ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
    private final SolutionPortfolioService solutions = mock(SolutionPortfolioService.class);
    private final InventoryEvidencePort evidence = mock(InventoryEvidencePort.class);
    private final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build());
    private final InventoryComparisonService service = new InventoryComparisonService(
            products, snapshots, projects, solutions, evidence, codec);
    private final SnapshotDetail snapshot =
            new SnapshotDetail(null, null, null, null, null, List.of(), List.of());
    private final Candidate external = new Candidate("external:fixture:17", null,
            "Sourced product", "TECHNOLOGY_PRODUCT", "external-v1",
            Map.of("sourceProfile", "fixture-source"), List.of());
    private final SourceEvidence source = source("fixture-source");

    @Test
    void mappingRetainsExactSourceAndProductIdentityWithoutApplyingAPortfolioDecision() {
        var comparison = prepareMapping(external, List.of(source("other-source"), source));
        Instant before = Instant.now();

        var result = service.map(comparison.id(), external.id(), request("comparison-v1"),
                ACTOR, CONTEXT);

        assertAll(
                () -> assertEquals(MAPPING_ID, result.id()),
                () -> assertEquals(external.id(), result.candidateId()),
                () -> assertEquals(7L, result.productId()),
                () -> assertEquals("product-v1", result.productFingerprint()),
                () -> assertEquals(source.profileId(), result.sourceProfile()),
                () -> assertEquals(source.revision(), result.sourceRevision()),
                () -> assertEquals(external.fingerprint(), result.sourceIdentityFingerprint()),
                () -> assertEquals(RATIONALE, result.rationale()),
                () -> assertEquals(ACTOR, result.actor()),
                () -> assertFalse(result.mappedAt().isBefore(before)),
                () -> assertFalse(result.mappedAt().isAfter(Instant.now())));
        verify(evidence).authorize(SCOPE, comparison.demand(), comparison.sources());
        verify(evidence).revalidate(SCOPE, comparison.demand(), comparison.sources());
        verify(evidence).map(eq(SCOPE), same(result));
        verify(evidence, never()).readSources(any(), any(), any());
        verify(products).getProduct(7L, ACTOR, CONTEXT);
        verifyNoMoreInteractions(products);
        verifyNoInteractions(solutions);
    }

    @Test
    void identicalMappingIdAndPayloadReplayTheRetainedMappingWithoutAnotherWrite() {
        var comparison = prepareMapping(external, List.of(source));
        var retained = mapping(7L, "product-v1", RATIONALE, ACTOR);
        when(evidence.mappings(SCOPE, external.id())).thenReturn(List.of(retained));

        assertSame(retained, service.map(comparison.id(), external.id(),
                request("comparison-v1"), ACTOR, CONTEXT));

        verify(evidence, never()).map(any(), any());
        verify(evidence, never()).readSources(any(), any(), any());
        verifyNoInteractions(solutions);
    }

    @ParameterizedTest
    @CsvSource({
            "8, product-v1, Verified vendor identifier, alice",
            "7, product-v2, Verified vendor identifier, alice",
            "7, product-v1, Different identity rationale, alice",
            "7, product-v1, Verified vendor identifier, bob"
    })
    void reusedMappingIdRejectsChangedProductFingerprintRationaleOrActor(
            long productId, String fingerprint, String rationale, String actor) {
        var comparison = prepareMapping(external, List.of(source));
        when(evidence.mappings(SCOPE, external.id()))
                .thenReturn(List.of(mapping(productId, fingerprint, rationale, actor)));

        var failure = assertThrows(PortfolioException.class,
                () -> service.map(comparison.id(), external.id(), request("comparison-v1"),
                        ACTOR, CONTEXT));

        assertEquals(PortfolioException.Kind.CONFLICT, failure.getKind());
        assertEquals("Mapping ID was reused with a different payload", failure.getMessage());
        verify(evidence, never()).map(any(), any());
        verifyNoInteractions(solutions);
    }

    @Test
    void staleComparisonFingerprintCannotCreateAMapping() {
        var comparison = prepareMapping(external, List.of(source));

        var failure = assertThrows(PortfolioException.class,
                () -> service.map(comparison.id(), external.id(), request("outdated"),
                        ACTOR, CONTEXT));

        assertEquals(PortfolioException.Kind.CONFLICT, failure.getKind());
        assertEquals("Comparison changed", failure.getMessage());
        verify(evidence, never()).revalidate(any(), any(), any());
        verify(evidence, never()).map(any(), any());
        verifyNoInteractions(products, solutions);
    }

    @Test
    void mappingRequiresACandidateRetainedInThisComparison() {
        var comparison = prepareMapping(external, List.of(source));

        var failure = assertThrows(PortfolioException.class,
                () -> service.map(comparison.id(), "external:another-object",
                        request("comparison-v1"), ACTOR, CONTEXT));

        assertEquals(PortfolioException.Kind.VALIDATION, failure.getKind());
        assertEquals("Candidate is not part of this retained comparison", failure.getMessage());
        verify(evidence, never()).revalidate(any(), any(), any());
        verify(evidence, never()).map(any(), any());
        verifyNoInteractions(products, solutions);
    }

    @Test
    void aNameWithoutAnExplicitExternalSourceIdentityCannotBeMapped() {
        var local = new Candidate("product:7", 7L, external.name(), external.kind(),
                "product-v1", Map.of(), List.of());
        var comparison = prepareMapping(local, List.of(source));

        var failure = assertThrows(PortfolioException.class,
                () -> service.map(comparison.id(), local.id(), request("comparison-v1"),
                        ACTOR, CONTEXT));

        assertEquals(PortfolioException.Kind.VALIDATION, failure.getKind());
        assertEquals("Only an explicit external source identity may be mapped", failure.getMessage());
        verify(evidence, never()).revalidate(any(), any(), any());
        verify(evidence, never()).map(any(), any());
        verifyNoInteractions(products, solutions);
    }

    @Test
    void externalCandidateRequiresItsOwnRetainedSourceEvidence() {
        var comparison = prepareMapping(external, List.of(source("another-profile")));

        var failure = assertThrows(PortfolioException.class,
                () -> service.map(comparison.id(), external.id(), request("comparison-v1"),
                        ACTOR, CONTEXT));

        assertEquals(PortfolioException.Kind.VALIDATION, failure.getKind());
        assertEquals("External candidate has no retained source identity", failure.getMessage());
        verify(evidence).revalidate(SCOPE, comparison.demand(), comparison.sources());
        verify(evidence, never()).mappings(any(), any());
        verify(evidence, never()).map(any(), any());
        verifyNoInteractions(solutions);
    }

    @Test
    void listingHidesOnlyForbiddenOrMissingSourcesAndNeverRefreshesTheirEvidence() {
        var forbidden = comparison(external, List.of(source("forbidden")));
        var visible = comparison(external, List.of(source));
        var missing = comparison(external, List.of(source("missing")));
        when(evidence.list(SCOPE, 1L, "snapshot"))
                .thenReturn(List.of(forbidden, visible, missing));
        doThrow(new InventoryEvidencePort.Failure("source_forbidden", 403, "Grant revoked"))
                .when(evidence).authorize(SCOPE, forbidden.demand(), forbidden.sources());
        doThrow(new InventoryEvidencePort.Failure("source_missing", 404, "Source unavailable"))
                .when(evidence).authorize(SCOPE, missing.demand(), missing.sources());

        assertEquals(List.of(visible), service.list(1L, "snapshot", ACTOR, CONTEXT));

        var order = inOrder(snapshots, evidence);
        order.verify(snapshots).getSnapshot(1L, "snapshot", ACTOR, CONTEXT);
        order.verify(evidence).list(SCOPE, 1L, "snapshot");
        order.verify(evidence).authorize(SCOPE, forbidden.demand(), forbidden.sources());
        order.verify(evidence).authorize(SCOPE, visible.demand(), visible.sources());
        order.verify(evidence).authorize(SCOPE, missing.demand(), missing.sources());
        verifyNoMoreInteractions(evidence);
        verifyNoInteractions(products, solutions);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void listingPropagatesSourceFailuresOtherThanForbiddenOrMissing(int status) {
        var retained = comparison(external, List.of(source));
        var failure = new InventoryEvidencePort.Failure("source_unavailable", status,
                "Source authorization could not be checked");
        when(evidence.list(SCOPE, 1L, "snapshot")).thenReturn(List.of(retained));
        doThrow(failure).when(evidence).authorize(SCOPE, retained.demand(), retained.sources());

        assertSame(failure, assertThrows(InventoryEvidencePort.Failure.class,
                () -> service.list(1L, "snapshot", ACTOR, CONTEXT)));

        verify(evidence, never()).readSources(any(), any(), any());
        verify(evidence, never()).save(any(), any());
        verifyNoInteractions(products, solutions);
    }

    @Test
    void sourcedAssertionIsAppendedUnchangedUnderTheProductLockAndExactTenantScope() {
        var assertion = assertion("product:7", Instant.EPOCH);

        assertSame(assertion, service.appendAssertion(7L, assertion, ACTOR, CONTEXT));

        var order = inOrder(products, evidence);
        order.verify(products).requireProductForUpdate(7L, ACTOR, CONTEXT);
        order.verify(evidence).appendAssertion(SCOPE, assertion);
        verifyNoMoreInteractions(products, evidence);
        verifyNoInteractions(snapshots, projects, solutions);
    }

    @ParameterizedTest
    @CsvSource({
            "product:8, 1970-01-01T00:00:00Z, Assertion must name this existing product",
            "product:7, 9999-12-31T23:59:59Z, Assertion recording date cannot be in the future"
    })
    void invalidAssertionCannotReachTheJournal(
            String candidate, String recordedAt, String expectedMessage) {
        var assertion = assertion(candidate, Instant.parse(recordedAt));

        var failure = assertThrows(PortfolioException.class,
                () -> service.appendAssertion(7L, assertion, ACTOR, CONTEXT));

        assertEquals(PortfolioException.Kind.VALIDATION, failure.getKind());
        assertEquals(expectedMessage, failure.getMessage());
        verify(products).requireProductForUpdate(7L, ACTOR, CONTEXT);
        verifyNoMoreInteractions(products);
        verifyNoInteractions(evidence, snapshots, projects, solutions);
    }

    @Test void replacementMappingMakesRetainedCandidateReviewStale() {
        var retained=new Candidate(external.id(),null,external.name(),external.kind(),external.fingerprint(),Map.of("sourceProfile","fixture-source","mappingStateFingerprint","old-state"),List.of());
        var comparison=prepareMapping(retained,List.of(source));
        when(evidence.mappings(SCOPE,retained.id())).thenReturn(List.of(mapping(7L,"product-v1",RATIONALE,ACTOR)));
        when(evidence.fingerprint(anyList())).thenReturn("new-state");
        when(evidence.review(any(),any(),any(),any())).thenAnswer(c->{((java.util.function.Supplier<?>)c.getArgument(3)).get();return null;});
        var review=new Review(UUID.randomUUID(),comparison.fingerprint(),retained.id(),"Reasoned review",null,false);
        var failure=assertThrows(PortfolioException.class,()->service.review(comparison.id(),review,ACTOR,CONTEXT));
        assertEquals(PortfolioException.Kind.CONFLICT,failure.getKind());assertTrue(failure.getMessage().contains("mapping authority"));
    }

    private Comparison prepareMapping(Candidate candidate, List<SourceEvidence> sources) {
        var comparison = comparison(candidate, sources);
        var product = mock(ProductView.class);
        var requirement = mock(RequirementView.class);
        when(product.id()).thenReturn(7L);
        when(requirement.currentVersionId()).thenReturn(3L);
        when(evidence.read(SCOPE, comparison.id())).thenReturn(comparison);
        when(snapshots.getSnapshot(1L, "snapshot", ACTOR, CONTEXT)).thenReturn(snapshot);
        when(evidence.fingerprint(codec.write(snapshot))).thenReturn("snapshot-v1");
        when(projects.getRequirement(1L, 2L, ACTOR, CONTEXT)).thenReturn(requirement);
        when(products.getProduct(7L, ACTOR, CONTEXT)).thenReturn(product);
        when(evidence.fingerprint(product)).thenReturn("product-v1");
        return comparison;
    }

    private Comparison comparison(Candidate candidate, List<SourceEvidence> sources) {
        var question = new Question(TimeMode.EXACT_DATE, LocalDate.parse("2030-01-01"),
                null, "north", "production");
        var demand = new Demand(SCOPE.repositoryId(), SCOPE.scopeKey(), SCOPE.branch(),
                1L, 2L, 3L, "snapshot", "catalogue-v1", "snapshot-v1",
                List.of("fixture:node"), question);
        return new Comparison(UUID.randomUUID(), demand, InventoryCriterionEvaluator.VERSION,
                Instant.EPOCH, List.of(candidate), List.of(), sources, "comparison-v1");
    }

    private MapCandidateRequest request(String fingerprint) {
        return new MapCandidateRequest(MAPPING_ID, 7L, fingerprint, RATIONALE);
    }

    private Mapping mapping(Long productId, String fingerprint, String rationale, String actor) {
        return new Mapping(MAPPING_ID, external.id(), productId, fingerprint, source.profileId(),
                source.revision(), external.fingerprint(), rationale, actor, Instant.EPOCH);
    }

    private SourceEvidence source(String profile) {
        return new SourceEvidence(profile, "source-revision-3", "observation-17", Instant.EPOCH,
                "selector-v1", "OBSERVED", Set.of());
    }

    private Assertion assertion(String candidate, Instant recordedAt) {
        return new Assertion("assertion-7", candidate, Criterion.AVAILABILITY, null, false,
                new Boundary("2030", Precision.YEAR), null, "north", "production",
                AssertionKind.DECLARED, "Sourced schedule explicitly excludes this period", recordedAt);
    }
}
