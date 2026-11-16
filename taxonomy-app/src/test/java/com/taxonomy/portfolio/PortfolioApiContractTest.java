package com.taxonomy.portfolio;

import org.junit.jupiter.api.Tag;

import com.jayway.jsonpath.JsonPath;
import com.taxonomy.testsupport.LocalTestLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST and authorization contracts for the project portfolio API. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "embedding.enabled=false",
        "llm.mock=true",
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
@Tag("ci-app-shard-domain-integration")
class PortfolioApiContractTest extends RetainedPortfolioCatalogue {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void readerCanListProjectsButCannotCreateOne() throws Exception {
        var actor = asLocalUser("reader", "USER");
        mockMvc.perform(get("/api/projects").with(actor))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectKey": "P-SECURITY",
                                  "title": "Forbidden project"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void architectCanCreateAProjectAndReceivesAResourceLocation() throws Exception {
        var actor = asLocalUser("architect", "ARCHITECT");
        mockMvc.perform(post("/api/projects")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectKey": "P-API-CONTRACT",
                                  "title": "API contract project",
                                  "status": "PLANNING"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/projects/[0-9]+")))
                .andExpect(jsonPath("$.projectKey").value("P-API-CONTRACT"))
                .andExpect(jsonPath("$.title").value("API contract project"));
    }

    @Test
    void browserAnalysisContractReturnsAndListsTheAcceptedJob() throws Exception {
        var actor = asLocalUser("architect", "ARCHITECT");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult projectResult = mockMvc.perform(post("/api/projects")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectKey": "P-ANALYSIS-%s",
                                  "title": "Analysis HTTP contract"
                                }
                                """.formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        long projectId = ((Number) JsonPath.read(
                projectResult.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult requirementResult = mockMvc.perform(post(
                        "/api/projects/{projectId}/requirements", projectId)
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requirementKey": "REQ-ANALYSIS-%s",
                                  "title": "Traceable secure communication",
                                  "text": "Provide traceable secure communication with auditable decisions.",
                                  "requirementType": "SECURITY"
                                }
                                """.formatted(suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        long requirementId = ((Number) JsonPath.read(
                requirementResult.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult jobResult = mockMvc.perform(post("/api/projects/{projectId}/analyses", projectId)
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requirementIds": [%d],
                                  "all": false,
                                  "provider": "MOCK",
                                  "maxArchitectureNodes": 25,
                                  "idempotencyKey": "http-contract-%s"
                                }
                                """.formatted(requirementId, suffix)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/projects/" + projectId + "/analysis-jobs/[A-Za-z0-9-]+")))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andReturn();
        String jobId = JsonPath.read(jobResult.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/projects/{projectId}/analysis-jobs", projectId).with(actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(jobId));
    }

    @Test
    void invalidPortfolioRequestUsesRfc9457ProblemDetail() throws Exception {
        var actor = asLocalUser("architect", "ARCHITECT");
        mockMvc.perform(post("/api/projects")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid portfolio request"))
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:validation"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void readerMayStartAnalysisButProjectIsolationStillReturnsNotFound() throws Exception {
        var actor = asLocalUser("reader", "USER");
        mockMvc.perform(post("/api/projects/999999999/analyses")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requirementIds": [1],
                                  "all": false,
                                  "provider": "MOCK"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Portfolio resource not found"))
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:not_found"));
    }


    @Test
    void readerCannotConfirmGeneratedMappings() throws Exception {
        var actor = asLocalUser("reader", "USER");
        mockMvc.perform(patch("/api/projects/1/analysis-mappings/elements/1")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewStatus": "CONFIRMED",
                                  "actionStatus": "REUSE",
                                  "comment": "reviewed"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void analyzeProjectAcceptsRequestWithoutAllField() throws Exception {
        var actor = asLocalUser("reader", "USER");
        mockMvc.perform(post("/api/projects/999999999/analyses")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requirementIds": [1],
                                  "provider": "MOCK"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void analyzeProjectAcceptsRequestWithNullAllField() throws Exception {
        var actor = asLocalUser("reader", "USER");
        mockMvc.perform(post("/api/projects/999999999/analyses")
                        .with(actor)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requirementIds": [1],
                                  "all": null,
                                  "provider": "MOCK"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    private RequestPostProcessor asLocalUser(String username, String role) {
        return authentication(LocalTestLogin.authentication(applicationContext, username, role));
    }
}
