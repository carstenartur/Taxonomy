package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import org.springframework.security.access.AccessDeniedException;
import java.util.Optional;
import java.util.UUID;

/** Application identity authority for opt-in collaborative workspaces. */
@FunctionalInterface
public interface WorkspaceActorAccess {
    void requireActive(String username);

    /** Return the verified stable owner, or empty for a verified pre-migration external login. */
    default Optional<UUID> currentOwnerPrincipal(String username) {
        throw new AccessDeniedException("Verified workspace owner required");
    }

    /** Implementations with verified actors separate ephemeral state from the legacy scope cache. */
    default Optional<UUID> currentNavigationPrincipal(String username) {
        return Optional.empty();
    }

    /** An explicit stable owner supersedes the historical routing label. */
    default boolean owns(String username, UserWorkspace workspace) {
        if (workspace == null || username == null || !username.equals(workspace.getUsername())) return false;
        if (workspace.getOwnerPrincipalId() == null) return true;
        return currentOwnerPrincipal(username).map(UUID::toString)
                .filter(workspace.getOwnerPrincipalId()::equals).isPresent();
    }

    /** Ownership proof for one queued analysis in the selected personal workspace. */
    @FunctionalInterface
    interface AnalysisOwner {
        boolean owns(String username, UserWorkspace workspace);

        /** Bind the independently verified actor to the route and recheck its authority at use time. */
        static AnalysisOwner forWorkspace(String username, String workspaceId, UUID principal, Runnable recheck) {
            java.util.Objects.requireNonNull(username);
            java.util.Objects.requireNonNull(workspaceId);
            java.util.Objects.requireNonNull(recheck);
            String id = principal == null ? null : principal.toString();
            return (scope, row) -> {
                if (row == null || !username.equals(scope) || !username.equals(row.getUsername())
                        || !workspaceId.equals(row.getWorkspaceId()) || !java.util.Objects.equals(id, row.getOwnerPrincipalId())) {
                    return false;
                }
                recheck.run();
                return true;
            };
        }
    }

    /** Existing unbound rows retain their compatibility scope. A bound owner needs a verified login. */
    default AnalysisOwner admitAnalysisOwner(String username, UserWorkspace workspace) {
        if (workspace == null || username == null || !username.equals(workspace.getUsername())
                || workspace.getOwnerPrincipalId() != null) throw new AccessDeniedException("Verified workspace owner required");
        return AnalysisOwner.forWorkspace(username, workspace.getWorkspaceId(), null, () -> {});
    }

    static WorkspaceActorAccess unavailable() {
        return username -> { throw new AccessDeniedException("Verified active principal required for shared editing"); };
    }
}
