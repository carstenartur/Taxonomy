package com.taxonomy.interop.publication;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only delegates stop AFTER real commits. The supervisor kills both independent JVMs. */
public final class PublicationBoundaryDriver {
    private static Path directory;
    private static String boundary;
    private static PublicationIntegrationFixture fixture;
    private static UUID operation;
    private static EntityManagerFactory factory;
    private static volatile boolean armed;

    public static void main(String[] args) throws Exception {
        directory = Path.of(args[0]); boundary = args[2];
        if (args[1].equals("provider")) { provider(args[3]); return; }
        String mode = args[3];
        long clockOffset = Long.parseLong(args[4]);
        String url = "jdbc:hsqldb:file:" + directory.resolve("database").toAbsolutePath() + ";shutdown=true;hsqldb.write_delay=false";
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class, PublicationIntegrationFixture.Configuration.class).run(
                "--server.port=0", "--spring.datasource.url=" + url, "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap", "--embedding.enabled=false", "--taxonomy.init.async=false",
                "--gemini.api.key=", "--openai.api.key=", "--deepseek.api.key=", "--qwen.api.key=", "--llama.api.key=", "--mistral.api.key=")) {
            fixture = new PublicationIntegrationFixture() {};
            fixture.directory = directory;
            for (var field : PublicationIntegrationFixture.class.getDeclaredFields()) if (field.isAnnotationPresent(Autowired.class)) {
                field.setAccessible(true); field.set(fixture, app.getBean(field.getType()));
            }
            factory = app.getBean(EntityManagerFactory.class);
            fixture.connector.beforeMutation = () -> { if (armed && boundary.equals("remote-commit-before-response")) capture(); };
            fixture.connector.endpoint(java.net.URI.create(Files.readString(directory.resolve("endpoint"))));
            fixture.connector.afterResponse = () -> { if (armed && boundary.equals("response-before-journal")) stop(); };
            var actual = fixture.editor;
            var port = (WorkspaceArchitectureIntegrationPort) Proxy.newProxyInstance(actual.getClass().getClassLoader(), new Class<?>[]{WorkspaceArchitectureIntegrationPort.class}, (proxy, method, values) -> {
                try {
                    Object result = method.invoke(actual, values);
                    if (armed && boundary.equals("git-before-ack") && method.getName().equals("checkpoint")) stop();
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            var local = new PublicationLocalAuthority(fixture.local.domain, port, app.getBean(SystemRepositoryService.class), app.getBean(RepositoryMembershipService.class), app.getBean(WorkspaceAccessService.class));
            fixture.store = new CommittedStore(app.getBean(EntityManagerFactory.class), app.getBean(PlatformTransactionManager.class), fixture.json, local);
            fixture.publication = new IntegrationPublicationService(fixture.store, fixture.registry, local, fixture.json, fixture.policy, Clock.offset(Clock.systemUTC(), Duration.ofSeconds(clockOffset)));
            Path stateFile = directory.resolve("scope.properties"); var state = new Properties();
            PublicationReview review;
            if (!Files.exists(stateFile)) {
                String actor = "boundary-" + UUID.randomUUID();
                var repo = fixture.repositories.createRepository("Boundary contract", actor, "", RepositoryVisibility.PRIVATE, actor, "draft");
                var workspace = fixture.workspaces.createWorkingCopy(actor, repo.getRepositoryId(), "draft", "Boundary", "");
                fixture.context = RepositoryContext.workspace(repo.getRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), actor);
                fixture.connection = fixture.integrations.create(fixture.context, new IntegrationService.CreateConnection(UUID.randomUUID(), "Independent JVM contract — TEST ONLY", PublicationContractProvider.PROFILE, AuthorityMode.BIDIRECTIONAL, PublicationContractProvider.SCOPE.externalScope(), null, null)).id();
                if (mode.equals("PUSH")) fixture.edit(new CreateArchitectureElement("restart-a", "System", Map.of("title", "Restart A")), new CreateArchitectureElement("restart-b", "System", Map.of("title", "Restart B")), new CreateArchitectureElement("restart-c", "System", Map.of("title", "Restart C")));
                var remote = fixture.connector.readPublicationScope(null, PublicationContractProvider.SCOPE, null);
                var preview = fixture.publication.previewPublication(fixture.context, fixture.connection, new PublicationPreviewRequest(UUID.randomUUID(), fixture.state(), PublicationMode.valueOf(mode), PublicationContractProvider.SCOPE, remote.revision()));
                var resolutions = new TreeMap<String, PublicationResolution>();
                preview.preview().changes().forEach(change -> resolutions.put(change.id(), mode.equals("PUSH") ? PublicationResolution.KEEP_LOCAL : PublicationResolution.TAKE_REMOTE));
                review = new PublicationReview(new ReviewedChangeSet(preview.operationId(), preview.preview().fingerprint(), Map.of(), "Explicit independent JVM boundary review"), resolutions);
                state.setProperty("repository", fixture.context.repositoryId()); state.setProperty("workspace", fixture.context.workspaceId()); state.setProperty("branch", fixture.context.branch()); state.setProperty("actor", actor);
                state.setProperty("connection", fixture.connection.toString()); state.setProperty("review", fixture.json.write(review));
                state.setProperty("initialCommits", Long.toString(commits())); state.setProperty("initialSemantic", Integer.toString(semantics()));
                try (var output = Files.newOutputStream(stateFile)) { state.store(output, "Test-only durable context"); }
            } else {
                try (var input = Files.newInputStream(stateFile)) { state.load(input); }
                fixture.context = RepositoryContext.workspace(state.getProperty("repository"), state.getProperty("workspace"), state.getProperty("branch"), state.getProperty("actor"));
                fixture.connection = UUID.fromString(state.getProperty("connection")); review = fixture.json.read(state.getProperty("review"), PublicationReview.class);
            }
            operation = review.review().operationId();
            armed = true;
            var before = fixture.publication.publication(fixture.context, fixture.connection, operation);
            var result = before.planFingerprint() == null ? fixture.publication.publish(fixture.context, fixture.connection, review) : fixture.publication.retryPublication(fixture.context, fixture.connection, operation);
            if (!boundary.equals("replay")) throw new AssertionError("Boundary not reached: " + boundary + " phase=" + result.phase() + " failure=" + result.failureCode());
            assertEquals(PublicationPhase.COMPLETED, result.phase()); assertNotNull(result.commonCheckpointId());
            assertEquals(result, fixture.publication.publish(fixture.context, fixture.connection, review));
            assertEquals(result, fixture.publication.retryPublication(fixture.context, fixture.connection, operation));
            assertEquals(Long.parseLong(state.getProperty("initialCommits")) + (mode.equals("PUSH") ? 0 : 1), commits());
            assertEquals(Integer.parseInt(state.getProperty("initialSemantic")) + (mode.equals("PUSH") ? 0 : 1), semantics());
            assertEquals(fixture.editor.read(fixture.context, null).dsl(), fixture.git.resolveRepository(fixture.context).getDslAtHead(fixture.context.branch()));
            capture();
            System.out.println("PUBLICATION_BOUNDARY_REPLAY_OK " + mode);
        }
    }
    private static void provider(String mode) throws Exception {
        boolean fresh = !Files.exists(directory.resolve("provider.json"));
        try (var provider = new PublicationContractProvider(directory.resolve("provider.json"))) {
            if (fresh) {
                var artifacts = mode.equals("PUSH") ? List.<Artifact>of() : List.of(new Artifact("remote-element", ArtifactKind.ELEMENT, "ApplicationComponent", "Remote typed element", "", Map.of(), Map.of("canonicalType", "System")));
                provider.seed(new ExchangeDocument(PublicationContractProvider.PROFILE, "1", null, true, "", artifacts, List.of(), List.of(), Map.of("identifier", PublicationContractProvider.SCOPE.rootResource()), List.of()));
            }
            provider.afterCommit = () -> { if (boundary.equals("remote-commit-before-response")) {
                write(directory.resolve(boundary + ".provider-marker"), Long.toString(ProcessHandle.current().pid())); block();
            }};
            write(directory.resolve("endpoint"), provider.base().toString());
            new CountDownLatch(1).await();
        }
    }
    private static final class CommittedStore extends IntegrationStore {
        private final ThreadLocal<Boolean> inspecting = ThreadLocal.withInitial(() -> false);
        CommittedStore(EntityManagerFactory factory, PlatformTransactionManager manager, IntegrationJson json, PublicationLocalAuthority local) { super(factory, manager, json, local); }
        @Override public <T> T locked(RepositoryContext context, UUID connection, Function<Session,T> action) {
            T value = super.locked(context, connection, action);
            if (!armed || inspecting.get() || TransactionSynchronizationManager.isActualTransactionActive()) return value;
            inspecting.set(true);
            try {
                var op = super.locked(context, connection, session -> session.publications().publication(operation));
                boolean reached = switch (boundary) {
                    case "plan-commit", "local-apply-before-git" -> op.phase() == PublicationPhase.LOCAL_CHECKPOINT_PENDING;
                    case "dispatch-intent" -> op.items().stream().anyMatch(item -> item.state() == ItemState.IN_FLIGHT);
                    case "partial-receipt" -> op.acknowledgedCount() == 1;
                    case "all-receipts-before-finalization" -> op.phase() == PublicationPhase.VERIFY_PENDING;
                    case "completed-commit" -> op.phase() == PublicationPhase.COMPLETED;
                    default -> false;
                };
                if (reached) stop();
            } finally { inspecting.set(false); }
            return value;
        }
    }
    private static void stop() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); capture(); block(); }
    private static void capture() {
        try {
            var op = fixture.publication.publication(fixture.context, fixture.connection, operation);
            var frozen = fixture.store.locked(fixture.context, fixture.connection, session -> session.publications().plan(operation));
            var data = new LinkedHashMap<String,Object>();
            data.put("boundary", boundary); data.put("clientPid", ProcessHandle.current().pid()); data.put("operation", op);
            data.put("gitCommits", commits()); data.put("semanticOperations", semantics());
            data.put("frozenItems", frozen == null ? List.of() : frozen.items());
            try (var em = factory.createEntityManager()) {
                var requests = new TreeMap<String,String>();
                for (Object row : em.createNativeQuery("select item_key,request_fingerprint from interop_publish_item where operation_id=?1 order by item_ordinal").setParameter(1, operation.toString()).getResultList()) {
                    Object[] columns = (Object[]) row;
                    if (columns[1] != null) requests.put((String) columns[0], (String) columns[1]);
                }
                data.put("dispatchRequestFingerprints", requests);
            }
            write(directory.resolve(boundary + ".client-marker"), fixture.json.write(data));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static long commits() throws Exception { return fixture.git.resolveRepository(fixture.context).getCommitCount(fixture.context.branch()); }
    private static int semantics() { var journal = fixture.journal.read(fixture.context); return journal == null ? 0 : journal.operations().size(); }
    private static void write(Path file, String value) {
        try { Path temporary = file.resolveSibling(file.getFileName() + ".tmp"); Files.writeString(temporary, value); Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void block() { try { new CountDownLatch(1).await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); } }
}
