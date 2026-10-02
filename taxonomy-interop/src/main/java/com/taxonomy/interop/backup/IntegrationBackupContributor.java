package com.taxonomy.interop.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import com.taxonomy.model.WorkspaceOverlayScope;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.*;
import javax.sql.DataSource;
import static com.taxonomy.exchange.backup.PortableRows.*;

/** Module-owned typed journal capture. No source mutation, worker claims, target authority or automatic dispatch. */
public final class IntegrationBackupContributor implements BackupDataContributor {
    private static final List<String> DATASETS = List.of("connections", "identities", "checkpoints", "operations", "events",
            "publications", "publish-items", "publish-attempts");
    private static final String PENDING = "e.status not in ('COMPLETED','CANCELLED')";
    private static final String PENDING_PARENT = "exists (select 1 from interop_operation o where o.id=e.operation_id and o.status not in ('COMPLETED','CANCELLED'))";
    private final PortableRows rows;
    private final BackupIntegrationScope.Selector scopes;
    public IntegrationBackupContributor(DataSource database, BackupIntegrationScope.Selector scopes) {
        rows = new PortableRows(database); this.scopes = Objects.requireNonNull(scopes);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("interop"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() {
        var result = new HashSet<String>();
        for (String name : List.of("IntegrationConnectionEntity", "ExternalIdentityMappingEntity", "IntegrationCheckpointEntity",
                "IntegrationOperationEntity", "IntegrationEventEntity", "IntegrationPublicationEntity", "IntegrationPublishItemEntity", "IntegrationPublishAttemptEntity"))
            result.add("com.taxonomy.interop.persistence." + name);
        return Set.copyOf(result);
    }
    @Override public List<String> omissions(BackupProfile profile) {
        if (profile == BackupProfile.SELECTED_VERSION)
            return List.of("interop: present-day unversioned journals are outside the selected Git version; no live integration records captured");
        if (profile.includesHistory())
            return List.of("interop: runtime leases and worker claims are excluded; source identities and dispatch evidence require target mapping and manual review");
        return List.of("interop: completed/cancelled operations, removed mappings, checkpoints, events and attempts require history",
                "interop: original transport input, before/baseline payloads, frozen dispatch requests, receipts and free-text mapping diagnostics require history; saved pending candidates remain",
                "interop: source journal references and fingerprints are provenance, not target keys or projected-payload digests; recompute baselines and review all outgoing work after restore");
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        sink.checkpoint(); var profile = snapshot.authorization().request().profile();
        if (profile == BackupProfile.SELECTED_VERSION) {
            empty(sink, profile); policy(sink, profile, "OUTSIDE_SCOPE"); return;
        }
        try {
            var selection = new IntegrationBackupSelection(snapshot, scopes, rows, sink);
            selection.verify();
            var datasets = new Capture(selection, sink, profile);
            var publications = new IntegrationBackupPublicationChecks(rows, sink::checkpoint);
            boolean history = profile.includesHistory();
            datasets.add("connections", "interop_connection", "id,scope_id,organization_id,display_name,connector_id,profile_version,authority_mode,remote_profile,project_id,external_scope,created_by,created_at,connection_revision,checkpoint_id,common_checkpoint_id,active_operation_id", "1=1",
                    r -> new ConnectionData(id(r, "connection", "id"), selection.scope(r), text(r, "organization_id"), text(r, "display_name"),
                            text(r, "connector_id"), text(r, "profile_version"), AuthorityMode.valueOf(text(r, "authority_mode")), text(r, "remote_profile"),
                            reference(r, "portfolio.project", "project_id"), json(r, "external_scope", ExternalScope.class, true), text(r, "created_by"), date(r, "created_at"),
                            r.getLong("connection_revision"), id(r, "checkpoint", "checkpoint_id"), id(r, "checkpoint", "common_checkpoint_id"), id(r, "operation", "active_operation_id")));
            datasets.add("identities", "interop_identity", "id,scope_id,connection_id,external_id,business_identity,requirement_id,external_version,fingerprint,external_json,internal_json,operation_id,removed",
                    history ? "1=1" : "e.removed=?", history ? List.of() : List.of(false), r -> new IdentityData(id(r, "identity", "id"), selection.scope(r), id(r, "connection", "connection_id"),
                            text(r, "external_id"), text(r, "business_identity"), reference(r, "portfolio.requirement", "requirement_id"), text(r, "external_version"), text(r, "fingerprint"),
                            history ? json(r, "external_json", Artifact.class, false) : null, history ? json(r, "internal_json", Artifact.class, false) : null,
                            id(r, "operation", "operation_id"), r.getBoolean("removed")));
            if (history) datasets.add("checkpoints", "interop_checkpoint", "id,scope_id,connection_id,operation_id,git_commit,external_version,fingerprint,context_json,created_at,kind,baseline_json,publication_completion_json", "1=1", r -> {
                var scope = selection.scope(r); String op = text(r, "operation_id");
                var state = json(r, "context_json", InternalState.class, true); state(state, scope);
                var completion = json(r, "publication_completion_json", PublicationCompletion.class, false); completion(completion, op, scope);
                var baseline = json(r, "baseline_json", CommonBaseline.class, false);
                publications.checkpoint(scope, text(r, "connection_id"), op, text(r, "kind"), baseline, completion);
                return new CheckpointData(id(r, "checkpoint", "id"), scope, id(r, "connection", "connection_id"), id(r, "operation", "operation_id"),
                        text(r, "git_commit"), text(r, "external_version"), text(r, "fingerprint"), state, date(r, "created_at"), text(r, "kind"),
                        baseline, completion);
            }); else datasets.header("checkpoints");
            datasets.add("operations", "interop_operation", "id,scope_id,connection_id,actor,status,direction,fingerprint,review_fingerprint,connection_revision,context_json,document_json,changes_json,review_json,result_json,result_state_json,result_file_json,result_commit,result_revision,failure_code,created_at,updated_at",
                    history ? "1=1" : PENDING, r -> {
                var scope = selection.scope(r); String op = text(r, "id"); var context = json(r, "context_json", IntegrationContext.class, true);
                context(context, scope, text(r, "connection_id"));
                var review = json(r, "review_json", ReviewedChangeSet.class, false); review(review, op);
                var state = json(r, "result_state_json", InternalState.class, false); state(state, scope);
                var status = OperationStatus.valueOf(text(r, "status"));
                String direction = text(r, "direction");
                // publicationPreview duplicates its remote-before snapshot into operation.document_json.
                // Saved publication work is captured from its preview/plan/items; the operation is only provenance here.
                boolean payload = history || !Set.of("PUSH", "SYNCHRONIZE").contains(direction);
                return new OperationData(id(r, "operation", "id"), scope, id(r, "connection", "connection_id"), text(r, "actor"), status,
                        terminal(status) ? "RECORDED_ONLY" : "MANUAL_REVIEW_REQUIRED", direction, text(r, "fingerprint"), text(r, "review_fingerprint"),
                        r.getLong("connection_revision"), context, payload ? IntegrationBackupPayloads.document(json(r, "document_json", ExchangeDocument.class, true), history) : null,
                        payload ? Arrays.stream(json(r, "changes_json", IntegrationChange[].class, true)).map(c -> IntegrationBackupPayloads.change(c, history)).toList() : List.of(), review,
                        payload ? IntegrationBackupPayloads.document(json(r, "result_json", ExchangeDocument.class, false), history) : null, state,
                        history ? json(r, "result_file_json", ExchangeFile.class, false) : null, text(r, "result_commit"), number(r, "result_revision"),
                        text(r, "failure_code"), date(r, "created_at"), date(r, "updated_at"));
            });
            if (history) datasets.add("events", "interop_event", "id,scope_id,connection_id,operation_id,event_type,actor,occurred_at,rationale,failure_code", "1=1",
                    r -> new EventData(id(r, "event", "id"), selection.scope(r), id(r, "connection", "connection_id"), id(r, "operation", "operation_id"), text(r, "event_type"),
                            text(r, "actor"), date(r, "occurred_at"), text(r, "rationale"), text(r, "failure_code")));
            else datasets.header("events");
            publications(selection, sink, profile, datasets, publications);
            datasets.write();
            policy(sink, profile, "CAPTURED");
        } catch (IllegalArgumentException | NullPointerException | DateTimeException failure) {
            throw new IOException("Invalid or unsupported integration backup evidence");
        }
    }

    private void publications(IntegrationBackupSelection selection, ComponentSink sink, BackupProfile profile, Capture datasets,
                              IntegrationBackupPublicationChecks checks) throws IOException {
        boolean history = profile.includesHistory();
        datasets.add("publications", "interop_publication", "id,scope_id,connection_id,schema_version,request_json,config_fingerprint,preview_json,plan_json,review_json,phase,predecessor_id,common_checkpoint_id,local_state_json,bindings_json,completion_json,failure_code,reserved_revision,created_at,updated_at",
                history ? "1=1" : "exists (select 1 from interop_operation o where o.id=e.id and o.status not in ('COMPLETED','CANCELLED'))", r -> {
            if (r.getInt("schema_version") != 1) throw new IOException("Unsupported integration publication schema");
            var scope = selection.scope(r); String op = text(r, "id"), connection = text(r, "connection_id"), common = text(r, "common_checkpoint_id");
            var request = json(r, "request_json", PublicationPreviewRequest.class, true); same(request.operationId(), op); state(request.expected(), scope);
            var preview = json(r, "preview_json", PublicationPreviewEnvelope.class, false);
            if (preview != null) {
                if (!preview.request().equals(request)) throw new IOException("Integration publication request differs");
                context(preview.context(), scope, connection); same(preview.commonCheckpointId(), common);
            }
            var plan = json(r, "plan_json", PublicationPlan.class, false);
            if (plan != null) { same(plan.operationId(), op); context(plan.context(), scope, connection); same(plan.commonCheckpointId(), common); }
            var review = json(r, "review_json", PublicationReview.class, false); if (review != null) review(review.review(), op);
            var state = json(r, "local_state_json", InternalState.class, false); state(state, scope);
            var completion = history ? json(r, "completion_json", PublicationCompletion.class, false) : null; completion(completion, op, scope);
            checks.items(scope, connection, op, plan, completion);
            var bindings = json(r, "bindings_json", StagedBinding[].class, false);
            return new PublicationData(id(r, "publication", "id"), scope, id(r, "connection", "connection_id"), request, text(r, "config_fingerprint"),
                    IntegrationBackupPayloads.preview(preview, history), IntegrationBackupPayloads.plan(plan, history), review,
                    PublicationPhase.valueOf(text(r, "phase")), id(r, "publication", "predecessor_id"), id(r, "checkpoint", "common_checkpoint_id"), state,
                    bindings == null ? null : Arrays.stream(bindings).map(b -> IntegrationBackupPayloads.binding(b, history)).toList(), completion,
                    text(r, "failure_code"), r.getLong("reserved_revision"), date(r, "created_at"), date(r, "updated_at"));
        });
        datasets.add("publish-items", "interop_publish_item", "id,scope_id,connection_id,operation_id,item_key,item_ordinal,idempotency_key,intent_json,request_json,request_fingerprint,state,receipt_json,attempt_count,resubmit_allowed,failure_code",
                history ? "1=1" : PENDING_PARENT, r -> {
            String op = text(r, "operation_id"), key = text(r, "item_key"), idempotency = text(r, "idempotency_key");
            var intent = json(r, "intent_json", PublicationItemIntent.class, true);
            if (!intent.itemId().equals(key) || !intent.idempotencyKey().equals(idempotency)) throw new IOException("Integration publication item differs");
            var request = history ? json(r, "request_json", PublicationItemRequest.class, false) : null;
            if (request != null) { same(request.operationId(), op); if (!request.item().equals(intent)) throw new IOException("Integration dispatch item differs"); }
            var receipt = history ? json(r, "receipt_json", PublicationReceipt.class, false) : null; receipt(receipt, op, key, idempotency);
            return new PublishItemData(id(r, "publish-item", "id"), selection.scope(r), id(r, "connection", "connection_id"), id(r, "publication", "operation_id"),
                    r.getInt("item_ordinal"), intent, request, text(r, "request_fingerprint"), ItemState.valueOf(text(r, "state")), receipt,
                    r.getInt("attempt_count"), r.getBoolean("resubmit_allowed"), text(r, "failure_code"));
        });
        if (history) {
            var queries = selection.queries("e.id,e.scope_id,e.connection_id,e.operation_id,e.item_id,e.kind,e.started_at,e.ended_at,e.outcome,e.receipt_json,i.item_key,i.idempotency_key",
                    "interop_publish_attempt e join interop_publish_item i on i.id=e.item_id", "1=1");
            datasets.add("publish-attempts", queries, r -> {
                sink.checkpoint(); var receipt = json(r, "receipt_json", PublicationReceipt.class, false);
                receipt(receipt, text(r, "operation_id"), text(r, "item_key"), text(r, "idempotency_key"));
                return new PublishAttemptData(id(r, "publish-attempt", "id"), selection.scope(r), id(r, "connection", "connection_id"), id(r, "publication", "operation_id"),
                        id(r, "publish-item", "item_id"), AttemptKind.valueOf(text(r, "kind")), date(r, "started_at"), date(r, "ended_at"), text(r, "outcome"), receipt);
            });
        } else datasets.header("publish-attempts");
    }

    /** Query plans retain no payloads. The caller's capture lease keeps both read passes on one fenced source state. */
    private final class Capture {
        private final IntegrationBackupSelection selection;
        private final ComponentSink sink;
        private final BackupProfile profile;
        private final List<Dataset<?>> datasets = new ArrayList<>();
        Capture(IntegrationBackupSelection selection, ComponentSink sink, BackupProfile profile) {
            this.selection = selection; this.sink = sink; this.profile = profile;
        }
        <T extends Record> void add(String kind, String table, String columns, String predicate, Mapper<T> mapper) throws IOException {
            add(kind, table, columns, predicate, List.of(), mapper);
        }
        <T extends Record> void add(String kind, String table, String columns, String predicate, List<?> parameters, Mapper<T> mapper) throws IOException {
            sink.checkpoint();
            var queries = selection.queries("e." + columns.replace(",", ",e."), table + " e", predicate).stream().map(q -> {
                var values = new ArrayList<Object>(q.parameters()); values.addAll(parameters); return new Query(q.sql(), values);
            }).toList();
            add(kind, queries, mapper);
        }
        <T extends Record> void add(String kind, List<Query> queries, Mapper<T> mapper) {
            datasets.add(new Dataset<>(kind, queries, r -> { sink.checkpoint(); return mapper.read(r); }));
        }
        void header(String kind) { add(kind, List.of(), ignored -> null); }
        void write() throws IOException {
            // Check typed embedded ownership and evidence before even a dataset header reaches the sink.
            for (var dataset : datasets) {
                sink.checkpoint(); dataset.verify(rows);
            }
            for (var dataset : datasets) {
                sink.checkpoint(); dataset.write(rows, sink, profile);
            }
        }
    }
    private record Dataset<T extends Record>(String kind, List<Query> queries, Mapper<T> mapper) {
        void verify(PortableRows rows) throws IOException {
            for (var query : queries) rows.visit(query, mapper, ignored -> { });
        }
        void write(PortableRows rows, ComponentSink sink, BackupProfile profile) throws IOException {
            rows.writeBatches(sink, "interop", kind, profile, queries, mapper);
        }
    }
    private void empty(ComponentSink sink, BackupProfile profile) throws IOException { for (String dataset : DATASETS) header(sink, profile, dataset); }
    private void header(ComponentSink sink, BackupProfile profile, String kind) throws IOException { sink.checkpoint(); rows.write(sink, "interop", kind, profile, null, ignored -> null); }
    private static void policy(ComponentSink sink, BackupProfile profile, String status) throws IOException {
        sink.checkpoint(); PortableRows.document(sink, "data/interop/policy.json", new CapturePolicy(1, profile, status, "MANUAL_REVIEW_REQUIRED", false,
                "SOURCE_EVIDENCE_REQUIRES_TARGET_MAPPING", profile.includesHistory() ? "HISTORY_CAPTURED" : "BASELINES_EXCLUDED_RECOMPUTE"));
    }
    private static <T> T json(ResultSet row, String column, Class<T> type, boolean required) throws SQLException, IOException {
        T result = IntegrationBackupJson.read(text(row, column), type);
        if (required && result == null) throw new IOException("Required integration evidence is missing");
        return result;
    }
    private static SourceRecordId id(ResultSet row, String kind, String column) throws SQLException, IOException {
        var result = reference(row, "interop." + kind, column);
        if (result != null && !Set.of("identity", "publish-item").contains(kind)
                && !UUID.fromString(result.value()).toString().equals(result.value())) throw new IOException("Invalid integration source identity");
        return result;
    }
    private static String date(ResultSet row, String column) throws SQLException, IOException {
        String value = text(row, column); return value == null ? null : Instant.parse(value).toString();
    }
    private static void state(InternalState value, BackupIntegrationScope scope) throws IOException {
        if (value != null && (!scope.repository().repositoryId().equals(value.repositoryId())
                || !WorkspaceOverlayScope.keyFor(scope.repository().workspaceId()).equals(value.workspaceScopeKey()) || !scope.branch().equals(value.branch())))
            throw new IOException("Integration evidence differs from its captured workspace");
    }
    private static void context(IntegrationContext value, BackupIntegrationScope scope, String connection) throws IOException {
        same(value.connectionId(), connection); state(value.internalState(), scope);
    }
    private static void same(UUID actual, String expected) throws IOException {
        if (!Objects.equals(actual == null ? null : actual.toString(), expected)) throw new IOException("Integration evidence has a different source parent");
    }
    private static void review(ReviewedChangeSet review, String operation) throws IOException { if (review != null) same(review.operationId(), operation); }
    private static void receipt(PublicationReceipt receipt, String operation, String item, String idempotency) throws IOException {
        if (receipt == null) return;
        same(receipt.operationId(), operation);
        if (!receipt.itemId().equals(item) || !receipt.idempotencyKey().equals(idempotency)) throw new IOException("Integration receipt has a different source item");
    }
    private static void completion(PublicationCompletion value, String operation, BackupIntegrationScope scope) throws IOException {
        if (value == null) return;
        same(value.operationId(), operation); state(value.localAfter(), scope);
        for (var receipt : value.receipts()) same(receipt.operationId(), operation);
    }
    private static boolean terminal(OperationStatus status) { return status == OperationStatus.COMPLETED || status == OperationStatus.CANCELLED; }

    public record CapturePolicy(int schemaVersion, BackupProfile profile, String status, String targetExecutionPolicy, boolean automaticExecutionEnabled,
                                String sourceIdentityPolicy, String baselinePolicy) { }
    public record ConnectionData(SourceRecordId sourceId, BackupIntegrationScope scope, String sourceOrganizationId, String displayName, String connectorId,
                                 String profileVersion, AuthorityMode sourceAuthority, String sourceRemoteProfile, SourceRecordId project, ExternalScope externalScope,
                                 String sourceCreatedBy, String createdAt, long sourceRevision, SourceRecordId sourceCheckpointId, SourceRecordId sourceCommonCheckpointId,
                                 SourceRecordId sourceActiveOperationId) { }
    public record IdentityData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, String externalId, String businessIdentity,
                               SourceRecordId requirement, String sourceExternalVersion, String sourceFingerprint, Artifact historicalExternal,
                               Artifact historicalInternal, SourceRecordId sourceOperationId, boolean removed) { }
    public record CheckpointData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, SourceRecordId operation,
                                 String sourceGitCommit, String sourceExternalVersion, String sourceFingerprint, InternalState sourceState,
                                 String createdAt, String kind, CommonBaseline baseline, PublicationCompletion completion) { }
    public record OperationData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, String sourceActor, OperationStatus sourceStatus,
                                String targetExecutionPolicy, String direction, String sourceFingerprint, String sourceReviewFingerprint, long sourceConnectionRevision,
                                IntegrationContext sourceContext, Record document, List<Record> changes, ReviewedChangeSet sourceReview, Record resultDocument,
                                InternalState sourceResultState, ExchangeFile historicalResultFile, String sourceResultCommit, Long sourceResultRevision,
                                String failureCode, String createdAt, String updatedAt) { }
    public record EventData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, SourceRecordId operation, String eventType,
                            String sourceActor, String occurredAt, String rationale, String failureCode) { }
    public record PublicationData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, PublicationPreviewRequest sourceRequest,
                                  String sourceConfigFingerprint, Record preview, Record plan, PublicationReview sourceReview, PublicationPhase sourcePhase,
                                  SourceRecordId sourcePredecessorId, SourceRecordId sourceCommonCheckpointId, InternalState sourceLocalState, List<Record> stagedBindings,
                                  PublicationCompletion historicalCompletion, String failureCode, long sourceReservedRevision, String createdAt, String updatedAt) { }
    public record PublishItemData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, SourceRecordId publication, int ordinal,
                                  PublicationItemIntent sourceIntent, PublicationItemRequest historicalRequest, String sourceRequestFingerprint, ItemState sourceState,
                                  PublicationReceipt historicalReceipt, int sourceAttemptCount, boolean sourceResubmitAllowed, String failureCode) { }
    public record PublishAttemptData(SourceRecordId sourceId, BackupIntegrationScope scope, SourceRecordId connection, SourceRecordId publication, SourceRecordId item,
                                     AttemptKind kind, String startedAt, String endedAt, String outcome, PublicationReceipt receipt) { }
}
