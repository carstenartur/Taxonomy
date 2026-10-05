package com.taxonomy.relations.controller;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.ProposalReviewStateStore;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** HTTP input and tenant contracts with the real review and proposal state services. */
class ProposalApiControllerBulkHttpTest {

    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);
    private static final String HEAD_C = "c".repeat(40);
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace(
            "repo-a", "workspace-a", "draft", "alice");

    private final RelationProposalRepository proposals = mock(RelationProposalRepository.class);
    private final GitAuthoritativeRelationMutationService mutations =
            mock(GitAuthoritativeRelationMutationService.class);
    private final RelationBranchProjectionReadinessService readiness =
            mock(RelationBranchProjectionReadinessService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final Map<Long, RelationProposal> activeProposals = new LinkedHashMap<>();
    private String currentHead;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        currentHead = HEAD_A;
        activeProposals.put(42L, proposal(42L, "repo-a", "workspace-a"));
        activeProposals.put(43L, proposal(43L, "repo-a", "workspace-a"));
        activeProposals.put(Long.MAX_VALUE, proposal(Long.MAX_VALUE, "repo-a", "workspace-a"));
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(CONTEXT);
        when(readiness.readCurrentHead(CONTEXT)).thenAnswer(invocation -> currentHead);
        when(proposals.findByIdInRepositoryWorkspace(eq("repo-a"), anyLong(), eq("workspace-a")))
                .thenAnswer(invocation -> Optional.ofNullable(activeProposals.get(invocation.getArgument(1))));
        when(proposals.findByIdInRepositoryWorkspaceForUpdate(eq("repo-a"), anyLong(), eq("workspace-a")))
                .thenAnswer(invocation -> Optional.ofNullable(activeProposals.get(invocation.getArgument(1))));
        when(mutations.upsert(eq(CONTEXT), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String expectedHead = invocation.getArgument(1);
                    if (!Objects.equals(currentHead, expectedHead)) {
                        throw new BranchHeadConflictException(
                                CONTEXT.branch(), expectedHead, currentHead, "Head changed");
                    }
                    String previousHead = currentHead;
                    currentHead = HEAD_A.equals(currentHead) ? HEAD_B : HEAD_C;
                    RelationDefinition definition = invocation.getArgument(2);
                    CommandMetadata metadata = invocation.getArgument(3);
                    return new MutationResult(
                            new CommandResult(CONTEXT.repositoryId(), CONTEXT.workspaceId(),
                                    CONTEXT.branch(), CONTEXT.scope(), previousHead, currentHead,
                                    ChangeKind.UPDATED, true, metadata.causationId()),
                            new ProjectionResult(ProjectionOutcome.UPDATED, currentHead,
                                    "accepted".equals(definition.status())));
                });
        var reviewService = new GitAuthoritativeProposalReviewService(
                new ProposalReviewStateStore(proposals), mutations);
        var controller = new ProposalApiController(mock(RelationProposalService.class),
                reviewService, readiness, resolver, mock(SystemRepositoryService.class),
                mock(RepositoryMembershipService.class));
        mvc = standaloneSetup(controller).build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"bad\"", "true", "{}", "[]", "42.5", "42.0", "9007199254740993.0",
            "9223372036854775808", "18446744073709551658", "-1", "0"
    })
    void malformedLaterIdRejectsTheWholeRequestBeforeAnyMutation(String invalidId) {
        assertAll(
                () -> mvc.perform(post("/api/proposals/bulk")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\":[42," + invalidId + "],\"action\":\"ACCEPT\"}"))
                        .andExpect(status().isBadRequest()),
                () -> verifyNoInteractions(mutations),
                () -> assertThat(activeProposals.get(42L).getStatus()).isEqualTo(ProposalStatus.PENDING),
                () -> assertThat(currentHead).isEqualTo(HEAD_A));
    }

    @Test
    void fullRangeIntegerIdsRetainTheirExactValueAndAdvanceTheExpectedHead() throws Exception {
        mvc.perform(post("/api/proposals/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42,9223372036854775807],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, '"' + HEAD_C + '"'))
                .andExpect(jsonPath("$.projected").value(2))
                .andExpect(jsonPath("$.complete").value(true));

        verify(proposals).findByIdInRepositoryWorkspace("repo-a", Long.MAX_VALUE, "workspace-a");
        verify(mutations).upsert(eq(CONTEXT), eq(HEAD_A), any(), any());
        verify(mutations).upsert(eq(CONTEXT), eq(HEAD_B), any(), any());
        assertThat(activeProposals.get(Long.MAX_VALUE).getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
    }

    @Test
    void nullIdKeepsTheExistingPerItemFailureAndSuccessfulReviewOrdering() throws Exception {
        mvc.perform(post("/api/proposals/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42,null,43],\"action\":\"REJECT\"}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.processed").value(3))
                .andExpect(jsonPath("$.projected").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.items[1].projectionStatus").value("INVALID_ID"))
                .andExpect(jsonPath("$.authoritativeCommitId").value(HEAD_C));

        assertThat(activeProposals.get(42L).getStatus()).isEqualTo(ProposalStatus.REJECTED);
        assertThat(activeProposals.get(43L).getStatus()).isEqualTo(ProposalStatus.REJECTED);
        verify(proposals, never()).findByIdInRepositoryWorkspace(any(), eq(null), any());
    }

    @ParameterizedTest
    @CsvSource(value = {"repo-b, workspace-b", "repo-a, workspace-b", "repo-a, NULL"}, nullValues = "NULL")
    void foreignProposalIsRejectedInPlaceWhileAuthorizedItemsUseOnlyTheResolvedTenant(
            String foreignRepository, String foreignWorkspace) throws Exception {
        RelationProposal foreign = proposal(44L, foreignRepository, foreignWorkspace);
        when(proposals.findById(44L)).thenReturn(Optional.of(foreign));
        when(proposals.findByIdInRepositoryWorkspace(foreignRepository, 44L, foreignWorkspace))
                .thenReturn(Optional.of(foreign));

        mvc.perform(post("/api/proposals/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42,44,43],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.processed").value(3))
                .andExpect(jsonPath("$.projected").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.items[1].proposalId").value(44))
                .andExpect(jsonPath("$.items[1].projectionStatus").value("REVIEW_REJECTED"))
                .andExpect(jsonPath("$.authoritativeCommitId").value(HEAD_C));

        verify(proposals).findByIdInRepositoryWorkspace("repo-a", 44L, "workspace-a");
        verify(proposals, never()).findById(any());
        verify(proposals, never()).findByIdInRepositoryWorkspace(foreignRepository, 44L, foreignWorkspace);
        verify(proposals, never()).findByIdInRepositoryWorkspaceForUpdate("repo-a", 44L, "workspace-a");
        verify(mutations, times(2)).upsert(eq(CONTEXT), anyString(), any(), any());
        assertThat(foreign.getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(activeProposals.get(42L).getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(activeProposals.get(43L).getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
    }

    @Test
    void staleExplicitHeadStopsTheBatchWithoutChangingGitOrProposalState() throws Exception {
        currentHead = HEAD_B;

        mvc.perform(post("/api/proposals/bulk")
                        .header(HttpHeaders.IF_MATCH, '"' + HEAD_A + '"')
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42,43],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isMultiStatus())
                .andExpect(header().string(HttpHeaders.ETAG, '"' + HEAD_B + '"'))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.projected").value(0))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.items[0].projectionStatus").value("PRECONDITION_FAILED"));

        verify(mutations).upsert(eq(CONTEXT), eq(HEAD_A), any(), any());
        verify(proposals, never()).save(any());
        verify(proposals, never()).findByIdInRepositoryWorkspace("repo-a", 43L, "workspace-a");
        assertThat(currentHead).isEqualTo(HEAD_B);
        assertThat(activeProposals.get(42L).getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(activeProposals.get(43L).getStatus()).isEqualTo(ProposalStatus.PENDING);
    }

    @Test
    void disappearedBranchAtExplicitHeadDoesNotAdvertiseStaleAuthority() throws Exception {
        when(mutations.upsert(eq(CONTEXT), eq(HEAD_A), any(), any()))
                .thenAnswer(invocation -> {
                    currentHead = null;
                    throw new BranchHeadConflictException(CONTEXT.branch(), HEAD_A, null, "Branch removed");
                });

        mvc.perform(post("/api/proposals/bulk")
                        .header(HttpHeaders.IF_MATCH, '"' + HEAD_A + '"')
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42,43],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isMultiStatus())
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.authoritativeCommitId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.projected").value(0))
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.items[0].projectionStatus").value("PRECONDITION_FAILED"));

        verify(proposals, never()).save(any());
        verify(proposals, never()).findByIdInRepositoryWorkspace("repo-a", 43L, "workspace-a");
        assertThat(currentHead).isNull();
        assertThat(activeProposals.get(42L).getStatus()).isEqualTo(ProposalStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "W/\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"", "not-a-commit"})
    void malformedExplicitHeadIsBadRequestBeforeAnyMutation(String ifMatch) throws Exception {
        mvc.perform(post("/api/proposals/bulk")
                        .header(HttpHeaders.IF_MATCH, ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mutations, proposals);
    }

    @Test
    void malformedIdempotencyKeyIsBadRequestBeforeAnyMutation() throws Exception {
        mvc.perform(post("/api/proposals/bulk")
                        .header("Idempotency-Key", "request\nforged")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mutations, proposals);
    }

    @Test
    void centralReaderCannotProbeAnyBatchProposal() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.centralRead("repo-a", "draft", "reader"));

        mvc.perform(post("/api/proposals/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42,44],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(proposals, mutations, readiness);
    }

    private static RelationProposal proposal(long id, String repositoryId, String workspaceId) {
        RelationProposal proposal = new RelationProposal();
        proposal.setId(id);
        proposal.setRepositoryId(repositoryId);
        proposal.setWorkspaceId(workspaceId);
        TaxonomyNode source = new TaxonomyNode();
        source.setCode("BP");
        TaxonomyNode target = new TaxonomyNode();
        target.setCode("CP");
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(RelationType.SUPPORTS);
        return proposal;
    }
}
