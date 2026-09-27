package com.taxonomy.export.service;

import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Transport orchestration checks; the real relationship protocol has its own integration suite. */
class AnalysisDrivenExportTest {
    private static final String ORIGINAL = "The application reads records. It must not modify them.";

    @Test void invokesTheSharedUseCaseOnceWithTheOriginalAndRequestWorkspace() {
        try (var f = new Fixture()) {
            f.facade.buildDiagram(ORIGINAL);
            var command = ArgumentCaptor.forClass(AnalyzeRequirementCommand.class);
            verify(f.analysis).analyzePreview(command.capture());
            assertEquals(ORIGINAL, command.getValue().businessText());
            assertEquals(f.workspace, command.getValue().workspaceContext());
            assertEquals("architect", command.getValue().username());
            assertTrue(command.getValue().includeArchitectureView());
            assertEquals(37, command.getValue().maxArchitectureNodes());
            verify(f.state).ensureWorkspaceState("architect");
            verify(f.llm, never()).analyzeWithBudget(anyString());
            verifyNoInteractions(f.legacy);
        }
    }

    @Test void doesNotReselectOrRebuildTheReturnedArchitecture() {
        try (var f = new Fixture()) {
            f.result.setArchitectureView(view(60));
            var diagram = f.facade.buildDiagram(ORIGINAL);
            assertEquals(60, diagram.nodes().size(), "A second diagram cap must not truncate the use-case result");
            assertEquals("E-59", diagram.nodes().getLast().id());
            assertEquals(60, f.result.getArchitectureView().getIncludedElements().size());
            verifyNoInteractions(f.legacy);
        }
    }

    @Test void partialEvidenceIsExportedOnlyWithAnExplicitPartialTitle() {
        try (var f = new Fixture()) {
            f.result.setStatus("PARTIAL");
            var diagram = f.facade.buildDiagram(ORIGINAL);
            assertEquals(3, diagram.nodes().size());
            assertTrue(diagram.title().contains("PARTIAL"));
            assertTrue(diagram.title().contains("TEILERGEBNIS"));
            assertNull(f.result.getArchitectureView().getViewTitle(), "Export must not mutate the supplied view");
        }
    }

    @Test void failedOrUnknownAnalysisNeverFallsBackToScores() {
        for (String status : new String[]{"ERROR", "CANCELLED", "RUNNING", null}) {
            try (var f = new Fixture()) {
                f.result.setStatus(status);
                var failure = assertThrows(ResponseStatusException.class, () -> f.facade.buildDiagram(ORIGINAL));
                assertEquals(422, failure.getStatusCode().value());
                verify(f.llm, never()).analyzeWithBudget(anyString());
                verifyNoInteractions(f.legacy);
            }
        }
    }

    @Test void absentOrEmptyArchitectureDoesNotProduceASuccessfulEmptyFile() {
        for (var view : Arrays.asList(null, view(0))) {
            try (var f = new Fixture()) {
                f.result.setArchitectureView(view);
                assertEquals(422, assertThrows(ResponseStatusException.class,
                        () -> f.facade.buildDiagram(ORIGINAL)).getStatusCode().value());
            }
        }
    }

    @Test void unavailableCatalogueOrWorkspaceStartsNoAnalysis() {
        try (var f = new Fixture()) {
            when(f.catalogue.isInitialized()).thenReturn(false);
            assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> f.facade.buildDiagram(ORIGINAL)).getStatusCode().value());
            verifyNoInteractions(f.analysis);
        }
        try (var f = new Fixture()) {
            when(f.resolver.resolveCurrentContext()).thenThrow(new AccessDeniedException("Foreign workspace"));
            assertThrows(AccessDeniedException.class, () -> f.facade.buildDiagram(ORIGINAL));
            verifyNoInteractions(f.analysis);
        }
    }

    @Test void requestSizeAndLiveLimitsAreAppliedBeforeProviderWork() {
        try (var f = new Fixture()) {
            when(f.settings.getInt("limits.max-business-text", 5000)).thenReturn(100);
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> f.facade.buildDiagram("x".repeat(101))).getStatusCode().value());
            verifyNoInteractions(f.analysis);
            when(f.settings.getInt("limits.max-architecture-nodes", 50)).thenReturn(81);
            f.facade.buildDiagram(ORIGINAL);
            var command = ArgumentCaptor.forClass(AnalyzeRequirementCommand.class);
            verify(f.analysis).analyzePreview(command.capture());
            assertEquals(81, command.getValue().maxArchitectureNodes());
        }
    }

    @Test void frozenViewExportDoesNotResolveOrAnalyzeAgain() {
        try (var f = new Fixture()) {
            var frozen = view(4);
            var diagram = f.facade.buildCurrentDiagram(frozen);
            assertEquals(4, diagram.nodes().size());
            verifyNoInteractions(f.analysis, f.resolver, f.state, f.llm, f.legacy, f.catalogue);
        }
    }

    private static RequirementArchitectureView view(int count) {
        var view = new RequirementArchitectureView();
        for (int i = 0; i < count; i++) {
            var element = new RequirementElementView();
            element.setNodeCode("E-" + i); element.setTitle("Record " + i);
            element.setTaxonomySheet("IP"); element.setRelevance(0.8);
            view.getIncludedElements().add(element);
        }
        return view;
    }

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final LlmService llm = mock(LlmService.class);
        final RequirementArchitectureViewService legacy = mock(RequirementArchitectureViewService.class);
        final AnalyzeRequirementUseCase analysis = mock(AnalyzeRequirementUseCase.class);
        final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
        final RepositoryStateService state = mock(RepositoryStateService.class);
        final AnalysisRuntimeSettings settings = mock(AnalysisRuntimeSettings.class);
        final TaxonomyService catalogue = mock(TaxonomyService.class);
        final WorkspaceContext workspace = new WorkspaceContext("architect", "workspace-a", "branch-a", "repository-a");
        final AnalysisResult result = new AnalysisResult();
        final ExportFacade facade;
        Fixture() {
            result.setStatus("SUCCESS"); result.setScores(Map.of("E-0", 80)); result.setArchitectureView(view(3));
            when(llm.analyzeWithBudget(anyString())).thenReturn(result);
            when(legacy.build(anyMap(), anyString(), anyInt())).thenReturn(view(1));
            when(analysis.analyzePreview(any())).thenReturn(new AnalyzeRequirementResult(result));
            when(resolver.resolveCurrentUsername()).thenReturn("architect");
            when(resolver.resolveCurrentContext()).thenReturn(workspace);
            when(settings.getInt("limits.max-business-text", 5000)).thenReturn(5000);
            when(settings.getInt("limits.max-architecture-nodes", 50)).thenReturn(37);
            when(catalogue.isInitialized()).thenReturn(true);
            context.getBeanFactory().registerSingleton("llm", llm);
            context.getBeanFactory().registerSingleton("legacy", legacy);
            context.getBeanFactory().registerSingleton("analysis", analysis);
            context.getBeanFactory().registerSingleton("resolver", resolver);
            context.getBeanFactory().registerSingleton("state", state);
            context.getBeanFactory().registerSingleton("settings", settings);
            context.getBeanFactory().registerSingleton("catalogue", catalogue);
            context.registerBean(DiagramProjectionService.class, DiagramProjectionService::new);
            context.getBeanFactory().registerSingleton("VisioDiagramService", mock(VisioDiagramService.class));
            context.getBeanFactory().registerSingleton("VisioPackageBuilder", mock(VisioPackageBuilder.class));
            context.getBeanFactory().registerSingleton("ArchiMateDiagramService", mock(ArchiMateDiagramService.class));
            context.getBeanFactory().registerSingleton("ArchiMateXmlExporter", mock(ArchiMateXmlExporter.class));
            context.getBeanFactory().registerSingleton("MermaidExportService", mock(MermaidExportService.class));
            context.getBeanFactory().registerSingleton("StructurizrExportService", mock(StructurizrExportService.class));
            context.getBeanFactory().registerSingleton("SavedAnalysisService", mock(SavedAnalysisService.class));
            context.register(ExportFacade.class);
            context.scan("com.taxonomy.composition.analysis");
            context.refresh();
            facade = context.getBean(ExportFacade.class);
        }
        public void close() { context.close(); }
    }

    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (var method : AnalysisDrivenExportTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) continue;
            try { method.invoke(new AnalysisDrivenExportTest()); passed++; }
            catch (java.lang.reflect.InvocationTargetException failure) {
                failed++; System.err.println(method.getName() + ": " + failure.getCause());
            }
        }
        System.out.println("Analysis-driven exports: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new AssertionError(failed + " failures");
    }
}
