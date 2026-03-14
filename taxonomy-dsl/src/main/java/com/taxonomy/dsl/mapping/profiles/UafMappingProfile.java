package com.taxonomy.dsl.mapping.profiles;

import com.taxonomy.dsl.mapping.MappingProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mapping profile for the Unified Architecture Framework (UAF) / DoDAF.
 *
 * <p>Translates UAF element types (from UPDM/UAF XMI profiles) to
 * taxonomy root codes and UAF relation types to canonical relation type names.
 */
public class UafMappingProfile implements MappingProfile {

    /**
     * UAF element type → taxonomy root code.
     */
    static final Map<String, String> ELEMENT_TYPE_MAP = Map.ofEntries(
            Map.entry("Capability", "CP"),
            Map.entry("OperationalActivity", "BP"),
            Map.entry("ServiceFunction", "CR"),
            Map.entry("CapabilityConfiguration", "CI"),
            Map.entry("CommunicationsFunction", "CO"),
            Map.entry("System", "UA"),
            Map.entry("Platform", "UA"),
            Map.entry("Performer", "BR"),
            Map.entry("Organization", "BR"),
            Map.entry("ResourcePerformer", "BR"),
            Map.entry("InformationElement", "IP")
    );

    /**
     * UAF relation type → canonical RelationType name.
     */
    static final Map<String, String> RELATION_TYPE_MAP = Map.ofEntries(
            Map.entry("Implements", "REALIZES"),
            Map.entry("Supports", "SUPPORTS"),
            Map.entry("Consumes", "CONSUMES"),
            Map.entry("Uses", "USES"),
            Map.entry("Provides", "FULFILLS"),
            Map.entry("IsAssignedTo", "ASSIGNED_TO"),
            Map.entry("DependsOn", "DEPENDS_ON"),
            Map.entry("Produces", "PRODUCES"),
            Map.entry("CommunicatesWith", "COMMUNICATES_WITH")
    );

    @Override
    public String profileId() {
        return "uaf";
    }

    @Override
    public String displayName() {
        return "UAF / DoDAF";
    }

    @Override
    public String mapElementType(String externalType) {
        if (externalType == null) return null;
        return ELEMENT_TYPE_MAP.get(externalType);
    }

    @Override
    public String mapRelationType(String externalRelType) {
        if (externalRelType == null) return null;
        return RELATION_TYPE_MAP.get(externalRelType);
    }

    @Override
    public Map<String, String> elementExtensions(String externalType, Map<String, String> externalProperties) {
        Map<String, String> ext = new LinkedHashMap<>();
        if (externalType != null) {
            ext.put("x-uaf-kind", externalType);
        }
        return ext;
    }

    @Override
    public Map<String, String> relationExtensions(String externalRelType, Map<String, String> externalProperties) {
        Map<String, String> ext = new LinkedHashMap<>();
        if (externalRelType != null) {
            ext.put("x-uaf-rel", externalRelType);
        }
        return ext;
    }

    @Override
    public Set<String> supportedElementTypes() {
        return ELEMENT_TYPE_MAP.keySet();
    }

    @Override
    public Set<String> supportedRelationTypes() {
        return RELATION_TYPE_MAP.keySet();
    }
}
