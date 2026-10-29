package com.taxonomy.portfolio.controller;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import com.taxonomy.portfolio.inventory.InventoryComparisonService;
import com.taxonomy.portfolio.inventory.InventoryComparisonService.MapCandidateRequest;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP routing/serialization contract; full security filters remain application-test owned. */
class InventoryComparisonControllerTest {
    private final InventoryComparisonService service = mock(InventoryComparisonService.class);
    private final WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
    private final WorkspaceContext context = new WorkspaceContext("alice", "ws", "draft", "repo");
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID comparisonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID mappingId = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private final String candidateId = "external:fixture:17";
    private final MapCandidateRequest mappingRequest = new MapCandidateRequest(mappingId, 7L,
            "comparison-v1", "Verified vendor identifier");
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(workspaces.resolveCurrentUsername()).thenReturn("alice");
        when(workspaces.resolveCurrentContext()).thenReturn(context);
        mvc = MockMvcBuilders.standaloneSetup(
                new InventoryComparisonController(service, workspaces)).build();
    }

    @Test
    void mappingPostBindsRetainedIdentityAndUsesTheResolvedWorkspace() throws Exception {
        var result = new Mapping(mappingId, candidateId, 7L, "product-v1", "fixture-source",
                "source-revision-3", "external-v1", mappingRequest.rationale(), "alice", Instant.EPOCH);
        when(service.map(comparisonId, candidateId, mappingRequest, "alice", context))
                .thenReturn(result);

        mvc.perform(post("/api/inventory-comparisons/{id}/candidates/{candidate}/mappings",
                        comparisonId, candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mappingRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mappingId.toString()))
                .andExpect(jsonPath("$.candidateId").value(candidateId))
                .andExpect(jsonPath("$.productId").value(7))
                .andExpect(jsonPath("$.productFingerprint").value("product-v1"))
                .andExpect(jsonPath("$.sourceProfile").value("fixture-source"))
                .andExpect(jsonPath("$.sourceRevision").value("source-revision-3"))
                .andExpect(jsonPath("$.sourceIdentityFingerprint").value("external-v1"))
                .andExpect(jsonPath("$.rationale").value(mappingRequest.rationale()))
                .andExpect(jsonPath("$.actor").value("alice"));

        verify(service).map(comparisonId, candidateId, mappingRequest, "alice", context);
        verifyNoMoreInteractions(service);
    }

    @Test
    void assertionPostPreservesNegativeEvidencePrecisionCitationAndResolvedScope() throws Exception {
        var assertion = new Assertion("assertion-7", "product:7", Criterion.AVAILABILITY,
                null, false, new Boundary("2030", Precision.YEAR), null, "north", "production",
                AssertionKind.DECLARED, "Sourced schedule excludes this period", Instant.EPOCH);
        when(service.appendAssertion(7L, assertion, "alice", context)).thenReturn(assertion);

        mvc.perform(post("/api/products/{productId}/inventory-assertions", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(assertion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("assertion-7"))
                .andExpect(jsonPath("$.candidateId").value("product:7"))
                .andExpect(jsonPath("$.criterion").value("AVAILABILITY"))
                .andExpect(jsonPath("$.positive").value(false))
                .andExpect(jsonPath("$.start.value").value("2030"))
                .andExpect(jsonPath("$.start.precision").value("YEAR"))
                .andExpect(jsonPath("$.organization").value("north"))
                .andExpect(jsonPath("$.environment").value("production"))
                .andExpect(jsonPath("$.kind").value("DECLARED"))
                .andExpect(jsonPath("$.citation").value(assertion.citation()));

        verify(service).appendAssertion(7L, assertion, "alice", context);
        verifyNoMoreInteractions(service);
    }

    @Test
    void sourceGrantFailureKeepsItsSafeStatusCodeAndProblemDetail() throws Exception {
        when(service.read(comparisonId, "alice", context)).thenThrow(
                new InventoryEvidencePort.Failure("source_forbidden", 403, "Source grant revoked"));

        mvc.perform(get("/api/inventory-comparisons/{id}", comparisonId))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Inventory evidence operation failed"))
                .andExpect(jsonPath("$.code").value("source_forbidden"))
                .andExpect(jsonPath("$.detail").value("Source grant revoked"));

        verify(service).read(comparisonId, "alice", context);
    }

    @Test
    void invalidMetadataReturnsABoundedProblemWithoutEchoingExceptionText() throws Exception {
        when(service.map(comparisonId, candidateId, mappingRequest, "alice", context))
                .thenThrow(new IllegalArgumentException("Untrusted metadata must not be echoed"));

        mvc.perform(post("/api/inventory-comparisons/{id}/candidates/{candidate}/mappings",
                        comparisonId, candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mappingRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "Invalid inventory comparison metadata or interval"))
                .andExpect(content().string(not(containsString("Untrusted metadata"))));

        verify(service).map(comparisonId, candidateId, mappingRequest, "alice", context);
    }
}
