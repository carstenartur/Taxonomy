package com.taxonomy.catalog.definition;

import com.taxonomy.dsl.model.CatalogueDefinition;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.Set;

/** Reads the definition from the same retained workbook bytes as its hierarchy. */
public final class WorkbookCatalogueDefinition {
    private static final String SHEET = "Catalogue Definition";
    private WorkbookCatalogueDefinition() {}

    public static CatalogueDefinition read(Workbook workbook) {
        var sheet = workbook.getSheet(SHEET);
        if (sheet == null) return CatalogueDefinition.standard();
        Row header = sheet.getRow(0);
        if (!"catalogue-definition-v1".equals(text(header, 0)))
            throw new IllegalArgumentException("Unrecognized catalogue definition format");
        String version = text(header, 1);
        String base = text(header, 2);
        if (!Set.of("standard-v1", "empty").contains(base))
            throw new IllegalArgumentException("Unknown catalogue definition base: " + base);
        var roots = new ArrayList<CatalogueDefinition.Root>();
        var directions = new ArrayList<CatalogueDefinition.Direction>();
        for (Row row : sheet) {
            if (row.getRowNum() == 0 || row.getPhysicalNumberOfCells() == 0) continue;
            String kind = text(row, 0);
            String first = text(row, 1);
            String second = text(row, 2);
            String third = text(row, 3);
            switch (kind) {
                case "root" -> {
                    if (SHEET.equalsIgnoreCase(first) || "Relations".equalsIgnoreCase(first) || workbook.getSheet(first) == null)
                        throw new IllegalArgumentException("Missing or reserved taxonomy sheet: " + first);
                    roots.add(new CatalogueDefinition.Root(first, second, third));
                }
                case "direction" -> directions.add(new CatalogueDefinition.Direction(first, second, third));
                default -> throw new IllegalArgumentException("Unknown catalogue definition row: " + (row.getRowNum() + 1));
            }
        }
        return new CatalogueDefinition(version, base.equals("standard-v1"), roots, directions);
    }

    private static String text(Row row, int col) {
        var cell = row == null ? null : row.getCell(col);
        if (cell == null || cell.getCellType() != CellType.STRING || cell.getStringCellValue().isBlank())
            throw new IllegalArgumentException("Missing catalogue definition text at column " + (col + 1));
        return cell.getStringCellValue().strip();
    }
}
