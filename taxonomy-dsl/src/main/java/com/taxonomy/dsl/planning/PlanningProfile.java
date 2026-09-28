package com.taxonomy.dsl.planning;

import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import java.util.List;
import java.util.Map;

/** Pure domain extension. Neither a vendor codec nor a parallel persistence model. */
public interface PlanningProfile {
    record Field(String key, boolean required, List<String> choices) {
        public Field { PlanningEntry.token(key, "field"); choices = List.copyOf(choices); }
    }
    record Descriptor(String id, String version, List<Field> fields) {
        public Descriptor { PlanningEntry.token(id, "profile"); PlanningEntry.token(version, "version"); fields = List.copyOf(fields); }
    }
    record Stored(String dsl, Map<String, String> values) {
        public Stored { values = Map.copyOf(values); }
    }
    Descriptor descriptor();
    void validate(Map<String, String> values);
    default Stored store(String dsl, String requirement, String entry, Map<String, String> values) {
        validate(values); return new Stored(dsl, values);
    }
    default String remove(String dsl, String requirement, String entry, Map<String, String> stored) { return dsl; }
    default Map<String, String> read(CanonicalArchitectureModel model, String requirement, Map<String, String> stored) {
        validate(stored); return stored;
    }
}
