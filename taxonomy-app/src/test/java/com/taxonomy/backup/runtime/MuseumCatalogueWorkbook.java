package com.taxonomy.backup.runtime;

import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

/** The same imported worksheet and ten-level hierarchy drive both browser-model and broker tests. */
public final class MuseumCatalogueWorkbook {
    private MuseumCatalogueWorkbook() {}

    public static List<RootCatalogueSnapshot> importRoots(CatalogueSourceIdentity source) throws Exception {
        try (var fixture = new CatalogueSourceLoaderIT.Fixture()) {
            fixture.resources.workbook.bytes = extend(CatalogueSourceLoaderIT.workbook("Museum baseline", true));
            fixture.load(false, false);
            var snapshots = new CatalogueSnapshotService(fixture.nodes,
                    new CatalogueOverlayService(new ObjectMapper(), fixture.resources, false, "unused"),
                    CatalogueRuntimePolicy.fullCatalogue(), fixture.journal, new RetainedCatalogueDefinitions(fixture.journal));
            return snapshots.captureRevision(source, fixture.journal.current().id(), Set.of());
        }
    }

    static byte[] extend(byte[] previous) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(previous)); var output = new ByteArrayOutputStream()) {
            var schema = workbook.createSheet("Catalogue Definition");
            var rows = new String[][]{{"catalogue-definition-v1", "museum-1", "standard-v1"},
                    {"root", "Museum Collections", "MC", "MuseumCollection"}, {"direction", "MC", "USES", "IP"}};
            for (int i = 0; i < rows.length; i++) {
                var row = schema.createRow(i);
                for (int column = 0; column < rows[i].length; column++) row.createCell(column).setCellValue(rows[i][column]);
            }
            var sheet = workbook.createSheet("Museum Collections");
            sheet.createRow(0).createCell(0).setCellValue("Code");
            for (int i = 1; i <= 10; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue("MC-" + i);
                row.createCell(2).setCellValue("Collection level " + i);
                row.createCell(4).setCellValue(i == 1 ? "MC" : "MC-" + (i - 1));
                row.createCell(11).setCellValue(i);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
