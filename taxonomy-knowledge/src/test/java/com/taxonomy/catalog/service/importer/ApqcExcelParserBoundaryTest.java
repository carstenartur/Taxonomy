package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ApqcExcelParserBoundaryTest {
    @Test
    void emptySheetHasNoElementsOrRelations() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet();
            var result = parse(workbook);
            assertThat(result.elements()).isEmpty();
            assertThat(result.relations()).isEmpty();
        }
    }

    @Test
    void numericIdsAndFallbackLevelsPreserveHierarchyAndSkipMissingIds() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            row(sheet, 0, "ID", "Process Name", "Hierarchy Level", "Desc");
            row(sheet, 1, "unused", "Root", "", "  Description  ").getCell(0).setCellValue(1);
            Row child = row(sheet, 2, "unused", "Child", "not-a-level", "child description");
            child.getCell(0).setCellValue(1.2);
            child.getCell(1).setCellValue(true);
            row(sheet, 3, " ", "Ignored", "1", "");
            sheet.createRow(4).createCell(1).setCellValue("Missing identifier");
            row(sheet, 5, "1.2.3", "Out of range", "0", "");
            row(sheet, 6, "1.2.3.4.5.6", "Too deep", "", "");
            var result = parse(workbook);
            assertThat(result.elements()).extracting(ExternalElement::id)
                    .containsExactly("apqc-1", "apqc-1-2", "apqc-1-2-3", "apqc-1-2-3-4-5-6");
            assertThat(result.elements()).extracting(ExternalElement::type)
                    .containsExactly("Category", "ProcessGroup", "Category", "Category");
            assertThat(result.elements().get(0).description()).isEqualTo("Description");
            assertThat(result.elements().get(1).name()).isEqualTo("true");
            assertThat(result.elements().get(1).properties()).containsEntry("parentId", "apqc-1");
            assertThat(result.relations()).hasSize(2);
            assertThat(result.relations().get(0).sourceId()).isEqualTo("apqc-1");
            assertThat(result.relations().get(0).targetId()).isEqualTo("apqc-1-2");
        }
    }

    @Test
    void absentOptionalColumnsAndBlankHeadersDoNotInventValues() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0); // actual blank cell in a sparse header
            header.createCell(2).setCellValue(" pcf_id ");
            Row entry = sheet.createRow(1);
            entry.createCell(2).setCellValue("9.2.1");
            var result = parse(workbook);
            assertThat(result.elements()).hasSize(1);
            ExternalElement element = result.elements().get(0);
            assertThat(element.type()).isEqualTo("Process");
            assertThat(element.name()).isNull();
            assertThat(element.description()).isNull();
            assertThat(element.properties()).containsEntry("parentId", "apqc-9-2");
            assertThat(result.relations()).isEmpty();
        }
    }

    private static Row row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int col = 0; col < values.length; col++) row.createCell(col).setCellValue(values[col]);
        return row;
    }

    private static ExternalParser.ParsedExternalModel parse(XSSFWorkbook workbook) throws Exception {
        var bytes = new ByteArrayOutputStream();
        workbook.write(bytes);
        return new ApqcExcelParser().parse(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
