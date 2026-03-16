package com.taxonomy.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;
import org.apache.poi.ss.usermodel.*;

import java.io.InputStream;
import java.util.*;

/**
 * Parses APQC Process Classification Framework (PCF) Excel (.xlsx) files into
 * {@link ExternalElement}s and {@link ExternalRelation}s.
 *
 * <p>Many APQC licensees deliver the PCF as {@code .xlsx}. This parser uses
 * Apache POI (already a project dependency) to read the workbook.
 *
 * <p>Expected column structure (flexible header matching):
 * <pre>
 * PCF ID | Name | Level | Description
 * 1.0    | Develop Vision and Strategy | 1 |
 * </pre>
 */
public class ApqcExcelParser implements ExternalParser {

    private static final String[] LEVEL_TYPES = {
        "Category", "ProcessGroup", "Process", "Activity", "Task"
    };

    @Override
    public String fileFormat() {
        return "xlsx";
    }

    @Override
    public ParsedExternalModel parse(InputStream input) throws Exception {
        List<ExternalElement> elements = new ArrayList<>();
        List<ExternalRelation> relations = new ArrayList<>();
        Map<String, String> idByPcfId = new LinkedHashMap<>();

        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                return new ParsedExternalModel(elements, relations);
            }

            Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) {
                return new ParsedExternalModel(elements, relations);
            }

            // Parse header row
            Row headerRow = rowIterator.next();
            int pcfIdCol = findColumn(headerRow, "PCF ID", "Id", "ID", "pcf_id");
            int nameCol = findColumn(headerRow, "Name", "name", "Process Name", "Title");
            int levelCol = findColumn(headerRow, "Level", "level", "Hierarchy Level");
            int descCol = findColumn(headerRow, "Description", "description", "Desc");

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                String pcfId = getCellString(row, pcfIdCol);
                String name = getCellString(row, nameCol);
                String levelStr = getCellString(row, levelCol);
                String description = getCellString(row, descCol, false);

                if (pcfId == null || pcfId.isBlank()) continue;

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

    private int findColumn(Row headerRow, String... candidates) {
        for (Cell cell : headerRow) {
            String value = getCellValueAsString(cell);
            if (value == null) continue;
            for (String candidate : candidates) {
                if (value.trim().equalsIgnoreCase(candidate)) {
                    return cell.getColumnIndex();
                }
            }
        }
        return -1;
    }

    private String getCellString(Row row, int colIndex) {
        return getCellString(row, colIndex, true);
    }

    private String getCellString(Row row, int colIndex, boolean trim) {
        if (colIndex < 0) return null;
        Cell cell = row.getCell(colIndex);
        return getCellValueAsString(cell, trim);
    }

    private String getCellValueAsString(Cell cell) {
        return getCellValueAsString(cell, true);
    }

    private String getCellValueAsString(Cell cell, boolean trim) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> {
                RichTextString rts = cell.getRichStringCellValue();
                String s = rts != null ? rts.getString() : cell.getStringCellValue();
                yield trim ? s.trim() : s.strip();
            }
            case NUMERIC -> {
                double num = cell.getNumericCellValue();
                if (num == Math.floor(num) && !Double.isInfinite(num)) {
                    yield String.valueOf((long) num);
                }
                yield String.valueOf(num);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private int parseLevel(String levelStr, String pcfId) {
        if (levelStr != null && !levelStr.isBlank()) {
            try {
                return Integer.parseInt(levelStr.trim());
            } catch (NumberFormatException ignored) {
                // Fall through
            }
        }
        long dots = pcfId.chars().filter(c -> c == '.').count();
        return (int) dots + 1;
    }

    private String deriveParentPcfId(String pcfId) {
        int lastDot = pcfId.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return pcfId.substring(0, lastDot);
    }
}
