package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreSemantics;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class AnalysisSseEventMapper {

    private static final long TREE_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final TaxonomyService taxonomyService;
    private final LongSupplier nanoTime;
    private final long treeCacheTtlNanos;
    private final Object treeCacheMonitor = new Object();
    private volatile CachedTaxonomyTree cachedTaxonomyTree;

    /** Retained for focused mapper tests that do not need catalogue semantics. */
    public AnalysisSseEventMapper() {
        this(null);
    }

    @Autowired
    public AnalysisSseEventMapper(TaxonomyService taxonomyService) {
        this(taxonomyService, System::nanoTime, TREE_CACHE_TTL_NANOS);
    }

    AnalysisSseEventMapper(
            TaxonomyService taxonomyService,
            LongSupplier nanoTime,
            long treeCacheTtlNanos) {
        this.taxonomyService = taxonomyService;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.treeCacheTtlNanos = Math.max(0L, treeCacheTtlNanos);
    }

    public MappedEvent map(AnalysisStreamEvent event) {
        if (event instanceof AnalysisStreamEvent.Phase phase) {
            return new MappedEvent("phase", Map.of(
                    "message", phase.message(),
                    "progress", phase.progressPercent()));
        }
        if (event instanceof AnalysisStreamEvent.Scores scores) {
            return new MappedEvent("scores", mapScores(scores));
        }
        if (event instanceof AnalysisStreamEvent.Expanding expanding) {
            return new MappedEvent("expanding", Map.of(
                    "parentCode", expanding.parentCode(),
                    "childCodes", expanding.childCodes()));
        }
        if (event instanceof AnalysisStreamEvent.Complete complete) {
            AnalysisScoreSemantics.Derived semantics = derive(complete.allScores());
            int totalMatched = (int) semantics.effectiveScores().values().stream()
                    .filter(value -> value > 0)
                    .count();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", complete.status());
            payload.put("totalScores", semantics.effectiveScores());
            payload.put("rawScores", complete.allScores());
            payload.put("effectiveScores", semantics.effectiveScores());
            payload.put("productSuitabilityScores", semantics.productSuitabilityScores());
            payload.put("scoreDetails", semantics.scoreDetails());
            payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);
            payload.put("scoreSemanticsWarnings", semantics.warnings());
            payload.put("totalMatched", totalMatched);
            payload.put("warnings", complete.warnings());
            payload.put("discrepancies", complete.discrepancies());
            payload.put("productCoverageGaps", complete.productCoverageGaps());
            return new MappedEvent("complete", payload);
        }
        if (event instanceof AnalysisStreamEvent.Error error) {
            AnalysisScoreSemantics.Derived semantics = derive(error.partialScores());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", error.status());
            payload.put("errorMessage", error.errorMessage());
            payload.put("partialScores", semantics.effectiveScores());
            payload.put("rawScores", error.partialScores());
            payload.put("effectiveScores", semantics.effectiveScores());
            payload.put("productSuitabilityScores", semantics.productSuitabilityScores());
            payload.put("scoreDetails", semantics.scoreDetails());
            payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);
            payload.put("scoreSemanticsWarnings", semantics.warnings());
            payload.put("warnings", error.warnings());
            payload.put("discrepancies", error.discrepancies());
            payload.put("productCoverageGaps", error.productCoverageGaps());
            return new MappedEvent("error", payload);
        }
        throw new IllegalArgumentException("Unsupported analysis stream event: " + event);
    }

    private Map<String, Object> mapScores(AnalysisStreamEvent.Scores scores) {
        AnalysisScoreSemantics.Derived semantics = derive(scores.newScores());
        Map<String, Object> payload = new LinkedHashMap<>();
        // Incremental batches deliberately remain raw. A product batch usually does not contain
        // its already emitted family score, so neither an effective map nor a batch-local
        // effective value is authoritative. scoreDetails therefore carries only raw kind/parent
        // hints; the browser combines them with accumulated raw family evidence. Terminal events
        // contain the complete authoritative envelope.
        payload.put("scores", scores.newScores());
        payload.put("rawScores", scores.newScores());
        payload.put("scoreDetails", incrementalScoreDetails(semantics.scoreDetails()));
        payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);
        payload.put("scoreSemanticsWarnings", semantics.warnings());
        payload.put("reasons", scores.reasons() != null ? scores.reasons() : Map.of());
        payload.put("description", scores.description());
        payload.put("message", scores.description());
        LlmCallDetail detail = scores.detail();
        if (detail != null) {
            payload.put("prompt", detail.getPrompt() != null ? detail.getPrompt() : "");
            payload.put("rawResponse", detail.getRawResponse() != null ? detail.getRawResponse() : "");
            payload.put("provider", detail.getProvider() != null ? detail.getProvider() : "");
            payload.put("durationMs", detail.getDurationMs());
            if (detail.getError() != null) {
                payload.put("error", detail.getError());
            }
        }
        return payload;
    }

    private Map<String, Map<String, Object>> incrementalScoreDetails(
            Map<String, AnalysisScoreDetail> details) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        details.forEach((code, detail) -> {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("nodeCode", detail.nodeCode());
            hint.put("kind", detail.kind());
            hint.put("rawScore", detail.rawScore());
            if (detail.parentCode() != null) {
                hint.put("parentCode", detail.parentCode());
            }
            result.put(code, Collections.unmodifiableMap(hint));
        });
        return Collections.unmodifiableMap(result);
    }

    private AnalysisScoreSemantics.Derived derive(Map<String, Integer> rawScores) {
        return AnalysisScoreSemantics.derive(rawScores, taxonomyTree());
    }

    private List<TaxonomyNodeDto> taxonomyTree() {
        if (taxonomyService == null) {
            return List.of();
        }
        long now = nanoTime.getAsLong();
        CachedTaxonomyTree current = cachedTaxonomyTree;
        if (current != null && current.isValidAt(now)) {
            return current.tree();
        }
        synchronized (treeCacheMonitor) {
            now = nanoTime.getAsLong();
            current = cachedTaxonomyTree;
            if (current != null && current.isValidAt(now)) {
                return current.tree();
            }
            List<TaxonomyNodeDto> loaded = taxonomyService.getFingerprintTree();
            List<TaxonomyNodeDto> snapshot = loaded == null ? List.of() : List.copyOf(loaded);
            cachedTaxonomyTree = new CachedTaxonomyTree(
                    snapshot, now, treeCacheTtlNanos);
            return snapshot;
        }
    }

    private record CachedTaxonomyTree(
            List<TaxonomyNodeDto> tree,
            long loadedAtNanos,
            long timeToLiveNanos) {

        private boolean isValidAt(long nowNanos) {
            // nanoTime values may wrap; an elapsed-duration comparison remains correct for this
            // bounded TTL as long as less than half the long range elapses between observations.
            long elapsedNanos = nowNanos - loadedAtNanos;
            return elapsedNanos >= 0 && elapsedNanos < timeToLiveNanos;
        }
    }

    public record MappedEvent(String name, Object payload) {
    }
}
