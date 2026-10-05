package com.taxonomy.relations.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslPublicationPort;
import com.taxonomy.workspace.service.WorkspaceRepositoryContextPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HypothesisLifecycleLoggingTest {

    // Synthetic unit fixtures, not catalogue identifiers or credentials.
    private static final String SOURCE = "private-source-marker";
    private static final String TARGET = "private-target-marker";
    private static final String SESSION = "private-session-marker";
    private static final long HYPOTHESIS_ID = 918273645L;
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace(
            "private-repository-marker", "private-workspace-marker",
            "private-branch-marker", "private-owner-marker");
    private final RelationHypothesisRepository hypotheses = mock(RelationHypothesisRepository.class);
    private final TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
    private final WorkspaceDslPublicationPort publication = mock(WorkspaceDslPublicationPort.class);
    private final HypothesisService service = new HypothesisService(
            hypotheses, mock(RelationEvidenceRepository.class), mock(TaxonomyRelationService.class),
            nodes, publication, mock(WorkspaceRepositoryContextPort.class));
    private final Logger logger = (Logger) LoggerFactory.getLogger(HypothesisService.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureProductiveLogs() throws Exception {
        when(hypotheses.save(any(RelationHypothesis.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(publication.publishSnapshot(any(), any(), any(), any())).thenReturn("private-commit-marker");
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restoreLoggingAndSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void persistenceAndPublicationLogCountsWithoutPrivateModelOrSession() throws Exception {
        var persisted = service.persistFromAnalysis(List.of(input()), SESSION, CONTEXT);

        assertThat(persisted).singleElement().satisfies(hypothesis -> {
            assertThat(hypothesis.getSourceNodeId()).isEqualTo(SOURCE);
            assertThat(hypothesis.getTargetNodeId()).isEqualTo(TARGET);
            assertThat(hypothesis.getAnalysisSessionId()).isEqualTo(SESSION);
            assertThat(hypothesis.getStatus()).isEqualTo(HypothesisStatus.PROVISIONAL);
        });
        ArgumentCaptor<String> dsl = ArgumentCaptor.forClass(String.class);
        verify(publication).publishSnapshot(eq(CONTEXT), eq("draft"), dsl.capture(), any());
        assertThat(dsl.getValue()).contains(SOURCE, TARGET, SESSION);
        assertSafeLogs();
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                assertThat(message).contains("Persisted hypotheses", "scope=WORKSPACE", "count=1"));
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                assertThat(message).contains("Committed hypotheses as canonical DSL", "scope=WORKSPACE", "count=1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptanceLogsOutcomeWithoutPrivateHypothesisIdentity(boolean nodesPresent) {
        RelationHypothesis hypothesis = hypothesis();
        when(hypotheses.findByIdInRepositoryWorkspace(
                CONTEXT.repositoryId(), HYPOTHESIS_ID, CONTEXT.workspaceId()))
                .thenReturn(Optional.of(hypothesis));
        if (nodesPresent) {
            when(nodes.findByCode(SOURCE)).thenReturn(Optional.of(node(SOURCE)));
            when(nodes.findByCode(TARGET)).thenReturn(Optional.of(node(TARGET)));
        }

        assertThat(service.accept(HYPOTHESIS_ID, CONTEXT)).isSameAs(hypothesis);

        assertThat(hypothesis.getStatus()).isEqualTo(HypothesisStatus.ACCEPTED);
        assertSafeLogs();
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                assertThat(message).contains("Accepted hypothesis", "scope=WORKSPACE", "count=1",
                        "relationCreated=" + nodesPresent));
        if (!nodesPresent) {
            assertThat(events.list).filteredOn(event -> event.getLevel() == Level.WARN)
                    .singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains("Could not create relation for hypothesis", "reason=NODE_NOT_FOUND"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void afterCommitFailureLogsBoundedReasonWithoutPrivateExceptionCause(boolean checked) throws Exception {
        Exception failure = checked
                ? new IOException("credential=synthetic-private-secret-marker")
                : new IllegalStateException("credential=synthetic-private-secret-marker");
        when(publication.publishSnapshot(any(), any(), any(), any())).thenThrow(failure);
        TransactionSynchronizationManager.initSynchronization();

        var persisted = service.persistFromAnalysisAfterCommit(List.of(input()), SESSION, CONTEXT);
        assertThat(persisted).hasSize(1);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();

        assertThat(persisted.getFirst().getAnalysisSessionId()).isEqualTo(SESSION);
        assertSafeLogs();
        assertThat(events.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("Failed to publish committed hypotheses", "scope=WORKSPACE", "count=1",
                                "reason=PUBLICATION_FAILED"));
    }

    private void assertSafeLogs() {
        assertThat(events.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain("private-", "credential=", String.valueOf(HYPOTHESIS_ID), "RELATED_TO");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static RelationHypothesisDto input() {
        return new RelationHypothesisDto(SOURCE, SOURCE, TARGET, TARGET,
                "RELATED_TO", 0.82, "private-rationale-marker");
    }

    private static RelationHypothesis hypothesis() {
        RelationHypothesis hypothesis = new RelationHypothesis();
        hypothesis.setId(HYPOTHESIS_ID);
        hypothesis.setRepositoryId(CONTEXT.repositoryId());
        hypothesis.setWorkspaceId(CONTEXT.workspaceId());
        hypothesis.setOwnerUsername(CONTEXT.username());
        hypothesis.setSourceNodeId(SOURCE);
        hypothesis.setTargetNodeId(TARGET);
        hypothesis.setRelationType(RelationType.RELATED_TO);
        hypothesis.setStatus(HypothesisStatus.PROVISIONAL);
        return hypothesis;
    }

    private static TaxonomyNode node(String code) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        node.setTaxonomyRoot("CP");
        return node;
    }
}
