package com.taxonomy.relations.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslPublicationPort;
import com.taxonomy.workspace.service.WorkspaceRepositoryContextPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HypothesisValidationLoggingTest {

    private static final RepositoryContext CONTEXT = RepositoryContext.workspace(
            "repo-a", "workspace-a", "draft", "alice");

    private final RelationHypothesisRepository hypothesisRepository =
            mock(RelationHypothesisRepository.class);
    private final WorkspaceDslPublicationPort publication = mock(WorkspaceDslPublicationPort.class);
    private final HypothesisService service = new HypothesisService(
            hypothesisRepository,
            mock(RelationEvidenceRepository.class),
            mock(TaxonomyRelationService.class),
            mock(TaxonomyNodeRepository.class),
            publication,
            mock(WorkspaceRepositoryContextPort.class));
    private final Logger logger = (Logger) LoggerFactory.getLogger(HypothesisService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureWarnings() {
        when(hypothesisRepository.save(any(RelationHypothesis.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogging() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void reportsWarningCountWithoutModelDetailsAtBothValidationPasses() throws Exception {
        CanonicalArchitectureModel published = persistAndPublish(List.of(hypothesis("BP", "CP")));

        assertThat(new DslValidator().validate(published).getWarnings()).hasSize(1);
        assertSafeWarnings(1);
    }

    @Test
    void countsEveryWarningWithoutDumpingTheWarningList() throws Exception {
        CanonicalArchitectureModel published = persistAndPublish(List.of(
                hypothesis("BP", "CP"), hypothesis("CP", "BP")));

        assertThat(new DslValidator().validate(published).getWarnings()).hasSize(2);
        assertSafeWarnings(2);
    }

    @Test
    void publishesValidHypothesesWithoutWarningLogs() throws Exception {
        CanonicalArchitectureModel published = persistAndPublish(List.of(hypothesis("CP", "CR")));

        assertThat(new DslValidator().validate(published).getWarnings()).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    private void assertSafeWarnings(int count) {
        assertThat(appender.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .doesNotContain("BP", "CP", "REALIZES", "valid source type", "valid target type")
                    .contains("validation warnings", "count=" + count);
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "generated hypothesis model contains validation warnings (count=" + count + ")",
                        "round-tripped hypothesis DSL contains validation warnings (count=" + count + ")");
    }

    private CanonicalArchitectureModel persistAndPublish(List<RelationHypothesisDto> hypotheses)
            throws Exception {
        List<RelationHypothesis> persisted = service.persistFromAnalysis(hypotheses, "analysis-1", CONTEXT);

        assertThat(persisted).hasSize(hypotheses.size());
        for (int i = 0; i < hypotheses.size(); i++) {
            RelationHypothesisDto input = hypotheses.get(i);
            RelationHypothesis saved = persisted.get(i);
            assertThat(saved.getSourceNodeId()).isEqualTo(input.getSourceCode());
            assertThat(saved.getTargetNodeId()).isEqualTo(input.getTargetCode());
            assertThat(saved.getRelationType().name()).isEqualTo(input.getRelationType());
            assertThat(saved.getConfidence()).isEqualTo(input.getConfidence());
            assertThat(saved.getStatus()).isEqualTo(HypothesisStatus.PROVISIONAL);
            assertThat(saved.getRepositoryId()).isEqualTo(CONTEXT.repositoryId());
            assertThat(saved.getWorkspaceId()).isEqualTo(CONTEXT.workspaceId());
            assertThat(saved.getOwnerUsername()).isEqualTo(CONTEXT.username());
            assertThat(saved.getAnalysisSessionId()).isEqualTo("analysis-1");
        }

        ArgumentCaptor<String> dsl = ArgumentCaptor.forClass(String.class);
        verify(publication).publishSnapshot(same(CONTEXT), eq("draft"), dsl.capture(),
                eq("Auto-generated from analysis session analysis-1"));
        CanonicalArchitectureModel published = new AstToModelMapper().map(
                new TaxDslParser().parse(dsl.getValue(), "hypotheses.taxdsl"));
        assertThat(published.getRelations()).hasSize(hypotheses.size());
        for (int i = 0; i < hypotheses.size(); i++) {
            assertThat(published.getRelations().get(i).getSourceId())
                    .isEqualTo(hypotheses.get(i).getSourceCode());
            assertThat(published.getRelations().get(i).getTargetId())
                    .isEqualTo(hypotheses.get(i).getTargetCode());
            assertThat(published.getRelations().get(i).getRelationType())
                    .isEqualTo(hypotheses.get(i).getRelationType());
        }
        return published;
    }

    private RelationHypothesisDto hypothesis(String source, String target) {
        return new RelationHypothesisDto(source, source, target, target, "REALIZES", 0.82, null);
    }
}
