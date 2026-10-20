package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RelationRecoveryPolicyTest {
    private final RequirementRelationSearchService service = new RequirementRelationSearchService(null, null, null, null);

    @ParameterizedTest
    @ValueSource(ints = {0, 7, 24, 256, 9999, 10000})
    void originalDefaultPackingIsRecoveredAcrossTheSupportedCallRange(int calls) {
        String original = legacy(calls, 8, 10, 512, 32, 120_000, "null", "null");
        var compatible = RelationRecoveryPolicy.compatible(AnalysisCheckpointSession.digest(original),
                previousPolicy(true, null, null), true, 8, null, null, 256, 4096, 128);
        assertThat(compatible).isNotNull();
        assertThat(compatible.fingerprint()).isEqualTo(original);
        assertThat(compatible.sourceCohortSize()).isEqualTo(32);
    }

    @Test
    void currentDeploymentPackingAndExplicitSemanticOverridesCanProveAnOldIdentity() {
        String original = legacy(99, 8, 3, 2048, 64, 16_000, "3", "16000");
        var compatible = RelationRecoveryPolicy.compatible(AnalysisCheckpointSession.digest(original),
                previousPolicy(true, 3, 16_000), true, 8, 3, 16_000, 256, 2048, 64);
        assertThat(compatible).isEqualTo(new RelationRecoveryPolicy.Compatible(original, 64));
    }

    @Test
    void changedNecessityPolicyRejectsLegacyBridge() {
        String previous = previousPolicy(true, null, null);
        for (String saved : new String[] {legacy(24, 8, 10, 512, 32, 120_000, "null", "null"), previous}) {
            String hash = AnalysisCheckpointSession.digest(saved);
            assertThat(RelationRecoveryPolicy.compatible(hash, previous + "/functional-necessity-v1",
                    true, 8, null, null, 256, 4096, 128)).isNull();
            assertThat(service.compatibleRecoveryPolicy(hash)).isNull();
        }
    }

    @Test
    void aCurrentSemanticFingerprintDoesNotInventHistoricalPacking() {
        String current = service.recoveryPolicyFingerprint();
        ReflectionTestUtils.setField(service, "maxCalls", 512);
        ReflectionTestUtils.setField(service, "maxTotalCalls", 2048);
        ReflectionTestUtils.setField(service, "maxSources", 256);
        ReflectionTestUtils.setField(service, "maxWorkItems", 8192);
        ReflectionTestUtils.setField(service, "autoContinue", false);
        assertThat(service.compatibleRecoveryPolicy(AnalysisCheckpointSession.digest(current)))
                .isEqualTo(new RelationRecoveryPolicy.Compatible(current, 0));
    }

    @Test
    void unknownHistoricalSourceAndWorkSettingsRequireTheirOriginalDeploymentConfiguration() {
        assertThat(RelationRecoveryPolicy.compatible(AnalysisCheckpointSession.digest(
                legacy(24, 8, 10, 513, 31, 120_000, "null", "null")),
                previousPolicy(true, null, null), true, 8, null, null, 256, 4096, 128)).isNull();
    }

    @Test
    void anExplicitPackingOverrideIsSemanticEvenWhenItsNumberEqualsTheOldDefault() {
        assertThat(RelationRecoveryPolicy.compatible(AnalysisCheckpointSession.digest(
                legacy(24, 8, 10, 512, 32, 120_000, "null", "null")),
                previousPolicy(true, 10, null), true, 8, 10, null, 256, 4096, 128)).isNull();
    }

    @Test
    void disabledRelationSearchCannotResumeEnabledEvidence() {
        assertThat(RelationRecoveryPolicy.compatible(AnalysisCheckpointSession.digest(
                legacy(24, 8, 10, 512, 32, 120_000, "null", "null")),
                previousPolicy(false, null, null), false, 8, null, null, 256, 4096, 128)).isNull();
    }

    /** Frozen v9 identity before the functional-necessity policy, independent of production changes. */
    private static String previousPolicy(boolean enabled, Integer batchOverride, Integer promptOverride) {
        return "relation-downwalk-v9/source-call-reservation=half/relation-semantics-v2/relation-source-context-v1/provider-profiles/taxonomy-cohorts/custom-defaults=3,16000/" + enabled
                + "/max-depth=8/standard-batch=" + (batchOverride == null ? 10 : batchOverride)
                + "/standard-prompt=" + (promptOverride == null ? 120_000 : promptOverride)
                + "/batch-override=" + batchOverride + "/prompt-override=" + promptOverride;
    }

    private static String legacy(int calls, int depth, int batch, int work, int sources, int prompt,
            String batchOverride, String promptOverride) {
        return "relation-downwalk-v8/source-call-reservation=half/relation-semantics-v2/relation-source-context-v1/provider-profiles/taxonomy-cohorts/custom-defaults=3,16000/true/"
                + "Options[limits=Limits[maxCalls=" + calls + ", maxDepth=" + depth + ", batchSize=" + batch
                + ", maxWorkItems=" + work + "], maxSources=" + sources + ", maxPromptCharacters=" + prompt
                + "]/batch-override=" + batchOverride + "/prompt-override=" + promptOverride;
    }
}
