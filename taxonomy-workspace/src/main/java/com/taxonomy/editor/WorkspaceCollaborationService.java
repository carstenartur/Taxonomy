package com.taxonomy.editor;

import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.persistence.EditorJournal.RevisionConflict;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceActorAccess;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Objects;

/** Owner-managed opt-in for the existing durable working copy, never a second editor state. */
@Service
public class WorkspaceCollaborationService {
    private final UserWorkspaceRepository workspaces;
    private final ArchitectureEditorService editor;
    private final SystemRepositoryService repositories;
    private WorkspaceActorAccess actorAccess = WorkspaceActorAccess.unavailable();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setActorAccess(WorkspaceActorAccess actorAccess) {
        this.actorAccess = Objects.requireNonNull(actorAccess);
    }

    public WorkspaceCollaborationService(UserWorkspaceRepository workspaces, ArchitectureEditorService editor,
            SystemRepositoryService repositories) {
        this.workspaces = workspaces; this.editor = editor; this.repositories = repositories;
    }

    public record Status(boolean enabled, boolean mayManage) {}

    @Transactional(readOnly = true)
    public Status status(RepositoryContext context) {
        if (context.scope() != RepositoryScope.WORKSPACE) return new Status(false, false);
        var workspace = workspaces.findByWorkspaceId(context.workspaceId()).orElseThrow(WorkspaceCollaborationService::unavailable);
        requireScope(context, workspace);
        return new Status(workspace.isCollaborative(), Objects.equals(context.username(), workspace.getUsername()));
    }

    @Transactional(rollbackFor = IOException.class)
    public Status setEnabled(RepositoryContext context, Context expected, boolean enabled) throws IOException {
        if (context.scope() != RepositoryScope.WORKSPACE) throw unavailable();
        if (workspaces.countReadyOwnedWorkspace(context.workspaceId(), context.username(), context.repositoryId(),
                context.branch(), WorkspaceProvisioningStatus.READY) != 1) throw unavailable();
        actorAccess.requireActive(context.username());
        if (repositories.getRepository(context.repositoryId()).getLifecycleState()
                != com.taxonomy.workspace.model.RepositoryLifecycleState.ACTIVE) throw unavailable();
        if (!Context.of(context, expected.commit(), expected.revision()).equals(expected)) throw unavailable();
        return editor.collaborationBoundary(context, current -> {
            // The journal lock is already held and joins this transaction through its commit/rollback.
            var workspace = workspaces.findByWorkspaceIdForUpdate(context.workspaceId()).orElseThrow(WorkspaceCollaborationService::unavailable);
            requireScope(context, workspace);
            if (!Objects.equals(context.username(), workspace.getUsername())
                    || context.collaborative() != workspace.isCollaborative()) throw unavailable();
            if (current.context().revision() != expected.revision()) throw new RevisionConflict(expected.revision(), current.context().revision());
            if (!Objects.equals(current.context().commit(), expected.commit()))
                throw new com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem("VERSION_CHANGED", "context",
                        "The architecture checkpoint changed; refresh before changing sharing", java.util.List.of());
            if (!"READY".equals(current.projectionState())) throw new IllegalArgumentException("Refresh or recover the current working copy before changing sharing");
            if (workspaces.updateCollaborationFlag(workspace.getWorkspaceId(), enabled) != 1) throw unavailable();
            // Bulk writes bypass managed state; retain the acknowledged flag in this transaction too.
            workspace.setCollaborative(enabled);
            return new Status(enabled, true);
        });
    }

    private static void requireScope(RepositoryContext context, UserWorkspace workspace) {
        if (workspace.isArchived() || workspace.isShared() || workspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY
                || !Objects.equals(context.repositoryId(), workspace.getSourceRepositoryId())
                || !Objects.equals(context.branch(), workspace.getCurrentBranch())) throw unavailable();
    }

    private static AccessDeniedException unavailable() { return new AccessDeniedException("Only the working-copy owner can manage sharing"); }
}
