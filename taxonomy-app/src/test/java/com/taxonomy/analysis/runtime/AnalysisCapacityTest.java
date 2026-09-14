package com.taxonomy.analysis.runtime;

import com.taxonomy.AppConfig;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ThreadPoolExecutor;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCapacityTest {
    @Test
    void streamingAnalysisExecutorHasABoundedQueue() {
        try (var executor = new AppConfig().analysisExecutor()) {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            assertThat(((ThreadPoolExecutor) executor).getQueue().remainingCapacity()).isBetween(1, 32);
        }
    }
}
