package com.taxonomy.provenance.service;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AiExtractedCandidate;
import com.taxonomy.dto.RegulationArchitectureMatch;
import com.taxonomy.provenance.service.DocumentAnalysisService;
import com.taxonomy.shared.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link DocumentAnalysisService} AI extraction and
 * regulation mapping logic.
 */
class DocumentAnalysisServiceTest {

    private DocumentAnalysisService service;
    private LlmService llmService;
    private PromptTemplateService promptTemplateService;
    private TaxonomyService taxonomyService;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        promptTemplateService = mock(PromptTemplateService.class);
        taxonomyService = mock(TaxonomyService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        service = new DocumentAnalysisService(
                promptTemplateService, llmService, taxonomyService, objectMapper);
    }

    // ── AI Extraction tests ───────────────────────────────────────────────────

    @Test
    void extractWithAi_parsesValidResponse() {
        when(llmService.isAvailable()).thenReturn(true);
        when(promptTemplateService.renderExtractionPrompt(anyString(), anyString()))
                .thenReturn("rendered prompt");
        when(llmService.callLlmRaw(anyString())).thenReturn(
                "[{\"text\": \"The authority must provide digital access.\", " +
                "\"sectionRef\": \"§ 4 Abs. 2\", \"confidence\": 0.92, \"type\": \"FUNCTIONAL\"}]");

        List<AiExtractedCandidate> result = service.extractWithAi("document text", "REGULATION");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("The authority must provide digital access.");
        assertThat(result.get(0).sectionRef()).isEqualTo("§ 4 Abs. 2");
        assertThat(result.get(0).confidence()).isEqualTo(0.92);
        assertThat(result.get(0).type()).isEqualTo("FUNCTIONAL");
    }

    @Test
    void extractWithAi_fallsBackOnInvalidJson() {
        when(llmService.isAvailable()).thenReturn(true);
        when(promptTemplateService.renderExtractionPrompt(anyString(), anyString()))
                .thenReturn("rendered prompt");
        when(llmService.callLlmRaw(anyString())).thenReturn("not valid json at all");

        List<AiExtractedCandidate> result = service.extractWithAi("document text", "REGULATION");

        assertThat(result).isEmpty();
    }

    @Test
    void extractWithAi_returnsEmptyWhenLlmUnavailable() {
        when(llmService.isAvailable()).thenReturn(false);

        List<AiExtractedCandidate> result = service.extractWithAi("document text", "REGULATION");

        assertThat(result).isEmpty();
        verify(llmService, never()).callLlmRaw(anyString());
    }

    @Test
    void extractWithAi_returnsEmptyOnNullResponse() {
        when(llmService.isAvailable()).thenReturn(true);
        when(promptTemplateService.renderExtractionPrompt(anyString(), anyString()))
                .thenReturn("rendered prompt");
        when(llmService.callLlmRaw(anyString())).thenReturn(null);

        List<AiExtractedCandidate> result = service.extractWithAi("document text", "REGULATION");

        assertThat(result).isEmpty();
    }

    @Test
    void regulationSourceType_usesSpecializedPrompt() {
        when(llmService.isAvailable()).thenReturn(true);
        when(promptTemplateService.renderExtractionPrompt(anyString(), anyString()))
                .thenReturn("rendered prompt");
        when(llmService.callLlmRaw(anyString())).thenReturn("[]");

        service.extractWithAi("document text", "REGULATION");
        verify(promptTemplateService).renderExtractionPrompt(eq("extract-regulation"), anyString());

        service.extractWithAi("document text", "BUSINESS_REQUEST");
        verify(promptTemplateService).renderExtractionPrompt(eq("extract-default"), anyString());
    }

    // ── Regulation mapping tests ──────────────────────────────────────────────

    @Test
    void mapRegulationToArchitecture_parsesMatches() {
        when(llmService.isAvailable()).thenReturn(true);
        when(taxonomyService.getRootNodes()).thenReturn(Collections.emptyList());
        when(promptTemplateService.renderRegulationMappingPrompt(anyString(), anyString(), anyString()))
                .thenReturn("rendered prompt");
        when(llmService.callLlmRaw(anyString())).thenReturn(
                "[{\"nodeCode\": \"CP-1023\", \"linkType\": \"MANDATES\", \"confidence\": 0.88, " +
                "\"paragraphRef\": \"§ 4 Abs. 2\", \"reason\": \"Mandates secure communication\"}]");

        List<RegulationArchitectureMatch> result = service.mapRegulationToArchitecture("regulation text");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeCode()).isEqualTo("CP-1023");
        assertThat(result.get(0).linkType()).isEqualTo("MANDATES");
        assertThat(result.get(0).confidence()).isEqualTo(0.88);
        assertThat(result.get(0).paragraphRef()).isEqualTo("§ 4 Abs. 2");
    }

    @Test
    void mapRegulationToArchitecture_emptyOnNoMatches() {
        when(llmService.isAvailable()).thenReturn(true);
        when(taxonomyService.getRootNodes()).thenReturn(Collections.emptyList());
        when(promptTemplateService.renderRegulationMappingPrompt(anyString(), anyString(), anyString()))
                .thenReturn("rendered prompt");
        when(llmService.callLlmRaw(anyString())).thenReturn("[]");

        List<RegulationArchitectureMatch> result = service.mapRegulationToArchitecture("regulation text");

        assertThat(result).isEmpty();
    }

    @Test
    void mapRegulationToArchitecture_returnsEmptyWhenLlmUnavailable() {
        when(llmService.isAvailable()).thenReturn(false);

        List<RegulationArchitectureMatch> result = service.mapRegulationToArchitecture("regulation text");

        assertThat(result).isEmpty();
        verify(llmService, never()).callLlmRaw(anyString());
    }

    // ── Truncation test ───────────────────────────────────────────────────────

    @Test
    void truncateIfNeeded_limitsDocumentLength() {
        String longText = "A".repeat(20_000);
        String truncated = service.truncateIfNeeded(longText);
        assertThat(truncated.length()).isLessThan(longText.length());
        assertThat(truncated).endsWith("[... truncated ...]");
    }

    @Test
    void truncateIfNeeded_preservesShortText() {
        String shortText = "Short document text.";
        assertThat(service.truncateIfNeeded(shortText)).isEqualTo(shortText);
    }

    // ── JSON parsing with markdown fences ──────────────────────────────────────

    @Test
    void parseExtractionResponse_handlesMarkdownCodeFences() {
        String response = "```json\n[{\"text\": \"Must provide access.\", \"sectionRef\": null, " +
                "\"confidence\": 0.85, \"type\": \"FUNCTIONAL\"}]\n```";
        List<AiExtractedCandidate> result = service.parseExtractionResponse(response);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("Must provide access.");
    }

    @Test
    void parseRegulationMappingResponse_handlesMarkdownCodeFences() {
        String response = "```json\n[{\"nodeCode\": \"BP-1000\", \"linkType\": \"REQUIRES\", " +
                "\"confidence\": 0.75, \"paragraphRef\": \"§ 2\", \"reason\": \"Process required\"}]\n```";
        List<RegulationArchitectureMatch> result = service.parseRegulationMappingResponse(response);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeCode()).isEqualTo("BP-1000");
    }
}
