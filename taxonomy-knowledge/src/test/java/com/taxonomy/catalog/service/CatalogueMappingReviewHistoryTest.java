package com.taxonomy.catalog.service;

import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.service.CatalogueMappingReviewHistory.Action;
import com.taxonomy.catalog.service.CatalogueMappingReviewHistory.Decision;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class CatalogueMappingReviewHistoryTest {
    @Test void preservesExactProposalsAndHistoryAcrossRemapsAndRejectsCrossProductReads() throws Exception {
        try (var storage = new DslGitRepositoryFactory(null)) {
            var journal = new CatalogueMappingReviewHistory(storage, JsonMapper.builder().build());
            String zero = ObjectId.zeroId().name();
            byte[] overlay = "{\"mappingVersion\":\"v2\"}".getBytes(StandardCharsets.UTF_8);
            String sha = CatalogueSourceBytes.read(new ByteArrayInputStream(overlay)).sha256();
            var first = journal.append(zero, decision("IP-P", "v1", Action.ACCEPT, "v2", sha), overlay);
            var second = journal.append(first.commit(), decision("IP-P", "v2", Action.REJECT, null, null), null);
            var other = journal.append(zero, decision("IP-Q", "v1", Action.REJECT, null, null), null);
            assertThat(journal.page("IP-P", null).decisions()).containsExactly(second, first);
            assertThat(journal.proposal("IP-P", first.commit())).isEqualTo(overlay);
            assertThatThrownBy(() -> journal.proposal("IP-P", second.commit())).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> journal.page("IP-P", other.commit())).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> journal.proposal("IP-Q", first.commit())).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> journal.append(first.commit(), decision("IP-P", "v1", Action.REJECT, null, null), null))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            assertThat(journal.page("IP-P", null).head()).isEqualTo(second.commit());
        }
    }

    @Test void competingReviewersCannotBothAppendAtTheSameExpectedVersion() throws Exception {
        try (var storage = new DslGitRepositoryFactory(null); var workers = Executors.newFixedThreadPool(2)) {
            var journal = new CatalogueMappingReviewHistory(storage, JsonMapper.builder().build());
            var barrier = new CyclicBarrier(2);
            Callable<String> submit = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    return journal.append(ObjectId.zeroId().name(), decision("IP-P", "v1", Action.REJECT, null, null), null).commit();
                } catch (ResponseStatusException conflict) {
                    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                    return "conflict";
                }
            };
            var a = workers.submit(submit); var b = workers.submit(submit);
            var result = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(result).containsOnlyOnce("conflict").contains(journal.page("IP-P", null).head());
            assertThat(journal.page("IP-P", null).decisions()).hasSize(1);
        }
    }

    private static Decision decision(String code, String version, Action action, String proposedVersion, String digest) {
        var mapping = new TaxonomyService.ProvisionalProductMapping(code, "Product", "source-parent", "IP-F", List.of(),
                0.7, "PRODUCT", "PROVISIONAL", "draft", "Original rationale");
        return new Decision(code, "generation-" + version, version, "w".repeat(64), "f".repeat(64), mapping, action,
                "IP-F", List.of(), "Independent rationale", "reviewer", Instant.now(), proposedVersion, digest);
    }
}
