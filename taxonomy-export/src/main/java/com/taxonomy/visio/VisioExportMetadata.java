package com.taxonomy.visio;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Authorized, allowlisted authority and human decision values supplied by the application. */
public record VisioExportMetadata(Map<String, VisioProperty> document,
                                  Map<String, Map<String, VisioProperty>> elements,
                                  Map<String, Map<String, VisioProperty>> relationships,
                                  List<VisioLoss> losses) {
    public VisioExportMetadata {
        document = Map.copyOf(document);
        elements = freeze(elements);
        relationships = freeze(relationships);
        losses = List.copyOf(losses);
    }

    public static VisioExportMetadata unbound() {
        return new VisioExportMetadata(Map.of(), Map.of(), Map.of(), List.of(
                new VisioLoss("document", "", "snapshotAuthority", "OMITTED",
                        "This diagram was not supplied by the authorized immutable snapshot endpoint.")));
    }

    private static Map<String, Map<String, VisioProperty>> freeze(Map<String, Map<String, VisioProperty>> values) {
        return values.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
    }
}
