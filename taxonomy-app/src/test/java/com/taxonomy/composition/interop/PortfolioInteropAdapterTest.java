package com.taxonomy.composition.interop;

import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.IntegrationPortfolioPort;
import com.taxonomy.interop.IntegrationPortfolioPort.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioInteropAdapterTest {
    private final ProjectPortfolioService projects = mock(ProjectPortfolioService.class, RETURNS_DEEP_STUBS);
    private final PortfolioGitService portfolio = mock(PortfolioGitService.class);
    private final IntegrationPortfolioPort port = new PortfolioInteropAdapter(projects, portfolio);
    private final WorkspaceContext scope = new WorkspaceContext("alice", "private", "draft", "repo");
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");

    @ParameterizedTest
    @EnumSource(RequirementStatus.class)
    void preservesRequirementValuesAndTheExistingFingerprint(RequirementStatus status) {
        RequirementView value = mock(RequirementView.class);
        RequirementVersionView version = mock(RequirementVersionView.class);
        when(value.id()).thenReturn(7L); when(value.requirementKey()).thenReturn("key");
        when(value.title()).thenReturn("Title"); when(value.status()).thenReturn(status);
        when(value.currentVersionId()).thenReturn(9L); when(value.updatedAt()).thenReturn(time);
        when(value.currentVersion()).thenReturn(version); when(version.text()).thenReturn("Body");
        when(version.createdAt()).thenReturn(time);
        when(projects.listRequirements(3L, "alice", scope)).thenReturn(List.of(value));
        var mapped = port.listRequirements(3L, "alice", scope).getFirst();
        assertThat(mapped).isEqualTo(new RequirementData(7L, "key", "Title", status.name(), 9L, time, new VersionData("Body", time)));
        var json = new IntegrationJson(JsonMapper.builder().build());
        assertThat(json.fingerprint(List.of(List.of(value.id(), value.title(), value.status(), value.currentVersionId(), value.updatedAt()))))
                .isEqualTo(json.fingerprint(List.of(List.of(mapped.id(), mapped.title(), mapped.status(), mapped.currentVersionId(), mapped.updatedAt()))));
        verify(projects).listRequirements(3L, "alice", scope);
    }

    @Test
    void optionalValuesAreNotInvented() {
        RequirementView value = mock(RequirementView.class);
        when(projects.getRequirement(3L, 7L, "alice", scope)).thenReturn(value);
        var mapped = port.getRequirement(3L, 7L, "alice", scope);
        assertThat(mapped.status()).isNull(); assertThat(mapped.currentVersion()).isNull();
        verify(projects).getRequirement(3L, 7L, "alice", scope);
    }

    @Test
    void delegatesProjectReadsLocksAndFailuresWithTheExactScope() {
        ProjectView project = mock(ProjectView.class);
        when(project.id()).thenReturn(3L); when(project.title()).thenReturn("Project");
        when(projects.listProjects("alice", scope)).thenReturn(List.of(project));
        when(projects.getProject(3L, "alice", scope)).thenReturn(project);
        assertThat(port.listProjects("alice", scope)).containsExactly(new ProjectData(3L, "Project"));
        assertThat(port.getProject(3L, "alice", scope)).isEqualTo(new ProjectData(3L, "Project"));
        port.requireProject(3L, "alice", scope); port.requireProjectForUpdate(3L, "alice", scope);
        verify(projects).requireProject(3L, "alice", scope);
        verify(projects).requireProjectForUpdate(3L, "alice", scope);
        var failure = new IllegalArgumentException("not accessible");
        doThrow(failure).when(projects).requireProjectForUpdate(4L, "alice", scope);
        assertThatThrownBy(() -> port.requireProjectForUpdate(4L, "alice", scope)).isSameAs(failure);
    }

    @Test
    void retainsApprovedPaginationAndProjection() {
        RequirementView value = mock(RequirementView.class);
        when(projects.listApprovedRequirements(3L, "alice", scope, 2, 10).requirements()).thenReturn(List.of(value));
        when(projects.listApprovedRequirements(3L, "alice", scope, 2, 10).hasNext()).thenReturn(true);
        var result = port.listApprovedRequirements(3L, "alice", scope, 2, 10);
        assertThat(result.requirements()).hasSize(1); assertThat(result.hasNext()).isTrue();
        when(portfolio.contributeTo("source", "alice", scope)).thenReturn("projection");
        assertThat(port.contributeTo("source", "alice", scope)).isEqualTo("projection");
        verify(portfolio).contributeTo("source", "alice", scope);
    }

    @Test
    void retainsImportedDefaultsAndProvenance() {
        when(projects.createRequirement(eq(3L), any(CreateRequirementRequest.class), eq("alice"), same(scope)))
                .thenReturn(mock(RequirementView.class));
        port.createRequirement(3L, new ImportedRequirement("EXT-key", "Title", "Body", "Reason",
                new ImportProvenance("integration:source", "Original")), "alice", scope);
        var request = ArgumentCaptor.forClass(CreateRequirementRequest.class);
        verify(projects).createRequirement(eq(3L), request.capture(), eq("alice"), same(scope));
        assertThat(request.getValue()).isEqualTo(new CreateRequirementRequest("EXT-key", "Title", "Body",
                RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED, "alice", "Reason",
                new SourceReference(null, null, List.of(), "integration:source", null, "Original")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void updatesOnlyTheFieldsTheExistingImportRequests(boolean review) {
        port.updateRequirement(3L, 7L, "Title", review, "alice", scope);
        var request = ArgumentCaptor.forClass(UpdateRequirementRequest.class);
        verify(projects).updateRequirement(eq(3L), eq(7L), request.capture(), eq("alice"), same(scope));
        assertThat(request.getValue()).isEqualTo(new UpdateRequirementRequest("Title",
                review ? RequirementStatus.DRAFT : null, null, null, null,
                review ? ReviewStatus.PROPOSED : null, null));
    }

    @Test
    void archivesWithoutOverwritingOtherFieldsAndPreservesVersionMetadata() {
        port.archiveRequirement(3L, 7L, "alice", scope);
        verify(projects).updateRequirement(3L, 7L,
                new UpdateRequirementRequest(null, RequirementStatus.ARCHIVED, null, null, null, null, null), "alice", scope);
        port.addRequirementVersion(3L, 7L, "Body", "Reason", new ImportProvenance("source", "Original"), "alice", scope);
        verify(projects).addRequirementVersion(3L, 7L,
                new CreateRequirementVersionRequest("Body", "Reason", new SourceReference(null, null, List.of(), "source", null, "Original")), "alice", scope);
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "Current requirement version not found: 9",
            "Requirement has no text version: REQ-1"
    })
    void missingCurrentVersionUsesStableInteropConflict(String message) {
        when(projects.getRequirement(3L, 7L, "alice", scope))
                .thenThrow(PortfolioException.notFound(message));

        IntegrationProblem failure = catchThrowableOfType(
                () -> port.getRequirement(3L, 7L, "alice", scope),
                IntegrationProblem.class);

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("REQUIREMENT_VERSION_UNAVAILABLE");
        assertThat(failure.status()).isEqualTo(409);
    }

    @Test
    void ordinaryPortfolioNotFoundKeepsItsStatusWithoutBecomingAVersionFailure() {
        when(projects.getRequirement(3L, 7L, "alice", scope))
                .thenThrow(PortfolioException.notFound("Requirement 7 was not found in project 3"));

        IntegrationProblem failure = catchThrowableOfType(
                () -> port.getRequirement(3L, 7L, "alice", scope),
                IntegrationProblem.class);

        assertThat(failure).isNotNull();
        assertThat(failure.status()).isEqualTo(404);
        assertThat(failure.code()).isEqualTo("PORTFOLIO_NOT_FOUND");
        assertThat(failure.getMessage()).isEqualTo("Requirement 7 was not found in project 3");
    }

    @ParameterizedTest
    @EnumSource(PortfolioException.Kind.class)
    void portfolioFailuresPreserveStatusAndExplicitCode(PortfolioException.Kind kind) {
        PortfolioException source = new PortfolioException(
                kind, "PORTFOLIO_SOURCE_CODE", "Portfolio failure", null);
        when(projects.getProject(3L, "alice", scope)).thenThrow(source);

        IntegrationProblem failure = catchThrowableOfType(
                () -> port.getProject(3L, "alice", scope),
                IntegrationProblem.class);

        assertThat(failure).isNotNull();
        assertThat(failure.status()).isEqualTo(switch (kind) {
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case VALIDATION -> 400;
            case PAYLOAD_TOO_LARGE -> 413;
            case ANALYSIS_FAILED -> 422;
            case UNAVAILABLE -> 503;
        });
        assertThat(failure.code()).isEqualTo("PORTFOLIO_SOURCE_CODE");
        assertThat(failure.getMessage()).isEqualTo("Portfolio failure");
    }


}
