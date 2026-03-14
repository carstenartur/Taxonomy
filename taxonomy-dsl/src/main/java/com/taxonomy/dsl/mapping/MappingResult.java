package com.taxonomy.dsl.mapping;

import com.taxonomy.dsl.model.CanonicalArchitectureModel;

import java.util.List;
import java.util.Map;

/**
 * Result of mapping external framework data into a canonical architecture model.
 *
 * @param model             the mapped canonical model
 * @param warnings          human-readable warnings generated during mapping
 * @param unmappedTypes     external types that had no mapping in the profile
 * @param mappingStatistics summary counts (e.g. {@code "elements"}, {@code "relations"}, {@code "unmapped"})
 */
public record MappingResult(
        CanonicalArchitectureModel model,
        List<String> warnings,
        List<String> unmappedTypes,
        Map<String, Integer> mappingStatistics
) {}
