package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.requirements.RequirementRealization;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual persistent fixtures shared by lifecycle contracts; these are authored elements, not catalogue claims. */
abstract class LifecycleTestSupport {
    static final String MODEL = """
            # Keep this architecture annotation through every portfolio contribution.
            element recorder type Component {
              title: "Recorder";
              x-source-evidence: "Original authored model";
            }
            element exporter type Component {
              title: "Exporter";
            }
            element dashboard type Component {
              title: "Dashboard";
            }
            relation recorder DEPENDS_ON exporter {
              status: accepted;
            }
            """;
    @Autowired ArchitectureLifecycleService lifecycle;
    @Autowired ArchitectureRequirementService changes;
    @Autowired ArchitectureEditorService editor;
    @Autowired ProjectPortfolioService projects;
    @Autowired PortfolioGitService portfolio;
    @Autowired SystemRepositoryService repositories;
    @Autowired RepositoryMembershipService membership;
    @Autowired UserWorkspaceRepository workspaces;
    @Autowired DslGitRepositoryFactory git;
    @Autowired EditorJournal journal;
    @Autowired EntityManagerFactory entityManagers;
    @Autowired PlatformTransactionManager transactions;

    RepositoryContext workspace(String purpose) throws Exception {
        String repository = repositories.getPrimaryRepository().getRepositoryId();
        String id = "lifecycle-" + purpose + "-" + UUID.randomUUID();
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(id); workspace.setUsername("alice"); workspace.setDisplayName(id);
        workspace.setSourceRepositoryId(repository); workspace.setSourceBranch("draft"); workspace.setCurrentBranch("draft");
        workspace.setCreatedAt(Instant.now()); workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        workspaces.saveAndFlush(workspace);
        RepositoryContext context = RepositoryContext.workspace(repository, id, "draft", "alice");
        git.resolveRepository(context).commitDsl("draft", MODEL, "alice", "Initial authored architecture");
        return context;
    }

    ArchitectureRequirementService.Request newRequirement(RepositoryContext context, Long projectId, String projectKey,
                                                          String key, String text) throws Exception {
        return new ArchitectureRequirementService.Request(editor.read(context, null).context(), metadata("Create " + key), "NEW", projectId,
                projectId == null ? new CreateProjectRequest(projectKey, "Project " + projectKey, null, null, null, null, null, null) : null,
                null, null, input(key, text), null, null, RequirementRealization.textHash(text));
    }

    ArchitectureRequirementService.Saved create(RepositoryContext context, Long projectId, String projectKey,
                                                 String key, String text) throws Exception {
        var saved = changes.save(context, newRequirement(context, projectId, projectKey, key, text));
        assertThat(saved.publication().state()).isEqualTo("CHECKPOINTED");
        return saved;
    }

    ArchitectureRequirementService.Request change(RepositoryContext context, Long projectId, Long requirementId,
                                                   String text, Long contributorProjectId) throws Exception {
        var requirement = projects.getRequirement(projectId, requirementId, "alice", scope(context));
        return new ArchitectureRequirementService.Request(editor.read(context, null).context(), metadata("Revise " + requirement.requirementKey()), "CHANGE",
                projectId, null, contributorProjectId, requirementId, input(requirement.requirementKey(), text),
                requirement.currentVersionId(), requirement.currentVersion().contentHash(), RequirementRealization.textHash(text));
    }

    ArchitectureLifecycleService.Request review(RepositoryContext context, Long projectId, Long requirementId,
                                                 Long contributorProjectId, String status, List<String> dependencies) throws Exception {
        var requirement = projects.getRequirement(projectId, requirementId, "alice", scope(context));
        return new ArchitectureLifecycleService.Request(editor.read(context, null).context(), metadata("Review " + requirement.requirementKey() + " as " + status),
                projectId, requirementId, requirement.currentVersionId(), requirement.currentVersion().contentHash(), contributorProjectId,
                status, dependencies, null);
    }

    ArchitectureLifecycleService.Request previewed(RepositoryContext context, ArchitectureLifecycleService.Request request) throws Exception {
        return withFingerprint(request, lifecycle.preview(context, request).fingerprint());
    }

    static ArchitectureLifecycleService.Request withFingerprint(ArchitectureLifecycleService.Request request, String fingerprint) {
        return new ArchitectureLifecycleService.Request(request.context(), request.metadata(), request.projectId(), request.requirementId(),
                request.expectedVersionId(), request.expectedContentHash(), request.contributorProjectId(), request.status(), request.dependencyIds(), fingerprint);
    }

    ArchitectureLifecycleService.Publication decide(RepositoryContext context, Long projectId, Long requirementId,
                                                     Long contributorProjectId, String status, List<String> dependencies) throws Exception {
        var accepted = lifecycle.accept(context, previewed(context, review(context, projectId, requirementId, contributorProjectId, status, dependencies)));
        assertThat(accepted.state()).isEqualTo("CHECKPOINTED");
        return accepted;
    }

    ArchitectureLifecycleService.Requirement requirement(ArchitectureLifecycleService.Overview overview, String projectKey, String requirementKey) {
        return overview.requirements().stream().filter(r -> projectKey.equals(r.projectKey()) && requirementKey.equals(r.requirementKey())).findFirst().orElseThrow();
    }

    Accepted edit(RepositoryContext context, String id, String title) throws Exception {
        return editor.execute(context, new Command(editor.read(context, null).context(), metadata("Change authored " + id),
                new SemanticCommand(new UpdateArchitectureElement(id, "Component", Map.of("title", title)))));
    }

    List<String> checkpointIds(RepositoryContext context) {
        try (var manager = entityManagers.createEntityManager()) {
            return manager.createQuery("select c.commandId from EditorCheckpoint c where c.scopeId = :scope order by c.commandId", String.class)
                    .setParameter("scope", EditorJournal.scope(context)).getResultList();
        }
    }

    record Unchanged(EditorJournal.Snapshot journal, List<String> checkpoints, String head) {}
    Unchanged capture(RepositoryContext context) throws Exception {
        return new Unchanged(journal.read(context), checkpointIds(context), git.resolveRepository(context).getHeadCommit("draft"));
    }
    void assertUnchanged(RepositoryContext context, Unchanged previous) throws Exception {
        assertThat(capture(context)).isEqualTo(previous);
    }
    interface CheckedAction { void run() throws Exception; }
    static void rejected(String code, CheckedAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CommandProblem.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
    static CreateRequirementRequest input(String key, String text) {
        return new CreateRequirementRequest(key, "Requirement " + key, text, null, null, null, null, null, "alice", "Reviewed exact source", null);
    }
    static WorkspaceContext scope(RepositoryContext context) {
        return new WorkspaceContext(context.username(), context.workspaceId(), context.branch(), context.repositoryId());
    }
    static Metadata metadata(String rationale) {
        String id = UUID.randomUUID().toString();
        return new Metadata(id, id, id, rationale);
    }
}
