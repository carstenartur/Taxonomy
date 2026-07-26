package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Handles synchronization between the internal system repository and an
 * external Git remote (for example Gitea, GitHub or GitLab).
 *
 * <p>Synchronization is ancestry-aware. A remote branch is fast-forwarded or
 * merged through JGit; remote file contents are never committed wholesale on
 * top of an unrelated local history. Diverging edits that cannot be merged are
 * reported as conflicts and leave the local shared branch unchanged.</p>
 */
@Service
public class ExternalGitSyncService {

    private static final Logger log = LoggerFactory.getLogger(ExternalGitSyncService.class);
    private static final Set<RemoteRefUpdate.Status> SUCCESSFUL_PUSH_STATUSES =
            EnumSet.of(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE);

    private final DslGitRepositoryFactory repositoryFactory;
    private final SystemRepositoryService systemRepositoryService;
    private final ExternalGitCredentials credentials;

    @Autowired
    public ExternalGitSyncService(DslGitRepositoryFactory repositoryFactory,
                                  SystemRepositoryService systemRepositoryService,
                                  ExternalGitCredentials credentials) {
        this.repositoryFactory = repositoryFactory;
        this.systemRepositoryService = systemRepositoryService;
        this.credentials = credentials;
    }

    /** Convenience constructor for focused tests without configured credentials. */
    public ExternalGitSyncService(DslGitRepositoryFactory repositoryFactory,
                                  SystemRepositoryService systemRepositoryService) {
        this(repositoryFactory, systemRepositoryService, ExternalGitCredentials.none());
    }

    /** Fetch all external branches into {@code refs/remotes/origin/*}. */
    public FetchResult fetchFromExternal() throws Exception {
        SystemRepository systemRepository = systemRepositoryService.getPrimaryRepository();
        validateExternalMode(systemRepository);

        Repository gitRepository = repositoryFactory.getSystemRepository().getGitRepository();
        URIish uri = new URIish(systemRepository.getExternalUrl());

        log.info("Fetching from configured external Git remote");
        try (Transport transport = Transport.open(gitRepository, uri)) {
            credentials.configure(transport);
            FetchResult result = transport.fetch(
                    NullProgressMonitor.INSTANCE,
                    List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*")));

            String sharedBranch = systemRepositoryService.getSharedBranch();
            Ref sharedRemoteRef = gitRepository.getRefDatabase()
                    .exactRef(remoteTrackingRefName(sharedBranch));

            systemRepository.setLastFetchAt(Instant.now());
            if (sharedRemoteRef != null && sharedRemoteRef.getObjectId() != null) {
                systemRepository.setLastFetchCommit(sharedRemoteRef.getObjectId().name());
            }
            systemRepositoryService.save(systemRepository);

            log.info("External fetch completed with {} tracking updates",
                    result.getTrackingRefUpdates().size());
            return result;
        }
    }

    /**
     * Push a local branch and fail when the remote rejects or does not perform
     * the requested update.
     */
    public PushResult pushToExternal(String localBranch) throws Exception {
        SystemRepository systemRepository = systemRepositoryService.getPrimaryRepository();
        validateExternalMode(systemRepository);
        validateBranchName(localBranch);

        Repository gitRepository = repositoryFactory.getSystemRepository().getGitRepository();
        URIish uri = new URIish(systemRepository.getExternalUrl());

        log.info("Pushing branch '{}' to configured external Git remote", localBranch);
        try (Transport transport = Transport.open(gitRepository, uri)) {
            credentials.configure(transport);
            RemoteRefUpdate refUpdate = new RemoteRefUpdate(
                    gitRepository,
                    localRefName(localBranch),
                    localRefName(localBranch),
                    false,
                    null,
                    null);
            PushResult result = transport.push(
                    NullProgressMonitor.INSTANCE,
                    List.of(refUpdate));

            verifyPushResult(result, localBranch);

            systemRepository.setLastPushAt(Instant.now());
            systemRepositoryService.save(systemRepository);
            log.info("Push completed successfully for branch '{}'", localBranch);
            return result;
        }
    }

    /**
     * Fetch and integrate the configured remote shared branch.
     *
     * <p>The method is synchronized to prevent two in-process synchronizations
     * from reusing the temporary merge ref concurrently. JGit expected-old
     * object IDs still protect every durable ref update.</p>
     *
     * @return the resulting local shared-branch commit, or {@code null} when the
     *         configured remote does not contain the shared branch
     * @throws ExternalSyncConflictException when histories diverge and cannot be merged
     */
    public synchronized String fullSync(String username) throws Exception {
        fetchFromExternal();

        DslGitRepository dslRepository = repositoryFactory.getSystemRepository();
        Repository gitRepository = dslRepository.getGitRepository();
        String sharedBranch = systemRepositoryService.getSharedBranch();
        validateBranchName(sharedBranch);

        String localRefName = localRefName(sharedBranch);
        Ref remoteRef = gitRepository.getRefDatabase()
                .exactRef(remoteTrackingRefName(sharedBranch));
        if (remoteRef == null || remoteRef.getObjectId() == null) {
            log.info("External sync found no remote shared branch '{}'", sharedBranch);
            return null;
        }

        Ref localRef = gitRepository.getRefDatabase().exactRef(localRefName);
        if (localRef == null || localRef.getObjectId() == null) {
            updateRef(
                    gitRepository,
                    localRefName,
                    remoteRef.getObjectId(),
                    ObjectId.zeroId(),
                    false,
                    username,
                    "external-sync: create shared branch from remote");
            log.info("Created local shared branch '{}' at fetched remote commit {}",
                    sharedBranch, remoteRef.getObjectId().name());
            return remoteRef.getObjectId().name();
        }

        try (RevWalk walk = new RevWalk(gitRepository)) {
            RevCommit localCommit = walk.parseCommit(localRef.getObjectId());
            RevCommit remoteCommit = walk.parseCommit(remoteRef.getObjectId());

            if (walk.isMergedInto(remoteCommit, localCommit)) {
                log.info("External sync: local shared branch '{}' is already up to date", sharedBranch);
                return localCommit.name();
            }
        }

        String temporaryBranch = temporaryMergeBranch(sharedBranch);
        String temporaryRefName = localRefName(temporaryBranch);
        Ref previousTemporaryRef = gitRepository.getRefDatabase().exactRef(temporaryRefName);
        ObjectId expectedTemporaryHead = previousTemporaryRef != null
                && previousTemporaryRef.getObjectId() != null
                ? previousTemporaryRef.getObjectId()
                : ObjectId.zeroId();

        updateRef(
                gitRepository,
                temporaryRefName,
                remoteRef.getObjectId(),
                expectedTemporaryHead,
                true,
                username,
                "external-sync: stage fetched shared branch");

        try {
            String resultingCommit = dslRepository.merge(temporaryBranch, sharedBranch);
            if (resultingCommit == null) {
                throw new ExternalSyncConflictException(
                        "The local and external shared branches contain conflicting changes");
            }
            log.info("External sync integrated remote branch into '{}' at {}",
                    sharedBranch, resultingCommit);
            return resultingCommit;
        } finally {
            deleteTemporaryRef(gitRepository, temporaryRefName);
        }
    }

    /** Get the current external synchronization status without exposing credentials. */
    public ExternalSyncStatus getStatus() {
        try {
            SystemRepository systemRepository = systemRepositoryService.getPrimaryRepository();
            return new ExternalSyncStatus(
                    systemRepository.getTopologyMode() == RepositoryTopologyMode.EXTERNAL_CANONICAL,
                    systemRepository.getExternalUrl(),
                    credentials.isConfigured(),
                    systemRepository.getLastFetchAt(),
                    systemRepository.getLastPushAt(),
                    systemRepository.getLastFetchCommit());
        } catch (Exception exception) {
            log.debug("External sync status is unavailable", exception);
            return new ExternalSyncStatus(false, null, credentials.isConfigured(), null, null, null);
        }
    }

    private void verifyPushResult(PushResult result, String branch) throws ExternalPushRejectedException {
        if (result.getRemoteUpdates().isEmpty()) {
            throw new ExternalPushRejectedException(
                    "External Git remote did not report an update for branch '" + branch + "'");
        }

        for (RemoteRefUpdate update : result.getRemoteUpdates()) {
            if (!SUCCESSFUL_PUSH_STATUSES.contains(update.getStatus())) {
                String reason = update.getMessage();
                String suffix = reason == null || reason.isBlank() ? "" : " (" + reason + ")";
                throw new ExternalPushRejectedException(
                        "External Git remote rejected branch '" + branch + "': "
                                + update.getStatus() + suffix);
            }
        }
    }

    private void validateExternalMode(SystemRepository systemRepository) {
        if (systemRepository.getTopologyMode() != RepositoryTopologyMode.EXTERNAL_CANONICAL) {
            throw new IllegalStateException(
                    "External sync operations require EXTERNAL_CANONICAL topology mode, but current mode is "
                            + systemRepository.getTopologyMode());
        }
        if (systemRepository.getExternalUrl() == null
                || systemRepository.getExternalUrl().isBlank()) {
            throw new IllegalStateException("External URL is not configured");
        }
    }

    private static void validateBranchName(String branch) {
        if (branch == null || branch.isBlank()
                || !Repository.isValidRefName(localRefName(branch))) {
            throw new IllegalArgumentException("Invalid Git branch name");
        }
    }

    private static String localRefName(String branch) {
        return Constants.R_HEADS + branch;
    }

    private static String remoteTrackingRefName(String branch) {
        return Constants.R_REMOTES + "origin/" + branch;
    }

    private static String temporaryMergeBranch(String sharedBranch) {
        return "__external_sync__/" + sharedBranch.replaceAll("[^A-Za-z0-9._/-]", "_");
    }

    private static void updateRef(Repository repository,
                                  String refName,
                                  ObjectId newObjectId,
                                  ObjectId expectedOldObjectId,
                                  boolean force,
                                  String username,
                                  String reflogMessage) throws IOException {
        RefUpdate update = repository.updateRef(refName);
        update.setNewObjectId(newObjectId);
        update.setExpectedOldObjectId(expectedOldObjectId);
        update.setForceUpdate(force);
        update.setRefLogIdent(actor(username));
        update.setRefLogMessage(reflogMessage, false);
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW
                && result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("Git ref update failed for " + refName + ": " + result);
        }
    }

    private static void deleteTemporaryRef(Repository repository, String refName) {
        try {
            Ref existing = repository.getRefDatabase().exactRef(refName);
            if (existing == null || existing.getObjectId() == null) {
                return;
            }
            RefUpdate delete = repository.updateRef(refName);
            delete.setExpectedOldObjectId(existing.getObjectId());
            delete.setForceUpdate(true);
            delete.setRefLogIdent(actor("taxonomy"));
            delete.setRefLogMessage("external-sync: remove temporary merge ref", false);
            RefUpdate.Result result = delete.delete();
            if (result != RefUpdate.Result.FORCED
                    && result != RefUpdate.Result.NO_CHANGE
                    && result != RefUpdate.Result.NEW) {
                log.warn("Could not remove temporary external-sync ref {}: {}", refName, result);
            }
        } catch (IOException exception) {
            log.warn("Could not remove temporary external-sync ref {}", refName, exception);
        }
    }

    private static PersonIdent actor(String username) {
        String name = username == null || username.isBlank() ? "taxonomy" : username;
        String safeName = name.replaceAll("[\\r\\n<>]", "_");
        return new PersonIdent(safeName, "taxonomy@localhost");
    }

    /** Raised when a fetched branch cannot be merged without conflicts. */
    public static class ExternalSyncConflictException extends IOException {
        public ExternalSyncConflictException(String message) {
            super(message);
        }
    }

    /** Raised when the remote does not accept the requested push update. */
    public static class ExternalPushRejectedException extends IOException {
        public ExternalPushRejectedException(String message) {
            super(message);
        }
    }

    public record ExternalSyncStatus(
            boolean externalEnabled,
            String externalUrl,
            boolean credentialConfigured,
            Instant lastFetchAt,
            Instant lastPushAt,
            String lastFetchCommit) {
    }
}
