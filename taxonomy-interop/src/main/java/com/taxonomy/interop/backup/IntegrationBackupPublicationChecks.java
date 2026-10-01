package com.taxonomy.interop.backup;

import com.taxonomy.backup.BackupCheckpoint;
import com.taxonomy.backup.BackupIntegrationScope;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.publication.PublicationEvidence.CommonBaseline;
import java.io.IOException;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.text;
import static com.taxonomy.extension.api.integration.PublicationBounds.MAX_MUTATIONS;

/** Structural source-evidence closure, never proof of provider effects or target authorization. */
final class IntegrationBackupPublicationChecks {
    private final PortableRows rows;
    private final BackupCheckpoint checkpoint;
    IntegrationBackupPublicationChecks(PortableRows rows, BackupCheckpoint checkpoint) {
        this.rows = rows; this.checkpoint = checkpoint;
    }

    void items(BackupIntegrationScope scope, String connection, String operation, PublicationPlan plan,
               PublicationCompletion completion) throws IOException {
        checkpoint.check();
        var receipts = new HashMap<String, PublicationReceipt>();
        if (completion != null) {
            if (plan == null || !plan.planFingerprint().equals(completion.planFingerprint())
                    || !plan.capabilities().provider().equals(completion.remoteAfter().provider())
                    || !plan.capabilities().scope().equals(completion.remoteAfter().scope())) throw invalid();
            for (var receipt : completion.receipts()) {
                checkpoint.check();
                if (!receipt.operationId().toString().equals(operation) || !receipt.planFingerprint().equals(plan.planFingerprint())
                        || receipts.putIfAbsent(receipt.itemId(), receipt) != null) throw invalid();
            }
        }
        var expected = plan == null ? List.<PublicationItemIntent>of() : plan.items();
        var seen = new HashSet<String>(); var keys = new HashSet<String>(); int[] count = { 0 };
        rows.visit(new PortableRows.Query("select item_key,item_ordinal,idempotency_key,intent_json,receipt_json from interop_publish_item"
                + " where scope_id=? and connection_id=? and operation_id=? order by item_ordinal,id",
                List.of(scope.scopeId(), connection, operation)), r -> {
            checkpoint.check();
            if (count[0] >= MAX_MUTATIONS || count[0] >= expected.size()) throw invalid();
            return new Item(text(r, "item_key"), r.getInt("item_ordinal"), text(r, "idempotency_key"),
                    IntegrationBackupJson.read(text(r, "intent_json"), PublicationItemIntent.class),
                    completion == null ? null : IntegrationBackupJson.read(text(r, "receipt_json"), PublicationReceipt.class));
        }, item -> {
            var intent = expected.get(count[0]);
            if (item.ordinal() != count[0]++ || !intent.equals(item.intent()) || !intent.itemId().equals(item.key())
                    || !intent.idempotencyKey().equals(item.idempotency()) || !seen.add(item.key()) || !keys.add(item.idempotency())) throw invalid();
            if (completion != null) {
                var receipt = receipts.remove(item.key());
                if (receipt == null || !receipt.idempotencyKey().equals(item.idempotency()) || !receipt.equals(item.receipt())) throw invalid();
            }
        });
        if (count[0] != expected.size() || !receipts.isEmpty()) throw invalid();
    }

    void checkpoint(BackupIntegrationScope scope, String connection, String operation, String kind,
                    CommonBaseline baseline, PublicationCompletion completion) throws IOException {
        if ("OBSERVATION".equals(kind)) {
            if (baseline != null || completion != null) throw invalid();
            return;
        }
        if (!"COMMON".equals(kind) || baseline == null || completion == null
                || !baseline.provider().equals(completion.remoteAfter().provider())
                || !baseline.scope().equals(completion.remoteAfter().scope())
                || !baseline.semanticFingerprint().equals(completion.commonSemanticFingerprint())) throw invalid();
        checkpoint.check(); int[] count = { 0 };
        rows.visit(new PortableRows.Query("select phase,completion_json from interop_publication where id=? and scope_id=? and connection_id=?",
                List.of(operation, scope.scopeId(), connection)), r -> {
            checkpoint.check();
            return new Completed(text(r, "phase"), IntegrationBackupJson.read(text(r, "completion_json"), PublicationCompletion.class));
        }, parent -> {
            if (++count[0] != 1 || !"COMPLETED".equals(parent.phase()) || !completion.equals(parent.completion())) throw invalid();
        });
        if (count[0] != 1) throw invalid();
    }

    private static IOException invalid() { return new IOException("Integration publication evidence has inconsistent source references"); }
    private record Item(String key, int ordinal, String idempotency, PublicationItemIntent intent, PublicationReceipt receipt) { }
    private record Completed(String phase, PublicationCompletion completion) { }
}
