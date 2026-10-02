package com.taxonomy.analysis.service;

import com.taxonomy.dto.RequirementSourceLinkDto;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.dto.SourceArtifactDto;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import com.taxonomy.catalog.service.TaxonomyService;

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

    private static final int CURRENT_VERSION = 2;
    private static final int MIN_SUPPORTED_VERSION = 1;
    private static final int MAX_SUPPORTED_VERSION = 3;

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
        saved.setVersion(CURRENT_VERSION);
        saved.setRequirement(requirement);
        saved.setTimestamp(Instant.now().toString());
        saved.setProvider(provider);
        saved.setScores(scores);
        saved.setReasons(reasons);
        return saved;
    }

    /**
     * Builds a {@link SavedAnalysis} with provenance data, ready for JSON serialization.
     *
     * @param requirement          the business requirement text
     * @param scores               node code → score (0 = scored zero, absent = not evaluated)
     * @param reasons              node code → reason text (may be null or sparse)
     * @param provider             LLM provider name (informational)
     * @param sources              source artifacts (optional, may be null)
     * @param requirementSourceLinks requirement-to-source links (optional, may be null)
     * @return populated {@link SavedAnalysis} with provenance
     */
    public SavedAnalysis buildExport(String requirement,
                                     Map<String, Integer> scores,
                                     Map<String, String> reasons,
                                     String provider,
                                     List<SourceArtifactDto> sources,
                                     List<RequirementSourceLinkDto> requirementSourceLinks) {
        SavedAnalysis saved = buildExport(requirement, scores, reasons, provider);
        saved.setSources(sources);
        saved.setRequirementSourceLinks(requirementSourceLinks);
        return saved;
    }

    /**
     * Deserializes and validates a {@link SavedAnalysis} from JSON.
     *
     * <p>Validation rules:
     * <ul>
     *   <li>{@code version} must be within the supported version range</li>
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
        int version = objectMapper.readTree(json).path("version").asInt(2);
        SavedAnalysis saved = version >= 3
                ? objectMapper.readerFor(SavedAnalysis.class)
                    .without(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(json)
                : objectMapper.readValue(json, SavedAnalysis.class);

        if (saved.getVersion() < MIN_SUPPORTED_VERSION || saved.getVersion() > MAX_SUPPORTED_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported version: " + saved.getVersion()
                            + " (supported: " + MIN_SUPPORTED_VERSION + "–" + MAX_SUPPORTED_VERSION + ")");
        }
        if (saved.getRequirement() == null || saved.getRequirement().isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        if (saved.getScores() == null || saved.getScores().isEmpty()) {
            throw new IllegalArgumentException("scores must not be null or empty");
        }

        validateCoverageEvidence(saved);

        // Warn about unknown node codes but do not reject
        List<String> unknownCodes = findUnknownCodes(saved);
        if (!unknownCodes.isEmpty()) {
            log.warn("SavedAnalysis import: {} unknown node code(s): {}", unknownCodes.size(), unknownCodes);
        }

        return saved;
    }

    /** Declared scope and version-3 evidence use real catalogue identities on both exchange paths. */
    public void validateCoverageEvidence(SavedAnalysis saved) {
        var scope = saved.getAnalysisScope();
        boolean selectedRoots = scope != null && !scope.taxonomyRoots().isEmpty();
        if (scope != null) {
            scope.validateRoots(taxonomyService.getRootCodes());
            if (!scope.taxonomyRoots().isEmpty()) {
                var codes = new LinkedHashSet<String>();
                if (saved.getScores() != null) codes.addAll(saved.getScores().keySet());
                if (saved.getRawScores() != null) codes.addAll(saved.getRawScores().keySet());
                if (saved.getAnalysisCoverage() != null) codes.addAll(saved.getAnalysisCoverage().nodes().keySet());
                for (String code : codes) {
                    taxonomyService.validateNodeRootMembership(code, scope.taxonomyRoots());
                }
            }
        }
        saved.validateCoverageEvidence();
        if (saved.getVersion() >= 3 && !selectedRoots) {
            for (String code : saved.getAnalysisCoverage().nodes().keySet()) {
                if (taxonomyService.getNodeByCode(code) == null) {
                    throw new IllegalArgumentException("Unknown catalogue node in assessment coverage: " + code);
                }
            }
        }
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
     * Returns the list of unknown node codes in a validated imported {@link SavedAnalysis}.
     * Used by the import endpoint to return warnings to the caller.
     */
    public List<String> findUnknownCodes(SavedAnalysis saved) {
        // Selected evidence has already proved every identity; unknown codes are fatal there.
        if (saved.getAnalysisScope() != null && !saved.getAnalysisScope().taxonomyRoots().isEmpty()) return List.of();
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
