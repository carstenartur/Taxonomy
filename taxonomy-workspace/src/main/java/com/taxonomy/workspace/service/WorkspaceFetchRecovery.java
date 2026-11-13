package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Service
public class WorkspaceFetchRecovery {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceFetchRecovery.class);
    private final WorkspaceFetchJournal journal;
    private final WorkspaceUpstreamService upstream;
    private final WorkspaceFetchAuthority actors;

    public WorkspaceFetchRecovery(WorkspaceFetchJournal journal, WorkspaceUpstreamService upstream, WorkspaceFetchAuthority actors) {
        this.journal = journal;
        this.upstream = upstream;
        this.actors = actors;
    }

    public record Receipt(String operationId, WorkspaceUpstreamService.Status result) { }

    public Receipt fetch(RepositoryContext context, WorkspaceUpstreamService.Request request,
                         String key, String causation) throws IOException {
        var grant = actors.authorize(context.username());
        var selection = upstream.capture(context);
        var state = journal.admit(context, grant, selection, request, key, causation);
        return complete(context, grant.principalId(), state, true);
    }

    public WorkspaceFetchJournal.View status(RepositoryContext context, String id) throws IOException {
        String principal = actors.currentActor(context.username());
        upstream.inspect(context);
        return journal.status(context, principal, id);
    }

    public Receipt retry(RepositoryContext context, String id) throws IOException {
        String principal = actors.authorize(context.username()).principalId();
        upstream.inspect(context);
        return complete(context, principal, journal.status(context, principal, id), false);
    }

    public WorkspaceFetchJournal.View archive(RepositoryContext context, String id) throws IOException {
        String principal = actors.authorize(context.username()).principalId();
        upstream.inspect(context);
        return journal.archive(context, principal, id);
    }

    private Receipt complete(RepositoryContext context, String principal, WorkspaceFetchJournal.View state,
                             boolean respectBackoff) throws IOException {
        // Repeating the fetch observes the saved delay. The explicit retry endpoint can request an early attempt.
        var claim = journal.claim(state.operationId(), principal, respectBackoff);
        if (claim == null) {
            state = journal.status(context, principal, state.operationId());
        } else {
            try {
                state = journal.run(claim, this::apply);
            } catch (IOException | RuntimeException failure) {
                // Return the persisted operation to the caller even when Git succeeded and the database update failed.
                var recorded = journal.status(context, principal, state.operationId());
                if ("RETRYABLE".equals(recorded.phase())) return new Receipt(recorded.operationId(), null);
                if (!"SUPERSEDED".equals(recorded.phase())) throw failure;
                state = recorded;
            }
        }
        if ("ARCHIVED".equals(state.phase())) throw new ResponseStatusException(HttpStatus.GONE, "FETCH_ARCHIVED");
        if ("DENIED".equals(state.phase())) throw new AccessDeniedException("Fetch authority changed");
        if ("RUNNING".equals(state.phase()) || "ADMITTED".equals(state.phase()) || "RETRYABLE".equals(state.phase())
                || "AWAITING_AUTHORIZATION".equals(state.phase())) {
            return new Receipt(state.operationId(), null);
        }
        if (!"COMPLETED".equals(state.phase())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fetch requires an explicit decision");
        var ref = new GitUpstreamRef(state.repositoryId(), state.sourceBranch());
        return new Receipt(state.operationId(), new WorkspaceUpstreamService.Status(state.repositoryId(), state.sourceBranch(),
                state.observedSourceCommit(), ref.name(), state.resultCommit()));
    }

    @Scheduled(fixedDelayString = "${taxonomy.workspace.fetch-recovery-poll:30000}")
    public void recoverDue() {
        journal.expireDue();
        for (String id : journal.due()) {
            var claim = journal.claim(id, "system:workspace-fetch-recovery", true);
            if (claim == null) continue;
            try {
                journal.run(claim, this::apply);
            } catch (IOException | RuntimeException failure) {
                // The journal records a bounded decision code without retaining provider, Git or SQL exception text.
                log.info("Workspace fetch recovery deferred or ended for operation {}", id);
            }
        }
    }

    private WorkspaceUpstreamService.Status apply(WorkspaceFetchOperation operation, Runnable checkLease) throws IOException {
        var original = new WorkspaceFetchAuthority.Grant(operation.actor, operation.loginKind, operation.bindingKey);
        boolean unattended = "system:workspace-fetch-recovery".equals(operation.executor);
        actors.checkPublication(original, operation.username, unattended);
        return upstream.fetchRecorded(operation.context(), operation.sourceBranch, operation.forkPoint,
                new WorkspaceUpstreamService.Request(operation.requested, operation.expected), operation.attempts > 1, () -> {
                    actors.checkPublication(original, operation.username, unattended);
                    checkLease.run();
                });
    }
}
