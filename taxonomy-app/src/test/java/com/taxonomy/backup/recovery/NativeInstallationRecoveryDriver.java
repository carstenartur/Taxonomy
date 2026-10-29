package com.taxonomy.backup.recovery;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.editor.*;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.model.SourceType;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.provenance.repository.SourceVersionRepository;
import com.taxonomy.provenance.service.SourceProvenanceService;
import com.taxonomy.reporting.templates.DecisionRationaleTemplateContract;
import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.templates.DocumentTemplateService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.storage.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Three normal application JVMs share only recovered on-disk data and an external assertion oracle. */
public final class NativeInstallationRecoveryDriver {
    private static final String ACTOR = "cold-recovery";
    private static final String TEXT = "Retain original source evidence and review every deployment decision. Ünicode.\n";
    private static final String CREATE = "00000000-0000-0000-0000-000000000101", UPDATE = "00000000-0000-0000-0000-000000000102";
    private static final String DRAFT = "00000000-0000-0000-0000-000000000103", UNDO = "00000000-0000-0000-0000-000000000104", REDO = "00000000-0000-0000-0000-000000000105";

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]), oracle = Path.of(args[1]); String phase = args[2];
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--spring.profiles.active=hsqldb-file", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=jdbc:hsqldb:file:" + data.resolve("taxonomydb") + ";hsqldb.default_table_type=cached;hsqldb.write_delay_millis=0;shutdown=true",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.properties.hibernate.search.backend.directory.type=local-filesystem",
                "--spring.jpa.properties.hibernate.search.backend.directory.root=" + data.resolve("lucene-index"),
                "--taxonomy.backup.source-content-directory=" + data.resolve("source-content"),
                "--taxonomy.backup.directory=" + data.resolve("backups"), "--taxonomy.backup.enabled=false",
                "--embedding.enabled=false", "--embedding.allow-download=false", "--taxonomy.init.async=false", "--taxonomy.init.reload-existing=false",
                "--llm.mock=true", "--llm.provider=GEMINI", "--llm.replay=false", "--llm.record=false",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=",
                "--custom.llm.url=", "--custom.llm.api.key=", "--taxonomy.analysis.transport.mode=local", "--taxonomy.analysis.artemis.broker-url=",
                "--taxonomy.admin-password=" + UUID.randomUUID())) {
            assertThat(app.getBean(LlmProviderConfig.class).isMockMode()).isTrue();
            Properties expected = new Properties();
            if (phase.equals("seed")) {
                seed(app, data, expected);
                try (var output = Files.newOutputStream(oracle, StandardOpenOption.CREATE_NEW)) { expected.store(output, "Native recovery assertion oracle; no credentials"); }
            } else {
                try (var input = Files.newInputStream(oracle)) { expected.load(input); }
                verify(app, data, expected, phase);
            }
            var jdbc = app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from req_analysis_job where status <> 'SUCCESS'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from analysis_dispatch_intent", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from interop_operation", Long.class)).isZero();
        }
        System.out.println("NATIVE_INSTALLATION_RECOVERY_OK " + phase);
    }

    private static void seed(ConfigurableApplicationContext app, Path data, Properties expected) throws Exception {
        var repository = app.getBean(ArchitectureRepositoryProvisioningService.class).createRepository("Cold recovery", ACTOR, "", RepositoryVisibility.PRIVATE, ACTOR, "draft");
        var workspace = app.getBean(RepositoryWorkspaceService.class).createWorkingCopy(ACTOR, repository.getRepositoryId(), "draft", "Cold recovery", "");
        expected.setProperty("repository", repository.getRepositoryId()); expected.setProperty("workspace", workspace.getWorkspaceId());
        expected.setProperty("applicationVersion", app.getBean(BuildProperties.class).getVersion());
        var context = context(expected); var scope = scope(expected);
        assertThat(app.getBean(TaxonomyService.class).getRootCodes()).contains("CP");
        var source = app.getBean(SourceProvenanceService.class);
        var artifact = source.createArtifact(SourceType.UPLOADED_DOCUMENT, "Recovery requirement source");
        var version = source.createVersion(artifact, "text/plain", hash(TEXT.getBytes(StandardCharsets.UTF_8)));
        Files.createDirectories(data.resolve("source-content")); Files.writeString(data.resolve("source-content/requirements.txt"), TEXT);
        version.setStorageLocation("requirements.txt"); app.getBean(SourceVersionRepository.class).save(version);
        var fragment = source.createFragment(version, TEXT, "§1", 1);
        var reference = new SourceReference(artifact.getId(), version.getId(), List.of(fragment.getId()), "§1", 1, TEXT);
        expected.setProperty("sourceVersion", version.getId().toString());
        var projects = app.getBean(ProjectPortfolioService.class);
        var project = projects.createProject(new CreateProjectRequest("COLD-RECOVERY", "Recovery portfolio", "Native installation evidence", ProjectStatus.ACTIVE, null, null, null, null), ACTOR, context);
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("REC-1", "Retain evidence", "Initial requirement", RequirementStatus.APPROVED, 80, Criticality.HIGH, RequirementType.TECHNICAL, ReviewStatus.CONFIRMED, ACTOR, "Original", reference), ACTOR, context);
        projects.addRequirementVersion(project.id(), requirement.id(), new CreateRequirementVersionRequest(TEXT, "Reviewed source", reference), ACTOR, context);
        expected.setProperty("project", project.id().toString()); expected.setProperty("requirement", requirement.id().toString());
        var persistence = app.getBean(PortfolioAnalysisPersistenceService.class);
        var job = persistence.createOrReuseJob(project.id(), List.of(requirement.id()), "GEMINI", 8, "cold-recovery-saved-fixture", ACTOR, context);
        String tenant = PortfolioScope.key(ACTOR, context);
        persistence.markJobRunning(job.id(), project.id(), tenant);
        persistence.markItemRunning(job.items().getFirst().id(), job.id(), project.id(), tenant);
        var analysis = new AnalysisResult(Map.of("CP", 80), List.of()); analysis.setStatus("SUCCESS");
        String snapshotId = UUID.randomUUID().toString();
        persistence.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), tenant, snapshotId, "cold-recovery-fixture", analysis,
                new GapAnalysisView(), new PatternDetectionView(), new ArchitectureRecommendation(), "GEMINI", "stored-fixture-no-inference", "fixture", "fixture", ACTOR, context, 0);
        persistence.completeJob(job.id(), project.id(), tenant);
        expected.setProperty("snapshot", snapshotId);
        expected.setProperty("analysisSha256", hash(app.getBean(RequirementAnalysisSnapshotRepository.class).findById(snapshotId).orElseThrow().getAnalysisPayload().getBytes(StandardCharsets.UTF_8)));

        var solutions = app.getBean(SolutionPortfolioService.class);
        var solution = solutions.createSolution(new CreateSolutionRequest("REC-SOLUTION", "Recovery service", "Reviewed decision", SolutionType.SERVICE, OperatingModel.HYBRID, LifecycleStatus.ACTIVE, 4, "Recovery team", null, null, null, 30, Map.of("source", "reviewed fixture")), ACTOR, context);
        solutions.upsertTaxonomyCoverage(solution.id(), new UpsertTaxonomyCoverageRequest("CP", 100, "Reviewed actual root", ReviewStatus.CONFIRMED), ACTOR, context);
        var selected = solutions.addProjectSolution(project.id(), new AddProjectSolutionRequest(solution.id(), ProjectSolutionStatus.SELECTED, ActionStatus.REUSE, 90, "Keep original evidence"), ACTOR, context);
        solutions.linkRequirement(project.id(), selected.id(), new LinkRequirementSolutionRequest(requirement.id(), snapshotId, 90, RequirementSolutionRole.USES, ReviewStatus.CONFIRMED, "Source §1"), ACTOR, context);
        var products = app.getBean(ProductCatalogService.class);
        var product = products.createProduct(new CreateProductRequest("REC-PRODUCT", "Fixture supplier", "Recovery", "Evidence store", "1", ProductStatus.ACTIVE, null, "Fixture", OperatingModel.HYBRID, "Linux", "Local", "Fixture", null, null, null, "Source §1", Instant.parse("2026-10-01T00:00:00Z")), ACTOR, context);
        products.upsertCandidate(project.id(), selected.id(), new UpsertProductCandidateRequest(product.id(), 90, null, "Evidence retained", null, "Deployment review", .9, ReviewStatus.CONFIRMED, ProductSelectionStatus.SELECTED), ACTOR, context);

        var editor = app.getBean(ArchitectureEditorService.class); var git = app.getBean(DslGitRepositoryFactory.class).resolveRepository(scope);
        var created = editor.execute(scope, new Command(editor.read(scope, null).context(), metadata(CREATE), new SemanticCommand(new CreateArchitectureElement("recovery-system", "System", Map.of("title", "Original")))));
        var first = editor.checkpoint(scope, new CreateCheckpointCommand(created.context(), metadata(UUID.randomUUID().toString())));
        var changed = editor.execute(scope, new Command(editor.read(scope, null).context(), metadata(UPDATE), new SemanticCommand(new UpdateArchitectureElement("recovery-system", "System", Map.of("title", "Checkpointed")))));
        var second = editor.checkpoint(scope, new CreateCheckpointCommand(changed.context(), metadata(UUID.randomUUID().toString())));
        git.createBranchAtCommit("recovery-baseline", first.commitId());
        editor.execute(scope, new Command(editor.read(scope, null).context(), metadata(DRAFT), new SemanticCommand(new UpdateArchitectureElement("recovery-system", "System", Map.of("title", "Uncheckpointed")))));
        expected.setProperty("firstCommit", first.commitId()); expected.setProperty("secondCommit", second.commitId());
        var history = git.getDslHistory("draft").stream().map(DslCommit::commitId).toList();
        assertThat(history).startsWith(second.commitId(), first.commitId());
        expected.setProperty("gitHistory", String.join(",", history));
        expected.setProperty("draftDsl", editor.read(scope, null).dsl());
        app.getBean(PreferencesService.class).update(Map.of("analysis.reason-length", "detailed"), ACTOR);
        expected.setProperty("preferencesCommit", app.getBean(PreferencesService.class).getHistory().getFirst().commitId());
        var templates = app.getBean(DocumentTemplateService.class);
        var original = templates.downloadCurrentValidated(DecisionRationaleTemplateContract.TEMPLATE_ID);
        templates.upload("cold-recovery-template", "Recovery template", new ByteArrayInputStream(original.content()), null, ACTOR, "Retain operator template");
        var template = templates.downloadCurrentValidated("cold-recovery-template");
        expected.setProperty("templateCommit", template.commitId()); expected.setProperty("templateSha256", hash(template.content()));
        assertSearch(app);
    }

    private static void verify(ConfigurableApplicationContext app, Path data, Properties expected, String phase) throws Exception {
        assertThat(app.getBean(BuildProperties.class).getVersion()).isEqualTo(expected.getProperty("applicationVersion"));
        var context = context(expected); var scope = scope(expected); long project = Long.parseLong(expected.getProperty("project")), requirement = Long.parseLong(expected.getProperty("requirement"));
        var projects = app.getBean(ProjectPortfolioService.class);
        assertThat(projects.getProject(project, ACTOR, context).title()).isEqualTo("Recovery portfolio");
        var versions = projects.listRequirementVersions(project, requirement, ACTOR, context);
        // Requirement entry intentionally strips surrounding whitespace; retained source bytes do not.
        assertThat(versions).hasSize(2); assertThat(versions).extracting(RequirementVersionView::text).containsExactly(TEXT.strip(), "Initial requirement");
        assertThat(projects.getRequirement(project, requirement, ACTOR, context).currentVersion().source().sourceVersionId()).isEqualTo(Long.parseLong(expected.getProperty("sourceVersion")));
        String snapshotId = expected.getProperty("snapshot");
        var detail = app.getBean(ProjectRequirementAnalysisService.class).getSnapshot(project, snapshotId, ACTOR, context);
        assertThat(detail.analysis().getScores()).containsEntry("CP", 80d);
        assertThat(hash(app.getBean(RequirementAnalysisSnapshotRepository.class).findById(snapshotId).orElseThrow().getAnalysisPayload().getBytes(StandardCharsets.UTF_8))).isEqualTo(expected.getProperty("analysisSha256"));
        var portfolio = app.getBean(PortfolioAggregationService.class).build(project, ACTOR, context);
        assertThat(portfolio.metrics().selectedProducts()).isEqualTo(1);
        assertThat(portfolio.solutions()).singleElement().satisfies(solution -> {
            assertThat(solution.actionStatus()).isEqualTo(ActionStatus.REUSE);
            assertThat(solution.requirements()).singleElement().satisfies(link -> { assertThat(link.requirementId()).isEqualTo(requirement); assertThat(link.snapshotId()).isEqualTo(snapshotId); assertThat(link.evidence()).isEqualTo("Source §1"); });
            assertThat(solution.productCandidates()).singleElement().satisfies(product -> { assertThat(product.selectionStatus()).isEqualTo(ProductSelectionStatus.SELECTED); assertThat(product.product().productKey()).isEqualTo("REC-PRODUCT"); });
        });
        var sourceVersion = app.getBean(SourceProvenanceService.class).findVersionById(Long.parseLong(expected.getProperty("sourceVersion"))).orElseThrow();
        assertThat(Files.readString(data.resolve("source-content").resolve(sourceVersion.getStorageLocation()))).isEqualTo(TEXT);
        assertThat(sourceVersion.getContentHash()).isEqualTo(hash(TEXT.getBytes(StandardCharsets.UTF_8)));
        var preferences = app.getBean(PreferencesService.class);
        assertThat(preferences.get("analysis.reason-length")).isEqualTo("detailed");
        assertThat(preferences.getHistory().getFirst().commitId()).isEqualTo(expected.getProperty("preferencesCommit"));
        var template = app.getBean(DocumentTemplateService.class).downloadCurrentValidated("cold-recovery-template");
        assertThat(template.commitId()).isEqualTo(expected.getProperty("templateCommit")); assertThat(hash(template.content())).isEqualTo(expected.getProperty("templateSha256"));
        var git = app.getBean(DslGitRepositoryFactory.class).resolveRepository(scope);
        assertThat(git.getDslHistory("draft")).extracting(DslCommit::commitId).containsExactly(expected.getProperty("gitHistory").split(","));
        assertThat(git.getHeadCommit("recovery-baseline")).isEqualTo(expected.getProperty("firstCommit"));
        assertThat(git.getDslAtCommit(expected.getProperty("firstCommit"))).contains("Original");
        assertThat(git.getDslAtCommit(expected.getProperty("secondCommit"))).contains("Checkpointed");
        var editor = app.getBean(ArchitectureEditorService.class); var current = editor.read(scope, null);
        if (phase.equals("undo")) {
            assertThat(current.dsl()).isEqualTo(expected.getProperty("draftDsl"));
            editor.execute(scope, new Command(current.context(), metadata(UNDO), new UndoArchitectureCommand(UUID.fromString(DRAFT))));
            assertThat(editor.read(scope, null).dsl()).contains("Checkpointed").doesNotContain("Uncheckpointed");
        } else {
            assertThat(current.dsl()).contains("Checkpointed").doesNotContain("Uncheckpointed");
            editor.execute(scope, new Command(current.context(), metadata(REDO), new RedoArchitectureCommand(UUID.fromString(UNDO))));
            var restored = editor.read(scope, null); assertThat(restored.dsl()).isEqualTo(expected.getProperty("draftDsl"));
            assertThat(restored.history()).extracting(ArchitectureEditorService.HistoryEntry::operationId).contains(REDO, UNDO, DRAFT, UPDATE, CREATE);
            assertThat(editor.rebuild(scope, restored.context())).isEqualTo("READY");
            assertThat(app.getBean(ArchitectureEditorProjection.class).project(restored, true).searchIndex()).extracting(ArchitectureEditorProjection.SearchEntry::text).anyMatch(text -> text.contains("uncheckpointed"));
        }
        assertThat(app.getBean(EditorJournal.class).read(scope).state().pendingCheckpoint()).isNull(); assertSearch(app);
    }

    private static void assertSearch(ConfigurableApplicationContext app) {
        assertThat(app.getBean(SearchFacade.class).fullTextSearch("Capabilities", 20)).extracting(TaxonomyNodeDto::getCode).contains("CP");
    }
    private static WorkspaceContext context(Properties values) { return new WorkspaceContext(ACTOR, values.getProperty("workspace"), "draft", values.getProperty("repository")); }
    private static RepositoryContext scope(Properties values) { return RepositoryContext.workspace(values.getProperty("repository"), values.getProperty("workspace"), "draft", ACTOR); }
    private static Metadata metadata(String id) { return new Metadata(id, id, id, "Native recovery evidence"); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
