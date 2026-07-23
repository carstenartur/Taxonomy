package com.taxonomy.provenance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Single source of truth for document upload, expansion, and LLM input limits. */
@Component
@ConfigurationProperties(prefix = "taxonomy.limits.document")
public class DocumentImportLimits {

    private long maxUploadBytes = 50L * 1024 * 1024;
    private int maxPdfPages = 500;
    private int maxExtractedCharacters = 1_000_000;
    private int maxCandidates = 2_000;
    private int maxLlmCharacters = 200_000;
    private long maxDocxEntryBytes = 64L * 1024 * 1024;
    private long maxDocxTextBytes = 128L * 1024 * 1024;
    private double minDocxInflateRatio = 0.01d;

    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }

    public int getMaxPdfPages() { return maxPdfPages; }
    public void setMaxPdfPages(int maxPdfPages) { this.maxPdfPages = maxPdfPages; }

    public int getMaxExtractedCharacters() { return maxExtractedCharacters; }
    public void setMaxExtractedCharacters(int maxExtractedCharacters) {
        this.maxExtractedCharacters = maxExtractedCharacters;
    }

    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }

    public int getMaxLlmCharacters() { return maxLlmCharacters; }
    public void setMaxLlmCharacters(int maxLlmCharacters) { this.maxLlmCharacters = maxLlmCharacters; }

    public long getMaxDocxEntryBytes() { return maxDocxEntryBytes; }
    public void setMaxDocxEntryBytes(long maxDocxEntryBytes) { this.maxDocxEntryBytes = maxDocxEntryBytes; }

    public long getMaxDocxTextBytes() { return maxDocxTextBytes; }
    public void setMaxDocxTextBytes(long maxDocxTextBytes) { this.maxDocxTextBytes = maxDocxTextBytes; }

    public double getMinDocxInflateRatio() { return minDocxInflateRatio; }
    public void setMinDocxInflateRatio(double minDocxInflateRatio) {
        this.minDocxInflateRatio = minDocxInflateRatio;
    }
}