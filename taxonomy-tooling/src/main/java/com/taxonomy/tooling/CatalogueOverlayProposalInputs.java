package com.taxonomy.tooling;

import com.taxonomy.tooling.CatalogueOverlayProposalModel.OverlayModel;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Patch;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceCatalogue;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Parses and validates the immutable workbook and reviewed-overlay inputs. */
final class CatalogueOverlayProposalInputs {

    private static final int SUPPORTED_OVERLAY_SCHEMA_VERSION = 2;
    private static final String SUPPORTED_OVERLAY_MODE = "OVERLAY";
    private static final List<String> EXPECTED_HEADERS = List.of(
            "Page", "UUID", "Title", "Description", "Parent", "Dataset",
            "ExternalID", "Source", "Reference", "Order", "State", "Level");

    private CatalogueOverlayProposalInputs() {
    }

    static OverlayModel readOverlay(Path overlayPath, String catalogueFilename)
            throws IOException {
        return parseOverlay(
                Files.readString(overlayPath, StandardCharsets.UTF_8),
                catalogueFilename);
    }

    static OverlayModel parseOverlay(String source, String catalogueFilename) {
        Map<String, Object> root = FlatJson.parseObject(source);
        int schemaVersion = integer(root.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != SUPPORTED_OVERLAY_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported overlay schemaVersion " + schemaVersion
                            + "; expected " + SUPPORTED_OVERLAY_SCHEMA_VERSION);
        }
        String mode = requiredText(root, "mode").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_OVERLAY_MODE.equals(mode)) {
            throw new IllegalArgumentException(
                    "Unsupported overlay mode '" + mode + "'");
        }
        String baseCatalogue = requiredText(root, "baseCatalogue");
        if (!catalogueFilename.equals(baseCatalogue)) {
            throw new IllegalArgumentException(
                    "Overlay targets baseCatalogue '" + baseCatalogue
                            + "' but the supplied workbook is '" + catalogueFilename + "'");
        }
        String mappingVersion = requiredText(root, "mappingVersion");

        Map<String, Object> validation = optionalObject(root.get("validation"), "validation");
        String strictRoot = optionalText(validation, "requireExplicitPatchForRoot", "IP");
        String strictState = optionalText(validation, "requireExplicitPatchForState", "draft");

        LinkedHashMap<String, Patch> patches = new LinkedHashMap<>();
        for (Object rawPatch : requiredArray(root, "nodePatches")) {
            Map<String, Object> patch = object(rawPatch, "nodePatches[]");
            String code = requiredText(patch, "code");
            String role = requiredText(patch, "analysisRole").toUpperCase(Locale.ROOT);
            if (!Set.of(
                    CatalogueOverlayProposalGenerator.ROLE_PRODUCT,
                    CatalogueOverlayProposalGenerator.ROLE_PRODUCT_FAMILY).contains(role)) {
                throw new IllegalArgumentException(
                        "Patch " + code + " has unsupported analysisRole '" + role + "'");
            }
            double confidence = number(patch.get("confidence"), code + ".confidence");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException(
                        "Patch " + code + " confidence must be between 0 and 1");
            }
            Patch parsed = new Patch(
                    code,
                    requiredText(patch, "expectedTitle"),
                    requiredText(patch, "expectedState"),
                    requiredText(patch, "parentCode"),
                    role,
                    stringArray(patch.get("secondaryClassificationCodes"),
                            code + ".secondaryClassificationCodes"),
                    decimal(confidence),
                    booleanValue(patch.get("reviewRequired"),
                            code + ".reviewRequired"),
                    requiredText(patch, "justification"));
            if (patches.putIfAbsent(code, parsed) != null) {
                throw new IllegalArgumentException("Duplicate overlay patch for " + code);
            }
        }
        return new OverlayModel(
                schemaVersion,
                mode,
                baseCatalogue,
                mappingVersion,
                strictRoot,
                strictState,
                Map.copyOf(patches));
    }

    static SourceCatalogue readSourceCatalogue(Path catalogue, String rootCode)
            throws IOException {
        OpenXmlWorkbook.SheetData sheet = OpenXmlWorkbook.readSheet(
                catalogue, "Information Products");
        if (!EXPECTED_HEADERS.equals(sheet.headers())) {
            throw new IllegalArgumentException(
                    "Information Products columns changed; expected " + EXPECTED_HEADERS
                            + " but found " + sheet.headers());
        }

        LinkedHashMap<String, SourceNode> rawNodes = new LinkedHashMap<>();
        Map<String, String> uuidToCode = new HashMap<>();
        for (Map<String, String> row : sheet.rows()) {
            String code = row.get("Page");
            String title = row.get("Title");
            if (isBlank(code) || isBlank(title)) {
                continue;
            }
            String uuid = row.get("UUID");
            SourceNode node = new SourceNode(
                    code,
                    uuid,
                    title,
                    nullToEmpty(row.get("Description")),
                    row.get("Parent"),
                    nullToEmpty(row.get("State")),
                    parseInteger(row.get("Level"), 1));
            if (rawNodes.putIfAbsent(code, node) != null) {
                throw new IllegalArgumentException(
                        "Information Products sheet contains duplicate code " + code);
            }
            if (!isBlank(uuid) && uuidToCode.putIfAbsent(uuid, code) != null) {
                throw new IllegalArgumentException(
                        "Information Products sheet contains duplicate UUID " + uuid);
            }
        }
        if (rawNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Information Products sheet contains no catalogue nodes");
        }

        LinkedHashMap<String, SourceNode> resolved = new LinkedHashMap<>();
        for (SourceNode node : rawNodes.values()) {
            String parent = node.sourceParentCode();
            if (isBlank(parent)) {
                parent = rootCode;
            } else if (!rootCode.equals(parent) && !rawNodes.containsKey(parent)) {
                String resolvedCode = uuidToCode.get(parent);
                if (resolvedCode != null) {
                    parent = resolvedCode;
                }
            }
            resolved.put(node.code(), node.withParent(parent));
        }
        return new SourceCatalogue(rootCode, Map.copyOf(resolved));
    }

    static void validateOverlayAgainstSource(
            OverlayModel overlay,
            SourceCatalogue source) {
        Set<String> secondaryCodes = new HashSet<>();
        for (Patch patch : overlay.patches().values()) {
            SourceNode node = source.nodes().get(patch.code());
            if (node == null) {
                throw new IllegalArgumentException(
                        "Overlay patch references unknown source node " + patch.code());
            }
            if (!normalizeWhitespace(patch.expectedTitle())
                    .equals(normalizeWhitespace(node.title()))) {
                throw new IllegalArgumentException(
                        "Overlay patch " + patch.code() + " expected title '"
                                + patch.expectedTitle() + "' but source title is '"
                                + node.title() + "'");
            }
            if (!patch.expectedState().equalsIgnoreCase(node.state())) {
                throw new IllegalArgumentException(
                        "Overlay patch " + patch.code() + " expected state '"
                                + patch.expectedState() + "' but source state is '"
                                + node.state() + "'");
            }
            CatalogueOverlayProposalValidator.requireKnownParent(
                    patch.code(), patch.parentCode(), source, overlay.strictRoot());
            if (patch.code().equals(patch.parentCode())) {
                throw new IllegalArgumentException(
                        "Overlay patch " + patch.code() + " is self-parented");
            }
            secondaryCodes.clear();
            for (String secondary : patch.secondaryClassificationCodes()) {
                CatalogueOverlayProposalValidator.requireKnownParent(
                        patch.code(), secondary, source, overlay.strictRoot());
                if (secondary.equals(patch.code()) || secondary.equals(patch.parentCode())) {
                    throw new IllegalArgumentException(
                            "Overlay patch " + patch.code()
                                    + " has redundant secondary classification " + secondary);
                }
                if (!secondaryCodes.add(secondary)) {
                    throw new IllegalArgumentException(
                            "Overlay patch " + patch.code()
                                    + " has duplicate secondary classification " + secondary);
                }
            }
        }
    }

    static Map<String, String> effectiveParents(
            SourceCatalogue source,
            OverlayModel overlay) {
        LinkedHashMap<String, String> parents = new LinkedHashMap<>();
        source.nodes().values().stream()
                .sorted(Comparator.comparing(SourceNode::code))
                .forEach(node -> {
                    String parent = node.sourceParentCode();
                    boolean missingStrictPatch = overlay.strictState().equalsIgnoreCase(node.state())
                            && node.code().startsWith(overlay.strictRoot() + "-")
                            && !overlay.patches().containsKey(node.code());
                    if (missingStrictPatch) {
                        // The runtime overlay fails strict coverage here. A temporary root
                        // attachment keeps the proposal graph valid while the original source
                        // parent remains visible in the review artifact.
                        parent = overlay.strictRoot();
                    }
                    parents.put(node.code(), parent);
                });
        overlay.patches().values().stream()
                .sorted(Comparator.comparing(Patch::code))
                .forEach(patch -> parents.put(patch.code(), patch.parentCode()));
        return parents;
    }

    static Set<String> candidateFamilyCodes(
            OverlayModel overlay,
            SourceCatalogue source) {
        Set<String> families = new LinkedHashSet<>();
        for (Patch patch : overlay.patches().values()) {
            if (CatalogueOverlayProposalGenerator.ROLE_PRODUCT_FAMILY
                    .equals(patch.analysisRole())) {
                families.add(patch.code());
            }
            if (CatalogueOverlayProposalGenerator.ROLE_PRODUCT
                    .equals(patch.analysisRole())) {
                families.add(patch.parentCode());
                families.addAll(patch.secondaryClassificationCodes());
            }
        }
        families.remove(overlay.strictRoot());
        families.removeIf(code -> !source.nodes().containsKey(code));
        return families.stream().sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static int parseInteger(String value, int fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid integer value '" + value + "'", error);
        }
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> optionalObject(Object value, String name) {
        return value == null ? Map.of() : object(value, name);
    }

    private static List<Object> requiredArray(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
        return new ArrayList<>(list);
    }

    private static List<String> stringArray(Object value, String name) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
        List<String> result = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank strings");
            }
            if (!duplicates.add(text)) {
                throw new IllegalArgumentException(name + " contains duplicate value " + text);
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String requiredText(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }

    private static String optionalText(
            Map<String, Object> object,
            String name,
            String fallback) {
        Object value = object.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }

    private static int integer(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return number.intValue();
    }

    private static double number(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return number.doubleValue();
    }

    private static boolean booleanValue(Object value, String name) {
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return bool;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private static String normalizeWhitespace(String value) {
        return nullToEmpty(value).strip().replaceAll("\\s+", " ");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
