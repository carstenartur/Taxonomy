package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses APQC Process Classification Framework (PCF) CSV files into
 * {@link ExternalElement}s and {@link ExternalRelation}s.
 *
 * <p>Expected CSV structure:
 * <pre>
 * PCF ID,Name,Level,Description
 * 1.0,Develop Vision and Strategy,1,
 * 1.1,Define the business concept,2,
 * 1.1.1,Assess the external environment,3,...
 * </pre>
 *
 * <p>Parent–child relations are derived from the PCF ID hierarchy
 * (e.g., 1.1 is a child of 1.0).
 */
public class ApqcCsvParser implements ExternalParser {

    private static final String[] LEVEL_TYPES = {
        "Category", "ProcessGroup", "Process", "Activity", "Task"
    };

    @Override
    public String fileFormat() {
        return "csv";
    }

    @Override
    public ParsedExternalModel parse(InputStream input) throws Exception {
        List<ExternalElement> elements = new ArrayList<>();
        List<ExternalRelation> relations = new ArrayList<>();
        Map<String, String> idByPcfId = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new ParsedExternalModel(elements, relations);
            }

            // Parse header to find column indices
            String[] headers = splitCsvLine(headerLine);
            int pcfIdCol = findColumn(headers, "PCF ID", "Id", "ID", "pcf_id");
            int nameCol = findColumn(headers, "Name", "name", "Process Name", "Title");
            int levelCol = findColumn(headers, "Level", "level", "Hierarchy Level");
            int descCol = findColumn(headers, "Description", "description", "Desc");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                String[] fields = splitCsvLine(line);
                String pcfId = safeGet(fields, pcfIdCol);
                String name = safeGet(fields, nameCol);
                String levelStr = safeGet(fields, levelCol);
                String description = safeGet(fields, descCol, false);

                if (pcfId == null || pcfId.isBlank()) continue;

                // Determine level from explicit column or PCF ID dot count
                int level = parseLevel(levelStr, pcfId);
                String type = level >= 1 && level <= LEVEL_TYPES.length
                        ? LEVEL_TYPES[level - 1] : "Category";

                String elementId = "apqc-" + pcfId.replace(".", "-");
                Map<String, String> props = new LinkedHashMap<>();
                props.put("pcfId", pcfId);
                String parentPcfId = deriveParentPcfId(pcfId);
                if (parentPcfId != null) {
                    props.put("parentId", "apqc-" + parentPcfId.replace(".", "-"));
                }

                elements.add(new ExternalElement(elementId, type, name, description, props));
                idByPcfId.put(pcfId, elementId);

                // Create parent-child relation
                if (parentPcfId != null && idByPcfId.containsKey(parentPcfId)) {
                    relations.add(new ExternalRelation(
                            idByPcfId.get(parentPcfId), elementId, "ParentChild", Map.of()));
                }
            }
        }

        return new ParsedExternalModel(elements, relations);
    }

    private int findColumn(String[] headers, String... candidates) {
        for (String candidate : candidates) {
            for (int i = 0; i < headers.length; i++) {
                if (headers[i].trim().equalsIgnoreCase(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String safeGet(String[] fields, int index) {
        return safeGet(fields, index, true);
    }

    private String safeGet(String[] fields, int index, boolean trim) {
        if (index < 0 || index >= fields.length) return null;
        String value = trim ? fields[index].trim() : fields[index].strip();
        // Remove surrounding quotes
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    private int parseLevel(String levelStr, String pcfId) {
        if (levelStr != null && !levelStr.isBlank()) {
            try {
                return Integer.parseInt(levelStr.trim());
            } catch (NumberFormatException ignored) {
                // Fall through to dot counting
            }
        }
        // Derive level from PCF ID dot count: "1.0" = 1 dot = level 1, "1.1.1" = 2 dots = level 3
        long dots = pcfId.chars().filter(c -> c == '.').count();
        return (int) dots + 1;
    }

    private String deriveParentPcfId(String pcfId) {
        int lastDot = pcfId.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return pcfId.substring(0, lastDot);
    }

    /**
     * Simple CSV line splitter that handles quoted fields.
     */
    static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());

        return fields.toArray(new String[0]);
    }
}
