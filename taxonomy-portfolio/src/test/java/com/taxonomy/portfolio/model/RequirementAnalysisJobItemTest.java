package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
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

class RequirementAnalysisJobItemTest {

    @Test
    void synchronizesExactTenantAndExposesInitialWorkIdentity() {
        Parents parents = parents(" scope-a ", 30L);

        RequirementAnalysisJobItem item = item(parents);

        assertThat(item.getId()).isNull();
        assertThat(item.getScopeKey()).isEqualTo("scope-a");
        assertThat(item.getProjectId()).isEqualTo(10L);
        assertThat(item.getJobId()).isEqualTo("job-1");
        assertThat(item.getJob()).isSameAs(parents.job());
        assertThat(item.getRequirementId()).isEqualTo(20L);
        assertThat(item.getRequirement()).isSameAs(parents.requirement());
        assertThat(item.getRequirementVersionId()).isEqualTo(30L);
        assertThat(item.getRequirementVersion()).isSameAs(parents.version());
        assertThat(item.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(item.getSnapshotId()).isNull();
        assertThat(item.getSnapshot()).isNull();
        assertThat(item.getAttempt()).isEqualTo(1);
        assertThat(item.getStartedAt()).isNull();
        assertThat(item.getCompletedAt()).isNull();
        assertThat(item.getErrorMessage()).isNull();
        assertThat(item.getRowVersion()).isZero();
    }

    @Test
    void runningCompletionFailureAndRetryHaveDeterministicStateTransitions()
            throws Exception {
        Parents parents = parents("scope-a", 30L);
        RequirementAnalysisJobItem item = item(parents);
        Instant firstStart = Instant.parse("2026-08-18T08:00:00Z");
        Instant firstCompletion = Instant.parse("2026-08-18T08:01:00Z");

        item.markRunning(firstStart);
        assertThat(item.getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(item.getStartedAt()).isEqualTo(firstStart);
        assertThat(item.getCompletedAt()).isNull();
        assertThat(item.getErrorMessage()).isNull();

        RequirementAnalysisSnapshot staleAssociation =
                mock(RequirementAnalysisSnapshot.class);
        set(item, "snapshot", staleAssociation);
        item.complete(AnalysisStatus.SUCCESS, "snapshot-1", firstCompletion);
        assertThat(item.getStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(item.getSnapshotId()).isEqualTo("snapshot-1");
        assertThat(item.getSnapshot()).isNull();
        assertThat(item.getCompletedAt()).isEqualTo(firstCompletion);
        assertThat(item.getErrorMessage()).isNull();

        Instant failedAt = Instant.parse("2026-08-18T08:02:00Z");
        item.fail("provider unavailable", failedAt);
        assertThat(item.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(item.getErrorMessage()).isEqualTo("provider unavailable");
        assertThat(item.getCompletedAt()).isEqualTo(failedAt);

        ProjectRequirementVersion retryVersion = version(
                "scope-a", 20L, 31L);
        set(item, "snapshot", staleAssociation);
        item.prepareRetry(retryVersion);
        assertThat(item.getRequirementVersion()).isSameAs(retryVersion);
        assertThat(item.getRequirementVersionId()).isEqualTo(31L);
        assertThat(item.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(item.getSnapshotId()).isNull();
        assertThat(item.getSnapshot()).isNull();
        assertThat(item.getErrorMessage()).isNull();
        assertThat(item.getStartedAt()).isNull();
        assertThat(item.getCompletedAt()).isNull();
        assertThat(item.getAttempt()).isEqualTo(2);

        Instant partialAt = Instant.parse("2026-08-18T08:03:00Z");
        item.complete(AnalysisStatus.PARTIAL, "snapshot-2", partialAt);
        assertThat(item.getStatus()).isEqualTo(AnalysisStatus.PARTIAL);
        assertThat(item.getSnapshotId()).isEqualTo("snapshot-2");
        assertThat(item.getCompletedAt()).isEqualTo(partialAt);
    }

    @Test
    void completionRejectsEveryNonResultStatus() {
        for (AnalysisStatus status : new AnalysisStatus[]{
                null,
                AnalysisStatus.PENDING,
                AnalysisStatus.RUNNING,
                AnalysisStatus.FAILED,
                AnalysisStatus.CANCELLED}) {
            RequirementAnalysisJobItem item = item(parents("scope-a", 30L));
            assertThatThrownBy(() -> item.complete(
                    status, "snapshot-1", Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUCCESS or PARTIAL");
            assertThat(item.getStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(item.getSnapshotId()).isNull();
        }
    }

    @Test
    void requiresJobRequirementAndImmutableVersion() {
        Parents parents = parents("scope-a", 30L);
        assertParentFailure(new Parents(
                null, parents.requirement(), parents.version()));
        assertParentFailure(new Parents(
                parents.job(), null, parents.version()));
        assertParentFailure(new Parents(
                parents.job(), parents.requirement(), null));
    }

    @Test
    void rejectsMissingBlankAndConflictingTenantScopes() {
        Parents missing = parents("scope-a", 30L);
        when(missing.job().getScopeKey()).thenReturn(null);
        assertThatThrownBy(() -> item(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("job must expose an exact tenant scope");

        Parents blank = parents("scope-a", 30L);
        when(blank.job().getScopeKey()).thenReturn("  ");
        assertThatThrownBy(() -> item(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("job must expose an exact tenant scope");

        Parents requirementMismatch = parents("scope-a", 30L);
        when(requirementMismatch.requirement().getScopeKey())
                .thenReturn("scope-b");
        assertTenantMismatch(requirementMismatch);

        Parents versionMismatch = parents("scope-a", 30L);
        when(versionMismatch.version().getScopeKey()).thenReturn("scope-b");
        assertTenantMismatch(versionMismatch);
    }

    @Test
    void lifecycleRequiresPersistedParentsAndRejectsIdentityDrift()
            throws Exception {
        Parents transientParents = parents("scope-a", 30L);
        when(transientParents.job().getId()).thenReturn(null);
        RequirementAnalysisJobItem transientItem = item(transientParents);
        assertThat(transientItem.getJobId()).isNull();
        assertLifecycleFailure(
                transientItem,
                "parents must be persisted before the item");

        Parents projectMismatch = parents("scope-a", 30L);
        RequirementAnalysisJobItem wrongProject = item(projectMismatch);
        when(projectMismatch.requirement().getProjectId()).thenReturn(11L);
        assertLifecycleFailure(
                wrongProject,
                "job and requirement belong to different projects");

        Parents versionMismatch = parents("scope-a", 30L);
        RequirementAnalysisJobItem wrongVersion = item(versionMismatch);
        when(versionMismatch.version().getRequirementId()).thenReturn(21L);
        assertLifecycleFailure(
                wrongVersion,
                "version belongs to another requirement");

        RequirementAnalysisJobItem scalarProjectDrift = item(
                parents("scope-a", 30L));
        set(scalarProjectDrift, "projectId", 99L);
        assertLifecycleFailure(
                scalarProjectDrift,
                "project ID does not match its parents");
    }

    @Test
    void lifecycleRejectsScalarScopeDriftAndAcceptsMatchingAuthority()
            throws Exception {
        RequirementAnalysisJobItem item = item(parents("scope-a", 30L));
        set(item, "scopeKey", "scope-b");
        assertLifecycleFailure(item, "tenant scope does not match its parents");

        set(item, "scopeKey", " scope-a ");
        set(item, "projectId", 10L);
        assertThatCode(() -> invokeLifecycle(item)).doesNotThrowAnyException();
        assertThat(item.getScopeKey()).isEqualTo("scope-a");
        assertThat(item.getProjectId()).isEqualTo(10L);
    }

    @Test
    void retryRejectsVersionFromAnotherTenantOrRequirement() {
        RequirementAnalysisJobItem item = item(parents("scope-a", 30L));
        ProjectRequirementVersion otherTenant = version("scope-b", 20L, 31L);
        assertThatThrownBy(() -> item.prepareRetry(otherTenant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same tenant scope");

        ProjectRequirementVersion otherRequirement = version(
                "scope-a", 21L, 31L);
        assertThatThrownBy(() -> item.prepareRetry(otherRequirement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another requirement");
    }

    private static RequirementAnalysisJobItem item(Parents parents) {
        return new RequirementAnalysisJobItem(
                parents.job(),
                parents.requirement(),
                parents.version());
    }

    private static Parents parents(String scopeKey, Long versionId) {
        RequirementAnalysisJob job = mock(RequirementAnalysisJob.class);
        ProjectRequirement requirement = mock(ProjectRequirement.class);
        ProjectRequirementVersion version = version(scopeKey, 20L, versionId);

        when(job.getScopeKey()).thenReturn(scopeKey);
        when(job.getProjectId()).thenReturn(10L);
        when(job.getId()).thenReturn("job-1");
        when(requirement.getScopeKey()).thenReturn(scopeKey);
        when(requirement.getProjectId()).thenReturn(10L);
        when(requirement.getId()).thenReturn(20L);
        return new Parents(job, requirement, version);
    }

    private static ProjectRequirementVersion version(
            String scopeKey,
            Long requirementId,
            Long id) {
        ProjectRequirementVersion version = mock(ProjectRequirementVersion.class);
        when(version.getScopeKey()).thenReturn(scopeKey);
        when(version.getRequirementId()).thenReturn(requirementId);
        when(version.getId()).thenReturn(id);
        return version;
    }

    private static void assertParentFailure(Parents parents) {
        assertThatThrownBy(() -> item(parents))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must reference job, requirement and immutable version");
    }

    private static void assertTenantMismatch(Parents parents) {
        assertThatThrownBy(() -> item(parents))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not belong to the same tenant scope");
    }

    private static void set(
            RequirementAnalysisJobItem item,
            String fieldName,
            Object value) throws Exception {
        Field field = RequirementAnalysisJobItem.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(item, value);
    }

    private static void invokeLifecycle(RequirementAnalysisJobItem item)
            throws Exception {
        Method lifecycle = RequirementAnalysisJobItem.class
                .getDeclaredMethod("synchronizeTenantAuthority");
        lifecycle.setAccessible(true);
        lifecycle.invoke(item);
    }

    private static void assertLifecycleFailure(
            RequirementAnalysisJobItem item,
            String message) {
        Throwable thrown = catchThrowable(() -> invokeLifecycle(item));
        assertThat(thrown).isInstanceOf(InvocationTargetException.class);
        assertThat(thrown.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private record Parents(
            RequirementAnalysisJob job,
            ProjectRequirement requirement,
            ProjectRequirementVersion version) {
    }
}
