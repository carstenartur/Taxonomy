package com.taxonomy.export.reformulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reads the saved walk-up evidence for display; the archived discovery stays unchanged. */
public final class ReformulationDiscoveryPresentation {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReformulationDiscoveryPresentation() {}

    public static List<String> context(String saved, boolean german) {
        String absent = german ? "Nicht erfasst" : "Not recorded";
        if (saved == null || saved.isBlank()) return List.of(absent);
        JsonNode root = object(saved);
        if (root == null || !root.has("current")
                || !(root.path("terminalContributions").isArray() || root.path("directParentContributions").isArray())) {
            return List.of(saved);
        }
        var result = new ArrayList<String>();
        appendNode(result, root.path("current"), german ? "Aktueller Knoten" : "Current node", german);
        for (var contribution : root.path("terminalContributions")) {
            appendNode(result, contribution, german ? "Beitrag eines Endknotens" : "Terminal node contribution", german);
        }
        for (var contribution : root.path("directParentContributions")) {
            appendNode(result, contribution, german ? "Direkter Beitrag" : "Direct contribution", german);
        }
        appendFields(result, root, Set.of("current", "terminalContributions", "directParentContributions"), german);
        return result.isEmpty() ? List.of(absent) : List.copyOf(result);
    }

    private static void appendNode(List<String> result, JsonNode node, String role, boolean german) {
        if (!node.isObject() || !node.path("id").isTextual()) {
            appendValue(result, role, node, german);
            return;
        }
        String description = node.path("description").asText("");
        JsonNode catalogue = object(description);
        String title = catalogue == null ? "" : localized(catalogue, "name", german);
        String score = node.path("score").isNumber() ? " · " + node.path("score").asText() + "%" : "";
        result.add(role + ": " + node.path("id").asText() + (title.isBlank() ? "" : " · " + title) + score);

        var parents = new ArrayList<String>();
        node.path("parentIds").forEach(parent -> parents.add(parent.asText()));
        if (!parents.isEmpty()) result.add((german ? "Übergeordnete Knoten: " : "Parent nodes: ") + String.join(", ", parents));
        if (catalogue == null) {
            if (!description.isBlank()) result.add(description);
        } else {
            String readable = localized(catalogue, "description", german);
            if (!readable.isBlank()) result.add(readable);
            // A catalogue often stores the same text under both the neutral and localized keys.
            // Keep distinct alternate wording, including the original when it differs.
            catalogue.properties().forEach(field -> {
                String key = field.getKey();
                String value = field.getValue().asText("");
                if (Set.of("name", "nameDe", "nameEn").contains(key) && value.equals(title)) return;
                if (Set.of("description", "descriptionDe", "descriptionEn").contains(key) && value.equals(readable)) return;
                appendValue(result, label(key, german), field.getValue(), german);
            });
        }
        appendFields(result, node, Set.of("id", "parentIds", "score", "description"), german);
    }

    private static void appendFields(List<String> result, JsonNode node, Set<String> displayed, boolean german) {
        node.properties().forEach(field -> {
            if (!displayed.contains(field.getKey()))
                appendValue(result, label(field.getKey(), german), field.getValue(), german);
        });
    }

    /** Keep the field and array position of nested evidence, including future catalogue fields. */
    private static void appendValue(List<String> result, String path, JsonNode value, boolean german) {
        if (value.isObject()) {
            value.properties().forEach(field -> appendValue(result, path + " › " + label(field.getKey(), german),
                    field.getValue(), german));
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) appendValue(result, path + " [" + (i + 1) + "]", value.get(i), german);
        } else if (!value.isNull() && !value.asText().isBlank()) {
            result.add(path + ": " + value.asText());
        }
    }

    private static String label(String key, boolean german) {
        return switch (key) {
            case "source" -> german ? "Quelle" : "Source";
            case "reference" -> german ? "Referenz" : "Reference";
            case "uuid" -> "UUID";
            case "id" -> "ID";
            case "code" -> "Code";
            case "state" -> german ? "Status" : "State";
            case "analysisRole" -> german ? "Analyserolle" : "Analysis role";
            case "classificationConfidence" -> german ? "Klassifikationskonfidenz" : "Classification confidence";
            case "classificationJustification" -> german ? "Klassifikationsbegründung" : "Classification justification";
            case "classificationReviewRequired" -> german ? "Klassifikationsprüfung erforderlich" : "Classification review required";
            case "secondaryClassificationCodes" -> german ? "Weitere Klassifikationscodes" : "Secondary classification codes";
            case "taxonomyContainer" -> german ? "Taxonomiecontainer" : "Taxonomy container";
            case "taxonomyRoot" -> german ? "Taxonomiewurzel" : "Taxonomy root";
            case "parentCode" -> german ? "Übergeordneter Code" : "Parent code";
            case "level" -> german ? "Ebene" : "Level";
            case "sortOrder" -> german ? "Sortierreihenfolge" : "Sort order";
            case "name", "nameEn", "nameDe" -> "Name" + languageSuffix(key);
            case "description", "descriptionEn", "descriptionDe" -> (german ? "Beschreibung" : "Description") + languageSuffix(key);
            case "dataset" -> german ? "Datensatz" : "Dataset";
            case "externalId" -> german ? "Externe ID" : "External ID";
            case "matchPercentage" -> german ? "Übereinstimmung (%)" : "Match (%)";
            case "incomingRelations" -> german ? "Eingehende Beziehungen" : "Incoming relationships";
            case "outgoingRelations" -> german ? "Ausgehende Beziehungen" : "Outgoing relationships";
            case "directContributions" -> german ? "Direkte Beiträge" : "Direct contributions";
            case "wording" -> german ? "Wortlaut" : "Wording";
            case "sourceSpans" -> german ? "Originalstellen (UTF-16)" : "Source spans (UTF-16)";
            case "start" -> german ? "Beginn" : "Start";
            case "end" -> german ? "Ende" : "End";
            case "exactText" -> german ? "Originaltext" : "Exact text";
            case "architectureLinks" -> german ? "Architekturverweise" : "Architecture links";
            case "questionDependencies" -> german ? "Frageabhängigkeiten" : "Question dependencies";
            case "conditionalValidity" -> german ? "Gültigkeitsbedingung" : "Conditional validity";
            case "editingOrigin" -> german ? "Bearbeitungsherkunft" : "Editing origin";
            case "provenance" -> german ? "Herkunft" : "Provenance";
            case "reviewState" -> german ? "Prüfstatus" : "Review state";
            default -> key;
        };
    }

    private static String languageSuffix(String key) {
        if (key.endsWith("De")) return " (DE)";
        if (key.endsWith("En")) return " (EN)";
        return "";
    }

    private static String localized(JsonNode node, String field, boolean german) {
        for (String name : List.of(field + (german ? "De" : "En"), field, field + (german ? "En" : "De"))) {
            String text = node.path(name).asText("");
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static JsonNode object(String value) {
        if (!value.stripLeading().startsWith("{")) return null;
        try {
            JsonNode node = JSON.readTree(value);
            return node.isObject() ? node : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
}
