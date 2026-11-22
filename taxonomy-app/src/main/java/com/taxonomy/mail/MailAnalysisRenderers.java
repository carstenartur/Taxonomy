package com.taxonomy.mail;

import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.reporting.api.decision.DecisionRationaleReportPlugin;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.taxonomy.mail.MailAnalysisCommand.Format;
import static com.taxonomy.mail.MailAnalysisCommand.Mode;

/** Uses the application's live renderer catalogue for both command admission and export. */
@Component
@ConditionalOnProperty(name = "taxonomy.mail.enabled", havingValue = "true")
@ConditionalOnFeature({"analysis", "architecture", "reporting"})
final class MailAnalysisRenderers {
    private final ExportFormatExtensionRegistry diagrams;
    private final ReportRendererRegistry reports;

    MailAnalysisRenderers(ExportFormatExtensionRegistry diagrams, ReportRendererRegistry reports) {
        this.diagrams = diagrams;
        this.reports = reports;
    }

    Optional<ExportFormatExtension> diagram(Format format) {
        return diagrams.findByFormatId(format.architectureId());
    }

    Optional<ReportRendererExtension> report(Mode mode, Format format) {
        return mode == Mode.ARCHITECTURE ? reports.findByFormatId(format.architectureId())
                : reports.findByFormatId(DecisionRationaleReportPlugin.REPORT_TYPE_ID, format.decisionId());
    }

    record AvailableFormats(Set<Format> architecture, Set<Format> decision) {
        AvailableFormats {
            architecture = Set.copyOf(architecture);
            decision = Set.copyOf(decision);
        }

        Set<Format> forMode(Mode mode) { return mode == Mode.ARCHITECTURE ? architecture : decision; }

        String decisionDefault() {
            if (decision.contains(Format.HTML)) return Format.HTML.name();
            return decision.stream().sorted().findFirst().map(Format::name).orElse("UNSUPPORTED");
        }
    }

    AvailableFormats available() {
        Set<String> diagramIds = diagrams.listDescriptors().stream()
                .map(ExportFormatDescriptor::id).collect(Collectors.toSet());
        Set<String> reportIds = reports.listDescriptors().stream()
                .map(ReportFormatDescriptor::id).collect(Collectors.toSet());
        Set<String> decisionIds = reports.listDescriptors(DecisionRationaleReportPlugin.REPORT_TYPE_ID).stream()
                .map(ReportFormatDescriptor::id).collect(Collectors.toSet());
        var architecture = EnumSet.noneOf(Format.class);
        var decision = EnumSet.noneOf(Format.class);
        for (Format format : Format.values()) {
            boolean available = switch (format.architectureRoute()) {
                case ARCHITECTURE_PDF -> true;
                case DIAGRAM -> diagramIds.contains(format.architectureId());
                case REPORT -> reportIds.contains(format.architectureId());
            };
            if (available) architecture.add(format);
            if (format.decisionId() != null && decisionIds.contains(format.decisionId())) decision.add(format);
        }
        return new AvailableFormats(architecture, decision);
    }
}
