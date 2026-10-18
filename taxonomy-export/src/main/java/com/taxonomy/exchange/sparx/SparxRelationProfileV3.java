package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.extension.api.integration.IntegrationContracts.Relation;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit Taxonomy semantics carried by XMI profile 3. The Association entries are transport
 * choices, not assertions that the Taxonomy concepts are native UML Association semantics.
 * In particular, Taxonomy REALIZES is Capability -> Service ("is made real by"),
 * the inverse of UML Realization's implementation/client -> specification/supplier meaning.
 * Versions 1 and 2 deliberately continue using the six mappings in SparxMappingProfile.
 */
public final class SparxRelationProfileV3 {
    public static final String VERSION = "3";
    public static final String TYPE_TAG = "tag:taxonomy.relationType";
    public static final String DIRECTION_TAG = "tag:taxonomy.relationDirection";
    public static final Map<String, String> TRANSPORTS = Map.ofEntries(
            Map.entry("DEPENDS_ON", "Dependency"), Map.entry("REALIZES", "Association"),
            Map.entry("COMMUNICATES_WITH", "InformationFlow"), Map.entry("RELATED_TO", "Association"),
            Map.entry("CONSUMES", "Usage"), Map.entry("CONTAINS", "Composition"),
            Map.entry("SUPPORTS", "Association"), Map.entry("USES", "Association"),
            Map.entry("FULFILLS", "Association"), Map.entry("ASSIGNED_TO", "Association"),
            Map.entry("PRODUCES", "Association"), Map.entry("REQUIRES", "Association"));
    public static final Set<String> ASSOCIATION_TRANSPORTS = Set.of(
            "REALIZES", "SUPPORTS", "USES", "FULFILLS", "ASSIGNED_TO", "PRODUCES", "REQUIRES");

    private SparxRelationProfileV3() { }

    public static String transport(String canonical) {
        String transport = canonical == null ? null : TRANSPORTS.get(canonical);
        if (transport == null) throw ExchangeXml.invalid("SPARX_RELATION_UNMAPPED", "Profile 3 requires an exact supported Taxonomy relation type");
        return transport;
    }

    public static String stereotype(String canonical) { transport(canonical); return "Taxonomy::" + canonical; }

    /** The same assertions apply before writing and after reading every redundant declaration. */
    public static void validate(Relation relation) {
        String canonical = relation.attributes().get(TYPE_TAG);
        String transport = transport(canonical);
        require(transport, relation.type(), "The declared Taxonomy relation type contradicts its bounded XMI transport");
        require(canonical, relation.extensions().get("canonicalType"), "The canonical relation type contradicts its Taxonomy tag");
        require(SparxMappingProfile.PROFILE + "@3", relation.attributes().get("tag:taxonomy.mappingProfile"),
                "Profile 3 relations need their explicit mapping-profile tag");
        require(stereotype(canonical), relation.extensions().get("stereotype"),
                "Profile 3 relations need their exact visible Taxonomy stereotype");
        String direction = relation.attributes().get(DIRECTION_TAG);
        if (!Set.of("Source -> Destination", "Destination -> Source").contains(Objects.toString(direction, ""))
                && !("RELATED_TO".equals(canonical) && "Unspecified".equals(direction)))
            throw ExchangeXml.invalid("SPARX_DIRECTION", "Profile 3 needs one explicit direction; only RELATED_TO permits Unspecified");
        require(direction, relation.extensions().get("direction"), "The connector direction contradicts its Taxonomy tag");
        projectionTags(relation.attributes(), relation.extensions());
    }

    static void projectionTags(Map<String, String> attributes, Map<String, String> extensions) {
        extensions.forEach((key, value) -> {
            if (key.startsWith("taxonomy:")) {
                String declared = attributes.get("tag:taxonomy." + key.substring(9));
                if (declared != null) require(declared, value, "A Taxonomy tag contradicts its preserved extension");
            }
        });
    }

    static void require(String expected, String actual, String message) {
        if (expected == null || !expected.equals(actual)) throw ExchangeXml.invalid("SPARX_SEMANTIC_CONFLICT", message);
    }
}
