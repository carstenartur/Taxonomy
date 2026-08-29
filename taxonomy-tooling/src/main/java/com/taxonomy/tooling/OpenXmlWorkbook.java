package com.taxonomy.tooling;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal, dependency-free OOXML workbook reader used by repository tooling.
 *
 * <p>The application itself uses Apache POI at runtime. Build tooling deliberately
 * avoids that dependency and reads only the small subset required for catalogue
 * proposal generation: workbook relationships, shared strings and worksheet cells.</p>
 */
final class OpenXmlWorkbook {

    private static final String OFFICE_RELATIONSHIPS_NAMESPACE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;

    private OpenXmlWorkbook() {
    }

    static SheetData readSheet(Path workbookPath, String requestedSheet) throws IOException {
        Objects.requireNonNull(workbookPath, "workbookPath");
        Objects.requireNonNull(requestedSheet, "requestedSheet");

        try (ZipFile archive = new ZipFile(workbookPath.toFile(), StandardCharsets.UTF_8)) {
            Document workbook = readXml(archive, "xl/workbook.xml");
            Document relationships = readXml(archive, "xl/_rels/workbook.xml.rels");
            Map<String, String> relationshipTargets = relationshipTargets(relationships);

            String relationshipId = null;
            for (Element sheet : XmlSupport.descendants(
                    workbook.getDocumentElement(), "sheet")) {
                if (!requestedSheet.equals(sheet.getAttribute("name"))) {
                    continue;
                }
                relationshipId = sheet.getAttributeNS(
                        OFFICE_RELATIONSHIPS_NAMESPACE, "id");
                if (relationshipId.isBlank()) {
                    relationshipId = sheet.getAttribute("r:id");
                }
                break;
            }
            if (relationshipId == null || relationshipId.isBlank()) {
                throw new IllegalArgumentException(
                        "Workbook has no sheet named '" + requestedSheet + "'");
            }

            String target = relationshipTargets.get(relationshipId);
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException(
                        "Workbook relationship " + relationshipId
                                + " has no worksheet target");
            }

            String worksheetPath = resolveTarget("xl/workbook.xml", target);
            Document worksheet = readXml(archive, worksheetPath);
            List<String> sharedStrings = readSharedStrings(archive);
            return parseSheet(worksheet, sharedStrings, requestedSheet);
        }
    }

    private static Map<String, String> relationshipTargets(Document relationships) {
        Map<String, String> targets = new LinkedHashMap<>();
        for (Element relationship : XmlSupport.descendants(
                relationships.getDocumentElement(), "Relationship")) {
            String id = relationship.getAttribute("Id");
            String target = relationship.getAttribute("Target");
            if (id.isBlank() || target.isBlank()) {
                continue;
            }
            if (targets.putIfAbsent(id, target) != null) {
                throw new IllegalArgumentException(
                        "Workbook contains duplicate relationship " + id);
            }
        }
        return targets;
    }

    private static List<String> readSharedStrings(ZipFile archive) throws IOException {
        ZipEntry entry = archive.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        Document document = parseXml(readEntry(archive, entry));
        List<String> strings = new ArrayList<>();
        for (Element item : XmlSupport.descendants(
                document.getDocumentElement(), "si")) {
            StringBuilder text = new StringBuilder();
            for (Element part : XmlSupport.descendants(item, "t")) {
                text.append(part.getTextContent());
            }
            strings.add(text.toString());
        }
        return List.copyOf(strings);
    }

    private static SheetData parseSheet(
            Document worksheet,
            List<String> sharedStrings,
            String sheetName) {
        List<String> headers = null;
        List<Map<String, String>> rows = new ArrayList<>();

        for (Element row : XmlSupport.descendants(
                worksheet.getDocumentElement(), "row")) {
            Map<Integer, String> values = new LinkedHashMap<>();
            int implicitColumn = 0;
            for (Element cell : XmlSupport.children(row, "c")) {
                String reference = cell.getAttribute("r");
                int column = reference.isBlank()
                        ? implicitColumn
                        : columnIndex(reference);
                implicitColumn = column + 1;
                values.put(column, cellValue(cell, sharedStrings));
            }
            if (values.values().stream().allMatch(OpenXmlWorkbook::isBlank)) {
                continue;
            }

            int width = values.keySet().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(-1) + 1;
            List<String> ordered = new ArrayList<>(width);
            for (int column = 0; column < width; column++) {
                ordered.add(values.get(column));
            }

            if (headers == null) {
                headers = ordered.stream()
                        .map(OpenXmlWorkbook::trimToEmpty)
                        .toList();
                continue;
            }

            LinkedHashMap<String, String> mapped = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String value = column < ordered.size() ? ordered.get(column) : null;
                mapped.put(headers.get(column), trimToNull(value));
            }
            rows.add(mapped);
        }

        if (headers == null) {
            throw new IllegalArgumentException(
                    "Worksheet '" + sheetName + "' contains no header row");
        }
        return new SheetData(List.copyOf(headers), List.copyOf(rows));
    }

    private static String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            StringBuilder value = new StringBuilder();
            for (Element text : XmlSupport.descendants(cell, "t")) {
                value.append(text.getTextContent());
            }
            return value.toString();
        }

        Element valueElement = XmlSupport.child(cell, "v");
        String raw = valueElement == null ? null : valueElement.getTextContent();
        if (raw == null) {
            Element formula = XmlSupport.child(cell, "f");
            return formula == null ? null : formula.getTextContent();
        }
        if ("s".equals(type)) {
            int index;
            try {
                index = Integer.parseInt(raw.strip());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        "Shared-string cell contains invalid index '" + raw + "'", error);
            }
            if (index < 0 || index >= sharedStrings.size()) {
                throw new IllegalArgumentException(
                        "Shared-string index " + index + " is outside the string table");
            }
            return sharedStrings.get(index);
        }
        if ("b".equals(type)) {
            return "1".equals(raw.strip()) ? "true" : "false";
        }
        return raw;
    }

    private static int columnIndex(String reference) {
        int result = 0;
        int letters = 0;
        for (int index = 0; index < reference.length(); index++) {
            char character = reference.charAt(index);
            if (character == '$') {
                continue;
            }
            if (!Character.isLetter(character)) {
                break;
            }
            result = result * 26 + (Character.toUpperCase(character) - 'A' + 1);
            letters++;
        }
        if (letters == 0) {
            throw new IllegalArgumentException(
                    "Worksheet cell has invalid reference '" + reference + "'");
        }
        return result - 1;
    }

    private static String resolveTarget(String baseEntry, String rawTarget) {
        String target = rawTarget.replace('\\', '/');
        if (target.startsWith("/")) {
            target = target.substring(1);
        } else {
            int slash = baseEntry.lastIndexOf('/');
            String baseDirectory = slash < 0 ? "" : baseEntry.substring(0, slash + 1);
            target = baseDirectory + target;
        }

        List<String> segments = new ArrayList<>();
        for (String segment : target.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Workbook relationship escapes the archive root: " + rawTarget);
                }
                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
        return String.join("/", segments);
    }

    private static Document readXml(ZipFile archive, String entryName) throws IOException {
        ZipEntry entry = archive.getEntry(entryName);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "Workbook is missing required OOXML entry " + entryName);
        }
        return parseXml(readEntry(archive, entry));
    }

    private static Document parseXml(byte[] bytes) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            return XmlSupport.parse(input);
        }
    }

    private static byte[] readEntry(ZipFile archive, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_ENTRY_BYTES) {
            throw new IllegalArgumentException(
                    "OOXML entry " + entry.getName() + " exceeds "
                            + MAX_ENTRY_BYTES + " bytes");
        }
        try (InputStream input = archive.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_ENTRY_BYTES) {
                    throw new IllegalArgumentException(
                            "OOXML entry " + entry.getName() + " exceeds "
                                    + MAX_ENTRY_BYTES + " bytes while inflating");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    record SheetData(List<String> headers, List<Map<String, String>> rows) {
    }
}
