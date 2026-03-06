package com.nato.taxonomy.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nato.taxonomy.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @deprecated Replaced by {@link LlmService}. This class is kept for reference only.
 */
@Deprecated(forRemoval = true)
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=";

    @Value("${gemini.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TaxonomyService taxonomyService;

    public GeminiService(RestTemplate restTemplate,
                         ObjectMapper objectMapper,
                         TaxonomyService taxonomyService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.taxonomyService = taxonomyService;
    }

    /**
     * Recursively analyses business text against taxonomy nodes.
     * Starts with root nodes (level 0), then drills into children of nodes with >0% match.
     */
    public Map<String, Integer> analyzeRecursive(String businessText) {
        Map<String, Integer> allScores = new HashMap<>();
        List<TaxonomyNode> roots = taxonomyService.getRootNodes();
        analyzeNodes(businessText, roots, allScores);
        return allScores;
    }

    private void analyzeNodes(String businessText,
                               List<TaxonomyNode> nodes,
                               Map<String, Integer> allScores) {
        if (nodes == null || nodes.isEmpty()) return;

        Map<String, Integer> scores = callGemini(businessText, nodes);
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            allScores.put(entry.getKey(), entry.getValue());
            if (entry.getValue() > 0) {
                List<TaxonomyNode> children = taxonomyService.getChildrenOf(entry.getKey());
                if (!children.isEmpty()) {
                    analyzeNodes(businessText, children, allScores);
                }
            }
        }
    }

    private Map<String, Integer> callGemini(String businessText, List<TaxonomyNode> nodes) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key not configured; returning zero scores.");
            Map<String, Integer> zeros = new HashMap<>();
            for (TaxonomyNode n : nodes) zeros.put(n.getCode(), 0);
            return zeros;
        }

        String nodeList = buildNodeList(nodes);
        String prompt = buildPrompt(businessText, nodeList);

        try {
            String requestBody = buildRequestBody(prompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    GEMINI_URL + apiKey,
                    HttpMethod.POST,
                    entity,
                    String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseGeminiResponse(response.getBody(), nodes);
            } else {
                log.error("Gemini API returned status {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error calling Gemini API", e);
        }
        return zeroScores(nodes);
    }

    private String buildNodeList(List<TaxonomyNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (TaxonomyNode n : nodes) {
            sb.append(n.getCode()).append(": ").append(n.getName());
            if (n.getDescription() != null && !n.getDescription().isBlank()) {
                sb.append(" - ").append(n.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String businessText, String nodeList) {
        return "You are an expert in NATO C3 taxonomy classification. " +
                "Given the following taxonomy categories and a business requirement, " +
                "estimate the percentage match (0-100) for each category.\n\n" +
                "Business Requirement: " + businessText + "\n\n" +
                "Categories:\n" + nodeList + "\n" +
                "Respond ONLY with a valid JSON object where keys are the category codes " +
                "and values are integer percentages (0-100). " +
                "Example: {\"C1\": 0, \"C2\": 15, \"C3\": 80}";
    }

    private String buildRequestBody(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", prompt);
        content.put("parts", List.of(part));
        body.put("contents", List.of(content));
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Integer> parseGeminiResponse(String responseBody, List<TaxonomyNode> nodes) {
        try {
            Map<String, Object> responseMap = objectMapper.readValue(responseBody,
                    new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) responseMap.get("candidates");
            if (candidates == null || candidates.isEmpty()) return zeroScores(nodes);

            @SuppressWarnings("unchecked")
            Map<String, Object> content =
                    (Map<String, Object>) candidates.get(0).get("content");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return zeroScores(nodes);

            String text = (String) parts.get(0).get("text");
            if (text == null) return zeroScores(nodes);

            // Extract JSON from markdown code fences if present
            String jsonText = extractJson(text);

            Map<String, Integer> scores = objectMapper.readValue(jsonText,
                    new TypeReference<>() {});

            // Fill in any missing nodes with 0
            for (TaxonomyNode n : nodes) {
                scores.putIfAbsent(n.getCode(), 0);
            }
            return scores;
        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", responseBody, e);
            return zeroScores(nodes);
        }
    }

    /** Strip markdown code fences and locate the JSON object. */
    private String extractJson(String text) {
        // Remove markdown fences
        String stripped = text.replaceAll("```json", "").replaceAll("```", "").trim();
        // Find the outermost { ... }
        Pattern p = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}",
                Pattern.DOTALL);
        Matcher m = p.matcher(stripped);
        if (m.find()) {
            return m.group();
        }
        return stripped;
    }

    private Map<String, Integer> zeroScores(List<TaxonomyNode> nodes) {
        Map<String, Integer> zeros = new HashMap<>();
        for (TaxonomyNode n : nodes) zeros.put(n.getCode(), 0);
        return zeros;
    }
}
