package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequirementRelationMappingTest {

    @Test
    void synchronizesExactTenantAndExposesReviewMetadata() {
        RequirementAnalysisSnapshot snapshot = snapshot(" scope-a ", "snapshot-1");

        RequirementRelationMapping mapping = new RequirementRelationMapping(
                snapshot,
                "BP-1000",
                "CP-1000",
                "REALIZES",
                "ANALYSIS",
                "STRUCTURAL",
                0.8,
                0.7,
                "The requirement connects the two nodes");

        assertThat(mapping.getSnapshot()).isSameAs(snapshot);
        assertThat(mapping.getScopeKey()).isEqualTo("scope-a");
        assertThat(mapping.getSnapshotId()).isEqualTo("snapshot-1");
        assertThat(mapping.getSourceCode()).isEqualTo("BP-1000");
        assertThat(mapping.getTargetCode()).isEqualTo("CP-1000");
        assertThat(mapping.getRelationType()).isEqualTo("REALIZES");
        assertThat(mapping.getRelationOrigin()).isEqualTo("ANALYSIS");
        assertThat(mapping.getRelationCategory()).isEqualTo("STRUCTURAL");
        assertThat(mapping.getRelevance()).isEqualTo(0.8);
        assertThat(mapping.getConfidence()).isEqualTo(0.7);
        assertThat(mapping.getPresenceReason())
                .isEqualTo("The requirement connects the two nodes");
        assertThat(mapping.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(mapping.getId()).isNull();
        assertThat(mapping.getRowVersion()).isZero();

        Instant proposedAt = Instant.parse("2026-08-18T08:00:00Z");
        mapping.review(null, "alice", "Keep proposed", proposedAt);
        assertThat(mapping.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(mapping.getDecisionBy()).isEqualTo("alice");
        assertThat(mapping.getDecisionComment()).isEqualTo("Keep proposed");
        assertThat(mapping.getDecisionAt()).isEqualTo(proposedAt);

        Instant confirmedAt = Instant.parse("2026-08-18T08:01:00Z");
        mapping.review(ReviewStatus.CONFIRMED, "architect", "Confirmed", confirmedAt);
        assertThat(mapping.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(mapping.getDecisionBy()).isEqualTo("architect");
        assertThat(mapping.getDecisionComment()).isEqualTo("Confirmed");
        assertThat(mapping.getDecisionAt()).isEqualTo(confirmedAt);
    }

    @Test
    void lifecycleRequiresAPersistedSnapshot() throws Exception {
        RequirementRelationMapping mapping = new RequirementRelationMapping(
                snapshot("scope-a", null),
                "BP-1000",
                "CP-1000",
                "REALIZES",
                "ANALYSIS",
                null,
                0.8,
                0.7,
                null);

        assertThat(mapping.getScopeKey()).isEqualTo("scope-a");
        assertThat(mapping.getSnapshotId()).isNull();
        assertLifecycleFailure(mapping, "must be persisted before the mapping");
    }

    @Test
    void rejectsMissingNullOrBlankSnapshotScope() {
        assertThatThrownBy(() -> mapping(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must expose an exact tenant scope");
        assertThatThrownBy(() -> mapping(snapshot(null, "snapshot-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must expose an exact tenant scope");
        assertThatThrownBy(() -> mapping(snapshot("  ", "snapshot-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must expose an exact tenant scope");
    }

    @Test
    void rejectsTenantAndSnapshotIdentityDrift() throws Exception {
        RequirementRelationMapping mapping = mapping(
                snapshot("scope-a", "snapshot-1"));

        set(mapping, "scopeKey", "scope-b");
        assertLifecycleFailure(mapping, "tenant scope does not match");

        set(mapping, "scopeKey", "scope-a");
        set(mapping, "snapshotId", "snapshot-2");
        assertLifecycleFailure(mapping, "snapshot ID does not match");
    }

    @Test
    void lifecycleAcceptsMatchingPrepopulatedAuthority() throws Exception {
        RequirementRelationMapping mapping = mapping(
                snapshot("scope-a", "snapshot-1"));
        set(mapping, "scopeKey", " scope-a ");
        set(mapping, "snapshotId", "snapshot-1");

        assertThatCode(() -> invokeLifecycle(mapping)).doesNotThrowAnyException();
        assertThat(mapping.getScopeKey()).isEqualTo("scope-a");
        assertThat(mapping.getSnapshotId()).isEqualTo("snapshot-1");
    }

    private static RequirementRelationMapping mapping(
            RequirementAnalysisSnapshot snapshot) {
        return new RequirementRelationMapping(
                snapshot,
                "BP-1000",
                "CP-1000",
                "REALIZES",
                "ANALYSIS",
                null,
                0.8,
                0.7,
                null);
    }

    private static RequirementAnalysisSnapshot snapshot(
            String scopeKey,
            String id) {
        RequirementAnalysisSnapshot snapshot = mock(RequirementAnalysisSnapshot.class);
        when(snapshot.getScopeKey()).thenReturn(scopeKey);
        when(snapshot.getId()).thenReturn(id);
        return snapshot;
    }

    private static void set(
            RequirementRelationMapping mapping,
            String fieldName,
            Object value) throws Exception {
        Field field = RequirementRelationMapping.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(mapping, value);
    }

    private static void invokeLifecycle(RequirementRelationMapping mapping)
            throws Exception {
        Method lifecycle = RequirementRelationMapping.class
                .getDeclaredMethod("synchronizeTenantAuthority");
        lifecycle.setAccessible(true);
        lifecycle.invoke(mapping);
    }

    private static void assertLifecycleFailure(
            RequirementRelationMapping mapping,
            String message) {
        Throwable thrown = catchThrowable(() -> invokeLifecycle(mapping));
        assertThat(thrown).isInstanceOf(InvocationTargetException.class);
        assertThat(thrown.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
