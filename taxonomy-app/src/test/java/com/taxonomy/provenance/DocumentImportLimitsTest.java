package com.taxonomy.provenance;

import com.taxonomy.provenance.config.DocumentImportLimits;
import com.taxonomy.provenance.service.DocumentLimitException;
import com.taxonomy.provenance.service.DocumentParserService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentImportLimitsTest {

    @Test
    void uploadLargerThanConfiguredPolicyIsRejectedBeforeParsing() {
        DocumentImportLimits limits = new DocumentImportLimits();
        limits.setMaxUploadBytes(10);
        DocumentParserService parser = new DocumentParserService(limits);
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", new byte[11]);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOfSatisfying(DocumentLimitException.class,
                        error -> assertThat(error.getCode()).isEqualTo("UPLOAD_TOO_LARGE"));
    }

    @Test
    void pdfPageLimitIsEnforcedBeforeTextExtraction() throws IOException {
        DocumentImportLimits limits = new DocumentImportLimits();
        limits.setMaxPdfPages(1);
        DocumentParserService parser = new DocumentParserService(limits);
        MockMultipartFile file = new MockMultipartFile(
                "file", "two-pages.pdf", "application/pdf", pdfWithPages(2));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOfSatisfying(DocumentLimitException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("PDF_PAGE_LIMIT_EXCEEDED"));
    }

    @Test
    void candidateCountIsBounded() {
        DocumentImportLimits limits = new DocumentImportLimits();
        limits.setMaxCandidates(1);
        DocumentParserService parser = new DocumentParserService(limits);
        String text = "First requirement paragraph contains enough detail to pass the candidate filter.\n\n"
                + "Second requirement paragraph also contains enough detail to pass the candidate filter.";

        assertThat(parser.extractCandidates(text)).hasSize(1);
    }

    @Test
    void streamingHashMatchesByteArrayHash() throws IOException {
        DocumentParserService parser = new DocumentParserService();
        byte[] content = "streamed document content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", content);

        assertThat(parser.computeContentHash(file))
                .isEqualTo(parser.computeContentHash(content));
    }

    @Test
    void suspiciousDocxCompressionIsRejectedBeforePoiExpansion() throws IOException {
        DocumentImportLimits limits = new DocumentImportLimits();
        limits.setMinDocxInflateRatio(0.50d);
        DocumentParserService parser = new DocumentParserService(limits);
        MockMultipartFile file = new MockMultipartFile(
                "file", "compressed.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                compressedDocxLikeArchive());

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOfSatisfying(DocumentLimitException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("DOCX_SUSPICIOUS_COMPRESSION"));
    }

    private static byte[] pdfWithPages(int pages) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] compressedDocxLikeArchive() throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("A".repeat(100_000).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }
}