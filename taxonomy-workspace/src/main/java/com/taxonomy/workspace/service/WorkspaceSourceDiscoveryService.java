package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;

/** Workspace-owned, bounded source discovery and exact-context read authority. No document access or mutation. */
@Service
public class WorkspaceSourceDiscoveryService {
    private static final int MAXIMUM_SOURCES = 16;
    private final SystemRepositoryRepository repositories;
    private final RepositoryMembershipService membership;
    private final UserWorkspaceRepository workspaces;

    public WorkspaceSourceDiscoveryService(SystemRepositoryRepository repositories, RepositoryMembershipService membership,
                                           UserWorkspaceRepository workspaces) {
        this.repositories = repositories;
        this.membership = membership;
        this.workspaces = workspaces;
    }

    public record Source(String repositoryId, String branch, String title, String description) { }
    public record Discovery(List<Source> sources, boolean truncated) {
        public Discovery { sources = List.copyOf(sources); }
    }

    @Transactional(readOnly = true)
    public Discovery readableSources(String username) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("A source reader is required");
        var page = repositories.findByLifecycleStateOrderByDisplayNameAsc(
                RepositoryLifecycleState.ACTIVE, PageRequest.of(0, MAXIMUM_SOURCES));
        var sources = page.stream().filter(repository -> readable(repository, username))
                .map(repository -> new Source(repository.getRepositoryId(), repository.getDefaultBranch(),
                        repository.getDisplayName(), repository.getDescription())).toList();
        return new Discovery(sources, page.hasNext());
    }

    @Transactional(readOnly = true)
    public boolean canReadRepository(String repositoryId, String username) {
        if (repositoryId == null || repositoryId.isBlank() || username == null || username.isBlank()) return false;
        return repositories.findByRepositoryId(repositoryId).filter(repository -> readable(repository, username)).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean canReadContext(String username, WorkspaceContext context) {
        if (context == null || !Objects.equals(username, context.username())
                || !canReadRepository(context.repositoryId(), username)) return false;
        if (context.workspaceId() == null || context.workspaceId().isBlank()) return true;
        return workspaces.findByWorkspaceId(context.workspaceId()).filter(workspace ->
                Objects.equals(workspace.getUsername(), username) && !workspace.isArchived() && !workspace.isShared()
                        && Objects.equals(workspace.getSourceRepositoryId(), context.repositoryId())
                        && Objects.equals(workspace.getCurrentBranch(), context.currentBranch())
                        && workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.READY).isPresent();
    }

    private boolean readable(SystemRepository repository, String username) {
        return repository.getLifecycleState() == RepositoryLifecycleState.ACTIVE && membership.canRead(repository, username);
    }
}
