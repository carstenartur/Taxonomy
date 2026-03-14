package com.taxonomy.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link ApqcExcelParser} correctly extracts text from cells
 * containing Rich Text (XSSFRichTextString with multiple formatting runs),
 * preserving all text including embedded line breaks.
 */
class ApqcExcelParserRichTextTest {

    private final ApqcExcelParser parser = new ApqcExcelParser();

    /**
     * Builds a minimal APQC xlsx in memory, runs the parser, and returns
     * the parsed elements.
     */
    private List<ExternalElement> parseWorkbook(XSSFWorkbook wb) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        var result = parser.parse(new ByteArrayInputStream(bos.toByteArray()));
        return result.elements();
    }

    @Test
    void plainStringCellIsExtracted() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("PCF");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("PCF ID");
        header.createCell(1).setCellValue("Name");
        header.createCell(2).setCellValue("Level");
        header.createCell(3).setCellValue("Description");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("1.0");
        row.createCell(1).setCellValue("Develop Vision");
        row.createCell(2).setCellValue("1");
        row.createCell(3).setCellValue("A plain description");

        List<ExternalElement> elements = parseWorkbook(wb);
        assertThat(elements).hasSize(1);
        assertThat(elements.get(0).description()).isEqualTo("A plain description");
    }

    @Test
    void richTextCellPreservesAllText() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("PCF");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("PCF ID");
        header.createCell(1).setCellValue("Name");
        header.createCell(2).setCellValue("Level");
        header.createCell(3).setCellValue("Description");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("2.0");
        row.createCell(1).setCellValue("Manage Supply Chain");
        row.createCell(2).setCellValue("1");

        // Create a rich text cell with two formatting runs (bold + normal)
        XSSFRichTextString rts = new XSSFRichTextString();
        XSSFFont boldFont = wb.createFont();
        boldFont.setBold(true);
        XSSFFont normalFont = wb.createFont();
        rts.append("Key activities:\n", boldFont);
        rts.append("• Plan\n• Execute", normalFont);
        Cell descCell = row.createCell(3);
        descCell.setCellValue(rts);

        List<ExternalElement> elements = parseWorkbook(wb);
        assertThat(elements).hasSize(1);
        String desc = elements.get(0).description();
        assertThat(desc).contains("Key activities:");
        assertThat(desc).contains("• Plan");
        assertThat(desc).contains("• Execute");
    }

    @Test
    void richTextCellWithLineBreaksPreservesNewlines() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("PCF");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("PCF ID");
        header.createCell(1).setCellValue("Name");
        header.createCell(2).setCellValue("Level");
        header.createCell(3).setCellValue("Description");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("3.0");
        row.createCell(1).setCellValue("Develop Products");
        row.createCell(2).setCellValue("1");

        // Simulate Alt+Enter line breaks in Excel
        XSSFRichTextString rts = new XSSFRichTextString("Line 1\nLine 2\nLine 3");
        Cell descCell = row.createCell(3);
        descCell.setCellValue(rts);

        List<ExternalElement> elements = parseWorkbook(wb);
        assertThat(elements).hasSize(1);
        String desc = elements.get(0).description();
        assertThat(desc).contains("\n");
        assertThat(desc).contains("Line 1");
        assertThat(desc).contains("Line 2");
        assertThat(desc).contains("Line 3");
    }
}
