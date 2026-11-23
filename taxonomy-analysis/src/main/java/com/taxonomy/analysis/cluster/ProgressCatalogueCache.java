package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reuse immutable progress evidence only when it exactly matches the JSON read from the database. */
final class ProgressCatalogueCache {
    private static final int MAX_ROOTS = 32;
    private static final long MAX_ESTIMATED_BYTES = 12L * 1024 * 1024;

    private record Entry(ClusterAnalysisProgress.Catalogue catalogue, long estimatedBytes) { }

    private final ObjectMapper mapper;
    private final Map<String, Entry> byJson = new LinkedHashMap<>(16, 0.75f, true);
    private long estimatedBytes;

    ProgressCatalogueCache(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ClusterAnalysisProgress.Catalogue read(String readJson, AnalysisOperationContext context, String root, boolean restored) {
        Entry entry = cached(readJson);
        if (entry != null) {
            var catalogue = entry.catalogue();
            catalogue.requireAuthority(context, root, restored);
            return catalogue;
        }
        var catalogue = ClusterAnalysisProgress.Catalogue.read(readJson, mapper, context, root, restored);
        if (catalogue != null) remember(readJson, catalogue);
        return catalogue;
    }

    private synchronized Entry cached(String readJson) {
        return byJson.get(readJson);
    }

    private synchronized void remember(String readJson, ClusterAnalysisProgress.Catalogue catalogue) {
        // Bound retained JSON and the compact node index. Full catalogue descriptions are discarded.
        long size = 2L * readJson.length() + 256L * catalogue.size();
        if (size > MAX_ESTIMATED_BYTES || byJson.containsKey(readJson)) return;
        byJson.put(readJson, new Entry(catalogue, size));
        estimatedBytes += size;
        var leastRecent = byJson.values().iterator();
        while (estimatedBytes > MAX_ESTIMATED_BYTES || byJson.size() > MAX_ROOTS) {
            estimatedBytes -= leastRecent.next().estimatedBytes();
            leastRecent.remove();
        }
    }
}
