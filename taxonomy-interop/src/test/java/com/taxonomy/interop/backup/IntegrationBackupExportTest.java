package com.taxonomy.interop.backup;

import com.taxonomy.backup.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import com.taxonomy.interop.publication.PublicationDigests;
import com.taxonomy.interop.publication.PublicationLocalAuthority;
import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.persistence.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class IntegrationBackupExportTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00.123456789Z");
    private static final RepositoryContext OWN = RepositoryContext.workspace("repo-a", "private-a", "draft", "alice");
    private static final RepositoryContext SIBLING = RepositoryContext.workspace("repo-a", "private-b", "draft", "bob");
    private static final RepositoryContext FOREIGN = RepositoryContext.workspace("repo-b", "private-c", "draft", "carol");
    private static final Class<?>[] ENTITIES = { IntegrationConnectionEntity.class, ExternalIdentityMappingEntity.class,
            IntegrationCheckpointEntity.class, IntegrationOperationEntity.class, IntegrationEventEntity.class,
            IntegrationPublicationEntity.class, IntegrationPublishItemEntity.class, IntegrationPublishAttemptEntity.class };
    private static final List<String> TABLES = List.of("interop_connection", "interop_identity", "interop_checkpoint", "interop_operation",
            "interop_event", "interop_publication", "interop_publish_item", "interop_publish_attempt");

    @Test void currentRetainsAllUnreservedPreviewsAndLiveMappingsWithoutHistoricalOrForeignPayload() throws Exception {
        try (var fixture = new Fixture()) {
            UUID connection = fixture.connection(OWN, "OWN-CONNECTION");
            fixture.history(OWN, connection);
            fixture.preview(OWN, connection, "SAVED-FIRST"); fixture.preview(OWN, connection, "SAVED-SECOND");
            fixture.preview(SIBLING, fixture.connection(SIBLING, "PRIVATE-SIBLING"), "SIBLING-SECRET");
            fixture.preview(FOREIGN, fixture.connection(FOREIGN, "OTHER-REPOSITORY"), "FOREIGN-SECRET");
            assertThat(fixture.store.read(OWN, connection).activeOperationId()).isNull();
            var before = fixture.image(); var out = new Contents();
            contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), out);
            assertThat(out.text()).contains("SAVED-FIRST", "SAVED-SECOND", "OWN-CONNECTION", "live-business", "portfolio.requirement", "MANUAL_REVIEW_REQUIRED")
                    .doesNotContain("SECRET", "removed-business", "PRIVATE-SIBLING", "OTHER-REPOSITORY", "\"before\"", "\"rowVersion\"");
            assertThat(out.rows("operations")).hasSize(2); assertThat(out.rows("identities")).hasSize(1);
            assertThat(out.rows("events")).isEmpty(); assertThat(out.rows("checkpoints")).isEmpty();
            assertThat(out.entries).hasSize(9); assertThat(fixture.image()).isEqualTo(before);
        }
    }

    @Test void historyRetainsAuthorizedEvidenceAndRemovedMappings() throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); fixture.history(OWN, connection);
            fixture.preview(OWN, connection, "CURRENT-PREVIEW");
            fixture.history(SIBLING, fixture.connection(SIBLING, "PRIVATE-SIBLING"));
            var out = new Contents(); contributor(fixture, OWN).write(snapshot(BackupProfile.REPOSITORY_HISTORY, OWN), out);
            assertThat(out.text()).contains("COMPLETED-SECRET", "SOURCE-SECRET", "BEFORE-SECRET", "BASELINE-SECRET", "removed-business", "COMPLETED")
                    .doesNotContain("PRIVATE-SIBLING", "private-b");
            assertThat(out.rows("checkpoints")).hasSize(1); assertThat(out.rows("events")).hasSize(3);
        }
    }

    @Test void installationRequiresProofForEveryStoredConnectionAndThenCapturesBothScopes() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.preview(OWN, fixture.connection(OWN, "Own"), "OWN-DRAFT");
            fixture.preview(SIBLING, fixture.connection(SIBLING, "Sibling"), "SIBLING-DRAFT");
            var snapshot = snapshot(BackupProfile.INSTALLATION_CURRENT, OWN, SIBLING);
            var incomplete = new Contents();
            assertThatThrownBy(() -> contributor(fixture, OWN).write(snapshot, incomplete)).isInstanceOf(IOException.class);
            assertThat(incomplete.entries).isEmpty();
            var out = new Contents(); contributor(fixture, OWN, SIBLING).write(snapshot, out);
            assertThat(out.text()).contains("OWN-DRAFT", "SIBLING-DRAFT").doesNotContain("SECRET");
        }
    }

    @ParameterizedTest @ValueSource(strings = { "interop_identity", "interop_checkpoint", "interop_event" })
    void rejectsCrossConnectionOperationEdgesBeforeAnyOutput(String table) throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); fixture.history(OWN, connection);
            var other = fixture.connection(OWN, "Same workspace, different connection");
            UUID otherOperation = fixture.preview(OWN, other, "SAME-BUSINESS-ID");
            fixture.jdbc.update("update " + table + " set operation_id=? where connection_id=?", otherOperation.toString(), connection.toString());
            var out = new Contents();
            assertThatThrownBy(() -> contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), out)).isInstanceOf(IOException.class);
            assertThat(out.entries).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = { "checkpoint_id", "common_checkpoint_id", "active_operation_id" })
    void rejectsConnectionPointersOutsideItsTenantBeforeAnyOutput(String pointer) throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); fixture.history(OWN, connection);
            var foreign = fixture.connection(SIBLING, "PRIVATE"); var foreignOperation = fixture.history(SIBLING, foreign);
            fixture.jdbc.update("update interop_connection set " + pointer + "=? where id=?", foreignOperation.toString(), connection.toString());
            var out = new Contents();
            assertThatThrownBy(() -> contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), out)).isInstanceOf(IOException.class);
            assertThat(out.entries).isEmpty();
        }
    }

    @Test void rejectsScopeHashTamperingAndOrphansWithoutGuessingOwnership() throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); var operation = fixture.preview(OWN, connection, "Own");
            fixture.jdbc.update("update interop_operation set scope_id=? where id=?", scope(SIBLING).scopeId(), operation.toString());
            var out = new Contents();
            assertThatThrownBy(() -> contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), out)).isInstanceOf(IOException.class);
            assertThat(out.entries).isEmpty();
            fixture.jdbc.update("update interop_operation set scope_id=?,connection_id=? where id=?", scope(OWN).scopeId(), UUID.randomUUID().toString(), operation.toString());
            assertThatThrownBy(() -> contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), out)).isInstanceOf(IOException.class);
            assertThat(out.entries).isEmpty();
        }
    }

    @Test void embeddedContextUsesTheWorkspaceOverlayKeyAndCannotRetargetAnotherBranch() throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); var operation = fixture.preview(OWN, connection, "Saved");
            var wrongState = new InternalState("repo-a", scope(OWN).scopeId(), "draft", null, 0, null, null);
            fixture.jdbc.update("update interop_operation set context_json=? where id=?", fixture.json.write(context(connection, wrongState)), operation.toString());
            fixture.assertRejected(BackupProfile.CURRENT_STATE);
            wrongState = new InternalState("repo-a", "private-a", "other-branch", null, 0, null, null);
            fixture.jdbc.update("update interop_operation set context_json=? where id=?", fixture.json.write(context(connection, wrongState)), operation.toString());
            fixture.assertRejected(BackupProfile.CURRENT_STATE);
        }
    }

    @Test void selectedVersionAndEmptyScopeDoNotReadLiveData() throws Exception {
        var selected = new IntegrationBackupContributor(noReads(), (ignored, checkpoint) -> { throw new AssertionError("No live selection"); });
        var out = new Contents(); selected.write(snapshot(BackupProfile.SELECTED_VERSION, OWN), out);
        assertThat(out.text()).contains("OUTSIDE_SCOPE"); assertThat(out.entries).hasSize(9);
        assertThat(selected.omissions(BackupProfile.SELECTED_VERSION)).isNotEmpty();
        out = new Contents(); new IntegrationBackupContributor(noReads(), (ignored, checkpoint) -> List.of())
                .write(snapshot(BackupProfile.CURRENT_STATE, OWN), out);
        assertThat(out.entries).hasSize(9);
    }

    @Test void rejectsForeignAndDuplicateSelectionBeforeDatabaseAccessAndHonorsCancellation() throws Exception {
        for (var selected : List.of(List.of(scope(SIBLING)), List.of(scope(OWN), scope(OWN)))) {
            var out = new Contents();
            assertThatThrownBy(() -> new IntegrationBackupContributor(noReads(), (ignored, checkpoint) -> selected)
                    .write(snapshot(BackupProfile.CURRENT_STATE, OWN), out)).isInstanceOf(IOException.class);
            assertThat(out.entries).isEmpty();
        }
        try {
            Thread.currentThread().interrupt(); var out = new Contents();
            assertThatThrownBy(() -> new IntegrationBackupContributor(noReads(), (ignored, checkpoint) -> List.of())
                    .write(snapshot(BackupProfile.CURRENT_STATE, OWN), out)).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); assertThat(out.entries).isEmpty();
        } finally { Thread.interrupted(); }
    }

    @Test void declaresEveryOwnedPersistentCategory() {
        var contributor = new IntegrationBackupContributor(noReads(), (ignored, checkpoint) -> List.of());
        assertThat(contributor.componentId()).isEqualTo(new BackupComponentId("interop")); assertThat(contributor.schemaVersion()).isEqualTo(1);
        assertThat(contributor.categories()).containsExactlyInAnyOrder(Arrays.stream(ENTITIES).map(Class::getName).toArray(String[]::new));
    }

    @Test void pendingPublicationKeepsCandidatesButNeverHistoricalDispatchEvidenceOrWorkerClaims() throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); fixture.publication(OWN, connection);
            var before = fixture.image(); var out = new Contents();
            contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), out);
            assertThat(out.text()).contains("PUBLICATION-CANDIDATE", "PUBLISH_PENDING", "MANUAL_REVIEW_REQUIRED", "portfolio.requirement")
                    .doesNotContain("SECRET", "LEASE-SENTINEL", "leaseEpoch", "leaseOwner", "leaseUntil", "rowVersion");
            assertThat(out.rows("publications")).hasSize(1); assertThat(out.rows("publish-items")).hasSize(1);
            assertThat(out.rows("publish-attempts")).isEmpty(); assertThat(fixture.image()).isEqualTo(before);
        }
    }

    @Test void fullHistoryKeepsAllEightDatasetsAndTypedReceiptsButNeverLeases() throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); fixture.history(OWN, connection); fixture.publication(OWN, connection);
            var out = new Contents(); contributor(fixture, OWN).write(snapshot(BackupProfile.INSTALLATION_FULL, OWN), out);
            for (var kind : List.of("connections", "identities", "checkpoints", "operations", "events", "publications", "publish-items", "publish-attempts"))
                assertThat(out.rows(kind)).as(kind).isNotEmpty();
            assertThat(out.text()).contains("LOCAL-BEFORE-SECRET", "REMOTE-BEFORE-SECRET", "REMOVED-BINDING-SECRET", NOW.toString(), "ACKNOWLEDGED")
                    .doesNotContain("LEASE-SENTINEL", "leaseEpoch", "leaseOwner", "leaseUntil", "rowVersion");
        }
    }

    @Test void invalidTimestampIsRedactedBeforeAnyOutput() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.preview(OWN, fixture.connection(OWN, "Own"), "Saved");
            fixture.jdbc.update("update interop_operation set created_at='TIMESTAMP-SECRET'");
            fixture.assertRejected(BackupProfile.CURRENT_STATE);
        }
    }

    @Test void initializedPublicationAndCancelledWorkRetainTheirDistinctProfileSemantics() throws Exception {
        try (var fixture = new Fixture()) {
            UUID connection = fixture.connection(OWN, "Own"), op = UUID.randomUUID();
            var request = new PublicationPreviewRequest(op, state(OWN), PublicationMode.PUSH,
                    new PublicationScope(new ExternalScope("contract", "remote", null), "root", "selector"), "scope-1");
            fixture.store.locked(OWN, connection, s -> s.publications().initialize(request, context(connection, state(OWN)), null));
            var current = new Contents(); contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), current);
            assertThat(current.rows("operations")).singleElement().asString().contains("FETCH_PENDING");
            assertThat(current.rows("publications")).hasSize(1); assertThat(current.rows("publish-items")).isEmpty();
            fixture.jdbc.update("update interop_operation set status='CANCELLED'");
            fixture.jdbc.update("update interop_publication set phase='CANCELLED'");
            current = new Contents(); contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), current);
            assertThat(current.rows("operations")).isEmpty(); assertThat(current.rows("publications")).isEmpty();
            var history = new Contents(); contributor(fixture, OWN).write(snapshot(BackupProfile.REPOSITORY_HISTORY, OWN), history);
            assertThat(history.rows("operations")).singleElement().asString().contains("CANCELLED", "RECORDED_ONLY");
            assertThat(history.rows("publications")).hasSize(1);
        }
    }

    @Test void malformedPayloadInAnotherPrivateWorkspaceIsNeverParsedAndOwnPayloadFailsBeforeOutput() throws Exception {
        try (var fixture = new Fixture()) {
            UUID own = fixture.preview(OWN, fixture.connection(OWN, "Own"), "OWN-CANDIDATE");
            UUID foreign = fixture.preview(SIBLING, fixture.connection(SIBLING, "Private"), "FOREIGN-SECRET");
            fixture.jdbc.update("update interop_operation set document_json='{INVALID-SECRET' where id=?", foreign.toString());
            var out = new Contents(); contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), out);
            assertThat(out.text()).contains("OWN-CANDIDATE").doesNotContain("SECRET");
            fixture.jdbc.update("update interop_operation set document_json='{INVALID-SECRET' where id=?", own.toString());
            fixture.assertRejected(BackupProfile.CURRENT_STATE);
        }
    }

    @Test void completedPublicationKeepsHistoricalCompletionAndCheckpointButNoCurrentPayload() throws Exception {
        try (var fixture = new Fixture()) {
            UUID op = fixture.publication(OWN, fixture.connection(OWN, "Own")); fixture.completePublication(op);
            var before = fixture.image(); var history = new Contents();
            contributor(fixture, OWN).write(snapshot(BackupProfile.REPOSITORY_HISTORY, OWN), history);
            assertThat(history.rows("checkpoints")).hasSize(1);
            assertThat(history.text()).contains("COMMON", "COMPLETION-SECRET", "REMOTE-BEFORE-SECRET");
            var current = new Contents(); contributor(fixture, OWN).write(snapshot(BackupProfile.CURRENT_STATE, OWN), current);
            for (String kind : List.of("operations", "publications", "publish-items", "publish-attempts", "checkpoints", "events"))
                assertThat(current.rows(kind)).as(kind).isEmpty();
            assertThat(current.text()).doesNotContain("SECRET", "PUBLICATION-CANDIDATE");
            assertThat(fixture.image()).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = { "item", "idempotency", "duplicate", "missing", "plan", "checkpoint" })
    void completionMustCloseOverTheOwnedPublicationItems(String corruption) throws Exception {
        try (var fixture = new Fixture()) {
            UUID op = fixture.publication(OWN, fixture.connection(OWN, "Own"));
            var completion = fixture.completePublication(op);
            String value = fixture.json.write(completion);
            value = switch (corruption) {
                case "item" -> value.replace("item-" + op, "orphan-item");
                case "idempotency" -> value.replace("key-" + op, "foreign-idempotency-key");
                case "plan" -> value.replace("plan-fp", "other-plan");
                case "checkpoint" -> value.replace("COMPLETION-SECRET", "DIFFERENT-CHECKPOINT-SECRET");
                default -> fixture.json.write(new PublicationCompletion(1, op, completion.planFingerprint(),
                        corruption.equals("duplicate") ? List.of(completion.receipts().getFirst(), completion.receipts().getFirst()) : List.of(),
                        completion.remoteAfter(), completion.localAfter(), completion.commonSemanticFingerprint()));
            };
            if (!corruption.equals("checkpoint")) fixture.jdbc.update("update interop_publication set completion_json=? where id=?", value, op.toString());
            fixture.jdbc.update("update interop_checkpoint set publication_completion_json=? where operation_id=?", value, op.toString());
            fixture.assertRejected(BackupProfile.REPOSITORY_HISTORY);
        }
    }

    @ParameterizedTest @ValueSource(strings = { "item", "idempotency", "target", "ordinal", "missing", "unplanned" })
    void publicationPlanMustAgreeWithStoredChildItems(String corruption) throws Exception {
        try (var fixture = new Fixture()) {
            UUID op = fixture.publication(OWN, fixture.connection(OWN, "Own"));
            switch (corruption) {
                case "ordinal" -> fixture.jdbc.update("update interop_publish_item set item_ordinal=1");
                case "missing" -> { fixture.jdbc.update("delete from interop_publish_attempt"); fixture.jdbc.update("delete from interop_publish_item"); }
                case "unplanned" -> fixture.jdbc.update("update interop_publication set plan_json=null");
                default -> {
                    String plan = fixture.jdbc.queryForObject("select plan_json from interop_publication where id=?", String.class, op.toString());
                    plan = switch (corruption) {
                        case "item" -> plan.replace("item-" + op, "foreign-item");
                        case "idempotency" -> plan.replace("key-" + op, "foreign-key");
                        default -> plan.replace("PUBLICATION-CANDIDATE", "DIFFERENT-TARGET");
                    };
                    fixture.jdbc.update("update interop_publication set plan_json=? where id=?", plan, op.toString());
                }
            }
            fixture.assertRejected(BackupProfile.CURRENT_STATE);
        }
    }

    @ParameterizedTest @ValueSource(strings = { "missing", "completed", "cancelled", "direction" })
    void publicationAndOperationCannotDisagreeAboutWorkToPreserve(String corruption) throws Exception {
        try (var fixture = new Fixture()) {
            UUID op = fixture.publication(OWN, fixture.connection(OWN, "Own"));
            switch (corruption) {
                case "missing" -> {
                    fixture.jdbc.update("delete from interop_publish_attempt"); fixture.jdbc.update("delete from interop_publish_item");
                    fixture.jdbc.update("delete from interop_publication");
                }
                case "completed" -> fixture.jdbc.update("update interop_operation set status='COMPLETED'");
                case "cancelled" -> fixture.jdbc.update("update interop_publication set phase='CANCELLED'");
                case "direction" -> fixture.jdbc.update("update interop_operation set direction='INBOUND'");
            }
            fixture.assertRejected(BackupProfile.CURRENT_STATE);
        }
    }

    @ParameterizedTest @ValueSource(strings = { "publication", "item", "attempt" })
    void rejectsBrokenPublicationParentChainsBeforeOutput(String edge) throws Exception {
        try (var fixture = new Fixture()) {
            var connection = fixture.connection(OWN, "Own"); var operation = fixture.publication(OWN, connection);
            String missing = UUID.randomUUID().toString();
            switch (edge) {
                case "publication" -> fixture.jdbc.update("update interop_publication set predecessor_id=? where id=?", missing, operation.toString());
                case "item" -> fixture.jdbc.update("update interop_publish_item set operation_id=?", missing);
                case "attempt" -> fixture.jdbc.update("update interop_publish_attempt set item_id=?", "f".repeat(64));
                default -> throw new AssertionError();
            }
            var out = new Contents();
            assertThatThrownBy(() -> contributor(fixture, OWN).write(snapshot(BackupProfile.REPOSITORY_HISTORY, OWN), out)).isInstanceOf(IOException.class);
            assertThat(out.entries).isEmpty();
        }
    }

    private static IntegrationBackupContributor contributor(Fixture fixture, RepositoryContext... scopes) {
        return new IntegrationBackupContributor(fixture.database, (ignored, checkpoint) -> Arrays.stream(scopes).map(IntegrationBackupExportTest::scope).toList());
    }
    private static BackupIntegrationScope scope(RepositoryContext context) { return new BackupIntegrationScope(new BackupRepositoryKey(context.repositoryId(), context.workspaceId()), context.branch()); }
    private static InternalState state(RepositoryContext context) { return new InternalState(context.repositoryId(), context.workspaceId(), context.branch(), null, 1, 7L, "source-project-digest"); }
    private static IntegrationContext context(UUID connection, InternalState state) { return new IntegrationContext(connection, AuthorityMode.BIDIRECTIONAL, new ExternalScope("contract", "remote", null), state, "alice", "contract", "1"); }
    private static Artifact artifact(String text) { return new Artifact("same-id", ArtifactKind.REQUIREMENT, "requirement", "Same business ID", text, Map.of(), Map.of()); }
    private static ExchangeDocument document(String text) { return new ExchangeDocument("contract", "1", "source-version", true, "SOURCE-SECRET", List.of(artifact(text)), List.of(), List.of(), Map.of(), List.of()); }
    private static SnapshotContext snapshot(BackupProfile profile, RepositoryContext... repositories) {
        var keys = Arrays.stream(repositories).map(r -> scope(r).repository()).distinct().toList();
        BackupScope selection = profile.isInstallation() ? new BackupScope.Installation() : new BackupScope.Workspace(keys.getFirst().repositoryId(), keys.getFirst().workspaceId());
        BackupTime time = profile.includesHistory() ? new BackupTime.History() : profile == BackupProfile.SELECTED_VERSION
                ? new BackupTime.SelectedVersion(Map.of(keys.getFirst(), "a".repeat(40))) : new BackupTime.Current();
        var request = new BackupRequest(profile, selection, time, profile.includesHistory() ? GitRepresentation.BUNDLE : GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var auth = new AuthorizedBackupRequest(request, PrincipalId.create(), "interop-fixture", NOW, EnumSet.allOf(BackupCapability.class));
        var states = new HashMap<BackupRepositoryKey, SnapshotContext.RepositoryState>();
        for (var key : keys) states.put(key, new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of()));
        return new SnapshotContext(BackupId.create(), auth, NOW, NOW, 1, states, Map.of(new BackupComponentId("interop"), 1));
    }
    private static DataSource noReads() { return new AbstractDataSource() {
        @Override public Connection getConnection() { throw new AssertionError("Database must not be read"); }
        @Override public Connection getConnection(String user, String password) { return getConnection(); }
    }; }
    private static final class Contents implements ComponentSink {
        final Map<String, String> entries = new LinkedHashMap<>();
        @Override public BackupEntry write(String path, InputStream input) throws IOException {
            var bytes = input.readAllBytes(); assertThat(entries.put(path, new String(bytes, StandardCharsets.UTF_8))).isNull();
            return new BackupEntry(path, bytes.length, "0".repeat(64));
        }
        String text() { return String.join("\n", entries.values()); }
        List<String> rows(String kind) { return entries.get("data/interop/" + kind + ".ndjson").lines().skip(1).toList(); }
    }
    private static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource();
        final org.hibernate.SessionFactory factory;
        final JdbcTemplate jdbc;
        final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
        final IntegrationStore store;
        Fixture() {
            database.setUrl("jdbc:hsqldb:mem:interop-backup-" + UUID.randomUUID()); database.setUser("sa");
            var config = new Configuration().setProperty("hibernate.connection.url", database.getUrl())
                    .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.hbm2ddl.auto", "create-drop");
            for (var type : ENTITIES) config.addAnnotatedClass(type);
            factory = config.buildSessionFactory(); jdbc = new JdbcTemplate(database);
            // This fixture exercises journal persistence, not production authorization. Only its three declared actors/scopes are admitted.
            var authority = new PublicationLocalAuthority(null, null, null, null, null) {
                @Override public void authorize(RepositoryContext context, boolean write, IntegrationStore.Connection connection) {
                    assertThat(context).isIn(OWN, SIBLING, FOREIGN);
                    assertThat(connection.organizationId()).isEqualTo("ORGANIZATION:source");
                    assertThat(connection.projectId()).isEqualTo(7L);
                }
            };
            store = new IntegrationStore(factory, new JpaTransactionManager(factory), json, authority);
        }
        UUID connection(RepositoryContext context, String name) {
            var id = UUID.randomUUID(); store.create(context, id, "ORGANIZATION:source", name, "contract", "1", AuthorityMode.BIDIRECTIONAL,
                    new ExternalScope("contract", "remote", null), 7L, "remote-profile"); return id;
        }
        UUID preview(RepositoryContext context, UUID connection, String text) {
            var id = UUID.randomUUID(); store.locked(context, connection, s -> s.preview(id, context(connection, state(context)), "INBOUND", "source-fingerprint",
                    document(text), List.of(new IntegrationChange("change", "same-id", ChangeKind.UPDATE, Set.of("text"), "local-fp", "remote-fp", artifact("BEFORE-SECRET"), artifact(text), List.of())))); return id;
        }
        UUID history(RepositoryContext context, UUID connection) {
            var id = preview(context, connection, "COMPLETED-SECRET");
            store.locked(context, connection, s -> {
                s.mapping(id, "same-id", "live-business", 42L, "v1", artifact("BASELINE-SECRET"), artifact("BASELINE-SECRET"), false);
                s.mapping(id, "removed-id", "removed-business", null, "v0", artifact("REMOVED-SECRET"), artifact("REMOVED-SECRET"), true);
                s.complete(id, state(context), "v1", "checkpoint-fingerprint", true); return null;
            }); return id;
        }
        UUID publication(RepositoryContext context, UUID connection) {
            var id = UUID.randomUUID(); var authority = context(connection, state(context));
            var scope = new PublicationScope(authority.externalScope(), "root", "selector");
            var provider = new ProviderIdentity("provider", "remote", "configuration");
            var request = new PublicationPreviewRequest(id, state(context), PublicationMode.PUSH, scope, "scope-1");
            store.locked(context, connection, s -> s.publications().initialize(request, authority, null));
            var digests = new PublicationDigests(json); var remoteDocument = document("REMOTE-BEFORE-SECRET");
            var remote = new ScopeSnapshot(1, provider, scope, "scope-1", digests.semantic(remoteDocument), true, remoteDocument,
                    Map.of("same-id", new ResourceState("same-id", true, "resource-1", digests.semantic(remoteDocument.artifacts().getFirst()))));
            var unsigned = new PublicationPreviewEnvelope(1, request, authority, store.read(context, connection).revision(), null, null, document("LOCAL-BEFORE-SECRET"), remote,
                    List.of(new PublicationChange("change", "same-id", artifact("BASE-LOCAL-SECRET"), artifact("BASE-REMOTE-SECRET"),
                            artifact("PUBLICATION-CANDIDATE"), artifact("PUBLICATION-CANDIDATE"), artifact("PUBLICATION-CANDIDATE"), Set.of("text"), Set.of("text"), List.of(), Set.of())), List.of(), "preview-fp");
            var preview = new PublicationPreviewEnvelope(1, request, authority, unsigned.connectionRevision(), null, null,
                    unsigned.localDocument(), remote, unsigned.changes(), unsigned.losses(), digests.previewFingerprint(unsigned));
            // The real journal duplicates the remote-before document into the operation. Capture must not leak it as current content.
            store.locked(context, connection, s -> s.publications().publicationPreview(id, preview));
            assertThat(jdbc.queryForObject("select document_json from interop_operation where id=?", String.class, id.toString())).contains("REMOTE-BEFORE-SECRET");
            var capabilities = new PublicationCapabilities(1, "1", provider, scope, Set.of(), Set.of(MutationKind.CREATE), Set.of(ArtifactKind.REQUIREMENT), "verification", "capability-fp", 10, 1024, 3600);
            var intent = new PublicationItemIntent("item-" + id, "key-" + id, MutationKind.CREATE, "same-id", new ResourceState("same-id", false, null, null), artifact("PUBLICATION-CANDIDATE"), Set.of(), "intent-fp");
            var plan = new PublicationPlan(1, id, authority, PublicationMode.PUSH, null, 1, "request-fp", "review-fp", capabilities,
                    remote, document("LOCAL-BEFORE-SECRET"), document("PUBLICATION-CANDIDATE"), document("PUBLICATION-CANDIDATE"), List.of(intent), List.of(), "plan-fp");
            var review = new PublicationReview(new ReviewedChangeSet(id, "preview-fp", Map.of(), "Reviewed candidates"), Map.of());
            var frozen = new PublicationItemRequest(1, id, "plan-fp", provider, scope, intent, "scope-1", "request-fp");
            var receipt = new PublicationReceipt(1, "1", provider, scope, id, intent.itemId(), intent.idempotencyKey(), "plan-fp", "request-fp",
                    ReceiptState.APPLIED, 1, true, "scope-1", "scope-2", new ResourceState("same-id", true, "resource-2", "result-fp"), null, NOW);
            var bindings = List.of(new StagedBinding("same-id", "pending-business", 42L, artifact("PUBLICATION-CANDIDATE"), artifact("PUBLICATION-CANDIDATE"), false),
                    new StagedBinding("removed-id", "removed-business", null, artifact("REMOVED-BINDING-SECRET"), artifact("REMOVED-BINDING-SECRET"), true));
            jdbc.update("update interop_publication set preview_json=?,plan_json=?,review_json=?,local_state_json=?,bindings_json=?,phase=?,lease_owner=?,lease_until=?,lease_epoch=? where id=?",
                    json.write(preview), json.write(plan), json.write(review), json.write(state(context)), json.write(bindings), "PUBLISH_PENDING", "LEASE-SENTINEL", NOW.toString(), 777L, id.toString());
            jdbc.update("update interop_operation set status='APPLYING' where id=?", id.toString());
            String itemId = com.taxonomy.identity.StableIdentityHash.sha256(id.toString());
            jdbc.update("insert into interop_publish_item(id,scope_id,connection_id,operation_id,item_key,item_ordinal,idempotency_key,intent_json,request_json,request_fingerprint,state,receipt_json,attempt_count,resubmit_allowed,row_version) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    itemId, scope(context).scopeId(), connection.toString(), id.toString(), intent.itemId(), 0, intent.idempotencyKey(), json.write(intent), json.write(frozen), "request-fp", "ACKNOWLEDGED", json.write(receipt), 1, true, 0L);
            jdbc.update("insert into interop_publish_attempt(id,scope_id,connection_id,operation_id,item_id,lease_epoch,kind,started_at,ended_at,outcome,receipt_json) values(?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), scope(context).scopeId(), connection.toString(), id.toString(), itemId, 777L, "SEND", NOW.toString(), NOW.toString(), "APPLIED", json.write(receipt));
            return id;
        }
        PublicationCompletion completePublication(UUID operation) {
            String id = operation.toString();
            var plan = json.read(jdbc.queryForObject("select plan_json from interop_publication where id=?", String.class, id), PublicationPlan.class);
            var receipt = json.read(jdbc.queryForObject("select receipt_json from interop_publish_item where operation_id=?", String.class, id), PublicationReceipt.class);
            var after = new ScopeSnapshot(1, plan.capabilities().provider(), plan.capabilities().scope(), "scope-2", "completion-fp", true,
                    document("COMPLETION-SECRET"), Map.of());
            var completion = new PublicationCompletion(1, operation, plan.planFingerprint(), List.of(receipt), after, state(OWN), "completion-fp");
            var baseline = new CommonBaseline(1, after.provider(), after.scope(), "contract", "1", after.document(), after.document(), "completion-fp");
            String connection = plan.context().connectionId().toString();
            jdbc.update("update interop_publication set phase='COMPLETED',completion_json=? where id=?", json.write(completion), id);
            jdbc.update("update interop_operation set status='COMPLETED' where id=?", id);
            jdbc.update("insert into interop_checkpoint(id,scope_id,connection_id,operation_id,external_version,fingerprint,context_json,created_at,kind,baseline_json,publication_completion_json) values(?,?,?,?,?,?,?,?,?,?,?)",
                    id, scope(OWN).scopeId(), connection, id, "scope-2", "completion-fp", json.write(state(OWN)), NOW.toString(), "COMMON", json.write(baseline), json.write(completion));
            jdbc.update("update interop_connection set common_checkpoint_id=?,active_operation_id=null where id=?", id, connection);
            return completion;
        }
        void assertRejected(BackupProfile profile) {
            var before = image(); var out = new Contents();
            assertThatThrownBy(() -> contributor(this, OWN).write(snapshot(profile, OWN), out))
                    .isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("SECRET");
            assertThat(out.entries).isEmpty(); assertThat(image()).isEqualTo(before);
        }
        Map<String, List<Map<String, Object>>> image() {
            var result = new TreeMap<String, List<Map<String, Object>>>();
            for (var table : TABLES) result.put(table, jdbc.queryForList("select * from " + table + " order by id")); return result;
        }
        @Override public void close() { factory.close(); }
    }
}
