package com.taxonomy.relations.controller;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
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
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real controller, role evaluation, review and bookkeeping; persistence/Git ports are doubles. */
class ProposalApiControllerAuthorizationHttpTest {

    private static final String HEAD = "a".repeat(40);
    private static final String NEXT = "b".repeat(40);
    private static final RepositoryContext READ = RepositoryContext.centralRead("selected-repo", "selected-branch", "alice");
    private static final RepositoryContext WRITE = RepositoryContext.centralWrite("selected-repo", "selected-branch", "alice");
    private final RelationProposalRepository proposals = mock(RelationProposalRepository.class);
    private final RepositoryMembershipRepository memberships = mock(RepositoryMembershipRepository.class);
    private final GitAuthoritativeRelationMutationService mutations = mock(GitAuthoritativeRelationMutationService.class);
    private final RelationBranchProjectionReadinessService readiness = mock(RelationBranchProjectionReadinessService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        SystemRepository selected = new SystemRepository();
        selected.setRepositoryId("selected-repo");
        selected.setOwnerId("someone-else");
        when(repositories.getRepository("selected-repo")).thenReturn(selected);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(READ);
        var reviews = new GitAuthoritativeProposalReviewService(new ProposalReviewStateStore(proposals), mutations);
        mvc = standaloneSetup(new ProposalApiController(mock(RelationProposalService.class), reviews,
                readiness, resolver, repositories, new RepositoryMembershipService(memberships))).build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> deniedRequests() {
        return Stream.of("NONE", "READER", "CONTRIBUTOR").flatMap(role ->
                Stream.of("accept", "reject", "revert", "bulk-accept", "bulk-reject").flatMap(action ->
                        Stream.of(true, false).map(explicitHead ->
                                org.junit.jupiter.params.provider.Arguments.of(role, action, explicitHead))));
    }

    @ParameterizedTest
    @MethodSource("deniedRequests")
    void centralRolesBelowMaintainerCannotProbeIdsOrReachAnyEffect(
            String role, String action, boolean explicitHead) throws Exception {
        grant(role);
        // These supplied routing fields cannot grant access or change the server-resolved context.
        var request = post(endpoint(action, Long.MAX_VALUE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[42,9223372036854775807],\"action\":\""
                                + (action.endsWith("reject") ? "REJECT" : "ACCEPT")
                                + "\",\"repositoryId\":\"other-repo\",\"scope\":\"CENTRAL_WRITE\"}");
        if (explicitHead) {
            request.header(HttpHeaders.IF_MATCH, '"' + HEAD + '"');
        }
        mvc.perform(request)
                .andExpect(status().isForbidden());

        verifyNoInteractions(proposals, mutations, readiness);
        verify(memberships).findByRepositoryIdAndUsername("selected-repo", "alice");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> permittedRequests() {
        return Stream.of("MAINTAINER", "OWNER", "ADMIN").flatMap(role ->
                Stream.of("accept", "reject", "revert").map(action ->
                        org.junit.jupiter.params.provider.Arguments.of(role, action)));
    }

    @ParameterizedTest
    @MethodSource("permittedRequests")
    void authorizedReviewUsesOnlySelectedRepositoryBranchAndLockedProposal(String role, String action) throws Exception {
        grant(role);
        RelationProposal own = proposal(42L, "selected-repo", null, initialStatus(action));
        when(proposals.findByIdInRepositoryWorkspace("selected-repo", 42L, null)).thenReturn(Optional.of(own));
        when(proposals.findByIdInRepositoryWorkspaceForUpdate("selected-repo", 42L, null)).thenReturn(Optional.of(own));
        MutationResult effect = effect(action);
        when(mutations.upsert(eq(WRITE), eq(HEAD), any(), any())).thenReturn(effect);
        when(mutations.remove(eq(WRITE), eq(HEAD), any(), any())).thenReturn(effect);

        mvc.perform(post(endpoint(action, 42L))
                        .header(HttpHeaders.IF_MATCH, '"' + HEAD + '"')
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"other-repo\",\"workspaceId\":\"foreign-workspace\",\"branch\":\"other-branch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(targetStatus(action).name()))
                .andExpect(jsonPath("$.authoritativeCommitId").value(NEXT));

        var order = inOrder(proposals, mutations);
        order.verify(proposals).findByIdInRepositoryWorkspace("selected-repo", 42L, null);
        if ("revert".equals(action)) {
            order.verify(mutations).remove(eq(WRITE), eq(HEAD), any(), any());
        } else {
            order.verify(mutations).upsert(eq(WRITE), eq(HEAD), any(), any());
        }
        order.verify(proposals).findByIdInRepositoryWorkspaceForUpdate("selected-repo", 42L, null);
        order.verify(proposals).save(own);
        assertThat(own.getStatus()).isEqualTo(targetStatus(action));
        assertThat(own.getReviewedAt() == null).isEqualTo("revert".equals(action));
        verify(proposals, never()).findById(any());
        verifyNoInteractions(readiness);
        if ("ADMIN".equals(role)) {
            verifyNoInteractions(memberships);
        } else {
            verify(memberships).findByRepositoryIdAndUsername("selected-repo", "alice");
        }
    }

    @ParameterizedTest
    @CsvSource({"accept, -1", "accept, 0", "accept, 9223372036854775807",
            "reject, -1", "reject, 0", "reject, 9223372036854775807",
            "revert, -1", "revert, 0", "revert, 9223372036854775807"})
    void arbitraryMissingIdCannotMutateAnAuthorizedRepository(String action, long id) throws Exception {
        grant("MAINTAINER");

        mvc.perform(post(endpoint(action, id)).header(HttpHeaders.IF_MATCH, '"' + HEAD + '"'))
                .andExpect(status().isBadRequest());

        verify(proposals).findByIdInRepositoryWorkspace("selected-repo", id, null);
        verify(proposals, never()).findById(any());
        verify(proposals, never()).save(any());
        verifyNoInteractions(mutations, readiness);
    }

    @ParameterizedTest
    @CsvSource(value = {"accept, other-repo, NULL", "reject, other-repo, NULL", "revert, other-repo, NULL",
            "accept, selected-repo, foreign-workspace", "reject, selected-repo, foreign-workspace",
            "revert, selected-repo, foreign-workspace"}, nullValues = "NULL")
    void evenAdminCannotReviewAnExistingProposalOutsideSelectedContext(
            String action, String foreignRepository, String foreignWorkspace) throws Exception {
        grant("ADMIN");
        RelationProposal foreign = proposal(44L, foreignRepository, foreignWorkspace, initialStatus(action));
        when(proposals.findById(44L)).thenReturn(Optional.of(foreign));
        when(proposals.findByIdInRepositoryWorkspace(foreignRepository, 44L, foreignWorkspace)).thenReturn(Optional.of(foreign));

        mvc.perform(post(endpoint(action, 44L)).header(HttpHeaders.IF_MATCH, '"' + HEAD + '"'))
                .andExpect(status().isBadRequest());

        verify(proposals).findByIdInRepositoryWorkspace("selected-repo", 44L, null);
        verify(proposals, never()).findById(any());
        verify(proposals, never()).save(any());
        verifyNoInteractions(mutations, readiness);
        assertThat(foreign.getStatus()).isEqualTo(initialStatus(action));
        assertThat(foreign.getReviewedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "reject", "revert"})
    void staleHeadCannotAdvanceProposalBookkeepingForAnyReviewAction(String action) throws Exception {
        grant("MAINTAINER");
        RelationProposal own = proposal(42L, "selected-repo", null, initialStatus(action));
        when(proposals.findByIdInRepositoryWorkspace("selected-repo", 42L, null)).thenReturn(Optional.of(own));
        var conflict = new BranchHeadConflictException("selected-branch", HEAD, NEXT, "Head changed");
        when(mutations.upsert(eq(WRITE), eq(HEAD), any(), any())).thenThrow(conflict);
        when(mutations.remove(eq(WRITE), eq(HEAD), any(), any())).thenThrow(conflict);

        mvc.perform(post(endpoint(action, 42L)).header(HttpHeaders.IF_MATCH, '"' + HEAD + '"'))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.actualHeadCommit").value(NEXT));

        verify(proposals, never()).findByIdInRepositoryWorkspaceForUpdate(any(), any(), any());
        verify(proposals, never()).save(any());
        assertThat(own.getStatus()).isEqualTo(initialStatus(action));
        assertThat(own.getReviewedAt()).isNull();
    }

    private void grant(String role) {
        if ("ADMIN".equals(role)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        } else if (!"NONE".equals(role)) {
            RepositoryMembership membership = new RepositoryMembership();
            membership.setRepositoryId("selected-repo");
            membership.setUsername("alice");
            membership.setRole(RepositoryRole.valueOf(role));
            when(memberships.findByRepositoryIdAndUsername("selected-repo", "alice")).thenReturn(Optional.of(membership));
        }
    }

    private static String endpoint(String action, long id) {
        return action.startsWith("bulk-") ? "/api/proposals/bulk" : "/api/proposals/" + id + "/" + action;
    }

    private static ProposalStatus initialStatus(String action) {
        return "revert".equals(action) ? ProposalStatus.ACCEPTED : ProposalStatus.PENDING;
    }

    private static ProposalStatus targetStatus(String action) {
        return switch (action) {
            case "accept" -> ProposalStatus.ACCEPTED;
            case "reject" -> ProposalStatus.REJECTED;
            default -> ProposalStatus.PENDING;
        };
    }

    private static MutationResult effect(String action) {
        return new MutationResult(new CommandResult("selected-repo", null, "selected-branch", WRITE.scope(),
                HEAD, NEXT, ChangeKind.UPDATED, true, "fixture-review"),
                new ProjectionResult(ProjectionOutcome.UPDATED, NEXT, "accept".equals(action)));
    }

    private static RelationProposal proposal(long id, String repository, String workspace, ProposalStatus status) {
        RelationProposal proposal = new RelationProposal();
        proposal.setId(id);
        proposal.setRepositoryId(repository);
        proposal.setWorkspaceId(workspace);
        TaxonomyNode source = new TaxonomyNode();
        source.setCode("BP");
        TaxonomyNode target = new TaxonomyNode();
        target.setCode("CP");
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(RelationType.SUPPORTS);
        proposal.setStatus(status);
        return proposal;
    }
}
