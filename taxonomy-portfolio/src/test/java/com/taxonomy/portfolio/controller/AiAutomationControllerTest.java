package com.taxonomy.portfolio.controller;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetCatalogView;
import com.taxonomy.analysis.service.AiTargetCatalogService;
import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiAutomationControllerTest {

    @Test
    void delegatesEffectivePolicyStatusToTheAutomationService() {
        CopilotAutomationService automationService = mock(CopilotAutomationService.class);
        AiTargetCatalogService targetCatalog = mock(AiTargetCatalogService.class);
        AiAutomationStatus expected = new AiAutomationStatus(
                null,
                true,
                false,
                false,
                false,
                "MOCK",
                null,
                null,
                null,
                1,
                1,
                25,
                null,
                List.of("analyse"),
                List.of("review"));
        when(automationService.status()).thenReturn(expected);
        AiAutomationController controller = new AiAutomationController(
                automationService, targetCatalog);

        assertThat(controller.status()).isSameAs(expected);
        verify(automationService).status();
        verifyNoInteractions(targetCatalog);
    }

    @Test
    void delegatesCredentialFreeTargetCatalogToTheCatalogService() {
        CopilotAutomationService automationService = mock(CopilotAutomationService.class);
        AiTargetCatalogService targetCatalog = mock(AiTargetCatalogService.class);
        AiTargetCatalogView expected = new AiTargetCatalogView(null, List.of());
        when(targetCatalog.catalog()).thenReturn(expected);
        AiAutomationController controller = new AiAutomationController(
                automationService, targetCatalog);

        assertThat(controller.targets()).isSameAs(expected);
        verify(targetCatalog).catalog();
        verifyNoInteractions(automationService);
    }
}
