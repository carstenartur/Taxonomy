package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.ProposalBranchAdoptionService;
import com.taxonomy.relations.service.ProposalBranchAdoptionService.Preview;
import com.taxonomy.relations.service.ProposalBranchAdoptionService.State;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ProposalBranchAdoptionApiControllerTest {
    private static final String PATH = "/api/proposals/42/adoption";
    private static final String SOURCE_SHA = "a".repeat(40);
    private static final String TARGET_SHA = "b".repeat(40);
    private static final RepositoryContext SOURCE = RepositoryContext.centralRead("project", "main", "alice");
    private static final RepositoryContext TARGET = RepositoryContext.workspace("project", "workspace-A", "draft", "alice");
    private final ProposalBranchAdoptionService adoption = mock(ProposalBranchAdoptionService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
    private final ContextNavigationService navigation = mock(ContextNavigationService.class);
    private final SystemRepository repository = new SystemRepository();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticate("ROLE_ARCHITECT");
        repository.setRepositoryId("project");
        repository.setDefaultBranch("main");
        when(repositories.getRepository("project")).thenReturn(repository);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(TARGET);
        when(memberships.canRead(repository, "alice")).thenReturn(true);
        when(memberships.canContribute(repository, "alice")).thenReturn(true);
        mvc = standaloneSetup(new ProposalBranchAdoptionApiController(
                adoption, resolver, repositories, memberships, navigation, Optional.empty())).build();
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void previewReportsBothSourcesAndLeavesTheTargetUntouched() throws Exception {
        when(adoption.preview(42L, SOURCE, TARGET)).thenReturn(preview(TARGET_SHA, State.AVAILABLE));
        mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sourceBranch").value("main"))
                .andExpect(jsonPath("$.sourceCommit").value(SOURCE_SHA))
                .andExpect(jsonPath("$.targetWorkspaceId").value("workspace-A"))
                .andExpect(jsonPath("$.targetRepositoryId").value("project"))
                .andExpect(jsonPath("$.targetBranch").value("draft"))
                .andExpect(jsonPath("$.targetCommit").value(TARGET_SHA))
                .andExpect(jsonPath("$.reviewedOnSourceBranch").value(true))
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.writable").value(true));
        verify(adoption).preview(42L, SOURCE, TARGET);
        verifyNoMoreInteractions(adoption);
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @Test
    void postUsesExactlyTheReviewedSourceAndTarget() throws Exception {
        String updated = "c".repeat(40);
        when(adoption.adopt(42L, SOURCE, SOURCE_SHA, TARGET, TARGET_SHA, new CommandMetadata("review-42")))
                .thenReturn(preview(updated, State.ALREADY_PRESENT));
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetCommit").value(updated))
                .andExpect(jsonPath("$.state").value("ALREADY_PRESENT"));
    }

    @Test
    void selectedWorkspaceChangeWithIdenticalBranchAndHeadRejectsBeforeReadingGit() throws Exception {
        mvc.perform(command("workspace-B", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isConflict());
        mvc.perform(command("workspace-A", "previous-default").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isConflict());
        verifyNoInteractions(adoption);
    }

    @Test
    void bothExactCommitPinsAreRequired() throws Exception {
        mvc.perform(command("workspace-A", "main")).andExpect(status().isPreconditionRequired());
        mvc.perform(command("workspace-A", "main").header("If-Match", "W/\"token\""))
                .andExpect(status().isBadRequest());
        mvc.perform(command("workspace-A", "main", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adoption);
    }

    @Test
    void staleGitCommitProduces412() throws Exception {
        when(adoption.adopt(42L, SOURCE, SOURCE_SHA, TARGET, TARGET_SHA, new CommandMetadata("review-42")))
                .thenThrow(new BranchHeadConflictException("draft", TARGET_SHA, "d".repeat(40), "moved"));
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void membershipRevocationPreventsDisclosureOfCentralSource() throws Exception {
        when(memberships.canRead(repository, "alice")).thenReturn(false);
        mvc.perform(get(PATH)).andExpect(status().isNotFound());
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isNotFound());
        verifyNoInteractions(adoption);
    }

    @Test
    void readerCanSeeEvidenceButCannotAdopt() throws Exception {
        authenticate("ROLE_READER");
        when(adoption.preview(42L, SOURCE, TARGET)).thenReturn(preview(TARGET_SHA, State.AVAILABLE));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.writable").value(false));
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isForbidden());
        verify(adoption).preview(42L, SOURCE, TARGET);
        verifyNoMoreInteractions(adoption);
    }

    @Test
    void writableArchitectStillNeedsCurrentContributionAndAnEditableWorkspace() throws Exception {
        when(memberships.canContribute(repository, "alice")).thenReturn(false);
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isForbidden());
        when(memberships.canContribute(repository, "alice")).thenReturn(true);
        when(navigation.isReadOnlyInScope(any())).thenReturn(true);
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adoption);
    }

    @Test
    void centralDraftRequiresMaintenanceAuthority() throws Exception {
        when(resolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(RepositoryContext.centralRead("project", "draft", "alice"));
        mvc.perform(command(null, "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adoption);
    }

    @Test
    void historicalPageCannotSilentlyAdoptAtCurrentHead() throws Exception {
        mvc.perform(command("workspace-A", "main").param("commitId", TARGET_SHA)
                        .header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adoption);
    }

    @Test
    void omittedWorkspacePinCannotSelectTheCentralTarget() throws Exception {
        when(resolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(RepositoryContext.centralRead("project", "draft", "alice"));
        when(memberships.canMaintain(repository, "alice")).thenReturn(true);
        mvc.perform(post(PATH).header("If-Match", '"' + TARGET_SHA + '"').header("Idempotency-Key", "review-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceBranch":"main","sourceCommit":"%s","targetRepositoryId":"project","targetBranch":"draft"}
                                """.formatted(SOURCE_SHA)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adoption);
    }

    @Test
    void centralMaintainerAdoptsAtTheDisplayedCentralBranch() throws Exception {
        var central = RepositoryContext.centralRead("project", "draft", "alice");
        var writable = RepositoryContext.centralWrite("project", "draft", "alice");
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(central);
        when(memberships.canMaintain(repository, "alice")).thenReturn(true);
        when(adoption.adopt(42L, SOURCE, SOURCE_SHA, writable, TARGET_SHA, new CommandMetadata("review-42")))
                .thenReturn(new Preview(42, "project", "main", SOURCE_SHA, null, "draft", TARGET_SHA,
                        State.ALREADY_PRESENT, true));
        mvc.perform(command(null, "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALREADY_PRESENT"));
        verify(adoption).adopt(42L, SOURCE, SOURCE_SHA, writable, TARGET_SHA, new CommandMetadata("review-42"));
    }

    @Test
    void committedGitChangeWithPendingProjectionReturnsItsAuthority() throws Exception {
        String committed = "c".repeat(40);
        var authority = new CommandResult("project", "workspace-A", "draft", RepositoryScope.WORKSPACE,
                TARGET_SHA, committed, ChangeKind.ADDED, true, "review-42");
        when(adoption.adopt(42L, SOURCE, SOURCE_SHA, TARGET, TARGET_SHA, new CommandMetadata("review-42")))
                .thenThrow(new ProjectionPendingException(authority, new IllegalStateException("projection unavailable")));
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", '"' + committed + '"'))
                .andExpect(jsonPath("$.projectionStatus").value("PENDING_RECOVERY"))
                .andExpect(jsonPath("$.authoritativeCommitId").value(committed));
    }

    @Test
    void unavailableGitReadIsNotReportedAsAnAbsentProposal() throws Exception {
        when(adoption.preview(42L, SOURCE, TARGET)).thenThrow(new java.io.IOException("unavailable"));
        mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingGitSourceAndConflictingDecisionHaveDifferentResponses() throws Exception {
        when(adoption.preview(42L, SOURCE, TARGET))
                .thenThrow(new ProposalBranchAdoptionService.ProposalNotFoundException());
        mvc.perform(get(PATH)).andExpect(status().isNotFound());
        when(adoption.adopt(42L, SOURCE, SOURCE_SHA, TARGET, TARGET_SHA, new CommandMetadata("review-42")))
                .thenThrow(new ProposalBranchAdoptionService.AdoptionConflictException());
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isConflict());
    }

    @Test
    void defaultBranchSourceInMissingRepositoryIsNotDisclosed() throws Exception {
        when(repositories.getRepository("project")).thenThrow(new IllegalArgumentException("missing"));
        mvc.perform(get(PATH)).andExpect(status().isNotFound());
        verifyNoInteractions(adoption, memberships);
    }

    @Test
    void collaborativeTargetRequiresCurrentCollaboratorMembership() throws Exception {
        var collaborative = RepositoryContext.collaborativeWorkspace("project", "workspace-A", "draft", "alice");
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(collaborative);
        when(memberships.canCollaborate(repository, "alice")).thenReturn(false);
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adoption);
    }

    @Test
    void cannotReplaceReviewedTargetWithANewOrUnrelatedBranch() throws Exception {
        mvc.perform(command("workspace-A", "main").header("If-None-Match", "*"))
                .andExpect(status().isBadRequest());
        when(resolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(RepositoryContext.workspace("project", "workspace-A", "other", "alice"));
        mvc.perform(command("workspace-A", "main").header("If-Match", '"' + TARGET_SHA + '"'))
                .andExpect(status().isConflict());
        verifyNoInteractions(adoption);
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority(role))));
    }

    private static Preview preview(String targetCommit, State state) {
        return new Preview(42, "project", "main", SOURCE_SHA, "workspace-A", "draft", targetCommit, state, true);
    }

    private static MockHttpServletRequestBuilder command(String workspace, String branch) {
        return command(workspace, branch, SOURCE_SHA);
    }

    private static MockHttpServletRequestBuilder command(String workspace, String sourceBranch, String sourceCommit) {
        return post(PATH).header("Idempotency-Key", "review-42").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceBranch":"%s","sourceCommit":"%s","targetRepositoryId":"project",
                         "targetWorkspaceId":%s,"targetBranch":"draft"}
                        """.formatted(sourceBranch, sourceCommit, workspace == null ? "null" : "\"" + workspace + "\""));
    }
}
