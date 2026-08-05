package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Servlet-level contract for the asynchronous single-requirement enqueue endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "embedding.enabled=false",
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
class ProjectAnalysisSingleRequirementMvcContractTest {

    private static final WorkspaceContext CONTEXT =
            new WorkspaceContext("architect", "workspace-a", "feature-a");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectRequirementAnalysisService analysisService;

    @MockitoBean
    private PortfolioAnalysisPersistenceService persistenceService;

    @MockitoBean
    private WorkspaceResolver workspaceResolver;

    @BeforeEach
    void configureWorkspace() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(CONTEXT.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(CONTEXT);
    }

    @Test
    @WithMockUser(username = "architect", roles = "ARCHITECT")
    void returnsAcceptedJobAndCanonicalLocation() throws Exception {
        AnalysisJobView job = new AnalysisJobView(
                "job-1",
                41L,
                AnalysisStatus.PENDING,
                "single-key",
                "GEMINI",
                25,
                CONTEXT.username(),
                CONTEXT.workspaceId(),
                Instant.parse("2026-08-05T18:00:00Z"),
                null,
                null,
                1,
                0,
                0,
                0,
                null,
                List.of());
        when(analysisService.enqueueRequirement(
                eq(41L),
                eq(7L),
                eq("GEMINI"),
                eq(25),
                eq("single-key"),
                eq(CONTEXT.username()),
                eq(CONTEXT)))
                .thenReturn(job);

        mockMvc.perform(post("/api/projects/{projectId}/requirements/{requirementId}/analyses", 41L, 7L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "GEMINI",
                                  "maxArchitectureNodes": 25,
                                  "idempotencyKey": "single-key"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(
                        "Location",
                        "/api/projects/41/analysis-jobs/job-1"))
                .andExpect(jsonPath("$.id").value("job-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
