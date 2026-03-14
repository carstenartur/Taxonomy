package com.taxonomy.dsl.mapping.profiles;

import com.taxonomy.dsl.mapping.MappingProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mapping profile for ArchiMate 3.x element and relationship types.
 *
 * <p>Translates ArchiMate element types to taxonomy root codes and
 * ArchiMate relationship types to canonical relation type names.
 * This profile extracts the mappings that were previously hard-coded
 * in the ArchiMate XML importer.
 */
public class ArchiMateMappingProfile implements MappingProfile {

    /**
     * ArchiMate element type → taxonomy root code.
     */
    static final Map<String, String> ELEMENT_TYPE_MAP = Map.ofEntries(
            Map.entry("Capability", "CP"),
            Map.entry("BusinessProcess", "BP"),
            Map.entry("BusinessRole", "BR"),
            Map.entry("ApplicationService", "CR"),
            Map.entry("BusinessService", "CI"),
            Map.entry("CommunicationNetwork", "CO"),
            Map.entry("TechnologyService", "CR"),
            Map.entry("ApplicationComponent", "UA"),
            Map.entry("DataObject", "IP"),
            Map.entry("BusinessObject", "IP")
    );

    /**
     * ArchiMate relationship type → canonical RelationType name.
     */
    static final Map<String, String> RELATION_TYPE_MAP = Map.ofEntries(
            Map.entry("Realization", "REALIZES"),
            Map.entry("Serving", "SUPPORTS"),
            Map.entry("Access", "CONSUMES"),
            Map.entry("Assignment", "ASSIGNED_TO"),
            Map.entry("Flow", "COMMUNICATES_WITH"),
            Map.entry("Composition", "RELATED_TO"),
            Map.entry("Aggregation", "RELATED_TO"),
            Map.entry("Association", "RELATED_TO"),
            Map.entry("Triggering", "SUPPORTS"),
            Map.entry("Influence", "RELATED_TO"),
            Map.entry("Specialization", "RELATED_TO")
    );

    @Override
    public String profileId() {
        return "archimate";
    }

    @Override
    public String displayName() {
        return "ArchiMate 3.x";
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
            ext.put("x-archimate-kind", externalType);
        }
        return ext;
    }

    @Override
    public Map<String, String> relationExtensions(String externalRelType, Map<String, String> externalProperties) {
        Map<String, String> ext = new LinkedHashMap<>();
        if (externalRelType != null) {
            ext.put("x-archimate-rel", externalRelType);
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
