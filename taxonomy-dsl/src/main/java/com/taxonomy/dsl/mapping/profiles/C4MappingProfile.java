package com.taxonomy.dsl.mapping.profiles;

import com.taxonomy.dsl.mapping.MappingProfile;

import java.util.Map;
import java.util.Set;

/**
 * Mapping profile for C4 / Structurizr architecture models.
 *
 * <p>Maps the four C4 abstraction levels plus the Person concept:
 * <ul>
 *   <li>Person → {@code BR} (Business Role)</li>
 *   <li>SoftwareSystem → {@code SY} (System)</li>
 *   <li>Container → {@code UA} (User Application)</li>
 *   <li>Component → {@code CM} (Component)</li>
 *   <li>DeploymentNode → {@code CO} (Communications Service)</li>
 *   <li>InfrastructureNode → {@code CO} (Communications Service)</li>
 *   <li>ContainerInstance → {@code UA} (User Application)</li>
 * </ul>
 */
public class C4MappingProfile implements MappingProfile {

    private static final Map<String, String> ELEMENT_MAP = Map.of(
            "Person", "BR",
            "SoftwareSystem", "SY",
            "Container", "UA",
            "Component", "CM",
            "DeploymentNode", "CO",
            "InfrastructureNode", "CO",
            "ContainerInstance", "UA"
    );

    private static final Map<String, String> RELATION_MAP = Map.of(
            "Uses", "USES",
            "Delivers", "PRODUCES",
            "InteractsWith", "COMMUNICATES_WITH",
            "DependsOn", "DEPENDS_ON",
            "Contains", "CONTAINS",
            "Realizes", "REALIZES",
            "Supports", "SUPPORTS"
    );

    @Override
    public String profileId() {
        return "c4";
    }

    @Override
    public String displayName() {
        return "C4 / Structurizr";
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
        extensions.put("x-c4-kind", externalType);
        String technology = externalProperties.get("technology");
        if (technology != null) {
            extensions.put("x-c4-technology", technology);
        }
        return extensions;
    }

    @Override
    public Map<String, String> relationExtensions(String externalRelType,
                                                   Map<String, String> externalProperties) {
        var extensions = new java.util.LinkedHashMap<String, String>();
        extensions.put("x-c4-rel", externalRelType);
        String technology = externalProperties.get("technology");
        if (technology != null) {
            extensions.put("x-c4-technology", technology);
        }
        return extensions;
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
