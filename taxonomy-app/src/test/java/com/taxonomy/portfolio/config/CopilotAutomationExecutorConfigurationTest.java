package com.taxonomy.portfolio.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopilotAutomationExecutorConfigurationTest {

    private final CopilotAutomationExecutorConfiguration configuration =
            new CopilotAutomationExecutorConfiguration();

    @Test
    void createsTheConfiguredBoundedExecutor() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor)
                configuration.copilotAutomationExecutor(3, 17);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(3);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(3);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(17);
            assertThat(executor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsUnboundedOrNonsensicalCapacitySettings() {
        assertThatThrownBy(() -> configuration.copilotAutomationExecutor(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrent-operations");
        assertThatThrownBy(() -> configuration.copilotAutomationExecutor(65, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 64");
        assertThatThrownBy(() -> configuration.copilotAutomationExecutor(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue-capacity");
        assertThatThrownBy(() -> configuration.copilotAutomationExecutor(1, 10_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 10000");
    }
}
