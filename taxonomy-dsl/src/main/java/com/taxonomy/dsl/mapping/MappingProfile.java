package com.taxonomy.dsl.mapping;

import java.util.Map;
import java.util.Set;

/**
 * A mapping profile translates external architecture framework types (elements and
 * relations) to the canonical taxonomy model used by TaxDSL.
 *
 * <p>Each profile represents one external framework (e.g. ArchiMate, UAF, C4, APQC).
 * The {@link ExternalModelMapper} uses a profile to convert external data into
 * {@link com.taxonomy.dsl.model.CanonicalArchitectureModel} instances.
 */
public interface MappingProfile {

    /** Unique profile identifier, e.g. {@code "archimate"}, {@code "uaf"}. */
    String profileId();

    /** Human-readable display name, e.g. {@code "ArchiMate 3.x"}. */
    String displayName();

    /**
     * Map an external element type to a canonical taxonomy root code
     * (one of the codes defined in {@link com.taxonomy.dsl.model.TaxonomyRootTypes}).
     *
     * @param externalType the element type in the external framework
     * @return the taxonomy root code (e.g. {@code "CP"}, {@code "BP"}), or {@code null} if unmapped
     */
    String mapElementType(String externalType);

    /**
     * Map an external relation type to a canonical {@code RelationType} name.
     *
     * @param externalRelType the relation type in the external framework
     * @return the canonical relation type name (e.g. {@code "REALIZES"}), or {@code null} if unmapped
     */
    String mapRelationType(String externalRelType);

    /**
     * Compute additional extension properties for a mapped element.
     *
     * @param externalType       the original external element type
     * @param externalProperties the original external properties
     * @return extension entries to add to the mapped element (never {@code null})
     */
    Map<String, String> elementExtensions(String externalType, Map<String, String> externalProperties);

    /**
     * Compute additional extension properties for a mapped relation.
     *
     * @param externalRelType    the original external relation type
     * @param externalProperties the original external properties
     * @return extension entries to add to the mapped relation (never {@code null})
     */
    Map<String, String> relationExtensions(String externalRelType, Map<String, String> externalProperties);

    /** The set of external element types this profile can map. */
    Set<String> supportedElementTypes();

    /** The set of external relation types this profile can map. */
    Set<String> supportedRelationTypes();
}
