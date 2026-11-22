package com.taxonomy.mail;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validated options for running a mail request through the normal analysis pipeline. */
public record MailAnalysisCommand(Mode mode, List<String> taxonomyCodes, List<Format> exports) {
    public MailAnalysisCommand {
        Objects.requireNonNull(mode);
        taxonomyCodes = List.copyOf(taxonomyCodes);
        exports = List.copyOf(exports);
        if ((mode == Mode.ARCHITECTURE) != taxonomyCodes.isEmpty()
                || taxonomyCodes.stream().anyMatch(String::isBlank) || Set.copyOf(taxonomyCodes).size() != taxonomyCodes.size()
                || exports.isEmpty() || Set.copyOf(exports).size() != exports.size()
                || mode == Mode.DECISION_TREE && exports.stream().anyMatch(format -> format.decisionId() == null)) {
            throw new IllegalArgumentException("Invalid email analysis scope or exports");
        }
    }

    public enum Mode { ARCHITECTURE, DECISION_TREE }

    enum ArchitectureRoute { ARCHITECTURE_PDF, DIAGRAM, REPORT }

    /** A subject uses the same format name in either mode, even when the renderer families differ. */
    public enum Format {
        PDF(ArchitectureRoute.ARCHITECTURE_PDF, "visio-pdf"),
        SVG(ArchitectureRoute.DIAGRAM, null),
        VISIO(ArchitectureRoute.DIAGRAM, "visio"),
        ARCHIMATE(ArchitectureRoute.DIAGRAM, null),
        STRUCTURIZR(ArchitectureRoute.DIAGRAM, null),
        MERMAID(ArchitectureRoute.DIAGRAM, null),
        SPARX(ArchitectureRoute.DIAGRAM, null),
        DOCX(ArchitectureRoute.REPORT, "docx"),
        HTML(ArchitectureRoute.REPORT, "html"),
        MARKDOWN(ArchitectureRoute.REPORT, null),
        JSON(ArchitectureRoute.REPORT, "json");

        private final ArchitectureRoute architectureRoute;
        private final String decisionId;

        Format(ArchitectureRoute architectureRoute, String decisionId) {
            this.architectureRoute = architectureRoute;
            this.decisionId = decisionId;
        }

        ArchitectureRoute architectureRoute() { return architectureRoute; }
        String architectureId() { return name().toLowerCase(Locale.ROOT); }
        String decisionId() { return decisionId; }
    }
}
