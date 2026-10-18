package com.taxonomy.portfolio.service;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import org.springframework.stereotype.Service;

import java.io.FilterReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central JSON codec for immutable snapshot payloads and extension attributes. */
@Service
public class PortfolioJsonCodec {

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictEvidenceMapper;
    private final ObjectMapper selectionMapper;

    public PortfolioJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.strictEvidenceMapper = objectMapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        this.selectionMapper = objectMapper.rebuild()
                .addMixIn(AnalysisResult.class, SelectionAnalysisFields.class)
                .addMixIn(TaxonomyNodeDto.class, SelectionNodeFields.class).build();
    }

    public String write(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not serialize portfolio payload", exception);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed(
                    "Could not deserialize portfolio payload as " + type.getSimpleName(), exception);
        }
    }

    /**
     * Skip report graphs and text during streaming deserialization. Reuse the normal score setters
     * and semantics, including legacy envelopes and product/family context, instead of trusting
     * the serialized scores alias or rebuilding the entire JSON tree.
     */
    AnalysisResult readSelectionAnalysis(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return selectionMapper.readValue(json, AnalysisResult.class);
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not deserialize portfolio selection evidence", exception);
        }
    }

    /** Read the whole selection envelope without materializing its CLOB; the caller owns the reader. */
    AnalysisResult readSelectionAnalysis(Reader json) {
        if (json == null) return null;
        // Jackson may close its source. Keep the JDBC reader owned by the transaction boundary,
        // where a read failure must retain any suppressed reader-close / Clob.free failures.
        try (var input = new PushbackReader(new FilterReader(json) {
            @Override public void close() { }
        }, 1)) {
            boolean nonJsonWhitespace = false;
            int next;
            while ((next = input.read()) != -1 && Character.isWhitespace(next)) {
                nonJsonWhitespace |= next != ' ' && next != '\t' && next != '\r' && next != '\n';
            }
            // String.isBlank accepts Unicode whitespace; JSON itself only accepts four characters.
            if (next == -1) return null;
            if (nonJsonWhitespace) throw new IOException("Non-JSON whitespace before selection payload");
            input.unread(next);
            return selectionMapper.readValue(input, AnalysisResult.class);
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not deserialize portfolio selection evidence", exception);
        }
    }

    @JsonIncludeProperties({"scores", "rawScores", "tree", "warnings", "scoreSemanticsContext",
            "scoreSemanticsVersion", "scoreSemanticsFingerprintSha256", "scoreDetails", "effectiveScores",
            "productSuitabilityScores", "scoreSemanticsWarnings"})
    private abstract static class SelectionAnalysisFields { }

    @JsonIncludeProperties({"code", "parentCode", "analysisRole", "children"})
    private abstract static class SelectionNodeFields { }

    /** Read recorded evidence without a DTO constructor inventing a default for an absent/null field. */
    public <T> T readField(String json, String field, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            var root = objectMapper.readTree(json);
            var value = root.get(field);
            return value == null || value.isNull() ? null : objectMapper.treeToValue(value, type);
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not deserialize recorded field " + field, exception);
        }
    }

    /** Evidence trust boundary: neither duplicate keys nor trailing values are recoverable. */
    public <T> T readStrictEvidence(String value, Class<T> type) {
        if (value == null || value.isBlank()) return null;
        try {
            return strictEvidenceMapper.readValue(value, type);
        } catch (Exception exception) {
            throw PortfolioException.validation("Reformulation evidence integrity payload is invalid");
        }
    }

    public List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<Long>>() {}));
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not deserialize source fragment IDs", exception);
        }
    }

    public Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> values = objectMapper.readValue(
                    json, new TypeReference<Map<String, String>>() {});
            return Map.copyOf(new LinkedHashMap<>(values));
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not deserialize extension attributes", exception);
        }
    }
}
