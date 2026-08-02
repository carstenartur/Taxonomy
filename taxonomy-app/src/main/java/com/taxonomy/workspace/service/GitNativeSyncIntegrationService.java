package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

/**
 * Portfolio-aware replacement for the legacy copy-based synchronization path.
 *
 * <p>Before pull or push, durable project decisions are projected into the
 * branch DSL through a workspace-owned port. Isolated repositories use a
 * tracked three-way semantic base instead of replacing the complete
 * architecture file. After every successful integration the portfolio blocks
 * are materialized into both affected scopes.</p>
 */
@Service
@Primary
public class GitNativeSyncIntegrationService extends SyncIntegrationService {

    private static final String WORKSPACE_BRANCH = "main";
    private static final String SEEDED_BRANCH = "draft";
    private static final String TRACKING_BRANCH = "sync-base";

    private final SyncStateRepository syncStateRepository;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final SemanticGitMergeService semanticMergeService;
    private final WorkspacePortfolioGitPort portfolioGitPort;
    private final WorkspaceContextResolver contextResolver;

    public GitNativeSyncIntegrationService(SyncStateRepository syncStateRepository,
                                           UserWorkspaceRepository workspaceRepository,
                                           SystemRepositoryService systemRepositoryService,
                                           DslGitRepositoryFactory repositoryFactory,
                                           SemanticGitMergeService semanticMergeService,
                                           WorkspacePortfolioGitPort portfolioGitPort,
                                           WorkspaceContextResolver contextResolver) {
        super(syncStateRepository, workspaceRepository, systemRepositoryService, repositoryFactory);
        this.syncStateRepository = syncStateRepository;
        this.systemRepositoryService = systemRepositoryService;
        this.repositoryFactory = repositoryFactory;
        this.semanticMergeService = semanticMergeService;
        this.portfolioGitPort = portfolioGitPort;
        this.contextResolver = contextResolver;
    }

    @Override
    public String syncFromShared(String username, String userBranch) throws IOException {
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() == null) {
            return mergeWithinRepository(username, context.currentBranch(), true);
        }
        return pullAcrossRepositories(username, context, context.currentBranch());
    }

    @Override
    public String publishToShared(String username, String userBranch) throws IOException {
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() == null) {
            return mergeWithinRepository(username, context.currentBranch(), false);
        }
        return publishAcrossRepositories(username, context, context.currentBranch());
    }

    @Override
    public String syncFromSharedToWorkspace(String username, String workspaceId) throws IOException {
        return pullAcrossRepositories(
                username,
                new WorkspaceContext(username, workspaceId, WORKSPACE_BRANCH),
                WORKSPACE_BRANCH);
    }

    @Override
    public String publishFromWorkspaceToShared(String username, String workspaceId) throws IOException {
        return publishAcrossRepositories(
                username,
                new WorkspaceContext(username, workspaceId, WORKSPACE_BRANCH),
                WORKSPACE_BRANCH);
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

    private String pullAcrossRepositories(String username,
                                          WorkspaceContext context,
                                          String userBranch) throws IOException {
        DslGitRepository sharedRepository = repositoryFactory.getSystemRepository();
        DslGitRepository workspaceRepository =
                repositoryFactory.getWorkspaceRepository(context.workspaceId());
        WorkspaceMergeState state = initialiseWorkspaceMergeBase(
                workspaceRepository, sharedRepository, username, userBranch);

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before pull", username, context);

        // Projection may have changed the active branch, so read it once after
        // the commit. The tracked base obtained during initialization is stable.
        String ours = requiredDsl(workspaceRepository, userBranch,
                "Workspace branch has no content: " + userBranch);
        String theirs = requiredDsl(sharedRepository, sharedBranch(),
                "Shared branch has no content");

        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), ours, theirs);
        requireSuccess("Pull from shared", merge);
        String localCommit = commitIfChanged(
                workspaceRepository,
                userBranch,
                merge.mergedText(),
                ours,
                username,
                "Semantic pull from shared portfolio");
        trackBase(workspaceRepository, merge.mergedText(), username);
        portfolioGitPort.materializePortfolio(merge.mergedText(), username, context);

        String sharedHead = sharedRepository.getHeadCommit(sharedBranch());
        updateAfterSync(username, sharedHead != null ? sharedHead : localCommit);
        return localCommit;
    }

    private String publishAcrossRepositories(String username,
                                             WorkspaceContext context,
                                             String userBranch) throws IOException {
        DslGitRepository sharedRepository = repositoryFactory.getSystemRepository();
        DslGitRepository workspaceRepository =
                repositoryFactory.getWorkspaceRepository(context.workspaceId());
        WorkspaceMergeState state = initialiseWorkspaceMergeBase(
                workspaceRepository, sharedRepository, username, userBranch);

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before push", username, context);

        String sharedDsl = valueOrEmpty(sharedRepository.getDslAtHead(sharedBranch()));
        String workspaceDsl = requiredDsl(workspaceRepository, userBranch,
                "Workspace branch has no content: " + userBranch);

        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), sharedDsl, workspaceDsl);
        requireSuccess("Push to shared", merge);

        String sharedCommit = commitIfChanged(
                sharedRepository,
                sharedBranch(),
                merge.mergedText(),
                sharedDsl,
                username,
                "Semantic publish from workspace " + context.workspaceId());
        commitIfChanged(
                workspaceRepository,
                userBranch,
                merge.mergedText(),
                workspaceDsl,
                username,
                "Integrate shared changes after publish");
        trackBase(workspaceRepository, merge.mergedText(), username);

        portfolioGitPort.materializePortfolio(
                merge.mergedText(), "shared", WorkspaceContext.SHARED);
        portfolioGitPort.materializePortfolio(
                merge.mergedText(), username, context);
        updateAfterPublish(username, sharedCommit);
        return sharedCommit;
    }

    private String mergeWithinRepository(String username,
                                         String userBranch,
                                         boolean pull) throws IOException {
        WorkspaceContext context = WorkspaceContext.SHARED;
        DslGitRepository repository = repositoryFactory.getSystemRepository();
        portfolioGitPort.commitPortfolio(userBranch,
                pull ? "Project requirements before sync from shared"
                        : "Project requirements before publish",
                username,
                context);
        String from = pull ? sharedBranch() : userBranch;
        String into = pull ? userBranch : sharedBranch();
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository, from, into, username);
        requireSuccess(pull ? "Sync" : "Publish", outcome);
        portfolioGitPort.materializePortfolioHead(into, username, context);
        if (pull) {
            updateAfterSync(username, repository.getHeadCommit(sharedBranch()));
        } else {
            updateAfterPublish(username, outcome.commitId());
        }
        return outcome.commitId();
    }

    /**
     * Establish the first common ancestor before any local portfolio projection
     * is committed and return the already-read base. Existing workspaces may
     * already carry a tracking base; in that case no seed branch access is
     * needed.
     */
    private WorkspaceMergeState initialiseWorkspaceMergeBase(
            DslGitRepository workspaceRepository,
            DslGitRepository sharedRepository,
            String username,
            String userBranch) throws IOException {
        String existingBase = valueOrEmpty(
                workspaceRepository.getDslAtHead(TRACKING_BRANCH));
        String existingUserBranch = valueOrEmpty(
                workspaceRepository.getDslAtHead(userBranch));
        if (!existingBase.isBlank() && !existingUserBranch.isBlank()) {
            return new WorkspaceMergeState(existingBase);
        }

        String shared = valueOrEmpty(sharedRepository.getDslAtHead(sharedBranch()));
        String seeded = valueOrEmpty(workspaceRepository.getDslAtHead(SEEDED_BRANCH));
        String commonBase = !seeded.isBlank() ? seeded : shared;
        if (commonBase.isBlank()) {
            throw new IOException("Neither shared nor workspace seed contains architecture DSL");
        }

        if (existingUserBranch.isBlank()) {
            workspaceRepository.commitDsl(
                    userBranch,
                    commonBase,
                    username,
                    "Initialize workspace branch from shared architecture");
        }
        if (existingBase.isBlank()) {
            workspaceRepository.commitDsl(
                    TRACKING_BRANCH,
                    commonBase,
                    username,
                    "Initialize semantic synchronization base");
        }
        return new WorkspaceMergeState(commonBase);
    }

    /**
     * Resolve the persistent active branch. The legacy in-memory workspace state
     * starts at {@code draft}; after isolated repository provisioning the durable
     * branch is normally {@code main}. An explicitly selected non-draft variant
     * still takes precedence.
     */
    private WorkspaceContext resolveWorkspaceContext(String username, String requestedBranch) {
        WorkspaceContext persistent = contextResolver.resolveForUser(username);
        if (persistent.workspaceId() == null) {
            return WorkspaceContext.SHARED;
        }
        String branch = requestedBranch;
        if (branch == null || branch.isBlank()
                || (SEEDED_BRANCH.equals(branch)
                && persistent.currentBranch() != null
                && !persistent.currentBranch().isBlank()
                && !SEEDED_BRANCH.equals(persistent.currentBranch()))) {
            branch = persistent.currentBranch();
        }
        if (branch == null || branch.isBlank()) branch = WORKSPACE_BRANCH;
        return new WorkspaceContext(username, persistent.workspaceId(), branch);
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

    private static String requiredDsl(DslGitRepository repository,
                                      String branch,
                                      String error) throws IOException {
        String dsl = valueOrEmpty(repository.getDslAtHead(branch));
        if (dsl.isBlank()) throw new IOException(error);
        return dsl;
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

    private record WorkspaceMergeState(String baseDsl) {
    }
}
