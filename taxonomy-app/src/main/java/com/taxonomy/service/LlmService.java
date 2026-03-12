package com.taxonomy.service;

import tools.jackson.databind.ObjectMapper;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.taxonomy.dto.SavedAnalysis;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Holds the parsed scores, optional reasons, and an optional discrepancy from an LLM response.
     * Backward-compatible: if the LLM returns the old integer-only format, reasons will be empty.
     * A non-null {@code discrepancy} indicates the LLM's raw child scores exceeded the parent
     * budget — a taxonomy inconsistency signal.
     */
    record ScoreParseResult(Map<String, Integer> scores, Map<String, String> reasons,
                            TaxonomyDiscrepancy discrepancy) {
        ScoreParseResult(Map<String, Integer> scores, Map<String, String> reasons) {
            this(scores, reasons, null);
        }
        static ScoreParseResult empty(List<TaxonomyNode> nodes) {
            Map<String, Integer> zeros = new HashMap<>();
            for (TaxonomyNode n : nodes) zeros.put(n.getCode(), 0);
            return new ScoreParseResult(zeros, Map.of(), null);
        }
    }

    // Gemini endpoint
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=";

    /** Minimum score (inclusive) for a node to appear as a cross-reference in leaf justification. */
    private static final int MIN_CROSS_REFERENCE_SCORE = 50;

    /** Maximum number of cross-reference nodes included in a leaf justification prompt. */
    private static final int MAX_CROSS_REFERENCES = 5;

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

    /**
     * When {@code true} (set via {@code llm.mock=true} property or {@code LLM_MOCK=true} env var),
     * the service returns hardcoded realistic scores instead of calling any real LLM API.
     * Useful for screenshot generation and CI environments where no API key is available.
     */
    @Value("${llm.mock:false}")
    private boolean llmMock;

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
    private final LocalEmbeddingService localEmbeddingService;
    private final SavedAnalysisService savedAnalysisService;
    private final LlmResponseParser responseParser;

    // ── Cached mock data loaded from classpath ────────────────────────────────
    private volatile SavedAnalysis cachedMockAnalysis = null;

    // ── Mock-mode scores for "Provide secure voice communications between HQ and deployed forces" ──
    // These are used as per-taxonomy independent scores (0–100 each), NOT a pie-chart that sums to 100.
    // Each value represents "how well does this taxonomy cover the requirement?" independently.
    private static final Map<String, Integer> MOCK_ROOT_SCORES = Map.of(
            "CO", 90,
            "CR", 70,
            "CP", 55,
            "IP", 30,
            "BP", 25,
            "CI", 15,
            "UA", 10,
            "BR",  0
    );

    private static final Map<String, String> MOCK_ROOT_REASONS = Map.of(
            "CO", "Directly related to providing secure voice communication channels between headquarters and deployed forces.",
            "CR", "Communications resources are needed to establish the secure voice links.",
            "CP", "Capability packages enable the implementation of secure voice communications.",
            "IP", "Infrastructure products form the physical foundation for voice communications.",
            "BP", "Business processes govern the use of secure voice communications.",
            "CI", "COI services may leverage secure voice communication channels.",
            "UA", "User applications provide interfaces for voice communication.",
            "BR", "Business rules define policies for secure voice communication usage."
    );

    // ── Diagnostics tracking ──────────────────────────────────────────────────
    private final AtomicLong totalCalls       = new AtomicLong(0);
    private final AtomicLong successfulCalls  = new AtomicLong(0);
    private final AtomicLong failedCalls      = new AtomicLong(0);
    private volatile Instant lastCallTime     = null;
    private volatile boolean lastCallSuccess  = false;
    private volatile String  lastError        = null;

    public LlmService(RestTemplate restTemplate,
                      ObjectMapper objectMapper,
                      TaxonomyService taxonomyService,
                      PromptTemplateService promptTemplateService,
                      LocalEmbeddingService localEmbeddingService,
                      SavedAnalysisService savedAnalysisService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.taxonomyService = taxonomyService;
        this.promptTemplateService = promptTemplateService;
        this.localEmbeddingService = localEmbeddingService;
        this.savedAnalysisService = savedAnalysisService;
        this.responseParser = new LlmResponseParser(objectMapper);
    }

    // ── Mock-mode helpers ─────────────────────────────────────────────────────

    /**
     * Loads the mock {@link SavedAnalysis} from classpath, caching it after first load.
     * Falls back to the hardcoded score maps if the file cannot be read.
     */
    private SavedAnalysis loadMockAnalysis() {
        if (cachedMockAnalysis != null) { return cachedMockAnalysis; }
        try {
            cachedMockAnalysis = savedAnalysisService.loadFromClasspath(
                    "mock-scores/secure-voice-comms.json");
            log.info("MOCK — loaded mock scores from classpath:mock-scores/secure-voice-comms.json");
        } catch (Exception e) {
            log.warn("MOCK — failed to load mock scores from classpath, using hardcoded fallback: {}", e.getMessage());
        }
        return cachedMockAnalysis;
    }

    /**
     * Builds mock {@link ScoreParseResult} for the given nodes.
     *
     * <p>First tries to look up each node's score in the saved analysis JSON loaded from
     * {@code classpath:mock-scores/secure-voice-comms.json}. The JSON was pre-computed by
     * {@code MockScoreGeneratorIT} using a hierarchical distribution algorithm that guarantees
     * children scores sum exactly to their parent's score at every level.
     *
     * <p>When <em>all</em> nodes are found in the JSON the pre-computed scores are returned
     * as-is, without any re-normalization.  Re-normalizing would distort the carefully computed
     * values: for example, a root node scored alone against {@code parentScore=100} would be
     * scaled up to 100, causing all roots to appear equally relevant and breaking the
     * parent&gt;=child constraint deeper in the tree.
     *
     * <p>If any node is missing from the JSON (rare, only for taxonomy nodes that were not
     * present when the JSON was generated), the fallback scores are capped at {@code parentScore}
     * and the whole batch is normalized so children sum to the parent budget.
     *
     * @param nodes       the nodes to score
     * @param parentScore the budget the child scores must sum to (used only in the fallback path)
     */
    private ScoreParseResult buildMockScores(List<TaxonomyNode> nodes, int parentScore) {
        SavedAnalysis mockAnalysis = loadMockAnalysis();
        Map<String, Integer> scores = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();
        boolean allFromJson = true;
        for (TaxonomyNode node : nodes) {
            // Try to look up score from the saved analysis JSON
            if (mockAnalysis != null && mockAnalysis.getScores() != null
                    && mockAnalysis.getScores().containsKey(node.getCode())) {
                scores.put(node.getCode(), mockAnalysis.getScores().get(node.getCode()));
                String reason = (mockAnalysis.getReasons() != null)
                        ? mockAnalysis.getReasons().get(node.getCode()) : null;
                if (reason == null) {
                    String root = node.getTaxonomyRoot() != null ? node.getTaxonomyRoot() : node.getCode();
                    reason = MOCK_ROOT_REASONS.getOrDefault(root,
                            "Relevant to the secure voice communications requirement.");
                }
                reasons.put(node.getCode(), reason);
            } else {
                allFromJson = false;
                // Fallback: use hardcoded root scores with deterministic variation,
                // capped at parentScore so individual scores never exceed the parent budget.
                String root = node.getTaxonomyRoot() != null ? node.getTaxonomyRoot() : node.getCode();
                int baseScore = MOCK_ROOT_SCORES.getOrDefault(root, 30);
                // Add deterministic variation ±15 based on the node code
                int variation = Math.floorMod(node.getCode().hashCode(), 31) - 15;
                int score = Math.max(0, Math.min(parentScore, baseScore + variation));
                scores.put(node.getCode(), score);
                String reason = MOCK_ROOT_REASONS.getOrDefault(root,
                        "Relevant to the secure voice communications requirement.");
                reasons.put(node.getCode(), reason);
            }
        }

        // When every score came from the pre-computed JSON the distribution is already correct:
        // MockScoreGeneratorIT.distributeScores() guarantees children sum exactly to their parent.
        // Return the JSON values directly — re-normalizing would distort them.
        if (allFromJson) {
            recordSuccess();
            return new ScoreParseResult(scores, reasons, null);
        }

        // Fallback path (one or more nodes missing from JSON): normalize so the batch sums to
        // parentScore, and record a discrepancy if the raw sum already exceeds the budget.
        int rawSum = scores.values().stream().mapToInt(Integer::intValue).sum();

        TaxonomyDiscrepancy discrepancy = null;
        if (rawSum > parentScore) {
            String parentCode = responseParser.deriveParentCode(nodes);
            discrepancy = new TaxonomyDiscrepancy(parentCode, parentScore, rawSum);
            log.warn("Mock discrepancy detected: children of '{}' sum to {} but parent score is {}",
                    parentCode, rawSum, parentScore);
        }

        Map<String, Integer> finalScores;
        if (rawSum == parentScore || rawSum == 0) {
            finalScores = scores;
        } else {
            finalScores = normalizeToParent(scores, parentScore);
        }

        recordSuccess();
        return new ScoreParseResult(finalScores, reasons, discrepancy);
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
     * Returns {@code true} if at least one provider has a configured API key,
     * or if the active provider is {@link LlmProvider#LOCAL_ONNX} (which requires no key),
     * or if mock mode is active.
     */
    public boolean isAvailable() {
        if (llmMock) return true;
        if (getActiveProvider() == LlmProvider.LOCAL_ONNX) return true;
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
        if (llmMock) return "Mock";
        return switch (getActiveProvider()) {
            case GEMINI      -> "Gemini";
            case OPENAI      -> "OpenAI";
            case DEEPSEEK    -> "DeepSeek";
            case QWEN        -> "Qwen";
            case LLAMA       -> "Llama";
            case MISTRAL     -> "Mistral";
            case LOCAL_ONNX  -> "Local (all-MiniLM-L6-v2)";
        };
    }

    /**
     * Priority order for sequential analysis (Business Processes first, Information Products last).
     * Codes must match the virtual-root codes in TaxonomyService.SHEET_PREFIXES.
     */
    private static final List<String> ANALYSIS_PRIORITY =
            List.of("BP", "CP", "CR", "CO", "CI", "UA", "BR", "IP");

    /**
     * Analyses business text using a sequential, prioritized traversal of taxonomy roots.
     * Each root is scored independently (0–100), then children distribute the root's score.
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
                // Score root independently (0-100) to gauge branch relevance
                Map<String, Integer> rootScore = callLlmPropagating(businessText, List.of(root), 100);
                allScores.putAll(rootScore);
                int score = rootScore.getOrDefault(root.getCode(), 0);

                if (score > 0) {
                    // Score Level-1 children distributing the root's score
                    List<TaxonomyNode> level1Children = taxonomyService.getChildrenOf(root.getCode());
                    if (!level1Children.isEmpty()) {
                        analyzeNodesPropagating(businessText, level1Children, allScores, score);
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
                                          Map<String, Integer> allScores,
                                          int parentScore) {
        if (nodes == null || nodes.isEmpty()) return;

        Map<String, Integer> scores = callLlmPropagating(businessText, nodes, parentScore);
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            allScores.put(entry.getKey(), entry.getValue());
            if (entry.getValue() > 0) {
                List<TaxonomyNode> children = taxonomyService.getChildrenOf(entry.getKey());
                if (!children.isEmpty()) {
                    analyzeNodesPropagating(businessText, children, allScores, entry.getValue());
                }
            }
        }
    }

    /**
     * Processes root nodes one at a time, firing {@link AnalysisEventCallback} events so callers
     * can forward results incrementally (e.g. via Server-Sent Events).
     *
     * <p>Each root is scored independently (0–100) to determine how relevant that taxonomy
     * branch is to the business requirement. Children then distribute the root's score.
     *
     * @param businessText the text to analyse
     * @param callback     receives phase, scores, expanding, complete and error events
     */
    public void analyzeStreaming(String businessText, AnalysisEventCallback callback) {
        Map<String, Integer> allScores = new HashMap<>();
        List<TaxonomyDiscrepancy> allDiscrepancies = new ArrayList<>();
        try {
            List<TaxonomyNode> roots = taxonomyService.getRootNodes();

            for (int i = 0; i < roots.size(); i++) {
                TaxonomyNode root = roots.get(i);
                int progress = (i * 100) / roots.size();
                callback.onPhase(
                        "Evaluating " + root.getName() + " (" + (i + 1) + "/" + roots.size() + ")…",
                        progress);

                // Score root independently (0-100) to gauge branch relevance
                ScoreParseResult rootResult = callLlmResult(businessText, List.of(root), 100);
                int rootScore = rootResult.scores().getOrDefault(root.getCode(), 0);
                allScores.put(root.getCode(), rootScore);
                callback.onScores(rootResult.scores(), rootResult.reasons(),
                        root.getName() + " scored " + rootScore + "/100");

                if (rootScore > 0) {
                    // Score Level-1 children distributing the root's score
                    List<TaxonomyNode> level1Children = taxonomyService.getChildrenOf(root.getCode());
                    if (!level1Children.isEmpty()) {
                        callback.onExpanding(root.getCode(),
                                level1Children.stream().map(TaxonomyNode::getCode).toList());
                        analyzeStreamingNodes(businessText, level1Children, allScores,
                                allDiscrepancies, callback, rootScore);
                    }
                }
            }

            callback.onComplete("SUCCESS", allScores, List.of(), allDiscrepancies);
        } catch (Exception e) {
            log.error("Streaming analysis failed", e);
            callback.onError("PARTIAL", "Analysis failed: " + e.getMessage(),
                    allScores, List.of(), allDiscrepancies);
        }
    }

    private void analyzeStreamingNodes(String businessText,
                                        List<TaxonomyNode> nodes,
                                        Map<String, Integer> allScores,
                                        List<TaxonomyDiscrepancy> allDiscrepancies,
                                        AnalysisEventCallback callback,
                                        int parentScore) {
        if (nodes == null || nodes.isEmpty()) return;

        ScoreParseResult result = callLlmResult(businessText, nodes, parentScore);
        allScores.putAll(result.scores());
        if (result.discrepancy() != null) {
            allDiscrepancies.add(result.discrepancy());
        }
        callback.onScores(result.scores(), result.reasons(), "Evaluated " + nodes.size() + " node(s)");

        for (Map.Entry<String, Integer> entry : result.scores().entrySet()) {
            if (entry.getValue() > 0) {
                List<TaxonomyNode> children = taxonomyService.getChildrenOf(entry.getKey());
                if (!children.isEmpty()) {
                    callback.onExpanding(entry.getKey(),
                            children.stream().map(TaxonomyNode::getCode).toList());
                    analyzeStreamingNodes(businessText, children, allScores,
                            allDiscrepancies, callback, entry.getValue());
                }
            }
        }
    }

    /**
     * Evaluates a single batch of nodes against the business text.
     * Makes exactly ONE LLM API call. Does NOT recurse into children.
     * Used by interactive mode. Delegates to {@link #analyzeSingleBatchDetailed}.
     */
    public Map<String, Integer> analyzeSingleBatch(String businessText, List<TaxonomyNode> nodes, int parentScore) {
        return callLlm(businessText, nodes, parentScore);
    }

    /**
     * Like {@link #analyzeSingleBatch} but also returns the prompt, raw LLM response,
     * provider name, and call duration. Used by the interactive-mode API endpoint so
     * the frontend can display the LLM communication log.
     */
    public com.taxonomy.dto.LlmCallDetail analyzeSingleBatchDetailed(
            String businessText, List<TaxonomyNode> nodes, int parentScore) {
        try {
            return callLlmPropagatingDetailed(businessText, nodes, parentScore);
        } catch (Exception e) {
            log.error("Error in detailed LLM call", e);
            com.taxonomy.dto.LlmCallDetail detail = new com.taxonomy.dto.LlmCallDetail();
            detail.setScores(responseParser.zeroScores(nodes));
            detail.setProvider(getActiveProviderName());
            detail.setPrompt("");
            detail.setRawResponse("");
            detail.setDurationMs(0);
            String errorMsg = "LLM call failed: " + e.getMessage();
            detail.setError(errorMsg);
            recordFailure(errorMsg);
            return detail;
        }
    }

    private Map<String, Integer> callLlm(String businessText, List<TaxonomyNode> nodes, int parentScore) {
        try {
            return callLlmPropagating(businessText, nodes, parentScore);
        } catch (Exception e) {
            log.error("Error calling LLM API", e);
            return responseParser.zeroScores(nodes);
        }
    }

    /**
     * Like {@link #callLlm} but returns both scores and reasons (backward-compatible).
     * Reasons will be empty when using LOCAL_ONNX or when the LLM returns the old integer-only format.
     */
    private ScoreParseResult callLlmResult(String businessText, List<TaxonomyNode> nodes, int parentScore) {
        try {
            if (llmMock) {
                log.info("MOCK — returning hardcoded scores for {} nodes", nodes.size());
                return buildMockScores(nodes, parentScore);
            }

            LlmProvider provider = getActiveProvider();

            if (provider == LlmProvider.LOCAL_ONNX) {
                log.info("LOCAL_ONNX — computing cosine-similarity scores for {} nodes", nodes.size());
                Map<String, Integer> scores = normalizeToParent(
                        localEmbeddingService.scoreNodes(businessText, nodes), parentScore);
                recordSuccess();
                return new ScoreParseResult(scores, Map.of());
            }

            String apiKey = getApiKey(provider);
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("⚠️ LLM analysis skipped: No API key configured for provider {}. "
                        + "Set environment variable {}_API_KEY to enable AI analysis.",
                        provider, provider.name());
                return ScoreParseResult.empty(nodes);
            }

            String nodeList = buildNodeListWithContext(nodes);
            String taxonomyCode = nodes.isEmpty() ? "default" : nodes.get(0).getTaxonomyRoot();
            String expectedKeys = String.join(", ", nodes.stream().map(TaxonomyNode::getCode).toList());
            String prompt = promptTemplateService.renderPrompt(taxonomyCode, businessText, nodeList, parentScore, expectedKeys);

            log.info("LLM Request [{}] — sending prompt for {} nodes: {}",
                    provider, nodes.size(), nodeList.substring(0, Math.min(nodeList.length(), 200)));
            log.debug("Full LLM prompt:\n{}", prompt);

            String rawText;
            if (provider == LlmProvider.GEMINI) {
                String body = callGeminiHttpBody(prompt, apiKey);
                rawText = body != null ? responseParser.extractGeminiText(body) : null;
            } else {
                String body = callOpenAiCompatibleHttpBody(prompt, apiKey, provider);
                rawText = body != null ? responseParser.extractOpenAiText(body) : null;
            }

            if (rawText == null) {
                return ScoreParseResult.empty(nodes);
            }
            try {
                ScoreParseResult result = responseParser.parseScoreParseResult(rawText, nodes, parentScore);
                recordSuccess();
                return result;
            } catch (Exception e) {
                log.error("Failed to parse LLM response in callLlmResult", e);
                recordFailure(e.getMessage());
                return ScoreParseResult.empty(nodes);
            }
        } catch (Exception e) {
            log.error("Error calling LLM API", e);
            return ScoreParseResult.empty(nodes);
        }
    }

    /**
     * Like {@link #callLlm} but propagates {@link LlmRateLimitException} instead of swallowing it.
     */
    private Map<String, Integer> callLlmPropagating(String businessText, List<TaxonomyNode> nodes, int parentScore) {
        if (llmMock) {
            log.info("MOCK — returning hardcoded scores for {} nodes", nodes.size());
            return buildMockScores(nodes, parentScore).scores();
        }

        LlmProvider provider = getActiveProvider();

        if (provider == LlmProvider.LOCAL_ONNX) {
            log.info("LOCAL_ONNX — computing cosine-similarity scores for {} nodes", nodes.size());
            Map<String, Integer> scores = normalizeToParent(
                    localEmbeddingService.scoreNodes(businessText, nodes), parentScore);
            recordSuccess();
            return scores;
        }

        String apiKey = getApiKey(provider);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️ LLM analysis skipped: No API key configured for provider {}. "
                    + "Set environment variable {}_API_KEY to enable AI analysis.",
                    provider, provider.name());
            return responseParser.zeroScores(nodes);
        }

        String nodeList = buildNodeListWithContext(nodes);
        String taxonomyCode = nodes.isEmpty() ? "default" : nodes.get(0).getTaxonomyRoot();
        String expectedKeys = String.join(", ", nodes.stream().map(TaxonomyNode::getCode).toList());
        String prompt = promptTemplateService.renderPrompt(taxonomyCode, businessText, nodeList, parentScore, expectedKeys);

        log.info("LLM Request [{}] — sending prompt for {} nodes: {}",
                provider, nodes.size(), nodeList.substring(0, Math.min(nodeList.length(), 200)));
        log.debug("Full LLM prompt:\n{}", prompt);

        if (provider == LlmProvider.GEMINI) {
            return callGemini(prompt, apiKey, nodes, parentScore);
        } else {
            return callOpenAiCompatible(prompt, apiKey, provider, nodes, parentScore);
        }
    }

    /**
     * Like {@link #callLlmPropagating} but also captures timing, the prompt, and the
     * raw LLM text response, returning them in a {@link com.taxonomy.dto.LlmCallDetail}.
     */
    private com.taxonomy.dto.LlmCallDetail callLlmPropagatingDetailed(
            String businessText, List<TaxonomyNode> nodes, int parentScore) {
        com.taxonomy.dto.LlmCallDetail detail = new com.taxonomy.dto.LlmCallDetail();
        detail.setProvider(getActiveProviderName());

        // ── Mock path ─────────────────────────────────────────────────────────
        if (llmMock) {
            log.info("MOCK — returning hardcoded scores for {} nodes", nodes.size());
            ScoreParseResult mock = buildMockScores(nodes, parentScore);
            detail.setScores(mock.scores());
            detail.setReasons(mock.reasons());
            detail.setPrompt("(mock mode – no prompt sent)");
            detail.setRawResponse("(hardcoded mock scores)");
            detail.setDurationMs(0);
            return detail;
        }

        LlmProvider provider = getActiveProvider();

        // ── Local embedding path ──────────────────────────────────────────────
        if (provider == LlmProvider.LOCAL_ONNX) {
            long start = System.currentTimeMillis();
            Map<String, Integer> scores = normalizeToParent(
                    localEmbeddingService.scoreNodes(businessText, nodes), parentScore);
            detail.setDurationMs(System.currentTimeMillis() - start);
            detail.setScores(scores);
            detail.setPrompt("(local embedding – no prompt sent)");
            detail.setRawResponse("(cosine similarity scores computed via all-MiniLM-L6-v2)");
            recordSuccess();
            return detail;
        }

        // ── API-based path ────────────────────────────────────────────────────
        String apiKey = getApiKey(provider);

        if (apiKey == null || apiKey.isBlank()) {
            String errorMsg = "No API key configured for provider " + provider
                    + ". Set environment variable " + provider.name() + "_API_KEY.";
            log.warn("⚠️ LLM analysis skipped: {}", errorMsg);
            detail.setScores(responseParser.zeroScores(nodes));
            detail.setPrompt("");
            detail.setRawResponse("");
            detail.setDurationMs(0);
            detail.setError(errorMsg);
            recordFailure(errorMsg);
            return detail;
        }

        String nodeList = buildNodeListWithContext(nodes);
        String taxonomyCode = nodes.isEmpty() ? "default" : nodes.get(0).getTaxonomyRoot();
        String expectedKeys = String.join(", ", nodes.stream().map(TaxonomyNode::getCode).toList());
        String prompt = promptTemplateService.renderPrompt(taxonomyCode, businessText, nodeList, parentScore, expectedKeys);
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
            String errorMsg = "LLM API call returned no response (possible network error or invalid key).";
            detail.setScores(responseParser.zeroScores(nodes));
            detail.setRawResponse("");
            detail.setError(errorMsg);
            recordFailure(errorMsg);
            return detail;
        }

        String rawText = (provider == LlmProvider.GEMINI)
                ? responseParser.extractGeminiText(apiResponseBody)
                : responseParser.extractOpenAiText(apiResponseBody);
        detail.setRawResponse(rawText != null ? rawText : "");

        if (rawText != null) {
            try {
                ScoreParseResult parsed = responseParser.parseScoreParseResult(rawText, nodes, parentScore);
                detail.setScores(parsed.scores());
                detail.setReasons(parsed.reasons());
                recordSuccess();
            } catch (Exception e) {
                log.error("Failed to parse scores in detailed LLM call", e);
                detail.setScores(responseParser.zeroScores(nodes));
                String errorMsg = "Failed to parse LLM response: " + e.getMessage();
                detail.setError(errorMsg);
                recordFailure(errorMsg);
            }
        } else {
            detail.setScores(responseParser.zeroScores(nodes));
            String errorMsg = "LLM response contained no usable text.";
            detail.setError(errorMsg);
            recordFailure(errorMsg);
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
                                             List<TaxonomyNode> nodes, int parentScore) {
        String responseBody = callGeminiHttpBody(prompt, apiKey);
        if (responseBody == null) return responseParser.zeroScores(nodes);
        return responseParser.parseGeminiResponse(responseBody, nodes, parentScore);
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
                                                       List<TaxonomyNode> nodes, int parentScore) {
        String responseBody = callOpenAiCompatibleHttpBody(prompt, apiKey, provider);
        if (responseBody == null) return responseParser.zeroScores(nodes);
        return responseParser.parseOpenAiResponse(responseBody, nodes, parentScore);
    }

    // ── Score normalization (public API) ─────────────────────────────────────

    /**
     * Normalizes a set of scores proportionally so that their sum equals {@code target}.
     * Preserves the ratio between scores. If all scores are zero, they are returned unchanged.
     * Rounding is adjusted using the largest-remainder method to ensure the exact sum equals {@code target}.
     *
     * @param scores raw scores to normalize
     * @param target the target sum (e.g. parent node's score, or 100 for root-level nodes)
     */
    public Map<String, Integer> normalizeToParent(Map<String, Integer> scores, int target) {
        return responseParser.normalizeToParent(scores, target);
    }

    /**
     * Normalizes a set of scores proportionally so that their sum equals 100.
     * Delegates to {@link #normalizeToParent(Map, int)} with target = 100.
     * Preserved for backward compatibility.
     */
    public Map<String, Integer> normalizeToHundred(Map<String, Integer> scores) {
        return responseParser.normalizeToHundred(scores);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getApiKey(LlmProvider provider) {
        return switch (provider) {
            case GEMINI      -> geminiApiKey;
            case OPENAI      -> openaiApiKey;
            case DEEPSEEK    -> deepseekApiKey;
            case QWEN        -> qwenApiKey;
            case LLAMA       -> llamaApiKey;
            case MISTRAL     -> mistralApiKey;
            case LOCAL_ONNX  -> null; // no API key required for local inference
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

    /**
     * Builds the node list for LLM prompts, optionally prepending the ancestor hierarchy as
     * context when the nodes share a common parent.
     *
     * <p>All nodes in the list are expected to be siblings (i.e. share the same parent) — this is
     * guaranteed by all callers, which always pass the result of {@link TaxonomyService#getChildrenOf}
     * or a root node combined with its direct children. The ancestor path is derived from the first
     * node's parent, which is representative for the whole batch. Nodes that have no parent (e.g. root
     * nodes) receive the same plain formatting as {@link #buildNodeList} without any ancestor header.
     */
    private String buildNodeListWithContext(List<TaxonomyNode> nodes) {
        if (nodes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        // Use the first node's parent as the shared context anchor
        String parentCode = nodes.get(0).getParentCode();
        if (parentCode != null && !parentCode.isBlank()) {
            List<TaxonomyNode> ancestors = taxonomyService.getPathToRoot(parentCode);
            if (!ancestors.isEmpty()) {
                sb.append("Parent hierarchy (for context — do NOT score these):\n");
                for (TaxonomyNode ancestor : ancestors) {
                    sb.append("  ").append(ancestor.getCode()).append(": ").append(ancestor.getName());
                    if (ancestor.getDescription() != null && !ancestor.getDescription().isBlank()) {
                        sb.append(" - ").append(ancestor.getDescription());
                    }
                    sb.append("\n");
                }
                sb.append("\nNodes to evaluate:\n");
            }
        }

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
        return "You are an expert in C3 taxonomy classification. " +
                "Given the following taxonomy categories and a business requirement, " +
                "estimate the percentage match (0-100) for each category.\n\n" +
                "Business Requirement: " + businessText + "\n\n" +
                "Categories:\n" + nodeList + "\n" +
                "Respond ONLY with a valid JSON object where keys are the category codes " +
                "and values are integer percentages (0-100). " +
                "Example: {\"C1\": 0, \"C2\": 15, \"C3\": 80}";
    }

    // ── Diagnostics helpers ───────────────────────────────────────────────────

    private synchronized void recordSuccess() {
        totalCalls.incrementAndGet();
        successfulCalls.incrementAndGet();
        lastCallTime = Instant.now();
        lastCallSuccess = true;
        lastError = null;
    }

    private synchronized void recordFailure(String error) {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        lastCallTime = Instant.now();
        lastCallSuccess = false;
        lastError = error;
    }

    /**
     * Returns a snapshot of diagnostics information for the {@code /api/diagnostics} endpoint.
     */
    public Map<String, Object> getDiagnostics() {
        LlmProvider provider = getActiveProvider();
        String apiKey = getApiKey(provider);
        boolean hasRealKey = provider != LlmProvider.LOCAL_ONNX
                && apiKey != null && !apiKey.isBlank();
        boolean apiKeyConfigured = llmMock || hasRealKey;
        String apiKeyPrefix = hasRealKey
                ? (apiKey.length() > 4 ? apiKey.substring(0, 4) + "****" : "****")
                : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider",        llmMock ? "Mock" : getActiveProviderName());
        result.put("apiKeyConfigured", apiKeyConfigured);
        result.put("apiKeyPrefix",     apiKeyPrefix);
        result.put("localModel",       provider == LlmProvider.LOCAL_ONNX
                ? LocalEmbeddingService.DEFAULT_MODEL_URL : null);
        result.put("lastCallTime",     lastCallTime != null ? lastCallTime.toString() : null);
        result.put("lastCallSuccess",  lastCallSuccess);
        result.put("lastError",        lastError);
        result.put("totalCalls",       totalCalls.get());
        result.put("successfulCalls",  successfulCalls.get());
        result.put("failedCalls",      failedCalls.get());
        result.put("serverTime",       Instant.now().toString());
        return result;
    }

    /**
     * Generates a leaf-node justification by sending a dedicated prompt to the LLM.
     * Describes why the given leaf node was chosen, referencing the full path and cross-references
     * to other high-scoring nodes.
     *
     * @param businessText  the original business requirement text
     * @param leafCode      the code of the leaf taxonomy node
     * @param pathNodes     the nodes from root to leaf (in order)
     * @param allScores     all accumulated scores from the analysis session
     * @param allReasons    all accumulated inline reasons from the analysis session
     * @return a multi-sentence justification string, or an error message
     */
    public String generateLeafJustification(String businessText,
                                             String leafCode,
                                             List<TaxonomyNode> pathNodes,
                                             Map<String, Integer> allScores,
                                             Map<String, String> allReasons) {
        if (llmMock) {
            recordSuccess();
            return "Node " + leafCode + " is relevant because it supports the requirement to "
                    + businessText.toLowerCase()
                    + ". The node's position within the taxonomy hierarchy indicates a direct "
                    + "contribution to the required capability.";
        }

        LlmProvider provider = getActiveProvider();

        if (provider == LlmProvider.LOCAL_ONNX) {
            return "Leaf justification is not available for the LOCAL_ONNX provider "
                    + "(cosine-similarity scores do not produce textual reasons).";
        }

        String apiKey = getApiKey(provider);
        if (apiKey == null || apiKey.isBlank()) {
            return "Leaf justification unavailable: no API key configured for provider " + provider + ".";
        }

        // Build the path description
        StringBuilder pathDesc = new StringBuilder();
        for (TaxonomyNode n : pathNodes) {
            pathDesc.append("  ").append(n.getCode()).append(": ").append(n.getName());
            int score = allScores.getOrDefault(n.getCode(), 0);
            pathDesc.append(" [").append(score).append("%]");
            String reason = allReasons.get(n.getCode());
            if (reason != null && !reason.isBlank()) {
                pathDesc.append(" — ").append(reason);
            }
            pathDesc.append("\n");
        }

        // Collect cross-references: other nodes with high scores (>= 50) not on the current path
        Set<String> pathCodes = new HashSet<>();
        for (TaxonomyNode n : pathNodes) pathCodes.add(n.getCode());

        StringBuilder crossRefs = new StringBuilder();
        allScores.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_CROSS_REFERENCE_SCORE && !pathCodes.contains(e.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_CROSS_REFERENCES)
                .forEach(e -> {
                    crossRefs.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("%");
                    String reason = allReasons.get(e.getKey());
                    if (reason != null && !reason.isBlank()) {
                        crossRefs.append(" — ").append(reason);
                    }
                    crossRefs.append("\n");
                });

        String prompt = promptTemplateService.renderLeafJustificationPrompt(
                businessText, leafCode, pathDesc.toString(),
                crossRefs.length() > 0 ? crossRefs.toString() : "  (none)");

        log.info("Generating leaf justification for node {} via {}", leafCode, provider);
        log.debug("Leaf justification prompt:\n{}", prompt);

        try {
            String rawText;
            if (provider == LlmProvider.GEMINI) {
                String body = callGeminiHttpBody(prompt, apiKey);
                rawText = body != null ? responseParser.extractGeminiText(body) : null;
            } else {
                String body = callOpenAiCompatibleHttpBody(prompt, apiKey, provider);
                rawText = body != null ? responseParser.extractOpenAiText(body) : null;
            }
            if (rawText == null || rawText.isBlank()) {
                return "The LLM did not return a justification. Please try again.";
            }
            recordSuccess();
            return rawText.trim();
        } catch (Exception e) {
            log.error("Failed to generate leaf justification for {}", leafCode, e);
            recordFailure(e.getMessage());
            return "Error generating justification: " + e.getMessage();
        }
    }
}
