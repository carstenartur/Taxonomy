package com.taxonomy.dsl.mapping;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.TaxonomyRootTypes;

import java.util.*;

/**
 * Generic converter that transforms external framework data into a
 * {@link CanonicalArchitectureModel} using a {@link MappingProfile}.
 *
 * <p>For every element and relation the profile is consulted to translate the
 * external type to a canonical type and to enrich the result with
 * framework-specific extension properties (prefixed with {@code x-}).
 */
public class ExternalModelMapper {

    private final MappingProfile profile;

    public ExternalModelMapper(MappingProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        this.profile = profile;
    }

    /**
     * Map a list of external elements and relations to a canonical architecture model.
     *
     * @param elements  external elements to map
     * @param relations external relations to map
     * @return a {@link MappingResult} containing the model, warnings, and statistics
     */
    public MappingResult map(List<ExternalElement> elements, List<ExternalRelation> relations) {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        List<String> warnings = new ArrayList<>();
        Set<String> unmappedTypes = new LinkedHashSet<>();
        int mappedElements = 0;
        int mappedRelations = 0;

        // Map elements
        for (ExternalElement ext : elements) {
            String rootCode = profile.mapElementType(ext.type());
            if (rootCode == null) {
                unmappedTypes.add(ext.type());
                warnings.add("Unmapped element type: " + ext.type() + " (element " + ext.name() + ")");
                // Still create an element, but mark it as unmapped
                ArchitectureElement el = createUnmappedElement(ext);
                model.getElements().add(el);
                continue;
            }

            String dslType = TaxonomyRootTypes.typeFor(rootCode);

            ArchitectureElement el = new ArchitectureElement();
            el.setId(ext.id());
            el.setType(dslType);
            el.setTitle(ext.name());
            el.setDescription(ext.description());

            // Set extensions: source framework marker + profile-specific extensions
            Map<String, String> extensions = new LinkedHashMap<>();
            extensions.put("x-source-framework", profile.profileId());
            extensions.put("x-" + profile.profileId() + "-kind", ext.type());
            Map<String, String> profileExtensions = profile.elementExtensions(
                    ext.type(), ext.properties() != null ? ext.properties() : Map.of());
            extensions.putAll(profileExtensions);
            el.setExtensions(extensions);

            model.getElements().add(el);
            mappedElements++;
        }

        // Map relations
        for (ExternalRelation ext : relations) {
            String relType = profile.mapRelationType(ext.type());
            if (relType == null) {
                unmappedTypes.add(ext.type());
                warnings.add("Unmapped relation type: " + ext.type());
                // Still create the relation with RELATED_TO as fallback
                relType = "RELATED_TO";
            } else {
                mappedRelations++;
            }

            ArchitectureRelation rel = new ArchitectureRelation();
            rel.setSourceId(ext.sourceId());
            rel.setRelationType(relType);
            rel.setTargetId(ext.targetId());
            rel.setStatus("proposed");
            rel.setProvenance(profile.profileId() + "-import");

            // Set extensions
            Map<String, String> extensions = new LinkedHashMap<>();
            extensions.put("x-source-framework", profile.profileId());
            extensions.put("x-" + profile.profileId() + "-rel", ext.type());
            Map<String, String> profileExtensions = profile.relationExtensions(
                    ext.type(), ext.properties() != null ? ext.properties() : Map.of());
            extensions.putAll(profileExtensions);
            rel.setExtensions(extensions);

            model.getRelations().add(rel);
        }

        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("elements", elements.size());
        stats.put("relations", relations.size());
        stats.put("mappedElements", mappedElements);
        stats.put("mappedRelations", mappedRelations);
        stats.put("unmapped", unmappedTypes.size());

        return new MappingResult(model, warnings, new ArrayList<>(unmappedTypes), stats);
    }

    private ArchitectureElement createUnmappedElement(ExternalElement ext) {
        ArchitectureElement el = new ArchitectureElement();
        el.setId(ext.id());
        el.setType("Unknown");
        el.setTitle(ext.name());
        el.setDescription(ext.description());

        Map<String, String> extensions = new LinkedHashMap<>();
        extensions.put("x-source-framework", profile.profileId());
        extensions.put("x-unmapped-type", ext.type());
        el.setExtensions(extensions);

        return el;
    }
}
