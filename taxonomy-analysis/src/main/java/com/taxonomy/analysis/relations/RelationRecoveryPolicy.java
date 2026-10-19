package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import java.util.LinkedHashSet;
import java.util.List;

/** Proves the original resource-bearing identity without changing its semantic inputs. */
public final class RelationRecoveryPolicy {
    private static final String LEGACY_PREFIX = "relation-downwalk-v8/source-call-reservation=half/relation-semantics-v2/relation-source-context-v1/provider-profiles/taxonomy-cohorts/custom-defaults=3,16000/";

    private RelationRecoveryPolicy() { }

    /** A zero cohort means the current policy has no historical source packing to restore. */
    public record Compatible(String fingerprint, int sourceCohortSize) { }

    static Compatible compatible(String savedHash, String currentFingerprint, boolean enabled, int depth,
            Integer batchOverride, Integer promptOverride, int configuredCalls, int configuredWork, int configuredSources) {
        if (savedHash == null || !savedHash.matches("[0-9a-f]{64}")) return null;
        if (savedHash.equals(AnalysisCheckpointSession.digest(currentFingerprint)))
            return new Compatible(currentFingerprint, 0);

        // Old fingerprints did not retain separate settings. Enumerate only bounded
        // call counts and known deployment/default packing; all semantics stay fixed.
        var sources = new LinkedHashSet<>(List.of(32, configuredSources));
        var workItems = new LinkedHashSet<>(List.of(512, configuredWork));
        var preferredCalls = new LinkedHashSet<>(List.of(24, configuredCalls, 256));
        for (int sourceCount : sources) for (int work : workItems) {
            for (int calls : preferredCalls) {
                String fingerprint = legacy(enabled, depth, batchOverride, promptOverride, calls, work, sourceCount);
                if (savedHash.equals(AnalysisCheckpointSession.digest(fingerprint)))
                    return new Compatible(fingerprint, sourceCount);
            }
            for (int calls = 0; calls <= 10_000; calls++) {
                if (preferredCalls.contains(calls)) continue;
                String fingerprint = legacy(enabled, depth, batchOverride, promptOverride, calls, work, sourceCount);
                if (savedHash.equals(AnalysisCheckpointSession.digest(fingerprint)))
                    return new Compatible(fingerprint, sourceCount);
            }
        }
        return null;
    }

    private static String legacy(boolean enabled, int depth, Integer batchOverride, Integer promptOverride,
            int calls, int work, int sources) {
        // Spell the original three-field record representation explicitly: Options
        // now has an execution budget field, which must not enter an old identity.
        return LEGACY_PREFIX + enabled + "/Options[limits=Limits[maxCalls=" + calls + ", maxDepth=" + depth
                + ", batchSize=" + (batchOverride == null ? 10 : batchOverride) + ", maxWorkItems=" + work
                + "], maxSources=" + sources + ", maxPromptCharacters=" + (promptOverride == null ? 120_000 : promptOverride)
                + "]/batch-override=" + batchOverride + "/prompt-override=" + promptOverride;
    }
}
