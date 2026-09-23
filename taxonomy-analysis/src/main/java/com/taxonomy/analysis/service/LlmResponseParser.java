package com.taxonomy.analysis.service;

import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.StreamReadFeature;
import com.taxonomy.analysis.assessment.ChildAssessmentContract;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import java.math.BigDecimal;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.catalog.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Parses LLM API responses (Gemini and OpenAI-compatible) and extracts structured
 * score/reason data. Also provides score normalization (largest-remainder method).
 *
 * <p>This class is stateless and does not depend on Spring — it can be unit-tested
 * without a running application context.</p>
 */
public class LlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseParser.class);

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
     * <p>All offered IDs require explicit valid scores. Missing, foreign or malformed
     * decisions invalidate the batch before normalization; absence is not negative evidence.
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
        Map<String, Object> raw = parseChildAssessment(text,
                nodes.stream().map(TaxonomyNode::getCode).toList(), (code, value) -> value);

        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String code = entry.getKey();
            Object value = entry.getValue();
            Object scoreValue = value instanceof Map<?, ?> object ? object.get("score") : value;
            if (!(scoreValue instanceof Number number)) {
                throw new IllegalArgumentException("Category response for " + code
                        + " requires an explicit integer score between 0 and 100");
            }
            final int score;
            try {
                // The reader preserves decimal tokens; conversion through double would
                // turn precision-boundary fractions into apparently integral scores.
                score = new BigDecimal(number.toString()).intValueExact();
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException("Category response for " + code
                        + " requires an exact integer score between 0 and 100", invalid);
            }
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("Category response for " + code
                        + " requires an explicit integer score between 0 and 100");
            }
            scores.put(code, score);
            if (value instanceof Map<?, ?> object && object.get("reason") instanceof String reason
                    && !reason.isBlank()) {
                reasons.put(code, reason);
            }
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

    /**
     * Parses independent concrete-product suitability scores. Unlike hierarchical category
     * scoring, values are never normalized to the parent score. The response must contain
     * exactly the requested product codes; scores below {@code minimumScore} are retained as
     * explicit zeroes so downstream selection and coverage-gap detection use one contract.
     */
    public LlmService.ScoreParseResult parseIndependentScoreParseResult(
            String text, List<TaxonomyNode> nodes, int minimumScore) throws Exception {
        Map<String, Object> raw = parseChildAssessment(text,
                nodes.stream().map(TaxonomyNode::getCode).toList(), (code, value) -> value);

        int threshold = Math.max(0, Math.min(100, minimumScore));
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes) {
            Object value = raw.get(node.getCode());
            if (!(value instanceof Map<?, ?> object)) {
                throw new IllegalArgumentException(
                        "Independent product response for " + node.getCode()
                                + " must be an object containing score and reason");
            }
            Object scoreValue = object.get("score");
            if (!(scoreValue instanceof Number number)) {
                throw new IllegalArgumentException(
                        "Independent product response for " + node.getCode()
                                + " has no numeric score");
            }
            Object reasonValue = object.get("reason");
            if (!(reasonValue instanceof String reason) || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "Independent product response for " + node.getCode()
                                + " has no non-blank reason");
            }
            int score = Math.max(0, Math.min(100, number.intValue()));
            scores.put(node.getCode(), score >= threshold ? score : 0);
            reasons.put(node.getCode(), reason);
        }
        log.info("Independent product scores parsed (threshold {}): {}", threshold, scores);
        return new LlmService.ScoreParseResult(scores, reasons, null);
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

    /**
     * Shared structured child evaluation boundary. The policy interprets values; the
     * common contract rejects missing/unknown IDs before any child is interpreted.
     * This is used by category relevance, independent product suitability and relation
     * assessment. Each caller retains its own semantics after complete identity validation.
     */
    public <T> Map<String, T> parseChildAssessment(String text, List<String> candidateIds,
                                                 BiFunction<String, Object, T> policy) {
        return ChildAssessmentContract.decode(candidateIds, readScoreObject(text), policy);
    }

    private Map<String, Object> readScoreObject(String text) {
        String jsonText = extractJson(text);
        if (!jsonText.startsWith("{")) {
            throw new IllegalArgumentException("Expected a JSON object for a child assessment, but the LLM "
                    + "returned empty or non-JSON text. Inspect the raw response in the LLM communication log.");
        }
        return objectMapper.readerFor(new TypeReference<Map<String, Object>>() {})
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(jsonText);
    }

    /**
     * Locates the first outer JSON container in plain text or a Markdown code block.
     * Complete bracketed prose such as {@code [IP]} is skipped when it is not JSON.
     * Actual arrays remain arrays so the score parser rejects a non-object root.
     * Respect strings and escapes: brackets and code fences inside a reason are
     * data, not delimiters. An incomplete outer container is returned intact for
     * rejection, never replaced by a seemingly valid inner object.
     */
    public String extractJson(String text) {
        String stripped = text == null ? "" : text.trim();
        int start = -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < stripped.length(); i++) {
            char character = stripped.charAt(i);
            if (start < 0) {
                if (character != '{' && character != '[') continue;
                start = i;
            }
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{' || character == '[') {
                depth++;
            } else if ((character == '}' || character == ']') && --depth == 0) {
                String candidate = stripped.substring(start, i + 1);
                if (stripped.charAt(start) == '[') {
                    try {
                        objectMapper.readTree(candidate);
                    } catch (StreamReadException notJson) {
                        // Skip the whole prose span, never salvage an object inside it.
                        // Other failures, including configured read limits, must propagate.
                        start = -1;
                        continue;
                    }
                }
                return candidate;
            }
        }
        return start < 0 ? stripped : stripped.substring(start);
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
