package com.taxonomy;

import com.taxonomy.security.service.UserManagementService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Exercises publication and projection through HTTP with the UI's default HSQL locking mode. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:hsqldb:mem:repository-publication-http;shutdown=true;hsqldb.lob_compressed=true;hsqldb.lob_file_scale=1;hsqldb.log_size=1",
        "spring.datasource.hikari.maximum-pool-size=8",
        "taxonomy.features.multi-repository-api.enabled=true",
        "taxonomy.security.require-password-change=false"
})
@ActiveProfiles("hsqldb")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("ci-app-shard-domain-integration")
class RepositoryPublicationHttpTest {
    private static final String USER = "repository-publication-admin";
    private static final String PASSWORD = "Publication-Locks-Fixture-73!";

    @LocalServerPort int port;
    @Autowired UserManagementService accounts;

    @Test
    void publishesAndBuildsOnlySelectedRepositoryProjectionsUnderHsqlLocks() throws Exception {
        accounts.createUser(Map.of("username", USER, "roles", List.of("ADMIN", "ARCHITECT", "USER")),
                PASSWORD, "fixture");
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        try (var contract = new RepositoryHistoryHttpContract(URI.create("http://127.0.0.1:" + port), authorization)) {
            contract.verifyTargetProjections();
        }
    }
}
