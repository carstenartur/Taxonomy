package com.taxonomy.backup;

import com.taxonomy.architecture.backup.ArchitectureBackupContributor;
import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.portfolio.backup.PortfolioStandDocument;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ArchitectureDocumentExportIT {
    private static final String CURRENT = "a".repeat(40), OLD = "b".repeat(40), FOREIGN = "c".repeat(40);
    private static final BackupRepositoryKey OWN = new BackupRepositoryKey("repo-a", "private-a");
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String CURRENT_DSL = """
            project P {
              title: "Current project";
            }
            projectRequirement P R {
              currentVersionNumber: "2";
            }
            requirementVersion P R 1 {
              text: "EMBEDDED-OLD-SECRET";
            }
            requirementVersion P R 2 {
              text: "CURRENT-ARCHITECTURE";
              originalText: "OLD-ORIGINAL";
            }
            """;
    private static final String SELECTED_DSL = """
            project P {
              title: "SELECTED-ARCHITECTURE";
            }
            """;

    @Test void scopedCurrentRequiresCommitPathAndContentProofAndProjectsEmbeddedHistory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.document(CURRENT, "architecture.taxdsl", CURRENT_DSL);
            fixture.document(OLD, "architecture.taxdsl", "OLDER-PRIVATE-ARCHITECTURE");
            fixture.document(FOREIGN, "architecture.taxdsl", "FOREIGN-WORKSPACE");
            fixture.document(CURRENT, "architecture.taxdsl", "SPOOFED-COMMIT-CONTENT");
            fixture.document(CURRENT, "other.taxdsl", "UNSELECTED-PATH");
            var output = new CurrentStateExportIT.Contents();
            contributor(fixture, proof(OWN, CURRENT, CURRENT_DSL)).write(snapshot(BackupProfile.CURRENT_STATE, CURRENT), output);
            assertThat(output.text()).contains("CURRENT-ARCHITECTURE", "private-a", "architecture.document")
                    .doesNotContain("EMBEDDED-OLD-SECRET", "OLD-ORIGINAL", "OLDER-PRIVATE-ARCHITECTURE", "FOREIGN-WORKSPACE",
                            "SPOOFED-COMMIT-CONTENT", "UNSELECTED-PATH", "UNPROVEN-METADATA");
            assertThat(fixture.jdbc.queryForObject("select count(*) from architecture_dsl_document", Integer.class)).isEqualTo(5);
            assertThat(fixture.jdbc.queryForList("select raw_content from architecture_dsl_document", String.class)).contains(CURRENT_DSL);
        }
    }

    @Test void selectedVersionDoesNotSubstituteCurrentDatabaseContent() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.document(OLD, "architecture.taxdsl", SELECTED_DSL);
            fixture.document(CURRENT, "architecture.taxdsl", CURRENT_DSL);
            var output = new CurrentStateExportIT.Contents();
            contributor(fixture, proof(OWN, OLD, SELECTED_DSL))
                    .write(snapshot(BackupProfile.SELECTED_VERSION, OLD), output);
            assertThat(output.text()).contains("SELECTED-ARCHITECTURE").doesNotContain("CURRENT-ARCHITECTURE", "UNPROVEN-METADATA");
        }
    }

    @Test void foreignRepositoryAndWrongTimeProofAreRejectedBeforeOutput() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.document(CURRENT, "architecture.taxdsl", CURRENT_DSL);
            for (var reference : List.of(proof(new BackupRepositoryKey("repo-a", "private-b"), CURRENT, CURRENT_DSL),
                    proof(OWN, OLD, "OLDER-PRIVATE-ARCHITECTURE"))) {
                var output = new CurrentStateExportIT.Contents();
                assertThatThrownBy(() -> contributor(fixture, reference).write(snapshot(BackupProfile.CURRENT_STATE, CURRENT), output))
                        .isInstanceOf(java.io.IOException.class);
                assertThat(output.entries).isEmpty();
            }
            var output = new CurrentStateExportIT.Contents();
            assertThatThrownBy(() -> contributor(fixture, proof(OWN, CURRENT, CURRENT_DSL))
                    .write(snapshot(BackupProfile.SELECTED_VERSION, OLD), output)).isInstanceOf(java.io.IOException.class);
            assertThat(output.entries).isEmpty();
        }
    }

    @Test void fullInstallationPreservesUnattributedLegacyRecordsWithoutInventingOwnership() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.document(null, "inline", "UNCOMMITTED-LEGACY");
            fixture.document(OLD, "architecture.taxdsl", "HISTORICAL-LEGACY");
            var output = new CurrentStateExportIT.Contents();
            new ArchitectureBackupContributor(fixture.database, PortfolioStandDocument::project,
                    (ignored, checkpoint) -> { throw new AssertionError("Full legacy capture does not need invented Git attribution"); })
                    .write(snapshot(BackupProfile.INSTALLATION_FULL, CURRENT), output);
            assertThat(output.text()).contains("UNCOMMITTED-LEGACY", "HISTORICAL-LEGACY", "UNPROVEN-METADATA", "architecture.legacy-document")
                    .doesNotContain("private-a");
        }
    }

    @Test void installationCurrentExcludesUnattributedHistoricalDocuments() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.document(CURRENT, "architecture.taxdsl", CURRENT_DSL);
            fixture.document(null, "inline", "UNCOMMITTED-LEGACY");
            fixture.document(OLD, "architecture.taxdsl", "HISTORICAL-LEGACY");
            var output = new CurrentStateExportIT.Contents();
            var contributor = contributor(fixture, proof(OWN, CURRENT, CURRENT_DSL));
            contributor.write(snapshot(BackupProfile.INSTALLATION_CURRENT, CURRENT), output);
            assertThat(output.text()).contains("CURRENT-ARCHITECTURE").doesNotContain("UNCOMMITTED-LEGACY", "HISTORICAL-LEGACY");
            assertThat(contributor.omissions(BackupProfile.INSTALLATION_CURRENT)).isNotEmpty();
        }
    }

    @Test void repositoryHistoryRetainsOnlyProvenHistoricalDocumentPayloads() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.document(CURRENT, "architecture.taxdsl", CURRENT_DSL);
            fixture.document(OLD, "architecture.taxdsl", "AUTHORIZED-OLD-ARCHITECTURE");
            fixture.document(FOREIGN, "architecture.taxdsl", "OTHER-PRIVATE-ARCHITECTURE");
            var output = new CurrentStateExportIT.Contents();
            new ArchitectureBackupContributor(fixture.database, PortfolioStandDocument::project,
                    (ignored, checkpoint) -> List.of(proof(OWN, CURRENT, CURRENT_DSL), proof(OWN, OLD, "AUTHORIZED-OLD-ARCHITECTURE")))
                    .write(snapshot(BackupProfile.REPOSITORY_HISTORY, CURRENT), output);
            assertThat(output.text()).contains("CURRENT-ARCHITECTURE", "EMBEDDED-OLD-SECRET", "AUTHORIZED-OLD-ARCHITECTURE")
                    .doesNotContain("OTHER-PRIVATE-ARCHITECTURE", "UNPROVEN-METADATA");
        }
    }

    private static ArchitectureBackupContributor contributor(Fixture fixture, BackupDocumentReference reference) {
        return new ArchitectureBackupContributor(fixture.database, PortfolioStandDocument::project, (ignored, checkpoint) -> List.of(reference));
    }
    private static BackupDocumentReference proof(BackupRepositoryKey key, String commit, String content) {
        try {
            return new BackupDocumentReference(key, commit, "architecture.taxdsl",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static SnapshotContext snapshot(BackupProfile profile, String selected) {
        BackupScope scope = profile.isInstallation() ? new BackupScope.Installation() : new BackupScope.Workspace("repo-a", "private-a");
        BackupTime time = profile.includesHistory() ? new BackupTime.History() : profile == BackupProfile.SELECTED_VERSION
                ? new BackupTime.SelectedVersion(Map.of(OWN, selected)) : new BackupTime.Current();
        var request = new BackupRequest(profile, scope, time, profile.includesHistory() ? GitRepresentation.BUNDLE : GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var authorized = new AuthorizedBackupRequest(request, PrincipalId.create(), "architecture-fixture", NOW, EnumSet.allOf(BackupCapability.class));
        return new SnapshotContext(BackupId.create(), authorized, NOW, NOW, 1,
                Map.of(OWN, new SnapshotContext.RepositoryState(Map.of("refs/heads/main", CURRENT), "refs/heads/main", Map.of(), Set.of(OLD))),
                Map.of(new BackupComponentId("architecture"), 1));
    }
    private static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource();
        final org.hibernate.SessionFactory factory;
        final JdbcTemplate jdbc;
        Fixture() {
            database.setUrl("jdbc:hsqldb:mem:architecture-backup-" + UUID.randomUUID()); database.setUser("sa");
            factory = new Configuration().addAnnotatedClass(ArchitectureDslDocument.class)
                    .setProperty("hibernate.connection.url", database.getUrl()).setProperty("hibernate.connection.username", "sa")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
            jdbc = new JdbcTemplate(database);
        }
        void document(String commit, String path, String content) {
            try (var session = factory.openSession()) {
                var tx = session.beginTransaction(); var document = new ArchitectureDslDocument();
                document.setPath(path); document.setCommitId(commit); document.setBranch("UNPROVEN-METADATA");
                document.setNamespace("UNPROVEN-METADATA"); document.setDslVersion("1"); document.setRawContent(content); document.setParsedAt(NOW);
                session.persist(document); tx.commit();
            }
        }
        @Override public void close() { factory.close(); }
    }
}
