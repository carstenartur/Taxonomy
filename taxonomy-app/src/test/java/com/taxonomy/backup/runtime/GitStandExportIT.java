package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.architecture.backup.ArchitectureBackupContributor;
import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.portfolio.backup.PortfolioStandDocument;
import com.taxonomy.workspace.backup.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepository;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Real Git/journal reads composed with the portfolio projection and architecture content-proof adapter. */
class GitStandExportIT {
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repository", "private-workspace");
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitStandBackupSource.Limits LIMITS = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);

    @Test void currentDraftRemovesEmbeddedHistoryAndSupersededDatabaseDocuments() throws Exception {
        try (var f = new Fixture()) {
            String head = f.commit(document("COMMITTED-OLD", "COMMITTED-ANCESTRY"));
            String saved = document("SAVED-DRAFT", "DRAFT-ANCESTRY"); f.saved(head, saved);
            var auth = current();
            try (var stand = f.source.open(auth, KEY, LIMITS, CHECK)) {
                String exported = files(stand); assertThat(exported).contains("SAVED-DRAFT", "CURRENT-ARCHITECTURE")
                        .doesNotContain("COMMITTED-OLD", "ANCESTRY", "OLD-ORIGINAL", "OLD-REASON", "OLD-EVIDENCE");
                assertThat(stand.documents()).isEmpty(); assertThat(architecture(f, auth, stand)).doesNotContain("COMMITTED-OLD");
                assertThat(f.jdbc.queryForObject("select dsl from editor_workspace", String.class)).isEqualTo("1:" + saved);
            }
            assertThat(f.jdbc.queryForObject("select raw_content from architecture_dsl_document", String.class)).contains("COMMITTED-ANCESTRY");
        }
    }

    @Test void aSelectedVersionProjectsOnlyItsOwnContentsAndSelectsItsMatchingMaterializedDocument() throws Exception {
        try (var f = new Fixture()) {
            String selected = f.commit(document("SELECTED-CONTENT", "SELECTED-ANCESTRY"));
            String today = f.commit(document("TODAY-CONTENT", "TODAY-ANCESTRY")); f.saved(today, document("LIVE-DRAFT", "LIVE-ANCESTRY"));
            var auth = authorized(new BackupRequest(BackupProfile.SELECTED_VERSION, scope(), new BackupTime.SelectedVersion(Map.of(KEY, selected)), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
            try (var stand = f.source.open(auth, KEY, LIMITS, CHECK)) {
                assertThat(files(stand)).contains("SELECTED-CONTENT").doesNotContain("ANCESTRY", "TODAY-CONTENT", "LIVE-DRAFT", "OLD-ORIGINAL", "OLD-REASON", "OLD-EVIDENCE");
                assertThat(architecture(f, auth, stand)).contains("SELECTED-CONTENT").doesNotContain("ANCESTRY", "TODAY-CONTENT", "LIVE-DRAFT", "OLD-ORIGINAL", "OLD-REASON", "OLD-EVIDENCE");
                assertThat(stand.state().workingStates()).isEmpty(); assertThat(stand.state().requiredCommits()).containsExactly(selected);
            }
        }
    }

    @Test void unchangedSavedStateRetainsMatchingDocumentProofButStillProjectsBothRepresentations() throws Exception {
        try (var f = new Fixture()) {
            String original = document("CURRENT", "OLD-ANCESTRY"), head = f.commit(original); f.saved(head, original);
            try (var stand = f.source.open(current(), KEY, LIMITS, CHECK)) {
                assertThat(stand.documents()).hasSize(1);
                assertThat(files(stand) + architecture(f, current(), stand)).contains("CURRENT").doesNotContain("OLD-ANCESTRY", "OLD-ORIGINAL", "OLD-REASON", "OLD-EVIDENCE");
            }
        }
    }

    @Test void unsupportedTaxdslCannotBecomeASilentIncompleteStand() throws Exception {
        try (var f = new Fixture()) {
            f.commit("element lost { title: \"PRIVATE-UNPARSED\"; }");
            assertThatThrownBy(() -> f.source.open(current(), KEY, LIMITS, CHECK)).isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("PRIVATE-UNPARSED");
        }
    }

    private static String files(GitStandBackupSource.Stand stand) throws IOException {
        var bytes = new ByteArrayOutputStream(); for (var file : stand.files()) stand.copy(file, bytes, CHECK); return bytes.toString(StandardCharsets.UTF_8);
    }
    private static String architecture(Fixture f, AuthorizedBackupRequest auth, GitStandBackupSource.Stand stand) throws IOException {
        var snapshot = new SnapshotContext(BackupId.create(), auth, Instant.now(), Instant.now(), 1, Map.of(KEY, stand.state()), Map.of());
        var output = new CurrentStateExportIT.Contents();
        new ArchitectureBackupContributor(f.database, PortfolioStandDocument::project, (ignored, checkpoint) -> stand.documents()).write(snapshot, output);
        return output.text();
    }
    private static BackupScope scope() { return new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()); }
    private static AuthorizedBackupRequest current() { return authorized(new BackupRequest(BackupProfile.CURRENT_STATE, scope(), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE)); }
    private static AuthorizedBackupRequest authorized(BackupRequest request) { return new AuthorizedBackupRequest(request, PrincipalId.create(), "test-decision", Instant.now(), EnumSet.allOf(BackupCapability.class)); }
    static String document(String current, String history) {
        return """
                project P {
                  title: "Current project";
                }
                projectRequirement P R {
                  currentVersionNumber: "2";
                }
                requirementVersion P R 1 {
                  text: "%s";
                }
                requirementVersion P R 2 {
                  text: "%s";
                  originalText: "OLD-ORIGINAL";
                  changeReason: "OLD-REASON";
                }
                reformulationEvidence P R 2 {
                  payload: "OLD-EVIDENCE";
                }
                element current type System {
                  title: "CURRENT-ARCHITECTURE";
                }
                """.formatted(history, current);
    }
    static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource(); final EditorPersistenceFixture persistence; final JdbcTemplate jdbc; final GitStandBackupSource source;
        Fixture() {
            String url = "jdbc:hsqldb:mem:git-stand-export-" + UUID.randomUUID() + ";hsqldb.tx=mvcc"; database.setUrl(url); database.setUser("SA"); database.setPassword("");
            persistence = new EditorPersistenceFixture(url, ArchitectureDslDocument.class); jdbc = new JdbcTemplate(database);
            jdbc.execute("create table system_repository(repository_id varchar(255) primary key,storage_repository_name varchar(255),default_branch varchar(255))");
            jdbc.execute("create table user_workspace(workspace_id varchar(255) primary key,source_repository_id varchar(255),current_branch varchar(255))");
            jdbc.update("insert into system_repository values('repository','central-storage','draft')"); jdbc.update("insert into user_workspace values('private-workspace','repository','draft')");
            source = new GitStandBackupSource(database, new ExistingGitBackupRepositories(database, persistence.factory), PortfolioStandDocument::project);
        }
        String commit(String dsl) throws Exception {
            String commit;
            try (var git = new DslGitRepository(new DefaultHibernateRepositoryFactory(persistence.factory), "ws-private-workspace")) { commit = git.commitDsl("draft", dsl, "actor", "SOURCE-COMMIT-METADATA"); }
            try (var session = persistence.factory.openSession()) {
                var tx = session.beginTransaction(); var document = new ArchitectureDslDocument(); document.setPath(DslGitRepository.DSL_FILENAME); document.setCommitId(commit); document.setRawContent(dsl); document.setParsedAt(Instant.now()); session.persist(document); tx.commit();
            }
            return commit;
        }
        void saved(String commit, String dsl) {
            String scope = RepositoryContext.workspace(KEY.repositoryId(), KEY.workspaceId(), "draft", "actor").repositoryWorkspaceScopeKey();
            jdbc.update("insert into editor_workspace(scope_id,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,pending_checkpoint,row_version) values(?,?,?,?,?,3,?,1,null,0)", scope, KEY.repositoryId(), KEY.workspaceId(), "draft", "1:"+dsl, commit);
        }
        @Override public void close() { persistence.close(); }
    }
}
