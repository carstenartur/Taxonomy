package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.controller.*;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.architecture.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.*;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.relations.service.*;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP admission, JPA journal, traversal and HTTP-provider calls. */
class TaxonomyExtensionHttpTest {
    @Test void addingCpRetainsBpWithoutAnotherBpProviderCall() throws Exception {
        try (var fixture = new Fixture()) {
            AnalysisResult source = fixture.analyze(fixture.start(Set.of("BP")));
            assertThat(source.getStatus()).isEqualTo("SUCCESS");
            assertThat(fixture.calls).containsExactlyEntriesOf(Map.of("BP", 1));
            var request = fixture.extend(source, Set.of("CP"));
            AnalysisResult extended = fixture.analyze(request);

            assertThat(extended.getRawScores()).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 71.125, "CP", 42.5));
            assertThat(extended.getReasons()).containsEntry("BP", "Original BP explanation.\nRetain verbatim.");
            assertThat(extended.getAnalysisScope()).isEqualTo(new AnalysisScope(Set.of("BP", "CP"), AnalysisMode.TAXONOMIES_ONLY));
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1));
            assertThat(extended.getRecovery().id()).isEqualTo(request.get("continuationId"));
            assertThat(fixture.store.read(source.getRecovery().id(), fixture.scope).recovery().version())
                    .isEqualTo(source.getRecovery().version());
        }
    }

    @Test void cpOnlyRetainsOpenBpAndAllRetriesOnlyItsUnfinishedQuestion() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.answers.put("unrelated-code", "{}");
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            assertThat(source.getRecovery().state()).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(source.getRawScores()).containsEntry("BP", 71.125).doesNotContainKey("unrelated-code");
            fixture.answers.put("unrelated-code", fixture.answer("unrelated-code", 23.75));

            var cpOnly = fixture.analyze(fixture.extend(source, Set.of("CP")));
            assertThat(cpOnly.getRawScores()).containsEntry("BP", 71.125).containsEntry("CP", 42.5)
                    .doesNotContainKey("unrelated-code");
            assertThat(cpOnly.getAnalysisCoverage().nodes().get("unrelated-code").state())
                    .isEqualTo(AnalysisCoverage.State.UNKNOWN);
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1, "unrelated-code", 1));

            var all = fixture.analyze(fixture.extend(cpOnly, Set.of()));
            assertThat(all.getStatus()).isEqualTo("SUCCESS");
            assertThat(all.getRawScores()).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 71.125, "CP", 42.5, "unrelated-code", 71.125));
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1, "unrelated-code", 2));
            assertThat(all.getReasons().get("BP")).isEqualTo(source.getReasons().get("BP"));
        }
    }

    @Test void completedZeroBranchAndRepeatedTargetNeedNoNewProviderCall() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.answers.put("BP", fixture.answer("BP", 0));
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            assertThat(source.getAnalysisCoverage().nodes().get("BP").descendants()).isEqualTo(AnalysisCoverage.Descendants.COMPLETE);
            var request = fixture.extend(source, Set.of("BP"));
            var target = fixture.analyze(request);
            var replay = fixture.analyze(request);
            assertThat(replay.getRecovery()).isEqualTo(target.getRecovery());
            assertThat(replay.getRawScores()).containsExactlyEntriesOf(Map.of("BP", 0d));
            assertThat(replay.getAnalysisCoverage().nodes().get("BP").descendants()).isEqualTo(AnalysisCoverage.Descendants.COMPLETE);
            assertThat(fixture.calls).containsExactlyEntriesOf(Map.of("BP", 1));
        }
    }

    @Test void unselectedUnvisitedQuestionCannotAcquireAProviderAttempt() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.answers.put("BP", fixture.answer("BP", 0));
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var request = fixture.mapper.convertValue(fixture.extend(source, Set.of("CP")), AnalysisRequest.class);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            try (var execution = fixture.continuations.begin(request, "alice", fixture.scope, 20)) {
                var unanswered = AnalysisCheckpointSession.evaluate("CATEGORY", "CUSTOM_OPENAI", List.of("unrelated-code"),
                        "a previously unvisited question", () -> { calls.incrementAndGet(); return new LlmCallDetail(); });
                assertThat(calls).hasValue(0);
                assertThat(unanswered.getScores()).isEmpty();
                assertThat(unanswered.getError()).startsWith("UNASSESSED:");
            }
        }
    }

    @Test void rejectedSourceAuthorityAndWorkSelectionNeverReachTheProvider() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            for (var mutation : List.<java.util.function.Consumer<Map<String, Object>>>of(
                    r -> r.put("businessText", "Changed requirement"), r -> r.put("provider", "GEMINI"),
                    r -> r.put("analysisLanguage", "de"), r -> r.put("analysisReasonLength", "detailed"),
                    r -> r.put("taxonomySourceVersion", source.getRecovery().version() + 1))) {
                var request = fixture.extend(source, Set.of("CP")); mutation.accept(request);
                fixture.reject(request, 409);
            }
            var missingRoot = fixture.extend(source, Set.of("CP", "MISSING")); fixture.reject(missingRoot, 400);
            var both = fixture.extend(source, Set.of("CP"));
            both.put("relationSourceContinuationId", source.getRecovery().id()); both.put("relationSourceVersion", source.getRecovery().version());
            fixture.reject(both, 400);
            var unknown = fixture.extend(source, Set.of("CP")); unknown.put("taxonomySourceContinuationId", UUID.randomUUID().toString());
            fixture.reject(unknown, 404);
            var request = fixture.mapper.convertValue(fixture.extend(source, Set.of("CP")), AnalysisRequest.class);
            for (var wrongScope : List.of(new WorkspaceContext("bob", "ws", "draft", "repo"),
                    new WorkspaceContext("alice", "other", "draft", "repo"), new WorkspaceContext("alice", "ws", "other", "repo"),
                    new WorkspaceContext("alice", "ws", "draft", "other")))
                assertThatThrownBy(() -> fixture.store.taxonomyInput(request, wrongScope)).hasMessageContaining("404");
            ReflectionTestUtils.setField(fixture.providers, "customLlmModel", "changed-model");
            fixture.reject(fixture.extend(source, Set.of("CP")), 409);
            assertThat(fixture.calls).containsExactlyEntriesOf(Map.of("BP", 1));
        }
    }

    @Test void everyIncompleteOrMixedTaxonomySourceRejectsWithoutCreatingWork() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            for (var mutation : List.<java.util.function.Consumer<Map<String, Object>>>of(
                    r -> r.put("resumable", false),
                    r -> r.remove("taxonomySourceVersion"),
                    r -> r.put("relationSourceContinuationId", source.getRecovery().id()),
                    r -> r.put("relationSourceVersion", source.getRecovery().version()))) {
                var request = fixture.extend(source, Set.of("CP")); mutation.accept(request);
                fixture.reject(request, 400);
                var decision = fixture.mapper.convertValue(request, AnalysisRequest.class);
                assertThatThrownBy(() -> fixture.continuations.resolveTaxonomiesRequest(decision, "alice", fixture.scope))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
                assertThatThrownBy(() -> fixture.store.read(decision.getContinuationId(), fixture.scope))
                        .hasMessageContaining("404");
            }
            var retained = fixture.store.read(source.getRecovery().id(), fixture.scope);
            assertThat(retained.recovery()).isEqualTo(source.getRecovery());
            assertThat(retained.result().getRawScores()).isEqualTo(source.getRawScores());
            assertThat(retained.result().getReasons()).isEqualTo(source.getReasons());
            assertThat(fixture.calls).containsExactlyEntriesOf(Map.of("BP", 1));
        }
    }

    @Test void eitherRelationsSourceFieldRejectsAtTheJournalBoundaryWithoutCreatingWork() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            for (var mutation : List.<java.util.function.Consumer<AnalysisRequest>>of(
                    r -> r.setRelationSourceContinuationId(source.getRecovery().id()),
                    r -> r.setRelationSourceVersion(source.getRecovery().version()))) {
                var request = fixture.mapper.convertValue(fixture.extend(source, Set.of("CP")), AnalysisRequest.class);
                mutation.accept(request);
                assertThatThrownBy(() -> fixture.store.taxonomyInput(request, fixture.scope))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
                assertThatThrownBy(() -> fixture.store.begin(request, fixture.scope, "rejected-mixed-source", List.of()))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
                assertThatThrownBy(() -> fixture.store.read(request.getContinuationId(), fixture.scope))
                        .hasMessageContaining("404");
            }
            var retained = fixture.store.read(source.getRecovery().id(), fixture.scope);
            assertThat(retained.recovery()).isEqualTo(source.getRecovery());
            assertThat(retained.result().getRawScores()).isEqualTo(source.getRawScores());
            assertThat(retained.result().getReasons()).isEqualTo(source.getReasons());
            assertThat(fixture.calls).containsExactlyEntriesOf(Map.of("BP", 1));
        }
    }

    @Test void sourceRequestAndRequestedWorkSurviveRestartAndRejectConflictingDuplicate() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var request = fixture.extend(source, Set.of("CP"));
            request.put("taxonomyRequestedScope", Map.of("taxonomyRoots", Set.of(), "mode", "FULL"));
            var target = fixture.analyze(request);
            var saved = fixture.store.read(target.getRecovery().id(), fixture.scope);
            assertThat(saved.request().getTaxonomyRequestedScope()).isEqualTo(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
            assertThat(saved.request().getAnalysisScope().taxonomyRoots()).containsExactlyInAnyOrder("BP", "CP");
            var restored = fixture.mapper.convertValue(saved.request(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
            restored.put("analysisScope", saved.request().getTaxonomyRequestedScope());
            var replay = fixture.analyze(restored);
            assertThat(replay.getRecovery()).isEqualTo(target.getRecovery());
            var wrong = new LinkedHashMap<>(restored); wrong.put("analysisScope", saved.request().getAnalysisScope());
            fixture.reject(wrong, 409);
            var duplicate = fixture.extend(source, Set.of("CP")); fixture.reject(duplicate, 409);
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1));
        }
    }

    @Test void cancellationAndFailedNewRootKeepAcceptedEvidence() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var decision = fixture.mapper.convertValue(fixture.extend(source, Set.of("CP")), AnalysisRequest.class);
            try (var execution = fixture.continuations.begin(decision, "alice", fixture.scope, 20)) {
                var cancelled = fixture.store.cancel(decision.getContinuationId(), fixture.scope).result();
                assertThat(cancelled.getRawScores()).isEqualTo(source.getRawScores());
                assertThat(cancelled.getReasons()).isEqualTo(source.getReasons());
                assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
                assertThat(cancelled.getAnalysisCoverage().nodes().get("CP").state()).isEqualTo(AnalysisCoverage.State.UNKNOWN);
            }
            fixture.answers.put("CP", "{}");
            var failedNew = fixture.analyze(fixture.extend(source, Set.of("CP")));
            assertThat(failedNew.getStatus()).isEqualTo("PARTIAL");
            assertThat(failedNew.getRawScores()).isEqualTo(source.getRawScores());
            assertThat(failedNew.getReasons()).isEqualTo(source.getReasons());
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1));
        }
    }

    @Test void partialProductGroupsPreserveTheirAcceptedSiblingsAcrossCpThenAll() throws Exception {
        try (var fixture = new Fixture(true, true, false)) {
            fixture.answers.put("accepted-item", fixture.answer("accepted-item", 83.125));
            fixture.answers.put("open-item", "{}");
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            assertThat(source.getRawScores()).containsEntry("accepted-item", 83.125).doesNotContainKey("open-item");
            fixture.answers.put("open-item", fixture.answer("open-item", 56.25));
            var cp = fixture.analyze(fixture.extend(source, Set.of("CP")));
            assertThat(cp.getRawScores()).containsEntry("accepted-item", 83.125).doesNotContainKey("open-item");
            var all = fixture.analyze(fixture.extend(cp, Set.of()));
            assertThat(all.getRawScores()).containsEntry("accepted-item", 83.125).containsEntry("open-item", 56.25);
            assertThat(all.getReasons().get("accepted-item")).isEqualTo(source.getReasons().get("accepted-item"));
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1, "accepted-item", 1, "open-item", 2));
        }
    }

    @Test void taxonomyRelationsTaxonomyChainKeepsBoundModelAndOriginalNodeJournal() throws Exception {
        try (var fixture = new Fixture(false, false, true)) {
            fixture.answers.put("BP", fixture.answer("BP", 0)); fixture.answers.put("CP", fixture.answer("CP", 0));
            var bp = fixture.analyze(fixture.start(Set.of("BP")));
            var both = fixture.analyze(fixture.extend(bp, Set.of("CP")));
            var relationRequest = fixture.start(Set.of("BP", "CP"));
            relationRequest.put("relationSourceContinuationId", both.getRecovery().id());
            relationRequest.put("relationSourceVersion", both.getRecovery().version());
            var relations = fixture.analyze(relationRequest);
            assertThat(relations.getAnalysisScope().mode()).isEqualTo(AnalysisMode.FULL);
            assertThat(relations.getModelBinding().scope().mode()).isEqualTo(AnalysisMode.TAXONOMIES_ONLY);
            assertThat(relations.getRelationSearchReport()).isNotNull();
            var savedRelations = fixture.store.read(relations.getRecovery().id(), fixture.scope).request();
            assertThat(savedRelations.getTaxonomySourceContinuationId()).isNull();
            assertThat(savedRelations.getTaxonomyRequestedScope()).isNull();

            var resumed = fixture.analyze(fixture.extend(relations, Set.of("BP")));
            assertThat(resumed.getRawScores()).isEqualTo(both.getRawScores());
            assertThat(resumed.getReasons()).isEqualTo(both.getReasons());
            assertThat(resumed.getModelBinding()).isEqualTo(both.getModelBinding());
            assertThat(resumed.getRelationSearchReport()).isNull(); assertThat(resumed.getArchitectureView()).isNull();
            assertThat(fixture.store.read(resumed.getRecovery().id(), fixture.scope).request().getRelationSourceContinuationId()).isNull();
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1));
        }
    }

    @Test void completedTargetReplayDoesNotNeedTheCurrentProviderConfiguration() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var request = fixture.extend(source, Set.of("CP"));
            var target = fixture.analyze(request);
            ReflectionTestUtils.setField(fixture.providers, "customLlmModel", "new-model-after-lost-response");
            var replay = fixture.analyze(request);
            assertThat(replay.getRecovery()).isEqualTo(target.getRecovery());
            assertThat(replay.getRawScores()).isEqualTo(target.getRawScores());
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1));
        }
    }

    @Test void missingAcceptedJournalRejectsAtomicallyInsteadOfReassessingSource() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var em = SharedEntityManagerCreator.createSharedEntityManager(fixture.bean.getObject());
            new org.springframework.transaction.support.TransactionTemplate(new JpaTransactionManager(fixture.bean.getObject()))
                    .executeWithoutResult(status -> em.createQuery("delete from AnalysisQuestionCheckpoint q where q.run.id=:id")
                            .setParameter("id", source.getRecovery().id()).executeUpdate());
            var request = fixture.extend(source, Set.of("CP"));
            fixture.reject(request, 409);
            assertThatThrownBy(() -> fixture.store.read((String) request.get("continuationId"), fixture.scope)).hasMessageContaining("404");
            assertThat(fixture.calls).containsExactlyEntriesOf(Map.of("BP", 1));
        }
    }

    @Test void runningAndUncertainTargetsMustUseTheirExistingRecoveryIdentity() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var request = fixture.mapper.convertValue(fixture.extend(source, Set.of("CP")), AnalysisRequest.class);
            try (var execution = fixture.continuations.begin(request, "alice", fixture.scope, 20)) {
                var running = fixture.store.read(request.getContinuationId(), fixture.scope);
                var further = fixture.extend(source, Set.of("CP"));
                further.put("taxonomySourceContinuationId", request.getContinuationId());
                further.put("taxonomySourceVersion", running.recovery().version());
                fixture.reject(further, 409);
                assertThatThrownBy(() -> AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", fixture.providers.getActiveProviderName(), List.of("CP"),
                        "SCORE:ROOT:CP", () -> { throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED); }))
                        .isInstanceOf(AnalysisStoppedException.class);
            }
            var uncertain = fixture.store.read(request.getContinuationId(), fixture.scope);
            assertThat(uncertain.recovery().openQuestions()).anyMatch(q -> q.error().startsWith("OUTCOME_UNCERTAIN"));
            var further = fixture.extend(source, Set.of("CP"));
            further.put("taxonomySourceContinuationId", request.getContinuationId()); further.put("taxonomySourceVersion", uncertain.recovery().version());
            fixture.reject(further, 409);
            var skip = fixture.mapper.convertValue(request, new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
            skip.put("continuationAction", "SKIP"); skip.put("continuationVersion", uncertain.recovery().version());
            skip.put("continuationQuestion", uncertain.recovery().openQuestions().getFirst().key());
            var skipped = fixture.analyze(skip);
            assertThat(skipped.getRecovery().state()).isEqualTo("COMPLETED_WITH_GAPS");
            var afterExplicitDecision = fixture.analyze(fixture.extend(skipped, Set.of("BP")));
            assertThat(afterExplicitDecision.getRawScores()).isEqualTo(source.getRawScores());
            assertThat(afterExplicitDecision.getAnalysisCoverage().nodes().get("CP").state()).isEqualTo(AnalysisCoverage.State.UNKNOWN);
            assertThat(fixture.calls).containsExactlyEntriesOf(Map.of("BP", 1));
        }
    }

    @Test void completedFullScopeCreatesANewReceiptWithoutNodeOrRelationCalls() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.answers.put("CP", fixture.answer("CP", 0));
            var initial = fixture.start(Set.of("BP", "CP"));
            initial.put("analysisScope", Map.of("taxonomyRoots", Set.of("BP", "CP"), "mode", "FULL"));
            var source = fixture.analyze(initial);
            assertThat(source.getStatus()).isEqualTo("SUCCESS");
            assertThat(fixture.calls.get("RELATIONS")).isEqualTo(1);
            var previousCalls = Map.copyOf(fixture.calls);
            var request = fixture.extend(source, Set.of("BP"));
            request.put("analysisScope", Map.of("taxonomyRoots", Set.of("BP"), "mode", "FULL"));
            var target = fixture.analyze(request);
            assertThat(target.getRecovery().id()).isEqualTo(request.get("continuationId")).isNotEqualTo(source.getRecovery().id());
            assertThat(target.getRecovery().state()).isEqualTo("COMPLETED");
            assertThat(target.getAnalysisScope().taxonomyRoots()).containsExactlyInAnyOrder("BP", "CP");
            assertThat(target.getRawScores()).isEqualTo(source.getRawScores());
            assertThat(target.getReasons()).isEqualTo(source.getReasons());
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(previousCalls);
            assertThat(target.getRelationSearchReport()).isEqualTo(source.getRelationSearchReport());
            var em = SharedEntityManagerCreator.createSharedEntityManager(fixture.bean.getObject());
            var keys = em.createQuery("select q.questionKey from AnalysisQuestionCheckpoint q where q.run.id=:id and q.questionKey like 'R%'", String.class)
                    .setParameter("id", source.getRecovery().id()).getResultList();
            assertThat(keys).isNotEmpty();
            for (String key : keys) {
                var old = fixture.store.detail(source.getRecovery().id(), key, fixture.scope);
                var copied = fixture.store.detail(target.getRecovery().id(), key, fixture.scope);
                assertThat(fixture.mapper.writeValueAsString(copied)).isEqualTo(fixture.mapper.writeValueAsString(old));
            }
            assertThat(fixture.analyze(request).getRecovery()).isEqualTo(target.getRecovery());
        }
    }

    @Test void fullExtensionBuildsRelationsFromAccumulatedNodesAndNodeOnlyClearsThem() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.answers.put("CP", fixture.answer("CP", 0));
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var request = fixture.extend(source, Set.of("CP"));
            request.put("analysisScope", Map.of("taxonomyRoots", Set.of("CP"), "mode", "FULL"));
            var full = fixture.analyze(request);
            assertThat(full.getStatus()).isEqualTo("SUCCESS");
            assertThat(full.getRawScores()).containsAllEntriesOf(source.getRawScores()).containsEntry("CP", 0d);
            assertThat(full.getRelationSearchReport().sources()).extracting(item -> item.node().id()).containsExactly("unrelated-code");
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1, "unrelated-code", 1, "RELATIONS", 1));
            var nodeOnly = fixture.analyze(fixture.extend(full, Set.of("BP")));
            assertThat(nodeOnly.getRawScores()).isEqualTo(full.getRawScores());
            assertThat(nodeOnly.getReasons()).isEqualTo(full.getReasons());
            assertThat(nodeOnly.getRelationSearchReport()).isNull(); assertThat(nodeOnly.getArchitectureView()).isNull();
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1, "unrelated-code", 1, "RELATIONS", 1));
        }
    }

    @Test void serverDefaultRetainsItsConcreteProviderAcrossSourceReadback() throws Exception {
        try (var fixture = new Fixture()) {
            var initial = fixture.start(Set.of("BP")); initial.remove("provider");
            var source = fixture.analyze(initial);
            assertThat(source.getAnalysisProviderId()).isEqualTo("CUSTOM_OPENAI");
            var saved = fixture.store.read(source.getRecovery().id(), fixture.scope);
            assertThat(saved.request().getProvider()).isEqualTo("CUSTOM_OPENAI");
            var request = fixture.extend(source, Set.of("CP")); request.put("provider", "");
            var target = fixture.analyze(request);
            assertThat(target.getAnalysisProviderId()).isEqualTo("CUSTOM_OPENAI");
            assertThat(target.getRawScores()).containsAllEntriesOf(source.getRawScores()).containsKey("CP");
            assertThat(target.getReasons().get("BP")).isEqualTo(source.getReasons().get("BP"));
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1));
        }
    }

    @Test void changedServerDefaultRejectsNewAdmissionButNotAnAcceptedReceiptReplay() throws Exception {
        try (var fixture = new Fixture()) {
            var initial = fixture.start(Set.of("BP")); initial.put("provider", null);
            var source = fixture.analyze(initial);
            var request = fixture.extend(source, Set.of("CP")); request.remove("provider");
            var target = fixture.analyze(request);
            ReflectionTestUtils.setField(fixture.providers, "llmProviderConfig", "GEMINI");
            assertThat(fixture.providers.getActiveProviderId().value()).isEqualTo("GEMINI");
            assertThat(fixture.analyze(request).getRecovery()).isEqualTo(target.getRecovery());
            for (String selected : Arrays.asList(null, "")) {
                var changed = fixture.extend(target, Set.of("BP")); changed.put("provider", selected);
                fixture.reject(changed, 409);
                assertThatThrownBy(() -> fixture.store.read((String) changed.get("continuationId"), fixture.scope))
                        .hasMessageContaining("404");
            }
            var explicit = fixture.analyze(fixture.extend(target, Set.of("BP")));
            assertThat(explicit.getRawScores()).isEqualTo(target.getRawScores());
            assertThat(explicit.getAnalysisProviderId()).isEqualTo("CUSTOM_OPENAI");
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "CP", 1));
        }
    }

    @Test void modeOnlyFullServerDefaultChecksNewAdmissionWhileReplayingItsAcceptedReceipt() throws Exception {
        try (var fixture = new Fixture(true)) {
            var initial = fixture.start(Set.of("BP")); initial.remove("provider");
            var source = fixture.analyze(initial);
            var request = fixture.extend(source, Set.of("BP")); request.put("provider", "");
            request.put("analysisScope", Map.of("taxonomyRoots", Set.of("BP"), "mode", "FULL"));
            request.put("includeArchitectureView", true);
            var target = fixture.analyze(request);
            assertThat(target.getArchitectureView()).isNotNull();
            assertThat(target.getRelationSearchReport()).isNotNull();
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "unrelated-code", 1, "RELATIONS", 1));
            ReflectionTestUtils.setField(fixture.providers, "llmProviderConfig", "GEMINI");
            assertThat(fixture.analyze(request).getRecovery()).isEqualTo(target.getRecovery());
            var changed = new LinkedHashMap<>(request); changed.put("continuationId", UUID.randomUUID().toString());
            var rejected = fixture.mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON)
                            .content(fixture.mapper.writeValueAsString(changed)))
                    .andExpect(status().isConflict()).andReturn().getResponse();
            assertThat(rejected.getErrorMessage()).contains("server default provider changed");
            assertThatThrownBy(() -> fixture.store.read((String) changed.get("continuationId"), fixture.scope))
                    .hasMessageContaining("404");
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "unrelated-code", 1, "RELATIONS", 1));
            assertThat(fixture.store.read(source.getRecovery().id(), fixture.scope).result().getRawScores()).isEqualTo(source.getRawScores());
        }
    }

    @Test void fullModeOptOutIsRetainedInServerReceiptWithoutRepeatingAcceptedNodes() throws Exception {
        try (var fixture = new Fixture(true)) {
            var source = fixture.analyze(fixture.start(Set.of("BP")));
            var request = fixture.extend(source, Set.of("BP"));
            request.put("analysisScope", Map.of("taxonomyRoots", Set.of("BP"), "mode", "FULL"));
            request.put("includeArchitectureView", false);
            var target = fixture.analyze(request);
            var saved = fixture.store.read(target.getRecovery().id(), fixture.scope);
            assertThat(saved.request().isIncludeArchitectureView()).isFalse();
            assertThat(saved.request().getTaxonomyRequestedScope().mode()).isEqualTo(AnalysisMode.FULL);
            assertThat(target.getArchitectureView()).isNull(); assertThat(target.getCopilot()).isNull();
            assertThat(target.getRelationSearchReport()).isNotNull();
            assertThat(target.getRawScores()).isEqualTo(source.getRawScores());
            assertThat(target.getReasons()).isEqualTo(source.getReasons());
            assertThat(fixture.calls).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "unrelated-code", 1, "RELATIONS", 1));
            var projection = fixture.extend(target, Set.of("BP"));
            projection.put("analysisScope", Map.of("taxonomyRoots", Set.of("BP"), "mode", "FULL"));
            projection.put("includeArchitectureView", true);
            var projected = fixture.analyze(projection);
            assertThat(projected.getArchitectureView()).isNotNull(); assertThat(projected.getCopilot()).isNotNull();
            assertThat(projected.getRawScores()).isEqualTo(source.getRawScores());
            assertThat(projected.getReasons()).isEqualTo(source.getReasons());
            assertThat(fixture.calls.get("BP")).isEqualTo(1); assertThat(fixture.calls.get("unrelated-code")).isEqualTo(1);
        }
    }

    static final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final WorkspaceContext scope = new WorkspaceContext("alice", "ws", "draft", "repo");
        final Map<String, Integer> calls = new LinkedHashMap<>();
        final Map<String, String> answers = new LinkedHashMap<>();
        final AnalysisContinuationStore store;
        final AnalysisContinuationService continuations;
        final LlmProviderConfig providers;
        final MockMvc mvc;
        final MockRestServiceServer gatewayServer;

        Fixture() { this(false, false, false); }
        Fixture(boolean withChild) { this(withChild, false, false); }
        Fixture(boolean withChild, boolean products, boolean modelBound) {
            bean.setDataSource(new DriverManagerDataSource("jdbc:hsqldb:mem:taxonomy-http-" + UUID.randomUUID(), "sa", ""));
            bean.setManagedTypes(PersistenceManagedTypes.of(AnalysisContinuationRun.class.getName(), AnalysisQuestionCheckpoint.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false"));
            bean.afterPropertiesSet();
            var em = SharedEntityManagerCreator.createSharedEntityManager(bean.getObject());
            var proxy = new ProxyFactory(new AnalysisContinuationStore(em, mapper)); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new JpaTransactionManager(bean.getObject()), new AnnotationTransactionAttributeSource()));
            store = (AnalysisContinuationStore) proxy.getProxy();
            var nodes = mock(TaxonomyNodeRepository.class);
            var roots = new ArrayList<TaxonomyNode>();
            for (String code : List.of("BP", "CP")) {
                var node = new TaxonomyNode(); node.setCode(code); node.setTaxonomyRoot(code); node.setNameEn(code); node.setLevel(0);
                roots.add(node);
                when(nodes.findByCode(code)).thenReturn(Optional.of(node));
                when(nodes.findByTaxonomyRootOrderByLevelAscNameEnAsc(code)).thenReturn(List.of(node));
                when(nodes.findByParentCodeOrderByNameEnAsc(code)).thenReturn(List.of());
            }
            if (withChild) {
              var children = new ArrayList<TaxonomyNode>();
              for (String code : products ? List.of("accepted-item", "open-item") : List.of("unrelated-code")) {
                var child = new TaxonomyNode(); child.setCode(code); child.setNameEn(code);
                child.setTaxonomyRoot("BP"); child.setParent(roots.getFirst()); child.setParentCode("BP"); child.setLevel(1);
                children.add(child);
                when(nodes.findByCode(child.getCode())).thenReturn(Optional.of(child));
                when(nodes.findByParentCodeOrderByNameEnAsc(child.getCode())).thenReturn(List.of());
              }
              roots.getFirst().setChildren(children);
              var subtree = new ArrayList<>(children); subtree.addFirst(roots.getFirst());
              when(nodes.findByTaxonomyRootOrderByLevelAscNameEnAsc("BP")).thenReturn(subtree);
              when(nodes.findByParentCodeOrderByNameEnAsc("BP")).thenReturn(children);
            }
            when(nodes.findByParentIsNullOrderByCodeAsc()).thenReturn(roots);
            var overlay = spy(new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused"));
            if (products) for (String code : List.of("accepted-item", "open-item"))
                doReturn(new CatalogueOverlayService.NodeMetadata("PRODUCT", List.of(), 1, false, null)).when(overlay).getNodeMetadata(code);
            var initialized = new AppInitializationStateService(); initialized.update(AppInitializationStateService.State.READY, "fixture ready");
            var catalogue = new TaxonomyService(nodes, mock(TaxonomyRelationRepository.class), initialized);
            ReflectionTestUtils.setField(catalogue, "catalogueOverlayService", overlay);
            var journal = mock(CatalogueSourceJournal.class);
            var unknown = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
            when(journal.lockForCapture()).thenReturn(new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                    unknown, new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0), unknown));
            var snapshots = spy(new CatalogueSnapshotService(nodes, overlay, CatalogueRuntimePolicy.fullCatalogue(), journal));
            if (modelBound) doAnswer(call -> snapshots.captureRoots(call.getArgument(0), Set.of("BP", "CP")))
                    .when(snapshots).captureRevision(any(), eq("fixture-catalogue"), anySet());
            var embedding = mock(LocalEmbeddingService.class);
            providers = new LlmProviderConfig(embedding);
            String endpoint = "https://fixture.invalid/chat/completions";
            ReflectionTestUtils.setField(providers, "customLlmUrl", endpoint);
            ReflectionTestUtils.setField(providers, "customLlmModel", "fixture");
            ReflectionTestUtils.setField(providers, "llmProviderConfig", "CUSTOM_OPENAI");
            var http = new RestTemplate(); gatewayServer = MockRestServiceServer.bindTo(http).build();
            gatewayServer.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), request -> { }).andRespond(request -> {
                String sent = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                String root = sent.contains("SCORE:ROOT:BP") ? "BP" : sent.contains("SCORE:ROOT:CP") ? "CP"
                        : sent.contains("SCORE:CHILD:unrelated-code") ? "unrelated-code"
                        : sent.contains("SCORE:PRODUCT:accepted-item") ? "accepted-item"
                        : sent.contains("SCORE:PRODUCT:open-item") ? "open-item" : null;
                if (root == null && !sent.contains("SCORE:")) root = "RELATIONS";
                if (root == null) throw new AssertionError("Unexpected provider request: " + sent);
                calls.merge(root, 1, Integer::sum);
                String answer = "RELATIONS".equals(root)
                        ? "{\"selections\":[{\"nodeId\":\"unrelated-code\",\"outcome\":\"REJECT\",\"contributions\":[],\"rationale\":\"No extra contribution in this fixture\",\"question\":\"\"}]}"
                        : answers.getOrDefault(root, answer(root, "BP".equals(root) ? 71.125 : 42.5));
                return withSuccess(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", answer))))),
                        MediaType.APPLICATION_JSON).createResponse(request);
            });
            AnalysisRuntimeSettings settings = (key, fallback) -> "llm.retry.max".equals(key) ? 0 : fallback;
            var gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, endpoint, "fixture", 0, http, mapper,
                    new LlmResponseParser(mapper), settings, null, null);
            var gateways = mock(LlmGatewayRegistry.class);
            when(gateways.getGatewayById(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(gateway);
            var prompts = mock(PromptTemplateService.class);
            when(prompts.renderRootPrompt(anyString(), anyString(), anyString())).thenAnswer(call -> "SCORE:ROOT:" + call.getArgument(0));
            when(prompts.renderPrompt(anyString(), anyString(), anyString(), anyDouble(), anyString()))
                    .thenAnswer(call -> "SCORE:CHILD:" + call.getArgument(4));
            when(prompts.renderProductPrompt(anyString(), anyString(), anyString(), anyInt()))
                    .thenAnswer(call -> "SCORE:PRODUCT:" + call.getArgument(2));
            var llm = new LlmService(providers, gateways, mapper, catalogue, prompts, embedding, mock(SavedAnalysisService.class));
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
            ReflectionTestUtils.setField(llm, "productBatchSize", 1);
            var budget = mock(AiPromptBudgetPolicy.class); var rules = new RelationCompatibilityMatrix();
            var relations = new RequirementRelationSearchService(catalogue, rules, llm, budget);
            var views = mock(RepositoryStateService.class);
            when(views.getViewContext("alice", "draft", scope)).thenReturn(new ViewContext("commit", "draft", null, false, false, false));
            var workspace = new UserWorkspace(); workspace.setUsername("alice"); var workspaces = mock(WorkspaceManager.class);
            when(workspaces.getWorkspaceById("ws")).thenReturn(workspace);
            continuations = new AnalysisContinuationService(store, catalogue, llm, workspaces, mapper);
            ReflectionTestUtils.setField(continuations, "providers", providers);
            ReflectionTestUtils.setField(continuations, "views", views);
            ReflectionTestUtils.setField(continuations, "relations", relations);
            ReflectionTestUtils.setField(continuations, "catalogueSnapshots", snapshots);
            if (modelBound) ReflectionTestUtils.setField(continuations, "admissionModels", (AnalysisAdmissionModelPort)
                    (workspaceContext, commit, revision, requestedScope) -> new AnalysisModelBinding(AnalysisModelBinding.State.RESOLVED,
                            "repo", "ws", "draft", "a".repeat(40), 9L, "GIT_CHECKPOINT", "a".repeat(64),
                            "fixture-catalogue", "standard", Set.of("BP", "CP"), requestedScope, "brief-1",
                            Map.of(), List.of(), Set.of(), null));
            var metadata = mock(ArchitectureReportMetadataPort.class);
            when(metadata.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
            var architecture = mock(RequirementArchitectureViewService.class);
            when(architecture.buildFromEvidence(any(), any(), anyInt(), any(), anySet())).thenReturn(new RequirementArchitectureView());
            var usecase = new AnalyzeRequirementUseCase(llm, budget, architecture, new AnalysisRelationGenerator(rules, nodes),
                    mock(HypothesisService.class), views, metadata);
            ReflectionTestUtils.setField(usecase, "requirementRelationSearch", relations);
            ReflectionTestUtils.setField(usecase, "catalogueSnapshots", snapshots);
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentUsername()).thenReturn("alice"); when(resolver.resolveCurrentContext()).thenReturn(scope);
            var controller = new AnalysisApiController(catalogue, executor, mapper, usecase, mock(StreamRequirementAnalysisUseCase.class),
                    mock(AnalyzeNodeChildrenUseCase.class), mock(JustifyLeafUseCase.class), new AnalysisSseEventMapper(), views, resolver, mock(MessageSource.class));
            ReflectionTestUtils.setField(controller, "continuationService", continuations);
            ReflectionTestUtils.setField(controller, "followUpProviders", new AnalysisFollowUpProviders(providers));
            mvc = MockMvcBuilders.standaloneSetup(controller).build();
        }

        String answer(String code, double score) {
            return mapper.writeValueAsString(Map.of(code, Map.of("score", score,
                    "reason", "Original " + code + " explanation.\nRetain verbatim.")));
        }

        Map<String, Object> start(Set<String> roots) {
            var request = new LinkedHashMap<String, Object>();
            request.put("businessText", "Read unchanged evidence"); request.put("provider", "CUSTOM_OPENAI");
            request.put("resumable", true); request.put("continuationId", UUID.randomUUID().toString());
            request.put("analysisScope", Map.of("taxonomyRoots", roots, "mode", "TAXONOMIES_ONLY"));
            return request;
        }
        Map<String, Object> extend(AnalysisResult source, Set<String> roots) {
            var request = start(roots);
            request.put("taxonomySourceContinuationId", source.getRecovery().id());
            request.put("taxonomySourceVersion", source.getRecovery().version());
            return request;
        }
        AnalysisResult analyze(Map<String, Object> request) throws Exception {
            var response = mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getErrorMessage()).isEqualTo(200);
            return mapper.readValue(response.getContentAsString(), AnalysisResult.class);
        }
        void reject(Map<String, Object> request, int expectedStatus) throws Exception {
            mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().is(expectedStatus));
        }
        public void close() { executor.close(); bean.destroy(); }
    }
}
