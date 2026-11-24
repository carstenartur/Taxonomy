package com.taxonomy;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableJsonPayloads;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.payload.PortfolioAnalysisPayloads;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.PortfolioSnapshotEvidenceReader;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.security.service.VerifiedLegacyOwnerMigration;
import com.taxonomy.security.service.LegacyOwnerMigrationReadiness;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.containers.JdbcDatabaseContainer;

import com.taxonomy.templates.DocumentTemplateWebDavLockManager;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.taxonomy.portfolio.model.PortfolioTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adds durable execution acceptance to the existing PostgreSQL, Oracle and SQL Server suites. */
abstract class AbstractExternalDatabaseContainerIT extends AbstractDatabaseContainerIT {
    protected abstract JdbcDatabaseContainer<?> getDatabaseContainer();

    @Override
    protected String publicationEvidence(String workspace) {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.setMaxRows(25);
        var operations = jdbc.query("""
                select phase, operation_step, attempts, decision_code from workspace_publication_operation
                where workspace_id=? order by created_at, operation_id
                """, (row, index) -> row.getString(1) + "/" + row.getString(2)
                + " attempts=" + row.getInt(3) + " decision=" + row.getString(4), workspace);
        var trail = jdbc.query("""
                select d.phase_code, d.decision_code from workspace_publication_decision d
                join workspace_publication_operation p on p.operation_id=d.operation_id
                where p.workspace_id=? order by d.id
                """, (row, index) -> row.getString(1) + "/" + row.getString(2), workspace);
        var sqlStates = java.util.regex.Pattern.compile("HHH000247: ErrorCode: -?[0-9]+, SQLState: [A-Z0-9]{5}\\b")
                .matcher(getAppContainer().getLogs()).results().map(java.util.regex.MatchResult::group)
                .distinct().limit(12).toList();
        return "Publication phases for the tested working copy: " + operations + ", decisions: " + trail
                + ", SQL diagnostic codes from the test container: " + sqlStates;
    }

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

    @Test
    void historyIndexClaimsAndPinnedPurgeReceiptsSurviveIndependentApplicationHandles() throws Exception {
        var database = getDatabaseContainer();
        var data = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        com.taxonomy.versioning.service.HistoryIndexDatabaseContract.independentWorkers(data);
        com.taxonomy.versioning.service.HistoryIndexWriterContractTest.verify(data, false);
    }

    @Test @Order(26)
    void workingCopyFetchClaimsAndScopedReceiptsSurviveIndependentApplicationHandles() throws Exception {
        var database = getDatabaseContainer();
        com.taxonomy.workspace.service.WorkspaceFetchDatabaseContractTest.verify(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    @Test @Order(27)
    void branchProgressRetainsIndependentSyncAndPublishObservationsInTheRunningDatabase() throws Exception {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        WorkspaceBranchProgressDatabaseContractTest.verify(source);
    }

    @Test @Order(28)
    void publicationSurvivesIndependentDatabaseSessionsAndFencesDeletedSources() throws Exception {
        var database=getDatabaseContainer();
        com.taxonomy.workspace.service.WorkspacePublicationDatabaseContractTest.verify(new DriverManagerDataSource(
                database.getJdbcUrl(),database.getUsername(),database.getPassword()));
    }

    @Test @Order(29)
    void versionedBranchPolicyAndNativePublicationUseTheSameDatabaseFence() throws Exception {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        URI application = URI.create("http://" + getAppContainer().getHost() + ":" + getAppContainer().getMappedPort(8080));
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
        try (var contract = new RepositoryHistoryHttpContract(application, authorization)) {
            contract.verifyRefPolicy(source);
        }
    }

    private static Configuration portfolioConfiguration(DataSource source) {
        var configuration = new Configuration().setProperty("hibernate.hbm2ddl.auto", "none")
                .setProperty("hibernate.search.enabled", "false");
        configuration.getProperties().put("hibernate.connection.datasource", source);
        for (var entity : List.of(ArchitectureProject.class, ProjectRequirement.class, ProjectRequirementVersion.class,
                RequirementAnalysisJob.class, RequirementAnalysisSnapshot.class, SnapshotReference.class)) {
            configuration.addAnnotatedClass(entity);
        }
        return configuration;
    }

    @Test
    void packedPortfolioSnapshotUsesActualDriverAcrossFullColdHitAndDirectReplacement() throws Exception {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        boolean postgres = "org.postgresql.Driver".equals(database.getDriverClassName());
        var codec = new PortfolioJsonCodec(JsonMapper.builder().build());
        String first = packedDriverAnalysis(codec, "PARTIAL", 65_536);
        String changed = null;
        for (int padding = 65_536; padding < 65_792; padding++) {
            String candidate = packedDriverAnalysis(codec, "SUCCESS", padding);
            if (candidate.length() == first.length()) {
                changed = candidate;
                break;
            }
        }
        assertThat(changed).as("independently valid equal-length v1 frame for same-OID replacement").isNotNull();

        String repository = jdbc.queryForObject("select repository_id from system_repository where primary_repo=?",
                String.class, Boolean.TRUE);
        String workspace = "packed-workspace-" + UUID.randomUUID();
        String scope = new RepositoryTenantIdentity(repository, "WORKSPACE:" + workspace, "draft").scopeKey();
        String snapshotId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try (var factory = portfolioConfiguration(source).buildSessionFactory()) {
            var ids = factory.fromTransaction(session -> {
                var project = new ArchitectureProject(scope, workspace, "alice", "PACKED", "Packed driver fixture", null,
                        ProjectStatus.ACTIVE, now);
                session.persist(project);
                session.flush();
                var requirement = new ProjectRequirement(project, "PACKED-REQ", "Packed driver requirement",
                        RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                        ReviewStatus.PROPOSED, "alice", now);
                session.persist(requirement);
                session.flush();
                var version = new ProjectRequirementVersion(requirement, 1, "Original requirement",
                        "b".repeat(64), null, "alice", now, null, null, null, null, null, null);
                session.persist(version);
                session.flush();
                var job = new RequirementAnalysisJob(jobId, project, "PACKED", "LOCAL", 30, "alice", workspace, 1, now);
                session.persist(job);
                session.flush();
                session.persist(new RequirementAnalysisSnapshot(snapshotId, project, requirement, version, job,
                        AnalysisStatus.PARTIAL, snapshotId, "LOCAL", null, null, null, workspace, "draft",
                        "a".repeat(40), "alice", now, 10, 0, null, "{}", null, null, null));
                return new PackedSnapshotIds(project.getId(), requirement.getId(), version.getId());
            });
            var ownedLocators = new LinkedHashSet<String>();
            try (AutoCloseable cleanup = () -> cleanupPackedDriverFixture(source, jdbc, scope, snapshotId, jobId, ownedLocators)) {
                String rowOid = null;
                if (postgres) {
                    ownedLocators.add(jdbc.queryForObject("select requirement_text::text from project_req_version where id=? and scope_key=?",
                            String.class, ids.version(), scope));
                    rowOid = packedDriverOid(jdbc, snapshotId, scope);
                    ownedLocators.add(rowOid);
                    putPackedDriverOid(jdbc, rowOid, first);
                    assertThat(packedDriverOidText(jdbc, snapshotId, scope)).isEqualTo(first);
                } else {
                    replacePackedDriverText(source, snapshotId, scope, first);
                }

                var reader = new PortfolioSnapshotEvidenceReader(
                        SharedEntityManagerCreator.createSharedEntityManager(factory), codec);
                var tx = new TransactionTemplate(new JpaTransactionManager(factory));
                assertThat(tx.execute(status -> reader.read(ids.project(), snapshotId, scope).orElseThrow().analysis()).getStatus())
                        .isEqualTo("PARTIAL");
                var foreign = tx.execute(status -> continuationInputs(reader, ids, snapshotId, scope, jobId + "-foreign"));
                assertThat(foreign).isNull();
                var cold = tx.execute(status -> continuationInputs(reader, ids, snapshotId, scope, jobId));
                assertThat(continuationStatus(cold)).isEqualTo("PARTIAL");
                var hit = tx.execute(status -> continuationInputs(reader, ids, snapshotId, scope, jobId));
                assertThat(hit).isSameAs(cold);

                String expected = "SUCCESS";
                if (postgres) {
                    // Overwrite the primed locator in place. Both ASCII frames have the same byte count.
                    putPackedDriverOid(jdbc, rowOid, changed);
                    assertThat(packedDriverOid(jdbc, snapshotId, scope)).isEqualTo(rowOid);
                    assertThat(packedDriverOidText(jdbc, snapshotId, scope)).isEqualTo(changed);
                    var inPlace = tx.execute(status -> continuationInputs(reader, ids, snapshotId, scope, jobId));
                    assertThat(inPlace).isNotSameAs(cold);
                    assertThat(continuationStatus(inPlace)).isEqualTo("SUCCESS");
                    cold = inPlace;

                    String replacement = jdbc.queryForObject("select lo_from_bytea(0, convert_to(?, 'UTF8'))::text",
                            String.class, first);
                    ownedLocators.add(replacement);
                    assertThat(jdbc.update("update req_analysis_snapshot set analysis_payload=cast(? as oid) where id=? and scope_key=?",
                            replacement, snapshotId, scope)).isEqualTo(1);
                    assertThat(packedDriverOid(jdbc, snapshotId, scope)).isEqualTo(replacement).isNotEqualTo(rowOid);
                    assertThat(packedDriverOidText(jdbc, snapshotId, scope)).isEqualTo(first);
                    expected = "PARTIAL";
                } else {
                    replacePackedDriverText(source, snapshotId, scope, changed);
                }
                var replaced = tx.execute(status -> continuationInputs(reader, ids, snapshotId, scope, jobId));
                assertThat(replaced).isNotSameAs(cold);
                assertThat(continuationStatus(replaced)).isEqualTo(expected);
                var replacementHit = tx.execute(status -> continuationInputs(reader, ids, snapshotId, scope, jobId));
                assertThat(replacementHit).isSameAs(replaced);
                assertThat(tx.execute(status -> reader.read(ids.project(), snapshotId, scope).orElseThrow().analysis()).getStatus())
                        .isEqualTo(expected);
            }
        }
    }

    private record PackedSnapshotIds(Long project, Long requirement, Long version) { }

    private static Object continuationInputs(PortfolioSnapshotEvidenceReader reader, PackedSnapshotIds ids,
                                             String snapshot, String scope, String job) {
        // Keep the application reader and its memo internals package-private.
        Optional<?> result = ReflectionTestUtils.invokeMethod(reader, "readContinuation",
                ids.project(), snapshot, scope, job, ids.requirement(), ids.version());
        return result.map(evidence -> ReflectionTestUtils.invokeMethod(evidence, "inputs")).orElse(null);
    }

    private static String continuationStatus(Object inputs) {
        assertThat(inputs).isNotNull();
        return ReflectionTestUtils.invokeMethod(inputs, "status");
    }

    private static String packedDriverAnalysis(PortfolioJsonCodec codec, String status, int padding) throws Exception {
        var analysis = new AnalysisResult(Map.of("BP-1000", 90), List.of());
        analysis.setStatus(status);
        String frame = PortableJsonPayloads.pack(codec.write(analysis) + " ".repeat(padding), 24_000_000);
        assertThat(frame).startsWith("taxonomy-json-payload:v1:");
        return frame;
    }

    private static String packedDriverOid(JdbcTemplate jdbc, String snapshot, String scope) {
        return jdbc.queryForObject("select analysis_payload::text from req_analysis_snapshot where id=? and scope_key=?",
                String.class, snapshot, scope);
    }

    private static String packedDriverOidText(JdbcTemplate jdbc, String snapshot, String scope) {
        return jdbc.queryForObject("select convert_from(lo_get(analysis_payload), 'UTF8') from req_analysis_snapshot where id=? and scope_key=?",
                String.class, snapshot, scope);
    }

    private static void putPackedDriverOid(JdbcTemplate jdbc, String oid, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        jdbc.query("select lo_put(cast(? as oid), cast(0 as bigint), ?)", rows -> {
            assertThat(rows.next()).isTrue();
            return null;
        }, oid, bytes);
    }

    private static void replacePackedDriverText(DataSource source, String snapshot, String scope, String payload) throws Exception {
        try (var connection = source.getConnection(); var update = connection.prepareStatement(
                "update req_analysis_snapshot set analysis_payload=? where id=? and scope_key=?")) {
            update.setCharacterStream(1, new StringReader(payload), payload.length());
            update.setString(2, snapshot);
            update.setString(3, scope);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static void cleanupPackedDriverFixture(DataSource source, JdbcTemplate jdbc, String scope,
                                                   String snapshotId, String jobId, Set<String> ownedLocators) {
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(transaction -> {
            jdbc.update("delete from req_analysis_snapshot where id=? and scope_key=?", snapshotId, scope);
            jdbc.update("delete from req_analysis_job where id=? and scope_key=?", jobId, scope);
            jdbc.update("delete from project_req_version where scope_key=?", scope);
            jdbc.update("delete from project_requirement where scope_key=?", scope);
            jdbc.update("delete from arch_project where scope_key=?", scope);
            for (String oid : ownedLocators) jdbc.queryForObject("select lo_unlink(cast(? as oid))", Integer.class, oid);
        });
    }

    protected void postgresPortfolioArchiveReadsPlainAndPackedAnalysisFromActualOidLocators() throws Exception {
        var database = getDatabaseContainer();
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        String repository = jdbc.queryForObject("select repository_id from system_repository where primary_repo=true", String.class);
        String workspace = "archive-workspace-" + UUID.randomUUID();
        String scope = new RepositoryTenantIdentity(repository, "WORKSPACE:" + workspace, "draft").scopeKey();
        String id = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String commit = "a".repeat(40);
        String original = " \n{\"legacyExtra\" : \"Unveränderte Evidenz 🙂\"} \n" + " ".repeat(65_536);
        String storedFrame = PortfolioAnalysisPayloads.store(original, true);
        String expectedArchive = PortableJsonPayloads.pack(original, 24_000_000);
        assertThat(expectedArchive).startsWith("taxonomy-json-payload:v1:");
        assertThat(storedFrame).startsWith("taxonomy-json-payload:v1:").isNotEqualTo(expectedArchive);
        Instant now = Instant.now();
        try (var factory = portfolioConfiguration(source).buildSessionFactory()) {
            factory.inTransaction(session -> {
                var project = new ArchitectureProject(scope, workspace, "alice", "ARCHIVE", "Archive fixture", null,
                        ProjectStatus.ACTIVE, now);
                session.persist(project);
                session.flush();
                var requirement = new ProjectRequirement(project, "ARCHIVE-REQ", "Frozen requirement", RequirementStatus.DRAFT,
                        50, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "alice", now);
                session.persist(requirement);
                session.flush();
                var version = new ProjectRequirementVersion(requirement, 1, "Original requirement",
                        "b".repeat(64), null, "alice", now, null, null, null, null, null, null);
                session.persist(version);
                session.flush();
                var job = new RequirementAnalysisJob(jobId, project, "ARCHIVE", "LOCAL", 30, "alice", workspace, 1, now);
                session.persist(job);
                session.flush();
                session.persist(new RequirementAnalysisSnapshot(id, project, requirement, version, job, AnalysisStatus.SUCCESS,
                        id, "LOCAL", null, null, null, workspace, "draft", commit, "alice", now, 10, 0,
                        null, original, null, null, null));
                session.flush();
                requirement.pointToVersion(version.getId(), now);
                requirement.pointToAnalysis(id, now);
            });
        }

        var ownedLocators = new LinkedHashSet<String>();
        try (AutoCloseable cleanup = () -> new TransactionTemplate(new DataSourceTransactionManager(source))
                .executeWithoutResult(transaction -> {
                    ownedLocators.add(jdbc.queryForObject(
                            "select requirement_text::text from project_req_version where scope_key=?", String.class, scope));
                    jdbc.update("update project_requirement set current_snapshot_id=null,current_version_id=null where scope_key=?", scope);
                    jdbc.update("delete from req_analysis_snapshot where id=? and scope_key=?", id, scope);
                    jdbc.update("delete from req_analysis_job where id=? and scope_key=?", jobId, scope);
                    jdbc.update("delete from project_req_version where scope_key=?", scope);
                    jdbc.update("delete from project_requirement where scope_key=?", scope);
                    jdbc.update("delete from arch_project where scope_key=?", scope);
                    for (String oid : ownedLocators) jdbc.queryForObject("select lo_unlink(cast(? as oid))", Integer.class, oid);
                })) {
            assertThat(jdbc.queryForObject("select pg_typeof(analysis_payload)::text from req_analysis_snapshot where id=?",
                    String.class, id)).isEqualTo("oid");
            String plainOid = jdbc.queryForObject("select analysis_payload::text from req_analysis_snapshot where id=?", String.class, id);
            ownedLocators.add(plainOid);
            var backup = postgresPortfolioArchiveContext(repository, workspace, commit, now);
            String plainArchive = capturePortfolioAnalysis(source, backup, id);
            assertThat(plainArchive).isEqualTo(expectedArchive);
            assertThat(PortableJsonPayloads.unpack(plainArchive, 24_000_000)).isEqualTo(original);
            assertThat(jdbc.queryForObject("select analysis_payload::text from req_analysis_snapshot where id=?", String.class, id))
                    .isEqualTo(plainOid);
            assertThat(jdbc.queryForObject("select convert_from(lo_get(analysis_payload), 'UTF8') from req_analysis_snapshot where id=?",
                    String.class, id)).isEqualTo(original);

            String packedOid = jdbc.queryForObject("select lo_from_bytea(0, convert_to(?, 'UTF8'))::text",
                    String.class, storedFrame);
            ownedLocators.add(packedOid);
            assertThat(jdbc.update("update req_analysis_snapshot set analysis_payload=cast(? as oid) where id=?", packedOid, id))
                    .isEqualTo(1);
            String packedArchive = capturePortfolioAnalysis(source, backup, id);
            assertThat(packedArchive).isEqualTo(expectedArchive);
            assertThat(PortableJsonPayloads.unpack(packedArchive, 24_000_000)).isEqualTo(original);
            assertThat(jdbc.queryForObject("select convert_from(lo_get(analysis_payload), 'UTF8') from req_analysis_snapshot where id=?",
                    String.class, id)).isEqualTo(storedFrame);
        }
    }

    private static SnapshotContext postgresPortfolioArchiveContext(String repository, String workspace, String commit, Instant now) {
        var scope = new BackupScope.Workspace(repository, workspace);
        var request = new BackupRequest(BackupProfile.CURRENT_STATE, scope, new BackupTime.Current(),
                GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var authorization = new AuthorizedBackupRequest(request, PrincipalId.create(), "archive-test", now,
                EnumSet.allOf(BackupCapability.class));
        var state = new SnapshotContext.RepositoryState(Map.of("refs/heads/draft", commit),
                "refs/heads/draft", Map.of("draft", new SnapshotContext.WorkingState(1, commit, 1)), Set.of(commit));
        return new SnapshotContext(BackupId.create(), authorization, now, now, 1,
                Map.of(new BackupRepositoryKey(repository, workspace), state), Map.of());
    }

    private static String capturePortfolioAnalysis(DataSource source, SnapshotContext backup, String id) throws Exception {
        var captured = new HashMap<String, byte[]>();
        var digest = MessageDigest.getInstance("SHA-256");
        new PortfolioBackupContributor(source).write(backup, (path, input) -> {
            byte[] bytes = input.readAllBytes();
            captured.put(path, bytes);
            return new BackupEntry(path, bytes.length, HexFormat.of().formatHex(digest.digest(bytes)));
        });
        byte[] records = captured.get("data/portfolio/analysis-snapshot.ndjson");
        assertThat(records).isNotNull();
        var lines = new String(records, StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(2);
        var record = PortableRows.json().readTree(lines.getLast());
        assertThat(record.path("sourceId").path("value").asText()).isEqualTo(id);
        assertThat(record.path("analysisPayload").isTextual()).isTrue();
        return record.path("analysisPayload").asText();
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
