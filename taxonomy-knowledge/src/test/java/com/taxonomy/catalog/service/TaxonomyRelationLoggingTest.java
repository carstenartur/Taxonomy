package com.taxonomy.catalog.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaxonomyRelationLoggingTest {

    // Synthetic unit fixtures, not catalogue identifiers.
    private static final String SOURCE = "private-source-marker";
    private static final String TARGET = "private-target-marker";
    private static final long RELATION_ID = 918273645L;
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace(
            "private-repository-marker", "private-workspace-marker",
            "private-branch-marker", "private-owner-marker");

    private final TaxonomyRelationRepository relations = mock(TaxonomyRelationRepository.class);
    private final TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
    private final TaxonomyRelationService service = new TaxonomyRelationService(
            relations, nodes, mock(SystemRepositoryService.class));
    private final Logger logger = (Logger) LoggerFactory.getLogger(TaxonomyRelationService.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureProductiveLogs() {
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
    void creationLogsOnlyOperationScopeAndCountWhilePreservingTheRelation() {
        stubNodes();
        when(relations.save(any(TaxonomyRelation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createRelationInContext(
                SOURCE, TARGET, RelationType.RELATED_TO,
                "private-description-marker", "private-provenance-marker", CONTEXT);

        assertThat(created.getSourceCode()).isEqualTo(SOURCE);
        assertThat(created.getTargetCode()).isEqualTo(TARGET);
        assertThat(created.getDescription()).isEqualTo("private-description-marker");
        assertThat(created.getProvenance()).isEqualTo("private-provenance-marker");
        assertSafeLog("Created relation", "count=1");
    }

    @Test
    void deletionByIdDoesNotLogPrivateRelationOrTenantIdentity() {
        TaxonomyRelation relation = relation();
        when(relations.findByIdInRepositoryWorkspace(
                CONTEXT.repositoryId(), RELATION_ID, CONTEXT.workspaceId()))
                .thenReturn(Optional.of(relation));

        service.deleteRelationInContext(RELATION_ID, CONTEXT);

        verify(relations).delete(relation);
        assertSafeLog("Deleted relation", "count=1");
    }

    @Test
    void deletionByEndpointsLogsTheNumberDeletedWithoutThePrivateMatch() {
        List<TaxonomyRelation> matches = List.of(relation(), relation());
        when(relations.findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                CONTEXT.repositoryId(), CONTEXT.workspaceId(), SOURCE, TARGET, RelationType.RELATED_TO))
                .thenReturn(matches);

        service.deleteRelationBySourceTargetTypeInContext(
                SOURCE, TARGET, RelationType.RELATED_TO, CONTEXT);

        verify(relations).deleteAll(matches);
        assertSafeLog("Deleted relations", "count=2");
    }

    @Test
    void missingSourceExceptionDoesNotCarryPrivateInputIntoCallerDiagnostics() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.createRelationInContext(
                        SOURCE, TARGET, RelationType.RELATED_TO, null, null, CONTEXT))
                .withMessage("Source node not found");
    }

    @Test
    void missingTargetExceptionDoesNotCarryPrivateInputIntoCallerDiagnostics() {
        when(nodes.findByCode(SOURCE)).thenReturn(Optional.of(node(SOURCE)));

        assertThatIllegalArgumentException().isThrownBy(() -> service.createRelationInContext(
                        SOURCE, TARGET, RelationType.RELATED_TO, null, null, CONTEXT))
                .withMessage("Target node not found");
    }

    @Test
    void duplicateExceptionDoesNotCarryThePrivateRelationIntoCallerDiagnostics() {
        stubNodes();
        when(relations.findVisibleByRepositoryAndWorkspaceAndSourceTargetType(
                CONTEXT.repositoryId(), CONTEXT.workspaceId(), SOURCE, TARGET, RelationType.RELATED_TO))
                .thenReturn(List.of(relation()));

        assertThatIllegalArgumentException().isThrownBy(() -> service.createRelationInContext(
                        SOURCE, TARGET, RelationType.RELATED_TO, null, null, CONTEXT))
                .withMessage("Relation already exists in active repository/workspace");
    }

    @Test
    void missingRelationExceptionDoesNotCarryPrivateIdentityIntoCallerDiagnostics() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                        service.deleteRelationInContext(RELATION_ID, CONTEXT))
                .withMessage("Relation not found in active repository/workspace");
    }

    private void assertSafeLog(String operation, String count) {
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .doesNotContain("private-", String.valueOf(RELATION_ID), "RELATED_TO")
                    .contains(operation, "scope=WORKSPACE", count);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void stubNodes() {
        when(nodes.findByCode(SOURCE)).thenReturn(Optional.of(node(SOURCE)));
        when(nodes.findByCode(TARGET)).thenReturn(Optional.of(node(TARGET)));
    }

    private static TaxonomyRelation relation() {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setId(RELATION_ID);
        relation.setRepositoryId(CONTEXT.repositoryId());
        relation.setWorkspaceId(CONTEXT.workspaceId());
        relation.setSourceNode(node(SOURCE));
        relation.setTargetNode(node(TARGET));
        relation.setRelationType(RelationType.RELATED_TO);
        return relation;
    }

    private static TaxonomyNode node(String code) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        return node;
    }
}
