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

class RequirementAnalysisSnapshotTest {

    @Test
    void synchronizesAllExactTenantParentIdentifiersAndExposesPayload() {
        Parents parents = parents(" scope-a ");
        Instant createdAt = Instant.parse("2026-08-18T08:00:00Z");

        RequirementAnalysisSnapshot snapshot = snapshot(
                parents,
                AnalysisStatus.SUCCESS,
                createdAt);

        assertThat(snapshot.getId()).isEqualTo("snapshot-1");
        assertThat(snapshot.getScopeKey()).isEqualTo("scope-a");
        assertThat(snapshot.getProjectId()).isEqualTo(10L);
        assertThat(snapshot.getProject()).isSameAs(parents.project());
        assertThat(snapshot.getRequirementId()).isEqualTo(20L);
        assertThat(snapshot.getRequirement()).isSameAs(parents.requirement());
        assertThat(snapshot.getRequirementVersionId()).isEqualTo(30L);
        assertThat(snapshot.getRequirementVersion()).isSameAs(parents.version());
        assertThat(snapshot.getJobId()).isEqualTo("job-1");
        assertThat(snapshot.getJob()).isSameAs(parents.job());
        assertThat(snapshot.getStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(snapshot.getAnalysisSessionId()).isEqualTo("analysis-1");
        assertThat(snapshot.getProvider()).isEqualTo("MOCK");
        assertThat(snapshot.getModelName()).isEqualTo("model-1");
        assertThat(snapshot.getPromptFingerprint()).isEqualTo("prompt-fingerprint");
        assertThat(snapshot.getTaxonomyFingerprint()).isEqualTo("taxonomy-fingerprint");
        assertThat(snapshot.getWorkspaceId()).isEqualTo("workspace-a");
        assertThat(snapshot.getBranchName()).isEqualTo("draft");
        assertThat(snapshot.getCommitSha()).isEqualTo("commit-1");
        assertThat(snapshot.getCreatedBy()).isEqualTo("alice");
        assertThat(snapshot.getCreatedAt()).isEqualTo(createdAt);
        assertThat(snapshot.getDurationMs()).isEqualTo(125L);
        assertThat(snapshot.getWarningCount()).isEqualTo(2);
        assertThat(snapshot.getErrorMessage()).isEqualTo("partial warning");
        assertThat(snapshot.getAnalysisPayload()).isEqualTo("{\"analysis\":true}");
        assertThat(snapshot.getGapAnalysisPayload()).isEqualTo("{\"gaps\":[]}");
        assertThat(snapshot.getPatternDetectionPayload()).isEqualTo("{\"patterns\":[]}");
        assertThat(snapshot.getRecommendationPayload())
                .isEqualTo("{\"recommendation\":true}");

        RequirementAnalysisSnapshot partial = snapshot(
                parents("scope-a"),
                AnalysisStatus.PARTIAL,
                createdAt);
        assertThat(partial.getStatus()).isEqualTo(AnalysisStatus.PARTIAL);
    }

    @Test
    void rejectsEveryNonSnapshotTerminalStatus() {
        Parents parents = parents("scope-a");
        for (AnalysisStatus status : new AnalysisStatus[]{
                AnalysisStatus.PENDING,
                AnalysisStatus.RUNNING,
                AnalysisStatus.FAILED,
                AnalysisStatus.CANCELLED}) {
            assertThatThrownBy(() -> snapshot(parents, status, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUCCESS or PARTIAL");
        }
    }

    @Test
    void requiresAllFourParentAssociations() {
        Parents parents = parents("scope-a");
        assertParentFailure(new Parents(
                null, parents.requirement(), parents.version(), parents.job()));
        assertParentFailure(new Parents(
                parents.project(), null, parents.version(), parents.job()));
        assertParentFailure(new Parents(
                parents.project(), parents.requirement(), null, parents.job()));
        assertParentFailure(new Parents(
                parents.project(), parents.requirement(), parents.version(), null));
    }

    @Test
    void rejectsMissingBlankAndConflictingTenantScopes() {
        Parents missing = parents("scope-a");
        when(missing.project().getScopeKey()).thenReturn(null);
        assertThatThrownBy(() -> snapshot(
                missing, AnalysisStatus.SUCCESS, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project must expose an exact tenant scope");

        Parents blank = parents("scope-a");
        when(blank.project().getScopeKey()).thenReturn("  ");
        assertThatThrownBy(() -> snapshot(
                blank, AnalysisStatus.SUCCESS, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project must expose an exact tenant scope");

        Parents requirementMismatch = parents("scope-a");
        when(requirementMismatch.requirement().getScopeKey()).thenReturn("scope-b");
        assertTenantMismatch(requirementMismatch);

        Parents versionMismatch = parents("scope-a");
        when(versionMismatch.version().getScopeKey()).thenReturn("scope-b");
        assertTenantMismatch(versionMismatch);

        Parents jobMismatch = parents("scope-a");
        when(jobMismatch.job().getScopeKey()).thenReturn("scope-b");
        assertTenantMismatch(jobMismatch);
    }

    @Test
    void lifecycleRequiresPersistedParentsAndRejectsIdentityDrift() throws Exception {
        Parents transientParents = parents("scope-a");
        when(transientParents.project().getId()).thenReturn(null);
        RequirementAnalysisSnapshot transientSnapshot = snapshot(
                transientParents, AnalysisStatus.SUCCESS, Instant.now());
        assertThat(transientSnapshot.getProjectId()).isNull();
        assertLifecycleFailure(
                transientSnapshot,
                "parents must be persisted before the snapshot");

        Parents projectMismatch = parents("scope-a");
        RequirementAnalysisSnapshot wrongProject = snapshot(
                projectMismatch, AnalysisStatus.SUCCESS, Instant.now());
        when(projectMismatch.requirement().getProjectId()).thenReturn(11L);
        assertLifecycleFailure(
                wrongProject,
                "project, requirement and job do not match");

        Parents jobMismatch = parents("scope-a");
        RequirementAnalysisSnapshot wrongJob = snapshot(
                jobMismatch, AnalysisStatus.SUCCESS, Instant.now());
        when(jobMismatch.job().getProjectId()).thenReturn(11L);
        assertLifecycleFailure(
                wrongJob,
                "project, requirement and job do not match");

        Parents versionMismatch = parents("scope-a");
        RequirementAnalysisSnapshot wrongVersion = snapshot(
                versionMismatch, AnalysisStatus.SUCCESS, Instant.now());
        when(versionMismatch.version().getRequirementId()).thenReturn(21L);
        assertLifecycleFailure(
                wrongVersion,
                "version belongs to another requirement");
    }

    @Test
    void lifecycleRejectsScalarScopeDriftAndAcceptsMatchingAuthority()
            throws Exception {
        RequirementAnalysisSnapshot snapshot = snapshot(
                parents("scope-a"), AnalysisStatus.SUCCESS, Instant.now());
        set(snapshot, "scopeKey", "scope-b");
        assertLifecycleFailure(snapshot, "tenant scope does not match its parents");

        set(snapshot, "scopeKey", " scope-a ");
        assertThatCode(() -> invokeLifecycle(snapshot)).doesNotThrowAnyException();
        assertThat(snapshot.getScopeKey()).isEqualTo("scope-a");
    }

    private static RequirementAnalysisSnapshot snapshot(
            Parents parents,
            AnalysisStatus status,
            Instant createdAt) {
        return new RequirementAnalysisSnapshot(
                "snapshot-1",
                parents.project(),
                parents.requirement(),
                parents.version(),
                parents.job(),
                status,
                "analysis-1",
                "MOCK",
                "model-1",
                "prompt-fingerprint",
                "taxonomy-fingerprint",
                "workspace-a",
                "draft",
                "commit-1",
                "alice",
                createdAt,
                125L,
                2,
                "partial warning",
                "{\"analysis\":true}",
                "{\"gaps\":[]}",
                "{\"patterns\":[]}",
                "{\"recommendation\":true}");
    }

    private static Parents parents(String scopeKey) {
        ArchitectureProject project = mock(ArchitectureProject.class);
        ProjectRequirement requirement = mock(ProjectRequirement.class);
        ProjectRequirementVersion version = mock(ProjectRequirementVersion.class);
        RequirementAnalysisJob job = mock(RequirementAnalysisJob.class);

        when(project.getScopeKey()).thenReturn(scopeKey);
        when(project.getId()).thenReturn(10L);
        when(requirement.getScopeKey()).thenReturn(scopeKey);
        when(requirement.getProjectId()).thenReturn(10L);
        when(requirement.getId()).thenReturn(20L);
        when(version.getScopeKey()).thenReturn(scopeKey);
        when(version.getRequirementId()).thenReturn(20L);
        when(version.getId()).thenReturn(30L);
        when(job.getScopeKey()).thenReturn(scopeKey);
        when(job.getProjectId()).thenReturn(10L);
        when(job.getId()).thenReturn("job-1");
        return new Parents(project, requirement, version, job);
    }

    private static void assertParentFailure(Parents parents) {
        assertThatThrownBy(() -> snapshot(
                parents, AnalysisStatus.SUCCESS, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must reference project, requirement, version and job");
    }

    private static void assertTenantMismatch(Parents parents) {
        assertThatThrownBy(() -> snapshot(
                parents, AnalysisStatus.SUCCESS, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not belong to the same tenant scope");
    }

    private static void set(
            RequirementAnalysisSnapshot snapshot,
            String fieldName,
            Object value) throws Exception {
        Field field = RequirementAnalysisSnapshot.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(snapshot, value);
    }

    private static void invokeLifecycle(RequirementAnalysisSnapshot snapshot)
            throws Exception {
        Method lifecycle = RequirementAnalysisSnapshot.class
                .getDeclaredMethod("synchronizeTenantAuthority");
        lifecycle.setAccessible(true);
        lifecycle.invoke(snapshot);
    }

    private static void assertLifecycleFailure(
            RequirementAnalysisSnapshot snapshot,
            String message) {
        Throwable thrown = catchThrowable(() -> invokeLifecycle(snapshot));
        assertThat(thrown).isInstanceOf(InvocationTargetException.class);
        assertThat(thrown.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private record Parents(
            ArchitectureProject project,
            ProjectRequirement requirement,
            ProjectRequirementVersion version,
            RequirementAnalysisJob job) {
    }
}
