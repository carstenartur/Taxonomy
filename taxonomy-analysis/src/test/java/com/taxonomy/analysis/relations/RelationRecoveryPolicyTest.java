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
        var compatible = service.compatibleRecoveryPolicy(AnalysisCheckpointSession.digest(original));
        assertThat(compatible).isNotNull();
        assertThat(compatible.fingerprint()).isEqualTo(original);
        assertThat(compatible.sourceCohortSize()).isEqualTo(32);
    }

    @Test
    void currentDeploymentPackingAndExplicitSemanticOverridesCanProveAnOldIdentity() {
        ReflectionTestUtils.setField(service, "maxSources", 64);
        ReflectionTestUtils.setField(service, "maxWorkItems", 2048);
        ReflectionTestUtils.setField(service, "batchSize", 3);
        ReflectionTestUtils.setField(service, "maxPromptCharacters", 16_000);
        String original = legacy(99, 8, 3, 2048, 64, 16_000, "3", "16000");
        var compatible = service.compatibleRecoveryPolicy(AnalysisCheckpointSession.digest(original));
        assertThat(compatible).isEqualTo(new RelationRecoveryPolicy.Compatible(original, 64));
    }

    @Test
    void aCurrentSemanticFingerprintDoesNotInventHistoricalPacking() {
        String current = service.recoveryPolicyFingerprint();
        assertThat(service.compatibleRecoveryPolicy(AnalysisCheckpointSession.digest(current)))
                .isEqualTo(new RelationRecoveryPolicy.Compatible(current, 0));
    }

    @Test
    void unknownHistoricalSourceAndWorkSettingsRequireTheirOriginalDeploymentConfiguration() {
        assertThat(service.compatibleRecoveryPolicy(AnalysisCheckpointSession.digest(
                legacy(24, 8, 10, 513, 31, 120_000, "null", "null")))).isNull();
    }

    @Test
    void anExplicitPackingOverrideIsSemanticEvenWhenItsNumberEqualsTheOldDefault() {
        ReflectionTestUtils.setField(service, "batchSize", 10);
        assertThat(service.compatibleRecoveryPolicy(AnalysisCheckpointSession.digest(
                legacy(24, 8, 10, 512, 32, 120_000, "null", "null")))).isNull();
    }

    @Test
    void disabledRelationSearchCannotResumeEnabledEvidence() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.compatibleRecoveryPolicy(AnalysisCheckpointSession.digest(
                legacy(24, 8, 10, 512, 32, 120_000, "null", "null")))).isNull();
    }

    private static String legacy(int calls, int depth, int batch, int work, int sources, int prompt,
            String batchOverride, String promptOverride) {
        return "relation-downwalk-v8/source-call-reservation=half/relation-semantics-v2/relation-source-context-v1/provider-profiles/taxonomy-cohorts/custom-defaults=3,16000/true/"
                + "Options[limits=Limits[maxCalls=" + calls + ", maxDepth=" + depth + ", batchSize=" + batch
                + ", maxWorkItems=" + work + "], maxSources=" + sources + ", maxPromptCharacters=" + prompt
                + "]/batch-override=" + batchOverride + "/prompt-override=" + promptOverride;
    }
}
