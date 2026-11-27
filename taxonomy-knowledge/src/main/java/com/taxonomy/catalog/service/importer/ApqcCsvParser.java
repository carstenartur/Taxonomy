package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;

import org.apache.commons.csv.CSVFormat;

import java.io.UncheckedIOException;
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

        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             var records = CSVFormat.DEFAULT.parse(reader)) {
            var iterator = records.iterator();
            if (!iterator.hasNext()) return new ParsedExternalModel(elements, relations);
            String[] headers = iterator.next().values();
            int pcfIdCol = findColumn(headers, "PCF ID", "Id", "ID", "pcf_id");
            int nameCol = findColumn(headers, "Name", "name", "Process Name", "Title");
            int levelCol = findColumn(headers, "Level", "level", "Hierarchy Level");
            int descCol = findColumn(headers, "Description", "description", "Desc");

            while (iterator.hasNext()) {
                String[] fields = iterator.next().values();
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
                elements.add(new ExternalElement(elementId, type, name, description, props));
                idByPcfId.put(pcfId, elementId);
            }
        } catch (UncheckedIOException malformed) {
            throw malformed.getCause();
        }

        // Resolve after the final row: a parent can occur later, and categories can use the PCF "1.0" notation.
        for (var element : elements) {
            String pcfId = element.properties().get("pcfId");
            if (categoryId(pcfId)) continue;
            String parent = deriveParentPcfId(pcfId);
            if (parent == null) continue;
            if (!parent.contains(".") && !idByPcfId.containsKey(parent) && idByPcfId.containsKey(parent + ".0"))
                parent += ".0";
            String resolved = idByPcfId.get(parent);
            element.properties().put("parentId", resolved == null ? "apqc-" + parent.replace(".", "-") : resolved);
            if (resolved != null) relations.add(new ExternalRelation(resolved, element.id(), "ParentChild", Map.of()));
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
        // Top-level PCF categories conventionally use e.g. "1.0". Their child "1.1" is level two.
        if (categoryId(pcfId)) return 1;
        long dots = pcfId.chars().filter(c -> c == '.').count();
        return (int) dots + 1;
    }

    private static boolean categoryId(String pcfId) {
        return pcfId.endsWith(".0") && pcfId.indexOf('.') == pcfId.length() - 2;
    }

    private String deriveParentPcfId(String pcfId) {
        int lastDot = pcfId.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return pcfId.substring(0, lastDot);
    }

}
