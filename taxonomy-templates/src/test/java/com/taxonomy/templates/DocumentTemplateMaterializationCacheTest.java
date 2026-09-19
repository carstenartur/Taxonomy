package com.taxonomy.templates;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTemplateMaterializationCacheTest {

    @Test
    void materializesAndValidatesAnImmutableCommitOnlyOnce() throws Exception {
        DocumentTemplateMaterializationCache cache =
                new DocumentTemplateMaterializationCache(4, 1024, null);
        AtomicInteger packs = new AtomicInteger();
        AtomicInteger validations = new AtomicInteger();

        byte[] first = cache.packed("report", commit('a'), () -> {
            packs.incrementAndGet();
            return new byte[]{1, 2, 3};
        });
        first[0] = 99;
        byte[] second = cache.packed("report", commit('a'), () -> {
            packs.incrementAndGet();
            return new byte[]{4};
        });
        cache.validateOnce("report", commit('a'), validations::incrementAndGet);
        cache.validateOnce("report", commit('a'), validations::incrementAndGet);

        assertThat(second).containsExactly(1, 2, 3);
        assertThat(packs).hasValue(1);
        assertThat(validations).hasValue(1);
    }

    @Test
    void doesNotCacheFailedMaterializationOrValidation() throws Exception {
        DocumentTemplateMaterializationCache cache =
                new DocumentTemplateMaterializationCache(4, 1024, null);
        AtomicInteger packs = new AtomicInteger();
        AtomicInteger validations = new AtomicInteger();

        assertThatThrownBy(() -> cache.packed("report", commit('b'), () -> {
            packs.incrementAndGet();
            throw new IOException("broken");
        })).isInstanceOf(IOException.class);
        assertThat(cache.packed("report", commit('b'), () -> {
            packs.incrementAndGet();
            return new byte[]{7};
        })).containsExactly(7);

        assertThatThrownBy(() -> cache.validateOnce("report", commit('b'), () -> {
            validations.incrementAndGet();
            throw new IOException("invalid");
        })).isInstanceOf(IOException.class);
        cache.validateOnce("report", commit('b'), validations::incrementAndGet);

        assertThat(packs).hasValue(2);
        assertThat(validations).hasValue(2);
    }

    @Test
    void evictsLeastRecentlyUsedEntriesWhenByteBudgetIsExceeded() throws Exception {
        DocumentTemplateMaterializationCache cache =
                new DocumentTemplateMaterializationCache(8, 5, null);
        AtomicInteger alphaPacks = new AtomicInteger();

        cache.packed("alpha", commit('a'), () -> {
            alphaPacks.incrementAndGet();
            return new byte[]{1, 2, 3};
        });
        cache.packed("beta", commit('b'), () -> new byte[]{4, 5, 6});
        cache.packed("alpha", commit('a'), () -> {
            alphaPacks.incrementAndGet();
            return new byte[]{1, 2, 3};
        });

        assertThat(alphaPacks).hasValue(2);
        assertThat(cache.sizeBytes()).isLessThanOrEqualTo(5);
    }

    private static String commit(char value) {
        return String.valueOf(value).repeat(40);
    }
}
