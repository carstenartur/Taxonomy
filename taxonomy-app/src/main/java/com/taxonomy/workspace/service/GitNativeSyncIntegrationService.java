package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Portfolio-aware replacement for the legacy copy-based synchronization path.
 *
 * <p>Before pull or push, relational project requirements are projected into
 * the branch DSL. Cross-repository synchronization uses a tracked three-way
 * semantic base instead of replacing the complete architecture file. After a
 * successful merge the portfolio blocks are materialized into the target scope.</p>
 */
@Service
@Primary
public class GitNativeSyncIntegrationService extends SyncIntegrationService {

    private static final String WORKSPACE_BRANCH = "main";
    private static final String TRACKING_BRANCH = "sync-base";

    private final SyncStateRepository syncStateRepository;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final SemanticGitMergeService semanticMergeService;
    private final PortfolioGitService portfolioGitService;

    public GitNativeSyncIntegrationService(SyncStateRepository syncStateRepository,
                                           UserWorkspaceRepository workspaceRepository,
                                           SystemRepositoryService systemRepositoryService,
                                           DslGitRepositoryFactory repositoryFactory,
                                           SemanticGitMergeService semanticMergeService,
                                           PortfolioGitService portfolioGitService) {
        super(syncStateRepository, workspaceRepository, systemRepositoryService, repositoryFactory);
        this.syncStateRepository = syncStateRepository;
        this.systemRepositoryService = systemRepositoryService;
        this.repositoryFactory = repositoryFactory;
        this.semanticMergeService = semanticMergeService;
        this.portfolioGitService = portfolioGitService;
    }

    /**
     * Pull through the normal workspace API.
     *
     * <p>When an isolated workspace repository exists, this method deliberately
     * routes to the cross-repository three-way merge. The previous implementation
     * tried to merge the workspace's stale local copy of the shared branch and
     * therefore did not actually pull changes from the system repository.</p>
     */
    @Override
    public String syncFromShared(String username, String userBranch) throws IOException {
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() != null) {
            return syncFromSharedToWorkspace(username, context.workspaceId(), userBranch);
        }

        portfolioGitService.commit(userBranch,
                "Project requirements before sync from shared", username, context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository, sharedBranch(), userBranch, username);
        requireSuccess("Sync", outcome);
        portfolioGitService.materializeHead(userBranch, username, context);
        updateAfterSync(username, repository.getHeadCommit(sharedBranch()));
        return outcome.commitId();
    }

    /** Route the normal publish API to cross-repository semantic push when needed. */
    @Override
    public String publishToShared(String username, String userBranch) throws IOException {
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() != null) {
            return publishFromWorkspaceToShared(username, context.workspaceId(), userBranch);
        }

        portfolioGitService.commit(userBranch,
                "Project requirements before publish", username, context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository, userBranch, sharedBranch(), username);
        requireSuccess("Publish", outcome);
        portfolioGitService.materializeHead(
                sharedBranch(), "shared", WorkspaceContext.SHARED);
        updateAfterPublish(username, outcome.commitId());
        return outcome.commitId();
    }

    @Override
    public String syncFromSharedToWorkspace(String username, String workspaceId) throws IOException {
        return syncFromSharedToWorkspace(username, workspaceId, WORKSPACE_BRANCH);
    }

    private String syncFromSharedToWorkspace(String username,
                                             String workspaceId,
                                             String workspaceBranch) throws IOException {
        WorkspaceContext workspaceContext = new WorkspaceContext(
                username, workspaceId, workspaceBranch);
        DslGitRepository sharedRepository = repositoryFactory.getSystemRepository();
        DslGitRepository workspaceRepository = repositoryFactory.getWorkspaceRepository(workspaceId);

        // Preserve local relational edits in Git before incorporating shared changes.
        portfolioGitService.commit(workspaceBranch,
                "Project requirements before pull", username, workspaceContext);

        String base = valueOrEmpty(workspaceRepository.getDslAtHead(TRACKING_BRANCH));
        String ours = valueOrEmpty(workspaceRepository.getDslAtHead(workspaceBranch));
        String theirs = valueOrEmpty(sharedRepository.getDslAtHead(sharedBranch()));
        if (theirs.isBlank()) throw new IOException("Shared branch has no content");

        TaxDslMergeResult merge = semanticMergeService.mergeContent(base, ours, theirs);
        requireSuccess("Pull from shared", merge);
        String localCommit = commitIfChanged(
                workspaceRepository,
                workspaceBranch,
                merge.mergedText(),
                ours,
                username,
                "Semantic pull from shared portfolio");
        trackBase(workspaceRepository, merge.mergedText(), username);
        portfolioGitService.materialize(
                merge.mergedText(), username, workspaceContext);

        String sharedHead = sharedRepository.getHeadCommit(sharedBranch());
        updateAfterSync(username, sharedHead != null ? sharedHead : localCommit);
        return localCommit;
    }

    @Override
    public String publishFromWorkspaceToShared(String username, String workspaceId) throws IOException {
        return publishFromWorkspaceToShared(username, workspaceId, WORKSPACE_BRANCH);
    }

    private String publishFromWorkspaceToShared(String username,
                                                String workspaceId,
                                                String workspaceBranch) throws IOException {
        WorkspaceContext workspaceContext = new WorkspaceContext(
                username, workspaceId, workspaceBranch);
        DslGitRepository sharedRepository = repositoryFactory.getSystemRepository();
        DslGitRepository workspaceRepository = repositoryFactory.getWorkspaceRepository(workspaceId);

        portfolioGitService.commit(workspaceBranch,
                "Project requirements before push", username, workspaceContext);

        String base = valueOrEmpty(workspaceRepository.getDslAtHead(TRACKING_BRANCH));
        String sharedBefore = valueOrEmpty(sharedRepository.getDslAtHead(sharedBranch()));
        String workspaceBefore = valueOrEmpty(workspaceRepository.getDslAtHead(workspaceBranch));
        if (workspaceBefore.isBlank()) throw new IOException("Workspace has no content");

        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                base, sharedBefore, workspaceBefore);
        requireSuccess("Push to shared", merge);
        String sharedCommit = commitIfChanged(
                sharedRepository,
                sharedBranch(),
                merge.mergedText(),
                sharedBefore,
                username,
                "Semantic publish from workspace " + workspaceId);

        // A push can incorporate concurrent changes already present on shared.
        // Bring the local branch to exactly the merged state as well, otherwise
        // the next pull would start from a base that the local branch never had.
        commitIfChanged(
                workspaceRepository,
                workspaceBranch,
                merge.mergedText(),
                workspaceBefore,
                username,
                "Synchronize workspace after semantic publish");
        trackBase(workspaceRepository, merge.mergedText(), username);

        portfolioGitService.materialize(
                merge.mergedText(), "shared", WorkspaceContext.SHARED);
        portfolioGitService.materialize(
                merge.mergedText(), username, workspaceContext);
        updateAfterPublish(username, sharedCommit);
        return sharedCommit;
    }

    @Override
    public String resolveDiverged(String username,
                                  String userBranch,
                                  DivergedStrategy strategy) throws IOException {
        if (strategy == DivergedStrategy.MERGE) {
            String commit = syncFromShared(username, userBranch);
            return "Semantically merged shared into your branch: " + abbreviate(commit);
        }
        return super.resolveDiverged(username, userBranch, strategy);
    }

    private WorkspaceContext resolveWorkspaceContext(String username, String branch) {
        return syncStateRepository.findByUsername(username)
                .map(state -> new WorkspaceContext(username, state.getWorkspaceId(), branch))
                .orElse(WorkspaceContext.SHARED);
    }

    private String sharedBranch() {
        return systemRepositoryService.getSharedBranch();
    }

    private String commitIfChanged(DslGitRepository repository,
                                   String branch,
                                   String merged,
                                   String previous,
                                   String username,
                                   String message) throws IOException {
        if (valueOrEmpty(previous).strip().equals(valueOrEmpty(merged).strip())) {
            String head = repository.getHeadCommit(branch);
            if (head != null) return head;
        }
        return repository.commitDsl(branch, merged, username, message);
    }

    private void trackBase(DslGitRepository workspaceRepository,
                           String mergedDsl,
                           String username) throws IOException {
        String current = valueOrEmpty(workspaceRepository.getDslAtHead(TRACKING_BRANCH));
        if (!current.strip().equals(valueOrEmpty(mergedDsl).strip())) {
            workspaceRepository.commitDsl(
                    TRACKING_BRANCH,
                    mergedDsl,
                    username,
                    "Update semantic synchronization base");
        }
    }

    private void updateAfterSync(String username, String commit) {
        SyncState state = getSyncState(username);
        state.setLastSyncedCommitId(commit);
        state.setLastSyncTimestamp(Instant.now());
        state.setSyncStatus("UP_TO_DATE");
        state.setUpdatedAt(Instant.now());
        syncStateRepository.save(state);
    }

    private void updateAfterPublish(String username, String commit) {
        SyncState state = getSyncState(username);
        state.setLastPublishedCommitId(commit);
        state.setLastSyncedCommitId(commit);
        state.setLastPublishTimestamp(Instant.now());
        state.setLastSyncTimestamp(Instant.now());
        state.setSyncStatus("UP_TO_DATE");
        state.setUnpublishedCommitCount(0);
        state.setUpdatedAt(Instant.now());
        syncStateRepository.save(state);
    }

    private static void requireSuccess(String operation,
                                       SemanticGitMergeService.MergeOutcome outcome) throws IOException {
        if (!outcome.success()) {
            throw new IOException(operation + " has semantic conflicts: "
                    + String.join(", ", outcome.conflicts()));
        }
    }

    private static void requireSuccess(String operation,
                                       TaxDslMergeResult result) throws IOException {
        if (!result.isSuccessful()) {
            throw new IOException(operation + " has semantic conflicts: "
                    + String.join(", ", result.conflictIdentifiers()));
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String commit) {
        return commit == null || commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
