package com.taxonomy.backup;

import com.taxonomy.analysis.backup.AnalysisBackupContributor;
import com.taxonomy.analysis.recovery.AnalysisContinuationRun;
import com.taxonomy.analysis.recovery.AnalysisQuestionCheckpoint;
import com.taxonomy.analysis.session.AnalysisWorkingDraft;
import com.taxonomy.editor.persistence.*;
import com.taxonomy.workspace.backup.WorkspaceBackupContributor;
import com.taxonomy.workspace.model.*;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import com.taxonomy.portfolio.backup.PortfolioStandDocument;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Real persistent rows deliberately collide on branch, user-visible IDs and old payloads. */
class CurrentStateExportIT {
    static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    static final String COMMIT = "a".repeat(40);

    @Test void aForeignSolutionReferenceFailsCaptureInsteadOfBeingSilentlyDropped() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            try (var em = fixture.factory.createEntityManager()) {
                var tx=em.getTransaction(); tx.begin();
                var project=em.createQuery("from ArchitectureProject",ArchitectureProject.class).getSingleResult();
                var foreign=new SolutionDefinition(new RepositoryTenantIdentity("repo-b","WORKSPACE:private-b","draft").scopeKey(),
                        "private-b","S-FOREIGN","FOREIGN-SOLUTION",null,SolutionType.APPLICATION,OperatingModel.PRIVATE_CLOUD,LifecycleStatus.ACTIVE,1,"bob",null,NOW);
                em.persist(foreign); em.flush();
                em.persist(new ProjectSolution(project,foreign,ProjectSolutionStatus.SELECTED,ActionStatus.REUSE,50,"Cross-scope reference","alice",NOW));
                tx.commit();
            }
            var output=new Contents();
            assertThatThrownBy(() -> new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.CURRENT_STATE,
                    new BackupScope.Workspace("repo-a","private-a")),output)).isInstanceOf(java.io.IOException.class);
            assertThat(output.text()).doesNotContain("FOREIGN-SOLUTION");
        }
    }

    @Test void staleCurrentAnalysisPointerCannotReintroduceAnEarlierRequirementVersion() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.requirement("repo-a","private-a","OLD","PREVIOUS-CURRENT");
            try (var em=fixture.factory.createEntityManager()) {
                var tx=em.getTransaction(); tx.begin();
                var requirement=em.createQuery("from ProjectRequirement",ProjectRequirement.class).getSingleResult();
                var version=new ProjectRequirementVersion(requirement,3,"LATEST-CURRENT",com.taxonomy.identity.StableIdentityHash.sha256("LATEST-CURRENT"),null,"alice",NOW,null,null,null,null,null,null);
                em.persist(version); em.flush(); requirement.pointToVersion(version.getId(),NOW); tx.commit();
            }
            var output=new Contents();
            new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.CURRENT_STATE,
                    new BackupScope.Workspace("repo-a","private-a")),output);
            assertThat(output.text()).contains("LATEST-CURRENT").doesNotContain("PREVIOUS-CURRENT","CURRENT-DECISION");
            var history=new Contents();
            new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Workspace("repo-a","private-a")),history);
            assertThat(history.text()).contains("LATEST-CURRENT","PREVIOUS-CURRENT","CURRENT-DECISION");
            assertThat(new String(history.entries.get("data/portfolio/requirement.ndjson"),StandardCharsets.UTF_8))
                    .doesNotContain("\"currentAnalysisSnapshotId\":null");
        }
    }

    @Test void portableTimeUsesUtcForBothZonedAndHibernateUtcTimestampColumns() throws Exception {
        var database=new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:portable-time-"+UUID.randomUUID()); database.setUser("sa");
        try (var connection=database.getConnection(); var schema=connection.createStatement()) {
            schema.execute("create table portable_time (zoned_value timestamp with time zone, utc_value timestamp)");
            try (var insert=connection.prepareStatement("insert into portable_time values (?,?)")) {
                insert.setObject(1,NOW.atOffset(java.time.ZoneOffset.ofHours(-7)));
                insert.setTimestamp(2,java.sql.Timestamp.from(NOW),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                insert.executeUpdate();
            }
        }
        var output=new Contents();
        new com.taxonomy.exchange.backup.PortableRows(database).write(output,"test","time",BackupProfile.CURRENT_STATE,
                new com.taxonomy.exchange.backup.PortableRows.Query("select zoned_value,utc_value from portable_time",List.of()),
                r -> new TimeRecord(com.taxonomy.exchange.backup.PortableRows.instant(r,"zoned_value"),com.taxonomy.exchange.backup.PortableRows.instant(r,"utc_value")));
        assertThat(output.text()).contains("\"zoned\":\""+NOW+"\"", "\"utc\":\""+NOW+"\"");
    }
    record TimeRecord(String zoned,String utc) { }

    @Test void portfolioSelectsTheCurrentVersionAndItsDecisionsInTheExactTenant() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.requirement("repo-a", "private-a", "OLD-REQUIREMENT-SECRET", "CURRENT-REQUIREMENT");
            fixture.requirement("repo-a", "private-b", "FOREIGN-OLD", "FOREIGN-CURRENT");
            fixture.requirement("repo-b", "private-a", "OTHER-OLD", "OTHER-CURRENT");
            var output = new Contents();
            new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.CURRENT_STATE,
                    new BackupScope.Workspace("repo-a", "private-a")), output);
            assertThat(output.text()).contains("CURRENT-REQUIREMENT", "P-SAME", "R-SAME", "CURRENT-DECISION")
                    .doesNotContain("OLD-REQUIREMENT-SECRET", "FOREIGN-OLD", "FOREIGN-CURRENT", "OTHER-OLD", "OTHER-CURRENT");
            var history = new Contents();
            new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Workspace("repo-a", "private-a")), history);
            assertThat(history.text()).contains("OLD-REQUIREMENT-SECRET", "CURRENT-REQUIREMENT").doesNotContain("FOREIGN-CURRENT", "OTHER-CURRENT");
        }
    }

    @Test void aSingleGitTreeCanContainHistoryAndMustBeProjectedBeforeAStandExport() {
        String dsl = """
                project P-SAME {
                  title: "Current project";
                }
                requirement P-SAME R-SAME {
                  currentVersionNumber: "2";
                }
                requirementVersion P-SAME R-SAME 1 {
                  text: "DELETED-TEXT-IN-HEAD";
                }
                requirementVersion P-SAME R-SAME 2 {
                  text: "CURRENT-TEXT";
                  originalText: "DELETED-ORIGINAL";
                  changeReason: "DELETED-REASON";
                }
                reformulationEvidence P-SAME R-SAME 2 {
                  payload: "ENCODED-HISTORICAL-PAYLOAD";
                }
                element arch-current type System {
                  title: "Keep current architecture";
                }
                requirement architecture-requirement {
                  title: "Keep current architecture requirement";
                  text: "Current non-portfolio requirement";
                }
                """;
        String projected = PortfolioStandDocument.project(dsl);
        assertThat(projected).contains("CURRENT-TEXT", "Keep current architecture", "Current non-portfolio requirement", "currentVersionNumber")
                .doesNotContain("DELETED-TEXT-IN-HEAD", "DELETED-ORIGINAL", "DELETED-REASON", "ENCODED-HISTORICAL-PAYLOAD");
        assertThatThrownBy(() -> PortfolioStandDocument.project(dsl.replace("currentVersionNumber: \"2\";", "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortfolioStandDocument.project("element lost { title: \"Do not silently lose this\"; }"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void currentWorkspaceIncludesSavedWorkButNeitherInverseOperationsNorOtherTenants() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.editor("a", "repo-a", "private-a", "CURRENT-A");
            fixture.editor("b", "repo-a", "private-b", "FOREIGN-PRIVATE");
            fixture.editor("c", "repo-b", "private-a", "OTHER-REPOSITORY");
            fixture.operation("a", "DELETED-HISTORICAL-SECRET");
            var output = new Contents();
            new WorkspaceBackupContributor(fixture.database, java.util.function.UnaryOperator.<String>identity()::apply).write(snapshot(BackupProfile.CURRENT_STATE,
                    new BackupScope.Workspace("repo-a", "private-a")), output);
            assertThat(output.text()).contains("CURRENT-A", "semanticRevision", "checkpointCommit")
                    .doesNotContain("DELETED-HISTORICAL-SECRET", "FOREIGN-PRIVATE", "OTHER-REPOSITORY");
            assertThat(fixture.jdbc.queryForObject("select count(*) from editor_operation", Integer.class)).isEqualTo(1);
        }
    }

    @Test void centralReadDoesNotSelectAnyPrivateWorkspaceAndHistoryRequiresTheHistoryProfile() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.editor("a", "repo-a", "private-a", "CURRENT-A");
            fixture.operation("a", "AUTHORIZED-HISTORY");
            var central = new Contents();
            new WorkspaceBackupContributor(fixture.database, java.util.function.UnaryOperator.<String>identity()::apply).write(snapshot(BackupProfile.CURRENT_STATE,
                    new BackupScope.Repositories(Map.of("repo-a", Set.of()))), central);
            assertThat(central.text()).doesNotContain("CURRENT-A", "AUTHORIZED-HISTORY");
            var history = new Contents();
            new WorkspaceBackupContributor(fixture.database, java.util.function.UnaryOperator.<String>identity()::apply).write(snapshot(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Workspace("repo-a", "private-a")), history);
            assertThat(history.text()).contains("CURRENT-A", "AUTHORIZED-HISTORY");
        }
    }

    @Test void analysisDraftKeepsCurrentTextAndOptionsWithoutStaleAnalysisOrUnknownEmbeddedHistory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", "alice", """
                    {"schemaVersion":1,"businessText":"SAVED-UNPUBLISHED-DRAFT","currentView":"list",
                     "lastAnalyzedText":"OLD-ANALYSIS-SECRET","storedBusinessText":"OLD-ANALYSIS-SECRET",
                     "scores":{"N1":80},"reasons":{"N1":"OLD-ANALYSIS-SECRET"},
                     "analysisOptions":{"maxNodes":20},"history":["HIDDEN-HISTORY"]}
                    """);
            fixture.draft("repo-a", "private-b", "bob", "{\"businessText\":\"PRIVATE-BOB\"}");
            fixture.draft("repo-b", "private-a", "alice", "{\"businessText\":\"OTHER-REPO\"}");
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(snapshot(BackupProfile.CURRENT_STATE,
                    new BackupScope.Workspace("repo-a", "private-a")), output);
            assertThat(output.text()).contains("SAVED-UNPUBLISHED-DRAFT", "maxNodes", NOW.toString(), "analysis.draft")
                    .doesNotContain("OLD-ANALYSIS-SECRET", "HIDDEN-HISTORY", "PRIVATE-BOB", "OTHER-REPO");
        }
    }

    static SnapshotContext snapshot(BackupProfile profile, BackupScope scope) {
        var keys = scope instanceof BackupScope.Installation
                ? Set.of(new BackupRepositoryKey("repo-a", "private-a"), new BackupRepositoryKey("repo-a", "private-b"))
                : scope.selectedRepositories();
        BackupTime time = profile == BackupProfile.SELECTED_VERSION
                ? new BackupTime.SelectedVersion(keys.stream().collect(java.util.stream.Collectors.toMap(k -> k, k -> COMMIT)))
                : profile.includesHistory() ? new BackupTime.History() : new BackupTime.Current();
        var request = new BackupRequest(profile, scope, time,
                profile.includesHistory() ? GitRepresentation.BUNDLE : GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var authorization = new AuthorizedBackupRequest(request, PrincipalId.create(), "test-decision", NOW,
                EnumSet.allOf(BackupCapability.class));
        var repositories = new HashMap<BackupRepositoryKey, SnapshotContext.RepositoryState>();
        for (var key : keys) repositories.put(key, new SnapshotContext.RepositoryState(Map.of("refs/heads/draft", COMMIT),
                "refs/heads/draft", Map.of("draft", new SnapshotContext.WorkingState(2, COMMIT, 1)), Set.of(COMMIT)));
        return new SnapshotContext(BackupId.create(), authorization, NOW, NOW, 1, repositories, Map.of());
    }

    static final class Contents implements ComponentSink {
        final Map<String, byte[]> entries = new TreeMap<>();
        @Override public BackupEntry write(String path, java.io.InputStream input) throws java.io.IOException {
            byte[] value = input.readAllBytes();
            assertThat(entries.putIfAbsent(path, value)).as("entry may only be written once").isNull();
            try { return new BackupEntry(path, value.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        String text() { return entries.values().stream().map(b -> new String(b, StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("\n")); }
    }

    static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource();
        final org.hibernate.SessionFactory factory;
        final JdbcTemplate jdbc;
        Fixture(Class<?>... additionalEntities) {
            this(configuration -> { }, additionalEntities);
        }
        Fixture(java.util.function.Consumer<Configuration> customize, Class<?>... additionalEntities) {
            database.setUrl("jdbc:hsqldb:mem:current-export-" + UUID.randomUUID()); database.setUser("sa");
            var configuration = new Configuration().setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.search.enabled", "false");
            configuration.getProperties().put("hibernate.connection.datasource", database);
            for (var type : List.of(EditorWorkspace.class, EditorOperation.class, EditorCheckpoint.class,
                    SystemRepository.class, UserWorkspace.class, RepositoryMembership.class, SyncState.class,
                    com.taxonomy.versioning.model.ArchitectureCommitIndex.class, com.taxonomy.versioning.model.ContextHistoryRecord.class,
                    AnalysisWorkingDraft.class, AnalysisContinuationRun.class, AnalysisQuestionCheckpoint.class,
                    com.taxonomy.analysis.cluster.ClusterAnalysisRun.class, com.taxonomy.analysis.cluster.ClusterAnalysisWork.class,
                    com.taxonomy.analysis.cluster.ClusterAnalysisInput.class, com.taxonomy.analysis.cluster.ClusterAnalysisEvent.class))
                configuration.addAnnotatedClass(type);
            try (var inventory = CurrentStateExportIT.class.getResourceAsStream("/backup/coverage-inventory.tsv")) {
                for (String line : new String(Objects.requireNonNull(inventory).readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    String category = line.split("\t")[0];
                    if (category.startsWith("com.taxonomy.portfolio.")) configuration.addAnnotatedClass(Class.forName(category));
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
            for (Class<?> type : additionalEntities) configuration.addAnnotatedClass(type);
            customize.accept(configuration);
            factory = configuration.buildSessionFactory(); jdbc = new JdbcTemplate(database);
        }
        void editor(String id, String repository, String workspace, String text) {
            jdbc.update("insert into editor_workspace(scope_id,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,row_version) values(?,?,?,?,?,2,?,1,0)",
                    id, repository, workspace, "draft", "1:" + text, COMMIT);
        }
        void operation(String scope, String before) {
            jdbc.update("insert into editor_operation(id,scope_id,command_id,actor,occurred_at,rationale,correlation_id,causation_id,kind,previous_revision,semantic_revision,fingerprint,body_version,before_dsl,after_dsl,affected_ids) values(?,?,?,?,?,?,?,?,?,1,2,?,1,?,?,?)",
                    UUID.randomUUID().toString(), scope, UUID.randomUUID().toString(), "alice", NOW.toString(), "Edit", "correlation", "causation", "UPDATE", "b".repeat(64), "1:" + before, "1:CURRENT-A", "1:arch-current");
        }
        void draft(String repository, String workspace, String user, String payload) {
            String scope = new RepositoryTenantIdentity(repository, "WORKSPACE:" + workspace, "draft").scopeKey();
            try (var em = factory.createEntityManager()) {
                var tx=em.getTransaction(); tx.begin();
                em.persist(new AnalysisWorkingDraft(scope,workspace,user,payload,NOW)); tx.commit();
            }
        }
        void requirement(String repository, String workspace, String before, String current) {
            try (var em = factory.createEntityManager()) {
                var tx = em.getTransaction(); tx.begin();
                String scope = new RepositoryTenantIdentity(repository,"WORKSPACE:"+workspace,"draft").scopeKey();
                var project = new ArchitectureProject(scope,workspace,"alice","P-SAME","Project",null,ProjectStatus.ACTIVE,NOW);
                em.persist(project); em.flush();
                var requirement = new ProjectRequirement(project,"R-SAME","Requirement",RequirementStatus.DRAFT,50,Criticality.MEDIUM,RequirementType.FUNCTIONAL,ReviewStatus.PROPOSED,"alice",NOW);
                em.persist(requirement); em.flush();
                var old = new ProjectRequirementVersion(requirement,1,before,com.taxonomy.identity.StableIdentityHash.sha256(before),null,"alice",NOW,null,null,null,null,null,null);
                em.persist(old); em.flush();
                var version = new ProjectRequirementVersion(requirement,2,current,com.taxonomy.identity.StableIdentityHash.sha256(current),before,"alice",NOW,null,null,null,null,null,before);
                em.persist(version); em.flush(); requirement.pointToVersion(version.getId(),NOW);
                var job = new RequirementAnalysisJob(UUID.randomUUID().toString(),project,null,"LOCAL",30,"alice",workspace,1,NOW);
                em.persist(job); em.flush();
                var snapshot = new RequirementAnalysisSnapshot(UUID.randomUUID().toString(),project,requirement,version,job,AnalysisStatus.SUCCESS,UUID.randomUUID().toString(),"LOCAL",null,null,null,workspace,"draft",COMMIT,"alice",NOW,10,0,null,"{\"current\":true}",null,null,null);
                em.persist(snapshot); em.flush(); requirement.pointToAnalysis(snapshot.getId(),NOW);
                var decision = new RequirementElementMapping(snapshot,"N-SAME","Node","T",80,0.8,0.9,MappingOrigin.MANUAL,"N-SAME","Relevant",true);
                decision.review(ReviewStatus.CONFIRMED,ActionStatus.REUSE,"CURRENT-DECISION","alice",null,NOW);
                em.persist(decision); tx.commit();
            }
        }
        @Override public void close() { factory.close(); }
    }
}
