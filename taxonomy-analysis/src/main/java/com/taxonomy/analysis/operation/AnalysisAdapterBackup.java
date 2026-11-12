package com.taxonomy.analysis.operation;

import com.taxonomy.backup.ComponentSink;
import com.taxonomy.backup.SnapshotContext;
import com.taxonomy.backup.SourceRecordId;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.backup.BackupRowScope;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static com.taxonomy.exchange.backup.PortableRows.text;

/** Portable receipts contain stored outcomes and idempotency hashes. They never grant permission to dispatch. */
public final class AnalysisAdapterBackup {
    private static final String REFERENCE = "analysis.adapter";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FAILURES = Set.of("ANALYSIS_FAILED", "RESULT_TOO_LARGE", "STALE_SOURCE", "OUTCOME_UNKNOWN");
    private AnalysisAdapterBackup() {}

    public record Receipt(SourceRecordId sourceId, AnalysisAdapterStore.Scope scope, String kind, String sourceCommit,
            String requestKey, String fingerprint, String state, String resultJson, String failureCode,
            long createdAt, long updatedAt, long sequence) {}

    public static void write(PortableRows rows, SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var scope = new BackupRowScope(snapshot);
        var selection = scope.repositories("repository_id","workspace_id");
        var parameters = new ArrayList<Object>(selection.parameters());
        String predicate = selection.sql();
        String principal = snapshot.authorization().principalId().value().toString();
        if (!scope.installation()) { predicate += " and actor=?"; parameters.add(principal); }
        var query = scope.selectedVersion() ? null : new PortableRows.Query("select id,actor,repository_id,workspace_id,"
                + "branch_name,kind,source_commit,request_key,fingerprint,state,result_json,failure_code,created_at,updated_at,row_version "
                + "from analysis_adapter_operation where " + predicate + " order by id", parameters);
        rows.write(sink,"analysis","adapter-operations",snapshot.authorization().request().profile(),query, r -> {
            var owner = new AnalysisAdapterStore.Scope(text(r,"actor"),text(r,"repository_id"),text(r,"workspace_id"),text(r,"branch_name"));
            if (!scope.includesWorkspace(owner.repository(),owner.workspace()) || (!scope.installation() && !principal.equals(owner.actor())))
                throw new IOException("Analysis receipt is outside the selected scope");
            var receipt = new Receipt(PortableRows.reference(r,REFERENCE,"id"),owner,text(r,"kind"),text(r,"source_commit"),
                    text(r,"request_key"),text(r,"fingerprint"),text(r,"state"),text(r,"result_json"),text(r,"failure_code"),
                    r.getLong("created_at"),r.getLong("updated_at"),r.getLong("row_version"));
            try { validate(receipt); } catch (RuntimeException invalid) { throw new IOException("Invalid stored analysis receipt", invalid); }
            return receipt;
        });
    }

    /** Import only into caller-authorized isolated storage under an explicitly remapped identity. */
    public static void restore(EntityManager em, PlatformTransactionManager tx, Receipt receipt, String targetId, AnalysisAdapterStore.Scope target) {
        validate(receipt);
        if (!canonicalId(targetId) || target == null) throw invalid();
        new TransactionTemplate(tx).executeWithoutResult(status -> {
            var row = new AnalysisAdapterOperation();
            row.id = targetId;
            row.actor = target.actor(); row.repository = target.repository(); row.workspace = target.workspace(); row.branch = target.branch();
            row.kind = receipt.kind(); row.sourceCommit = receipt.sourceCommit(); row.requestKey = receipt.requestKey();
            row.commandKey = AnalysisAdapterStore.command(target, row.kind, row.requestKey);
            row.fingerprint = receipt.fingerprint();
            boolean interrupted = "RUNNING".equals(receipt.state());
            row.state = interrupted ? "FAILED" : receipt.state(); row.failureCode = interrupted ? "OUTCOME_UNKNOWN" : receipt.failureCode();
            row.resultJson = receipt.resultJson(); row.createdAt = receipt.createdAt(); row.updatedAt = receipt.updatedAt();
            em.persist(row); em.flush();
            long revision = Math.addExact(receipt.sequence(), interrupted ? 1L : 0L);
            em.createQuery("update AnalysisAdapterOperation o set o.revision=:revision where o.id=:id")
                    .setParameter("revision", revision).setParameter("id", targetId).executeUpdate();
            em.refresh(row);
        });
    }

    private static void validate(Receipt r) {
        if (r == null || r.scope() == null || r.sourceId() == null || !REFERENCE.equals(r.sourceId().kind()) || !canonicalId(r.sourceId().value())
                || !AnalysisAdapterStore.kinds().contains(r.kind()) || r.sourceCommit() == null || !r.sourceCommit().matches("[a-f0-9]{40}|[a-f0-9]{64}")
                || !AnalysisAdapterStore.hash(r.requestKey()) || !AnalysisAdapterStore.hash(r.fingerprint())
                || r.createdAt() < 0 || r.updatedAt() < r.createdAt() || r.sequence() < 0 || r.sequence() == Long.MAX_VALUE) throw invalid();
        switch (r.state()) {
            case "COMPLETED" -> {
                if (r.failureCode() != null || r.resultJson() == null
                        || r.resultJson().getBytes(StandardCharsets.UTF_8).length > PortableRows.MAX_RECORD_BYTES / 4) throw invalid();
                var result = JSON.readTree(r.resultJson());
                if ("DOCUMENT_EXTRACTION".equals(r.kind()) || "REGULATION_MAPPING".equals(r.kind())) {
                    String list = "DOCUMENT_EXTRACTION".equals(r.kind()) ? "aiCandidates" : "matches";
                    if (!result.isObject() || !result.path(list).isArray()) throw invalid();
                } else ChildAnalysisReceipt.validate(r.kind(),r.sourceId().value(),result);
            }
            case "RUNNING","EXPIRED","CANCELLED" -> { if (r.resultJson() != null || r.failureCode() != null) throw invalid(); }
            case "FAILED" -> { if (r.resultJson() != null || r.failureCode() == null || !FAILURES.contains(r.failureCode())) throw invalid(); }
            default -> throw invalid();
        }
    }
    private static boolean canonicalId(String id) {
        try { return UUID.fromString(id).toString().equals(id); } catch (NullPointerException | IllegalArgumentException invalid) { return false; }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid portable analysis receipt"); }
}
