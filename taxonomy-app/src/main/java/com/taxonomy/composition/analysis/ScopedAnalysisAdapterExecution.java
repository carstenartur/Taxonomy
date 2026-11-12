package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.operation.AnalysisAdapterStore;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.persistence.EntityManager;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Authenticated repository and principal binding for synchronous provider-backed public routes. */
@Service
@org.springframework.scheduling.annotation.EnableScheduling
@ConditionalOnFeature({"analysis"})
public class ScopedAnalysisAdapterExecution {
    private final WorkspaceResolver workspaces;
    private final WorkspaceDslReadPort repositories;
    private final PrincipalIdentityService identities;
    private final AnalysisAdapterStore store;
    private final ObjectMapper json;
    private org.springframework.beans.factory.ObjectProvider<com.taxonomy.analysis.operation.AnalysisChildSourceAuthority> childSources;

    @org.springframework.beans.factory.annotation.Autowired
    void childSources(org.springframework.beans.factory.ObjectProvider<com.taxonomy.analysis.operation.AnalysisChildSourceAuthority> authorities) {
        this.childSources = authorities;
    }

    private boolean retainedSourceCurrent(AnalysisAdapterStore.Operation operation) {
        if (operation.resultJson() == null || operation.kind().equals("DOCUMENT_EXTRACTION") || operation.kind().equals("REGULATION_MAPPING"))
            return true;
        var authority = childSources == null ? null : childSources.getIfAvailable();
        return authority != null && authority.current(operation);
    }

    public ScopedAnalysisAdapterExecution(WorkspaceResolver workspaces, WorkspaceDslReadPort repositories,
            PrincipalIdentityService identities, EntityManager em, PlatformTransactionManager transactions, ObjectMapper json) {
        this.workspaces = workspaces; this.repositories = repositories; this.identities = identities;
        this.store = new AnalysisAdapterStore(em, transactions); this.json = json;
    }

    public ResponseEntity<?> execute(String kind, String fingerprint, boolean available, Supplier<?> work, Supplier<?> local) {
        return execute(kind, fingerprint, available, work, local, () -> true);
    }
    public ResponseEntity<?> execute(String kind, String fingerprint, boolean available, Supplier<?> work, Supplier<?> local, BooleanSupplier sourceCurrent) {
        return executeWithReceipt(kind, fingerprint, available, id -> work.get(), local, sourceCurrent);
    }
    public ResponseEntity<?> executeWithReceipt(String kind, String fingerprint, boolean available,
            Function<String, ?> work, Supplier<?> local, BooleanSupplier sourceCurrent) {
        var context = workspaces.resolveCurrentRepositoryContextForRead();
        var owner = scope(context);
        var handle = repositories.openRead(context);
        String commit = head(handle);
        if (!sourceCurrent.getAsBoolean()) return stale();
        var attributes = RequestContextHolder.getRequestAttributes();
        String key = attributes instanceof ServletRequestAttributes request ? request.getRequest().getHeader("Idempotency-Key") : null;
        AnalysisAdapterStore.Admission admission;
        try {
            admission = store.admit(owner, kind, commit, fingerprint, key, available);
        } catch (ResponseStatusException conflict) {
            if (!HttpStatus.CONFLICT.equals(conflict.getStatusCode())) throw conflict;
            return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(Map.of("code","COMMAND_CONFLICT"));
        }
        if (admission == null) return ResponseEntity.ok(local.get());
        var accepted = admission.operation();
        BooleanSupplier current = () -> commit.equals(head(handle)) && sourceCurrent.getAsBoolean();
        if (!admission.claimed()) return current.getAsBoolean() && retainedSourceCurrent(accepted) ? response(accepted) : stale();
        if (!verifySource(current, accepted, "SOURCE_UNAVAILABLE"))
            return response(store.settle(owner, accepted.id(), accepted.revision(), null, "STALE_SOURCE"));
        String payload;
        try {
            payload = json.writeValueAsString(work.apply(accepted.id()));
        } catch (RuntimeException failed) {
            return response(store.settle(owner, accepted.id(), accepted.revision(), null, "ANALYSIS_FAILED"));
        }
        if (!verifySource(current, accepted, "OUTCOME_UNKNOWN"))
            return response(store.settle(owner, accepted.id(), accepted.revision(), null, "STALE_SOURCE"));
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > com.taxonomy.exchange.backup.PortableRows.MAX_RECORD_BYTES / 4)
            return response(store.settle(owner, accepted.id(), accepted.revision(), null, "RESULT_TOO_LARGE"));
        return response(store.settle(owner, accepted.id(), accepted.revision(), payload, null));
    }

    private boolean verifySource(BooleanSupplier current, AnalysisAdapterStore.Operation accepted, String failureCode) {
        try { return current.getAsBoolean(); }
        catch (RuntimeException unavailable) {
            store.settle(accepted.scope(), accepted.id(), accepted.revision(), null, failureCode);
            throw unavailable;
        }
    }

    private AnalysisAdapterStore.Scope scope(RepositoryContext context) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var principal = identities.require(auth);
        if (!context.username().equals(auth.getName()))
            throw new org.springframework.security.access.AccessDeniedException("Analysis workspace ownership changed");
        return new AnalysisAdapterStore.Scope(principal.value().toString(),context.repositoryId(),context.workspaceId(),context.branch());
    }

    private static String head(WorkspaceDslReadPort.RepositoryRead handle) {
        try {
            String commit = handle.currentHead();
            if (commit == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis source is unavailable");
            return WorkspaceDslReadPort.normalizeCommitId(commit);
        } catch (IOException unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Analysis source is unavailable"); }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${taxonomy.analysis.retention.sweep-delay:PT1M}",
            initialDelayString = "${taxonomy.analysis.retention.sweep-delay:PT1M}")
    public void sweep() { store.sweep(System.currentTimeMillis()); }

    AnalysisAdapterStore.Operation readOwned(String id) {
        var context = workspaces.resolveCurrentRepositoryContextForRead();
        var operation = store.read(scope(context),id);
        if ("EXPIRED".equals(operation.state())) throw new ResponseStatusException(HttpStatus.GONE, "Analysis operation expired");
        if (!"COMPLETED".equals(operation.state()) || !operation.sourceCommit().equals(head(repositories.openRead(context))) || !retainedSourceCurrent(operation))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis source or operation changed");
        return operation;
    }

    ResponseEntity<?> status(String id, boolean cancel) {
        var context = workspaces.resolveCurrentRepositoryContextForRead();
        var owner = scope(context);
        var operation = cancel ? store.cancel(owner,id) : store.read(owner,id);
        if (!cancel && !"EXPIRED".equals(operation.state()) && (!operation.sourceCommit().equals(head(repositories.openRead(context))) || !retainedSourceCurrent(operation)))
            return stale();
        return response(operation);
    }

    private static ResponseEntity<?> stale() {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("code","STALE_SOURCE"));
    }

    private ResponseEntity<?> response(AnalysisAdapterStore.Operation o) {
        int status = switch (o.state()) {
            case "RUNNING" -> 202;
            case "COMPLETED" -> 200;
            case "EXPIRED" -> 410;
            case "CANCELLED" -> 409;
            case "FAILED" -> "STALE_SOURCE".equals(o.failureCode()) ? 409 : 422;
            default -> throw new IllegalStateException("Unknown durable analysis adapter state");
        };
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Analysis-Operation-Id",o.id()).header("X-Analysis-Source-Commit",o.sourceCommit())
                .header("Content-Location","/api/analysis-adapters/" + o.id());
        builder.header("X-Analysis-Phase",o.state())
                .header("X-Analysis-Sequence",Long.toString(o.revision()))
                .header("X-Analysis-Started-At",Long.toString(o.createdAt()))
                .header("X-Analysis-Updated-At",Long.toString(o.updatedAt()));
        if (status == 202) builder.header("Retry-After","2");
        if (status == 410) return builder.body(Map.of("operationId",o.id(),"code","ANALYSIS_EXPIRED"));
        if ("ANALYSIS_FAILED".equals(o.failureCode())) {
            if ("DOCUMENT_EXTRACTION".equals(o.kind()))
                return builder.body(Map.of("error","AI_EXTRACTION_FAILED","message","AI-assisted extraction could not be completed"));
            if ("REGULATION_MAPPING".equals(o.kind()))
                return builder.body(Map.of("error","REGULATION_MAPPING_FAILED","message","Regulation mapping could not be completed"));
        }
        if (o.resultJson() != null) return builder.body(com.taxonomy.analysis.operation.ChildAnalysisReceipt.publicResult(o.kind(),json.readTree(o.resultJson())));
        return builder.body(Map.of("operationId",o.id(),"state",o.state(),"phase",o.state(),"sequence",o.revision(),
                "startedAt",o.createdAt(),"updatedAt",o.updatedAt(),"sourceCommit",o.sourceCommit(),
                "code",o.failureCode() == null ? o.state() : o.failureCode()));
    }
}
