package com.taxonomy.catalog.service;

import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueMappingReviewHistory.Action;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogueMappingReviewServiceTest {
    @Test void preparesAReviewableNewOverlayAgainstTheOriginalWorkbookWithoutChangingTheAppliedSource() throws Exception {
        var mapper = JsonMapper.builder().build();
        var workbook = CatalogueSourceBytes.capture(() -> getClass().getResourceAsStream("/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx"));
        var overlay = CatalogueSourceBytes.capture(() -> getClass().getResourceAsStream("/data/nato-taxonomy.json"));
        var generation = new CatalogueSourceJournal.Snapshot("applied", Instant.EPOCH,
                new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, workbook.sha256(), workbook.length()),
                new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, overlay.sha256(), overlay.length()),
                new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0));
        var contents = mapper.readTree(overlay.openStream());
        var source = contents.path("nodePatches").valueStream().filter(item -> item.path("reviewRequired").asBoolean(false)).findFirst().orElseThrow();
        String code = source.path("code").asString(); String parent = source.path("parentCode").asString();
        String version = contents.path("mappingVersion").asString();
        var product = new TaxonomyService.ProvisionalProductMapping(code, source.path("expectedTitle").asString(), "original",
                parent, source.path("secondaryClassificationCodes").valueStream().map(item -> item.asString()).toList(),
                source.path("confidence").asDouble(), "PRODUCT", "PROVISIONAL", "draft", source.path("justification").asString());
        var sources = mock(CatalogueSourceJournal.class); var taxonomy = mock(TaxonomyService.class);
        when(sources.lockForCapture()).thenReturn(generation);
        when(sources.retainedWorkbook(generation.workbook())).thenReturn(workbook);
        when(sources.retainedInput(generation.overlay())).thenReturn(overlay);
        when(taxonomy.provisionalInformationProducts(code)).thenReturn(new TaxonomyService.ProvisionalProductQueue(
                "applied", CatalogueSourceJournal.Use.APPLIED, workbook.sha256(), overlay.sha256(), version, List.of(product)));
        try (var store = new DslGitRepositoryFactory(null)) {
            var journal = new CatalogueMappingReviewHistory(store, mapper);
            var service = new CatalogueMappingReviewService(sources, taxonomy,
                    new CatalogueOverlayService(mapper, new DefaultResourceLoader(), true, "classpath:data/nato-taxonomy.json"), journal, mapper);
            String zero = ObjectId.zeroId().name();
            var changed = new CatalogueMappingReviewService.Command("older", version, overlay.sha256(), zero,
                    Action.ACCEPT, null, null, "Controlled fixture");
            assertThatThrownBy(() -> service.review(code, changed, "fixture")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            var invalid = new CatalogueMappingReviewService.Command("applied", version, overlay.sha256(), zero,
                    Action.CHANGE, code, List.of(), "Controlled invalid parent");
            assertThatThrownBy(() -> service.review(code, invalid, "fixture")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
            assertThat(journal.page(code, null).head()).isEqualTo(zero);
            var accept = new CatalogueMappingReviewService.Command("applied", version, overlay.sha256(), zero,
                    Action.ACCEPT, null, null, "Controlled fixture");
            var entry = service.review(code, accept, "fixture");
            var proposed = mapper.readTree(journal.proposal(code, entry.commit()));
            assertThat(proposed.path("mappingVersion").asString()).isNotEqualTo(version).isEqualTo(entry.decision().proposedVersion());
            var next = proposed.path("nodePatches").valueStream().filter(item -> code.equals(item.path("code").asString())).findFirst().orElseThrow();
            assertThat(next.path("reviewRequired").asBoolean()).isFalse();
            assertThat(next.path("parentCode").asString()).isEqualTo(parent);
            assertThat(source.path("reviewRequired").asBoolean()).isTrue();
            assertThat(journal.page(code, null).decisions()).containsExactly(entry);
        }
    }
}
