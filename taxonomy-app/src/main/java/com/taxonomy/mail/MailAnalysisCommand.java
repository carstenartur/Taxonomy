package com.taxonomy.mail;

import java.util.List;

/** Validated options for running a mail request through the normal analysis pipeline. */
public record MailAnalysisCommand(Mode mode, List<String> taxonomyCodes, List<Format> exports) {
    public MailAnalysisCommand {
        taxonomyCodes = List.copyOf(taxonomyCodes);
        exports = List.copyOf(exports);
    }

    public enum Mode { ARCHITECTURE, DECISION_TREE }

    public enum Format { PDF, SVG, VISIO, ARCHIMATE, STRUCTURIZR, DOCX, HTML }
}
