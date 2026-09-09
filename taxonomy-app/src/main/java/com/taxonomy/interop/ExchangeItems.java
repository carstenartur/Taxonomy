package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Uniform comparison values; relation/hierarchy identities remain distinct from their endpoint identities. */
public final class ExchangeItems {
    private ExchangeItems() {}
    public static String key(Artifact value) { return value.kind().name() + ":" + value.id(); }
    public static Map<String, Artifact> flatten(ExchangeDocument document) {
        Map<String, Artifact> result = new TreeMap<>();
        document.artifacts().forEach(a -> add(result, a));
        for (Relation relation : document.relations()) {
            Map<String, String> extension = new LinkedHashMap<>(relation.extensions()); extension.put("source", relation.source()); extension.put("target", relation.target());
            add(result, new Artifact(relation.id(), ArtifactKind.RELATION, relation.type(), "", "", relation.attributes(), extension));
        }
        for (Placement placement : document.placements()) {
            Map<String, String> extension = new LinkedHashMap<>(); extension.put("container", placement.containerId());
            extension.put("parent", placement.parentId() == null ? "" : placement.parentId()); extension.put("artifact", placement.artifactId() == null ? "" : placement.artifactId());
            extension.put("position", Integer.toString(placement.position()));
            add(result, new Artifact(placement.id(), ArtifactKind.PLACEMENT, "placement", "", "", placement.attributes(), extension));
        }
        Map<String, String> modelMetadata = new TreeMap<>(document.metadata());
        modelMetadata.remove("remoteRequest"); modelMetadata.remove("discovery");
        add(result, new Artifact("package", ArtifactKind.METADATA, document.profile(), "", "", modelMetadata, Map.of()));
        return result;
    }
    public static ExchangeDocument expand(ExchangeDocument template, Map<String, Artifact> items) {
        List<Artifact> artifacts = new ArrayList<>(); List<Relation> relations = new ArrayList<>(); List<Placement> placements = new ArrayList<>();
        Map<String, String> metadata = template.metadata();
        for (Artifact item : new TreeMap<>(items).values()) switch (item.kind()) {
            case RELATION -> {
                Map<String, String> extension = new LinkedHashMap<>(item.extensions()); String source = extension.remove("source"), target = extension.remove("target");
                relations.add(new Relation(item.id(), item.type(), source, target, item.attributes(), extension));
            }
            case PLACEMENT -> placements.add(new Placement(item.id(), item.extensions().get("container"), emptyToNull(item.extensions().get("parent")),
                    emptyToNull(item.extensions().get("artifact")), Integer.parseInt(item.extensions().get("position")), item.attributes()));
            case METADATA -> metadata = item.attributes();
            default -> artifacts.add(item);
        }
        return new ExchangeDocument(template.profile(), template.profileVersion(), template.externalVersion(), template.completeScope(), template.source(), artifacts, relations, placements, metadata, template.losses());
    }
    public static Map<String, String> fields(Artifact artifact) {
        Map<String, String> result = new TreeMap<>();
        if (artifact == null) return result;
        result.put("title", artifact.title()); result.put("text", artifact.text()); result.put("type", artifact.type()); result.put("kind", artifact.kind().name());
        artifact.attributes().forEach((key, value) -> {
            if (!key.equals("LAST-CHANGE") && !artifact.extensions().getOrDefault("definition:" + key, "").startsWith("Taxonomy."))
                result.put("attribute:" + key, comparable(value));
        });
        artifact.extensions().forEach((key, value) -> { if (!Set.of("xml", "rdfXml", "lastChange").contains(key)) result.put("extension:" + key, comparable(value)); });
        return result;
    }
    private static String comparable(String value) {
        if (value.startsWith("<")) {
            try { return com.taxonomy.exchange.ExchangeXml.semantic(value); }
            catch (com.taxonomy.exchange.ExchangeFormatException notXml) { return value; }
        }
        return value;
    }
    /** Apply only fields changed externally; preserve non-intersecting local edits. */
    public static Artifact merge(Artifact baselineExternal, Artifact currentInternal, Artifact incoming) {
        if (currentInternal == null || baselineExternal == null) return incoming;
        Map<String, String> before = fields(baselineExternal), after = fields(incoming);
        Map<String, String> merged = new TreeMap<>(rawFields(currentInternal)), incomingValues = rawFields(incoming);
        var keys = new java.util.TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        for (String key : keys) if (!java.util.Objects.equals(before.get(key), after.get(key))) {
            if (after.containsKey(key)) merged.put(key, incomingValues.get(key)); else merged.remove(key);
        }
        Map<String, String> attributes = new LinkedHashMap<>(), extensions = new LinkedHashMap<>();
        merged.forEach((key, value) -> { if (key.startsWith("attribute:")) attributes.put(key.substring(10), value);
            if (key.startsWith("extension:")) extensions.put(key.substring(10), value); });
        // Evidence describes the newly observed source, while mapped values above keep disjoint local edits.
        for (String evidence : List.of("xml", "rdfXml", "lastChange")) {
            extensions.remove(evidence);
            if (incoming.extensions().containsKey(evidence)) extensions.put(evidence, incoming.extensions().get(evidence));
        }
        return new Artifact(incoming.id(), incoming.kind(), merged.get("type"), merged.get("title"), merged.get("text"), attributes, extensions);
    }
    private static Map<String, String> rawFields(Artifact value) {
        Map<String, String> result = new TreeMap<>();
        result.put("title", value.title()); result.put("text", value.text()); result.put("type", value.type()); result.put("kind", value.kind().name());
        value.attributes().forEach((key, item) -> result.put("attribute:" + key, item));
        value.extensions().forEach((key, item) -> result.put("extension:" + key, item)); return result;
    }
    private static void add(Map<String, Artifact> items, Artifact value) {
        if (items.putIfAbsent(key(value), value) != null) throw new IntegrationProblem("DUPLICATE_IDENTITY", 400, "Exchange contains a duplicate external identity");
    }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
}
