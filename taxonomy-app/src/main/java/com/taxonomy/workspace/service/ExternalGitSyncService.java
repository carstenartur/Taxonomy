package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Handles synchronization between the internal system repository and an
 * external Git remote (e.g. Gitea, GitHub, GitLab).
 *
 * <p>This service is only active when the system repository is configured
 * in {@link RepositoryTopologyMode#EXTERNAL_CANONICAL} mode. It uses JGit's
 * {@link Transport} API to perform fetch and push operations directly on the
 * underlying DFS repository.
 *
 * <p>Typical workflow:
 * <ol>
 *   <li>{@link #fetchFromExternal()} — fetch all branches from the remote</li>
 *   <li>{@link #pushToExternal(String)} — push a local branch to the remote</li>
 *   <li>{@link #fullSync(String)} — fetch + merge remote into shared branch</li>
 * </ol>
 */
@Service
public class ExternalGitSyncService {

    private static final Logger log = LoggerFactory.getLogger(ExternalGitSyncService.class);

    private final DslGitRepositoryFactory repositoryFactory;
    private final SystemRepositoryService systemRepositoryService;

    @Autowired
    public ExternalGitSyncService(DslGitRepositoryFactory repositoryFactory,
                                  SystemRepositoryService systemRepositoryService) {
        this.repositoryFactory = repositoryFactory;
        this.systemRepositoryService = systemRepositoryService;
    }

    /**
     * Fetch all branches from the external remote into the system repository.
     *
     * @return the JGit FetchResult
     * @throws Exception if the fetch operation fails
     */
    public FetchResult fetchFromExternal() throws Exception {
        SystemRepository sysRepo = systemRepositoryService.getPrimaryRepository();
        validateExternalMode(sysRepo);

        Repository gitRepo = repositoryFactory.getSystemRepository().getGitRepository();
        URIish uri = new URIish(sysRepo.getExternalUrl());

        log.info("Fetching from external remote: {}", sysRepo.getExternalUrl());
        try (Transport transport = Transport.open(gitRepo, uri)) {
            configureCredentials(transport, sysRepo);
            FetchResult result = transport.fetch(NullProgressMonitor.INSTANCE,
                    List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*")));

            log.info("Fetch complete from {}: {} updates",
                    sysRepo.getExternalUrl(),
                    result.getTrackingRefUpdates().size());
            return result;
        }
    }

    /**
     * Push a local branch to the external remote.
     *
     * @param localBranch the local branch name to push
     * @return the JGit PushResult
     * @throws Exception if the push operation fails
     */
    public PushResult pushToExternal(String localBranch) throws Exception {
        SystemRepository sysRepo = systemRepositoryService.getPrimaryRepository();
        validateExternalMode(sysRepo);

        Repository gitRepo = repositoryFactory.getSystemRepository().getGitRepository();
        URIish uri = new URIish(sysRepo.getExternalUrl());

        log.info("Pushing branch '{}' to external remote: {}", localBranch, sysRepo.getExternalUrl());
        try (Transport transport = Transport.open(gitRepo, uri)) {
            configureCredentials(transport, sysRepo);
            RemoteRefUpdate refUpdate = new RemoteRefUpdate(
                    gitRepo,
                    "refs/heads/" + localBranch,
                    "refs/heads/" + localBranch,
                    false, null, null);
            PushResult result = transport.push(NullProgressMonitor.INSTANCE,
                    java.util.List.of(refUpdate));

            log.info("Push complete: branch '{}' → {}", localBranch, sysRepo.getExternalUrl());
            return result;
        }
    }

    /**
     * Full sync cycle: fetch from remote, then merge into the shared branch.
     *
     * @param username the user performing the sync
     * @return the merge commit SHA, or null if no merge was needed
     * @throws Exception if any operation fails
     */
    public String fullSync(String username) throws Exception {
        fetchFromExternal();

        DslGitRepository sysRepo = repositoryFactory.getSystemRepository();
        String sharedBranch = systemRepositoryService.getSharedBranch();

        // Try to merge remote tracking branch into local shared branch
        String remoteRef = "remotes/origin/" + sharedBranch;
        String remoteDsl = sysRepo.getDslAtHead(remoteRef);
        if (remoteDsl == null) {
            // Try without remotes/ prefix
            remoteDsl = sysRepo.getDslAtHead("origin/" + sharedBranch);
        }
        if (remoteDsl != null) {
            String localDsl = sysRepo.getDslAtHead(sharedBranch);
            if (localDsl == null || !remoteDsl.equals(localDsl)) {
                String commitId = sysRepo.commitDsl(sharedBranch, remoteDsl, username,
                        "Synced from external remote");
                log.info("Full sync complete: merged remote into shared branch '{}'", sharedBranch);
                return commitId;
            }
            log.info("Full sync: shared branch '{}' already up-to-date", sharedBranch);
            return sysRepo.getHeadCommit(sharedBranch);
        }

        log.info("Full sync: no remote content found for branch '{}'", sharedBranch);
        return null;
    }

    /**
     * Get the current external sync status.
     *
     * @return a status summary
     */
    public ExternalSyncStatus getStatus() {
        try {
            SystemRepository sysRepo = systemRepositoryService.getPrimaryRepository();
            return new ExternalSyncStatus(
                    sysRepo.getTopologyMode() == RepositoryTopologyMode.EXTERNAL_CANONICAL,
                    sysRepo.getExternalUrl(),
                    sysRepo.getLastFetchAt(),
                    sysRepo.getLastPushAt(),
                    sysRepo.getLastFetchCommit()
            );
        } catch (Exception e) {
            return new ExternalSyncStatus(false, null, null, null, null);
        }
    }

    private void validateExternalMode(SystemRepository sysRepo) {
        if (sysRepo.getTopologyMode() != RepositoryTopologyMode.EXTERNAL_CANONICAL) {
            throw new IllegalStateException(
                    "External sync operations require EXTERNAL_CANONICAL topology mode, " +
                            "but current mode is " + sysRepo.getTopologyMode());
        }
        if (sysRepo.getExternalUrl() == null || sysRepo.getExternalUrl().isBlank()) {
            throw new IllegalStateException("External URL is not configured");
        }
    }

    private void configureCredentials(Transport transport, SystemRepository sysRepo) {
        String token = sysRepo.getExternalAuthToken();
        if (token != null && !token.isBlank()) {
            transport.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(token, ""));
        }
    }

    /**
     * Summary of the external sync configuration and state.
     */
    public record ExternalSyncStatus(
            boolean externalEnabled,
            String externalUrl,
            Instant lastFetchAt,
            Instant lastPushAt,
            String lastFetchCommit
    ) {}
}
