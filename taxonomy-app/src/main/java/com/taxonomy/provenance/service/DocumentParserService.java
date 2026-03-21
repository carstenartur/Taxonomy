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
import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /** Pattern to detect [H1], [H2], etc. markers injected during DOCX extraction. */
    private static final Pattern H_MARKER_PATTERN = Pattern.compile(
            "^\\[H(\\d+)]\\s*(.+)$");

    /** Pattern to extract a trailing digit from a style ID (e.g. "Heading2" → 2). */
    private static final Pattern STYLE_LEVEL_PATTERN = Pattern.compile("\\d+");

    /** Represents a detected heading with its hierarchical level. */
    record HeadingMatch(int level, String text) {}

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
            if (text == null || text.isBlank()) continue;
            String styleId = para.getStyleID();
            if (styleId != null && isHeadingStyle(styleId)) {
                int level = extractHeadingLevel(styleId);
                sb.append("[H").append(level).append("] ").append(text).append("\n\n");
            } else {
                sb.append(text).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * Checks whether a DOCX style ID represents a heading style.
     * Matches English ("Heading1"), German ("Überschrift1" / "berschrift1"
     * where the umlaut may be stripped by some XML serialisers), and
     * common variations.
     */
    private static boolean isHeadingStyle(String styleId) {
        String lower = styleId.toLowerCase(Locale.ROOT);
        return lower.startsWith("heading")
                || lower.startsWith("berschrift")  // Ü stripped by some XML encodings
                || lower.startsWith("überschrift");
    }

    /**
     * Extracts the numeric heading level from a style ID.
     * "Heading2" → 2, "berschrift3" → 3, fallback → 1.
     */
    private static int extractHeadingLevel(String styleId) {
        Matcher m = STYLE_LEVEL_PATTERN.matcher(styleId);
        return m.find() ? Integer.parseInt(m.group()) : 1;
    }

    /**
     * Splits raw text into requirement candidates based on paragraph boundaries,
     * heading detection (including [H1]/[H2] markers from DOCX styles), and
     * hierarchical section-path tracking.
     */
    public List<RequirementCandidate> extractCandidates(String rawText) {
        List<RequirementCandidate> candidates = new ArrayList<>();
        String[] paragraphs = rawText.split("\\n\\s*\\n");
        Deque<Map.Entry<Integer, String>> headingStack = new ArrayDeque<>();
        String currentSectionPath = null;
        int index = 0;

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            // Detect headings (with level from [H] markers or regex heuristics)
            HeadingMatch heading = detectHeading(trimmed);
            if (heading != null) {
                // Pop all headings at the same or deeper level
                while (!headingStack.isEmpty() && headingStack.peek().getKey() >= heading.level()) {
                    headingStack.pop();
                }
                headingStack.push(Map.entry(heading.level(), heading.text()));
                currentSectionPath = buildSectionPath(headingStack);
                continue;
            }

            // Skip very short paragraphs (page numbers, headers, footers)
            if (trimmed.length() < MIN_CANDIDATE_LENGTH) continue;

            // Split at sentence boundaries instead of truncating
            if (trimmed.length() > MAX_CANDIDATE_LENGTH) {
                for (String sub : splitAtSentenceBoundaries(trimmed, MAX_CANDIDATE_LENGTH)) {
                    candidates.add(new RequirementCandidate(index++, currentSectionPath, sub, null));
                }
            } else {
                candidates.add(new RequirementCandidate(index++, currentSectionPath, trimmed, null));
            }
        }

        return candidates;
    }

    // ── Heading detection helpers ──────────────────────────────────────────────

    /**
     * Tries to detect a heading in the given text, returning a {@link HeadingMatch}
     * with level and clean text, or {@code null} if the text is not a heading.
     *
     * <p>Recognition order:
     * <ol>
     *   <li>[H1]/[H2] markers injected during DOCX style-based extraction</li>
     *   <li>Regex-based heading patterns (§, Chapter, Section, …)</li>
     *   <li>All-caps short lines</li>
     * </ol>
     */
    HeadingMatch detectHeading(String text) {
        if (text.length() > 200) return null;

        // 1. [H1], [H2], … markers from DOCX extraction
        Matcher hm = H_MARKER_PATTERN.matcher(text);
        if (hm.matches()) {
            int level = Integer.parseInt(hm.group(1));
            return new HeadingMatch(level, hm.group(2).strip());
        }

        // 2. Regex-based heading patterns with inferred level
        if (HEADING_PATTERN.matcher(text).matches()) {
            int level = inferRegexHeadingLevel(text);
            return new HeadingMatch(level, text);
        }

        // 3. All-caps short lines
        if (text.length() < 80 && text.equals(text.toUpperCase()) && text.matches(".*[A-ZÄÖÜ].*")) {
            return new HeadingMatch(1, text);
        }

        return null;
    }

    /**
     * Infers a hierarchical heading level from a regex-matched heading.
     * Chapter/Kapitel → 1, Section/Abschnitt → 2, numbered (X.Y) → dot-depth + 1,
     * everything else → 1.
     */
    private static int inferRegexHeadingLevel(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("chapter") || lower.startsWith("kapitel")) return 1;
        if (lower.startsWith("section") || lower.startsWith("abschnitt")) return 2;
        // Numbered sections: "3.2 …" → level 2, "3.2.1 …" → level 3
        if (text.matches("^\\d+\\.\\d+\\.\\d+.*")) return 3;
        if (text.matches("^\\d+\\.\\d+.*")) return 2;
        return 1;
    }

    /**
     * Builds a hierarchical section path from the heading stack.
     * E.g. "§ 3 Datenschutz > Abs. 2 Verarbeitung".
     *
     * <p>The stack is sorted by level because {@link ArrayDeque#stream()}
     * iterates head-to-tail (deepest level first), but we need the
     * shallowest level first in the output path.
     */
    private static String buildSectionPath(Deque<Map.Entry<Integer, String>> headingStack) {
        return headingStack.stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .collect(Collectors.joining(" > "));
    }

    // ── Sentence-boundary splitting ────────────────────────────────────────────

    /**
     * Splits text at sentence boundaries so that each resulting chunk is at most
     * {@code maxLen} characters long. This avoids the text loss caused by simple
     * truncation.
     */
    List<String> splitAtSentenceBoundaries(String text, int maxLen) {
        List<String> result = new ArrayList<>();
        BreakIterator sentIter = BreakIterator.getSentenceInstance(Locale.GERMAN);
        sentIter.setText(text);
        StringBuilder current = new StringBuilder();
        int start = sentIter.first();
        for (int end = sentIter.next(); end != BreakIterator.DONE; end = sentIter.next()) {
            String sentence = text.substring(start, end);
            if (current.length() + sentence.length() > maxLen && !current.isEmpty()) {
                result.add(current.toString().strip());
                current = new StringBuilder();
            }
            current.append(sentence);
            start = end;
        }
        if (!current.isEmpty()) {
            result.add(current.toString().strip());
        }
        return result;
    }
}
