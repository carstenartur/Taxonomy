package com.taxonomy.interop.publication;

import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class IntegrationPublicationSecurityTest extends PublicationIntegrationFixture {
    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory factory;
    @Test void ambiguousHttpAndInvalidReceiptResponsesRemainUnknown(CapturedOutput logs) throws Exception {
        for (String fault : List.of("401", "429", "500", "redirect", "oversized", "foreign", "reflection")) {
            if (provider != null) provider.close();
            provider = new PublicationContractProvider(directory.resolve(fault + ".json")); connector.provider = provider;
            connector.fixtureCredential = "fixture-secret-" + UUID.randomUUID();
            connection = integrations.create(context, new IntegrationService.CreateConnection(UUID.randomUUID(), "Security contract", PublicationContractProvider.PROFILE, AuthorityMode.BIDIRECTIONAL, PublicationContractProvider.SCOPE.externalScope(), null, null)).id();
            if (fault.equals("401")) edit(new CreateArchitectureElement("security-resource", "System", Map.of("title", "Security")));
            provider.responseStatus = switch (fault) { case "401" -> 401; case "429" -> 429; case "500" -> 500; case "redirect" -> 302; default -> 0; };
            provider.oversizedResponse = fault.equals("oversized"); provider.foreignReceipt = fault.equals("foreign");
            if (fault.equals("reflection")) provider.reflectedCredential = connector.fixtureCredential;
            var result = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
            assertEquals(ItemState.UNKNOWN, result.items().getFirst().state(), fault);
            assertNull(result.commonCheckpointId()); assertFalse(result.allowedActions().contains(PublicationAction.RECONCILE));
            assertEquals(1, provider.mutationCount(result.items().getFirst().resourceId()));
            assertEquals(result.operationId(), store.read(context, connection).activeOperationId());
            assertFalse(json.write(result).contains(connector.fixtureCredential));
            try (var em = factory.createEntityManager()) {
                for (String table : List.of("interop_operation", "interop_event", "interop_publication", "interop_publish_item", "interop_publish_attempt")) {
                    var rows = em.createNativeQuery("select * from " + table).getResultList();
                    for (Object row : rows) for (Object cell : row instanceof Object[] values ? values : new Object[] { row }) {
                        String persisted = cell instanceof java.sql.Clob clob ? clob.getSubString(1, Math.toIntExact(clob.length()))
                                : cell instanceof java.sql.Blob blob ? new String(blob.getBytes(1, Math.toIntExact(blob.length())), java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(cell);
                        assertFalse(persisted.contains(connector.fixtureCredential), table);
                    }
                }
            }
            assertFalse(logs.getAll().contains(connector.fixtureCredential));
        }
        connector.fixtureCredential = null;
    }
    @Test void explicitLoopbackTransportRejectsHostPathCredentialAndDnsEscape() {
        for (String uri : List.of("http://127.0.0.1:80@evil.example", "http://evil.example:80", "http://localhost:80", "http://127.0.0.1:80/../item", "http://127.0.0.1:80/?token=secret", "https://127.0.0.1:80", "http://[::1]:80"))
            assertThrows(IllegalArgumentException.class, () -> connector.endpoint(URI.create(uri)), uri);
        assertEquals(0, provider.writes.get()); assertEquals(0, provider.reads.get());
    }
    @Test void craftedPcsAndWrongActorBranchAndScopeCannotPublish() throws Exception {
        var pcs = store.create(context, UUID.randomUUID(), store.read(context, connection).organizationId(), "Unverified PCS", "sparx-oslc-am-2.0", "2", AuthorityMode.BIDIRECTIONAL, PublicationContractProvider.SCOPE.externalScope(), null, "unverified");
        mvc.perform(get("/api/integrations/" + pcs.id()).param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId()).param("branch", context.branch()).with(user(context.username()).roles("ARCHITECT")))
                .andExpect(status().isOk()).andExpect(jsonPath("publicationAvailability.reasonCode").value("PUBLICATION_GUARANTEES_UNVERIFIED"));
        var request = new PublicationPreviewRequest(UUID.randomUUID(), state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.revision());
        mvc.perform(post("/api/integrations/" + pcs.id() + "/publication-previews").param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId()).param("branch", context.branch())
                .with(user(context.username()).roles("ARCHITECT")).with(csrf()).contentType("application/json").content(json.write(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("PUBLICATION_GUARANTEES_UNVERIFIED"));
        var p = preview(PublicationMode.PUSH);
        String path = "/api/integrations/" + connection + "/operations/" + p.operationId() + "/retry";
        mvc.perform(post(path).param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId()).param("branch", context.branch())
                .with(user("intruder").roles("ARCHITECT")).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
        var before = publication.publication(context, connection, p.operationId());
        mvc.perform(post(path).param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId()).param("branch", "moved-branch")
                .with(user(context.username()).roles("ARCHITECT")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("INTERNAL_STATE_CHANGED"));
        assertEquals(before, publication.publication(context, connection, p.operationId()));
        assertEquals(0, provider.writes.get());
        var foreignScope = new PublicationScope(PublicationContractProvider.SCOPE.externalScope(), "urn:other:model", "all");
        assertThrows(com.taxonomy.interop.IntegrationProblem.class, () -> publication.previewPublication(context, connection, new PublicationPreviewRequest(UUID.randomUUID(), state(), PublicationMode.PUSH, foreignScope, provider.revision())));
        mvc.perform(post(path).with(user(context.username()).roles("USER")).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
        assertEquals(0, provider.writes.get());
    }
}
