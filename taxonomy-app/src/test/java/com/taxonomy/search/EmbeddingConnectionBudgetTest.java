package com.taxonomy.search;

import com.taxonomytest.search.EmbeddingConnectionBudgetScenario;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Execution(ExecutionMode.SAME_THREAD)
class EmbeddingConnectionBudgetTest {
    @ParameterizedTest
    @ValueSource(strings = {"hsqldb", "hsqldb-file"})
    void foregroundStorageRemainsUsableDuringEmbeddingIndexing(String profile) throws Exception {
        EmbeddingConnectionBudgetScenario.verify(profile);
    }
}
