package com.taxonomy.templates;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/** Uses the actual server, security filters, MVC advice and Git-backed template storage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.servlet.context-path=/taxonomy",
        "spring.datasource.url=jdbc:hsqldb:mem:template_http_contract",
        "embedding.enabled=false",
        "llm.mock=true",
        "taxonomy.init.async=false",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
})
@ActiveProfiles("hsqldb")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentTemplateHttpIT {
    private static final String PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("taxonomy.admin-password", () -> PASSWORD);
    }

    @Value("${local.server.port}")
    private int port;

    private DocumentTemplateHttpContract contract;

    @BeforeEach
    void openClient() {
        contract = new DocumentTemplateHttpContract("http://127.0.0.1:" + port + "/taxonomy", PASSWORD);
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

}
