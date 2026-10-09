package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.*;
import com.taxonomy.shared.extension.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class PluginCatalogTest {
    static PluginDescriptor plugin(String id, PluginRequirement... requirements) {
        return new PluginDescriptor(new PluginIdentity(id, "1.0.0", "a".repeat(64)),
                ">=1.0.0 & <2.0.0", List.of(requirements), Set.of("render"), PluginMode.DYNAMIC);
    }
    static ReportRendererExtension renderer(String family, String format) {
        return new ReportRendererExtension() {
            public String reportTypeId() { return family; }
            public Class<?> reportModelType() { return String.class; }
            public ReportFormatDescriptor descriptor() { return new ReportFormatDescriptor(format, "Text", "txt", "text/plain", false); }
            public ReportRenderResult render(ReportRenderContext context) { return new ReportRenderResult(context.payloadAs(String.class).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        };
    }
    @Test void collisionLeavesTheWholePublicationUnchanged() {
        var catalog = new PluginCatalog(); var first = plugin("example.first");
        catalog.publish(first, List.of(renderer("family", "txt")));
        var before = catalog.snapshot();
        assertThatThrownBy(() -> catalog.publish(plugin("example.second"),
                List.of(renderer("other", "txt"), renderer(" FAMILY ", " TXT "))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate extension ID");
        assertThat(catalog.snapshot()).isSameAs(before);
        assertThatThrownBy(() -> catalog.acquire(ExtensionKey.report("other", "txt"), ReportRendererExtension.class))
                .isInstanceOf(ExtensionUnavailableException.class);
    }
    @Test void sameFormatInDifferentReportFamiliesIsIndependentAndSorted() {
        var catalog = new PluginCatalog();
        catalog.publish(plugin("example.renderers"), List.of(renderer("zeta", "txt"), renderer("alpha", "txt")));
        assertThat(catalog.snapshot().extensions()).extracting(e -> e.key().id()).containsExactly("alpha:txt", "zeta:txt");
        try (var lease = catalog.acquire(ExtensionKey.report("ALPHA", "TXT"), ReportRendererExtension.class)) {
            assertThat(lease.extension().render(ReportRenderContext.ofPayload("captured")).utf8()).isEqualTo("captured");
            assertThat(lease.plugin()).isEqualTo(plugin("example.renderers").identity());
        }
    }
    @Test void incompatibleApiAndMissingDependenciesPublishNothing() {
        var catalog = new PluginCatalog(); var before = catalog.snapshot(); var valid = plugin("example.renderer");
        var incompatible = new PluginDescriptor(valid.identity(), ">=2.0.0", List.of(), Set.of(), PluginMode.DYNAMIC);
        assertThatThrownBy(() -> catalog.publish(incompatible, List.of(renderer("a", "txt"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Host API");
        assertThatThrownBy(() -> catalog.publish(plugin("example.dependent", new PluginRequirement("example.absent", ">=1.0.0")), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("example.absent");
        assertThat(catalog.snapshot()).isSameAs(before);
    }
    @Test void aBatchCanSatisfyItsDependenciesButMustNotIntroduceACycle() {
        var catalog = new PluginCatalog();
        var dependent = new PluginCatalog.Contribution(plugin("example.dependent", new PluginRequirement("example.base", "1.0.0")), List.of(renderer("a", "txt")));
        var base = new PluginCatalog.Contribution(plugin("example.base"), List.of());
        catalog.publishAll(List.of(dependent, base));
        assertThat(catalog.snapshot().plugins()).extracting(p -> p.identity().id()).containsExactly("example.base", "example.dependent");
        assertThatThrownBy(() -> catalog.beginDraining(base.descriptor().identity())).hasMessageContaining("example.dependent");
        var empty = new PluginCatalog();
        assertThatThrownBy(() -> empty.publishAll(List.of(dependent, new PluginCatalog.Contribution(
                plugin("example.base", new PluginRequirement("example.dependent", "1.0.0")), List.of()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
        assertThat(empty.snapshot().plugins()).isEmpty();
    }
    @Test void wrongDependencyVersionAndDynamicStatefulContributionsAreRejected() {
        var catalog = new PluginCatalog(); catalog.publish(plugin("example.base"), List.of());
        assertThatThrownBy(() -> catalog.publish(plugin("example.dependent", new PluginRequirement("example.base", ">=2.0.0")), List.of()))
                .hasMessageContaining("version");
        TaxonomyExtension stateful = new TaxonomyExtension() {
            public String id() { return "provider"; } public String displayName() { return "Provider"; }
            public ExtensionKind kind() { return ExtensionKind.LLM_PROVIDER; }
        };
        assertThatThrownBy(() -> catalog.publish(plugin("example.llm"), List.of(stateful)))
                .hasMessageContaining("DYNAMIC");
    }
    @Test void leasePinsTheOldVersionAndDrainRefusesNewAcquisitions() throws Exception {
        var catalog = new PluginCatalog(); var plugin = plugin("example.renderer");
        catalog.publish(plugin, List.of(renderer("a", "txt")));
        var lease = catalog.acquire(ExtensionKey.report("a", "txt"), ReportRendererExtension.class);
        catalog.beginDraining(plugin.identity());
        assertThatThrownBy(() -> catalog.acquire(ExtensionKey.report("a", "txt"), ReportRendererExtension.class))
                .isInstanceOf(ExtensionUnavailableException.class);
        assertThat(catalog.awaitReleased(plugin.identity(), Duration.ZERO)).isFalse();
        assertThatThrownBy(() -> catalog.remove(plugin.identity())).isInstanceOf(IllegalStateException.class);
        assertThat(lease.extension().render(ReportRenderContext.ofPayload("old")).utf8()).isEqualTo("old");
        assertThat(lease.plugin()).isEqualTo(plugin.identity());
        lease.close(); lease.close();
        assertThatThrownBy(lease::extension).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.awaitReleased(plugin.identity(), Duration.ZERO)).isTrue();
        catalog.remove(plugin.identity());
        var next = new PluginDescriptor(new PluginIdentity(plugin.identity().id(), "2.0.0", "b".repeat(64)),
                plugin.hostApiRange(), List.of(), Set.of(), PluginMode.DYNAMIC);
        catalog.publish(next, List.of(renderer("a", "txt")));
        try (var replacement = catalog.acquire(ExtensionKey.report("a", "txt"), ReportRendererExtension.class)) {
            assertThat(replacement.plugin()).isEqualTo(next.identity());
        }
    }
    @Test void duplicatePluginAndWrongRequestedTypeDoNotChangeExistingCatalog() {
        var catalog = new PluginCatalog(); var plugin = plugin("example.renderer");
        catalog.publish(plugin, List.of(renderer("a", "txt"))); var before = catalog.snapshot();
        assertThatThrownBy(() -> catalog.publish(plugin, List.of())).hasMessageContaining("Duplicate plugin");
        assertThatThrownBy(() -> catalog.acquire(ExtensionKey.report("a", "txt"), com.taxonomy.extension.api.llm.LlmProviderExtension.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.snapshot()).isSameAs(before);
    }
    @Test void concurrentReadersOnlySeeWholeSnapshots() throws Exception {
        var catalog = new PluginCatalog(); var ready = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = executor.submit(() -> {
                ready.countDown();
                for (int i = 0; i < 10000; i++) {
                    var snapshot = catalog.snapshot();
                    assertThat(snapshot.extensions().size()).isEqualTo(snapshot.plugins().size() * 2);
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) {
                catalog.publish(plugin("example.renderer-" + i), List.of(renderer("r"+i, "txt"), renderer("r"+i, "html")));
            }
            reader.get(5, TimeUnit.SECONDS);
        }
    }
}
