package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotRunRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectAutopilotService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAutopilotServiceTest {

    @Mock private CopilotAutomationService automationService;
    @Mock private ProjectPortfolioService projectService;

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");

    @Test
    void explicitRequirementBatchStartsOnlyTheRequestedOperations() {
        ProjectAutopilotService service = service(10);
        RequirementView first = requirement(7L);
        RequirementView second = requirement(8L);
        RequirementView third = requirement(9L);
        when(automationService.status()).thenReturn(status(true, true, "ready"));
        when(projectService.listRequirements(41L, context.username(), context))
                .thenReturn(List.of(first, second, third));
        when(automationService.tryAutopilot(
                41L, 9L, context.username(), context))
                .thenReturn(Optional.of(operation("a".repeat(64))));
        when(automationService.tryAutopilot(
                41L, 7L, context.username(), context))
                .thenReturn(Optional.of(operation("b".repeat(64))));

        var result = service.run(
                41L,
                new ProjectAutopilotRunRequest(List.of(9L, 7L, 9L), 2),
                context.username(),
                context);

        assertThat(result.selectedRequirements()).isEqualTo(2);
        assertThat(result.operationsStarted()).isEqualTo(2);
        assertThat(result.operationIds())
                .containsExactly("a".repeat(64), "b".repeat(64));
        verify(automationService).tryAutopilot(
                41L, 9L, context.username(), context);
        verify(automationService).tryAutopilot(
                41L, 7L, context.username(), context);
    }

    @Test
    void allRequirementsMustFitTheConfiguredOrRequestedLimit() {
        ProjectAutopilotService service = service(2);
        when(automationService.status()).thenReturn(status(true, true, "ready"));
        when(projectService.listRequirements(41L, context.username(), context))
                .thenReturn(List.of(requirement(7L), requirement(8L), requirement(9L)));

        assertThatThrownBy(() -> service.run(
                41L, null, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exceeding the bounded batch limit of 2");
    }

    @Test
    void projectStatusValidatesScopeAndReportsSaveHookSeparately() {
        ProjectAutopilotService service = service(25);
        when(projectService.listRequirements(41L, context.username(), context))
                .thenReturn(List.of(requirement(7L), requirement(8L)));
        when(automationService.status())
                .thenReturn(status(true, false, "save hook disabled"));

        var result = service.status(41L, context.username(), context);

        assertThat(result.projectId()).isEqualTo(41L);
        assertThat(result.autopilotReady()).isTrue();
        assertThat(result.runAfterRequirementSave()).isFalse();
        assertThat(result.requirementCount()).isEqualTo(2);
        assertThat(result.maximumBatchRequirements()).isEqualTo(25);
    }

    @Test
    void projectRunFailsClosedWhenAutopilotIsNotReady() {
        ProjectAutopilotService service = service(10);
        when(automationService.status())
                .thenReturn(status(false, false, "UNMETERED is required"));

        assertThatThrownBy(() -> service.run(
                41L, null, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("UNMETERED");
    }

    private ProjectAutopilotService service(int maximumBatch) {
        return new ProjectAutopilotService(
                automationService, projectService, maximumBatch);
    }

    private static RequirementView requirement(Long id) {
        return new RequirementView(
                id,
                41L,
                "REQ-" + id,
                "Requirement " + id,
                RequirementStatus.DRAFT,
                50,
                Criticality.MEDIUM,
                RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED,
                "architect",
                90L + id,
                null,
                Instant.now(),
                Instant.now(),
                null);
    }

    private static CopilotOperationView operation(String operationId) {
        CopilotOperationView operation = mock(CopilotOperationView.class);
        when(operation.operationId()).thenReturn(operationId);
        return operation;
    }

    private static AiAutomationStatus status(
            boolean ready,
            boolean runAfterSave,
            String reason) {
        AiAutomationStatus status = mock(AiAutomationStatus.class);
        when(status.autopilotReady()).thenReturn(ready);
        when(status.runAfterRequirementSave()).thenReturn(runAfterSave);
        when(status.reason()).thenReturn(reason);
        return status;
    }
}
