package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
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

    public GitNativeSyncIntegrationService(SyncStateRepository syncStateRepository,
                                           UserWorkspaceRepository workspaceRepository,
                                           SystemRepositoryService systemRepositoryService,
                                           DslGitRepositoryFactory repositoryFactory,
                                           SemanticGitMergeService semanticMergeService,
                                           WorkspacePortfolioGitPort portfolioGitPort,
                                           WorkspaceContextResolver contextResolver) {
        super(syncStateRepository, workspaceRepository, systemRepositoryService, repositoryFactory);
        this.syncStateRepository = syncStateRepository;
        this.workspaceRepository = workspaceRepository;
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
        UserWorkspace workspaceMetadata = requireWorkspace(context.workspaceId());
        SystemRepository sourceMetadata = requireSourceRepository(workspaceMetadata);
        requireMatchingRepository(context, sourceMetadata);
        String sourceBranch = sourceBranch(workspaceMetadata, sourceMetadata);
        DslGitRepository sourceRepository =
                repositoryFactory.getCentralRepository(sourceMetadata.getRepositoryId());
        DslGitRepository workspaceGit =
                repositoryFactory.openWorkspaceRepository(context.workspaceId());
        WorkspaceMergeState state = initialiseWorkspaceMergeBase(
                workspaceGit, sourceRepository, sourceBranch, username, userBranch);

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before pull", username, context);

        String ours = requiredDsl(workspaceGit, userBranch,
                "Workspace branch has no content: " + userBranch);
        String theirs = requiredDsl(sourceRepository, sourceBranch,
                "Source branch has no content: " + sourceBranch);

        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), ours, theirs);
        requireSuccess("Pull from source", merge);
        String localCommit = commitIfChanged(
                workspaceGit,
                userBranch,
                merge.mergedText(),
                ours,
                username,
                "Semantic pull from " + sourceMetadata.getSlug());
        trackBase(workspaceGit, merge.mergedText(), username);
        portfolioGitPort.materializePortfolio(merge.mergedText(), username, context);

        String sourceHead = sourceRepository.getHeadCommit(sourceBranch);
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
        UserWorkspace workspaceMetadata = requireWorkspace(context.workspaceId());
        SystemRepository sourceMetadata = requireSourceRepository(workspaceMetadata);
        requireMatchingRepository(context, sourceMetadata);
        String sourceBranch = sourceBranch(workspaceMetadata, sourceMetadata);
        DslGitRepository sourceRepository =
                repositoryFactory.getCentralRepository(sourceMetadata.getRepositoryId());
        DslGitRepository workspaceGit =
                repositoryFactory.openWorkspaceRepository(context.workspaceId());
        WorkspaceMergeState state = initialiseWorkspaceMergeBase(
                workspaceGit, sourceRepository, sourceBranch, username, userBranch);

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before push", username, context);

        String sourceDsl = valueOrEmpty(sourceRepository.getDslAtHead(sourceBranch));
        String workspaceDsl = requiredDsl(workspaceGit, userBranch,
                "Workspace branch has no content: " + userBranch);

        TaxDslMergeResult merge = semanticMergeService.mergeContent(
                state.baseDsl(), sourceDsl, workspaceDsl);
        requireSuccess("Publish to source", merge);

        String sourceCommit = commitIfChanged(
                sourceRepository,
                sourceBranch,
                merge.mergedText(),
                sourceDsl,
                username,
                "Semantic publish from workspace " + context.workspaceId());
        String workspaceCommit = commitIfChanged(
                workspaceGit,
                userBranch,
                merge.mergedText(),
                workspaceDsl,
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
            String sourceBranch,
            String username,
            String userBranch) throws IOException {
        String existingBase = valueOrEmpty(
                workspaceGit.getDslAtHead(TRACKING_BRANCH));
        String existingUserBranch = valueOrEmpty(
                workspaceGit.getDslAtHead(userBranch));
        if (!existingBase.isBlank() && !existingUserBranch.isBlank()) {
            return new WorkspaceMergeState(existingBase);
        }

        String source = valueOrEmpty(sourceRepository.getDslAtHead(sourceBranch));
        String seeded = valueOrEmpty(workspaceGit.getDslAtHead(SEEDED_BRANCH));
        String commonBase = !seeded.isBlank() ? seeded : source;
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
                                   String username,
                                   String message) throws IOException {
        if (valueOrEmpty(previous).strip().equals(valueOrEmpty(merged).strip())) {
            String head = repository.getHeadCommit(branch);
            if (head != null) {
                return head;
            }
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
        if (dsl.isBlank()) {
            throw new IOException(error);
        }
        return dsl;
    }

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
