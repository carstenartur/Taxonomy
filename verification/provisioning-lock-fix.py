"""Apply the bounded manager/resolver correction after the source-checkpoint patch."""
from pathlib import Path

MANAGER = Path('taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceManager.java')
RESOLVER = MANAGER.with_name('WorkspaceContextResolver.java')

def replace(source, old, new, count=1):
    assert source.count(old) == count, (old, source.count(old))
    return source.replace(old, new)

source = MANAGER.read_text()
assert 'Source repository has no checkpoint on ' in source
source = replace(source, 'import org.springframework.stereotype.Service;', '''import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;''')
source = replace(source, '    private final DslGitRepositoryFactory repositoryFactory;', '''    private final DslGitRepositoryFactory repositoryFactory;

    @PersistenceContext
    private EntityManager entityManager;''')
source = replace(source, '    public synchronized UserWorkspace provisionWorkspaceRepository(',
                 '    @Transactional(noRollbackFor = WorkspaceProvisioningFailure.class)\n    public synchronized UserWorkspace provisionWorkspaceRepository(', 2)
source = replace(source, '    public synchronized UserWorkspace provisionDefaultWorkspaceRepository(',
                 '    @Transactional(noRollbackFor = WorkspaceProvisioningFailure.class)\n    public synchronized UserWorkspace provisionDefaultWorkspaceRepository(')
source = replace(source, '''        if (workspace == null || !workspace.isDefault()) {
            throw new AccessDeniedException("No automatic default workspace was selected");''', '''        lockWorkspace(workspace);
        if (workspace == null || !workspace.isDefault()) {
            throw new AccessDeniedException("No automatic default workspace was selected");''')
source = replace(source, '''            String username, UserWorkspace workspace, String targetBranch) {
        if (workspace == null''', '''            String username, UserWorkspace workspace, String targetBranch) {
        lockWorkspace(workspace);
        if (workspace == null''')
source = replace(source, '''            throw new RuntimeException(
                    "Could not provision workspace for " + username,
                    exception);''', '''            throw new WorkspaceProvisioningFailure(username, exception);''')
marker = '    // ── Internal helpers ───────────────────────────────────────────'
source = replace(source, marker, '''    /** Refresh under a database lock: another server may have completed a stale selection. */
    private void lockWorkspace(UserWorkspace workspace) {
        // Spring always injects this persistence context. Directly constructed
        // in-memory/unit-test managers have no database entity to lock.
        if (workspace != null && entityManager != null) {
            entityManager.refresh(workspace, LockModeType.PESSIMISTIC_WRITE);
        }
    }

    /** Preserve the FAILED metadata when a claimed Git initialization fails. */
    public static final class WorkspaceProvisioningFailure extends RuntimeException {
        private WorkspaceProvisioningFailure(String username, Exception cause) {
            super("Could not provision workspace for " + username, cause);
        }
    }

''' + marker)
MANAGER.write_text(source)
source = RESOLVER.read_text()
source = replace(source, '''        // Explicit tab pins and failed/in-progress attempts require lifecycle recovery.
        if (workspace != null && workspace.isDefault() && requestedWorkspaceId() == null''', '''        // A browser pins this same default during startup; it still needs first-use
        // initialization. Non-default and failed/in-progress selections require recovery.
        if (workspace != null && workspace.isDefault()''')
RESOLVER.write_text(source)
