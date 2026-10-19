package com.taxonomy.preferences;

import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.preferences.storage.PreferencesCommit;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/**
 * Service that manages runtime application preferences backed by a dedicated JGit repository.
 *
 * <p>On startup, preferences are loaded from the HEAD of the {@code main} branch in the
 * preferences Git repository. If no commit exists yet, all values are initialised from
 * {@code application.properties} defaults and committed as the first entry.
 *
 * <p>Every {@link #update(Map)} call merges the provided changes into the current preferences,
 * serialises them to JSON, and creates a new Git commit — giving a full audit trail of all
 * preference changes over time.
 *
 * <p>The preferences Git repository uses project name {@code "taxonomy-preferences"}, which
 * is completely separate from the Architecture DSL repository ({@code "taxonomy-dsl"}).
 */
@Service
public class PreferencesService {

    private static final Logger log = LoggerFactory.getLogger(PreferencesService.class);

    private static final String TOKEN_MASK_PREFIX = "****";
    private static final String ANALYSIS_REASON_LENGTH = "analysis.reason-length";
    private static final String RELATION_MAX_CALLS = "analysis.relations.max-calls";
    private static final String RELATION_MAX_TOTAL_CALLS = "analysis.relations.max-total-calls";
    private static final String RELATION_AUTO_CONTINUE = "analysis.relations.auto-continue";
    private static final String RELATION_MAX_SOURCES = "analysis.relations.max-sources";
    private static final String RELATION_MAX_WORK_ITEMS = "analysis.relations.max-work-items";

    private final PreferencesGitRepository gitRepository;
    private final ObjectMapper objectMapper;

    // Atomically published immutable snapshot of the current preferences.
    //
    // Persist first and publish second: a failed Git commit must never change the
    // runtime settings observed by analysis or export code.
    private volatile Map<String, Object> cache = Map.of();

    // ── Default values loaded from application.properties ─────────────────────

    @Value("${taxonomy.llm.rpm:5}")
    private int defaultLlmRpm;

    @Value("${taxonomy.llm.timeout-seconds:30}")
    private int defaultLlmTimeoutSeconds;

    @Value("${taxonomy.rate-limit.per-minute:10}")
    private int defaultRateLimitPerMinute;

    @Value("${taxonomy.analysis.min-score:70}")
    private int defaultAnalysisMinScore;

    @Value("${taxonomy.analysis.reason-length:standard}")
    private String defaultAnalysisReasonLength = "standard";

    @Value("${taxonomy.analysis.relations.hierarchical.max-calls:256}")
    private int defaultRelationMaxCalls = 256;

    @Value("${taxonomy.analysis.relations.hierarchical.max-total-calls:4096}")
    private int defaultRelationMaxTotalCalls = 4096;

    @Value("${taxonomy.analysis.relations.hierarchical.auto-continue:true}")
    private boolean defaultRelationAutoContinue = true;

    @Value("${taxonomy.analysis.relations.hierarchical.max-sources:128}")
    private int defaultRelationMaxSources = 128;

    @Value("${taxonomy.analysis.relations.hierarchical.max-work-items:4096}")
    private int defaultRelationMaxWorkItems = 4096;

    @Value("${taxonomy.dsl.default-branch:draft}")
    private String defaultDslDefaultBranch;

    @Value("${taxonomy.dsl.project-name:Taxonomy Architecture}")
    private String defaultDslProjectName;

    @Value("${taxonomy.dsl.auto-save-interval:0}")
    private int defaultDslAutoSaveInterval;

    @Value("${taxonomy.dsl.remote-url:}")
    private String defaultDslRemoteUrl;

    @Value("${taxonomy.dsl.remote-token:}")
    private String defaultDslRemoteToken;

    @Value("${taxonomy.dsl.remote-push-on-commit:false}")
    private boolean defaultDslRemotePushOnCommit;

    @Value("${taxonomy.limits.max-business-text:5000}")
    private int defaultMaxBusinessText;

    @Value("${taxonomy.limits.max-architecture-nodes:50}")
    private int defaultMaxArchitectureNodes;

    @Value("${taxonomy.limits.max-export-nodes:200}")
    private int defaultMaxExportNodes;

    @Value("${taxonomy.diagram.policy:defaultImpact}")
    private String defaultDiagramPolicy;

    public PreferencesService(PreferencesGitRepository gitRepository, ObjectMapper objectMapper) {
        this.gitRepository = gitRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * On startup: load preferences from the JGit repository, or initialise from defaults.
     */
    @PostConstruct
    public void init() {
        try {
            String json = gitRepository.readHead();
            if (json != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> loaded = objectMapper.readValue(json, Map.class);
                // Older commits omit newer analysis preferences. Expose deployment
                // defaults immediately without creating a read-time commit.
                loaded.putIfAbsent(ANALYSIS_REASON_LENGTH, configuredReasonLength());
                configuredRelationBudgets().forEach(loaded::putIfAbsent);
                cache = immutableSnapshot(loaded);
                log.info("Preferences loaded from JGit repository ({} entries)", cache.size());
            } else {
                // No commits yet — initialise from property defaults and commit
                Map<String, Object> defaults = buildDefaults();
                String initialJson = objectMapper.writeValueAsString(defaults);
                gitRepository.commit(initialJson, "system", "Initial preferences from application.properties");
                cache = immutableSnapshot(defaults);
                log.info("Preferences initialised from defaults and committed to JGit");
            }
        } catch (IOException e) {
            log.warn("Could not load preferences from JGit, using defaults: {}", e.getMessage());
            cache = immutableSnapshot(buildDefaults());
        }
    }

    /**
     * Returns all current preferences. The {@code dsl.remote.token} value is masked.
     *
     * @return an unmodifiable copy of the preferences map with the token masked
     */
    public Map<String, Object> getAll() {
        Map<String, Object> result = new LinkedHashMap<>(cache);
        maskToken(result);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the raw (unmasked) value for the given key.
     *
     * @param key the preference key
     * @return the value, or {@code null} if not set
     */
    public Object get(String key) {
        return cache.get(key);
    }

    /**
     * Returns the integer value for the given key, or the default if not present or not a number.
     */
    public int getInt(String key, int defaultValue) {
        Object val = cache.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    /**
     * Returns the string value for the given key, or the default if not present.
     */
    public String getString(String key, String defaultValue) {
        Object val = cache.get(key);
        return val != null ? String.valueOf(val) : defaultValue;
    }

    /**
     * Returns the boolean value for the given key, or the default if not present.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = cache.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /**
     * Merge the provided changes into the current preferences, commit to JGit, and update
     * the in-memory cache. Each call creates a new Git commit with the full preferences JSON.
     *
     * @param changes   partial map of settings to update
     * @param author    the user making the change (for the Git commit author)
     * @throws IOException if the JGit commit fails
     */
    public synchronized void update(Map<String, Object> changes, String author) throws IOException {
        Map<String, Object> validated = new LinkedHashMap<>(changes);
        validateReasonLength(validated);
        validateRelationBudgets(validated);
        Map<String, Object> updated = new LinkedHashMap<>(cache);
        updated.putAll(validated);
        String json = objectMapper.writeValueAsString(updated);
        String commitMsg = "Preferences updated: " + String.join(", ", validated.keySet());

        // The repository is the durable authority. Do not publish a new runtime
        // snapshot until its commit has succeeded.
        gitRepository.commit(json, author, commitMsg);
        cache = immutableSnapshot(updated);
        log.info("Preferences updated by '{}': {}", author, validated.keySet());
    }

    /**
     * Resets all preferences to their default values (from application.properties),
     * commits to JGit, and updates the in-memory cache.
     *
     * @param author the user requesting the reset
     * @throws IOException if the JGit commit fails
     */
    public synchronized void resetToDefaults(String author) throws IOException {
        Map<String, Object> defaults = buildDefaults();
        String json = objectMapper.writeValueAsString(defaults);

        // As with update(), a failed reset must leave the active runtime
        // preferences untouched.
        gitRepository.commit(json, author, "Preferences reset to defaults");
        cache = immutableSnapshot(defaults);
        log.info("Preferences reset to defaults by '{}'", author);
    }

    /**
     * Returns the commit history of the preferences repository, newest first.
     */
    public List<PreferencesCommit> getHistory() throws IOException {
        return gitRepository.getHistory();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private static void validateReasonLength(Map<String, Object> changes) {
        if (!changes.containsKey(ANALYSIS_REASON_LENGTH)) return;
        Object value = changes.get(ANALYSIS_REASON_LENGTH);
        if (value instanceof String text && !text.isBlank()) {
            try {
                AnalysisReasonLength.fromValue(text);
                return;
            } catch (IllegalArgumentException ignored) {
                // Return a stable, non-reflective validation message at the API boundary.
            }
        }
        throw new IllegalArgumentException("analysis.reason-length must be short, standard or detailed");
    }

    private String configuredReasonLength() {
        return AnalysisReasonLength.fromValue(defaultAnalysisReasonLength).value();
    }

    private static void validateRelationBudgets(Map<String, Object> changes) {
        validateIntegerBudget(changes, RELATION_MAX_CALLS, 0, 10_000);
        validateIntegerBudget(changes, RELATION_MAX_TOTAL_CALLS, 0, 10_000);
        validateIntegerBudget(changes, RELATION_MAX_SOURCES, 1, 256);
        validateIntegerBudget(changes, RELATION_MAX_WORK_ITEMS, 1, 100_000);
        if (changes.containsKey(RELATION_AUTO_CONTINUE)
                && !(changes.get(RELATION_AUTO_CONTINUE) instanceof Boolean)) {
            throw new IllegalArgumentException(RELATION_AUTO_CONTINUE + " must be true or false");
        }
    }

    private static void validateIntegerBudget(Map<String, Object> changes, String key, int min, int max) {
        if (!changes.containsKey(key)) return;
        Object value = changes.get(key);
        if (value instanceof Number number) {
            try {
                // intValue() silently truncates fractions and wraps overflowing
                // longs. Persist one exact integer representation after validation.
                int integer = new BigDecimal(number.toString()).intValueExact();
                if (integer >= min && integer <= max) {
                    changes.put(key, integer);
                    return;
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Return the same bounded message for every invalid representation.
            }
        }
        throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
    }

    private Map<String, Object> configuredRelationBudgets() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(RELATION_MAX_CALLS, defaultRelationMaxCalls);
        defaults.put(RELATION_MAX_TOTAL_CALLS, defaultRelationMaxTotalCalls);
        defaults.put(RELATION_AUTO_CONTINUE, defaultRelationAutoContinue);
        defaults.put(RELATION_MAX_SOURCES, defaultRelationMaxSources);
        defaults.put(RELATION_MAX_WORK_ITEMS, defaultRelationMaxWorkItems);
        validateRelationBudgets(defaults);
        return defaults;
    }

    private static Map<String, Object> immutableSnapshot(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private Map<String, Object> buildDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        // LLM Configuration
        defaults.put("llm.rpm", defaultLlmRpm);
        defaults.put("llm.timeout.seconds", defaultLlmTimeoutSeconds);
        defaults.put("rate-limit.per-minute", defaultRateLimitPerMinute);
        defaults.put("analysis.min-relevance-score", defaultAnalysisMinScore);
        defaults.put(ANALYSIS_REASON_LENGTH, configuredReasonLength());
        defaults.putAll(configuredRelationBudgets());
        // JGit / DSL Configuration
        defaults.put("dsl.default-branch", defaultDslDefaultBranch);
        defaults.put("dsl.project-name", defaultDslProjectName);
        defaults.put("dsl.auto-save.interval-seconds", defaultDslAutoSaveInterval);
        defaults.put("dsl.remote.url", defaultDslRemoteUrl);
        defaults.put("dsl.remote.token", defaultDslRemoteToken);
        defaults.put("dsl.remote.push-on-commit", defaultDslRemotePushOnCommit);
        // Size Limits
        defaults.put("limits.max-business-text", defaultMaxBusinessText);
        defaults.put("limits.max-architecture-nodes", defaultMaxArchitectureNodes);
        defaults.put("limits.max-export-nodes", defaultMaxExportNodes);
        // Diagram Configuration
        defaults.put("diagram.policy", defaultDiagramPolicy);
        return defaults;
    }

    private void maskToken(Map<String, Object> prefs) {
        Object token = prefs.get("dsl.remote.token");
        if (token instanceof String s && !s.isEmpty()) {
            int showChars = Math.min(4, s.length());
            prefs.put("dsl.remote.token",
                    TOKEN_MASK_PREFIX + s.substring(s.length() - showChars));
        }
    }
}
