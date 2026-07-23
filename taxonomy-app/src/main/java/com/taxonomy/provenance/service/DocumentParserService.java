package com.taxonomy.provenance.service;

import com.taxonomy.dto.DocumentParseResult;
import com.taxonomy.dto.RequirementCandidate;
import com.taxonomy.provenance.config.DocumentImportLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.BreakIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Parses uploaded PDF and DOCX documents into bounded requirement candidates.
 *
 * <p>The service never calls {@link MultipartFile#getBytes()}. Uploads are
 * copied once to a temporary file, parsed from disk, and deleted afterwards.
 * Expanded DOCX entries, PDF page count, extracted text, and candidate count
 * are constrained by {@link DocumentImportLimits}.</p>
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);
    private static final int MIN_CANDIDATE_LENGTH = 40;
    private static final int MAX_CANDIDATE_LENGTH = 2000;
    private static final int RAW_TEXT_PREVIEW_LENGTH = 2000;
    private static final int MAX_DOCX_ENTRIES = 10_000;
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:§\\s*\\d+|Art\\.?\\s*\\d+|\\d+\\.\\d*\\s+[A-ZÄÖÜ]|[IVXLCDM]+\\.\\s+|Kapitel\\s+\\d+|"
                    + "Chapter\\s+\\d+|Section\\s+\\d+|Abschnitt\\s+\\d+|Artikel\\s+\\d+).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern H_MARKER_PATTERN = Pattern.compile("^\\[H(\\d+)]\\s*(.+)$");
    private static final Pattern STYLE_LEVEL_PATTERN = Pattern.compile("\\d+");

    private final DocumentImportLimits limits;

    /** Convenience constructor retained for focused unit tests. */
    public DocumentParserService() {
        this(new DocumentImportLimits());
    }

    @Autowired
    public DocumentParserService(DocumentImportLimits limits) {
        this.limits = limits;
    }

    record HeadingMatch(int level, String text) {
    }

    private record TextExtraction(String text, boolean truncated) {
    }

    private record ParsedContent(String text, int pageCount, boolean truncated) {
    }

    private record CandidateExtraction(List<RequirementCandidate> candidates, boolean truncated) {
    }

    /** Parses an uploaded PDF or DOCX using a bounded temporary-file workflow. */
    public DocumentParseResult parse(MultipartFile file) throws IOException {
        validateUpload(file);
        String contentType = detectMimeType(file);
        Path temporaryFile = Files.createTempFile("taxonomy-document-", suffixFor(contentType));
        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            }

            ParsedContent parsed = switch (contentType) {
                case MIME_PDF -> parsePdf(temporaryFile);
                case MIME_DOCX -> parseDocx(temporaryFile);
                default -> throw new IOException("Unsupported file type: " + contentType
                        + ". Only PDF and DOCX files are supported.");
            };

            CandidateExtraction candidateExtraction = extractCandidatesBounded(
                    parsed.text(), limits.getMaxCandidates());

            DocumentParseResult result = new DocumentParseResult();
            result.setFileName(file.getOriginalFilename());
            result.setMimeType(contentType);
            result.setTotalPages(parsed.pageCount());
            result.setRawTextPreview(parsed.text().length() > RAW_TEXT_PREVIEW_LENGTH
                    ? parsed.text().substring(0, RAW_TEXT_PREVIEW_LENGTH) + "…"
                    : parsed.text());
            result.setCandidates(candidateExtraction.candidates());
            result.setWarnings(new ArrayList<>());

            if (parsed.truncated()) {
                result.getWarnings().add("Extracted document text was truncated at "
                        + limits.getMaxExtractedCharacters() + " characters.");
            }
            if (candidateExtraction.truncated()) {
                result.getWarnings().add("Requirement candidates were truncated at "
                        + limits.getMaxCandidates() + " entries.");
            }
            if (candidateExtraction.candidates().isEmpty()) {
                result.getWarnings().add("No requirement candidates were extracted from this document.");
            }

            log.info("Parsed document '{}': {} pages, {} candidates, textTruncated={}, candidatesTruncated={}",
                    file.getOriginalFilename(), parsed.pageCount(),
                    candidateExtraction.candidates().size(), parsed.truncated(),
                    candidateExtraction.truncated());
            return result;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /** Computes SHA-256 from a stream without materializing the upload in heap. */
    public String computeContentHash(MultipartFile file) throws IOException {
        validateUpload(file);
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /** Backward-compatible helper for small in-memory test fixtures. */
    public String computeContentHash(byte[] content) {
        return HexFormat.of().formatHex(sha256().digest(content));
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentLimitException("EMPTY_FILE", "The uploaded file is empty");
        }
        if (file.getSize() > limits.getMaxUploadBytes()) {
            throw new DocumentLimitException("UPLOAD_TOO_LARGE",
                    "File exceeds the configured upload limit of "
                            + limits.getMaxUploadBytes() + " bytes");
        }
    }

    private ParsedContent parsePdf(Path path) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            if (pageCount > limits.getMaxPdfPages()) {
                throw new DocumentLimitException("PDF_PAGE_LIMIT_EXCEEDED",
                        "PDF contains " + pageCount + " pages; maximum is "
                                + limits.getMaxPdfPages());
            }
            BoundedTextWriter writer = new BoundedTextWriter(limits.getMaxExtractedCharacters());
            new PDFTextStripper().writeText(pdf, writer);
            return new ParsedContent(writer.text(), pageCount, writer.truncated());
        }
    }

    private ParsedContent parseDocx(Path path) throws IOException {
        inspectDocxArchive(path);
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(input)) {
            int pageCount = 1;
            try {
                int pages = document.getProperties().getExtendedProperties()
                        .getUnderlyingProperties().getPages();
                if (pages > 0) {
                    pageCount = pages;
                }
            } catch (RuntimeException ignored) {
                // Page count is optional in minimal DOCX files.
            }
            TextExtraction extraction = extractDocxText(document);
            return new ParsedContent(extraction.text(), pageCount, extraction.truncated());
        } catch (DocumentLimitException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("Unable to parse DOCX document", error);
        }
    }

    /** Rejects ZIP bombs before Apache POI expands any OOXML entry. */
    private void inspectDocxArchive(Path path) throws IOException {
        long totalExpanded = 0;
        int entries = 0;
        try (ZipFile archive = new ZipFile(path.toFile())) {
            var enumeration = archive.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                entries++;
                if (entries > MAX_DOCX_ENTRIES) {
                    throw new DocumentLimitException("DOCX_ENTRY_COUNT_EXCEEDED",
                            "DOCX archive contains too many entries");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                long expanded = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (expanded < 0 || compressed < 0) {
                    throw new DocumentLimitException("DOCX_UNKNOWN_ENTRY_SIZE",
                            "DOCX archive contains an entry with unknown size");
                }
                if (expanded > limits.getMaxDocxEntryBytes()) {
                    throw new DocumentLimitException("DOCX_ENTRY_TOO_LARGE",
                            "DOCX entry exceeds the configured expanded-size limit");
                }
                totalExpanded = Math.addExact(totalExpanded, expanded);
                if (totalExpanded > limits.getMaxDocxTextBytes()) {
                    throw new DocumentLimitException("DOCX_EXPANDED_SIZE_EXCEEDED",
                            "DOCX expanded content exceeds the configured limit");
                }
                if (expanded > 0 && compressed > 0
                        && ((double) compressed / (double) expanded)
                        < limits.getMinDocxInflateRatio()) {
                    throw new DocumentLimitException("DOCX_SUSPICIOUS_COMPRESSION",
                            "DOCX compression ratio is below the configured safety threshold");
                }
            }
        } catch (ArithmeticException error) {
            throw new DocumentLimitException("DOCX_EXPANDED_SIZE_EXCEEDED",
                    "DOCX expanded size overflow", error);
        }
    }

    private TextExtraction extractDocxText(XWPFDocument document) {
        StringBuilder text = new StringBuilder(Math.min(
                limits.getMaxExtractedCharacters(), 64 * 1024));
        boolean truncated = false;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String paragraphText = paragraph.getText();
            if (paragraphText == null || paragraphText.isBlank()) {
                continue;
            }
            String value;
            String styleId = paragraph.getStyleID();
            if (styleId != null && isHeadingStyle(styleId)) {
                value = "[H" + extractHeadingLevel(styleId) + "] " + paragraphText + "\n\n";
            } else {
                value = paragraphText + "\n\n";
            }
            int remaining = limits.getMaxExtractedCharacters() - text.length();
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (value.length() > remaining) {
                text.append(value, 0, remaining);
                truncated = true;
                break;
            }
            text.append(value);
        }
        return new TextExtraction(text.toString(), truncated);
    }

    private String detectMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            if (contentType.contains("pdf")) return MIME_PDF;
            if (contentType.contains("wordprocessingml") || contentType.contains("docx")) return MIME_DOCX;
        }
        String name = file.getOriginalFilename();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) return MIME_PDF;
            if (lower.endsWith(".docx")) return MIME_DOCX;
        }
        return contentType != null ? contentType : "application/octet-stream";
    }

    private static String suffixFor(String contentType) {
        return MIME_PDF.equals(contentType) ? ".pdf" : MIME_DOCX.equals(contentType) ? ".docx" : ".upload";
    }

    public List<RequirementCandidate> extractCandidates(String rawText) {
        return extractCandidatesBounded(rawText, limits.getMaxCandidates()).candidates();
    }

    private CandidateExtraction extractCandidatesBounded(String rawText, int maxCandidates) {
        List<RequirementCandidate> candidates = new ArrayList<>();
        String[] paragraphs = rawText.split("\\n\\s*\\n");
        Deque<Map.Entry<Integer, String>> headingStack = new ArrayDeque<>();
        String currentSectionPath = null;
        int index = 0;
        boolean truncated = false;

        outer:
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) continue;

            HeadingMatch heading = detectHeading(trimmed);
            if (heading != null) {
                while (!headingStack.isEmpty() && headingStack.peek().getKey() >= heading.level()) {
                    headingStack.pop();
                }
                headingStack.push(Map.entry(heading.level(), heading.text()));
                currentSectionPath = buildSectionPath(headingStack);
                continue;
            }
            if (trimmed.length() < MIN_CANDIDATE_LENGTH) continue;

            List<String> parts = trimmed.length() > MAX_CANDIDATE_LENGTH
                    ? splitAtSentenceBoundaries(trimmed, MAX_CANDIDATE_LENGTH)
                    : List.of(trimmed);
            for (String part : parts) {
                if (candidates.size() >= maxCandidates) {
                    truncated = true;
                    break outer;
                }
                candidates.add(new RequirementCandidate(index++, currentSectionPath, part, null));
            }
        }
        return new CandidateExtraction(List.copyOf(candidates), truncated);
    }

    private static boolean isHeadingStyle(String styleId) {
        String lower = styleId.toLowerCase(Locale.ROOT);
        return lower.startsWith("heading")
                || lower.startsWith("berschrift")
                || lower.startsWith("überschrift");
    }

    private static int extractHeadingLevel(String styleId) {
        Matcher matcher = STYLE_LEVEL_PATTERN.matcher(styleId);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 1;
    }

    HeadingMatch detectHeading(String text) {
        if (text.length() > 200) return null;
        Matcher marker = H_MARKER_PATTERN.matcher(text);
        if (marker.matches()) {
            return new HeadingMatch(Integer.parseInt(marker.group(1)), marker.group(2).strip());
        }
        if (HEADING_PATTERN.matcher(text).matches()) {
            return new HeadingMatch(inferRegexHeadingLevel(text), text);
        }
        if (text.length() < 80 && text.equals(text.toUpperCase(Locale.ROOT))
                && text.matches(".*[A-ZÄÖÜ].*")) {
            return new HeadingMatch(1, text);
        }
        return null;
    }

    private static int inferRegexHeadingLevel(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("chapter") || lower.startsWith("kapitel")) return 1;
        if (lower.startsWith("section") || lower.startsWith("abschnitt")) return 2;
        if (text.matches("^\\d+\\.\\d+\\.\\d+.*")) return 3;
        if (text.matches("^\\d+\\.\\d+.*")) return 2;
        return 1;
    }

    private static String buildSectionPath(Deque<Map.Entry<Integer, String>> headingStack) {
        return headingStack.stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .collect(Collectors.joining(" > "));
    }

    List<String> splitAtSentenceBoundaries(String text, int maxLength) {
        List<String> result = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.GERMAN);
        iterator.setText(text);
        StringBuilder current = new StringBuilder();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
            String sentence = text.substring(start, end);
            if (current.length() + sentence.length() > maxLength && !current.isEmpty()) {
                result.add(current.toString().strip());
                current = new StringBuilder();
            }
            if (sentence.length() > maxLength && current.isEmpty()) {
                for (int index = 0; index < sentence.length(); index += maxLength) {
                    result.add(sentence.substring(index,
                            Math.min(index + maxLength, sentence.length())).strip());
                }
            } else {
                current.append(sentence);
            }
            start = end;
        }
        if (!current.isEmpty()) {
            result.add(current.toString().strip());
        }
        return result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }

    private static final class BoundedTextWriter extends Writer {
        private final int limit;
        private final StringBuilder value;
        private boolean truncated;

        private BoundedTextWriter(int limit) {
            this.limit = limit;
            this.value = new StringBuilder(Math.min(limit, 64 * 1024));
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            int remaining = limit - value.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int accepted = Math.min(remaining, length);
            value.append(characters, offset, accepted);
            if (accepted < length) {
                truncated = true;
            }
        }

        @Override public void flush() { }
        @Override public void close() { }

        private String text() { return value.toString(); }
        private boolean truncated() { return truncated; }
    }
}