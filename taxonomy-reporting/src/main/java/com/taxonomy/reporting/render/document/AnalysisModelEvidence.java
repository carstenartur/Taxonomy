package com.taxonomy.reporting.render.document;

import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.reporting.render.decision.DecisionReportLabels;

import java.util.ArrayList;
import java.util.List;

/** Human-readable version evidence shared by the existing retained report renderers. */
public final class AnalysisModelEvidence {
    private AnalysisModelEvidence() {}

    static List<List<String>> rows(ArchitectureReport report, DecisionReportLabels labels) {
        List<List<String>> rows = new ArrayList<>();
        if (report.getRequirementVersionId() != null) {
            rows.add(List.of(labels.requirementVersion() + " (ID)", report.getRequirementVersionId().toString()));
        }
        rows.addAll(rows(report.getModelBinding(), report.getAnalysisScope(), labels));
        return rows;
    }

    public static List<List<String>> rows(AnalysisModelBinding binding, AnalysisScope actualScope, DecisionReportLabels labels) {
        if (binding == null) return List.of();
        List<List<String>> rows = new ArrayList<>();
        boolean de = labels.german();
        rows.add(List.of(de ? "Stand bei Analysestart" : "State at analysis start", switch (binding.state()) {
            case RESOLVED -> de ? "Versionen aufgelöst" : "Versions resolved";
            case MODEL_UNCOMMITTED -> de ? "Modell ohne Git-Commit" : "Model without a Git commit";
            case CATALOGUE_UNRESOLVED -> de ? "Katalogzuordnung ungeklärt" : "Catalogue assignment unresolved";
            case BRIEF_UNASSIGNED -> de ? "Kein Architekturauftrag zugeordnet" : "No architecture brief assigned";
            case PROFILE_UNRESOLVED -> de ? "Profilzuordnung ungeklärt" : "Profile assignment unresolved";
        }));
        rows.add(List.of(de ? "Modellquelle" : "Model source", binding.modelSource()));

        rows.add(List.of(labels.repository(), binding.repositoryId()));
        rows.add(List.of(labels.workspace(), binding.workspaceScopeKey()));
        rows.add(List.of(labels.branch(), binding.branch()));
        rows.add(List.of(labels.basedOnCommit(), recorded(binding.commit(), labels)));
        rows.add(List.of(de ? "Modellrevision" : "Model revision", recorded(binding.semanticRevision(), labels)));
        rows.add(List.of(de ? "SHA-256 des Architekturmodells" : "Architecture model SHA-256", binding.modelSha256()));
        rows.add(List.of(de ? "Katalogzuordnung im Projekt" : "Catalogue assigned to the project",
                recorded(binding.catalogueRevision(), labels)));
        rows.add(List.of(de ? "Ausgeführte Kataloggeneration" : "Catalogue used for analysis",
                recorded(binding.executionCatalogueRevision(), labels)));
        rows.add(List.of(de ? "Version der Katalogdefinition" : "Catalogue definition version",
                recorded(binding.catalogueDefinitionVersion(), labels)));
        rows.add(List.of(de ? "Version des Architekturauftrags" : "Architecture brief version",
                recorded(binding.briefVersion(), labels)));
        rows.add(List.of(de ? "Beauftragter Analyseumfang" : "Requested analysis scope", scope(binding.scope(), labels)));
        if (actualScope != null) {
            rows.add(List.of(de ? "Ausgeführter Analyseumfang" : "Executed analysis scope",
                    scope(actualScope, labels)));
        }
        if (!binding.rootCodes().isEmpty()) {
            rows.add(List.of(de ? "Erfasste Katalogwurzeln" : "Recorded catalogue roots",
                    String.join(", ", binding.rootCodes())));
        }
        for (var ref : binding.references()) {
            var version = new ArrayList<String>();
            version.add(ref.sourceId());
            version.add(recorded(ref.sourceVersionId(), labels));
            if (ref.sourceFragmentId() != null) version.add(ref.sourceFragmentId());
            for (String field : List.of("identifier", "edition", "section", "scope")) {
                if (ref.values().containsKey(field)) version.add(ref.values().get(field));
            }
            rows.add(List.of((de ? "Profilreferenz " : "Profile reference ") + ref.id(), String.join(" / ", version)));
        }
        if (binding.profileProblem() != null) {
            rows.add(List.of(de ? "Profilstatus" : "Profile status", binding.profileProblem()));
        }
        return rows;
    }

    private static String recorded(Object value, DecisionReportLabels labels) {
        return value == null ? labels.unknown() : value.toString();
    }

    private static String scope(AnalysisScope scope, DecisionReportLabels labels) {
        String mode = scope.mode() == AnalysisMode.TAXONOMIES_ONLY
                ? (labels.german() ? "Nur Taxonomien" : "Taxonomies only")
                : (labels.german() ? "Taxonomien und Beziehungen" : "Taxonomies and relations");
        String roots = scope.taxonomyRoots().isEmpty()
                ? (labels.german() ? "alle Katalogwurzeln" : "all catalogue roots")
                : String.join(", ", scope.taxonomyRoots());
        return mode + " (" + roots + ")";
    }
}
