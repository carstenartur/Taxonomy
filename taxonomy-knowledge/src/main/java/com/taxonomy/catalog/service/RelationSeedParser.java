package com.taxonomy.catalog.service;

import com.taxonomy.dto.RelationSeedRow;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.SeedType;
import org.apache.commons.csv.CSVFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
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
 * <p>Invalid required field values are logged and their records skipped.
 * A broken CSV stream (for example, an unterminated quoted field) raises an IOException.
 */
public final class RelationSeedParser {

    private static final Logger log = LoggerFactory.getLogger(RelationSeedParser.class);

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
        try (var reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             var records = CSVFormat.DEFAULT.parse(reader)) {
            var iterator = records.iterator();
            if (!iterator.hasNext()) {
                log.warn("Relation seed CSV is empty.");
                return Collections.emptyList();
            }
            iterator.next(); // Header; older seed files may have only three or four columns.
            while (iterator.hasNext()) {
                var fields = iterator.next().values();
                if (fields.length == 1 && fields[0].isBlank()) continue;
                RelationSeedRow row = parseRow(fields, records.getCurrentLineNumber());
                if (row != null) rows.add(row);
            }
        } catch (UncheckedIOException malformed) {
            throw malformed.getCause();
        }
        return Collections.unmodifiableList(rows);
    }

    /** Validate one decoded record, reporting the physical source line on bad field values. */
    private static RelationSeedRow parseRow(String[] parts, long lineNumber) {
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
            relationType = RelationType.valueOf(typeStr.toUpperCase(java.util.Locale.ROOT));
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

    private static double parseConfidence(String value, long lineNumber) {
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

    private static SeedType parseSeedType(String value, long lineNumber) {
        if (value == null) return SeedType.TYPE_DEFAULT;
        try {
            return SeedType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
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
