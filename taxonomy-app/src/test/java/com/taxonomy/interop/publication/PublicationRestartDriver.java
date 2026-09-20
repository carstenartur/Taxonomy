package com.taxonomy.interop.publication;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Full application processes share only durable database, Git, provider file and captured scope identifiers.
 */
public final class PublicationRestartDriver {

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        String phase = args[1];
        String url = "jdbc:hsqldb:file:" + directory.resolve("publication-database").toAbsolutePath() + ";shutdown=true;hsqldb.write_delay=false";
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class, PublicationIntegrationFixture.Configuration.class).run("--server.port=0", "--spring.datasource.url=" + url, "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver", "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap", "--embedding.enabled=false", "--taxonomy.init.async=false", "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            var fixture = new PublicationIntegrationFixture() {
            };
            fixture.directory = directory;
            for (var field : PublicationIntegrationFixture.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    field.setAccessible(true);
                    field.set(fixture, app.getBean(field.getType()));
                }
            }
            try {
                Path captured = directory.resolve("publication-scope.properties");
                var evidence = new Properties();
                PublicationReview review;
                if (phase.equals("unknown")) {
                    fixture.setup();
                    fixture.provider.seed(new ExchangeDocument(PublicationContractProvider.PROFILE, "1", null, true, "", List.of(), List.of(), List.of(), Map.of("identifier", PublicationContractProvider.SCOPE.rootResource()), List.of()));
                    fixture.edit(new CreateArchitecturePackage("restart-package", Map.of("title", "Restart package")), new CreateArchitectureElement("restart-element", "System", Map.of("title", "Restart element")), new SetArchitecturePackagePlacements(List.of(new PackagePlacement(PackageMemberKind.ELEMENT, "restart-element", "restart-package", 0)), Set.of("restart-package")));
                    review = fixture.review(fixture.preview(PublicationMode.PUSH));
                    fixture.provider.closeAt = 1;
                    var unknown = fixture.publication.publish(fixture.context, fixture.connection, review);
                    assertEquals(ItemState.UNKNOWN, unknown.items().getFirst().state());
                    assertNull(unknown.commonCheckpointId());
                    assertEquals(1, fixture.provider.writes.get());
                    assertTrue(fixture.store.identities(fixture.context, fixture.connection).isEmpty());
                    evidence.setProperty("repository", fixture.context.repositoryId());
                    evidence.setProperty("workspace", fixture.context.workspaceId());
                    evidence.setProperty("branch", fixture.context.branch());
                    evidence.setProperty("actor", fixture.context.username());
                    evidence.setProperty("connection", fixture.connection.toString());
                    evidence.setProperty("review", fixture.json.write(review));
                    evidence.setProperty("commits", Long.toString(fixture.git.resolveRepository(fixture.context).getCommitCount(fixture.context.branch())));
                    evidence.setProperty("operations", Integer.toString(fixture.journal.read(fixture.context).operations().size()));
                    try (var output = Files.newOutputStream(captured)) {
                        evidence.store(output, "Publication durable scope");
                    }
                } else {
                    assertEquals("recover", phase);
                    try (var input = Files.newInputStream(captured)) {
                        evidence.load(input);
                    }
                    fixture.context = RepositoryContext.workspace(evidence.getProperty("repository"), evidence.getProperty("workspace"), evidence.getProperty("branch"), evidence.getProperty("actor"));
                    fixture.connection = UUID.fromString(evidence.getProperty("connection"));
                    fixture.provider = new PublicationContractProvider(directory.resolve("provider.json"));
                    fixture.connector.provider = fixture.provider;
                    review = fixture.json.read(evidence.getProperty("review"), PublicationReview.class);
                    var id = review.review().operationId();
                    assertEquals(ItemState.UNKNOWN, fixture.publication.publication(fixture.context, fixture.connection, id).items().getFirst().state());
                    var completed = fixture.publication.retryPublication(fixture.context, fixture.connection, id);
                    assertEquals(PublicationPhase.COMPLETED, completed.phase(), completed.failureCode());
                    assertEquals(1, fixture.provider.lookups.get());
                    assertEquals(completed.items().size() - 1, fixture.provider.writes.get());
                    completed.items().forEach(item -> assertEquals(1, fixture.provider.mutationCount(item.resourceId())));
                    assertEquals(Long.parseLong(evidence.getProperty("commits")), fixture.git.resolveRepository(fixture.context).getCommitCount(fixture.context.branch()));
                    assertEquals(Integer.parseInt(evidence.getProperty("operations")), fixture.journal.read(fixture.context).operations().size());
                    assertEquals(completed, fixture.publication.publish(fixture.context, fixture.connection, review));
                    assertEquals(fixture.editor.read(fixture.context, null).dsl(), fixture.git.resolveRepository(fixture.context).getDslAtHead(fixture.context.branch()));
                }
            } finally {
                if (fixture.provider != null) {
                    fixture.close();
                }
            }
            System.out.println("PUBLICATION_PROCESS_RESTART_OK " + phase);
        }
    }
}
