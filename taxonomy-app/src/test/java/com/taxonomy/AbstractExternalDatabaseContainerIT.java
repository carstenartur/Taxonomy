package com.taxonomy;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.security.service.VerifiedLegacyOwnerMigration;
import com.taxonomy.security.service.LegacyOwnerMigrationReadiness;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.containers.JdbcDatabaseContainer;

import com.taxonomy.templates.DocumentTemplateWebDavLockManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adds durable execution acceptance to the existing PostgreSQL, Oracle and SQL Server suites. */
abstract class AbstractExternalDatabaseContainerIT extends AbstractDatabaseContainerIT {
    protected abstract JdbcDatabaseContainer<?> getDatabaseContainer();

    @Test
    void currentLocalAdministratorCanPreviewLegacyOwnersOnTheDeployedSchema() {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        String adminId = jdbc.queryForObject("select principal_id from app_user where username=?", String.class, "admin");
        var roles = jdbc.query("""
                select r.name from app_user u join user_roles ur on ur.user_id=u.id
                join app_role r on r.id=ur.role_id where u.principal_id=?
                """, (row, index) -> new SimpleGrantedAuthority(row.getString(1)), adminId);
        assertThat(roles).contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var admin = new PrincipalUserDetails(new PrincipalId(UUID.fromString(adminId)), "admin", "", true, roles);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(admin, null, roles);
        var migration = new VerifiedLegacyOwnerMigration(source, new PrincipalIdentityService(source));

        var preview = migration.preview(authentication, 1);

        assertThat(preview.owners()).hasSizeLessThanOrEqualTo(1);
        assertThat(preview.fingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void unreservedOwnerWrittenAfterMigrationBlocksStableStartupOnTheDeployedSchema() {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        String owner = "late-owner-" + UUID.randomUUID();

        transactions.executeWithoutResult(transaction -> {
            var before = LegacyOwnerMigrationReadiness.inspect(source);
            jdbc.update("insert into context_history_record (username, created_at) values (?, ?)",
                    owner, Timestamp.from(Instant.now()));

            var after = LegacyOwnerMigrationReadiness.inspect(source);
            assertThat(after.observedScopes()).isEqualTo(before.observedScopes() + 1);
            assertThat(after.missingReservations()).isEqualTo(before.missingReservations() + 1);
            assertThatThrownBy(() -> LegacyOwnerMigrationReadiness.requireReady(source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("verified migration").hasMessageNotContaining(owner);
            transaction.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("select count(*) from context_history_record where username=?",
                Integer.class, owner)).isZero();
    }

    private ClusterAnalysisDatabaseContract clusterContract() {
        var database = getDatabaseContainer();
        // Validate the running application's schema; never create/drop tables in its database.
        return ClusterAnalysisDatabaseContract.pooledExisting(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    @Test @Order(21)
    void clusterLargePayloadsAndAllFourTablesSurviveANewPersistenceFactory() {
        try (var contract = clusterContract()) {
            contract.largePayloadsAndAllFourTablesSurviveANewPersistenceFactory();
            contract.durableDiagnosticsSurviveANewPersistenceFactoryWithUnknownUsageAndScopedDetails();
        }
    }
    @Test @Order(22)
    void clusterConcurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce() throws Exception {
        try (var contract = clusterContract()) {
            contract.concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce();
        }
    }
    @Test @Order(23)
    void clusterOwnerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit() {
        try (var contract = clusterContract()) {
            contract.ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit();
            contract.scopedExpiryFencesRedeliveryAndPreservesForeignDatabaseRows();
        }
    }

    @Test @Order(25)
    void durableDocumentAdapterRetainsItsNativeResultAndFencesLateCompletion() throws Exception {
        try (var db = clusterContract()) { db.durableDocumentAdapterRetainsItsNativeResultAndFencesLateCompletion(); }
    }

    @Test @Order(26)
    void workingCopyFetchClaimsAndScopedReceiptsSurviveIndependentApplicationHandles() throws Exception {
        var database = getDatabaseContainer();
        com.taxonomy.workspace.service.WorkspaceFetchDatabaseContractTest.verify(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    @Test @Order(24)
    void webDavLeasesFenceIndependentApplicationHandlesAndRetainExactGitVersions() throws Exception {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        String id = "shared-dav-" + UUID.randomUUID();
        String base = "http://" + getAppContainer().getHost() + ":" + getAppContainer().getMappedPort(8080);
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
        byte[] template;
        try (var input = getClass().getResourceAsStream("/document-templates/decision-rationale-report.dotx")) {
            assertThat(input).isNotNull();
            template = input.readAllBytes();
        }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var created = client.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/document-templates/" + id
                            + "?displayName=Shared%20WebDAV%20Acceptance"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", authorization)
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.template")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(template)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(created.statusCode()).as("Template creation response: %s",
                    new String(created.body(), StandardCharsets.UTF_8)).isEqualTo(201);
            String original = created.headers().firstValue("ETag").orElseThrow();
            URI resource = URI.create(base + "/dav/templates/" + id + ".dotx");
            var locked = client.send(HttpRequest.newBuilder(resource).timeout(Duration.ofSeconds(20))
                    .header("Authorization", authorization).method("LOCK", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(locked.statusCode()).isEqualTo(200);
            String token = locked.headers().firstValue("Lock-Token").orElseThrow();
            try {
                var otherInstance = new DocumentTemplateWebDavLockManager(source);
                var lease = otherInstance.find(id);
                assertThat(lease).isNotNull();
                assertThat("<" + lease.token() + ">").isEqualTo(token);
                assertThat(lease.currentCommit()).isEqualTo(original.replace("\"", ""));
                assertThatThrownBy(() -> otherInstance.acquire(id, "foreign-actor", lease.currentCommit(), Duration.ofMinutes(5), null))
                        .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
                assertThatThrownBy(() -> otherInstance.executeWrite(id, null, lease.owner(), lease.currentCommit(),
                        expected -> { throw new AssertionError("A foreign write must never reach Git"); }))
                        .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
                var saved = client.send(HttpRequest.newBuilder(resource).timeout(Duration.ofSeconds(20))
                        .header("Authorization", authorization).header("If", "(" + token + ")")
                        .header("If-Match", original).PUT(HttpRequest.BodyPublishers.ofByteArray(template)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(saved.statusCode()).isEqualTo(204);
                String current = saved.headers().firstValue("ETag").orElseThrow();
                assertThat(current).isNotEqualTo(original);
                var restartedInstance = new DocumentTemplateWebDavLockManager(source);
                assertThat(restartedInstance.require(id, token, lease.owner()).currentCommit())
                        .isEqualTo(current.replace("\"", ""));
                var stale = client.send(HttpRequest.newBuilder(resource).timeout(Duration.ofSeconds(20))
                        .header("Authorization", authorization).header("If", "(" + token + ")")
                        .header("If-Match", original).PUT(HttpRequest.BodyPublishers.ofByteArray(template)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(stale.statusCode()).isEqualTo(412);
                assertThat(otherInstance.require(id, token, lease.owner()).currentCommit())
                        .isEqualTo(current.replace("\"", ""));
            } finally {
                var unlocked = client.send(HttpRequest.newBuilder(resource).timeout(Duration.ofSeconds(20))
                        .header("Authorization", authorization).header("Lock-Token", token)
                        .method("UNLOCK", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(unlocked.statusCode()).isEqualTo(204);
            }
        }
    }
}
