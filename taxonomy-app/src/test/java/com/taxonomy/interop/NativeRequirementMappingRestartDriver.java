package com.taxonomy.interop;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationService.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Two complete application JVMs share only persisted HSQL, Git and the captured scope identifiers. */
public final class NativeRequirementMappingRestartDriver {
    private static final String ACTOR = "native-restart";
    private static final String PROJECT_KEY = "NATIVE-RESTART";
    private static final String ROOT = "{00000000-0000-4000-8000-000000000000}";
    private static final String PACKAGE = "{33333333-3333-4333-8333-333333333333}";
    private static final String ELEMENT = "{11111111-1111-4111-8111-111111111111}";
    private static final String REQUIREMENT = "{55555555-5555-4555-8555-555555555555}";
    private static final String CONNECTOR = "{66666666-6666-4666-8666-666666666666}";
    private static final UUID CONNECTION = UUID.fromString("1502e67c-2941-4991-92ef-000000000011");
    private static final UUID OPERATION = UUID.fromString("1502e67c-2941-4991-92ef-000000000012");

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path captured = directory.resolve("native-scope.properties");
        String url = "jdbc:hsqldb:file:" + directory.resolve("native-database").toAbsolutePath()
                + ";shutdown=true;hsqldb.write_delay=false";
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=SA", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            var integrations = app.getBean(IntegrationService.class);
            var projects = app.getBean(ProjectPortfolioService.class);
            var editor = app.getBean(ArchitectureEditorService.class);
            var journal = app.getBean(EditorJournal.class);
            Properties evidence = new Properties();
            RepositoryContext scope;
            if ("apply".equals(args[1])) {
                var repository = app.getBean(ArchitectureRepositoryProvisioningService.class)
                        .createRepository("Native restart", ACTOR, "", RepositoryVisibility.PRIVATE, ACTOR, "draft");
                var workspace = app.getBean(RepositoryWorkspaceService.class)
                        .createWorkingCopy(ACTOR, repository.getRepositoryId(), "draft", "Native restart", "");
                scope = RepositoryContext.workspace(repository.getRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), ACTOR);
                Long project = projects.createProject(new CreateProjectRequest(PROJECT_KEY, "Restart requirements", null,
                        ProjectStatus.ACTIVE, null, null, null, null), ACTOR, IntegrationDomainAdapter.workspace(scope)).id();
                integrations.create(scope, new CreateConnection(CONNECTION, "Native v2 restart", SparxMappingProfile.PROFILE,
                        AuthorityMode.BIDIRECTIONAL, new ExternalScope("Synthetic contract", "native-restart", null), project, null, "2"));
                var preview = integrations.preview(scope, CONNECTION, new PreviewRequest(OPERATION,
                        integrations.overview(scope, CONNECTION).current(), "application/xmi+xml", true), fixture());
                var endpoints = integrations.endpointOptions(scope, CONNECTION, OPERATION);
                String requirementIdentity = endpoints.external().get(REQUIREMENT).businessIdentity();
                String elementIdentity = endpoints.external().get(ELEMENT).businessIdentity();
                String change = preview.changes().stream().filter(c -> c.externalId().equals("RELATION:" + CONNECTOR))
                        .findFirst().orElseThrow().id();
                Map<String, Decision> decisions = new TreeMap<>();
                preview.changes().forEach(c -> decisions.put(c.id(), Decision.ACCEPT));
                var review = new ReviewedChangeSet(OPERATION, preview.fingerprint(), decisions, "Reviewed native restart",
                        Map.of(), Map.of(change, new EndpointOverride(requirementIdentity, elementIdentity,
                        RelationProjection.REQUIREMENT_MAPPING, null)));
                assertEquals(OperationStatus.COMPLETED, integrations.apply(scope, CONNECTION, review).status());
                evidence.setProperty("repository", scope.repositoryId());
                evidence.setProperty("workspace", workspace.getWorkspaceId());
                evidence.setProperty("branch", scope.branch());
                evidence.setProperty("project", project.toString());
                evidence.setProperty("requirementIdentity", requirementIdentity);
                evidence.setProperty("elementIdentity", elementIdentity);
            } else {
                assertEquals("retry", args[1]);
                try (var input = Files.newInputStream(captured)) {
                    evidence.load(input);
                }
                scope = RepositoryContext.workspace(evidence.getProperty("repository"), evidence.getProperty("workspace"),
                        evidence.getProperty("branch"), ACTOR);
            }
            var versions = app.getBean(DslGitRepositoryFactory.class).resolveRepository(scope);
            var operation = integrations.operation(scope, CONNECTION, OPERATION);
            assertEquals(OperationStatus.COMPLETED, operation.status());
            assertNotNull(operation.review());
            String dsl = editor.read(scope, null).dsl();
            assertEquals(dsl, versions.getDslAtHead(scope.branch()));
            var blocks = ArchitectureSemanticPatch.index(dsl);
            String requirementIdentity = evidence.getProperty("requirementIdentity");
            String elementIdentity = evidence.getProperty("elementIdentity");
            var requirementBlock = blocks.get("requirement:" + requirementIdentity);
            assertNotNull(requirementBlock);
            assertEquals(PROJECT_KEY, requirementBlock.property("x-project-key"));
            assertNotNull(blocks.get("mapping:" + requirementIdentity + " -> " + elementIdentity));
            assertEquals(1, blocks.values().stream().filter(b -> b.getKind().equals("requirement")).count());
            assertEquals(1, blocks.values().stream().filter(b -> b.getKind().equals("mapping")).count());
            assertEquals(1, blocks.values().stream().filter(b -> b.getKind().equals("package")).count());
            var requirements = projects.listRequirements(Long.valueOf(evidence.getProperty("project")), ACTOR,
                    IntegrationDomainAdapter.workspace(scope));
            assertEquals(1, requirements.size());
            var requirement = requirements.getFirst();
            assertEquals(requirement.requirementKey(), requirementBlock.property("x-requirement-key"));
            assertEquals("The model shall survive restart.", requirement.currentVersion().text());
            var identities = integrations.identities(scope, CONNECTION);
            var requirementMapping = identities.stream().filter(i -> i.externalId().equals("REQUIREMENT:" + REQUIREMENT))
                    .findFirst().orElseThrow();
            assertEquals(requirement.id(), requirementMapping.requirementId());
            assertEquals(requirement.requirementKey(), requirementMapping.businessIdentity());
            var connectorMapping = identities.stream().filter(i -> i.externalId().equals("RELATION:" + CONNECTOR))
                    .findFirst().orElseThrow();
            assertEquals(requirementIdentity + " -> " + elementIdentity, connectorMapping.businessIdentity());
            assertEquals(1, journal.read(scope).operations().size());
            var entry = journal.operation(scope, journal.read(scope).operations().getFirst().commandId());
            assertEquals("VERSION_IMPORT", entry.kind());
            assertEquals(dsl, entry.afterDsl());
            long commits = versions.getCommitCount(scope.branch());
            assertTrue(commits > 0);
            if ("apply".equals(args[1])) {
                evidence.setProperty("requirementId", requirement.id().toString());
                evidence.setProperty("versionId", requirement.currentVersionId().toString());
                evidence.setProperty("gitCommit", versions.getHeadCommit(scope.branch()));
                evidence.setProperty("gitCount", Long.toString(commits));
                evidence.setProperty("identities", Integer.toString(identities.size()));
                try (var output = Files.newOutputStream(captured)) {
                    evidence.store(output, "Actual native apply recovery identities");
                }
            } else {
                assertEquals(evidence.getProperty("requirementId"), requirement.id().toString());
                assertEquals(evidence.getProperty("versionId"), requirement.currentVersionId().toString());
                assertEquals(evidence.getProperty("gitCommit"), versions.getHeadCommit(scope.branch()));
                assertEquals(Long.parseLong(evidence.getProperty("gitCount")), commits);
                assertEquals(Integer.parseInt(evidence.getProperty("identities")), identities.size());
                assertEquals(operation, integrations.retry(scope, CONNECTION, OPERATION));
                assertEquals(operation, integrations.apply(scope, CONNECTION, operation.review()));
                assertEquals(dsl, editor.read(scope, null).dsl());
                assertEquals(dsl, versions.getDslAtHead(scope.branch()));
                assertEquals(1, journal.read(scope).operations().size());
                assertEquals(commits, versions.getCommitCount(scope.branch()));
                assertEquals(evidence.getProperty("gitCommit"), versions.getHeadCommit(scope.branch()));
                assertEquals(identities, integrations.identities(scope, CONNECTION));
                assertEquals(requirements, projects.listRequirements(Long.valueOf(evidence.getProperty("project")), ACTOR,
                        IntegrationDomainAdapter.workspace(scope)));
            }
            System.out.println("NATIVE_RESTART_EVIDENCE phase=" + args[1] + " requirement=" + requirementIdentity
                    + " requirementId=" + requirement.id() + " mappings=1 journalOperations=1 gitCommits=" + commits
                    + " identities=" + identities.size() + " head=" + versions.getHeadCommit(scope.branch()));
        }
        System.out.println("NATIVE_REQUIREMENT_MAPPING_RESTART_OK " + args[1]);
    }

    private static byte[] fixture() {
        var artifacts = List.of(new Artifact(PACKAGE, ArtifactKind.SPECIFICATION, "Package", "Restart package", "", Map.of(), Map.of()),
                new Artifact(ELEMENT, ArtifactKind.ELEMENT, "Component", "Restart component", "", Map.of(), Map.of("canonicalType", "Component")),
                new Artifact(REQUIREMENT, ArtifactKind.REQUIREMENT, "Class", "Restart requirement", "The model shall survive restart.", Map.of(), Map.of()));
        var placements = List.of(new Placement("placement:" + PACKAGE, ROOT, null, PACKAGE, 0, Map.of()),
                new Placement("placement:" + ELEMENT, ROOT, "placement:" + PACKAGE, ELEMENT, 0, Map.of()),
                new Placement("placement:" + REQUIREMENT, ROOT, "placement:" + PACKAGE, REQUIREMENT, 1, Map.of()));
        var connector = new Relation(CONNECTOR, "Dependency", REQUIREMENT, ELEMENT, Map.of(),
                Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination"));
        return new SparxXmiCodec("2").write(new ExchangeDocument(SparxMappingProfile.PROFILE, "2", "native-restart-v1", true,
                "", artifacts, List.of(connector), placements, Map.of("identifier", ROOT, "title", "Synthetic restart contract"), List.of()));
    }
}
