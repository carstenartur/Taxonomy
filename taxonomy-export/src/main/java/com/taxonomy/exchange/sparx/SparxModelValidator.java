package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.util.*;
import static com.taxonomy.exchange.sparx.SparxMappingProfile.*;

/** Shared semantic validation for reviewed Sparx selections, independent of transport/profile. */
public final class SparxModelValidator {
    private SparxModelValidator() {}
    public static void validate(ExchangeDocument source) { model(source); }
    record Validated(Map<String, Artifact> objects, String modelId, Map<String, Placement> byId,
                     Map<String, Placement> byObject) {}
    static Validated model(ExchangeDocument source) {
        if (source.artifacts().size() + source.relations().size() > ExchangeXml.MAX_ARTIFACTS)
            throw ExchangeXml.invalid("ITEM_LIMIT", "Sparx scope exceeds the supported item limit");
        Map<String, Artifact> objects = new TreeMap<>(); Set<String> taxonomyIds = new HashSet<>();
        for (Artifact artifact : source.artifacts()) {
            if (objects.putIfAbsent(guid(artifact.id()), artifact) != null) throw duplicate();
            if (!Set.of(ArtifactKind.SPECIFICATION, ArtifactKind.ELEMENT, ArtifactKind.REQUIREMENT).contains(artifact.kind()))
                throw ExchangeXml.invalid("SPARX_KIND_UNMAPPED", "Artifact kind is outside the semantic XMI subset");
            if (artifact.kind() == ArtifactKind.ELEMENT && (elementType(artifact.type(), null, null) == null
                    || !CANONICAL_TYPES.contains(artifact.extensions().getOrDefault("canonicalType", ""))
                    || !artifact.extensions().get("canonicalType").equals(elementType(artifact.type(),
                            artifact.extensions().get("stereotype"), artifact.attributes().get("tag:taxonomy.elementType")))))
                throw ExchangeXml.invalid("SPARX_ELEMENT_UNMAPPED", "Reject or explicitly remap unsupported element types before export");
            checkTags(artifact.attributes(), taxonomyIds, source.profile() + "@" + source.profileVersion());
        }
        String modelId = guid(source.metadata().get("identifier"));
        if (objects.containsKey(modelId)) throw duplicate();
        Set<String> allIds = new HashSet<>(objects.keySet()); allIds.add(modelId);
        for (Relation relation : source.relations()) {
            if (!allIds.add(guid(relation.id()))) throw duplicate();
            if (!objects.containsKey(relation.source()) || !objects.containsKey(relation.target()))
                throw ExchangeXml.invalid("SPARX_ENDPOINT_REQUIRED", "A reviewed connector endpoint is missing");
            if (relationType(relation.type()) == null
                    || !relationType(relation.type()).equals(relation.extensions().get("canonicalType")))
                throw ExchangeXml.invalid("SPARX_RELATION_UNMAPPED", "Reject or remap unsupported connectors before export");
            checkTags(relation.attributes(), taxonomyIds, source.profile() + "@" + source.profileVersion());
        }
        Map<String, Placement> byId = new HashMap<>(), byObject = new HashMap<>();
        for (Placement p : source.placements()) {
            if (byId.putIfAbsent(p.id(), p) != null || byObject.putIfAbsent(p.artifactId(), p) != null) throw duplicate();
            if (!modelId.equals(p.containerId()) || !objects.containsKey(p.artifactId()))
                throw ExchangeXml.invalid("HIERARCHY_REFERENCE", "A reviewed occurrence has a missing model or artifact");
        }
        for (Placement p : source.placements()) {
            Set<String> visited = new HashSet<>(); Placement next = p;
            while (next.parentId() != null) {
                if (!visited.add(next.id()) || visited.size() > 80)
                    throw ExchangeXml.invalid("HIERARCHY_CYCLE", "Package hierarchy is cyclic or too deep");
                next = byId.get(next.parentId());
                if (next == null || objects.get(next.artifactId()).kind() != ArtifactKind.SPECIFICATION)
                    throw ExchangeXml.invalid("HIERARCHY_REFERENCE", "A reviewed parent package is missing");
            }
        }
        return new Validated(objects, modelId, byId, byObject);
    }
    private static void checkTags(Map<String, String> attributes, Set<String> identities, String selectedProfile) {
        String id = attributes.get("tag:taxonomy.id");
        if (id != null && !identities.add(guid(id))) throw duplicate();
        String profile = attributes.get("tag:taxonomy.mappingProfile");
        if (profile != null && !selectedProfile.equals(profile))
            throw ExchangeXml.invalid("PROFILE_VERSION_CHANGED", "Tagged profile differs from the selected mapping profile");
    }
    private static RuntimeException duplicate() { return ExchangeXml.invalid("DUPLICATE_IDENTITY", "Duplicated EA GUID or Taxonomy identity in reviewed output"); }
}
