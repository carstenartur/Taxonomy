package com.taxonomy.dsl.mapping.profiles;

import com.taxonomy.dsl.mapping.MappingProfile;

import java.util.Map;
import java.util.Set;

/**
 * Mapping profile for APQC Process Classification Framework (PCF).
 *
 * <p>APQC organizes processes in a hierarchy of levels (1 through 5).
 * Each level is mapped to one of the existing taxonomy root codes.
 *
 * <ul>
 *   <li>Level 1 (Category) → {@code CP} (Capability)</li>
 *   <li>Level 2 (Process Group) → {@code BP} (Business Process)</li>
 *   <li>Level 3 (Process) → {@code CR} (Core Service)</li>
 *   <li>Level 4 (Activity) → {@code CI} (COI Service)</li>
 *   <li>Level 5 (Task) → {@code BR} (Business Role)</li>
 * </ul>
 */
public class ApqcMappingProfile implements MappingProfile {

    private static final Map<String, String> ELEMENT_MAP = Map.of(
            "Category", "CP",
            "ProcessGroup", "BP",
            "Process", "CR",
            "Activity", "CI",
            "Task", "BR"
    );

    private static final Map<String, String> RELATION_MAP = Map.of(
            "ParentChild", "SUPPORTS",
            "Enables", "SUPPORTS",
            "Consumes", "CONSUMES",
            "Produces", "PRODUCES"
    );

    @Override
    public String profileId() {
        return "apqc";
    }

    @Override
    public String displayName() {
        return "APQC PCF";
    }

    @Override
    public String mapElementType(String externalType) {
        return ELEMENT_MAP.get(externalType);
    }

    @Override
    public String mapRelationType(String externalType) {
        return RELATION_MAP.get(externalType);
    }

    @Override
    public Map<String, String> elementExtensions(String externalType,
                                                  Map<String, String> externalProperties) {
        var extensions = new java.util.LinkedHashMap<String, String>();
        extensions.put("x-apqc-level", externalType);
        String parentId = externalProperties.get("parentId");
        if (parentId != null) {
            extensions.put("x-apqc-parent", parentId);
        }
        String pcfId = externalProperties.get("pcfId");
        if (pcfId != null) {
            extensions.put("x-apqc-pcf-id", pcfId);
        }
        return extensions;
    }

    @Override
    public Map<String, String> relationExtensions(String externalRelType,
                                                   Map<String, String> externalProperties) {
        return Map.of("x-apqc-rel", externalRelType);
    }

    @Override
    public Set<String> supportedElementTypes() {
        return ELEMENT_MAP.keySet();
    }

    @Override
    public Set<String> supportedRelationTypes() {
        return RELATION_MAP.keySet();
    }
}
