package com.taxonomy.provenance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.dto.RequirementCandidate;
import com.taxonomy.provenance.service.DocumentParserService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserLoggingTest {

    private static final String TEXT = "The private-document-content must remain available to the importing user "
            + "without being copied into operational diagnostics.";

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx"})
    void parsesRealDocumentsWithoutLoggingFilenameOrContents(String format) throws Exception {
        String filename = "private-customer-filename\r\nforged-log-entry." + format;
        String mimeType = format.equals("pdf") ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        var file = new MockMultipartFile("file", filename, mimeType, document(format));
        var logger = (Logger) LoggerFactory.getLogger(DocumentParserService.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);
        logger.addAppender(events);
        try {
            var result = new DocumentParserService().parse(file);

            assertThat(result.getFileName()).isEqualTo(filename);
            assertThat(result.getMimeType()).isEqualTo(mimeType);
            assertThat(result.getTotalPages()).isEqualTo(1);
            assertThat(result.getRawTextPreview()).contains(TEXT);
            assertThat(result.getCandidates()).extracting(RequirementCandidate::getText)
                    .singleElement().asString().contains(TEXT);
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                        .doesNotContain("private-", "forged-log-entry", "\r", "\n")
                        .contains("pages=1", "candidates=1", "textTruncated=false", "candidatesTruncated=false");
                assertThat(event.getThrowableProxy()).isNull();
                if (event.getArgumentArray() != null) {
                    assertThat(event.getArgumentArray()).allSatisfy(argument ->
                            assertThat(String.valueOf(argument)).doesNotContain("private-", "forged-log-entry"));
                }
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    private static byte[] document(String format) throws Exception {
        var bytes = new ByteArrayOutputStream();
        if (format.equals("docx")) {
            try (var document = new XWPFDocument()) {
                document.createParagraph().createRun().setText(TEXT);
                document.write(bytes);
            }
        } else {
            try (var document = new PDDocument()) {
                var page = new PDPage();
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(40, 700);
                    content.showText(TEXT);
                    content.endText();
                }
                document.save(bytes);
            }
        }
        return bytes.toByteArray();
    }
}
