package com.nato.taxonomy.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nato.taxonomy.dto.AnalysisResult;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-agnostic LLM service for taxonomy analysis.
 * Supports Gemini, OpenAI, DeepSeek, Qwen, Llama, and Mistral.
 *
 * <p>Provider selection priority:
 * <ol>
 *   <li>Explicit {@code llm.provider} config / {@code LLM_PROVIDER} env var</li>
 *   <li>Auto-detect from available API keys (Gemini → OpenAI → DeepSeek → Qwen → Llama → Mistral)</li>
 *   <li>Default: GEMINI (even if no key is configured)</li>
 * </ol>
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    // Gemini endpoint
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";

    private static final Pattern JSON_OBJECT_PATTERN =
            Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", Pattern.DOTALL);
    private static final String OPENAI_URL   = "https://api.openai.com/v1/chat/completions";
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String QWEN_URL     = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String LLAMA_URL    = "https://api.llama-api.com/chat/completions";
    private static final String MISTRAL_URL  = "https://api.mistral.ai/v1/chat/completions";

    // Default model names per provider
    private static final String OPENAI_MODEL   = "gpt-4o-mini";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final String QWEN_MODEL     = "qwen-plus";
    private static final String LLAMA_MODEL    = "llama3.1-70b";
    private static final String MISTRAL_MODEL  = "mistral-small-latest";

    @Value("${llm.provider:}")
    private String llmProviderConfig;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    @Value("${deepseek.api.key:}")
    private String deepseekApiKey;

    @Value("${qwen.api.key:}")
    private String qwenApiKey;

    @Value("${llama.api.key:}")
    private String llamaApiKey;

    @Value("${mistral.api.key:}")
    private String mistralApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TaxonomyService taxonomyService;
    private final PromptTemplateService promptTemplateService;

    public LlmService(RestTemplate restTemplate,
                      ObjectMapper objectMapper,
                      TaxonomyService taxonomyService,
                      PromptTemplateService promptTemplateService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.taxonomyService = taxonomyService;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * Returns the active provider based on the priority chain.
     */
    public LlmProvider getActiveProvider() {
        // Priority 1: explicit config / LLM_PROVIDER env var
        if (llmProviderConfig != null && !llmProviderConfig.isBlank()) {
            try {
                return LlmProvider.valueOf(llmProviderConfig.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown LLM provider '{}' in config; falling back to auto-detect",
                        llmProviderConfig);
            }
        }

        // Priority 2: auto-detect from available API keys
        if (geminiApiKey  != null && !geminiApiKey.isBlank())  return LlmProvider.GEMINI;
        if (openaiApiKey  != null && !openaiApiKey.isBlank())  return LlmProvider.OPENAI;
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) return LlmProvider.DEEPSEEK;
        if (qwenApiKey    != null && !qwenApiKey.isBlank())    return LlmProvider.QWEN;
        if (llamaApiKey   != null && !llamaApiKey.isBlank())   return LlmProvider.LLAMA;
        if (mistralApiKey != null && !mistralApiKey.isBlank()) return LlmProvider.MISTRAL;

        // Priority 3: default
        return LlmProvider.GEMINI;
    }

    /**
     * Returns {@code true} if at least one provider has a configured API key.
     */
    public boolean isAvailable() {
        return (geminiApiKey  != null && !geminiApiKey.isBlank())
            || (openaiApiKey  != null && !openaiApiKey.isBlank())
            || (deepseekApiKey != null && !deepseekApiKey.isBlank())
            || (qwenApiKey    != null && !qwenApiKey.isBlank())
            || (llamaApiKey   != null && !llamaApiKey.isBlank())
            || (mistralApiKey != null && !mistralApiKey.isBlank());
    }

    /**
     * Returns a human-readable name for the active provider (e.g. "Gemini", "OpenAI").
     */
    public String getActiveProviderName() {
        return switch (getActiveProvider()) {
            case GEMINI   -> "Gemini";
            case OPENAI   -> "OpenAI";
            case DEEPSEEK -> "DeepSeek";
            case QWEN     -> "Qwen";
            case LLAMA    -> "Llama";
            case MISTRAL  -> "Mistral";
        };
    }

    /**
     * Priority order for sequential analysis (Business Processes first, Information Products last).
     * Codes must match the virtual-root codes in TaxonomyService.SHEET_PREFIXES.
     */
    private static final List<String> ANALYSIS_PRIORITY =
            List.of("BP", "CP", "CR", "CO", "CI", "UA", "BR", "IP");

    /**
     * Recursively analyses business text against taxonomy nodes.
     * Starts with root nodes (level 0), then drills into children of nodes with >0% match.
     *
     * @deprecated Use {@link #analyzeWithBudget(String)} instead.
     */
    @Deprecated
    public Map<String, Integer> analyzeRecursive(String businessText) {
        Map<String, Integer> allScores = new HashMap<>();
        List<TaxonomyNode> roots = taxonomyService.getRootNodes();
        analyzeNodes(businessText, roots, allScores);
        return allScores;
    }

    /**
     * Analyses business text using a sequential, prioritized traversal of taxonomy roots.
     * Handles rate-limit errors gracefully by returning partial results.
     *
     * @param businessText the business requirement text
     * @return an {@link AnalysisResult} with status SUCCESS, PARTIAL, or ERROR
     */
    public AnalysisResult analyzeWithBudget(String businessText) {
        Map<String, Integer> allScores = new HashMap<>();
        List<String> warnings = new ArrayList<>();

        // Sort root nodes by priority order
        List<TaxonomyNode> roots = taxonomyService.getRootNodes();
        roots.sort(Comparator.comparingInt(r -> {
            int idx = ANALYSIS_PRIORITY.indexOf(r.getCode());
            return idx < 0 ? Integer.MAX_VALUE : idx;
        }));

        List<String> completedRoots = new ArrayList<>();
        List<String> skippedRoots  = new ArrayList<>();
        boolean rateLimitHit = false;

        for (TaxonomyNode root : roots) {
            if (rateLimitHit) {
                skippedRoots.add(root.getName());
                continue;
            }
            try {
                // Get Level-1 children of this root
                List<TaxonomyNode> level1Children = taxonomyService.getChildrenOf(root.getCode());
                if (level1Children.isEmpty()) {
                    completedRoots.add(root.getName());
                    continue;
                }

                // Score the Level-1 children
                Map<String, Integer> level1Scores = callLlmPropagating(businessText, level1Children);
                allScores.putAll(level1Scores);

                // Drill into children of matched Level-1 nodes
                for (Map.Entry<String, Integer> entry : level1Scores.entrySet()) {
                    if (entry.getValue() > 0) {
                        analyzeNodesPropagating(businessText, taxonomyService.getChildrenOf(entry.getKey()), allScores);
                    }
                }

                completedRoots.add(root.getName());

            } catch (LlmRateLimitException e) {
                rateLimitHit = true;
                skippedRoots.add(root.getName());
                log.warn("Rate limit hit while processing '{}': {}", root.getName(), e.getMessage());
            } catch (Exception e) {
                log.error("Error processing root '{}': {}", root.getName(), e.getMessage(), e);
                warnings.add("Error processing " + root.getName() + ": " + e.getMessage());
                completedRoots.add(root.getName());
            }
        }

        // Build the annotated tree from whatever scores were collected
        List<TaxonomyNodeDto> rawTree = taxonomyService.getFullTree();
        List<TaxonomyNodeDto> annotatedTree = new ArrayList<>();
        for (TaxonomyNodeDto rootDto : rawTree) {
            annotatedTree.add(taxonomyService.applyScores(rootDto, allScores));
        }

        AnalysisResult result = new AnalysisResult(allScores, annotatedTree);
        result.setWarnings(warnings);

        if (rateLimitHit) {
            String msg = "Rate limit reached after processing: " +
                    String.join(", ", completedRoots) + ". Skipped: " +
                    String.join(", ", skippedRoots) + ".";
            result.setStatus("PARTIAL");
            result.setErrorMessage(msg);
            result.getWarnings().add(0, msg);
        } else if (!warnings.isEmpty()) {
            // Some roots had errors but we continued — still treat as PARTIAL if no scores
            result.setStatus(allScores.values().stream().anyMatch(v -> v > 0) ? "SUCCESS" : "PARTIAL");
        } else {
            result.setStatus("SUCCESS");
        }

        return result;
    }

    private void analyzeNodesPropagating(String businessText,
                                          List<TaxonomyNode> nodes,
                                          Map<String, Integer> allScores) {
        if (nodes == null || nodes.isEmpty()) return;

        Map<String, Integer> scores = callLlmPropagating(businessText, nodes);
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            allScores.put(entry.getKey(), entry.getValue());
            if (entry.getValue() > 0) {
                List<TaxonomyNode> children = taxonomyService.getChildrenOf(entry.getKey());
                if (!children.isEmpty()) {
                    analyzeNodesPropagating(businessText, children, allScores);
                }
            }
        }
    }

    /** Used by the deprecated {@link #analyzeRecursive(String)} path. */
    private void analyzeNodes(String businessText,
                               List<TaxonomyNode> nodes,
                               Map<String, Integer> allScores) {
        if (nodes == null || nodes.isEmpty()) return;

        Map<String, Integer> scores = callLlm(businessText, nodes);
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

    /**
     * Streaming version of {@link #analyzeRecursive}: processes root nodes one at a time,
     * firing {@link AnalysisEventCallback} events so callers can forward results incrementally
     * (e.g. via Server-Sent Events).
     *
     * @param businessText the text to analyse
     * @param callback     receives phase, scores, expanding, complete and error events
     */
    public void analyzeStreaming(String businessText, AnalysisEventCallback callback) {
        Map<String, Integer> allScores = new HashMap<>();
        try {
            List<TaxonomyNode> roots = taxonomyService.getRootNodes();

            for (int i = 0; i < roots.size(); i++) {
                TaxonomyNode root = roots.get(i);
                int progress = (i * 100) / roots.size();
                callback.onPhase(
                        "Evaluating " + root.getName() + " (" + (i + 1) + "/" + roots.size() + ")…",
                        progress);

                Map<String, Integer> rootScore = callLlm(businessText, List.of(root));
                allScores.putAll(rootScore);
                callback.onScores(rootScore, root.getName() + " evaluated");

                if (rootScore.getOrDefault(root.getCode(), 0) > 0) {
                    List<TaxonomyNode> children = taxonomyService.getChildrenOf(root.getCode());
                    if (!children.isEmpty()) {
                        callback.onExpanding(root.getCode(),
                                children.stream().map(TaxonomyNode::getCode).toList());
                        analyzeStreamingNodes(businessText, children, allScores, callback);
                    }
                }
            }

            callback.onComplete("SUCCESS", allScores, List.of());
        } catch (Exception e) {
            log.error("Streaming analysis failed", e);
            callback.onError("PARTIAL", "Analysis failed: " + e.getMessage(),
                    allScores, List.of());
        }
    }

    private void analyzeStreamingNodes(String businessText,
                                        List<TaxonomyNode> nodes,
                                        Map<String, Integer> allScores,
                                        AnalysisEventCallback callback) {
        if (nodes == null || nodes.isEmpty()) return;

        Map<String, Integer> scores = callLlm(businessText, nodes);
        allScores.putAll(scores);
        callback.onScores(scores, "Evaluated " + nodes.size() + " node(s)");

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > 0) {
                List<TaxonomyNode> children = taxonomyService.getChildrenOf(entry.getKey());
                if (!children.isEmpty()) {
                    callback.onExpanding(entry.getKey(),
                            children.stream().map(TaxonomyNode::getCode).toList());
                    analyzeStreamingNodes(businessText, children, allScores, callback);
                }
            }
        }
    }

    /**
     * Evaluates a single batch of nodes against the business text.
     * Makes exactly ONE LLM API call. Does NOT recurse into children.
     * Used by interactive mode. Delegates to {@link #analyzeSingleBatchDetailed}.
     */
    public Map<String, Integer> analyzeSingleBatch(String businessText, List<TaxonomyNode> nodes) {
        return callLlm(businessText, nodes);
    }

    /**
     * Like {@link #analyzeSingleBatch} but also returns the prompt, raw LLM response,
     * provider name, and call duration. Used by the interactive-mode API endpoint so
     * the frontend can display the LLM communication log.
     */
    public com.nato.taxonomy.dto.LlmCallDetail analyzeSingleBatchDetailed(
            String businessText, List<TaxonomyNode> nodes) {
        try {
            return callLlmPropagatingDetailed(businessText, nodes);
        } catch (Exception e) {
            log.error("Error in detailed LLM call", e);
            com.nato.taxonomy.dto.LlmCallDetail detail = new com.nato.taxonomy.dto.LlmCallDetail();
            detail.setScores(zeroScores(nodes));
            detail.setProvider(getActiveProviderName());
            detail.setPrompt("");
            detail.setRawResponse("");
            detail.setDurationMs(0);
            return detail;
        }
    }

    private Map<String, Integer> callLlm(String businessText, List<TaxonomyNode> nodes) {
        try {
            return callLlmPropagating(businessText, nodes);
        } catch (Exception e) {
            log.error("Error calling LLM API", e);
            return zeroScores(nodes);
        }
    }

    /**
     * Like {@link #callLlm} but propagates {@link LlmRateLimitException} instead of swallowing it.
     */
    private Map<String, Integer> callLlmPropagating(String businessText, List<TaxonomyNode> nodes) {
        LlmProvider provider = getActiveProvider();
        String apiKey = getApiKey(provider);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️ LLM analysis skipped: No API key configured for provider {}. "
                    + "Set environment variable {}_API_KEY to enable AI analysis.",
                    provider, provider.name());
            return zeroScores(nodes);
        }

        String nodeList = buildNodeList(nodes);
        String taxonomyCode = nodes.isEmpty() ? "default" : nodes.get(0).getTaxonomyRoot();
        String prompt = promptTemplateService.renderPrompt(taxonomyCode, businessText, nodeList);

        log.info("LLM Request [{}] — sending prompt for {} nodes: {}",
                provider, nodes.size(), nodeList.substring(0, Math.min(nodeList.length(), 200)));
        log.debug("Full LLM prompt:\n{}", prompt);

        if (provider == LlmProvider.GEMINI) {
            return callGemini(prompt, apiKey, nodes);
        } else {
            return callOpenAiCompatible(prompt, apiKey, provider, nodes);
        }
    }

    /**
     * Like {@link #callLlmPropagating} but also captures timing, the prompt, and the
     * raw LLM text response, returning them in a {@link com.nato.taxonomy.dto.LlmCallDetail}.
     */
    private com.nato.taxonomy.dto.LlmCallDetail callLlmPropagatingDetailed(
            String businessText, List<TaxonomyNode> nodes) {
        LlmProvider provider = getActiveProvider();
        String apiKey = getApiKey(provider);

        com.nato.taxonomy.dto.LlmCallDetail detail = new com.nato.taxonomy.dto.LlmCallDetail();
        detail.setProvider(getActiveProviderName());

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️ LLM analysis skipped: No API key configured for provider {}. "
                    + "Set environment variable {}_API_KEY to enable AI analysis.",
                    provider, provider.name());
            detail.setScores(zeroScores(nodes));
            detail.setPrompt("");
            detail.setRawResponse("");
            detail.setDurationMs(0);
            return detail;
        }

        String nodeList = buildNodeList(nodes);
        String taxonomyCode = nodes.isEmpty() ? "default" : nodes.get(0).getTaxonomyRoot();
        String prompt = promptTemplateService.renderPrompt(taxonomyCode, businessText, nodeList);
        detail.setPrompt(prompt);

        log.info("LLM Request [{}] — sending prompt for {} nodes: {}",
                provider, nodes.size(), nodeList.substring(0, Math.min(nodeList.length(), 200)));
        log.debug("Full LLM prompt:\n{}", prompt);

        long start = System.currentTimeMillis();
        String apiResponseBody;
        if (provider == LlmProvider.GEMINI) {
            apiResponseBody = callGeminiHttpBody(prompt, apiKey);
        } else {
            apiResponseBody = callOpenAiCompatibleHttpBody(prompt, apiKey, provider);
        }
        detail.setDurationMs(System.currentTimeMillis() - start);

        if (apiResponseBody == null) {
            detail.setScores(zeroScores(nodes));
            detail.setRawResponse("");
            return detail;
        }

        String rawText = (provider == LlmProvider.GEMINI)
                ? extractGeminiText(apiResponseBody)
                : extractOpenAiText(apiResponseBody);
        detail.setRawResponse(rawText != null ? rawText : "");

        if (rawText != null) {
            try {
                detail.setScores(parseScoresFromText(rawText, nodes));
            } catch (Exception e) {
                log.error("Failed to parse scores in detailed LLM call", e);
                detail.setScores(zeroScores(nodes));
            }
        } else {
            detail.setScores(zeroScores(nodes));
        }
        return detail;
    }

    /**
     * Makes the Gemini HTTP call and returns the raw API response body, or {@code null}
     * on error (including logging). Rate-limit exceptions are propagated.
     */
    private String callGeminiHttpBody(String prompt, String apiKey) {
        Map<String, Object> body    = new LinkedHashMap<>();
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, Object> part    = new LinkedHashMap<>();
        part.put("text", prompt);
        content.put("parts", List.of(part));
        body.put("contents", List.of(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(
                        GEMINI_URL + apiKey, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    throw new LlmRateLimitException(
                            "Gemini rate limit (HTTP 429): " + e.getResponseBodyAsString(), e);
                }
                throw new RuntimeException("Gemini API error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException e) {
                throw new RuntimeException("Gemini API server error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            }

            String responseBody = response.getBody();

            if (responseBody != null && responseBody.contains("RESOURCE_EXHAUSTED")) {
                throw new LlmRateLimitException("Gemini quota exhausted: " + responseBody);
            }
            if (responseBody != null && responseBody.contains("\"error\"")) {
                log.error("Gemini API returned error in body: {}", responseBody);
                return null;
            }

            if (response.getStatusCode().is2xxSuccessful() && responseBody != null) {
                log.info("LLM Response [GEMINI] — raw response (first 500 chars): {}",
                        responseBody.substring(0, Math.min(responseBody.length(), 500)));
                return responseBody;
            }
            log.error("Gemini API returned status {}", response.getStatusCode());
            return null;
        } catch (LlmRateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling Gemini API", e);
            return null;
        }
    }

    private Map<String, Integer> callGemini(String prompt, String apiKey,
                                             List<TaxonomyNode> nodes) {
        String responseBody = callGeminiHttpBody(prompt, apiKey);
        if (responseBody == null) return zeroScores(nodes);
        return parseGeminiResponse(responseBody, nodes);
    }

    /**
     * Makes an OpenAI-compatible HTTP call and returns the raw API response body, or
     * {@code null} on error. Rate-limit exceptions are propagated.
     */
    private String callOpenAiCompatibleHttpBody(String prompt, String apiKey,
                                                 LlmProvider provider) {
        String url = switch (provider) {
            case OPENAI   -> OPENAI_URL;
            case DEEPSEEK -> DEEPSEEK_URL;
            case QWEN     -> QWEN_URL;
            case LLAMA    -> LLAMA_URL;
            case MISTRAL  -> MISTRAL_URL;
            default -> throw new IllegalArgumentException("Not an OpenAI-compatible provider: " + provider);
        };
        String model = switch (provider) {
            case OPENAI   -> OPENAI_MODEL;
            case DEEPSEEK -> DEEPSEEK_MODEL;
            case QWEN     -> QWEN_MODEL;
            case LLAMA    -> LLAMA_MODEL;
            case MISTRAL  -> MISTRAL_MODEL;
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };

        Map<String, Object> body    = new LinkedHashMap<>();
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        body.put("model", model);
        body.put("messages", List.of(message));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    throw new LlmRateLimitException(
                            provider + " rate limit (HTTP 429): " + e.getResponseBodyAsString(), e);
                }
                throw new RuntimeException(provider + " API error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException e) {
                throw new RuntimeException(provider + " API server error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            }

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("LLM Response [{}] — raw response (first 500 chars): {}",
                        provider, response.getBody().substring(0, Math.min(response.getBody().length(), 500)));
                return response.getBody();
            }
            log.error("{} API returned status {}", provider, response.getStatusCode());
            return null;
        } catch (LlmRateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling {} API", provider, e);
            return null;
        }
    }

    private Map<String, Integer> callOpenAiCompatible(String prompt, String apiKey,
                                                       LlmProvider provider,
                                                       List<TaxonomyNode> nodes) {
        String responseBody = callOpenAiCompatibleHttpBody(prompt, apiKey, provider);
        if (responseBody == null) return zeroScores(nodes);
        return parseOpenAiResponse(responseBody, nodes);
    }

    // ── Response parsers ──────────────────────────────────────────────────────

    /** Extracts the raw LLM text from a Gemini API JSON response body, or {@code null} on failure. */
    private String extractGeminiText(String responseBody) {
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

    /** Extracts the raw LLM text from an OpenAI-compatible API JSON response body, or {@code null}. */
    private String extractOpenAiText(String responseBody) {
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

    private Map<String, Integer> parseGeminiResponse(String responseBody,
                                                      List<TaxonomyNode> nodes) {
        String text = extractGeminiText(responseBody);
        if (text == null) {
            log.error("Failed to parse Gemini response: {}", responseBody);
            return zeroScores(nodes);
        }
        try {
            return parseScoresFromText(text, nodes);
        } catch (Exception e) {
            log.error("Failed to parse scores from Gemini response: {}", responseBody, e);
            return zeroScores(nodes);
        }
    }

    private Map<String, Integer> parseOpenAiResponse(String responseBody,
                                                      List<TaxonomyNode> nodes) {
        String text = extractOpenAiText(responseBody);
        if (text == null) {
            log.error("Failed to parse OpenAI-compatible response: {}", responseBody);
            return zeroScores(nodes);
        }
        try {
            return parseScoresFromText(text, nodes);
        } catch (Exception e) {
            log.error("Failed to parse scores from OpenAI-compatible response: {}", responseBody, e);
            return zeroScores(nodes);
        }
    }

    private Map<String, Integer> parseScoresFromText(String text,
                                                      List<TaxonomyNode> nodes) throws Exception {
        String jsonText = extractJson(text);
        Map<String, Integer> scores = objectMapper.readValue(jsonText, new TypeReference<>() {});
        for (TaxonomyNode n : nodes) {
            scores.putIfAbsent(n.getCode(), 0);
        }
        log.info("LLM Scores parsed: {}", scores);
        return scores;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getApiKey(LlmProvider provider) {
        return switch (provider) {
            case GEMINI   -> geminiApiKey;
            case OPENAI   -> openaiApiKey;
            case DEEPSEEK -> deepseekApiKey;
            case QWEN     -> qwenApiKey;
            case LLAMA    -> llamaApiKey;
            case MISTRAL  -> mistralApiKey;
        };
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

    /** Strip markdown code fences and locate the outermost JSON object. */
    private String extractJson(String text) {
        String stripped = text.replaceAll("```json", "").replaceAll("```", "").trim();
        Matcher m = JSON_OBJECT_PATTERN.matcher(stripped);
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
