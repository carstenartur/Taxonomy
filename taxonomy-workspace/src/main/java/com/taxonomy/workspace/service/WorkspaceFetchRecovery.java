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
        var selection = upstream.inspect(context);
        var state = journal.admit(context, grant, selection, request, key, causation);
        return complete(context, grant.principalId(), state);
    }

    public WorkspaceFetchJournal.View status(RepositoryContext context, String id) throws IOException {
        String principal = actors.currentActor(context.username());
        upstream.inspect(context);
        return journal.status(context, principal, id);
    }

    public Receipt retry(RepositoryContext context, String id) throws IOException {
        String principal = actors.authorize(context.username()).principalId();
        upstream.inspect(context);
        return complete(context, principal, journal.status(context, principal, id));
    }

    private Receipt complete(RepositoryContext context, String principal, WorkspaceFetchJournal.View state) throws IOException {
        var claim = journal.claim(state.operationId(), principal, false);
        state = claim == null ? journal.status(context, principal, state.operationId()) : journal.run(claim, this::apply);
        if ("DENIED".equals(state.phase())) throw new AccessDeniedException("Fetch authority changed");
        if (!"COMPLETED".equals(state.phase())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fetch requires review or is already running");
        var ref = new GitUpstreamRef(state.repositoryId(), state.sourceBranch());
        return new Receipt(state.operationId(), new WorkspaceUpstreamService.Status(state.repositoryId(), state.sourceBranch(),
                state.observedSourceCommit(), ref.name(), state.resultCommit()));
    }

    @Scheduled(fixedDelayString = "${taxonomy.workspace.fetch-recovery-poll:30000}")
    public void recoverDue() {
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
        return upstream.fetchRecorded(operation.context(), operation.sourceBranch,
                new WorkspaceUpstreamService.Request(operation.requested, operation.expected), () -> {
                    actors.checkPublication(original, operation.username, unattended);
                    checkLease.run();
                });
    }
}
