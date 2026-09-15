package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
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
 * architecture file. Every synchronization path carries the exact logical
 * repository, workspace/central scope and branch into both Git and portfolio
 * materialisation.</p>
 */
@Service
@Primary
public class GitNativeSyncIntegrationService extends SyncIntegrationService {

    private static final String WORKSPACE_BRANCH = "main";
    private static final String SEEDED_BRANCH = "draft";
    private static final String TRACKING_BRANCH = "sync-base";

    private final SyncStateRepository syncStateRepository;
    private final UserWorkspaceRepository workspaceRepository;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final SemanticGitMergeService semanticMergeService;
    private final WorkspacePortfolioGitPort portfolioGitPort;
    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceArchitectureVersionPort editorVersions;

    public GitNativeSyncIntegrationService(SyncStateRepository syncStateRepository,
                                           UserWorkspaceRepository workspaceRepository,
                                           SystemRepositoryService systemRepositoryService,
                                           DslGitRepositoryFactory repositoryFactory,
                                           SemanticGitMergeService semanticMergeService,
                                           WorkspacePortfolioGitPort portfolioGitPort,
                                           WorkspaceContextResolver contextResolver, WorkspaceArchitectureVersionPort editorVersions) {
        super(syncStateRepository, workspaceRepository, systemRepositoryService, repositoryFactory);
        this.syncStateRepository = syncStateRepository;
        this.workspaceRepository = workspaceRepository;
        this.systemRepositoryService = systemRepositoryService;
        this.repositoryFactory = repositoryFactory;
        this.semanticMergeService = semanticMergeService;
        this.portfolioGitPort = portfolioGitPort;
        this.contextResolver = contextResolver;
        this.editorVersions = editorVersions;
    }

    @Override
    public String syncFromShared(String username, String userBranch) throws IOException {
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() == null) {
            return mergeWithinRepository(
                    username, context, context.currentBranch(), true);
        }
        return pullAcrossRepositories(username, context, context.currentBranch());
    }

    @Override
    public String publishToShared(String username, String userBranch) throws IOException {
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() == null) {
            return mergeWithinRepository(
                    username, context, context.currentBranch(), false);
        }
        return publishAcrossRepositories(username, context, context.currentBranch());
    }

    @Override
    public String syncFromSharedToWorkspace(String username, String workspaceId)
            throws IOException {
        WorkspaceContext context = resolveExplicitWorkspaceContext(
                username, workspaceId, WORKSPACE_BRANCH);
        return pullAcrossRepositories(username, context, WORKSPACE_BRANCH);
    }

    @Override
    public String publishFromWorkspaceToShared(String username, String workspaceId)
            throws IOException {
        WorkspaceContext context = resolveExplicitWorkspaceContext(
                username, workspaceId, WORKSPACE_BRANCH);
        return publishAcrossRepositories(username, context, WORKSPACE_BRANCH);
    }

    @Override
    public String resolveDiverged(String username,
                                  String userBranch,
                                  DivergedStrategy strategy) throws IOException {
        if (strategy == DivergedStrategy.MERGE) {
            String commit = syncFromShared(username, userBranch);
            return "Semantically merged source into your branch: " + abbreviate(commit);
        }
        return super.resolveDiverged(username, userBranch, strategy);
    }

    private String pullAcrossRepositories(String username,
                                          WorkspaceContext context,
                                          String userBranch) throws IOException {
        RepositoryContext selected = RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), userBranch, username);
        return editorVersions.version(selected, "Integrate architecture versions from source", () -> pullAcrossRepositoriesVersion(username, context, userBranch));
    }

    private String pullAcrossRepositoriesVersion(String username,
                                          WorkspaceContext context,
                                          String userBranch) throws IOException {
        UserWorkspace workspaceMetadata = requireWorkspace(context.workspaceId());
        SystemRepository sourceMetadata = requireSourceRepository(workspaceMetadata);
        requireMatchingRepository(context, sourceMetadata);
        String sourceBranch = sourceBranch(workspaceMetadata, sourceMetadata);
        DslGitRepository sourceRepository =
                repositoryFactory.getCentralRepository(sourceMetadata.getRepositoryId());
        DslGitRepository workspaceGit =
                repositoryFactory.openWorkspaceRepository(context.workspaceId());
        BranchSnapshot sourceSnapshot = snapshot(sourceRepository, sourceBranch);
        WorkspaceMergeState state = initialiseWorkspaceMergeBase(
                workspaceGit, sourceRepository, sourceSnapshot.dsl(), username, userBranch,
                workspaceMetadata.getBaseCommit());

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before pull", username, context);

        BranchSnapshot localSnapshot = snapshot(workspaceGit, userBranch);
        String ours = requiredSnapshot(localSnapshot, "Workspace branch has no content: " + userBranch);
        String theirs = requiredSnapshot(sourceSnapshot, "Source branch has no content: " + sourceBranch);

        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), ours, theirs);
        requireSuccess("Pull from source", merge);
        sourceRepository.verifyExpectedHead(sourceBranch, sourceSnapshot.head());
        String localCommit = commitIfChanged(
                workspaceGit,
                userBranch,
                merge.mergedText(),
                ours,
                localSnapshot.head(),
                username,
                "Semantic pull from " + sourceMetadata.getSlug());
        trackBase(workspaceGit, merge.mergedText(), username);
        portfolioGitPort.materializePortfolio(merge.mergedText(), username, context);

        // Movement after the checked read remains a future change, never integrated metadata.
        String sourceHead = sourceSnapshot.head();
        workspaceMetadata.setLastFetchedCommit(sourceHead);
        workspaceMetadata.setLastIntegratedCommit(sourceHead);
        workspaceMetadata.setCurrentCommit(localCommit);
        workspaceMetadata.setLastAccessedAt(Instant.now());
        workspaceRepository.save(workspaceMetadata);
        updateAfterSync(username, sourceHead != null ? sourceHead : localCommit);
        return localCommit;
    }

    private String publishAcrossRepositories(String username,
                                             WorkspaceContext context,
                                             String userBranch) throws IOException {
        RepositoryContext selected = RepositoryContext.workspace(context.repositoryId(), context.workspaceId(), userBranch, username);
        return editorVersions.version(selected, "Publish architecture workspace", () -> publishAcrossRepositoriesVersion(username, context, userBranch));
    }

    private String publishAcrossRepositoriesVersion(String username,
                                             WorkspaceContext context,
                                             String userBranch) throws IOException {
        UserWorkspace workspaceMetadata = requireWorkspace(context.workspaceId());
        SystemRepository sourceMetadata = requireSourceRepository(workspaceMetadata);
        requireMatchingRepository(context, sourceMetadata);
        String sourceBranch = sourceBranch(workspaceMetadata, sourceMetadata);
        DslGitRepository sourceRepository =
                repositoryFactory.getCentralRepository(sourceMetadata.getRepositoryId());
        DslGitRepository workspaceGit =
                repositoryFactory.openWorkspaceRepository(context.workspaceId());
        BranchSnapshot sourceSnapshot = snapshot(sourceRepository, sourceBranch);
        WorkspaceMergeState state = initialiseWorkspaceMergeBase(
                workspaceGit, sourceRepository, sourceSnapshot.dsl(), username, userBranch,
                workspaceMetadata.getBaseCommit());

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before push", username, context);

        String sourceDsl = sourceSnapshot.dsl();
        BranchSnapshot localSnapshot = snapshot(workspaceGit, userBranch);
        String workspaceDsl = requiredSnapshot(localSnapshot, "Workspace branch has no content: " + userBranch);

        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), sourceDsl, workspaceDsl);
        requireSuccess("Publish to source", merge);
        // Detect a changed local input before updating the central repository.
        workspaceGit.verifyExpectedHead(userBranch, localSnapshot.head());

        String sourceCommit = commitIfChanged(
                sourceRepository,
                sourceBranch,
                merge.mergedText(),
                sourceDsl,
                sourceSnapshot.head(),
                username,
                "Semantic publish from workspace " + context.workspaceId());
        String workspaceCommit = commitIfChanged(
                workspaceGit,
                userBranch,
                merge.mergedText(),
                workspaceDsl,
                localSnapshot.head(),
                username,
                "Integrate source changes after publish");
        trackBase(workspaceGit, merge.mergedText(), username);

        WorkspaceContext centralContext = new WorkspaceContext(
                "shared",
                null,
                sourceBranch,
                requireRepositoryId(sourceMetadata));
        portfolioGitPort.materializePortfolio(
                merge.mergedText(), "shared", centralContext);
        portfolioGitPort.materializePortfolio(
                merge.mergedText(), username, context);

        workspaceMetadata.setLastFetchedCommit(sourceCommit);
        workspaceMetadata.setLastIntegratedCommit(sourceCommit);
        workspaceMetadata.setCurrentCommit(workspaceCommit);
        workspaceMetadata.setLastAccessedAt(Instant.now());
        workspaceRepository.save(workspaceMetadata);
        sourceMetadata.setLastPushAt(Instant.now());
        systemRepositoryService.save(sourceMetadata);
        updateAfterPublish(username, sourceCommit);
        return sourceCommit;
    }

    private String mergeWithinRepository(String username,
                                         WorkspaceContext context,
                                         String userBranch,
                                         boolean pull) throws IOException {
        if (context.workspaceId() != null) {
            throw new IllegalArgumentException(
                    "Central synchronization must not carry a workspaceId");
        }
        DslGitRepository repository =
                repositoryFactory.getCentralRepository(context.repositoryId());
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

    /** Establish the common ancestor before any local portfolio projection is committed. */
    private WorkspaceMergeState initialiseWorkspaceMergeBase(
            DslGitRepository workspaceGit,
            DslGitRepository sourceRepository,
            String capturedSourceDsl,
            String username,
            String userBranch,
            String baseCommit) throws IOException {
        String existingBase = valueOrEmpty(
                workspaceGit.getDslAtHead(TRACKING_BRANCH));
        String existingUserBranch = valueOrEmpty(
                workspaceGit.getDslAtHead(userBranch));
        if (!existingBase.isBlank() && !existingUserBranch.isBlank()) {
            return new WorkspaceMergeState(existingBase);
        }

        String commonBase;
        if (baseCommit != null && !baseCommit.isBlank()) {
            // Initial provisioning records the exact fork point. A newer source
            // HEAD is not the common ancestor of independent local/remote edits.
            commonBase = valueOrEmpty(sourceRepository.getDslAtCommit(baseCommit));
        } else {
            // Preserve the compatibility path for metadata without a fork point.
            String source = capturedSourceDsl;
            String seeded = valueOrEmpty(workspaceGit.getDslAtHead(SEEDED_BRANCH));
            commonBase = !seeded.isBlank() ? seeded : source;
        }
        if (commonBase.isBlank()) {
            throw new IOException("Neither source nor workspace seed contains architecture DSL");
        }

        if (existingUserBranch.isBlank()) {
            workspaceGit.commitDsl(
                    userBranch,
                    commonBase,
                    username,
                    "Initialize workspace branch from source architecture");
        }
        if (existingBase.isBlank()) {
            workspaceGit.commitDsl(
                    TRACKING_BRANCH,
                    commonBase,
                    username,
                    "Initialize semantic synchronization base");
        }
        return new WorkspaceMergeState(commonBase);
    }

    /** Resolve the exact persistent repository and active branch for one user. */
    private WorkspaceContext resolveWorkspaceContext(String username, String requestedBranch) {
        RepositoryContext persistent =
                contextResolver.resolveRepositoryContextForUser(username);
        if (persistent.workspaceId() == null) {
            return new WorkspaceContext(
                    persistent.username(),
                    null,
                    persistent.branch(),
                    persistent.repositoryId());
        }
        String branch = requestedBranch;
        if (branch == null || branch.isBlank()
                || (SEEDED_BRANCH.equals(branch)
                && !SEEDED_BRANCH.equals(persistent.branch()))) {
            branch = persistent.branch();
        }
        if (branch == null || branch.isBlank()) {
            branch = WORKSPACE_BRANCH;
        }
        return new WorkspaceContext(
                persistent.username(),
                persistent.workspaceId(),
                branch,
                persistent.repositoryId());
    }

    /** Resolve an explicitly addressed workspace without inventing repository provenance. */
    private WorkspaceContext resolveExplicitWorkspaceContext(
            String username, String workspaceId, String branch) {
        UserWorkspace workspace = requireWorkspace(workspaceId);
        SystemRepository source = requireSourceRepository(workspace);
        return new WorkspaceContext(
                username,
                workspace.getWorkspaceId(),
                branch,
                requireRepositoryId(source));
    }

    private UserWorkspace requireWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        return workspaceRepository.findByWorkspaceId(workspaceId.strip())
                .orElseThrow(() -> new IllegalStateException(
                        "Workspace metadata not found: " + workspaceId));
    }

    private SystemRepository requireSourceRepository(UserWorkspace workspace) {
        String repositoryId = workspace.getSourceRepositoryId();
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalStateException(
                    "Workspace has no sourceRepositoryId: " + workspace.getWorkspaceId());
        }
        return systemRepositoryService.getRepository(repositoryId.strip());
    }

    private static void requireMatchingRepository(
            WorkspaceContext context, SystemRepository sourceRepository) {
        String sourceRepositoryId = requireRepositoryId(sourceRepository);
        String requestRepositoryId = context.repositoryId();
        if (!sourceRepositoryId.equals(requestRepositoryId)) {
            throw new IllegalStateException(
                    "Workspace repository context does not match persisted source provenance: "
                            + "request repositoryId=" + requestRepositoryId
                            + ", persisted sourceRepositoryId=" + sourceRepositoryId);
        }
    }

    private static String requireRepositoryId(SystemRepository repository) {
        if (repository == null || repository.getRepositoryId() == null
                || repository.getRepositoryId().isBlank()) {
            throw new IllegalStateException("Source repository has no repositoryId");
        }
        return repository.getRepositoryId().strip();
    }

    private static String sourceBranch(
            UserWorkspace workspace, SystemRepository sourceRepository) {
        if (workspace.getSyncTargetBranch() != null
                && !workspace.getSyncTargetBranch().isBlank()) {
            return workspace.getSyncTargetBranch();
        }
        if (workspace.getSourceBranch() != null && !workspace.getSourceBranch().isBlank()) {
            return workspace.getSourceBranch();
        }
        return sourceRepository.getDefaultBranch();
    }

    private String sharedBranch() {
        return systemRepositoryService.getSharedBranch();
    }

    private String commitIfChanged(DslGitRepository repository,
                                   String branch,
                                   String merged,
                                   String previous,
                                   String expectedHead,
                                   String username,
                                   String message) throws IOException {
        if (expectedHead != null && valueOrEmpty(previous).strip().equals(valueOrEmpty(merged).strip())) {
            return repository.verifyExpectedHead(branch, expectedHead);
        }
        return repository.commitDslIfHeadMatches(branch, expectedHead, merged, username, message);
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

    private BranchSnapshot snapshot(DslGitRepository repository, String branch) throws IOException {
        String head = repository.getHeadCommit(branch);
        return new BranchSnapshot(head, head == null ? "" : valueOrEmpty(repository.getDslAtCommit(head)));
    }

    private String requiredSnapshot(BranchSnapshot snapshot, String message) throws IOException {
        if (snapshot.dsl().isBlank()) throw new IOException(message);
        return snapshot.dsl();
    }

    private record BranchSnapshot(String head, String dsl) { }

    private static void requireSuccess(String operation,
                                       SemanticGitMergeService.MergeOutcome outcome)
            throws IOException {
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
