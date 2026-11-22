package com.taxonomy.backup.runtime;

import com.taxonomy.catalog.definition.BundledCatalogue;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Compares the public DTO, in its serialized order, against a fresh traversal of the real bilingual catalogue. */
class CatalogueTreeReadIT {
    @Test
    void bulkReadPreservesTheWholeCatalogueAndExcludesPrivateRelations() throws Exception {
        try (var fixture = new CatalogueSourceLoaderIT.Fixture()) {
            var state = fixture.start(true, false, new DefaultResourceLoader(),
                    BundledCatalogue.BILINGUAL_RESOURCE, "classpath:data/nato-taxonomy.json");
            assertThat(state.isReady()).as(state.getError()).isTrue();
            Supplier<List<TaxonomyNodeDto>> traverse = () -> fixture.tx.execute(tx -> fixture.service.getRootNodes()
                    .stream().map(fixture.service::toDto).toList());
            Supplier<List<TaxonomyNodeDto>> bulk = () -> fixture.tx.execute(tx -> fixture.service.getFullTree());
            var statistics = fixture.factory.getStatistics();
            statistics.setStatisticsEnabled(true);
            statistics.clear();
            String expected = digest(traverse.get());
            long traversalStatements = statistics.getPrepareStatementCount();
            statistics.clear();
            assertThat(digest(bulk.get())).isEqualTo(expected);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
            assertThat(statistics.getCollectionFetchCount()).isZero();
            assertThat(statistics.getEntityStatistics(TaxonomyNode.class.getName()).getLoadCount()).isZero();
            if (Boolean.getBoolean("taxonomy.benchmark.catalogue-tree")) {
                System.out.printf("Catalogue tree SQL statements: traversal %d, bulk %d%n",
                        traversalStatements, statistics.getPrepareStatementCount());
                measure(traverse, bulk);
            }

            fixture.tx.executeWithoutResult(tx -> {
                var seed = fixture.relations.findCentralReferenceRelations().getFirst();
                persistRelation(fixture, seed, seed.getRepositoryId(), "private-workspace");
                persistRelation(fixture, seed, "another-repository", null);
            });
            assertThat(digest(bulk.get())).isEqualTo(expected);
        }
    }

    private static String digest(List<TaxonomyNodeDto> roots) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new ObjectMapper().writeValueAsBytes(roots)));
    }

    private static void persistRelation(CatalogueSourceLoaderIT.Fixture fixture, TaxonomyRelation seed,
                                        String repository, String workspace) {
        var relation = new TaxonomyRelation();
        relation.setRepositoryId(repository);
        relation.setWorkspaceId(workspace);
        relation.setSourceNode(seed.getSourceNode());
        relation.setTargetNode(seed.getTargetNode());
        relation.setRelationType(seed.getRelationType());
        relation.setDescription("Private relationship");
        fixture.em.persist(relation);
    }

    /** Alternate the order so both readers experience the same database and JIT warm-up. */
    private static void measure(Supplier<List<TaxonomyNodeDto>> traverse, Supplier<List<TaxonomyNodeDto>> bulk) {
        final int warmups = 8;
        final int samples = 11;
        long[][] times = new long[2][samples];
        var readers = List.of(traverse, bulk);
        for (int round = -warmups; round < samples; round++) {
            for (int half = 0; half < readers.size(); half++) {
                int reader = Math.floorMod(round + half, readers.size());
                long start = System.nanoTime();
                var tree = readers.get(reader).get();
                long elapsed = System.nanoTime() - start;
                assertThat(tree).isNotEmpty();
                if (round >= 0) times[reader][round] = elapsed;
            }
        }
        Arrays.sort(times[0]);
        Arrays.sort(times[1]);
        System.out.printf("Catalogue tree median (%d samples each, %d warmups): traversal %.3f ms, bulk %.3f ms%n",
                samples, warmups, times[0][samples / 2] / 1_000_000.0, times[1][samples / 2] / 1_000_000.0);
    }
}
