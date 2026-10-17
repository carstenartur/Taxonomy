package com.taxonomy.archimate.exchange;

import com.taxonomy.archimate.ArchiMateProperty;
import com.taxonomy.diagram.DiagramRelationMetadata;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.model.SeedType;

import java.math.BigDecimal;
import java.util.*;

/** Optional, typed Exchange properties retain recorded evidence without inventing review status. */
public final class ArchiMateRelationProvenance {
    private static final String PREFIX = "taxonomy.provenance.";
    private ArchiMateRelationProvenance() { }

    public static Map<String, ArchiMateProperty> write(DiagramRelationMetadata metadata) {
        if (metadata == null) return Map.of();
        var fields = new LinkedHashMap<String, ArchiMateProperty>();
        put(fields, "version", "1");
        if (metadata.relationId() != null)
            fields.put(PREFIX + "relationId", new ArchiMateProperty("number", metadata.relationId().toString()));
        put(fields, "origin", metadata.origin());
        if (metadata.confidence() != null) fields.put(PREFIX + "confidence", ArchiMateProperty.number(metadata.confidence()));
        put(fields, "includedBecause", metadata.includedBecause());
        put(fields, "derivationReason", metadata.derivationReason());
        put(fields, "presenceReason", metadata.presenceReason());
        put(fields, "seedType", metadata.seedType());
        fields.put(PREFIX + "evidenceCount", ArchiMateProperty.number(metadata.requirementEvidence().size()));
        for (int index = 0; index < metadata.requirementEvidence().size(); index++) {
            Edge evidence = metadata.requirementEvidence().get(index);
            String key = "evidence." + index + ".";
            node(fields, key + "contribution.source.", evidence.contribution().source());
            put(fields, key + "contribution.text", evidence.contribution().text());
            put(fields, key + "contribution.quote", evidence.contribution().quote());
            put(fields, key + "contribution.condition", evidence.contribution().condition());
            node(fields, key + "target.", evidence.target());
            put(fields, key + "type", evidence.type());
            put(fields, key + "direction", evidence.direction());
            fields.put(PREFIX + key + "hasDecision", ArchiMateProperty.flag(evidence.evidence() != null));
            Decision d = evidence.evidence();
            if (d != null) {
                put(fields, key + "decision.targetId", d.targetId());
                put(fields, key + "decision.outcome", d.outcome());
                put(fields, key + "decision.contribution", d.contribution());
                put(fields, key + "decision.quote", d.quote());
                put(fields, key + "decision.necessity", d.necessity());
                put(fields, key + "decision.condition", d.condition());
                put(fields, key + "decision.alternativeGroup", d.alternativeGroup());
                put(fields, key + "decision.rationale", d.rationale());
                put(fields, key + "decision.question", d.question());
            }
        }
        return fields;
    }

    public static DiagramRelationMetadata read(Map<String, ArchiMateProperty> properties) {
        var f = new Fields(properties);
        if (f.values.isEmpty()) return null; // Legacy exchanges never asserted provenance.
        if (!"1".equals(f.required("version", "string"))) throw new IllegalArgumentException("Unsupported relationship provenance version");
        String relationId = f.optional("relationId", "number");
        Long id = relationId == null ? null : new BigDecimal(relationId).longValueExact();
        RelationOrigin origin = f.enumeration("origin", RelationOrigin.class);
        String confidenceValue = f.optional("confidence", "number");
        Double confidence = confidenceValue == null ? null : Double.valueOf(confidenceValue);
        if (confidence != null && !Double.isFinite(confidence)) throw new IllegalArgumentException("Non-finite relationship confidence");
        String included = f.optional("includedBecause", "string");
        String derivation = f.optional("derivationReason", "string");
        String presence = f.optional("presenceReason", "string");
        SeedType seed = f.enumeration("seedType", SeedType.class);
        int count = new BigDecimal(f.required("evidenceCount", "number")).intValueExact();
        // Each evidence item needs several properties; reject impossible counts before iteration/allocation.
        if (count < 0 || count > f.values.size()) throw new IllegalArgumentException("Invalid relationship evidence count");
        var evidence = new ArrayList<Edge>();
        for (int index = 0; index < count; index++) {
            String key = "evidence." + index + ".";
            var source = node(f, key + "contribution.source.");
            var contribution = new Contribution(source, f.required(key + "contribution.text", "string"),
                    f.required(key + "contribution.quote", "string"), f.required(key + "contribution.condition", "string"));
            var target = node(f, key + "target.");
            String type = f.required(key + "type", "string");
            Direction direction = Direction.valueOf(f.required(key + "direction", "string"));
            Decision decision = null;
            if (Boolean.parseBoolean(f.required(key + "hasDecision", "boolean"))) {
                decision = new Decision(f.optional(key + "decision.targetId", "string"),
                        f.enumeration(key + "decision.outcome", Outcome.class),
                        f.optional(key + "decision.contribution", "string"), f.optional(key + "decision.quote", "string"),
                        f.enumeration(key + "decision.necessity", Necessity.class), f.optional(key + "decision.condition", "string"),
                        f.optional(key + "decision.alternativeGroup", "string"), f.optional(key + "decision.rationale", "string"),
                        f.optional(key + "decision.question", "string"));
            }
            evidence.add(new Edge(contribution, target, type, direction, decision));
        }
        if (!f.values.isEmpty()) throw new IllegalArgumentException("Unsupported relationship provenance fields: " + f.values.keySet());
        return new DiagramRelationMetadata(id, origin, confidence, included, derivation, presence, seed, evidence);
    }

    private static void node(Map<String, ArchiMateProperty> fields, String key, Node node) {
        put(fields, key + "id", node.id()); put(fields, key + "root", node.root());
        put(fields, key + "name", node.name()); put(fields, key + "description", node.description());
        fields.put(PREFIX + key + "container", ArchiMateProperty.flag(node.container()));
    }

    private static Node node(Fields fields, String key) {
        return new Node(fields.required(key + "id", "string"), fields.required(key + "root", "string"),
                fields.required(key + "name", "string"), fields.required(key + "description", "string"),
                Boolean.parseBoolean(fields.required(key + "container", "boolean")));
    }

    private static void put(Map<String, ArchiMateProperty> fields, String key, Object value) {
        if (value != null) fields.put(PREFIX + key, ArchiMateProperty.text(value instanceof Enum<?> e ? e.name() : value.toString()));
    }

    private static final class Fields {
        private final Map<String, ArchiMateProperty> values = new TreeMap<>();
        Fields(Map<String, ArchiMateProperty> properties) {
            properties.forEach((key, value) -> { if (key.startsWith(PREFIX)) values.put(key.substring(PREFIX.length()), value); });
        }
        String required(String key, String type) {
            String result = optional(key, type);
            if (result == null) throw new IllegalArgumentException("Missing relationship provenance property: " + key);
            return result;
        }
        String optional(String key, String type) {
            var value = values.remove(key);
            if (value == null) return null;
            if (!type.equals(value.type())) throw new IllegalArgumentException("Wrongly typed relationship provenance property: " + key);
            return value.value();
        }
        <T extends Enum<T>> T enumeration(String key, Class<T> type) {
            String value = optional(key, "string");
            return value == null ? null : Enum.valueOf(type, value);
        }
    }
}
