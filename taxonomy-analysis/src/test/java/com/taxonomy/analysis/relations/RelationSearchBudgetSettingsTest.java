package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationSearchBudgetSettingsTest {
    @Test
    void defaultsProvideBoundedCapacityWithoutAnApplicationAdapter() {
        assertThat(new RelationSearchBudgetSettings().snapshot())
                .isEqualTo(new RelationSearchBudgetSettings.Snapshot(256, 4096, true, 128, 4096));
    }

    @Test
    void deploymentPropertiesRemainTheConfiguredDefaults() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
                    "taxonomy.analysis.relations.hierarchical.max-calls", "320",
                    "taxonomy.analysis.relations.hierarchical.max-total-calls", "2400",
                    "taxonomy.analysis.relations.hierarchical.auto-continue", "false",
                    "taxonomy.analysis.relations.hierarchical.max-sources", "96",
                    "taxonomy.analysis.relations.hierarchical.max-work-items", "8192")));
            context.registerBean(RelationSearchBudgetSettings.class);
            context.refresh();
            assertThat(context.getBean(RelationSearchBudgetSettings.class).snapshot())
                    .isEqualTo(new RelationSearchBudgetSettings.Snapshot(320, 2400, false, 96, 8192));
        }
    }

    @Test
    void runtimeReadsCaptureOneVersionAndDoNotMutateAnEarlierBudget() {
        var versions = new AtomicReference<>(Map.<String, Object>of(
                "analysis.relations.max-calls", 12, "analysis.relations.max-total-calls", 48,
                "analysis.relations.auto-continue", false, "analysis.relations.max-sources", 3,
                "analysis.relations.max-work-items", 8));
        var snapshots = new AtomicInteger();
        var settings = new RelationSearchBudgetSettings();
        ReflectionTestUtils.setField(settings, "runtimeSettings", new AnalysisRuntimeSettings() {
            public int getInt(String key, int fallback) {
                throw new AssertionError("Grouped budget reads must use a captured settings version");
            }
            public AnalysisRuntimeSettings snapshot() {
                snapshots.incrementAndGet();
                Map<String, Object> captured = versions.get();
                return new AnalysisRuntimeSettings() {
                    public int getInt(String key, int fallback) {
                        return captured.get(key) instanceof Integer value ? value : fallback;
                    }
                    public boolean getBoolean(String key, boolean fallback) {
                        return captured.get(key) instanceof Boolean value ? value : fallback;
                    }
                };
            }
        });
        var first = settings.snapshot();
        versions.set(Map.of("analysis.relations.max-calls", 512, "analysis.relations.max-total-calls", 8192,
                "analysis.relations.auto-continue", true, "analysis.relations.max-sources", 256,
                "analysis.relations.max-work-items", 100_000));
        var next = settings.snapshot();

        assertThat(first).isEqualTo(new RelationSearchBudgetSettings.Snapshot(12, 48, false, 3, 8));
        assertThat(next).isEqualTo(new RelationSearchBudgetSettings.Snapshot(512, 8192, true, 256, 100_000));
        assertThat(snapshots.get()).isEqualTo(2);
    }

    @Test
    void optionalRuntimeAdapterUsesConfiguredFallbacksForMissingValues() {
        var settings = new RelationSearchBudgetSettings();
        ReflectionTestUtils.setField(settings, "defaultMaxCalls", 320);
        ReflectionTestUtils.setField(settings, "defaultAutoContinue", false);
        ReflectionTestUtils.setField(settings, "runtimeSettings", (AnalysisRuntimeSettings) (key, fallback) -> fallback);
        assertThat(settings.snapshot()).isEqualTo(new RelationSearchBudgetSettings.Snapshot(320, 4096, false, 128, 4096));
    }

    @Test
    void snapshotRejectsOutOfRangeBudgetsWithoutReinterpretingZero() {
        var zero = new RelationSearchBudgetSettings.Snapshot(0, 0, false, 1, 1);
        assertThat(zero.maxCalls()).isZero();
        assertThat(zero.maxTotalCalls()).isZero();
        assertThatThrownBy(() -> new RelationSearchBudgetSettings.Snapshot(-1, 4096, true, 128, 4096))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("analysis.relations.max-calls");
        assertThatThrownBy(() -> new RelationSearchBudgetSettings.Snapshot(256, 10_001, true, 128, 4096))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("analysis.relations.max-total-calls");
        assertThatThrownBy(() -> new RelationSearchBudgetSettings.Snapshot(256, 4096, true, 0, 4096))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("analysis.relations.max-sources");
        assertThatThrownBy(() -> new RelationSearchBudgetSettings.Snapshot(256, 4096, true, 128, 100_001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("analysis.relations.max-work-items");
    }
}
