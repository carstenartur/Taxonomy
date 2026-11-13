package com.taxonomy.versioning.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.DerivedProjection;
import com.taxonomy.workspace.service.HistoryIndexGrant;
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
    private DerivedProjection derived;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureDerivedProjection(DerivedProjection derived) { this.derived = derived; }

    public record ProjectionOutcome(Receipt receipt, DerivedProjection.Result result) {}

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
        if (kind.relationProjection()) throw new IllegalArgumentException("Use the selected relation projection endpoint");
        var context = selected.resolveCurrentRepositoryContext();
        var grant = actors.authorizeHistoryIndex(context.username(), kind.destructive());
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

    /** The caller has already applied the public writable-scope selection and HTTP precondition grammar. */
    public ProjectionOutcome submitProjection(RepositoryContext context, DerivedProjection.Kind command,
                                             String expectedCommit, String key, String cause) {
        if (expectedCommit == null || !expectedCommit.matches("[a-fA-F0-9]{40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An existing Git commit is required");
        }
        Kind kind = Kind.valueOf(command.name());
        var grant = actors.authorizeHistoryIndex(context.username(), kind.destructive());
        var row = journal.admit(context, context.branch(), kind, grant, key, cause, () -> {
            String owner = authorizeProjectionTarget(context, grant, false);
            var source = derived.capture(context, command, expectedCommit);
            return new HistoryIndexJournal.Capture(expectedCommit, owner, source.key(), source.generation());
        });
        if (!expectedCommit.equals(row.receipt().sourceCommit())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to a different Git precondition");
        }
        var receipt = resume(row, context, grant.principalId(), false);
        if (!"COMPLETED".equals(receipt.phase())) {
            if ("GIT_HEAD_ADVANCED".equals(receipt.reason()) || "GIT_HISTORY_CHANGED".equals(receipt.reason())) {
                derived.verifyHead(context, expectedCommit);
            }
            var stored = journal.inspectOperation(context, grant.principalId(), receipt.operationId(), this::verifyTarget);
            return new ProjectionOutcome(stored.receipt(), stored.result() == null ? null : derived.readResult(stored.result()));
        }
        // Retained receipts carry only the original bounded response; Git HEAD and resource policy remain current.
        var completed = journal.inspectOperation(context, grant.principalId(), receipt.operationId(), this::verifyTarget);
        derived.verifyHead(context, expectedCommit);
        return new ProjectionOutcome(completed.receipt(), derived.readResult(completed.result()));
    }

    public Receipt get(String id) {
        var context = selected.resolveCurrentRepositoryContextForRead();
        return journal.inspect(context, actors.currentActor(context.username()), id, this::verifyReadableTarget);
    }

    public Receipt retry(String id) {
        var context = selected.resolveCurrentRepositoryContext();
        var row = journal.get(context, actors.currentActor(context.username()), id);
        var grant = actors.authorizeHistoryIndex(context.username(), row.receipt().kind().destructive());
        if (!row.grant().equals(grant)) throw denied();
        return resume(row, context, grant.principalId(), true);
    }

    public Receipt archive(String id) {
        var context = selected.resolveCurrentRepositoryContextForRead();
        var grant = actors.authorizeHistoryIndex(context.username(), true);
        return journal.archive(context, grant.principalId(), id, this::verifyTarget);
    }

    public Receipt projectionStatus(RepositoryContext context, String operationId) {
        return projectionRecord(context, operationId).receipt();
    }

    public Receipt retryProjection(RepositoryContext context, String operationId) {
        var row = projectionRecord(context, operationId);
        return resume(row, context, row.receipt().actor(), true);
    }

    public Receipt archiveProjection(RepositoryContext context, String operationId) {
        var row = projectionRecord(context, operationId);
        var grant = actors.authorizeHistoryIndex(context.username(), true);
        return journal.archive(context, grant.principalId(), row.receipt().operationId(), this::verifyReadableTarget);
    }

    private HistoryIndexJournal.Operation projectionRecord(RepositoryContext context, String operationId) {
        return journal.inspectOperation(context, actors.currentActor(context.username()), operationId, row -> {
            if (!row.receipt().kind().relationProjection()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            verifyReadableTarget(row);
        });
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
                boolean purge = receipt.kind().destructive();
                actors.checkHistoryIndex(row.grant(), row.username(), purge, background);
                var context = row.context();
                if (receipt.kind().relationProjection()) {
                    verifyProjectionTarget(row, background);
                    String operationId = receipt.operationId();
                    var result = derived.apply(context, DerivedProjection.Kind.valueOf(receipt.kind().name()),
                            receipt.sourceCommit(), new DerivedProjection.Source(row.sourceKey(), row.generation()),
                            operationId, () -> actors.checkHistoryIndex(row.grant(), row.username(), purge, background));
                    actors.checkHistoryIndex(row.grant(), row.username(), purge, background);
                    verifyProjectionTarget(row, background);
                    journal.saveProjectionResult(operationId, derived.describe(result));
                    return result.relations();
                }
                verifyTarget(row);
                int result = purge ? projections.purgeCaptured(context, receipt.operationId())
                        : projections.applyCapturedBranch(receipt.branch(), context, receipt.sourceCommit(), receipt.kind() == Kind.REBUILD);
                actors.checkHistoryIndex(row.grant(), row.username(), purge, background);
                verifyTarget(row);
                return result;
            });
        } catch (WorkspaceFetchAuthority.FreshLoginRequired failure) {
            return journal.failed(token, "AWAITING_AUTHORIZATION", "FRESH_LOGIN_REQUIRED");
        } catch (AccessDeniedException | org.springframework.security.core.AuthenticationException failure) {
            return journal.failed(token, "DENIED", "AUTHORIZATION_CHANGED");
        } catch (DerivedProjection.DecisionRequired decision) {
            return journal.failed(token, decision.retryable() ? "RETRYABLE" : "MANUAL_REVIEW", decision.code(),
                    derived.describe(decision.result()));
        } catch (DerivedProjection.HeadChanged changed) {
            return movedHead(token, changed.actual());
        } catch (DerivedProjection.SourceChanged changed) {
            return journal.failed(token, "MANUAL_REVIEW", "PROJECTION_SOURCE_CHANGED");
        } catch (DerivedProjection.SourceUnavailable | TransientDataAccessException failure) {
            return journal.failed(token, "RETRYABLE", "PROJECTION_INTERRUPTED");
        } catch (IllegalStateException failure) {
            var original = NestedExceptionUtils.getMostSpecificCause(failure);
            if (original instanceof BranchHeadConflictException changed) {
                return movedHead(token, changed.getActualHeadCommit());
            }
            if (original instanceof IOException) {
                return journal.failed(token, "RETRYABLE", "GIT_READ_INTERRUPTED");
            }
            if (journal.claimed(token).receipt().kind().relationProjection()) {
                return journal.failed(token, "RETRYABLE", "PROJECTION_INTERRUPTED");
            }
            throw failure;
        }
    }

    private Receipt movedHead(HistoryIndexJournal.Claim token, String actual) {
        var row = journal.claimed(token);
        try {
            if (actual != null && repositories.resolveRepository(row.context()).isAncestor(row.receipt().sourceCommit(), actual)) {
                return journal.failed(token, "SUPERSEDED", "GIT_HEAD_ADVANCED");
            }
        } catch (IOException failure) {
            return journal.failed(token, "MANUAL_REVIEW", "GIT_ANCESTRY_UNAVAILABLE");
        }
        return journal.failed(token, "MANUAL_REVIEW", "GIT_HISTORY_CHANGED");
    }

    private void verifyReadableTarget(HistoryIndexJournal.Operation row) {
        if (row.receipt().kind().relationProjection()) {
            actors.checkHistoryIndex(row.grant(), row.username(), row.receipt().kind().destructive(), false);
        }
        verifyTarget(row);
    }

    private void verifyTarget(HistoryIndexJournal.Operation row) {
        if (row.receipt().kind().relationProjection()) {
            verifyProjectionTarget(row, false);
        } else if (!Objects.equals(row.owner(), authorizeTarget(row.context()))) throw denied();
    }

    private void verifyProjectionTarget(HistoryIndexJournal.Operation row, boolean background) {
        if (!Objects.equals(row.owner(), authorizeProjectionTarget(row.context(), row.grant(), background))) throw denied();
    }

    private String authorizeProjectionTarget(RepositoryContext context, WorkspaceFetchAuthority.Grant grant, boolean background) {
        return authorizeTarget(context, true, actors.isCurrentAdministrator(grant, context.username(), background));
    }

    /** Use physical row writes for HSQL MVCC as well as PostgreSQL before testing target policy. */
    private String authorizeTarget(RepositoryContext context) {
        return authorizeTarget(context, false, false);
    }

    private String authorizeTarget(RepositoryContext context, boolean projection, boolean administrator) {
        if (catalogue.lockForMutation(context.repositoryId()) != 1) throw denied();
        // Role updates and revocations mutate these same membership rows. A physical write
        // also holds the fence on HSQL MVCC, where SELECT FOR UPDATE alone is insufficient.
        entities.createQuery("update RepositoryMembership m set m.version=m.version where m.repositoryId=:repository and m.username=:actor")
                .setParameter("repository", context.repositoryId()).setParameter("actor", context.username()).executeUpdate();
        var repo = catalogue.findByRepositoryId(context.repositoryId()).orElseThrow(HistoryIndexMaintenance::denied);
        if (context.workspaceId() == null) {
            if (projection ? !administrator && !membership.canMaintain(repo, context.username())
                    : !membership.canContribute(repo, context.username())) throw denied();
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
