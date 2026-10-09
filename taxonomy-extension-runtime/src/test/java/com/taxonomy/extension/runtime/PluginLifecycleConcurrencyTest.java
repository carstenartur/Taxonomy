package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginClassLoader;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.channels.FileChannel;
import static org.assertj.core.api.Assertions.*;

class PluginLifecycleConcurrencyTest {
    @TempDir Path temporary;
    private Path root() throws Exception { return Files.createDirectories(temporary.resolve("plugins")); }
    private Pf4jPluginRuntime runtime(PluginCatalog catalog) throws Exception {
        return new Pf4jPluginRuntime(root(), temporary.resolve("cache"), catalog);
    }
    private PluginLifecycleCoordinator coordinator(Pf4jPluginRuntime runtime) throws Exception {
        return new PluginLifecycleCoordinator(runtime, root(), true, false);
    }
    @Test void admittedRendererFinishesOnOldVersionWhileNewCallsAreRefusedAndReplacementWaits() throws Exception {
        PluginJarFixture.createSource(root(), "renderer.jar", "example.renderer", Map.of(), BLOCKING, Map.of());
        var catalog = new PluginCatalog();
        try (var runtime = runtime(catalog)) {
            var management = coordinator(runtime); var old = management.activate("example.renderer");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var call = CompletableFuture.supplyAsync(() -> {
                try (var lease = catalog.acquire(ExtensionKey.report("external", "txt"), ReportRendererExtension.class)) {
                    assertThat(lease.plugin()).isEqualTo(old);
                    return lease.extension().render(ReportRenderContext.ofPayload(Map.of("entered", entered, "release", release))).utf8();
                }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var draining = management.deactivate(old.id(), Duration.ZERO);
                assertThat(draining.status()).isEqualTo(PluginOperationResult.Status.DRAINING);
                assertThat(runtime.openClassLoaders()).isEqualTo(1);
                assertThatThrownBy(() -> catalog.acquire(ExtensionKey.report("external", "txt"), ReportRendererExtension.class))
                        .isInstanceOf(ExtensionUnavailableException.class);
                assertThatThrownBy(() -> management.activate(old.id())).hasMessage("DRAINING");
            } finally { release.countDown(); }
            assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("old-version");
            assertThat(management.deactivate(old.id(), Duration.ofSeconds(1)).status()).isEqualTo(PluginOperationResult.Status.STOPPED);
            assertThat(runtime.openClassLoaders()).isZero();
            PluginJarFixture.create(root(), "renderer.jar", old.id(), Map.of("Plugin-Version", "2.0.0"), "return List.of(new Renderer());", Map.of());
            var replacement = management.activate(old.id());
            assertThat(replacement.version()).isEqualTo("2.0.0");
            assertThat(replacement.artifactSha256()).isNotEqualTo(old.artifactSha256());
        }
    }
    @Test void fiftyCyclesReleaseThreadsChannelsListenersAndTheActualJarClassLoader() throws Exception {
        PluginJarFixture.createSource(root(), "resources.jar", "example.resources", Map.of(), RESOURCES, Map.of());
        var catalog = new PluginCatalog();
        try (var runtime = runtime(catalog)) {
            var management = coordinator(runtime);
            for (int i = 0; i < 50; i++) {
                var thread = new AtomicReference<Thread>(); var channel = new AtomicReference<FileChannel>();
                var listeners = new CopyOnWriteArrayList<String>();
                var id = management.activate("example.resources");
                PluginClassLoader loader;
                try (var lease = catalog.acquire(ExtensionKey.report("external", "txt"), ReportRendererExtension.class)) {
                    loader = (PluginClassLoader) lease.extension().getClass().getClassLoader();
                    lease.extension().render(ReportRenderContext.ofPayload(Map.of("thread", thread, "channel", channel,
                            "listeners", listeners, "path", temporary.resolve("resource-" + i))));
                }
                assertThat(thread.get().isAlive()).isTrue(); assertThat(channel.get().isOpen()).isTrue();
                assertThat(listeners).containsExactly("registered");
                assertThat(management.deactivate(id.id(), Duration.ofSeconds(2)).status()).isEqualTo(PluginOperationResult.Status.STOPPED);
                assertThat(thread.get().isAlive()).isFalse(); assertThat(channel.get().isOpen()).isFalse();
                assertThat(listeners).isEmpty(); assertThat(loader.isClosed()).isTrue();
                assertThat(runtime.openClassLoaders()).isZero(); assertThat(catalog.snapshot().extensions()).isEmpty();
            }
        }
    }
    @Test void disabledClusterStartupDependencyAndUntrustedIdRejectWithoutChangingTheCatalog() throws Exception {
        PluginJarFixture.create(root(), "parent.jar", "example.parent");
        PluginJarFixture.create(root(), "dependent.jar", "example.dependent", Map.of("Plugin-Dependencies", "example.parent@>=1.0.0"), "return List.of();", Map.of());
        PluginJarFixture.create(root(), "startup.jar", "example.startup", Map.of("Taxonomy-Plugin-Mode", "STARTUP"), "return List.of();", Map.of());
        var catalog = new PluginCatalog();
        try (var runtime = runtime(catalog)) {
            for (var policy : List.of(new PluginLifecycleCoordinator(runtime, root(), false, false),
                    new PluginLifecycleCoordinator(runtime, root(), true, true))) {
                assertThatThrownBy(() -> policy.activate("example.parent")).isInstanceOf(PluginOperationException.class);
                assertThat(runtime.openClassLoaders()).isZero();
            }
            var management = coordinator(runtime); var parent = management.activate("example.parent");
            management.activate("example.dependent"); var before = catalog.snapshot();
            assertThat(management.deactivate(parent.id(), Duration.ZERO).code()).isEqualTo("DEPENDENTS_ACTIVE");
            assertThatThrownBy(() -> management.activate("example.startup")).hasMessage("STARTUP_ONLY");
            assertThatThrownBy(() -> management.activate("../parent.jar")).hasMessage("INVALID_ID");
            assertThat(catalog.snapshot()).isSameAs(before);
        }
    }
    @Test void rejectedStartIsFullyUnloadedAndOperatorCanRepairAndReactivateTheSameId() throws Exception {
        PluginJarFixture.create(root(), "broken.jar", "example.broken", Map.of(), "throw new IllegalStateException(\"private details\");", Map.of());
        var catalog = new PluginCatalog();
        try (var runtime = runtime(catalog)) {
            var management = coordinator(runtime);
            assertThatThrownBy(() -> management.activate("example.broken")).hasMessage("START_REJECTED");
            assertThat(runtime.openClassLoaders()).isZero(); assertThat(catalog.snapshot().plugins()).isEmpty();
            PluginJarFixture.create(root(), "broken.jar", "example.broken");
            assertThat(management.activate("example.broken").id()).isEqualTo("example.broken");
        }
    }
    @Test void failedCloseStillReleasesClassLoaderAndOtherPluginsOnShutdown() throws Exception {
        String failing = BLOCKING.replace("public List<TaxonomyExtension> extensions()", "public void close() { throw new IllegalStateException(\"private cleanup\"); } public List<TaxonomyExtension> extensions()");
        PluginJarFixture.createSource(root(), "bad-close.jar", "example.close", Map.of(), failing, Map.of());
        PluginJarFixture.create(root(), "other.jar", "example.other", Map.of(), "return List.of();", Map.of());
        var catalog = new PluginCatalog(); var runtime = runtime(catalog); var management = coordinator(runtime);
        management.activate("example.close"); management.activate("example.other");
        assertThatThrownBy(runtime::close).hasMessage("Plugin shutdown cleanup failed");
        assertThat(runtime.openClassLoaders()).isZero(); assertThat(runtime.installed()).isEmpty();
        assertThat(catalog.snapshot().plugins()).isEmpty(); runtime.close();
    }
    private static final String BLOCKING = """
            package example.plugin;
            import com.taxonomy.extension.api.plugin.*;
            import com.taxonomy.extension.api.report.*;
            import com.taxonomy.shared.extension.TaxonomyExtension;
            import java.util.*; import java.util.concurrent.*;
            public final class ExamplePlugin implements TaxonomyPlugin {
                public List<TaxonomyExtension> extensions() { return List.of(new Renderer()); }
                public static final class Renderer implements ReportRendererExtension {
                    public String reportTypeId() { return "external"; }
                    public Class<?> reportModelType() { return Map.class; }
                    public ReportFormatDescriptor descriptor() { return new ReportFormatDescriptor("txt", "External", "txt", "text/plain", false); }
                    public ReportRenderResult render(ReportRenderContext context) {
                        Map data = context.payloadAs(Map.class);
                        ((CountDownLatch)data.get("entered")).countDown();
                        try { if (!((CountDownLatch)data.get("release")).await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        return new ReportRenderResult("old-version".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
            """;
    private static final String RESOURCES = """
            package example.plugin;
            import com.taxonomy.extension.api.plugin.*;
            import com.taxonomy.extension.api.report.*;
            import com.taxonomy.shared.extension.TaxonomyExtension;
            import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*;
            import java.nio.channels.*; import java.nio.file.*;
            public final class ExamplePlugin implements TaxonomyPlugin {
                Thread thread; FileChannel channel; List<String> listeners;
                public List<TaxonomyExtension> extensions() { return List.of(new Renderer()); }
                public void close() {
                    try {
                        if (thread != null) { thread.interrupt(); thread.join(2000); if (thread.isAlive()) throw new IllegalStateException("owned thread did not stop"); }
                        if (channel != null) channel.close(); if (listeners != null) listeners.remove("registered");
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }
                public final class Renderer implements ReportRendererExtension {
                    public String reportTypeId() { return "external"; }
                    public Class<?> reportModelType() { return Map.class; }
                    public ReportFormatDescriptor descriptor() { return new ReportFormatDescriptor("txt", "External", "txt", "text/plain", false); }
                    public ReportRenderResult render(ReportRenderContext context) {
                        Map data = context.payloadAs(Map.class);
                        try {
                            channel = FileChannel.open((Path)data.get("path"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                            listeners = (List<String>)data.get("listeners"); listeners.add("registered");
                            thread = new Thread(() -> { try { new CountDownLatch(1).await(); } catch (InterruptedException done) { Thread.currentThread().interrupt(); } }, "owned-plugin-thread");
                            thread.setDaemon(true); thread.start();
                            ((AtomicReference)data.get("thread")).set(thread); ((AtomicReference)data.get("channel")).set(channel);
                            return new ReportRenderResult(new byte[0]);
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    }
                }
            }
            """;
}
