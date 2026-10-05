package com.taxonomy.dsl.export;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DslMaterializeLoggingTest {

    private static final String PRIVATE_PATH = "private-path-marker.taxdsl";
    private static final String PRIVATE_FAILURE = "credential=synthetic-private-secret-marker";
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace(
            "private-repository-marker", "private-workspace-marker",
            "private-branch-marker", "private-owner-marker");
    // Synthetic model identifiers, not catalogue entries.
    private static final String ELEMENTS = """
            element private-source-marker type Capability {
              title: "private-source-title-marker";
            }
            element private-target-marker type CoreService {
              title: "private-target-title-marker";
            }
            """;

    private final TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
    private final RelationHypothesisRepository hypotheses = mock(RelationHypothesisRepository.class);
    private final ArchitectureDslDocumentRepository documents = mock(ArchitectureDslDocumentRepository.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final DslMaterializeService service = new DslMaterializeService(
            relations, hypotheses, documents, null, resolver, null);
    private final Logger logger = (Logger) LoggerFactory.getLogger(DslMaterializeService.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureProductiveLogs() {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(CONTEXT);
        when(documents.save(any(ArchitectureDslDocument.class))).thenAnswer(invocation -> {
            ArchitectureDslDocument document = invocation.getArgument(0);
            document.setId(1L);
            return document;
        });
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restoreLogging() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void fullMaterializationLogsCountsAndPreservesPrivateDocumentContent() {
        String dsl = ELEMENTS + relation("accepted") + """
                relation private-target-marker RELATED_TO private-source-marker {
                  status: proposed;
                }
                """;

        var result = service.materialize(dsl, PRIVATE_PATH, CONTEXT.branch(), "private-commit-marker");

        assertThat(result.valid()).isTrue();
        assertThat(result.relationsCreated()).isEqualTo(1);
        assertThat(result.hypothesesCreated()).isEqualTo(1);
        assertThat(result.documentId()).isEqualTo(1L);
        ArgumentCaptor<ArchitectureDslDocument> document = ArgumentCaptor.forClass(ArchitectureDslDocument.class);
        verify(documents).save(document.capture());
        assertThat(document.getValue().getRawContent()).isEqualTo(dsl);
        assertThat(document.getValue().getPath()).isEqualTo(PRIVATE_PATH);
        assertSafeLogs();
        assertThat(events.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .contains("Materialized DSL document", "scope=WORKSPACE", "relations=1", "hypotheses=1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "proposed", "provisional"})
    void skippedProjectionLogsBoundedReasonWithoutPrivateEndpointsOrException(String status) {
        if ("accepted".equals(status)) {
            when(relations.createRelationInContext(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException(PRIVATE_FAILURE));
        } else {
            when(hypotheses.save(any(RelationHypothesis.class)))
                    .thenThrow(new IllegalArgumentException(PRIVATE_FAILURE));
        }

        var result = service.materialize(ELEMENTS + relation(status), PRIVATE_PATH, CONTEXT.branch(), null);

        assertThat(result.valid()).isTrue();
        assertThat(result.relationsCreated()).isZero();
        assertThat(result.hypothesesCreated()).isZero();
        assertThat(result.documentId()).isEqualTo(1L);
        assertSafeLogs();
        assertThat(events.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("accepted".equals(status) ? "Skipped DSL relation" : "Skipped DSL hypothesis",
                                "reason=INVALID_ARGUMENT"));
    }

    @Test
    void incrementalMaterializationLogsCountsWithoutTenantOrDocumentIdentity() {
        ArchitectureDslDocument after = new ArchitectureDslDocument();
        after.setId(2L);
        after.setPath(PRIVATE_PATH);
        after.setRawContent(ELEMENTS + relation("accepted"));
        when(documents.findById(2L)).thenReturn(Optional.of(after));

        var result = service.materializeIncremental(null, 2L);

        assertThat(result.valid()).isTrue();
        assertThat(result.relationsCreated()).isEqualTo(1);
        assertThat(result.hypothesesCreated()).isZero();
        assertThat(result.documentId()).isEqualTo(2L);
        assertSafeLogs();
        assertThat(events.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .contains("Incremental materialization", "scope=WORKSPACE",
                                "relations=1", "hypotheses=0", "changes=3"));
    }

    private void assertSafeLogs() {
        assertThat(events.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("private-", "credential=", "RELATED_TO");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static String relation(String status) {
        return """
                relation private-source-marker RELATED_TO private-target-marker {
                  status: %s;
                }
                """.formatted(status);
    }
}
