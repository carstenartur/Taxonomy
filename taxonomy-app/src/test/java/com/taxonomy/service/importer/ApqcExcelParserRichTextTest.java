package com.taxonomy.service.importer;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link ApqcExcelParser} correctly preserves plain text,
 * Rich Text (multiple formatting runs), and embedded newlines from Excel cells.
 */
class ApqcExcelParserRichTextTest {

    private ApqcExcelParser parser;

    @BeforeEach
    void setUp() {
        parser = new ApqcExcelParser();
    }

    /** Helper: serialise an in-memory workbook to an InputStream. */
    private InputStream toInputStream(XSSFWorkbook workbook) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        workbook.write(baos);
        workbook.close();
        return new ByteArrayInputStream(baos.toByteArray());
    }

    @Test
    void plainTextIsPreserved() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            // Header row
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("PCF ID");
            header.createCell(1).setCellValue("Name");
            header.createCell(2).setCellValue("Level");
            header.createCell(3).setCellValue("Description");
            // Data row
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("1.0");
            row.createCell(1).setCellValue("Develop Vision");
            row.createCell(2).setCellValue("1");
            row.createCell(3).setCellValue("Plain description text");

            ExternalParser.ParsedExternalModel result = parser.parse(toInputStream(wb));

            assertThat(result.elements()).hasSize(1);
            assertThat(result.elements().get(0).description()).isEqualTo("Plain description text");
        }
    }

    @Test
    void richTextRunsAreConcatenated() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            // Header row
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("PCF ID");
            header.createCell(1).setCellValue("Name");
            header.createCell(2).setCellValue("Level");
            header.createCell(3).setCellValue("Description");
            // Data row with Rich Text: bold part + normal part
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("2.0");
            row.createCell(1).setCellValue("Manage Enterprise Risk");
            row.createCell(2).setCellValue("1");

            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            XSSFFont normalFont = wb.createFont();
            normalFont.setBold(false);

            XSSFRichTextString rts = new XSSFRichTextString();
            rts.append("Bold part", boldFont);
            rts.append(" and normal part", normalFont);
            row.createCell(3).setCellValue(rts);

            ExternalParser.ParsedExternalModel result = parser.parse(toInputStream(wb));

            assertThat(result.elements()).hasSize(1);
            assertThat(result.elements().get(0).description())
                    .isEqualTo("Bold part and normal part");
        }
    }

    @Test
    void embeddedNewlinesArePreserved() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            // Header row
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("PCF ID");
            header.createCell(1).setCellValue("Name");
            header.createCell(2).setCellValue("Level");
            header.createCell(3).setCellValue("Description");
            // Data row with embedded newline (Alt+Enter in Excel)
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("3.0");
            row.createCell(1).setCellValue("Process Strategy");
            row.createCell(2).setCellValue("1");
            row.createCell(3).setCellValue("Line one\nLine two\nLine three");

            ExternalParser.ParsedExternalModel result = parser.parse(toInputStream(wb));

            assertThat(result.elements()).hasSize(1);
            String desc = result.elements().get(0).description();
            assertThat(desc).contains("\n");
            assertThat(desc).contains("Line one");
            assertThat(desc).contains("Line two");
            assertThat(desc).contains("Line three");
        }
    }

    @Test
    void bulletPointIndentationIsPreserved() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            // Header row
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("PCF ID");
            header.createCell(1).setCellValue("Name");
            header.createCell(2).setCellValue("Level");
            header.createCell(3).setCellValue("Description");
            // Data row with indented bullet points (Alt+Enter in Excel)
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("4.0");
            row.createCell(1).setCellValue("Manage Topics");
            row.createCell(2).setCellValue("1");
            row.createCell(3).setCellValue("Main topic\n  \u2022 Sub-item 1\n  \u2022 Sub-item 2\n    - Detail A");

            ExternalParser.ParsedExternalModel result = parser.parse(toInputStream(wb));

            assertThat(result.elements()).hasSize(1);
            String desc = result.elements().get(0).description();
            assertThat(desc).contains("Main topic");
            assertThat(desc).contains("\n  \u2022 Sub-item 1");
            assertThat(desc).contains("\n  \u2022 Sub-item 2");
            assertThat(desc).contains("\n    - Detail A");
        }
    }
}
