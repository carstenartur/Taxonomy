package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;

@Service
public class AnalysisContinuationService {
    private final AnalysisContinuationStore store;
    private final TaxonomyService catalogue;
    private final LlmService llm;
    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper;
    public AnalysisContinuationService(AnalysisContinuationStore store, TaxonomyService catalogue,
            LlmService llm, WorkspaceManager workspaces, ObjectMapper mapper) {
        this.store = store; this.catalogue = catalogue; this.llm = llm; this.workspaces = workspaces; this.mapper = mapper;
    }
    public Execution begin(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        authorize(username, scope);
        AnalysisRequest frozen = mapper.readValue(mapper.writeValueAsString(request), AnalysisRequest.class);
        frozen.setMaxArchitectureNodes(maxNodes);
        String signature = AnalysisCheckpointSession.digest("resumable-scoring-v1", frozen.getBusinessText(),
                Boolean.toString(frozen.isIncludeArchitectureView()), Integer.toString(maxNodes),
                llm.recoveryPolicyFingerprint(frozen.getProvider()), mapper.writeValueAsString(catalogue.getFullTree()));
        return new Execution(store.begin(frozen, scope, signature));
    }
    public AnalysisContinuationStore.Snapshot read(String id, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.read(id, scope);
    }
    public AnalysisContinuationStore.Snapshot cancel(String id, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.cancel(id, scope);
    }
    public LlmCallDetail detail(String id, String key, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.detail(id, key, scope);
    }
    private void authorize(String username, WorkspaceContext scope) {
        if (scope == null || !Objects.equals(username, scope.username()) || scope.workspaceId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An owned workspace is required");
        var workspace = workspaces.getWorkspaceById(scope.workspaceId());
        if (workspace == null || workspace.isArchived() || workspace.isShared()
                || !Objects.equals(username, workspace.getUsername()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis workspace not found");
    }
    public final class Execution implements AutoCloseable {
        private final AnalysisContinuationStore.Claim claim;
        private final AnalysisCheckpointSession session;
        private boolean completed;
        Execution(AnalysisContinuationStore.Claim claim) {
            this.claim = claim;
            session = claim.completedResult() != null ? null : new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
                public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                    return store.prepare(claim, q);
                }
                public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                    store.finish(claim, q, state, detail);
                    if ("SUCCESS".equals(state) && "PAUSED".equals(store.state(claim)))
                        throw new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION);
                }
                public void checkActive() {
                    String state = store.state(claim);
                    if ("CANCELLED".equals(state)) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                    if ("PAUSED".equals(state)) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION);
                }
            });
        }
        public AnalysisResult replay() { return claim.completedResult(); }
        public AnalysisResult complete(AnalysisResult result) {
            AnalysisResult completedResult = store.complete(claim, result, catalogue.getFullTree());
            completed = true; return completedResult;
        }
        @Override public void close() {
            if (session != null) session.close();
            if (!completed && claim.completedResult() == null) store.interrupted(claim);
        }
    }
}
