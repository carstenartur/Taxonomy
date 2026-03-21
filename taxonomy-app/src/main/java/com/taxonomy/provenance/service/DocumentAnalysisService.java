package com.taxonomy.provenance.service;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AiExtractedCandidate;
import com.taxonomy.dto.RegulationArchitectureMatch;
import com.taxonomy.shared.service.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

/**
 * AI-powered document analysis service that provides:
 * <ul>
 *   <li>LLM-assisted extraction of requirement candidates from document text</li>
 *   <li>Direct regulation-to-architecture taxonomy mapping via LLM</li>
 * </ul>
 *
 * <p>Falls back gracefully when the LLM is unavailable or returns invalid responses.
 */
@Service
public class DocumentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalysisService.class);

    /** Maximum characters of document text sent to the LLM in a single prompt. */
    private static final int MAX_DOCUMENT_LENGTH = 15_000;

    private final PromptTemplateService promptTemplateService;
    private final LlmService llmService;
    private final TaxonomyService taxonomyService;
    private final ObjectMapper objectMapper;

    public DocumentAnalysisService(PromptTemplateService promptTemplateService,
                                    LlmService llmService,
                                    TaxonomyService taxonomyService,
                                    ObjectMapper objectMapper) {
        this.promptTemplateService = promptTemplateService;
        this.llmService = llmService;
        this.taxonomyService = taxonomyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts requirement candidates from document text using the LLM.
     * Falls back to an empty list if the LLM is unavailable or returns invalid JSON.
     *
     * @param documentText raw document text
     * @param sourceType   "REGULATION" uses the specialized regulation extraction prompt;
     *                     all other values use the general extraction prompt
     * @return list of AI-extracted requirement candidates
     */
    public List<AiExtractedCandidate> extractWithAi(String documentText, String sourceType) {
        if (!llmService.isAvailable()) {
            log.warn("LLM is not available — AI extraction skipped");
            return Collections.emptyList();
        }

        String promptCode = "REGULATION".equalsIgnoreCase(sourceType)
                ? "extract-regulation" : "extract-default";
        String prompt = promptTemplateService.renderExtractionPrompt(
                promptCode, truncateIfNeeded(documentText));

        log.info("Calling LLM for AI extraction (prompt code: {})", promptCode);
        String response = llmService.callLlmRaw(prompt);
        return parseExtractionResponse(response);
    }

    /**
     * Maps regulation text directly to architecture taxonomy nodes using the LLM.
     * Returns direct node matches with confidence and paragraph references.
     *
     * @param regulationText raw regulation text
     * @return list of regulation-to-architecture matches
     */
    public List<RegulationArchitectureMatch> mapRegulationToArchitecture(String regulationText) {
        if (!llmService.isAvailable()) {
            log.warn("LLM is not available — regulation mapping skipped");
            return Collections.emptyList();
        }

        String nodeList = buildFullNodeList();
        String prompt = promptTemplateService.renderRegulationMappingPrompt(
                "reg-map-default", truncateIfNeeded(regulationText), nodeList);

        log.info("Calling LLM for regulation-to-architecture mapping");
        String response = llmService.callLlmRaw(prompt);
        return parseRegulationMappingResponse(response);
    }

    /**
     * Extracts requirement candidates from document text using the LLM,
     * enhanced with parent-section context for better understanding.
     *
     * <p>The parent context is prepended to the prompt so the LLM can
     * understand the chunk in its broader document context.
     *
     * @param chunkText     the text of the specific chunk
     * @param parentContext the surrounding section context
     * @param sourceType    "REGULATION" or other
     * @return list of AI-extracted requirement candidates
     */
    public List<AiExtractedCandidate> extractWithAiContextual(String chunkText,
                                                               String parentContext,
                                                               String sourceType) {
        if (!llmService.isAvailable()) {
            log.warn("LLM is not available — AI extraction skipped");
            return Collections.emptyList();
        }

        String contextualText = parentContext != null && !parentContext.isBlank()
                ? "Context (parent section): " + parentContext + "\n\n"
                  + "Text to analyse:\n" + chunkText
                : chunkText;

        return extractWithAi(contextualText, sourceType);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    List<AiExtractedCandidate> parseExtractionResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Empty LLM response for AI extraction");
            return Collections.emptyList();
        }
        try {
            String json = extractJsonArray(response);
            return objectMapper.readValue(json, new TypeReference<List<AiExtractedCandidate>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse AI extraction response: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    List<RegulationArchitectureMatch> parseRegulationMappingResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Empty LLM response for regulation mapping");
            return Collections.emptyList();
        }
        try {
            String json = extractJsonArray(response);
            return objectMapper.readValue(json, new TypeReference<List<RegulationArchitectureMatch>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse regulation mapping response: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    String truncateIfNeeded(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_DOCUMENT_LENGTH) return text;
        log.info("Document text truncated from {} to {} characters", text.length(), MAX_DOCUMENT_LENGTH);
        return text.substring(0, MAX_DOCUMENT_LENGTH) + "\n\n[... truncated ...]";
    }

    private String buildFullNodeList() {
        List<TaxonomyNode> roots = taxonomyService.getRootNodes();
        StringBuilder sb = new StringBuilder();
        for (TaxonomyNode root : roots) {
            appendNodeTree(sb, root, 0);
        }
        return sb.toString();
    }

    private void appendNodeTree(StringBuilder sb, TaxonomyNode node, int depth) {
        sb.append("  ".repeat(depth))
          .append(node.getCode()).append(": ").append(node.getName()).append("\n");
        List<TaxonomyNode> children = taxonomyService.getChildrenOf(node.getCode());
        for (TaxonomyNode child : children) {
            appendNodeTree(sb, child, depth + 1);
        }
    }

    /**
     * Extracts a JSON array from a text response that may contain markdown code fences
     * or surrounding prose.
     */
    private String extractJsonArray(String text) {
        // Strip markdown code fences
        String stripped = text.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            if (firstNewline > 0) {
                stripped = stripped.substring(firstNewline + 1);
            }
            if (stripped.endsWith("```")) {
                stripped = stripped.substring(0, stripped.length() - 3);
            }
            stripped = stripped.strip();
        }
        // Find outermost JSON array
        int start = stripped.indexOf('[');
        int end = stripped.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return stripped.substring(start, end + 1);
        }
        return stripped;
    }
}
