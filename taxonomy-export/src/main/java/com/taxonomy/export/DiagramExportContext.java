package com.taxonomy.export;

import com.taxonomy.diagram.DiagramCanonicalEvidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable display-only source context shared by standalone SVG and PDF exports. */
public record DiagramExportContext(List<String> header, List<String> warnings) {
    public DiagramExportContext {
        header = List.copyOf(header);
        warnings = List.copyOf(warnings);
    }

    public static DiagramExportContext snapshot(ArchitecturePdfRenderer.SnapshotMetadata source, Locale locale) {
        Objects.requireNonNull(source, "snapshot metadata");
        var language = DiagramLanguage.from(locale);
        var lines = new ArrayList<String>();
        lines.add(language.text("Project ", "Projekt ") + safe(source.projectKey())
                + language.text(" · Requirement ", " · Anforderung ") + safe(source.requirementKey())
                + " · Snapshot " + safe(source.snapshotId()));
        lines.add(language.text("Provider ", "Anbieter ") + safe(source.provider())
                + " · Branch " + safe(source.branchName()));
        lines.add("Commit " + safe(source.commitSha()));
        if (source.analysisStatus() != null && !source.analysisStatus().isBlank())
            lines.add(language.text("Recorded analysis status: ", "Gespeicherter Analysestatus: ")
                    + source.analysisStatus());
        return new DiagramExportContext(lines, source.warnings().stream()
                .map(value -> GeneratedDiagnosticText.display(value, language.german())).toList());
    }

    public static DiagramExportContext canonical(String provenance, DiagramCanonicalEvidence evidence, Locale locale) {
        Objects.requireNonNull(evidence, "canonical evidence");
        var language = DiagramLanguage.from(locale);
        var lines = new ArrayList<String>();
        if (provenance != null && !provenance.isBlank()) lines.addAll(provenance.lines().toList());
        String scope = evidence.viewId() == null || evidence.viewId().isBlank()
                ? language.text("Scope: canonical workspace", "Umfang: kanonischer Arbeitsbereich")
                : language.text("Scope: canonical view ", "Umfang: kanonische Sicht ") + evidence.viewId();
        long partial = evidence.analysisSources().stream().filter(source -> "PARTIAL".equals(source.status())).count();
        if (partial != 0) scope += language.text(" · PARTIAL analysis sources: ", " · Analysequellen mit Status PARTIAL: ") + partial;
        lines.add(scope);
        return new DiagramExportContext(lines, List.of());
    }

    /** Resolve exact generated aliases only; authored titles and unknown keys remain verbatim. */
    public static String title(String title, Locale locale) {
        if (title == null) return null;
        var language = DiagramLanguage.from(locale);
        return switch (title) {
            case "archview.policy.title.defaultImpact" -> language.text("Architecture Impact View", "Architektur-Wirkungsansicht");
            case "archview.policy.title.leafOnly" -> language.text("Leaf-Only Showcase View", "Nur-Blatt-Präsentationsansicht");
            case "archview.policy.title.clustering" -> language.text("Clustered Impact View", "Gruppierte Wirkungsansicht");
            case "archview.policy.title.trace" -> language.text("Scoring Trace", "Scoring-Nachverfolgung");
            default -> title;
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }
}
