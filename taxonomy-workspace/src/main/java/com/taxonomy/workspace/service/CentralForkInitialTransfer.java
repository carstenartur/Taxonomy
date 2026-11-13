package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.eclipse.jgit.lib.Constants;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** Publishes only the captured original central-fork commit under the shared transfer lease. */
@Service
public class CentralForkInitialTransfer {
    private final SystemRepositoryService catalogue;
    private final RepositoryMembershipService memberships;
    private final DslGitRepositoryFactory repositories;
    private final WorkspaceFetchJournal journal;
    private final WorkspaceFetchAuthority actors;

    public CentralForkInitialTransfer(SystemRepositoryService catalogue, RepositoryMembershipService memberships,
                                     DslGitRepositoryFactory repositories, WorkspaceFetchJournal journal, WorkspaceFetchAuthority actors) {
        this.catalogue = catalogue;
        this.memberships = memberships;
        this.repositories = repositories;
        this.journal = journal;
        this.actors = actors;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemRepository admit(String source, String branch, String commit, String name, String slug,
                                  String description, RepositoryVisibility visibility, String username) {
        var grant = actors.authorize(username, RepositoryScope.CENTRAL_WRITE);
        var fork = catalogue.createForkMetadata(source, branch, commit, name, slug, description, visibility, username);
        if (!grant.principalId().equals(fork.getForkInitiatorKey())) throw unavailable();
        memberships.assignOwner(fork.getRepositoryId(), username);
        journal.admitInitialFork(fork, grant);
        return fork;
    }

    boolean isJournalled(String targetId) { return journal.hasInitialFork(targetId); }

    SystemRepository complete(SystemRepository fork, String username) {
        var context = RepositoryContext.centralWrite(fork.getRepositoryId(), fork.getDefaultBranch(), username);
        var actor = actors.authorize(username, RepositoryScope.CENTRAL_WRITE);
        String id = CentralFetchOperation.initialId(fork.getRepositoryId());
        var state = journal.status(context, actor.principalId(), id);
        if ("COMPLETED".equals(state.phase())) {
            requireOriginal(state, fork);
            return fork;
        }
        // An interactive owner may supply fresh external authorization after a worker had to stop.
        var claim = journal.claim(id, actor.principalId(), !"AWAITING_AUTHORIZATION".equals(state.phase()));
        if (claim == null) {
            state = journal.status(context, actor.principalId(), id);
            if ("COMPLETED".equals(state.phase())) {
                var completed = catalogue.getRepository(fork.getRepositoryId());
                requireOriginal(state, completed);
                ArchitectureRepositoryProvisioningService.verifyRetainedFork(repositories.getCentralRepository(completed.getRepositoryId()), completed);
                return completed;
            }
            if ("DENIED".equals(state.phase())) throw unavailable();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Fork transfer awaits its recorded claim or requires review");
        }
        try {
            journal.run(claim, (row, checkLease) -> apply((CentralFetchOperation) row, () -> {
                actors.checkPublication(actor, username, RepositoryScope.CENTRAL_WRITE, false);
                if (!Objects.equals(row.actor, actor.principalId()) || !Objects.equals(row.bindingKey, actor.bindingKey())
                        || !Objects.equals(row.loginKind, actor.loginKind())) throw unavailable();
                checkLease.run();
            }));
        } catch (IOException failure) {
            if (failure instanceof BranchHeadConflictException)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The retained fork requires review", failure);
            throw new UncheckedIOException(failure);
        }
        return catalogue.getRepository(fork.getRepositoryId());
    }

    private static void requireOriginal(WorkspaceFetchJournal.View receipt, SystemRepository fork) {
        if (!Objects.equals(receipt.resultCommit(), fork.getForkPointCommit())
                || !Objects.equals(receipt.sourceRepositoryId(), fork.getUpstreamRepositoryId())
                || fork.getLifecycleState() != RepositoryLifecycleState.ACTIVE) throw unavailable();
    }

    WorkspaceUpstreamService.Status apply(CentralFetchOperation row, Runnable authorize) throws IOException {
        if (!row.isInitialFork() || !Objects.equals(row.requested, row.forkPoint) || row.expected != null
                || row.selectedTag != null || !Objects.equals(row.branch, row.sourceBranch)) throw unavailable();
        authorize.run();
        var fork = catalogue.getRepository(row.targetRepositoryId);
        if (!row.fingerprint.equals(fork.forkRequestFingerprint()) || !row.actor.equals(fork.getForkInitiatorKey())
                || !Objects.equals(row.forkPoint, fork.getForkPointCommit()) || !row.sourceRepositoryId.equals(fork.getUpstreamRepositoryId())
                || !row.sourceBranch.equals(fork.getUpstreamBranch()) || !row.branch.equals(fork.getDefaultBranch())
                || !row.username.equals(fork.getOwnerId()) || fork.getLifecycleState() != RepositoryLifecycleState.PROVISIONING)
            throw unavailable();
        catalogue.readCurrentAuthorization(row.sourceRepositoryId, current -> requireSource(current, fork, row.username));
        authorize.run();
        var source = repositories.getCentralRepository(row.sourceRepositoryId);
        var target = repositories.createCentralRepository(fork.getRepositoryId(), fork.getStorageRepositoryName());
        var upstream = new GitUpstreamRef(row.sourceRepositoryId, row.sourceBranch);
        var localRef = Constants.R_HEADS + row.branch;
        String captured = row.forkPoint;
        String tracked = upstream.head(target);
        String local = target.getHeadCommit(row.branch);
        for (var ref : target.getGitRepository().getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
            if (ref.isSymbolic() || ref.getObjectId() == null || !captured.equals(ref.getObjectId().name())
                    || !(localRef.equals(ref.getName()) || upstream.name().equals(ref.getName())))
                throw conflict(row.branch, captured, local);
        }
        if (local != null && tracked == null) throw conflict(row.branch, captured, local);
        String observed = requireSourceLineage(source, row.sourceBranch, captured);
        ArchitectureRepositoryProvisioningService.verifyForkSnapshot(source, captured);
        GitHistoryTransfer.copyCommitClosure(source, target, captured, List.of());

        // Hold the policy-edit fences for publication only. Role revocation remains possible during a large object import.
        var currentSource = catalogue.lockCurrentPublicationSource(row.sourceRepositoryId);
        var currentTarget = catalogue.lockCurrentPublicationSource(row.targetRepositoryId);
        if (!row.fingerprint.equals(currentTarget.forkRequestFingerprint()) || currentTarget.getLifecycleState() != RepositoryLifecycleState.PROVISIONING)
            throw unavailable();
        Runnable current = () -> { requireSource(currentSource, currentTarget, row.username); authorize.run(); };
        current.run();
        // Re-read the source reference immediately before either publication. A true descendant retains the captured source.
        requireSourceLineage(source, row.sourceBranch, captured);
        if (tracked == null) target.recordUpstreamForkPoint(row.sourceRepositoryId, row.sourceBranch, captured, row.username);
        current.run();
        requireSourceLineage(source, row.sourceBranch, captured);
        if (local == null) target.fastForwardIfHeadMatches(row.branch, null, captured, row.username, "Provision repository fork");
        target.verifyExpectedHead(row.branch, captured);
        if (!captured.equals(upstream.head(target))) throw conflict(upstream.name(), captured, upstream.head(target));
        current.run();
        catalogue.markProvisioningReady(row.targetRepositoryId);
        return new WorkspaceUpstreamService.Status(row.sourceRepositoryId, row.sourceBranch, observed, upstream.name(), captured);
    }

    private void requireSource(SystemRepository source, SystemRepository fork, String username) {
        if (source.getLifecycleState() != RepositoryLifecycleState.ACTIVE || !memberships.canContribute(source, username)) throw unavailable();
        SystemRepositoryService.requirePermittedForkVisibility(source, fork.getVisibility());
    }

    private static String requireSourceLineage(DslGitRepository source, String branch,
                                               String captured) throws IOException {
        var ref = source.readFreshRef(Constants.R_HEADS + branch);
        if (ref == null || ref.isSymbolic() || ref.getObjectId() == null) throw conflict(branch, captured, null);
        String head = ref.getObjectId().name();
        if (!source.isAncestor(captured, head)) throw conflict(branch, captured, head);
        return head;
    }

    private static BranchHeadConflictException conflict(String ref, String expected, String actual) {
        return new BranchHeadConflictException(ref, expected, actual, "Retained authority changed while provisioning the central fork");
    }

    private static AccessDeniedException unavailable() { return new AccessDeniedException("The original fork authority is unavailable"); }
}
