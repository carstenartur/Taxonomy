package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.util.*;
import static com.taxonomy.exchange.sparx.SparxMappingProfile.*;

/** One v2 semantic policy for both transport parsers. The records are bounded parsing values, not authoring models. */
final class SparxSemanticExchangeAssembler {
    record Resource(String id, ArtifactKind kind, String type, String title, String text, String parent,
                    Integer position, Map<String,String> attributes, Map<String,String> extensions) {}
    record Feature(String id, String owner, String type, String title, String text, Integer position,
                   Map<String,String> attributes, Map<String,String> extensions) {}
    record Connector(String id, String source, String target, String type, String direction,
                     Map<String,String> attributes, Map<String,String> extensions) {}
    private SparxSemanticExchangeAssembler() {}

    static ExchangeDocument assemble(String profile, String version, boolean complete, String source,
            Map<String,String> metadata, List<Resource> resources, List<Feature> features,
            List<Connector> connectors, List<MappingLoss> inputLosses) {
        Map<String,Artifact> objects = new TreeMap<>();
        List<MappingLoss> losses = new ArrayList<>(inputLosses);
        for (Resource r : resources) {
            Map<String,String> extensions = new TreeMap<>(r.extensions());
            if (r.kind() == ArtifactKind.ELEMENT) {
                String canonical = mappedType(r.type(), r.attributes(), extensions);
                if (canonical != null) extensions.put("canonicalType", canonical);
                else losses.add(loss(r.id(), "type", "SPARX_ELEMENT_UNMAPPED", LossDisposition.UNSUPPORTED));
            }
            put(objects, new Artifact(guid(r.id()), r.kind(), r.type(), r.title(), r.text(), r.attributes(), extensions));
        }
        Set<String> resourceIds = Set.copyOf(objects.keySet());
        for (Feature f : features) {
            Map<String,String> extensions = new TreeMap<>(f.extensions());
            extensions.put("owner", guid(f.owner()));
            if (f.position() != null) extensions.put("position", Integer.toString(f.position()));
            put(objects, new Artifact(guid(f.id()), ArtifactKind.FEATURE, f.type(), f.title(), f.text(), f.attributes(), extensions));
        }
        // Identity-bearing tags remain separate even when a legacy owner projection is possible.
        Map<String,Map<String,List<Artifact>>> tags = new TreeMap<>();
        for (Artifact a : objects.values()) if (a.kind() == ArtifactKind.FEATURE && a.type().equals("tagged-value")) {
            if (a.title().isBlank() || a.title().length() > 240 || a.text().length() > 100000)
                throw ExchangeXml.invalid("SPARX_TAG_INVALID", "Tagged values need bounded names and values");
            tags.computeIfAbsent(a.extensions().get("owner"), unused -> new TreeMap<>())
                    .computeIfAbsent(a.title(), unused -> new ArrayList<>()).add(a);
        }
        tags.forEach((owner, names) -> {
            Artifact a = objects.get(owner);
            if (a == null) throw ExchangeXml.invalid("SPARX_FEATURE_OWNER", "Feature owner is missing");
            Map<String,String> attributes = new TreeMap<>(a.attributes());
            names.forEach((name, values) -> {
                attributes.remove("tag:" + name);
                if (values.size() == 1) attributes.put("tag:" + name, values.getFirst().text());
                else losses.add(loss(owner, "tag:" + name, "SPARX_TAG_DUPLICATE_UNMAPPED", LossDisposition.PRESERVED_EXTENSION));
            });
            Map<String,String> extensions = new TreeMap<>(a.extensions());
            if (a.kind() == ArtifactKind.ELEMENT) {
                String canonical = mappedType(a.type(), attributes, extensions);
                if (canonical != null) extensions.put("canonicalType", canonical);
            }
            objects.put(owner, new Artifact(a.id(), a.kind(), a.type(), a.title(), a.text(), attributes, extensions));
        });
        Map<String,Relation> relations = new TreeMap<>();
        for (Connector c : connectors) {
            String id = guid(c.id()), from = guid(c.source()), to = guid(c.target());
            String direction = c.direction();
            if (!Set.of("Unspecified", "Source -> Destination", "Destination -> Source", "Bi-Directional").contains(direction))
                throw ExchangeXml.invalid("SPARX_DIRECTION", "Unsupported connector direction");
            Map<String,String> extensions = new TreeMap<>(c.extensions());
            extensions.put("direction", direction);
            String canonical = relationType(c.type());
            String declared = c.attributes().get("tag:taxonomy.relationType");
            if (declared != null) {
                if (eaRelation(declared) == null) throw ExchangeXml.invalid("SPARX_RELATION_UNMAPPED", "Unsupported declared relation type");
                canonical = declared;
            }
            if (canonical != null) extensions.put("canonicalType", canonical);
            else losses.add(loss(id, "type", "SPARX_RELATION_UNMAPPED", LossDisposition.UNSUPPORTED));
            if (direction.equals("Bi-Directional")) losses.add(loss(id, "direction", "SPARX_DIRECTION_UNMAPPED", LossDisposition.UNSUPPORTED));
            Relation relation = new Relation(id, c.type(), from, to, c.attributes(), extensions);
            Relation previous = relations.putIfAbsent(id, relation);
            if (previous != null && !previous.equals(relation)) throw duplicate();
        }
        for (Relation r : new ArrayList<>(relations.values())) {
            if (objects.containsKey(r.id())) throw duplicate();
            if (!resourceIds.contains(r.source()) || !resourceIds.contains(r.target())) {
                String owner = resourceIds.contains(r.source()) ? r.source() : r.target();
                Map<String,String> ext = new TreeMap<>(r.extensions());
                ext.put("owner", owner); ext.put("source", r.source()); ext.put("target", r.target());
                ext.put("eaType", r.type()); ext.put("preservedOnly", "true");
                put(objects, new Artifact(r.id(), ArtifactKind.FEATURE, "external-connector",
                        r.attributes().getOrDefault("name", ""), r.attributes().getOrDefault("description", ""), r.attributes(), ext));
                relations.remove(r.id());
                losses.add(loss(r.id(), "endpoint", "SPARX_AM_ENDPOINT_OUTSIDE_SCOPE", LossDisposition.PRESERVED_EXTENSION));
            }
        }
        String model = guid(metadata.get("identifier"));
        Set<String> ids = new HashSet<>(objects.keySet());
        if (!ids.add(model)) throw duplicate();
        for (Relation r : relations.values()) if (!ids.add(r.id())) throw duplicate();
        if (objects.size() + relations.size() > ExchangeXml.MAX_ARTIFACTS) throw ExchangeXml.invalid("ITEM_LIMIT", "Sparx scope exceeds the item bound");
        SparxModelValidator.features(objects);
        Set<String> taxonomyIds = new HashSet<>();
        for (Artifact a : objects.values()) SparxModelValidator.checkTags(a.attributes(), taxonomyIds, profile + "@2");
        for (Relation r : relations.values()) SparxModelValidator.checkTags(r.attributes(), taxonomyIds, profile + "@2");
        List<Placement> placements = new ArrayList<>();
        Map<String,Resource> roots = new TreeMap<>(); resources.forEach(r -> roots.put(guid(r.id()), r));
        Map<String,Integer> positions = new HashMap<>();
        for (Resource r : roots.values()) {
            String parent = r.parent() == null || r.parent().isEmpty() ? null : guid(r.parent());
            Set<String> seen = new HashSet<>(); String ancestor = parent;
            while (ancestor != null) {
                if (!seen.add(ancestor) || seen.size() >= 80 || ancestor.equals(guid(r.id())))
                    throw ExchangeXml.invalid("HIERARCHY_CYCLE", "Package hierarchy is cyclic or too deep");
                Resource p = roots.get(ancestor);
                if (p == null || p.kind() != ArtifactKind.SPECIFICATION)
                    throw ExchangeXml.invalid("SPARX_AM_PARENT_MISSING", "Parent package is outside the scope");
                ancestor = p.parent() == null || p.parent().isEmpty() ? null : guid(p.parent());
            }
            placements.add(new Placement("placement:" + guid(r.id()), model, parent == null ? null : "placement:" + parent,
                    guid(r.id()), r.position() == null ? positions.getOrDefault(parent, 0) : r.position(), Map.of()));
            positions.merge(parent, 1, Integer::sum);
        }
        losses.sort(Comparator.comparing((MappingLoss l) -> Objects.toString(l.artifactId(), "")).thenComparing(MappingLoss::field).thenComparing(MappingLoss::code));
        return new ExchangeDocument(profile, "2", version, complete, source, List.copyOf(objects.values()),
                List.copyOf(relations.values()), placements, metadata, losses);
    }
    private static String mappedType(String type, Map<String,String> attributes, Map<String,String> extensions) {
        if ("UNMAPPED".equals(extensions.get("stereotypeMapping")) && !attributes.containsKey("tag:taxonomy.elementType")) return null;
        return elementType(type, extensions.get("stereotype"), attributes.get("tag:taxonomy.elementType"));
    }
    static MappingLoss loss(String id, String field, String code, LossDisposition disposition) {
        return new MappingLoss(id, field, code, disposition, "Bounded Sparx feature requires explicit review; no implicit native authoring");
    }
    private static void put(Map<String,Artifact> values, Artifact value) { if (values.putIfAbsent(value.id(), value) != null) throw duplicate(); }
    private static RuntimeException duplicate() { return ExchangeXml.invalid("DUPLICATE_IDENTITY", "Conflicting or duplicate Sparx identity"); }
}
