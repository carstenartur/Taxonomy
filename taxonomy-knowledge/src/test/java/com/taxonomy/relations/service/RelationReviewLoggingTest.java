package com.taxonomy.relations.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationReviewLoggingTest {

    private static final long PROPOSAL_ID = 918273645L;
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace(
            "private-repository-marker", "private-workspace-marker",
            "private-branch-marker", "private-owner-marker");
    private final RelationProposalRepository proposals = mock(RelationProposalRepository.class);
    private final TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
    private final RelationProposalService mapping = mock(RelationProposalService.class);
    private final RelationReviewService service = new RelationReviewService(proposals, relations, mapping);
    private final Logger logger = (Logger) LoggerFactory.getLogger(RelationReviewService.class);
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
    void acceptanceLogsOperationWithoutThePrivateProposal() {
        RelationProposal proposal = stubProposal(ProposalStatus.PENDING);
        TaxonomyRelationDto relation = new TaxonomyRelationDto();
        when(relations.createRelationInContext(any(), any(), any(), any(), any(), any()))
                .thenReturn(relation);

        assertThat(service.acceptProposal(PROPOSAL_ID, CONTEXT)).isSameAs(relation);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(proposal.getReviewedAt()).isNotNull();
        assertSafeLog("Accepted proposal");
    }

    @Test
    void rejectionLogsOperationWithoutThePrivateProposal() {
        RelationProposal proposal = stubProposal(ProposalStatus.PENDING);
        RelationProposalDto response = new RelationProposalDto();
        when(mapping.toDto(proposal)).thenReturn(response);

        assertThat(service.rejectProposal(PROPOSAL_ID, CONTEXT)).isSameAs(response);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.REJECTED);
        assertThat(proposal.getReviewedAt()).isNotNull();
        assertSafeLog("Rejected proposal");
    }

    @ParameterizedTest
    @EnumSource(value = ProposalStatus.class, names = {"ACCEPTED", "REJECTED"})
    void revertLogsPreviousStatusWithoutThePrivateProposal(ProposalStatus previousStatus) {
        RelationProposal proposal = stubProposal(previousStatus);
        RelationProposalDto response = new RelationProposalDto();
        when(mapping.toDto(proposal)).thenReturn(response);

        assertThat(service.revertProposal(PROPOSAL_ID, CONTEXT)).isSameAs(response);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(proposal.getReviewedAt()).isNull();
        assertSafeLog("Reverted proposal");
        assertThat(events.list.getFirst().getFormattedMessage()).contains("previousStatus=" + previousStatus);
    }

    private void assertSafeLog(String operation) {
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .doesNotContain("private-", String.valueOf(PROPOSAL_ID), "RELATED_TO")
                    .contains(operation, "scope=WORKSPACE", "count=1");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private RelationProposal stubProposal(ProposalStatus status) {
        // Synthetic unit fixtures, not catalogue identifiers.
        TaxonomyNode source = new TaxonomyNode();
        source.setCode("private-source-marker");
        TaxonomyNode target = new TaxonomyNode();
        target.setCode("private-target-marker");
        RelationProposal proposal = new RelationProposal();
        proposal.setId(PROPOSAL_ID);
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(RelationType.RELATED_TO);
        proposal.setRationale("private-rationale-marker");
        proposal.setStatus(status);
        when(proposals.findByIdInRepositoryWorkspace(
                CONTEXT.repositoryId(), PROPOSAL_ID, CONTEXT.workspaceId()))
                .thenReturn(Optional.of(proposal));
        return proposal;
    }
}
