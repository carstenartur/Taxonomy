package com.taxonomy.analysis.cluster;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

/** Durable observation boundary. Reading or reconnecting never starts analysis work. */
public interface ClusterAnalysisObservation {
    /**
     * Empty means no durable operation exists. A known operation outside the exact
     * authenticated scope throws 404, preventing fallback to a process-local record.
     */
    Optional<ClusterAnalysisView> snapshot(String operationId, String owner, WorkspaceContext scope);

    /** Persist cancellation before publishing it; uses the same absence/404 contract as snapshot. */
    Optional<ClusterAnalysisView> cancel(String operationId, String owner, WorkspaceContext scope);

    /** Bounded, newest-first observations visible in the authenticated scope. */
    List<ClusterAnalysisView> recent(String owner, WorkspaceContext scope, Long projectId, Long requirementId);

    /**
     * Authorize, subscribe and reconcile a durable view newer than afterSequence without a
     * read/subscription gap. Replay may coalesce hints into the latest persisted snapshot;
     * never label a current view with an older event revision. Emit snapshot/progress with its sequence as SSE id.
     * Broker notifications only wake replay; they are never the authority for view data.
     */
    SseEmitter events(String operationId, String owner, WorkspaceContext scope, long afterSequence);

    /** Return the persisted result, 409 before it is ready, or indistinguishable 404 outside scope. */
    AnalysisResult result(String operationId, String owner, WorkspaceContext scope);

    /** Original user input for an explicit recovery action, never provider credentials or internal command state. */
    RecoveryInput request(String operationId, String owner, WorkspaceContext scope);

    record RecoveryInput(String operationId, String businessText, String provider, AnalysisScope analysisScope,
                         ClusterAnalysisView.ObservationScope scope) { }
}
