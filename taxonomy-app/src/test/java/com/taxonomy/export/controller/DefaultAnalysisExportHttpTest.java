package com.taxonomy.export.controller;

import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.AnalysisMemoryGuard;
import com.taxonomy.analysis.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP composition with the explicitly selected deterministic remote-boundary demonstration. */
@SpringBootTest(properties = "llm.mock=true")
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class DefaultAnalysisExportHttpTest {
    private static final String ORIGINAL = "The communication application reads treatment records.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private RequirementRelationSearchService relations;
    @MockitoSpyBean private LlmService llm;

    @Test void allTextExportRoutesUseRequirementScopedRelationships() throws Exception {
        try (var memory = healthyMemory()) {
            String body = JSON.writeValueAsString(Map.of("businessText", ORIGINAL));
            for (String format : List.of("mermaid", "archimate", "visio", "structurizr")) {
                var response = mvc.perform(post("/api/diagram/" + format)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk()).andReturn().getResponse();
                assertThat(response.getContentAsByteArray()).isNotEmpty();
                if (format.equals("mermaid")) {
                    assertThat(response.getContentAsString()).contains("UA_1574", "IP_1136", "CR_1005", "consumes");
                }
            }
            String svg = mvc.perform(post("/api/diagram/export/svg")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(svg).contains("UA-1574", "IP-1136", "CR-1005");
            assertThat(relations.isEnabled()).isTrue();
            verify(relations, times(5)).search(eq(ORIGINAL), anyMap());
        }
    }

    @Test void invalidRelationRepliesDoNotProduceAFileFromLegacyScores() throws Exception {
        try (var memory = healthyMemory()) {
            doReturn("[]").when(llm).callLlmRaw(anyString());
            mvc.perform(post("/api/diagram/export/svg").contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of("businessText", ORIGINAL))))
                    .andExpect(status().isUnprocessableEntity());
            verify(relations).search(eq(ORIGINAL), anyMap());
        }
    }

    @Test void exportingAnExistingViewDoesNotRepeatAnyProviderOrRelationWork() throws Exception {
        try (var memory = healthyMemory()) {
            String analysis = mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of("businessText", ORIGINAL, "includeArchitectureView", true))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var result = JSON.readTree(analysis);
            assertThat(result.path("status").asString()).isEqualTo("SUCCESS");
            String frozen = JSON.writeValueAsString(result.get("architectureView"));
            clearInvocations(relations, llm);
            String svg = mvc.perform(post("/api/diagram/current/svg").contentType(MediaType.APPLICATION_JSON).content(frozen))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(svg).contains("UA-1574", "IP-1136", "CR-1005");
            verifyNoInteractions(relations, llm);
            assertThat(JSON.writeValueAsString(result.get("architectureView"))).isEqualTo(frozen);
        }
    }

    private static org.mockito.MockedStatic<AnalysisMemoryGuard> healthyMemory() {
        var memory = mockStatic(AnalysisMemoryGuard.class);
        long mib = 1024L * 1024;
        memory.when(AnalysisMemoryGuard::heapSample).thenReturn(new AnalysisMemoryGuard.Sample(64 * mib, 512 * mib));
        return memory;
    }
}
