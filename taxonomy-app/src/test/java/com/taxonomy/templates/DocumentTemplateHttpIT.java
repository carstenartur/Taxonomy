package com.taxonomy.templates;

import com.taxonomy.TaxonomyApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Uses the actual server, security filters, MVC advice and Git-backed template storage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("hsqldb")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentTemplateHttpIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Map<String, String> NODE_PROPERTIES = Map.of(
            "server.address", "127.0.0.1",
            "server.servlet.context-path", "/taxonomy",
            "spring.jpa.hibernate.ddl-auto", "update",
            "embedding.enabled", "false",
            "llm.mock", "true",
            "taxonomy.init.async", "false",
            "spring.jpa.properties.hibernate.search.backend.directory.type", "local-heap");

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void nodeProperties(DynamicPropertyRegistry properties) {
        NODE_PROPERTIES.forEach((name, value) -> properties.add(name, () -> value));
        properties.add("spring.datasource.url", DocumentTemplateHttpIT::databaseUrl);
        properties.add("taxonomy.admin-password", () -> PASSWORD);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DocumentTemplateGitRepository repository;

    private DocumentTemplateHttpContract contract;

    @BeforeEach
    void openClient() {
        contract = client(port);
    }

    @AfterEach
    void closeClient() {
        if (contract != null) contract.close();
    }

    @Test
    void webDavDiscoverLockRefreshSaveAndUnlock() throws Exception {
        contract.webDavRoundTrip();
    }

    @Test
    void templateApiPreservesDomainErrorsAndConcurrentHistory() throws Exception {
        contract.apiConflictAndHistory();
    }

    @Test
    void extendedMethodsCannotEscapeTheTemplateCollection() throws Exception {
        contract.methodAndPathRestrictions();
    }

    @Test
    void browserSessionsStillRequireCsrfForDavAndApiWrites() throws Exception {
        contract.sessionCsrfRemainsRequired();
    }

    @Test
    void readOnlyDavCredentialsCannotWriteOrEnterRestAdministration() throws Exception {
        contract.readOnlyCredentialsRemainScopedAndRevocable();
    }

    @Test
    @Timeout(180)
    void twoServersShareDavAuthorityAndRecoverAnUnacknowledgedSaveAfterRestart() throws Exception {
        DocumentTemplateHttpContract.PendingDavRecovery pending;
        DocumentTemplateGitRepository peerRepository;
        try (var peer = startPeer(); var peerClient = client(port(peer))) {
            assertNotEquals(port, port(peer), "Both servers must be listening at once");
            peerRepository = peer.getBean(DocumentTemplateGitRepository.class);
            assertNotSame(repository, peerRepository, "Each server opens its own Git storage handle");
            pending = contract.contendAndLoseDavAcknowledgement(peerClient);
        }

        try (var restarted = startPeer(); var restartedClient = client(port(restarted))) {
            assertNotSame(peerRepository, restarted.getBean(DocumentTemplateGitRepository.class));
            contract.recoverDavSave(restartedClient, pending);
        }
    }

    private static ConfigurableApplicationContext startPeer() {
        var args = new ArrayList<>(NODE_PROPERTIES.entrySet().stream()
                .map(property -> "--" + property.getKey() + "=" + property.getValue()).toList());
        args.add("--spring.profiles.active=hsqldb");
        args.add("--server.port=0");
        args.add("--spring.datasource.url=" + databaseUrl());
        args.add("--taxonomy.admin-password=" + PASSWORD);
        return new SpringApplicationBuilder(TaxonomyApplication.class).registerShutdownHook(false)
                .run(args.toArray(String[]::new));
    }

    private static String databaseUrl() {
        return "jdbc:hsqldb:file:" + databaseDirectory.resolve("templates")
                + ";shutdown=true;hsqldb.tx=mvcc;hsqldb.write_delay_millis=0";
    }

    private static int port(ConfigurableApplicationContext node) {
        return node.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    private static DocumentTemplateHttpContract client(int port) {
        return new DocumentTemplateHttpContract("http://127.0.0.1:" + port + "/taxonomy", PASSWORD);
    }
}
