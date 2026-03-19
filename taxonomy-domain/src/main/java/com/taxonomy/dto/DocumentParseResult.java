package com.taxonomy.dto;

import java.util.List;

/**
 * Result of parsing an uploaded document (PDF/DOCX) into requirement
 * candidates.
 */
public class DocumentParseResult {

    private String fileName;
    private String mimeType;
    private int totalPages;
    private int totalCandidates;
    private String rawTextPreview;
    private Long sourceArtifactId;
    private Long sourceVersionId;
    private List<RequirementCandidate> candidates;
    private List<String> warnings;

    public DocumentParseResult() {}

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public int getTotalCandidates() { return totalCandidates; }
    public void setTotalCandidates(int totalCandidates) { this.totalCandidates = totalCandidates; }

    public String getRawTextPreview() { return rawTextPreview; }
    public void setRawTextPreview(String rawTextPreview) { this.rawTextPreview = rawTextPreview; }

    public Long getSourceArtifactId() { return sourceArtifactId; }
    public void setSourceArtifactId(Long sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }

    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }

    public List<RequirementCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<RequirementCandidate> candidates) {
        this.candidates = candidates;
        this.totalCandidates = candidates != null ? candidates.size() : 0;
    }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
