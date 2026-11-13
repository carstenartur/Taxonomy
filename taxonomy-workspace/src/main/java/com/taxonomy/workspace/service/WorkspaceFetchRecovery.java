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
    private final CentralForkUpstreamService forks;

    public WorkspaceFetchRecovery(WorkspaceFetchJournal journal, WorkspaceUpstreamService upstream, WorkspaceFetchAuthority actors, CentralForkUpstreamService forks) {
        this.journal = journal;
        this.upstream = upstream;
        this.actors = actors;
        this.forks = forks;
    }

    private CentralForkInitialTransfer initialTransfers;

    @org.springframework.beans.factory.annotation.Autowired
    void useInitialTransfers(CentralForkInitialTransfer transfers) { this.initialTransfers = transfers; }

    public record Receipt(String operationId, WorkspaceUpstreamService.Status result, WorkspaceUpstreamService.TagStatus tag) {
        public Object response() { return tag == null ? result : tag; }
    }

    private WorkspaceUpstreamService.Capture capture(RepositoryContext context) throws IOException {
        return context.scope() == RepositoryScope.CENTRAL_WRITE ? forks.capture(context) : upstream.capture(context);
    }

    private void inspect(RepositoryContext context) throws IOException {
        if (context.scope() == RepositoryScope.CENTRAL_WRITE) forks.inspect(context.repositoryId(), context.username());
        else upstream.inspect(context);
    }

    public Receipt fetch(RepositoryContext context, WorkspaceUpstreamService.Request request,
                         String key, String causation) throws IOException {
        var grant = actors.authorize(context.username(), context.scope());
        var selection = capture(context);
        var state = journal.admit(context, grant, selection, request, key, causation);
        return complete(context, grant.principalId(), state, true);
    }

    public Receipt fetchTag(RepositoryContext context, WorkspaceUpstreamService.TagRequest tag,
                            String key, String cause) throws IOException {
        var grant = actors.authorize(context.username(), context.scope());
        var selection = capture(context);
        var state = journal.admitTag(context, grant, selection, tag, key, cause);
        return complete(context, grant.principalId(), state, true);
    }

    public WorkspaceFetchJournal.View status(RepositoryContext context, String id) throws IOException {
        String principal = actors.currentActor(context.username());
        inspect(context);
        return journal.status(context, principal, id);
    }

    public Receipt retry(RepositoryContext context, String id) throws IOException {
        String principal = actors.authorize(context.username(), context.scope()).principalId();
        inspect(context);
        return complete(context, principal, journal.status(context, principal, id), false);
    }

    public WorkspaceFetchJournal.View archive(RepositoryContext context, String id) throws IOException {
        String principal = actors.authorize(context.username(), context.scope()).principalId();
        inspect(context);
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
                if ("RETRYABLE".equals(recorded.phase())) return new Receipt(recorded.operationId(), null, null);
                if (!"SUPERSEDED".equals(recorded.phase())) throw failure;
                state = recorded;
            }
        }
        if ("ARCHIVED".equals(state.phase())) throw new ResponseStatusException(HttpStatus.GONE, "FETCH_ARCHIVED");
        if ("DENIED".equals(state.phase())) throw new AccessDeniedException("Fetch authority changed");
        if ("RUNNING".equals(state.phase()) || "ADMITTED".equals(state.phase()) || "RETRYABLE".equals(state.phase())
                || "AWAITING_AUTHORIZATION".equals(state.phase())) {
            return new Receipt(state.operationId(), null, null);
        }
        if (!"COMPLETED".equals(state.phase())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fetch requires an explicit decision");
        if (state.selectedTag() != null) {
            return new Receipt(state.operationId(), null, new WorkspaceUpstreamService.TagStatus(state.selectedTag(),
                    state.requestedTagObject(), state.resultTagObject(), state.resultCommit()));
        }
        return new Receipt(state.operationId(), new WorkspaceUpstreamService.Status(state.sourceRepositoryId(), state.sourceBranch(),
                state.observedSourceCommit(), state.trackingRef(), state.resultCommit()), null);
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

    private WorkspaceUpstreamService.Status apply(RepositoryFetchOperation operation, Runnable checkLease) throws IOException {
        var original = new WorkspaceFetchAuthority.Grant(operation.actor, operation.loginKind, operation.bindingKey);
        boolean unattended = "system:workspace-fetch-recovery".equals(operation.executor);
        Runnable authorize = () -> {
            actors.checkPublication(original, operation.username, operation.context().scope(), unattended);
            checkLease.run();
        };
        authorize.run();
        if (operation instanceof CentralFetchOperation central && CentralFetchOperation.initialIdFormat(operation.id)) {
            if (!central.isInitialFork()) throw new AccessDeniedException("Invalid initial fork operation");
            return initialTransfers.apply(central, authorize);
        }
        boolean recovering = operation.attempts > 1;
        var context = operation.context();
        var fork = operation instanceof CentralFetchOperation ? forks.recorded(operation) : null;
        if (operation.selectedTag != null) {
            var request = new WorkspaceUpstreamService.TagRequest(operation.selectedTag, operation.requestedTagObject, operation.expected);
            var tag = fork == null
                    ? upstream.fetchRecordedTag(context, operation.sourceBranch, operation.forkPoint, request, recovering, authorize)
                    : fork.fetchTag(request, recovering, authorize);
            operation.resultTagObject = tag.destinationTagObject();
            var tracking = new GitUpstreamRef(operation.sourceRepositoryId(), operation.sourceBranch);
            return new WorkspaceUpstreamService.Status(tracking.repositoryId(), tracking.branch(), operation.observed,
                    tracking.name(), tag.trackedCommit());
        }
        var request = new WorkspaceUpstreamService.Request(operation.requested, operation.expected);
        if (fork != null) return recovering ? fork.recover(request, authorize) : fork.fetch(request, authorize);
        return upstream.fetchRecorded(context, operation.sourceBranch, operation.forkPoint, request, recovering, authorize);
    }
}
