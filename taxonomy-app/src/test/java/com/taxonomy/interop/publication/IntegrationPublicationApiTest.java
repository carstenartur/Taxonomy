package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@AutoConfigureMockMvc
class IntegrationPublicationApiTest extends PublicationIntegrationFixture {
    @Autowired MockMvc mvc;
    MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder r) {
        return r.param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId()).param("branch", context.branch())
                .with(user(context.username()).roles("ARCHITECT")).with(csrf());
    }
    String path() { return "/api/integrations/" + connection; }
    @Test void realRoutesRestoreFrozenReviewScopeAndRetryWithoutFileDelivery() throws Exception {
        edit(new CreateArchitectureElement("api-element", "System", Map.of("title", "API publication")));
        UUID id = UUID.randomUUID();
        var request = new PublicationPreviewRequest(id, state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        var response = mvc.perform(scoped(post(path() + "/publication-previews")).contentType("application/json").content(json.write(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("operationId").value(id.toString())).andReturn();
        var preview = json.read(response.getResponse().getContentAsString(), PublicationOperation.class);
        var review = review(preview);
        mvc.perform(scoped(post(path() + "/publish")).contentType("application/json").content(json.write(review)))
                .andExpect(status().isOk()).andExpect(jsonPath("phase").value("COMPLETED"));
        mvc.perform(scoped(get(path() + "/operations/" + id + "/publication")))
                .andExpect(status().isOk()).andExpect(jsonPath("review.resolutions").isMap())
                .andExpect(jsonPath("scope.rootResource").value(PublicationContractProvider.SCOPE.rootResource()))
                .andExpect(jsonPath("expectedExternalRevision").value(request.expectedExternalRevision()))
                .andExpect(jsonPath("requestFingerprint").isNotEmpty()).andExpect(jsonPath("leaseOwner").doesNotExist());
        mvc.perform(scoped(post(path() + "/operations/" + id + "/retry")).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("phase").value("COMPLETED"));
        assertEquals(1, provider.writes.get());
        mvc.perform(scoped(post(path() + "/files")).contentType("application/json").content(json.write(review.review())))
                .andExpect(status().isConflict());
    }
    @Test void overviewScopeAndExactStateAreRequiredBeforePublication() throws Exception {
        mvc.perform(scoped(get(path()))).andExpect(status().isOk())
                .andExpect(jsonPath("publicationAvailability.available").value(false));
        mvc.perform(scoped(get(path())).param("rootResource", PublicationContractProvider.SCOPE.rootResource()).param("selectorFingerprint", "all"))
                .andExpect(status().isOk()).andExpect(jsonPath("publicationAvailability.available").value(true));
        mvc.perform(scoped(post(path() + "/publication-previews")).contentType("application/json")
                .content(json.write(Map.of("operationId", UUID.randomUUID(), "mode", "PUSH", "scope", PublicationContractProvider.SCOPE, "expectedExternalRevision", "scope-0"))))
                .andExpect(status().isPreconditionRequired());
        assertEquals(0, provider.writes.get());
    }
    @Test void reconciliationRequiresExactStateAtTheSameHttpBoundary() throws Exception {
        var before = preview(PublicationMode.PUSH);
        var request = Map.of("operationId", UUID.randomUUID(), "mode", "PUSH", "scope", PublicationContractProvider.SCOPE, "expectedExternalRevision", provider.revision());
        mvc.perform(scoped(post(path() + "/operations/" + before.operationId() + "/reconciliation-previews")).contentType("application/json")
                .content(json.write(Map.of("predecessorOperationId", before.operationId(), "request", request, "rationale", "Explicit next review"))))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("code").value("EXACT_STATE_REQUIRED"));
        assertEquals(before, publication.publication(context, connection, before.operationId()));
        assertEquals(0, provider.writes.get());
    }

}
