package com.taxonomy.composition.interop;

import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.InteropPortfolioPort;
import com.taxonomy.portfolio.dto.PortfolioDtos;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static com.taxonomy.interop.IntegrationDomainAdapter.workspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PortfolioInteropAdapterTest {
    private final RepositoryContext context = RepositoryContext.workspace("repo-b", "workspace-b", "draft", "alice");
    private final ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
    private final PortfolioInteropAdapter adapter = new PortfolioInteropAdapter(projects, mock(PortfolioGitService.class));

    @Test
    void requirementProjectionRetainsValuesAndOriginalStateFingerprint() {
        var original = mock(PortfolioDtos.RequirementView.class);
        var version = mock(PortfolioDtos.RequirementVersionView.class);
        Instant updated = Instant.parse("2026-08-01T12:00:00Z");
        Instant created = Instant.parse("2026-07-01T12:00:00Z");
        when(original.id()).thenReturn(42L);
        when(original.requirementKey()).thenReturn("REQ-42");
        when(original.title()).thenReturn("Preserved requirement");
        when(original.status()).thenReturn(RequirementStatus.APPROVED);
        when(original.currentVersionId()).thenReturn(7L);
        when(original.updatedAt()).thenReturn(updated);
        when(original.currentVersion()).thenReturn(version);
        when(version.text()).thenReturn("Unmodified body");
        when(version.createdAt()).thenReturn(created);
        when(projects.listRequirements(3L, context.username(), workspace(context))).thenReturn(List.of(original));
        when(projects.getRequirement(3L, 42L, context.username(), workspace(context))).thenReturn(original);

        var projected = adapter.listRequirements(context, 3L).getFirst();
        assertThat(adapter.getRequirement(context, 3L, 42L)).isEqualTo(projected);
        assertThat(projected.requirementKey()).isEqualTo(original.requirementKey());
        assertThat(projected.currentVersion().text()).isEqualTo(version.text());
        assertThat(projected.currentVersion().createdAt()).isEqualTo(created);
        assertThat(projected.approved()).isTrue();
        assertThat(projected.archived()).isFalse();
        var json = new IntegrationJson(JsonMapper.builder().build());
        assertThat(json.fingerprint(List.of(List.of(projected.id(), projected.title(), projected.status(),
                projected.currentVersionId(), projected.updatedAt()))))
                .isEqualTo(json.fingerprint(List.of(List.of(original.id(), original.title(), original.status(),
                        original.currentVersionId(), original.updatedAt()))));
        verify(projects).listRequirements(3L, context.username(), workspace(context));
        verify(projects).getRequirement(3L, 42L, context.username(), workspace(context));
    }

    @Test
    void projectReadsAndLocksRetainActorAndExactWorkspace() {
        var original = mock(PortfolioDtos.ProjectView.class);
        when(original.id()).thenReturn(3L);
        when(original.title()).thenReturn("Scoped project");
        when(projects.listProjects(context.username(), workspace(context))).thenReturn(List.of(original));
        when(projects.getProject(3L, context.username(), workspace(context))).thenReturn(original);
        assertThat(adapter.listProjects(context)).containsExactly(new InteropPortfolioPort.ProjectView(3L, "Scoped project"));
        assertThat(adapter.getProject(context, 3L)).isEqualTo(new InteropPortfolioPort.ProjectView(3L, "Scoped project"));
        adapter.requireProject(context, 3L);
        adapter.lockProject(context, 3L);
        adapter.lockProject(context, null);
        verify(projects).listProjects(context.username(), workspace(context));
        verify(projects).getProject(3L, context.username(), workspace(context));
        verify(projects).requireProject(3L, context.username(), workspace(context));
        verify(projects).requireProjectForUpdate(3L, context.username(), workspace(context));
        verifyNoMoreInteractions(projects);
    }

    @Test
    void authorizationFailurePropagatesWithoutFallback() {
        var denied = new IllegalArgumentException("not in this workspace");
        when(projects.getRequirement(3L, 42L, context.username(), workspace(context))).thenThrow(denied);
        assertThatThrownBy(() -> adapter.getRequirement(context, 3L, 42L)).isSameAs(denied);
        verify(projects).getRequirement(3L, 42L, context.username(), workspace(context));
        verifyNoMoreInteractions(projects);
    }

    @Test
    void statusNamesPreserveApprovedAndArchivedDecisions() {
        for (RequirementStatus status : RequirementStatus.values()) {
            var original = mock(PortfolioDtos.RequirementView.class);
            when(original.status()).thenReturn(status);
            when(projects.getRequirement(3L, 42L, context.username(), workspace(context))).thenReturn(original);
            var projected = adapter.getRequirement(context, 3L, 42L);
            assertThat(projected.status()).isEqualTo(status.name());
            assertThat(projected.approved()).isEqualTo(status == RequirementStatus.APPROVED);
            assertThat(projected.archived()).isEqualTo(status == RequirementStatus.ARCHIVED);
            assertThat(projected.currentVersion()).isNull();
        }
    }
}
