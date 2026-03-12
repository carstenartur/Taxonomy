package com.taxonomy.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LLM API responses (Gemini and OpenAI-compatible) and extracts structured
 * score/reason data. Also provides score normalization (largest-remainder method).
 *
 * <p>This class is stateless and does not depend on Spring — it can be unit-tested
 * without a running application context.</p>
 */
public class LlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseParser.class);

    private static final Pattern JSON_OBJECT_PATTERN =
            Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public LlmResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Response text extraction ──────────────────────────────────────────────

    /**
     * Extracts the raw LLM text from a Gemini API JSON response body, or {@code null}.
     */
    public String extractGeminiText(String responseBody) {
        try {
            Map<String, Object> responseMap = objectMapper.readValue(responseBody,
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) responseMap.get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> content =
                    (Map<String, Object>) candidates.get(0).get("content");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return null;
            return (String) parts.get(0).get("text");
        } catch (Exception e) {
            log.debug("Failed to extract text from Gemini response", e);
            return null;
        }
    }

    /**
     * Extracts the raw LLM text from an OpenAI-compatible API JSON response body, or {@code null}.
     */
    public String extractOpenAiText(String responseBody) {
        try {
            Map<String, Object> responseMap = objectMapper.readValue(responseBody,
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) responseMap.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> message =
                    (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            log.debug("Failed to extract text from OpenAI-compatible response", e);
            return null;
        }
    }

    // ── Score parsing ─────────────────────────────────────────────────────────

    /**
     * Parses a Gemini response body into scores, falling back to zero scores on failure.
     */
    public Map<String, Integer> parseGeminiResponse(String responseBody,
                                                     List<TaxonomyNode> nodes, int parentScore) {
        String text = extractGeminiText(responseBody);
        if (text == null) {
            log.error("Failed to parse Gemini response: {}", responseBody);
            return zeroScores(nodes);
        }
        try {
            return parseScoreParseResult(text, nodes, parentScore).scores();
        } catch (Exception e) {
            log.error("Failed to parse scores from Gemini response: {}", responseBody, e);
            return zeroScores(nodes);
        }
    }

    /**
     * Parses an OpenAI-compatible response body into scores, falling back to zero scores on failure.
     */
    public Map<String, Integer> parseOpenAiResponse(String responseBody,
                                                     List<TaxonomyNode> nodes, int parentScore) {
        String text = extractOpenAiText(responseBody);
        if (text == null) {
            log.error("Failed to parse OpenAI-compatible response: {}", responseBody);
            return zeroScores(nodes);
        }
        try {
            return parseScoreParseResult(text, nodes, parentScore).scores();
        } catch (Exception e) {
            log.error("Failed to parse scores from OpenAI-compatible response: {}", responseBody, e);
            return zeroScores(nodes);
        }
    }

    /**
     * Parses both scores and reasons from LLM response text.
     * Supports two formats (backward-compatible):
     * <ul>
     *   <li>Old format: {@code {"C1": 80, "C2": 0}} — integer values, no reasons</li>
     *   <li>New format: {@code {"C1": {"score": 80, "reason": "..."}, "C2": {"score": 0, "reason": "..."}}}
     * </ul>
     * <p>The LLM is asked to distribute exactly {@code parentScore} across child categories.
     * If the raw sum already matches, scores are passed through without normalization.
     * If the raw sum differs, scores are normalized as a fallback.
     * If the raw sum <em>exceeds</em> the parent score, a {@link TaxonomyDiscrepancy} is
     * recorded — this signals that the LLM considers the children collectively more relevant
     * than the parent budget allows, which is a useful taxonomy inconsistency indicator.
     */
    public LlmService.ScoreParseResult parseScoreParseResult(String text,
                                                              List<TaxonomyNode> nodes,
                                                              int parentScore) throws Exception {
        String jsonText = extractJson(text);
        Map<String, Object> raw = objectMapper.readValue(jsonText, new TypeReference<>() {});

        Map<String, Integer> scores = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String code = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Number num) {
                scores.put(code, num.intValue());
            } else if (value instanceof Map<?, ?> obj) {
                Object scoreVal = obj.get("score");
                int score = scoreVal instanceof Number n ? n.intValue() : 0;
                scores.put(code, score);
                Object reasonVal = obj.get("reason");
                if (reasonVal instanceof String reasonStr && !reasonStr.isBlank()) {
                    reasons.put(code, reasonStr);
                }
            }
        }

        for (TaxonomyNode n : nodes) {
            scores.putIfAbsent(n.getCode(), 0);
        }

        int rawSum = scores.values().stream().mapToInt(Integer::intValue).sum();

        // Detect discrepancy: raw child sum exceeds parent budget
        TaxonomyDiscrepancy discrepancy = null;
        if (rawSum > parentScore) {
            String parentCode = deriveParentCode(nodes);
            discrepancy = new TaxonomyDiscrepancy(parentCode, parentScore, rawSum);
            log.warn("Discrepancy detected: children of '{}' sum to {} but parent score is {}",
                    parentCode, rawSum, parentScore);
        }

        // Only normalize if sum doesn't match parent (fallback); trust the LLM otherwise
        Map<String, Integer> finalScores;
        if (rawSum == parentScore) {
            finalScores = scores;
        } else {
            finalScores = normalizeToParent(scores, parentScore);
        }

        log.info("LLM Scores parsed (target {}, raw sum {}): {}", parentScore, rawSum, finalScores);
        return new LlmService.ScoreParseResult(finalScores, reasons, discrepancy);
    }

    // ── Score normalization ───────────────────────────────────────────────────

    /**
     * Normalizes a set of scores proportionally so that their sum equals {@code target}.
     * Uses the largest-remainder method to ensure exact sum after integer rounding.
     */
    public Map<String, Integer> normalizeToParent(Map<String, Integer> scores, int target) {
        int total = scores.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return scores;

        Map<String, Integer> normalized = new LinkedHashMap<>();
        List<Map.Entry<String, Double>> fractionals = new ArrayList<>();
        int runningSum = 0;

        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            double scaled = (double) e.getValue() * target / total;
            int floor = (int) scaled;
            normalized.put(e.getKey(), floor);
            fractionals.add(Map.entry(e.getKey(), scaled - floor));
            runningSum += floor;
        }

        int remaining = target - runningSum;
        fractionals.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < remaining && i < fractionals.size(); i++) {
            normalized.merge(fractionals.get(i).getKey(), 1, Integer::sum);
        }

        return normalized;
    }

    /**
     * Normalizes a set of scores proportionally so that their sum equals 100.
     */
    public Map<String, Integer> normalizeToHundred(Map<String, Integer> scores) {
        return normalizeToParent(scores, 100);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Strip markdown code fences and locate the outermost JSON object. */
    public String extractJson(String text) {
        String stripped = text.replaceAll("```json", "").replaceAll("```", "").trim();
        Matcher m = JSON_OBJECT_PATTERN.matcher(stripped);
        if (m.find()) {
            return m.group();
        }
        return stripped;
    }

    /**
     * Returns a map of zero scores for all given nodes.
     */
    public Map<String, Integer> zeroScores(List<TaxonomyNode> nodes) {
        Map<String, Integer> zeros = new HashMap<>();
        for (TaxonomyNode n : nodes) zeros.put(n.getCode(), 0);
        return zeros;
    }

    /**
     * Derives the parent code from a batch of sibling nodes.
     */
    String deriveParentCode(List<TaxonomyNode> nodes) {
        if (nodes.isEmpty()) return "unknown";
        String parentCode = nodes.get(0).getParentCode();
        if (parentCode != null && !parentCode.isBlank()) return parentCode;
        String root = nodes.get(0).getTaxonomyRoot();
        return root != null ? root : "unknown";
    }
}
