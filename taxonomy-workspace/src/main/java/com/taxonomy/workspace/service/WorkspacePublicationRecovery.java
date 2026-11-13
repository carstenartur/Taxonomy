package com.taxonomy.workspace.service;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

import static com.taxonomy.workspace.service.WorkspacePublicationOperation.Status.*;

@Service
public class WorkspacePublicationRecovery {
    static final String WORKER = "system:workspace-publication";
    private final WorkspacePublicationJournal journal;
    private final WorkspacePublicationAuthority actors;

    public WorkspacePublicationRecovery(WorkspacePublicationJournal journal,WorkspacePublicationAuthority actors) {
        this.journal=journal;this.actors=actors;
    }

    @FunctionalInterface interface PhaseExecutor {
        void execute(WorkspacePublicationOperation row,Runnable currentGrant) throws IOException;
    }

    WorkspacePublicationJournal.View submit(RepositoryContext context, boolean keepWorkspace, String key, String cause,
            WorkspacePublicationJournal.Snapshot snapshot, PhaseExecutor executor) throws IOException {
        return submit(context, keepWorkspace, key, cause, snapshot, executor, false);
    }

    WorkspacePublicationJournal.View submit(RepositoryContext context,boolean keepWorkspace,String key,String cause,
            WorkspacePublicationJournal.Snapshot snapshot, PhaseExecutor executor, boolean commandErrors) throws IOException {
        var grant=actors.authorizePublication(context.username());
        var row=journal.admit(context,grant,keepWorkspace,key,cause,snapshot);
        return resume(context,grant.principalId(),row,false,executor,commandErrors);
    }

    WorkspacePublicationJournal.View get(RepositoryContext context,String id) {
        return journal.get(context,actors.currentActor(context.username()),id);
    }

    WorkspacePublicationJournal.View archive(RepositoryContext context,String id) {
        return journal.archive(context,actors.authorizePublication(context.username()).principalId(),id);
    }

    WorkspacePublicationJournal.View retry(RepositoryContext context,String id,PhaseExecutor executor) {
        var grant=actors.authorizePublication(context.username());
        return resume(context,grant.principalId(),journal.get(context,grant.principalId(),id),true,executor,false);
    }

    void recoverDue(PhaseExecutor executor) {
        for (String id:journal.due()) {
            var claim=journal.claim(id,WORKER,false);
            if (claim!=null) {
                try { run(claim,executor,false); }
                catch (ResponseStatusException failure) {
                    if (failure.getStatusCode()!=HttpStatus.CONFLICT) throw failure;
                }
            }
        }
    }

    @FunctionalInterface interface PinCleaner {
        void release(WorkspacePublicationOperation row,Runnable grant) throws IOException;
    }

    void cleanDue(PinCleaner cleaner) {
        for (String id : journal.dueForCleanup()) clean(id,WORKER,false,cleaner);
        journal.expireDue();
    }

    void cleanArchived(RepositoryContext selected, String id, PinCleaner cleaner) {
        var actor=actors.authorizePublication(selected.username());
        journal.get(selected,actor.principalId(),id);
        clean(id,actor.principalId(),true,cleaner);
    }

    private void clean(String id,String executor,boolean requested,PinCleaner cleaner) {
        journal.cleanup(id,executor,requested,row -> {
            var original=new WorkspaceFetchAuthority.Grant(row.actor,row.loginKind,row.binding);
            Runnable grant=() -> actors.checkPublicationGrant(original,row.username,!requested);
            grant.run();
            cleaner.release(row,grant);
        });
    }

    private WorkspacePublicationJournal.View resume(RepositoryContext context,String actor,WorkspacePublicationJournal.View row,
            boolean explicit,PhaseExecutor executor,boolean commandErrors) {
        if (row.archivedAt()!=null) throw new ResponseStatusException(HttpStatus.GONE,"Publication archived");
        if (row.completedAt()!=null) return row;
        var claim=journal.claim(row.operationId(),actor,explicit);
        return claim==null ? journal.get(context,actor,row.operationId()) : run(claim,executor,commandErrors);
    }

    private WorkspacePublicationJournal.View run(WorkspacePublicationJournal.Claim claim,PhaseExecutor executor,boolean commandErrors) {
        while (true) {
            try {
                var state=journal.step(claim,(row,checkLease) -> {
                    var original=new WorkspaceFetchAuthority.Grant(row.actor,row.loginKind,row.binding);
                    Runnable authorize=() -> {
                        checkLease.run();
                        actors.checkPublicationGrant(original,row.username,WORKER.equals(claim.executor()));
                    };
                    authorize.run();
                    executor.execute(row,authorize);
                });
                if (state.completedAt()!=null) return state;
            } catch (WorkspaceFetchAuthority.FreshLoginRequired failure) {
                return journal.decide(claim,AWAITING_AUTHORIZATION,"FRESH_LOGIN_REQUIRED");
            } catch (AccessDeniedException failure) {
                return journal.decide(claim,DENIED,"AUTHORIZATION_REVOKED");
            } catch (Superseded failure) {
                return journal.decide(claim,SUPERSEDED,"RETAINED_DESCENDANT",failure.source,failure.sourceTime);
            } catch (SemanticConflict failure) {
                return journal.decide(claim,MANUAL_REVIEW,"SEMANTIC_CONFLICT");
            } catch (com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem failure) {
                var decision = journal.decide(claim, MANUAL_REVIEW, "RECONCILIATION_REQUIRED");
                if (commandErrors) throw failure;
                return decision;
            } catch (BranchHeadConflictException failure) {
                return journal.decide(claim,MANUAL_REVIEW,"AUTHORITY_CHANGED");
            } catch (ResponseStatusException failure) {
                if (!failure.getStatusCode().is5xxServerError()) throw failure;
                return journal.decide(claim,RETRYABLE,"DEPENDENCY_UNAVAILABLE");
            } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException failure) {
                return journal.decide(claim,MANUAL_REVIEW,"RECORDED_GIT_OBJECT_UNAVAILABLE");
            } catch (IOException | TransientDataAccessException failure) {
                return journal.decide(claim,RETRYABLE,"PUBLICATION_INTERRUPTED");
            }
        }
    }

    static final class SemanticConflict extends IOException {
        SemanticConflict(String message) { super(message); }
    }

    static final class Superseded extends IOException {
        final boolean source;
        final java.time.Instant sourceTime;
        Superseded(boolean source) { this(source,null); }
        Superseded(boolean source,java.time.Instant sourceTime) {
            super("A retained branch already descends from the recorded publication");
            this.source=source;
            this.sourceTime=sourceTime;
        }
    }
}
