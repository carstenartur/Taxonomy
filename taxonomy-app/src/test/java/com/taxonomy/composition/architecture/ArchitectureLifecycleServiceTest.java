package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.requirements.RequirementRealization;
import com.taxonomy.editor.ArchitectureCheckpointWriter;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectRequest;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Persistent application contracts; no remote analysis, shell Git, or container prerequisites. */
@SpringBootTest
@WithMockUser(username = "alice", roles = "ADMIN")
class ArchitectureLifecycleServiceTest extends LifecycleTestSupport {
    @Autowired ObjectMapper json;

    @Test
    void previewIsReadOnlyAndAcceptanceRequiresItsExactRequirementVersionAndFingerprint() throws Exception {
        var context = workspace("preview");
        var saved = create(context, null, "REVIEW", "REQ-1", "Record every accepted event");
        var request = review(context, saved.projectId(), saved.requirementId(), null, "COMPLETE", List.of("element:recorder"));
        var before = capture(context);
        var preview = lifecycle.preview(context, request);
        assertThat(preview.realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        assertThat(preview.realization().reviewed().actor()).isEqualTo("alice");
        assertThat(preview.realization().reviewed().contentHash()).isEqualTo(saved.requirement().currentVersion().contentHash());
        assertUnchanged(context, before);
        assertThat(requirement(lifecycle.overview(context, null, null), "REVIEW", "REQ-1").realization().state())
                .isEqualTo(RequirementRealization.State.OPEN);
        var wrongVersion = new ArchitectureLifecycleService.Request(request.context(), request.metadata(), request.projectId(), request.requirementId(),
                request.expectedVersionId(), "0".repeat(64), null, request.status(), request.dependencyIds(), null);
        rejected("REQUIREMENT_VERSION_CHANGED", () -> lifecycle.preview(context, wrongVersion));
        rejected("PREVIEW_CHANGED", () -> lifecycle.accept(context, withFingerprint(request, "0".repeat(64))));
        assertUnchanged(context, before);
        var accepted = lifecycle.accept(context, withFingerprint(request, preview.fingerprint()));
        assertThat(accepted.state()).isEqualTo("CHECKPOINTED");
        assertThat(requirement(lifecycle.overview(context, null, null), "REVIEW", "REQ-1").realization().state())
                .isEqualTo(RequirementRealization.State.COMPLETE);
    }

    @Test
    void aReviewedRelationIncludesItsEndpointsWhileUnrelatedArchitectureChangesStayCurrent() throws Exception {
        var context = workspace("dependencies");
        var saved = create(context, null, "DEPENDENCIES", "REQ-1", "Export recorded events");
        decide(context, saved.projectId(), saved.requirementId(), null, "COMPLETE", List.of("relation:recorder DEPENDS_ON exporter"));
        var reviewed = requirement(lifecycle.overview(context, null, null), "DEPENDENCIES", "REQ-1").realization();
        assertThat(reviewed.reviewed().dependencies()).extracting(RequirementRealization.Dependency::id)
                .containsExactlyInAnyOrder("relation:recorder DEPENDS_ON exporter", "element:recorder", "element:exporter");
        edit(context, "dashboard", "Unrelated display change");
        assertThat(requirement(lifecycle.overview(context, null, null), "DEPENDENCIES", "REQ-1").realization()).isEqualTo(reviewed);
        edit(context, "exporter", "Changed shared export dependency");
        var stale = requirement(lifecycle.overview(context, null, null), "DEPENDENCIES", "REQ-1").realization();
        assertThat(stale.state()).isEqualTo(RequirementRealization.State.REVIEW_REQUIRED);
        assertThat(stale.changedDependencies()).containsExactly("element:exporter");
        assertThat(stale.reviewed()).isEqualTo(reviewed.reviewed());
    }

    @Test
    void historicalGitSelectionKeepsItsTextReviewAndProjectWithoutCurrentDatabaseOverlay() throws Exception {
        var context = workspace("historical-git");
        var saved = create(context, null, "HISTORY", "REQ-1", "Original version text");
        var reviewed = decide(context, saved.projectId(), saved.requirementId(), null, "COMPLETE", List.of("element:recorder"));
        changes.save(context, change(context, saved.projectId(), saved.requirementId(), "Current replacement text", null));
        projects.updateProject(saved.projectId(), new UpdateProjectRequest("Renamed current project", null, null, null, null, null, null), "alice", scope(context));
        create(context, saved.projectId(), null, "REQ-2", "Later requirement");
        var historical = lifecycle.overview(context, reviewed.checkpoint().commitId(), null);
        assertThat(historical.source()).isEqualTo("GIT_CHECKPOINT");
        assertThat(historical.projectionState()).isEqualTo("HISTORICAL");
        assertThat(historical.context().commit()).isEqualTo(reviewed.checkpoint().commitId());
        assertThat(historical.mayEdit()).isFalse();
        assertThat(historical.projects()).singleElement().satisfies(project -> {
            assertThat(project.title()).isEqualTo("Project HISTORY");
            assertThat(project.id()).isNull();
        });
        assertThat(historical.requirements()).singleElement().satisfies(requirement -> {
            assertThat(requirement.text()).isEqualTo("Original version text");
            assertThat(requirement.version()).isEqualTo(1);
            assertThat(requirement.versionId()).isNull();
            assertThat(requirement.requirementId()).isNull();
            assertThat(requirement.realization().state()).isEqualTo(RequirementRealization.State.COMPLETE);
        });
        assertThat(requirement(lifecycle.overview(context, null, null), "HISTORY", "REQ-1").text()).isEqualTo("Current replacement text");
    }

    @Test
    void historicalSemanticRevisionReadsItsRetainedSourceIncludingRevisionZero() throws Exception {
        var context = workspace("historical-revision");
        var saved = create(context, null, "REVISIONS", "REQ-1", "Text at accepted revision one");
        long revision = saved.publication().context().revision();
        projects.addRequirementVersion(saved.projectId(), saved.requirementId(), new CreateRequirementVersionRequest(
                "New database text outside the old revision", "Later portfolio decision", null), "alice", scope(context));
        edit(context, "dashboard", "New retained model revision");
        var historical = lifecycle.overview(context, null, revision);
        assertThat(historical.source()).isEqualTo("WORKSPACE_REVISION");
        assertThat(historical.projectionState()).isEqualTo("HISTORICAL");
        assertThat(historical.context().revision()).isEqualTo(revision);
        assertThat(historical.mayEdit()).isFalse();
        assertThat(historical.requirements()).singleElement().satisfies(requirement -> {
            assertThat(requirement.text()).isEqualTo("Text at accepted revision one");
            assertThat(requirement.version()).isEqualTo(1);
            assertThat(requirement.versionId()).isNull();
        });
        var initial = lifecycle.overview(context, null, 0L);
        assertThat(initial.mayEdit()).isFalse();
        assertThat(initial.requirements()).isEmpty();
        assertThat(requirement(lifecycle.overview(context, null, null), "REVISIONS", "REQ-1").text())
                .isEqualTo("New database text outside the old revision");
    }

    @Test
    void contextAndPortfolioTenantChecksRejectOtherActorsWorkspacesBranchesAndRequirementsWithoutWrites() throws Exception {
        var own = workspace("authority");
        var other = workspace("other");
        var saved = create(own, null, "PRIVATE", "REQ-1", "Private requirement body");
        var original = newRequirement(own, saved.projectId(), null, "REQ-2", "Unaccepted next body");
        var expected = original.context();
        var before = capture(own);
        for (Context forged : List.of(
                new Context(expected.repositoryId() + "-other", expected.workspaceScopeKey(), expected.branch(), expected.commit(), expected.revision(), expected.actor(), expected.writeMode()),
                new Context(expected.repositoryId(), Context.of(other, null).workspaceScopeKey(), expected.branch(), expected.commit(), expected.revision(), expected.actor(), expected.writeMode()),
                new Context(expected.repositoryId(), expected.workspaceScopeKey(), "other-branch", expected.commit(), expected.revision(), expected.actor(), expected.writeMode()),
                new Context(expected.repositoryId(), expected.workspaceScopeKey(), expected.branch(), expected.commit(), expected.revision(), "bob", expected.writeMode()))) {
            var request = new ArchitectureRequirementService.Request(forged, original.metadata(), original.choice(), original.projectId(), original.newProject(),
                    original.contributorProjectId(), original.requirementId(), original.requirement(), original.expectedVersionId(), original.expectedContentHash(), original.inputHash());
            rejected("CONTEXT_CHANGED", () -> changes.save(own, request));
        }
        rejected("NOT_FOUND", () -> lifecycle.overview(RepositoryContext.workspace(own.repositoryId(), own.workspaceId(), "draft", "bob"), null, null));
        rejected("CONTEXT_CHANGED", () -> lifecycle.overview(RepositoryContext.workspace(own.repositoryId(), own.workspaceId(), "different", "alice"), null, null));
        var otherBefore = capture(other);
        var crossTenant = new ArchitectureLifecycleService.Request(editor.read(other, null).context(), metadata("Attempt another workspace requirement"),
                saved.projectId(), saved.requirementId(), saved.requirement().currentVersionId(), saved.requirement().currentVersion().contentHash(),
                null, "COMPLETE", List.of("element:recorder"), null);
        assertThatThrownBy(() -> lifecycle.preview(other, crossTenant)).isInstanceOfSatisfying(PortfolioException.class,
                failure -> assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
        assertUnchanged(own, before); assertUnchanged(other, otherBefore);
        assertThat(projects.listRequirements(saved.projectId(), "alice", scope(own))).hasSize(1);
    }

    @Test
    void httpRequiresExactIfMatchAndTreatsQueryContextPinsOnlyAsAssertions() throws Exception {
        var context = workspace("http");
        var saved = create(context, null, "HTTP", "REQ-1", "HTTP requirement text");
        var request = review(context, saved.projectId(), saved.requirementId(), null, "PARTIAL", List.of("element:recorder"));
        WorkspaceResolver resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
        var mvc = standaloneSetup(new ArchitectureLifecycleController(lifecycle, new LifecycleHttpContext(resolver)))
                .setControllerAdvice(new ArchitectureLifecycleExceptionHandler()).build();
        String body = json.writeValueAsString(request), etag = "\"workspace-revision-" + request.context().revision() + "\"";
        var before = capture(context);
        mvc.perform(post("/api/architecture/lifecycle/realizations/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(post("/api/architecture/lifecycle/realizations/preview").contentType(MediaType.APPLICATION_JSON).content(body).header("If-None-Match", etag))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post("/api/architecture/lifecycle/realizations/preview").contentType(MediaType.APPLICATION_JSON).content(body).header("If-Match", "\"workspace-revision-0\""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/architecture/lifecycle/realizations/preview").contentType(MediaType.APPLICATION_JSON).content(body).header("If-Match", etag).header("If-None-Match", "*"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/architecture/lifecycle/realizations/preview").contentType(MediaType.APPLICATION_JSON).content(body).header("If-Match", etag))
                .andExpect(status().isOk()).andExpect(header().string("ETag", etag)).andExpect(header().string("Cache-Control", "no-store"));
        for (String pin : List.of("repositoryId", "workspaceScopeKey", "branch"))
            mvc.perform(get("/api/architecture/lifecycle").param(pin, "another-context"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertUnchanged(context, before);
    }

    @Test
    void requirementCommandRetriesKeepTheirOriginalVersionAndCheckpointAfterLaterChanges() throws Exception {
        var context = workspace("mutation-replay");
        var firstRequest = newRequirement(context, null, "RETRY", "REQ-1", "Original command A text");
        var first = changes.save(context, firstRequest);
        var afterFirst = capture(context);
        var immediate = changes.save(context, firstRequest);
        assertThat(immediate.projectId()).isEqualTo(first.projectId());
        assertThat(immediate.requirementId()).isEqualTo(first.requirementId());
        assertThat(immediate.requirement().currentVersion()).isEqualTo(first.requirement().currentVersion());
        assertUnchanged(context, afterFirst);
        var secondRequest = change(context, first.projectId(), first.requirementId(), "Later command B text", null);
        var second = changes.save(context, secondRequest);
        var afterSecond = capture(context);
        var secondReplay = changes.save(context, secondRequest);
        assertThat(secondReplay.requirement().currentVersion()).isEqualTo(second.requirement().currentVersion());
        assertUnchanged(context, afterSecond);
        var originalReplay = changes.save(context, firstRequest);
        assertThat(originalReplay.publication().checkpoint().commitId()).isEqualTo(first.publication().checkpoint().commitId());
        assertThat(originalReplay.requirement().currentVersion()).isEqualTo(first.requirement().currentVersion());
        assertUnchanged(context, afterSecond);
        assertThat(projects.listProjects("alice", scope(context))).hasSize(1);
        assertThat(projects.listRequirements(first.projectId(), "alice", scope(context))).hasSize(1);
        assertThat(projects.listRequirementVersions(first.projectId(), first.requirementId(), "alice", scope(context))).hasSize(2);
        var reused = new ArchitectureRequirementService.Request(firstRequest.context(), firstRequest.metadata(), firstRequest.choice(), firstRequest.projectId(),
                firstRequest.newProject(), null, null, input("REQ-1", "Different command body"), null, null, RequirementRealization.textHash("Different command body"));
        rejected("COMMAND_ID_REUSED", () -> changes.save(context, reused));
        assertUnchanged(context, afterSecond);
    }

    @Test
    void publicationFailureRetainsTheDecisionAndExactRetryPublishesOnceIncludingReviewReplayAfterB() throws Exception {
        var context = workspace("pending");
        AtomicBoolean unavailable = new AtomicBoolean(true);
        var writer = new ArchitectureCheckpointWriter() {
            @Override public Result write(DslGitRepository repository, String scope, String branch, EditorJournal.Checkpoint request) throws IOException {
                if (unavailable.compareAndSet(true, false)) throw new IOException("Deterministic publication outage");
                return super.write(repository, scope, branch, request);
            }
        };
        var retainedEditor = new ArchitectureEditorService(git, journal, writer);
        var retainedLifecycle = new ArchitectureLifecycleService(retainedEditor, projects, portfolio, workspaces, repositories, membership, transactions);
        var retainedChanges = new ArchitectureRequirementService(retainedEditor, retainedLifecycle, projects, portfolio, transactions);
        String initialHead = git.resolveRepository(context).getHeadCommit("draft");
        var request = newRequirement(context, null, "PENDING", "REQ-1", "Durable requirement while Git is unavailable");
        var pending = retainedChanges.save(context, request);
        assertThat(pending.publication().state()).isEqualTo("CHECKPOINT_PENDING");
        assertThat(journal.read(context).state().pendingCheckpoint()).isNotNull();
        assertThat(git.resolveRepository(context).getHeadCommit("draft")).isEqualTo(initialHead);
        assertThat(projects.getRequirement(pending.projectId(), pending.requirementId(), "alice", scope(context)).currentVersion().text())
                .isEqualTo(request.requirement().text());
        long retainedRevision = journal.read(context).state().revision();
        var resumed = retainedChanges.save(context, request);
        assertThat(resumed.publication().state()).isEqualTo("CHECKPOINTED");
        assertThat(resumed.requirementId()).isEqualTo(pending.requirementId());
        assertThat(journal.read(context).state().revision()).isEqualTo(retainedRevision);
        assertThat(journal.read(context).state().pendingCheckpoint()).isNull();
        assertThat(git.resolveRepository(context).getDslHistory("draft")).hasSize(2);
        var reviewRequest = review(context, resumed.projectId(), resumed.requirementId(), null, "PARTIAL", List.of("element:recorder"));
        reviewRequest = withFingerprint(reviewRequest, retainedLifecycle.preview(context, reviewRequest).fingerprint());
        unavailable.set(true);
        var reviewPending = retainedLifecycle.accept(context, reviewRequest);
        assertThat(reviewPending.state()).isEqualTo("CHECKPOINT_PENDING");
        assertThat(requirement(lifecycle.overview(context, null, null), "PENDING", "REQ-1").realization().state())
                .isEqualTo(RequirementRealization.State.PARTIAL);
        var reviewPublished = retainedLifecycle.accept(context, reviewRequest);
        assertThat(reviewPublished.state()).isEqualTo("CHECKPOINTED");
        var second = retainedChanges.save(context, newRequirement(context, resumed.projectId(), null, "REQ-2", "Later operation B"));
        assertThat(second.publication().state()).isEqualTo("CHECKPOINTED");
        var afterB = capture(context);
        var reviewReplay = retainedLifecycle.accept(context, reviewRequest);
        assertThat(reviewReplay.checkpoint().commitId()).isEqualTo(reviewPublished.checkpoint().commitId());
        assertUnchanged(context, afterB);
        assertThat(git.resolveRepository(context).getDslHistory("draft")).hasSize(4);
        var reused = new ArchitectureLifecycleService.Request(reviewRequest.context(), reviewRequest.metadata(), reviewRequest.projectId(), reviewRequest.requirementId(),
                reviewRequest.expectedVersionId(), reviewRequest.expectedContentHash(), reviewRequest.contributorProjectId(), "COMPLETE", reviewRequest.dependencyIds(), reviewRequest.previewFingerprint());
        rejected("COMMAND_ID_REUSED", () -> retainedLifecycle.accept(context, reused));
        assertUnchanged(context, afterB);
    }
}
