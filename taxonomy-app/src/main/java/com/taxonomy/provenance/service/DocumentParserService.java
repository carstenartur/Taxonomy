package com.taxonomy.provenance.service;

import com.taxonomy.dto.DocumentParseResult;
import com.taxonomy.dto.RequirementCandidate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Parses uploaded PDF and DOCX documents into requirement candidates.
 *
 * <p>This first-stage parser performs:
 * <ul>
 *   <li>Raw text extraction</li>
 *   <li>Section/heading detection</li>
 *   <li>Requirement candidate splitting (paragraph-based)</li>
 * </ul>
 *
 * <p>It does <em>not</em> attempt to fully interpret legal semantics.
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    /** Minimum length for a paragraph to be considered a requirement candidate. */
    private static final int MIN_CANDIDATE_LENGTH = 40;

    /** Maximum length for a single candidate text. */
    private static final int MAX_CANDIDATE_LENGTH = 2000;

    /** Maximum characters for the raw text preview. */
    private static final int RAW_TEXT_PREVIEW_LENGTH = 2000;

    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Pattern to detect likely section headings. */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:§\\s*\\d+|Art\\.?\\s*\\d+|\\d+\\.\\d*\\s+[A-ZÄÖÜ]|[IVXLCDM]+\\.\\s+|Kapitel\\s+\\d+|" +
            "Chapter\\s+\\d+|Section\\s+\\d+|Abschnitt\\s+\\d+|Artikel\\s+\\d+).*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parses an uploaded document and extracts requirement candidates.
     *
     * @param file the uploaded PDF or DOCX file
     * @return the parse result with candidates
     * @throws IOException if the file cannot be read
     */
    public DocumentParseResult parse(MultipartFile file) throws IOException {
        String contentType = detectMimeType(file);
        String rawText;
        int pageCount;

        if (MIME_PDF.equals(contentType)) {
            try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
                pageCount = pdf.getNumberOfPages();
                PDFTextStripper stripper = new PDFTextStripper();
                rawText = stripper.getText(pdf);
            }
        } else if (MIME_DOCX.equals(contentType)) {
            try (InputStream in = file.getInputStream();
                 XWPFDocument doc = new XWPFDocument(in)) {
                pageCount = 1;
                try {
                    int p = doc.getProperties().getExtendedProperties()
                            .getUnderlyingProperties().getPages();
                    if (p > 0) pageCount = p;
                } catch (Exception ignored) {
                    // Page count unavailable in minimal DOCX files
                }
                rawText = extractDocxText(doc);
            }
        } else {
            throw new IOException("Unsupported file type: " + contentType
                    + ". Only PDF and DOCX files are supported.");
        }

        List<RequirementCandidate> candidates = extractCandidates(rawText);

        DocumentParseResult result = new DocumentParseResult();
        result.setFileName(file.getOriginalFilename());
        result.setMimeType(contentType);
        result.setTotalPages(pageCount);
        result.setRawTextPreview(rawText.length() > RAW_TEXT_PREVIEW_LENGTH
                ? rawText.substring(0, RAW_TEXT_PREVIEW_LENGTH) + "…"
                : rawText);
        result.setCandidates(candidates);
        result.setWarnings(new ArrayList<>());

        if (candidates.isEmpty()) {
            result.getWarnings().add("No requirement candidates were extracted from this document.");
        }

        log.info("Parsed document '{}': {} pages, {} candidates extracted",
                file.getOriginalFilename(), pageCount, candidates.size());

        return result;
    }

    /**
     * Computes a SHA-256 content hash for deduplication.
     */
    public String computeContentHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private String detectMimeType(MultipartFile file) {
        String ct = file.getContentType();
        if (ct != null && !ct.isBlank()) {
            if (ct.contains("pdf")) return MIME_PDF;
            if (ct.contains("wordprocessingml") || ct.contains("docx")) return MIME_DOCX;
        }
        String name = file.getOriginalFilename();
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".pdf")) return MIME_PDF;
            if (lower.endsWith(".docx")) return MIME_DOCX;
        }
        return ct != null ? ct : "application/octet-stream";
    }

    private String extractDocxText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph para : doc.getParagraphs()) {
            String text = para.getText();
            if (text != null && !text.isBlank()) {
                sb.append(text).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * Splits raw text into requirement candidates based on paragraph boundaries
     * and heading detection.
     */
    public List<RequirementCandidate> extractCandidates(String rawText) {
        List<RequirementCandidate> candidates = new ArrayList<>();
        String[] paragraphs = rawText.split("\\n\\s*\\n");
        String currentHeading = null;
        int index = 0;

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            // Detect headings
            if (isLikelyHeading(trimmed)) {
                currentHeading = trimmed.length() > 200
                        ? trimmed.substring(0, 200) : trimmed;
                continue;
            }

            // Skip very short paragraphs (page numbers, headers, footers)
            if (trimmed.length() < MIN_CANDIDATE_LENGTH) continue;

            // Truncate very long paragraphs
            String candidateText = trimmed.length() > MAX_CANDIDATE_LENGTH
                    ? trimmed.substring(0, MAX_CANDIDATE_LENGTH) + "…"
                    : trimmed;

            candidates.add(new RequirementCandidate(index++, currentHeading, candidateText, null));
        }

        return candidates;
    }

    private boolean isLikelyHeading(String text) {
        // Short text with all caps or matching heading patterns
        if (text.length() > 200) return false;
        if (HEADING_PATTERN.matcher(text).matches()) return true;
        // All-caps short lines are likely headings
        if (text.length() < 80 && text.equals(text.toUpperCase()) && text.matches(".*[A-ZÄÖÜ].*")) {
            return true;
        }
        return false;
    }
}
