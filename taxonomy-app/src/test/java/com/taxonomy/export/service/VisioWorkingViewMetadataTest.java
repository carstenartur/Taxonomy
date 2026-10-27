package com.taxonomy.export.service;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.controller.ExportApiController;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioExportMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import com.taxonomy.visio.VisioProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VisioWorkingViewMetadataTest {
    @BeforeEach
    void authenticateServerExporter() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("server-exporter", null, List.of()));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private static VisioWorkingViewMetadata contextService() {
        return new VisioWorkingViewMetadata(null, "fixture-build-17", Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC));
    }

    private static void assertServerContext(Map<String, VisioProperty> properties) {
        assertThat(properties.get("taxonomy.generatorName").value()).isEqualTo("Taxonomy Architecture Analyzer");
        assertThat(properties.get("taxonomy.generatorVersion").value()).isEqualTo("fixture-build-17");
        assertThat(properties.get("taxonomy.exportedBy").value()).isEqualTo("server-exporter");
        assertThat(properties.get("taxonomy.exportedAt").value()).isEqualTo("2026-10-27T09:00:00Z");
        assertThat(properties.get("taxonomy.requirementUrlStatus").value()).contains("working view", "no saved requirement");
        assertThat(properties.get("taxonomy.requirementTextProvenance").value()).contains("working source", "no saved requirement version");
        assertThat(properties).doesNotContainKeys("taxonomy.requirementUrl", "taxonomy.requirementVersionCreatedBy",
                "taxonomy.requirementVersionCreatedAt", "taxonomy.requirementVersionId");
    }

    @Test
    void originalWorkingSourceIsIncludedWithoutTrustingClientAuthority() throws Exception {
        var converter = mock(VisioDiagramService.class);
        var builder = mock(VisioPackageBuilder.class);
        when(converter.convert(any(), any())).thenReturn(new VisioDocument());
        when(builder.build(any())).thenReturn(new byte[]{1});
        var adapter = new VisioExportExtension(converter, builder, contextService());
        var diagram = new DiagramModel("Original graph", List.of(), List.of(), null);

        adapter.export(new ExportContext(diagram, Map.of(
                "originalRequirementText", "Original submitted text\nÜnicode source.",
                "generatorName", "Forged generator", "exportedBy", "Forged editor",
                "requirementUrl", "https://forged.invalid/requirement")));

        var metadata = ArgumentCaptor.forClass(VisioExportMetadata.class);
        verify(converter).convert(same(diagram), metadata.capture());
        assertServerContext(metadata.getValue().document());
        assertThat(metadata.getValue().document()).containsKey("taxonomy.requirementText");
        assertThat(metadata.getValue().document().get("taxonomy.requirementText").value())
                .isEqualTo("Original submitted text\nÜnicode source.");
        assertThat(metadata.getValue().document()).doesNotContainKeys("taxonomy.requirementUrl",
                "taxonomy.requirementVersionCreatedBy", "taxonomy.requirementVersionCreatedAt");
        assertThat(metadata.getValue().document().values()).noneMatch(value -> value.value().startsWith("Forged"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"visio", "VISIO", "ViSiO"})
    void analysisDrivenRegistryRouteUsesTheSubmittedRequirementRatherThanASpoofedOption(String formatId) throws Exception {
        var converter = mock(VisioDiagramService.class);
        var builder = mock(VisioPackageBuilder.class);
        when(converter.convert(any(), any())).thenReturn(new VisioDocument());
        when(builder.build(any())).thenReturn(new byte[]{1});
        var adapter = new VisioExportExtension(converter, builder, contextService());
        var facade = mock(ExportFacade.class);
        var diagram = new DiagramModel("Original graph", List.of(), List.of(), null);
        when(facade.buildDiagram("Exact submitted requirement")).thenReturn(diagram);
        var controller = new ExportApiController(facade, new ExportFormatExtensionRegistry(List.of(adapter)));

        var response = controller.exportByFormat(formatId, Map.of("businessText", "Exact submitted requirement",
                "originalRequirementText", "Later edited draft", "requirementUrl", "https://forged.invalid/"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var metadata = ArgumentCaptor.forClass(VisioExportMetadata.class);
        verify(converter).convert(same(diagram), metadata.capture());
        assertServerContext(metadata.getValue().document());
        assertThat(metadata.getValue().document()).containsKey("taxonomy.requirementText");
        assertThat(metadata.getValue().document().get("taxonomy.requirementText").value()).isEqualTo("Exact submitted requirement");
        assertThat(metadata.getValue().document()).doesNotContainKey("taxonomy.requirementUrl");
        verify(facade, times(1)).buildDiagram("Exact submitted requirement");
    }

    @Test
    void legacyVisioRouteIncludesTheExactSourceWithoutCreatingPersistentRequirementAuthority() throws Exception {
        var converter = mock(VisioDiagramService.class);
        var builder = mock(VisioPackageBuilder.class);
        when(converter.convert(any(), any())).thenReturn(new VisioDocument());
        when(builder.build(any())).thenReturn(new byte[]{1});
        var analysis = mock(LlmService.class);
        var architecture = mock(RequirementArchitectureViewService.class);
        String source = "  Original submitted source\nUnicode: Österreich.  ";
        var result = new AnalysisResult(Map.of("CP", 80d), List.of());
        var view = new RequirementArchitectureView();
        var node = new RequirementElementView();
        node.setNodeCode("CP"); node.setTitle("Capability"); node.setTaxonomySheet("CP"); node.setRelevance(.8);
        view.setIncludedElements(List.of(node));
        when(analysis.analyzeWithBudget(source)).thenReturn(result);
        when(architecture.build(result.getScores(), source, 20)).thenReturn(view);
        var facade = new ExportFacade(analysis, architecture, new DiagramProjectionService(), converter, builder,
                null, null, null, null, contextService());
        var controller = new ExportApiController(facade, new ExportFormatExtensionRegistry(List.of()));

        var response = controller.exportVisio(Map.of("businessText", source, "exportedBy", "Forged editor"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var metadata = ArgumentCaptor.forClass(VisioExportMetadata.class);
        verify(converter).convert(any(), metadata.capture());
        assertServerContext(metadata.getValue().document());
        assertThat(metadata.getValue().document()).containsKey("taxonomy.requirementText");
        assertThat(metadata.getValue().document().get("taxonomy.requirementText").value()).isEqualTo(source);
        assertThat(metadata.getValue().document()).doesNotContainKeys("taxonomy.requirementUrl", "taxonomy.requirementVersionCreatedBy");
        verify(analysis, times(1)).analyzeWithBudget(source);
    }
    @ParameterizedTest
    @CsvSource({"false,visio", "true,visio", "true,VISIO", "true,ViSiO"})
    void currentRouteRetainsOptionalCapturedSourceAndServerContextWithoutAnalysis(boolean hasSource, String formatId) throws Exception {
        var converter = mock(VisioDiagramService.class);
        var builder = mock(VisioPackageBuilder.class);
        when(converter.convert(any(), any())).thenReturn(new VisioDocument());
        when(builder.build(any())).thenReturn(new byte[]{1});
        var adapter = new VisioExportExtension(converter, builder, contextService());
        // No analysis or architecture derivation service exists: accidental reanalysis fails.
        var facade = new ExportFacade(null, null, new DiagramProjectionService(), null, null, null, null, null, null);
        var controller = new ExportApiController(facade, new ExportFormatExtensionRegistry(List.of(adapter)));
        var view = new RequirementArchitectureView();
        var node = new RequirementElementView();
        node.setNodeCode("CP"); node.setTitle("Existing capability"); node.setTaxonomySheet("CP"); node.setRelevance(.8);
        node.setIncludedBecause("Original explanation");
        view.setIncludedElements(List.of(node));
        String source = hasSource ? "  Captured original source\nÜnicode preserved.  " : null;
        view.setOriginalRequirementText(source);
        var expectedGraph = facade.buildCurrentDiagram(view);

        var response = controller.exportCurrentDiagram(formatId, view);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var metadata = ArgumentCaptor.forClass(VisioExportMetadata.class);
        verify(converter).convert(eq(expectedGraph), metadata.capture());
        assertServerContext(metadata.getValue().document());
        if (hasSource) assertThat(metadata.getValue().document().get("taxonomy.requirementText"))
                .isEqualTo(VisioProperty.text(source));
        else assertThat(metadata.getValue().document()).doesNotContainKey("taxonomy.requirementText");
        assertThat(metadata.getValue().elements().get("CP").get("taxonomy.presenceReason").value()).isEqualTo("Original explanation");
        assertThat(metadata.getValue().losses()).anyMatch(loss -> loss.field().equals("snapshotAuthority"));
        assertThat(view.getOriginalRequirementText()).isEqualTo(source);
        assertThat(facade.buildCurrentDiagram(view)).isEqualTo(expectedGraph);
    }

    @Test
    void anonymousSecurityContextCannotBecomeTheExportingEditor() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("fixture-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var converter = mock(VisioDiagramService.class);
        var builder = mock(VisioPackageBuilder.class);
        when(converter.convert(any(), any())).thenReturn(new VisioDocument());
        when(builder.build(any())).thenReturn(new byte[]{1});
        var adapter = new VisioExportExtension(converter, builder, contextService());

        adapter.export(new ExportContext(new DiagramModel("Graph", List.of(), List.of(), null),
                Map.of("exportedBy", "Forged editor", "generatorVersion", "Forged version")));

        var metadata = ArgumentCaptor.forClass(VisioExportMetadata.class);
        verify(converter).convert(any(), metadata.capture());
        assertThat(metadata.getValue().document()).doesNotContainKeys("taxonomy.exportedBy", "taxonomy.requirementText");
        assertThat(metadata.getValue().document().get("taxonomy.generatorVersion").value()).isEqualTo("fixture-build-17");
        assertThat(metadata.getValue().document().get("taxonomy.exportedAt").value()).isEqualTo("2026-10-27T09:00:00Z");
    }

}
