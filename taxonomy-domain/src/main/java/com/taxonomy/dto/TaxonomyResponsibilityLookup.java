package com.taxonomy.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Authorized effective organizational assignments, keyed by taxonomy element code. */
public interface TaxonomyResponsibilityLookup {
    record Assignment(String unitId, String name, String sourceId) {
        private static final List<String> COLORS = List.of(
                "#0072B2", "#D55E00", "#00875A", "#8E579A", "#896A00", "#007E87", "#94534B", "#5264A2");

        public Assignment {
            Objects.requireNonNull(unitId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(sourceId);
        }

        /** Stable across languages, renderers and organization renames. */
        public String color() {
            return COLORS.get(Math.floorMod(unitId.hashCode(), COLORS.size()));
        }
    }

    Map<String, Assignment> resolve(AnalysisModelBinding binding);

    Map<String, Assignment> current();
}
