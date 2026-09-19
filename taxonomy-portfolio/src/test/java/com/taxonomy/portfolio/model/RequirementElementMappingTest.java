package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.MappingOrigin;
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

class RequirementElementMappingTest {

    @Test
    void synchronizesExactTenantAndExposesReviewAndActionMetadata() {
        RequirementAnalysisSnapshot snapshot = snapshot(" scope-a ", "snapshot-1");

        RequirementElementMapping mapping = new RequirementElementMapping(
                snapshot,
                "BP-1000",
                "Business process",
                "BP",
                85,
                0.8,
                0.7,
                MappingOrigin.DIRECT,
                "BP/BP-1000",
                "Direct requirement match",
                true);

        assertThat(mapping.getSnapshot()).isSameAs(snapshot);
        assertThat(mapping.getScopeKey()).isEqualTo("scope-a");
        assertThat(mapping.getSnapshotId()).isEqualTo("snapshot-1");
        assertThat(mapping.getNodeCode()).isEqualTo("BP-1000");
        assertThat(mapping.getNodeTitle()).isEqualTo("Business process");
        assertThat(mapping.getTaxonomyRoot()).isEqualTo("BP");
        assertThat(mapping.getDirectScore()).isEqualTo(85);
        assertThat(mapping.getRelevance()).isEqualTo(0.8);
        assertThat(mapping.getConfidence()).isEqualTo(0.7);
        assertThat(mapping.getMappingOrigin()).isEqualTo(MappingOrigin.DIRECT);
        assertThat(mapping.getHierarchyPath()).isEqualTo("BP/BP-1000");
        assertThat(mapping.getPresenceReason()).isEqualTo("Direct requirement match");
        assertThat(mapping.isSelectedForImpact()).isTrue();
        assertThat(mapping.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(mapping.getActionStatus()).isEqualTo(ActionStatus.UNDECIDED);
        assertThat(mapping.getId()).isNull();
        assertThat(mapping.getRowVersion()).isZero();

        Instant proposedAt = Instant.parse("2026-08-18T08:00:00Z");
        mapping.review(
                null,
                null,
                "No action selected",
                "alice",
                "Keep proposed",
                proposedAt);
        assertThat(mapping.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(mapping.getActionStatus()).isEqualTo(ActionStatus.UNDECIDED);
        assertThat(mapping.getActionEvidence()).isEqualTo("No action selected");
        assertThat(mapping.getDecisionBy()).isEqualTo("alice");
        assertThat(mapping.getDecisionComment()).isEqualTo("Keep proposed");
        assertThat(mapping.getDecisionAt()).isEqualTo(proposedAt);

        Instant confirmedAt = Instant.parse("2026-08-18T08:01:00Z");
        mapping.review(
                ReviewStatus.CONFIRMED,
                ActionStatus.REUSE,
                "Existing service covers the requirement",
                "architect",
                "Confirmed",
                confirmedAt);
        assertThat(mapping.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(mapping.getActionStatus()).isEqualTo(ActionStatus.REUSE);
        assertThat(mapping.getActionEvidence())
                .isEqualTo("Existing service covers the requirement");
        assertThat(mapping.getDecisionBy()).isEqualTo("architect");
        assertThat(mapping.getDecisionComment()).isEqualTo("Confirmed");
        assertThat(mapping.getDecisionAt()).isEqualTo(confirmedAt);
    }

    @Test
    void lifecycleRequiresAPersistedSnapshot() {
        RequirementElementMapping mapping = mapping(snapshot("scope-a", null));

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
        RequirementElementMapping mapping = mapping(
                snapshot("scope-a", "snapshot-1"));

        set(mapping, "scopeKey", "scope-b");
        assertLifecycleFailure(mapping, "tenant scope does not match");

        set(mapping, "scopeKey", "scope-a");
        set(mapping, "snapshotId", "snapshot-2");
        assertLifecycleFailure(mapping, "snapshot ID does not match");
    }

    @Test
    void lifecycleAcceptsMatchingPrepopulatedAuthority() throws Exception {
        RequirementElementMapping mapping = mapping(
                snapshot("scope-a", "snapshot-1"));
        set(mapping, "scopeKey", " scope-a ");
        set(mapping, "snapshotId", "snapshot-1");

        assertThatCode(() -> invokeLifecycle(mapping)).doesNotThrowAnyException();
        assertThat(mapping.getScopeKey()).isEqualTo("scope-a");
        assertThat(mapping.getSnapshotId()).isEqualTo("snapshot-1");
    }

    private static RequirementElementMapping mapping(
            RequirementAnalysisSnapshot snapshot) {
        return new RequirementElementMapping(
                snapshot,
                "BP-1000",
                "Business process",
                "BP",
                85,
                0.8,
                0.7,
                MappingOrigin.DIRECT,
                null,
                null,
                false);
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
            RequirementElementMapping mapping,
            String fieldName,
            Object value) throws Exception {
        Field field = RequirementElementMapping.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(mapping, value);
    }

    private static void invokeLifecycle(RequirementElementMapping mapping)
            throws Exception {
        Method lifecycle = RequirementElementMapping.class
                .getDeclaredMethod("synchronizeTenantAuthority");
        lifecycle.setAccessible(true);
        lifecycle.invoke(mapping);
    }

    private static void assertLifecycleFailure(
            RequirementElementMapping mapping,
            String message) {
        Throwable thrown = catchThrowable(() -> invokeLifecycle(mapping));
        assertThat(thrown).isInstanceOf(InvocationTargetException.class);
        assertThat(thrown.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
