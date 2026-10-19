package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Deployment defaults plus committed runtime overrides, captured once per admitted execution. */
@Service
public class RelationSearchBudgetSettings {
    @Autowired(required = false)
    private AnalysisRuntimeSettings runtimeSettings;

    @Value("${taxonomy.analysis.relations.hierarchical.max-calls:256}")
    private int defaultMaxCalls = 256;
    @Value("${taxonomy.analysis.relations.hierarchical.max-total-calls:4096}")
    private int defaultMaxTotalCalls = 4096;
    @Value("${taxonomy.analysis.relations.hierarchical.auto-continue:true}")
    private boolean defaultAutoContinue = true;
    @Value("${taxonomy.analysis.relations.hierarchical.max-sources:128}")
    private int defaultMaxSources = 128;
    @Value("${taxonomy.analysis.relations.hierarchical.max-work-items:4096}")
    private int defaultMaxWorkItems = 4096;

    @PostConstruct
    void validate() { configuredDefaults(); }

    public Snapshot snapshot() {
        Snapshot defaults = configuredDefaults();
        if (runtimeSettings == null) return defaults;
        AnalysisRuntimeSettings captured = runtimeSettings.snapshot();
        return new Snapshot(captured.getInt("analysis.relations.max-calls", defaults.maxCalls()),
                captured.getInt("analysis.relations.max-total-calls", defaults.maxTotalCalls()),
                captured.getBoolean("analysis.relations.auto-continue", defaults.autoContinue()),
                captured.getInt("analysis.relations.max-sources", defaults.maxSources()),
                captured.getInt("analysis.relations.max-work-items", defaults.maxWorkItems()));
    }

    private Snapshot configuredDefaults() {
        return new Snapshot(defaultMaxCalls, defaultMaxTotalCalls, defaultAutoContinue, defaultMaxSources, defaultMaxWorkItems);
    }

    /** Resource limits are independent of the semantic authority for accepted relation evidence. */
    public record Snapshot(int maxCalls, int maxTotalCalls, boolean autoContinue, int maxSources, int maxWorkItems) {
        public Snapshot {
            requireRange("analysis.relations.max-calls", maxCalls, 0, 10_000);
            requireRange("analysis.relations.max-total-calls", maxTotalCalls, 0, 10_000);
            requireRange("analysis.relations.max-sources", maxSources, 1, 256);
            requireRange("analysis.relations.max-work-items", maxWorkItems, 1, 100_000);
        }

        private static void requireRange(String key, int value, int min, int max) {
            if (value < min || value > max)
                throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        }
    }
}
