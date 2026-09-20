package com.taxonomy;

import com.taxonomy.interop.publication.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import java.nio.file.Files;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = { "taxonomy.features.multi-repository-api.enabled=true", "embedding.enabled=false", "taxonomy.admin-password=Civilian-Acceptance-2026!", "taxonomy.security.require-password-change=false" })
@Import(PublicationCivilianConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CivilianPublicationFixtureTest {
    @LocalServerPort int port;
    @Autowired PublicationContractProvider provider;
    @Test void actualBrowserFixtureRoutesProducePartialThenRecoveredPublication() throws Exception {
        var app = new CivilianArchitectureAcceptanceTest(); app.port = port; Files.createDirectories(app.output);
        var context = CivilianPublicationWalkthrough.prepare(app, provider);
        String scope = context.path("scope").asText(), path = "/api/integrations/" + context.path("connection").asText();
        String operation = context.path("operation").asText();
        var preview = app.get(path + "/operations/" + operation + "/publication" + scope);
        var partial = app.post(path + "/publish" + scope, CivilianPublicationWalkthrough.review(preview, "KEEP_LOCAL"), 200);
        assertThat(partial.path("acknowledgedCount").asInt()).isEqualTo(1); assertThat(partial.path("unknownCount").asInt()).isEqualTo(1);
        assertThat(partial.path("commonCheckpointId").isNull()).isTrue();
        assertThat(app.get(path + "/operations/" + operation + "/publication" + scope)).isEqualTo(partial);
        var recovered = app.post(path + "/operations/" + operation + "/retry" + scope, Map.of(), 200);
        assertThat(recovered.path("phase").asText()).isEqualTo("COMPLETED");
        assertThat(recovered.path("acknowledgedCount").asInt()).isEqualTo(3);
        assertThat(recovered.path("requestFingerprint").asText()).isEqualTo(partial.path("requestFingerprint").asText());
    }
}
