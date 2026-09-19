package com.taxonomy.catalog.service;

import com.taxonomy.dto.RelationSeedRow;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.SeedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the relation seed CSV file into {@link RelationSeedRow} records.
 *
 * <p>The parser supports both the legacy 4-column format
 * ({@code SourceCode,TargetCode,RelationType,Description}) and the extended
 * 10-column format that adds {@code SourceStandard}, {@code SourceReference},
 * {@code Confidence}, {@code SeedType}, {@code ReviewRequired}, and
 * {@code Status}.
 *
 * <p>Rows that cannot be parsed are logged as warnings and skipped.
 * The parser does not throw exceptions for individual malformed rows.
 */
public final class RelationSeedParser {

    private static final Logger log = LoggerFactory.getLogger(RelationSeedParser.class);

    /** Maximum number of columns in the extended CSV format. */
    private static final int EXTENDED_COLUMN_COUNT = 10;

    /** Minimum number of columns required for a valid row. */
    private static final int MIN_COLUMN_COUNT = 3;

    private RelationSeedParser() {
        // utility class
    }

    /**
     * Parse the relation seed CSV from the given input stream.
     *
     * @param inputStream the CSV input stream (UTF-8 encoded, with header row)
     * @return an unmodifiable list of parsed seed rows; never {@code null}
     * @throws IOException if the stream cannot be read
     */
    public static List<RelationSeedRow> parse(InputStream inputStream) throws IOException {
        List<RelationSeedRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine(); // skip header row
            if (header == null) {
                log.warn("Relation seed CSV is empty.");
                return Collections.emptyList();
            }

            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;

                RelationSeedRow row = parseLine(line, lineNumber);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * Parse a single CSV line into a {@link RelationSeedRow}.
     *
     * @param line       the raw CSV line
     * @param lineNumber the 1-based line number (for error messages)
     * @return the parsed row, or {@code null} if the line is malformed
     */
    static RelationSeedRow parseLine(String line, int lineNumber) {
        String[] parts = line.split(",", EXTENDED_COLUMN_COUNT);
        if (parts.length < MIN_COLUMN_COUNT) {
            log.warn("Relation seed CSV line {}: too few columns ({}) — skipping.", lineNumber, parts.length);
            return null;
        }

        String sourceCode = parts[0].trim();
        String targetCode = parts[1].trim();
        String typeStr = parts[2].trim();

        if (sourceCode.isEmpty() || targetCode.isEmpty() || typeStr.isEmpty()) {
            log.warn("Relation seed CSV line {}: empty required field — skipping.", lineNumber);
            return null;
        }

        // Parse relation type
        RelationType relationType;
        try {
            relationType = RelationType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Relation seed CSV line {}: unknown relation type '{}' — skipping.", lineNumber, typeStr);
            return null;
        }

        // Column 4: Description (optional)
        String description = safeGet(parts, 3);

        // Column 5: SourceStandard (optional)
        String sourceStandard = safeGet(parts, 4);

        // Column 6: SourceReference (optional)
        String sourceReference = safeGet(parts, 5);

        // Column 7: Confidence (optional, default 1.0)
        double confidence = parseConfidence(safeGet(parts, 6), lineNumber);

        // Column 8: SeedType (optional, default TYPE_DEFAULT)
        SeedType seedType = parseSeedType(safeGet(parts, 7), lineNumber);

        // Column 9: ReviewRequired (optional, default false)
        boolean reviewRequired = parseBoolean(safeGet(parts, 8));

        // Column 10: Status (optional, default "accepted")
        String status = safeGet(parts, 9);
        if (status == null || status.isEmpty()) {
            status = "accepted";
        }

        return new RelationSeedRow(
                sourceCode, targetCode, relationType, description,
                sourceStandard, sourceReference, confidence,
                seedType, reviewRequired, status);
    }

    private static String safeGet(String[] parts, int index) {
        if (index >= parts.length) return null;
        String value = parts[index].trim();
        return value.isEmpty() ? null : value;
    }

    private static double parseConfidence(String value, int lineNumber) {
        if (value == null) return 1.0;
        try {
            double confidence = Double.parseDouble(value);
            if (confidence < 0.0 || confidence > 1.0) {
                log.warn("Relation seed CSV line {}: confidence {} out of [0.0, 1.0] range — clamping.",
                        lineNumber, confidence);
                return Math.max(0.0, Math.min(1.0, confidence));
            }
            return confidence;
        } catch (NumberFormatException e) {
            log.warn("Relation seed CSV line {}: invalid confidence '{}' — using default 1.0.",
                    lineNumber, value);
            return 1.0;
        }
    }

    private static SeedType parseSeedType(String value, int lineNumber) {
        if (value == null) return SeedType.TYPE_DEFAULT;
        try {
            return SeedType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Relation seed CSV line {}: unknown seed type '{}' — using TYPE_DEFAULT.",
                    lineNumber, value);
            return SeedType.TYPE_DEFAULT;
        }
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value);
    }
}
