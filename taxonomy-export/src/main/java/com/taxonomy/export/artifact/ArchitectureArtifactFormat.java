package com.taxonomy.export.artifact;

/** Supported payload formats for a snapshot-bound architecture artifact. */
public enum ArchitectureArtifactFormat {

    MERMAID(
            "text/plain;charset=UTF-8",
            "architecture.mmd",
            ".mmd"),
    JSON(
            "application/json",
            "architecture.json",
            ".json");

    private final String mediaType;
    private final String defaultFileName;
    private final String fileSuffix;

    ArchitectureArtifactFormat(
            String mediaType,
            String defaultFileName,
            String fileSuffix) {
        this.mediaType = mediaType;
        this.defaultFileName = defaultFileName;
        this.fileSuffix = fileSuffix;
    }

    public String mediaType() {
        return mediaType;
    }

    public String defaultFileName() {
        return defaultFileName;
    }

    public String fileSuffix() {
        return fileSuffix;
    }
}
