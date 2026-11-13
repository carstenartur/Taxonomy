package com.taxonomy.versioning.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Objects;

import static com.taxonomy.versioning.service.HistoryIndexJournal.Kind;
import static com.taxonomy.versioning.service.HistoryIndexJournal.Receipt;

@Service
public class HistoryIndexMaintenance {
    private static final String WORKER = "system:history-index";

    private final HistoryIndexJournal journal;
    private final HistoryIndexGrant actors;
    private final WorkspaceResolver selected;
    private final DslGitRepositoryFactory repositories;
    private final SystemRepositoryRepository catalogue;
    private final RepositoryMembershipService membership;
    private final EntityManager entities;
    private final CommitIndexService projections;

    public HistoryIndexMaintenance(HistoryIndexJournal journal, HistoryIndexGrant actors, WorkspaceResolver selected,
                                   DslGitRepositoryFactory repositories, SystemRepositoryRepository catalogue,
                                   RepositoryMembershipService membership, EntityManager entities, CommitIndexService projections) {
        this.journal = journal;
        this.actors = actors;
        this.selected = selected;
        this.repositories = repositories;
        this.catalogue = catalogue;
        this.membership = membership;
        this.entities = entities;
        this.projections = projections;
    }

    public Receipt submit(Kind kind, String requestedBranch, String key, String cause) {
        var context = selected.resolveCurrentRepositoryContext();
        var grant = actors.authorize(context.username(), kind == Kind.PURGE);
        String branch = kind == Kind.PURGE ? context.branch() : checkedBranch(requestedBranch);
        var row = journal.admit(context, branch, kind, grant, key, cause, () -> {
            String owner = authorizeTarget(context);
            if (kind == Kind.PURGE) return new HistoryIndexJournal.Capture(null, owner);
            try {
                String head = repositories.resolveRepository(context).getHeadCommit(branch);
                if (head == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "History branch is unavailable");
                return new HistoryIndexJournal.Capture(head, owner);
            } catch (IOException failure) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "History source is unavailable", failure);
            }
        });
        return resume(row, context, grant.principalId(), false);
    }

    public Receipt get(String id) {
        var context = selected.resolveCurrentRepositoryContextForRead();
        return journal.inspect(context, actors.currentActor(context.username()), id, this::verifyTarget);
    }

    public Receipt retry(String id) {
        var context = selected.resolveCurrentRepositoryContext();
        var row = journal.get(context, actors.currentActor(context.username()), id);
        var grant = actors.authorize(context.username(), row.receipt().kind() == Kind.PURGE);
        if (!row.grant().equals(grant)) throw denied();
        return resume(row, context, grant.principalId(), true);
    }

    public Receipt archive(String id) {
        var context = selected.resolveCurrentRepositoryContextForRead();
        var grant = actors.authorize(context.username(), true);
        return journal.archive(context, grant.principalId(), id, this::verifyTarget);
    }

    private Receipt resume(HistoryIndexJournal.Operation row, RepositoryContext context, String actor, boolean explicit) {
        var token = journal.claim(row.receipt().operationId(), actor, explicit);
        return token == null
                ? journal.inspect(context, actor, row.receipt().operationId(), this::verifyTarget)
                : run(token, false);
    }

    @Scheduled(fixedDelayString = "${taxonomy.history-index.recovery-delay-ms:60000}")
    public void recover() {
        for (String id : journal.due()) {
            var token = journal.claim(id, WORKER, false);
            if (token != null) {
                try {
                    run(token, true);
                } catch (ResponseStatusException failure) {
                    if (failure.getStatusCode() != HttpStatus.CONFLICT) throw failure;
                }
            }
        }
        journal.expire();
    }

    private Receipt run(HistoryIndexJournal.Claim token, boolean background) {
        try {
            return journal.execute(token, row -> {
                var receipt = row.receipt();
                boolean purge = receipt.kind() == Kind.PURGE;
                actors.verify(row.grant(), row.username(), purge, background);
                var context = row.context();
                verifyTarget(row);
                int result = purge ? projections.purgeCaptured(context, receipt.operationId())
                        : projections.applyCapturedBranch(receipt.branch(), context, receipt.sourceCommit(), receipt.kind() == Kind.REBUILD);
                actors.verify(row.grant(), row.username(), purge, background);
                verifyTarget(row);
                return result;
            });
        } catch (WorkspaceFetchAuthority.FreshLoginRequired failure) {
            return journal.failed(token, "AWAITING_AUTHORIZATION", "FRESH_LOGIN_REQUIRED");
        } catch (AccessDeniedException | org.springframework.security.core.AuthenticationException failure) {
            return journal.failed(token, "DENIED", "AUTHORIZATION_CHANGED");
        } catch (TransientDataAccessException failure) {
            return journal.failed(token, "RETRYABLE", "PROJECTION_INTERRUPTED");
        } catch (IllegalStateException failure) {
            var original = NestedExceptionUtils.getMostSpecificCause(failure);
            if (original instanceof BranchHeadConflictException changed) {
                var row = journal.claimed(token);
                String actual = changed.getActualHeadCommit();
                try {
                    if (actual != null && repositories.resolveRepository(row.context()).isAncestor(row.receipt().sourceCommit(), actual)) {
                        return journal.failed(token, "SUPERSEDED", "GIT_HEAD_ADVANCED");
                    }
                } catch (IOException readFailure) {
                    return journal.failed(token, "MANUAL_REVIEW", "GIT_ANCESTRY_UNAVAILABLE");
                }
                return journal.failed(token, "MANUAL_REVIEW", "GIT_HISTORY_CHANGED");
            }
            if (original instanceof IOException) {
                return journal.failed(token, "RETRYABLE", "GIT_READ_INTERRUPTED");
            }
            throw failure;
        }
    }

    private void verifyTarget(HistoryIndexJournal.Operation row) {
        if (!Objects.equals(row.owner(), authorizeTarget(row.context()))) throw denied();
    }

    /** Use physical row writes for HSQL MVCC as well as PostgreSQL before testing target policy. */
    private String authorizeTarget(RepositoryContext context) {
        if (catalogue.lockForMutation(context.repositoryId()) != 1) throw denied();
        // Role updates and revocations mutate these same membership rows. A physical write
        // also holds the fence on HSQL MVCC, where SELECT FOR UPDATE alone is insufficient.
        entities.createQuery("update RepositoryMembership m set m.version=m.version where m.repositoryId=:repository and m.username=:actor")
                .setParameter("repository", context.repositoryId()).setParameter("actor", context.username()).executeUpdate();
        var repo = catalogue.findByRepositoryId(context.repositoryId()).orElseThrow(HistoryIndexMaintenance::denied);
        if (context.workspaceId() == null) {
            if (!membership.canContribute(repo, context.username())) throw denied();
            return null;
        }
        if (entities.createQuery("update UserWorkspace w set w.archived=w.archived where w.workspaceId=:id")
                .setParameter("id", context.workspaceId()).executeUpdate() != 1) throw denied();
        var work = entities.createQuery("select w from UserWorkspace w where w.workspaceId=:id", UserWorkspace.class)
                .setParameter("id", context.workspaceId()).getSingleResult();
        if (!membership.canUseWorkspace(repo, work, context.username()) || work.getProvisioningStatus() != WorkspaceProvisioningStatus.READY) throw denied();
        if (!work.getUsername().equals(context.username()) && (!context.collaborative() || !work.isCollaborative())) throw denied();
        return work.getUsername();
    }

    private static String checkedBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.strip().length() > 255
                || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch.strip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history branch");
        }
        return branch.strip();
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("History maintenance permission is unavailable"); }
}
