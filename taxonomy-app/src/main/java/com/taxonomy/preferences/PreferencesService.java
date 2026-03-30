package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesCommit;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final PreferencesGitRepository gitRepository;
    private final ObjectMapper objectMapper;

    // In-memory cache of the current preferences
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    // ── Default values loaded from application.properties ─────────────────────

    @Value("${taxonomy.llm.rpm:5}")
    private int defaultLlmRpm;

    @Value("${taxonomy.llm.timeout-seconds:30}")
    private int defaultLlmTimeoutSeconds;

    @Value("${taxonomy.rate-limit.per-minute:10}")
    private int defaultRateLimitPerMinute;

    @Value("${taxonomy.analysis.min-score:70}")
    private int defaultAnalysisMinScore;

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
                cache.putAll(loaded);
                log.info("Preferences loaded from JGit repository ({} entries)", cache.size());
            } else {
                // No commits yet — initialise from property defaults and commit
                cache.putAll(buildDefaults());
                String initialJson = objectMapper.writeValueAsString(cache);
                gitRepository.commit(initialJson, "system", "Initial preferences from application.properties");
                log.info("Preferences initialised from defaults and committed to JGit");
            }
        } catch (IOException e) {
            log.warn("Could not load preferences from JGit, using defaults: {}", e.getMessage());
            cache.putAll(buildDefaults());
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
    public void update(Map<String, Object> changes, String author) throws IOException {
        cache.putAll(changes);
        String json = objectMapper.writeValueAsString(cache);
        String commitMsg = "Preferences updated: " + String.join(", ", changes.keySet());
        gitRepository.commit(json, author, commitMsg);
        log.info("Preferences updated by '{}': {}", author, changes.keySet());
    }

    /**
     * Resets all preferences to their default values (from application.properties),
     * commits to JGit, and updates the in-memory cache.
     *
     * @param author the user requesting the reset
     * @throws IOException if the JGit commit fails
     */
    public void resetToDefaults(String author) throws IOException {
        Map<String, Object> defaults = buildDefaults();
        cache.clear();
        cache.putAll(defaults);
        String json = objectMapper.writeValueAsString(cache);
        gitRepository.commit(json, author, "Preferences reset to defaults");
        log.info("Preferences reset to defaults by '{}'", author);
    }

    /**
     * Returns the commit history of the preferences repository, newest first.
     */
    public List<PreferencesCommit> getHistory() throws IOException {
        return gitRepository.getHistory();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private Map<String, Object> buildDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        // LLM Configuration
        defaults.put("llm.rpm", defaultLlmRpm);
        defaults.put("llm.timeout.seconds", defaultLlmTimeoutSeconds);
        defaults.put("rate-limit.per-minute", defaultRateLimitPerMinute);
        defaults.put("analysis.min-relevance-score", defaultAnalysisMinScore);
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
