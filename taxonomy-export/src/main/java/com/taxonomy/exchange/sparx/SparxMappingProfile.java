package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeXml;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable v1 mapping. No transport or proprietary SDK type enters the canonical model. */
public final class SparxMappingProfile {
    public static final String PROFILE = "sparx-xmi-2.1";
    public static final String VERSION = "1";
    public static final String XMI = "http://schema.omg.org/spec/XMI/2.1";
    public static final String UML = "http://schema.omg.org/spec/UML/2.1";
    public static final Set<String> CANONICAL_TYPES = Set.of("Capability", "Process", "CoreService", "COIService",
            "CommunicationsService", "UserApplication", "InformationProduct", "BusinessRole", "System", "Component");
    private static final Map<String, String> ELEMENTS = Map.of("Class", "Component", "Component", "Component",
            "Actor", "BusinessRole", "Activity", "Process", "UseCase", "Capability", "Interface", "CoreService",
            "Node", "System", "Artifact", "InformationProduct");
    private static final Map<String, String> RELATIONS = Map.of("Dependency", "DEPENDS_ON", "Realization", "REALIZES",
            "InformationFlow", "COMMUNICATES_WITH", "Association", "RELATED_TO", "Usage", "CONSUMES",
            "Composition", "CONTAINS");

    static final Set<String> FEATURE_SCALARS = Set.of("scope", "classifierName", "defaultValue", "lowerBound", "upperBound",
            "paramDirection", "alias", "containment", "isStatic", "isCollection", "isOrdered", "isConst", "allowDuplicates",
            "concurrency", "isAbstract", "isReturnArray", "isQuery", "isSynchronized", "isPure", "behavior", "status",
            "complexity", "phase", "version", "language");

    private SparxMappingProfile() {}

    public static String guid(String value) {
        if (value == null) throw ExchangeXml.invalid("SPARX_GUID_REQUIRED", "A stable EA GUID is required");
        String uuid = value;
        if (uuid.startsWith("EAID_") || uuid.startsWith("EAPK_")) uuid = uuid.substring(5).replace('_', '-');
        if (uuid.startsWith("{") && uuid.endsWith("}")) uuid = uuid.substring(1, uuid.length() - 1);
        if (!uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw ExchangeXml.invalid("SPARX_GUID_REQUIRED", "The object identity is not a supported EA GUID");
        return "{" + UUID.fromString(uuid).toString().toUpperCase(Locale.ROOT) + "}";
    }

    /** Strip exactly one allowed PCS kind prefix; generic GUID parsing intentionally stays unchanged. */
    public static String prefixedGuid(String value, Set<String> allowedPrefixes) {
        if (value != null) for (String prefix : allowedPrefixes) {
            if (value.startsWith(prefix) && value.substring(prefix.length()).matches("\\{[0-9a-fA-F-]{36}}"))
                return guid(value.substring(prefix.length()));
        }
        throw ExchangeXml.invalid("SPARX_GUID_REQUIRED", "Identity prefix does not match the expected Sparx feature kind");
    }

    public static String version(String version) {
        if (!Set.of("1", "2").contains(version))
            throw ExchangeXml.invalid("PROFILE_VERSION_CHANGED", "Select a supported immutable Sparx profile version");
        return version;
    }

    public static String xmiId(String guid, boolean packageId) {
        String value = guid(guid);
        return (packageId ? "EAPK_" : "EAID_") + value.substring(1, value.length() - 1).replace('-', '_');
    }

    public static String externalId(UUID connection, String identity) {
        return guid(UUID.nameUUIDFromBytes((connection + "\u0000" + identity).getBytes(StandardCharsets.UTF_8)).toString());
    }

    public static String elementType(String umlType, String stereotype, String declared) {
        if (declared != null) {
            if (!CANONICAL_TYPES.contains(declared)) throw ExchangeXml.invalid("SPARX_TYPE_MAPPING", "Unsupported declared Taxonomy type");
            return declared;
        }
        if (!ELEMENTS.containsKey(umlType)) return null;
        if (stereotype != null) for (String type : CANONICAL_TYPES)
            if (type.equalsIgnoreCase(stereotype)) return type;
        return ELEMENTS.get(umlType);
    }

    public static String umlType(String canonical) {
        if (!CANONICAL_TYPES.contains(canonical)) throw ExchangeXml.invalid("SPARX_TYPE_MAPPING", "No explicit Sparx element mapping");
        return switch (canonical) {
            case "BusinessRole" -> "Actor";
            case "Process" -> "Activity";
            case "Capability" -> "UseCase";
            case "InformationProduct" -> "Artifact";
            case "CoreService", "COIService", "CommunicationsService" -> "Interface";
            default -> "Component";
        };
    }

    public static String relationType(String eaType) { return RELATIONS.get(eaType); }
    public static boolean isRelation(String type) { return RELATIONS.containsKey(type) || Set.of("Abstraction", "Generalization").contains(type); }
    public static String eaRelation(String canonical) {
        return switch (canonical) {
            case "DEPENDS_ON" -> "Dependency"; case "REALIZES" -> "Realization";
            case "COMMUNICATES_WITH" -> "InformationFlow"; case "RELATED_TO" -> "Association";
            case "CONSUMES" -> "Usage"; case "CONTAINS" -> "Composition";
            default -> null;
        };
    }
}
