package com.taxonomy.export.controller;

import com.taxonomy.analysis.service.SavedAnalysisService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.export.service.ExportFacade;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Exercises the real import/export boundary, not just DTO self-consistency. */
public final class RecoveryCatalogueExchangeProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void verify(String direction, boolean fabricated, boolean legacy) throws Exception {
        var catalogue = new TaxonomyService(null, null, null) {
            @Override public TaxonomyNode getNodeByCode(String code) {
                if (!Set.of("BP", "IP").contains(code)) return null;
                var node = new TaxonomyNode(); node.setCode(code); return node;
            }
        };
        var service = new SavedAnalysisService(MAPPER, catalogue);
        var facade = new ExportFacade(null,null,null,null,null,null,null,null,null,service);
        var controller = new ExportApiController(facade,null);
        var field = ExportApiController.class.getDeclaredField("recoveryObjectMapper"); field.setAccessible(true); field.set(controller,MAPPER);
        String unknown = fabricated ? "NOT_IN_THE_OFFICIAL_CATALOGUE" : "IP";
        var saved = new SavedAnalysis(); saved.setVersion(legacy ? 2 : 3); saved.setRequirement("Hospital requirement"); saved.setProvider("MOCK");
        saved.setScores(Map.of("BP",70));
        if (!legacy) {
            saved.setRawScores(Map.of("BP",70)); saved.setAnalysisStatus("PARTIAL");
            saved.setAnalysisCoverage(new AnalysisCoverage(Map.of(
                "BP", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.RELEVANT,70,70,AnalysisCoverage.Descendants.COMPLETE,null),
                unknown, new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN,null,null,AnalysisCoverage.Descendants.UNASSESSED,"LEFT_OPEN:q")
            ),1,1,1));
        } else saved.setScores(Map.of(unknown, 70));
        var response = "import".equals(direction) ? controller.importScores(MAPPER.writeValueAsString(saved))
                : controller.exportScores(MAPPER.convertValue(saved, new tools.jackson.core.type.TypeReference<Map<String,Object>>() {}));
        int expected = fabricated && !legacy ? 400 : 200;
        check(response.getStatusCode().value() == expected, direction + " accepted fabricated coverage or rejected compatible evidence: " + response.getStatusCode());
        if (expected == 200 && !legacy) {
            var body = MAPPER.valueToTree(response.getBody());
            check(body.path("analysisCoverage").path("nodes").has("IP"), "Real unknown scope was lost");
            check(!body.path("scores").has("IP"), "Unassessed scope became a zero score");
        }
    }
    public static void selectedScopeRoundTrip() throws Exception {
        var catalogue = new TaxonomyService(null, null, null) {
            @Override public Set<String> getRootCodes() { return Set.of("BP"); }
            @Override public TaxonomyNode getNodeByCode(String code) {
                if (!"BP".equals(code)) return null;
                var node = new TaxonomyNode(); node.setCode(code); node.setTaxonomyRoot("BP"); return node;
            }
        };
        var service = new SavedAnalysisService(MAPPER, catalogue);
        var facade = new ExportFacade(null,null,null,null,null,null,null,null,null,service);
        var controller = new ExportApiController(facade,null);
        var field = ExportApiController.class.getDeclaredField("recoveryObjectMapper");
        field.setAccessible(true); field.set(controller, MAPPER);
        var selected = new AnalysisScope(Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY);
        var saved = new SavedAnalysis(); saved.setVersion(3); saved.setRequirement("requirement"); saved.setProvider("MOCK");
        saved.setScores(Map.of("BP",70)); saved.setRawScores(Map.of("BP",70)); saved.setAnalysisStatus("SUCCESS");
        saved.setAnalysisScope(selected);
        saved.setAnalysisCoverage(new AnalysisCoverage(Map.of("BP", new AnalysisCoverage.NodeAssessment(
                AnalysisCoverage.State.RELEVANT,70,70,AnalysisCoverage.Descendants.COMPLETE,null)),1,0,0));
        var exported = controller.exportScores(MAPPER.convertValue(saved, new tools.jackson.core.type.TypeReference<Map<String,Object>>() {}));
        check(exported.getStatusCode().value() == 200, "Selected evidence export failed");
        var exportedBody = MAPPER.valueToTree(exported.getBody());
        check(MAPPER.treeToValue(exportedBody.path("analysisScope"), AnalysisScope.class).equals(selected), "Export lost selected scope");
        var imported = controller.importScores(MAPPER.writeValueAsString(exported.getBody()));
        check(imported.getStatusCode().value() == 200, "Selected evidence import failed");
        var importedBody = MAPPER.valueToTree(imported.getBody());
        check(MAPPER.treeToValue(importedBody.path("analysisScope"), AnalysisScope.class).equals(selected), "Import lost selected scope");
        check(importedBody.path("analysisCoverage").path("nodes").size() == 1
                && importedBody.path("analysisCoverage").path("nodes").has("BP"), "Exchange broadened assessment coverage");
    }
    public static void main(String[] args) throws Exception { verify(args[0], Boolean.parseBoolean(args[1]), Boolean.parseBoolean(args[2])); System.out.println("PASS " + String.join(" ",args)); }
}
