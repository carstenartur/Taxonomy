package com.taxonomy.portfolio.service;

/** Presentation of a saved snapshot after its recorded provider has been classified. */
public enum SnapshotExportLabel {
    STANDARD("", ""), DEMO("DEMO · ", "DEMO-");

    private final String titlePrefix;
    private final String filePrefix;

    SnapshotExportLabel(String titlePrefix, String filePrefix) {
        this.titlePrefix = titlePrefix;
        this.filePrefix = filePrefix;
    }

    public static SnapshotExportLabel forReplay(boolean replay) {
        return replay ? DEMO : STANDARD;
    }

    public String title(String value) { return titlePrefix + value; }
    public String fileName(String value) { return filePrefix + value; }
}
