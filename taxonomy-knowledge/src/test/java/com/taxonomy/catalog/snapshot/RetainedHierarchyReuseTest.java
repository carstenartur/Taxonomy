package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.InputReference;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.Snapshot;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.Use;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RetainedHierarchyReuseTest {
    private static final InputReference UNUSED = new InputReference(Use.NOT_USED, null, 0);
    private final CatalogueSourceJournal journal = mock(CatalogueSourceJournal.class);
    private final CatalogueSnapshotService captures = new CatalogueSnapshotService(null,
            new CatalogueOverlayService(new ObjectMapper(), new DefaultResourceLoader(), true, "unused"),
            CatalogueRuntimePolicy.fullCatalogue(), journal);

    @Test
    void reusesOnlyVerifiedNodesAcrossSeparateSourcesAndRevisions() throws Exception {
        var workbook = retained("data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
        var patch = retained("data/nato-taxonomy.json");
        when(journal.retainedWorkbook(reference(workbook))).thenReturn(workbook);
        when(journal.retainedInput(reference(patch))).thenReturn(patch);
        var old = generation(workbook, reference(patch));
        var latest = generation(workbook, reference(patch));
        var one = new CatalogueSourceIdentity("repo-1", "workspace-1", "old", "commit-1");
        var two = new CatalogueSourceIdentity("repo-2", "workspace-2", "new", "commit-2");
        var cp = captures.captureRevision(one, old.id(), Set.of("CP")).getFirst();
        var different = captures.captureRevision(two, latest.id(), Set.of("CP", "IP"));
        assertThat(different).extracting(RootCatalogueSnapshot::rootCode).containsExactly("CP", "IP");
        assertThat(different).allSatisfy(root -> {
            assertThat(root.source()).isEqualTo(two);
            assertThat(root.catalogueProvenance()).isEqualTo(latest);
        });
        assertThat(different.getFirst().nodes()).containsExactlyElementsOf(cp.nodes());
        assertThat(different.getFirst().nodes().getFirst()).as("immutable catalogue node reused across captures")
                .isSameAs(cp.nodes().getFirst());
        assertThat(cp.source()).isEqualTo(one);
        assertThat(cp.catalogueProvenance()).isEqualTo(old);
        assertThatThrownBy(() -> captures.captureRevision(two, latest.id(), Set.of("UNKNOWN")))
                .isInstanceOf(IllegalArgumentException.class);
        when(journal.retainedInput(reference(patch))).thenThrow(new IOException("missing verified overlay"));
        assertThatThrownBy(() -> captures.captureRevision(one, old.id(), Set.of("CP")))
                .hasRootCauseMessage("missing verified overlay");

        // A different, valid JSON encoding has a different retained digest.
        var changed = CatalogueSourceBytes.capture(() -> new SequenceInputStream(patch.openStream(),
                new ByteArrayInputStream(new byte[]{' '})));
        when(journal.retainedInput(reference(changed))).thenReturn(changed);
        var other = generation(workbook, reference(changed));
        var rebuilt = captures.captureRevision(two, other.id(), Set.of("CP")).getFirst();
        assertThat(rebuilt.overlayMetadata().sha256()).isEqualTo(changed.sha256());
        assertThat(rebuilt.nodes().getFirst()).isEqualTo(cp.nodes().getFirst()).isNotSameAs(cp.nodes().getFirst());
        when(journal.retainedWorkbook(reference(workbook))).thenThrow(new IOException("missing verified workbook"));
        assertThatThrownBy(() -> captures.captureRevision(two, other.id(), Set.of("CP")))
                .hasRootCauseMessage("missing verified workbook");
    }

    @Test
    void restoresAnOlderWorkbookAfterAnotherRevisionReplacesTheCachedHierarchy() throws Exception {
        var first = workbook("Historical capacity");
        var second = workbook("Revised capacity");
        var old = generation(first, UNUSED);
        var latest = generation(second, UNUSED);
        when(journal.retainedWorkbook(reference(first))).thenReturn(first);
        when(journal.retainedWorkbook(reference(second))).thenReturn(second);
        var source = new CatalogueSourceIdentity("repo", "workspace", "branch", "commit");
        var historical = captures.captureRevision(source, old.id(), Set.of("CP")).getFirst();
        assertThat(historical.nodes()).extracting(RootCatalogueSnapshot.Node::nameEn).contains("Historical capacity");
        assertThat(captures.captureRevision(source, latest.id(), Set.of("CP")).getFirst().nodes())
                .extracting(RootCatalogueSnapshot.Node::nameEn).contains("Revised capacity").doesNotContain("Historical capacity");
        var reopened = captures.captureRevision(source, old.id(), Set.of("CP")).getFirst();
        assertThat(reopened).isEqualTo(historical);
        assertThat(reopened.nodes().getFirst()).as("the historical hierarchy was evicted and rebuilt")
                .isNotSameAs(historical.nodes().getFirst());
    }

    private static CatalogueSourceBytes workbook(String title) throws IOException {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Capabilities");
            sheet.createRow(0).createCell(0).setCellValue("Page");
            var entry = sheet.createRow(1);
            entry.createCell(0).setCellValue("CP-1");
            entry.createCell(2).setCellValue(title);
            book.write(bytes);
            return CatalogueSourceBytes.read(new ByteArrayInputStream(bytes.toByteArray()));
        }
    }

    private Snapshot generation(CatalogueSourceBytes workbook, InputReference overlay) {
        var generation = new Snapshot(UUID.randomUUID().toString(), Instant.EPOCH, reference(workbook), overlay, UNUSED);
        when(journal.findRevision(generation.id())).thenReturn(Optional.of(generation));
        return generation;
    }

    private static InputReference reference(CatalogueSourceBytes bytes) {
        return new InputReference(Use.APPLIED, bytes.sha256(), bytes.length());
    }

    private static CatalogueSourceBytes retained(String path) throws IOException {
        return CatalogueSourceBytes.capture(new ClassPathResource(path)::getInputStream);
    }
}
