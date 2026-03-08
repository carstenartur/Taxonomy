package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.SavedAnalysis;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for building, exporting, and importing {@link SavedAnalysis} objects.
 *
 * <p>The {@code SavedAnalysis} format preserves the semantic distinction between:
 * <ul>
 *   <li>A node code present with value {@code 0} → evaluated and scored 0% (not relevant)</li>
 *   <li>A node code absent → not yet evaluated</li>
 * </ul>
 */
@Service
public class SavedAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SavedAnalysisService.class);

    private static final int SUPPORTED_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final TaxonomyService taxonomyService;

    public SavedAnalysisService(ObjectMapper objectMapper, TaxonomyService taxonomyService) {
        this.objectMapper = objectMapper;
        this.taxonomyService = taxonomyService;
    }

    /**
     * Builds a {@link SavedAnalysis} ready for JSON serialization and download.
     *
     * @param requirement the business requirement text
     * @param scores      node code → score (0 = scored zero, absent = not evaluated)
     * @param reasons     node code → reason text (may be null or sparse)
     * @param provider    LLM provider name (informational)
     * @return populated {@link SavedAnalysis}
     */
    public SavedAnalysis buildExport(String requirement,
                                     Map<String, Integer> scores,
                                     Map<String, String> reasons,
                                     String provider) {
        SavedAnalysis saved = new SavedAnalysis();
        saved.setVersion(SUPPORTED_VERSION);
        saved.setRequirement(requirement);
        saved.setTimestamp(Instant.now().toString());
        saved.setProvider(provider);
        saved.setScores(scores);
        saved.setReasons(reasons);
        return saved;
    }

    /**
     * Deserializes and validates a {@link SavedAnalysis} from JSON.
     *
     * <p>Validation rules:
     * <ul>
     *   <li>{@code version} must be {@value #SUPPORTED_VERSION}</li>
     *   <li>{@code requirement} must not be blank</li>
     *   <li>{@code scores} must not be null or empty</li>
     *   <li>Unknown node codes in {@code scores} generate warnings but do not fail</li>
     * </ul>
     *
     * @param json raw JSON string
     * @return validated {@link SavedAnalysis}
     * @throws IllegalArgumentException if validation fails
     * @throws IOException              if the JSON cannot be parsed
     */
    public SavedAnalysis importFromJson(String json) throws IOException {
        SavedAnalysis saved = objectMapper.readValue(json, SavedAnalysis.class);

        if (saved.getVersion() != SUPPORTED_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported version: " + saved.getVersion() + " (expected " + SUPPORTED_VERSION + ")");
        }
        if (saved.getRequirement() == null || saved.getRequirement().isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        if (saved.getScores() == null || saved.getScores().isEmpty()) {
            throw new IllegalArgumentException("scores must not be null or empty");
        }

        // Warn about unknown node codes but do not reject
        List<String> unknownCodes = new ArrayList<>();
        for (String code : saved.getScores().keySet()) {
            if (taxonomyService.getNodeByCode(code) == null) {
                unknownCodes.add(code);
            }
        }
        if (!unknownCodes.isEmpty()) {
            log.warn("SavedAnalysis import: {} unknown node code(s): {}", unknownCodes.size(), unknownCodes);
        }

        return saved;
    }

    /**
     * Loads and validates a {@link SavedAnalysis} from a classpath resource.
     *
     * @param resourcePath classpath-relative path (e.g. {@code "mock-scores/secure-voice-comms.json"})
     * @return validated {@link SavedAnalysis}
     * @throws IOException if the resource cannot be read or parsed
     */
    public SavedAnalysis loadFromClasspath(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return importFromJson(json);
        }
    }

    /**
     * Returns the list of unknown node codes found in the given {@link SavedAnalysis}.
     * Used by the import endpoint to return warnings to the caller.
     */
    public List<String> findUnknownCodes(SavedAnalysis saved) {
        if (saved.getScores() == null) { return List.of(); }
        List<String> unknown = new ArrayList<>();
        for (String code : saved.getScores().keySet()) {
            if (taxonomyService.getNodeByCode(code) == null) {
                unknown.add(code);
            }
        }
        return unknown;
    }
}
