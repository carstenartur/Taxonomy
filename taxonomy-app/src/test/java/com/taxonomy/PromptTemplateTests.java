package com.taxonomy;

import com.taxonomy.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class PromptTemplateTests {

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetOverrides() {
        // Clean up any overrides from previous tests
        for (String code : List.copyOf(promptTemplateService.getAllTemplateCodes())) {
            promptTemplateService.resetTemplate(code);
        }
    }

    // ── Unit tests for PromptTemplateService ──────────────────────────────────

    @Test
    void defaultTemplatesAreLoaded() {
        assertThat(promptTemplateService.getAllTemplateCodes()).isNotEmpty();
        assertThat(promptTemplateService.getAllTemplateCodes()).contains("default");
    }

    @Test
    void taxonomySpecificTemplatesAreLoaded() {
        List<String> codes = promptTemplateService.getAllTemplateCodes();
        assertThat(codes).contains("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
    }

    @Test
    void defaultTemplateContainsPlaceholders() {
        String template = promptTemplateService.getDefaultTemplate("default");
        assertThat(template).contains("{{BUSINESS_TEXT}}");
        assertThat(template).contains("{{NODE_LIST}}");
        assertThat(template).contains("{{PARENT_SCORE}}");
        assertThat(template).contains("{{EXPECTED_KEYS}}");
    }

    @Test
    void taxonomySpecificTemplateContainsPlaceholders() {
        String bpTemplate = promptTemplateService.getDefaultTemplate("BP");
        assertThat(bpTemplate).contains("{{BUSINESS_TEXT}}");
        assertThat(bpTemplate).contains("{{NODE_LIST}}");
        assertThat(bpTemplate).contains("{{TAXONOMY_NAME}}");
        assertThat(bpTemplate).contains("{{PARENT_SCORE}}");
        assertThat(bpTemplate).contains("{{EXPECTED_KEYS}}");
    }

    @Test
    void renderPromptReplacesAllPlaceholders() {
        String rendered = promptTemplateService.renderPrompt("default", "test business text", "C1: Capability", 75, "C1, C2");
        assertThat(rendered).contains("test business text");
        assertThat(rendered).contains("C1: Capability");
        assertThat(rendered).contains("75");
        assertThat(rendered).contains("C1, C2");
        assertThat(rendered).doesNotContain("{{BUSINESS_TEXT}}");
        assertThat(rendered).doesNotContain("{{NODE_LIST}}");
        assertThat(rendered).doesNotContain("{{PARENT_SCORE}}");
        assertThat(rendered).doesNotContain("{{EXPECTED_KEYS}}");
    }

    @Test
    void renderPromptDefaultOverloadUsesHundred() {
        String rendered = promptTemplateService.renderPrompt("default", "test text", "C1: Node");
        assertThat(rendered).doesNotContain("{{PARENT_SCORE}}");
        // The default overload should use 100 as the parent score
        // Verify by checking with the 4-arg variant that produces the same result
        String renderedExplicit = promptTemplateService.renderPrompt("default", "test text", "C1: Node", 100);
        assertThat(rendered).isEqualTo(renderedExplicit);
    }

    @Test
    void renderPromptSubstitutesExpectedKeys() {
        String rendered = promptTemplateService.renderPrompt("BP", "test text", "BP-1000: Process", 100, "BP-1000, BP-2000, BP-3000");
        assertThat(rendered).contains("BP-1000, BP-2000, BP-3000");
        assertThat(rendered).doesNotContain("{{EXPECTED_KEYS}}");
    }

    @Test
    void renderPromptForTaxonomyReplacesTaxonomyName() {
        String rendered = promptTemplateService.renderPrompt("BP", "test text", "BP1: Process");
        assertThat(rendered).doesNotContain("{{TAXONOMY_NAME}}");
        assertThat(rendered).contains("Business Processes");
    }

    @Test
    void overrideReplacesDefaultTemplate() {
        String customTemplate = "Custom prompt for {{BUSINESS_TEXT}} with {{NODE_LIST}}";
        promptTemplateService.setTemplate("BP", customTemplate);

        assertThat(promptTemplateService.isOverridden("BP")).isTrue();
        assertThat(promptTemplateService.getTemplate("BP")).isEqualTo(customTemplate);
    }

    @Test
    void getDefaultTemplateIgnoresOverride() {
        String originalDefault = promptTemplateService.getDefaultTemplate("BP");
        promptTemplateService.setTemplate("BP", "override text");

        assertThat(promptTemplateService.getDefaultTemplate("BP")).isEqualTo(originalDefault);
    }

    @Test
    void resetTemplateRemovesOverride() {
        promptTemplateService.setTemplate("BP", "override text");
        assertThat(promptTemplateService.isOverridden("BP")).isTrue();

        promptTemplateService.resetTemplate("BP");
        assertThat(promptTemplateService.isOverridden("BP")).isFalse();
        assertThat(promptTemplateService.getTemplate("BP")).isEqualTo(
                promptTemplateService.getDefaultTemplate("BP"));
    }

    @Test
    void unknownCodeFallsBackToDefault() {
        String fallback = promptTemplateService.getDefaultTemplate("UNKNOWN_CODE");
        String explicitDefault = promptTemplateService.getDefaultTemplate("default");
        assertThat(fallback).isEqualTo(explicitDefault);
    }

    @Test
    void getTaxonomyNameReturnsHumanReadableName() {
        assertThat(promptTemplateService.getTaxonomyName("BP")).isEqualTo("Business Processes");
        assertThat(promptTemplateService.getTaxonomyName("CP")).isEqualTo("Capabilities");
        assertThat(promptTemplateService.getTaxonomyName("UNKNOWN")).isEqualTo("UNKNOWN");
    }

    // ── Integration tests for REST endpoints ──────────────────────────────────

    @Test
    void getPromptsReturnsAllTemplates() throws Exception {
        mockMvc.perform(get("/api/prompts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void getPromptsResponseContainsRequiredFields() throws Exception {
        mockMvc.perform(get("/api/prompts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").exists())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].template").exists())
                .andExpect(jsonPath("$[0].overridden").isBoolean());
    }

    @Test
    void getPromptByCodeReturnsSingleTemplate() throws Exception {
        mockMvc.perform(get("/api/prompts/BP").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BP"))
                .andExpect(jsonPath("$.template").exists())
                .andExpect(jsonPath("$.defaultTemplate").exists())
                .andExpect(jsonPath("$.overridden").value(false));
    }

    @Test
    void putPromptSavesOverride() throws Exception {
        mockMvc.perform(put("/api/prompts/BP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\": \"Custom BP prompt for {{BUSINESS_TEXT}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(true));

        // Verify override is stored
        mockMvc.perform(get("/api/prompts/BP").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.template").value("Custom BP prompt for {{BUSINESS_TEXT}}"));

        // Clean up
        promptTemplateService.resetTemplate("BP");
    }

    @Test
    void putPromptReturnsBadRequestWhenMissingTemplate() throws Exception {
        mockMvc.perform(put("/api/prompts/BP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletePromptResetsToDefault() throws Exception {
        // First set an override
        promptTemplateService.setTemplate("BP", "override");
        assertThat(promptTemplateService.isOverridden("BP")).isTrue();

        // Then delete (reset)
        mockMvc.perform(delete("/api/prompts/BP").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(false));

        assertThat(promptTemplateService.isOverridden("BP")).isFalse();
    }
}
