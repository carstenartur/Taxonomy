package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
    public synchronized FetchResult fetchFromExternal() throws Exception {
        return fetchFromExternal(systemRepositoryService.getPrimaryRepository(),
                repositoryFactory.getSystemRepository(), systemRepositoryService.getSharedBranch());
    }

    /** Fetch only the explicitly selected central repository; an invalid ID never selects primary. */
    public synchronized FetchResult fetchFromExternal(String repositoryId) throws Exception {
        SystemRepository selected = systemRepositoryService.getRepository(repositoryId);
        return fetchFromExternal(selected, repositoryFactory.getCentralRepository(repositoryId),
                selected.getDefaultBranch());
    }

    private FetchResult fetchFromExternal(SystemRepository systemRepository,
                                           DslGitRepository dslRepository,
                                           String sharedBranch) throws Exception {
        URIish uri = validateExternalModeAndCreateUri(systemRepository);
        validateBranchName(sharedBranch);
        Repository gitRepository = dslRepository.getGitRepository();
        log.info("Fetching from configured external Git remote");
        try (Transport transport = Transport.open(gitRepository, uri)) {
            credentials.configure(transport, systemRepository.getRepositoryId(), systemRepository.isPrimaryRepo());
            FetchResult result = transport.fetch(
                    NullProgressMonitor.INSTANCE,
                    List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*")));

            // Tracking refs are fetched objects, not an acceptance boundary. Verify
            // against local accepted ancestry before recording successful import.
            for (Ref fetched : gitRepository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/")) {
                if (fetched.getObjectId() != null) {
                    String branch = fetched.getName().substring((Constants.R_REMOTES + "origin/").length());
                    Ref accepted = gitRepository.getRefDatabase().exactRef(localRefName(branch));
                    dslRepository.verifyIncoming(fetched.getObjectId().name(),
                            accepted == null || accepted.getObjectId() == null ? null : accepted.getObjectId().name());
                }
            }
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
    public synchronized PushResult pushToExternal(String localBranch) throws Exception {
        return pushToExternal(systemRepositoryService.getPrimaryRepository(),
                repositoryFactory.getSystemRepository(), localBranch);
    }

    public synchronized PushResult pushToExternal(String repositoryId, String localBranch) throws Exception {
        SystemRepository selected = systemRepositoryService.getRepository(repositoryId);
        return pushToExternal(selected, repositoryFactory.getCentralRepository(repositoryId), localBranch);
    }

    private PushResult pushToExternal(SystemRepository systemRepository,
                                     DslGitRepository dslRepository,
                                     String localBranch) throws Exception {
        URIish uri = validateExternalModeAndCreateUri(systemRepository);
        validateBranchName(localBranch);
        Repository gitRepository = dslRepository.getGitRepository();
        log.info("Pushing branch '{}' to configured external Git remote", localBranch);
        try (Transport transport = Transport.open(gitRepository, uri)) {
            credentials.configure(transport, systemRepository.getRepositoryId(), systemRepository.isPrimaryRepo());
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
     * <p>Synchronization serializes in-process external operations. Each merge
     * uses its own temporary ref, and expected-old object IDs protect creation,
     * integration and cleanup from overwriting another writer's changes.</p>
     *
     * @return the resulting local shared-branch commit, or {@code null} when the
     *         configured remote does not contain the shared branch
     * @throws ExternalSyncConflictException when histories diverge and cannot be merged
     */
    public synchronized String fullSync(String username) throws Exception {
        SystemRepository primary = systemRepositoryService.getPrimaryRepository();
        return fullSync(primary, repositoryFactory.getSystemRepository(),
                systemRepositoryService.getSharedBranch(), username);
    }

    public synchronized String fullSync(String repositoryId, String username) throws Exception {
        SystemRepository selected = systemRepositoryService.getRepository(repositoryId);
        return fullSync(selected, repositoryFactory.getCentralRepository(repositoryId),
                selected.getDefaultBranch(), username);
    }

    private String fullSync(SystemRepository metadata, DslGitRepository dslRepository,
                            String sharedBranch, String username) throws Exception {
        fetchFromExternal(metadata, dslRepository, sharedBranch);
        Repository gitRepository = dslRepository.getGitRepository();
        validateBranchName(sharedBranch);

        String localRefName = localRefName(sharedBranch);
        Ref remoteRef = gitRepository.getRefDatabase()
                .exactRef(remoteTrackingRefName(sharedBranch));
        if (remoteRef == null || remoteRef.getObjectId() == null) {
            log.info("External sync found no remote shared branch '{}'", sharedBranch);
            return null;
        }

        Ref localRef = gitRepository.getRefDatabase().exactRef(localRefName);
        dslRepository.verifyIncoming(remoteRef.getObjectId().name(),
                localRef == null || localRef.getObjectId() == null ? null : localRef.getObjectId().name());
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

        String temporaryBranch = "__external_sync__/" + UUID.randomUUID();
        String temporaryRefName = localRefName(temporaryBranch);

        updateRef(
                gitRepository,
                temporaryRefName,
                remoteRef.getObjectId(),
                ObjectId.zeroId(),
                false,
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
            deleteTemporaryRef(gitRepository, temporaryRefName, remoteRef.getObjectId());
        }
    }

    /** Get the current external synchronization status without exposing credentials. */
    public ExternalSyncStatus getStatus() {
        try {
            SystemRepository systemRepository = systemRepositoryService.getPrimaryRepository();
            return statusFor(systemRepository);
        } catch (Exception exception) {
            log.debug("External sync status is unavailable", exception);
            return new ExternalSyncStatus(false, null, credentials.isConfigured(), null, null, null);
        }
    }

    /** Explicit selection errors remain visible; no fallback to primary status. */
    public ExternalSyncStatus getStatus(String repositoryId) {
        return statusFor(systemRepositoryService.getRepository(repositoryId));
    }

    private ExternalSyncStatus statusFor(SystemRepository repository) {
        return new ExternalSyncStatus(
                repository.getTopologyMode() == RepositoryTopologyMode.EXTERNAL_CANONICAL,
                sanitizedExternalUrl(repository.getExternalUrl()),
                credentials.isConfigured(repository.getRepositoryId(), repository.isPrimaryRepo()),
                repository.getLastFetchAt(), repository.getLastPushAt(), repository.getLastFetchCommit());
    }

    /** Render persisted legacy URLs without disclosing credential-bearing user information. */
    private static String sanitizedExternalUrl(String externalUrl) {
        if (externalUrl == null) return null;
        try {
            return new URIish(externalUrl).setPass(null).setUser(null).toString();
        } catch (java.net.URISyntaxException | IllegalArgumentException exception) {
            // An unparseable legacy value cannot safely be returned as a URL.
            return null;
        }
    }

    /**
     * Validate a remote URL without returning or logging embedded credentials.
     * HTTP(S) user information and passwords in any URI form are rejected. SSH
     * user names such as {@code git@example.org:team/repo.git} remain supported,
     * but authentication material must come from deployment secret configuration.
     */
    static URIish validateExternalUrl(String externalUrl) {
        if (externalUrl == null || externalUrl.isBlank()) {
            throw new IllegalArgumentException("External URL is not configured");
        }
        try {
            URIish uri = new URIish(externalUrl.strip());
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean httpUserInfo = ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getUser() != null;
            if (uri.getPass() != null || httpUserInfo) {
                throw new IllegalArgumentException(
                        "External Git URL must not contain credentials or HTTP user information");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("External Git URL is invalid", exception);
        }
    }

    private URIish validateExternalModeAndCreateUri(SystemRepository systemRepository) {
        if (systemRepository.getTopologyMode() != RepositoryTopologyMode.EXTERNAL_CANONICAL) {
            throw new IllegalStateException(
                    "External sync operations require EXTERNAL_CANONICAL topology mode, but current mode is "
                            + systemRepository.getTopologyMode());
        }
        try {
            return validateExternalUrl(systemRepository.getExternalUrl());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
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

    private static void deleteTemporaryRef(Repository repository, String refName, ObjectId stagedHead) {
        try {
            Ref existing = repository.getRefDatabase().exactRef(refName);
            if (existing == null || !stagedHead.equals(existing.getObjectId())) {
                return;
            }
            RefUpdate delete = repository.updateRef(refName);
            delete.setExpectedOldObjectId(stagedHead);
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
