package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginClassLoader;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PluginArtifactLoadingTest {
    @TempDir Path temporary;
    Path root() throws Exception { return Files.createDirectories(temporary.resolve("operator plugins")); }
    Path cache() { return temporary.resolve("private-cache"); }
    @Test void independentlyCompiledJarWithSpacesSharesOnlyParentSdkIdentity() throws Exception {
        Path jar = PluginJarFixture.create(root(), "example plugin.jar", "example.renderer");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var catalog = new PluginCatalog();
        try (var runtime = new Pf4jPluginRuntime(root(), cache(), catalog)) {
            var identity = runtime.install(jar); assertThat(identity.artifactSha256()).isEqualTo(digest);
            assertThat(catalog.snapshot().extensions()).isEmpty(); runtime.start(identity.id());
            PluginClassLoader loader;
            try (var lease = catalog.acquire(ExtensionKey.report("external", "txt"), ReportRendererExtension.class)) {
                assertThat(lease.plugin()).isEqualTo(identity);
                assertThat(lease.extension().render(ReportRenderContext.ofPayload("frozen payload")).utf8()).isEqualTo("frozen payload");
                loader = (PluginClassLoader) lease.extension().getClass().getClassLoader();
                assertThat(loader).isNotSameAs(TaxonomyPlugin.class.getClassLoader());
                assertThat(loader.loadClass(ReportRendererExtension.class.getName())).isSameAs(ReportRendererExtension.class);
            }
            assertThat(runtime.stop(identity.id(), Duration.ZERO)).isTrue(); runtime.unload(identity.id());
            assertThat(loader.isClosed()).isTrue(); assertThat(runtime.openClassLoaders()).isZero();
            assertThat(catalog.snapshot().extensions()).isEmpty();
        }
    }
    @Test void replacingTheOperatorFileCannotChangeTheInstalledBytes() throws Exception {
        Path jar = PluginJarFixture.create(root(), "replace.jar", "example.renderer");
        var catalog = new PluginCatalog();
        try (var runtime = new Pf4jPluginRuntime(root(), cache(), catalog)) {
            var identity = runtime.install(jar);
            Files.writeString(jar, "replaced after admission");
            runtime.start(identity.id());
            try (var lease = catalog.acquire(ExtensionKey.report("external", "txt"), ReportRendererExtension.class)) {
                assertThat(lease.extension().render(ReportRenderContext.ofPayload("original")).utf8()).isEqualTo("original");
                assertThat(lease.plugin()).isEqualTo(identity);
            }
        }
    }
    @Test void wrongHostRangeMissingDependencyAndDuplicatePluginLeavePriorCatalogUnchanged() throws Exception {
        var catalog = new PluginCatalog();
        try (var runtime = new Pf4jPluginRuntime(root(), cache(), catalog)) {
            var existing = runtime.install(PluginJarFixture.create(root(), "existing.jar", "example.existing")); runtime.start(existing.id());
            var before = catalog.snapshot();
            for (var invalid : List.of(Map.of("Plugin-Requires", ">=2.0.0"), Map.of("Plugin-Dependencies", "example.missing@>=1.0.0"))) {
                Path jar = PluginJarFixture.create(root(), "invalid.jar", "example.invalid", invalid, "return List.of(new Renderer());", Map.of());
                assertThatThrownBy(() -> runtime.install(jar)).isInstanceOf(IllegalArgumentException.class);
                assertThat(catalog.snapshot()).isSameAs(before); assertThat(runtime.openClassLoaders()).isEqualTo(1);
            }
            Path duplicate = PluginJarFixture.create(root(), "duplicate.jar", "example.existing");
            assertThatThrownBy(() -> runtime.install(duplicate)).isInstanceOf(IllegalArgumentException.class);
            assertThat(catalog.snapshot()).isSameAs(before); assertThat(runtime.openClassLoaders()).isEqualTo(1);
        }
    }
    @Test void embeddedSdkCopiesAreRejectedBeforeClassloading() throws Exception {
        for (String copy : List.of("com/taxonomy/extension/api/plugin/TaxonomyPlugin.class", "META-INF/versions/21/com/taxonomy/extension/api/report/ReportRendererExtension.class")) {
            Path jar = PluginJarFixture.create(root(), "sdk-copy.jar", "example.copy", Map.of(), "return List.of(new Renderer());", Map.of(copy, new byte[]{0, 1}));
            var catalog = new PluginCatalog();
            try (var runtime = new Pf4jPluginRuntime(root(), cache(), catalog)) {
                assertThatThrownBy(() -> runtime.install(jar)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SDK");
                assertThat(runtime.openClassLoaders()).isZero(); assertThat(catalog.snapshot().plugins()).isEmpty();
            }
        }
    }
    @Test void malformedServiceAndFailingContributionCloseTheFailedLoaderWithoutPublication() throws Exception {
        for (boolean brokenService : List.of(true, false)) {
            Path jar = PluginJarFixture.create(root(), "broken.jar", "example.broken", Map.of(),
                    brokenService ? "return List.of(new Renderer());" : "throw new IllegalStateException(\"simulated start failure\");",
                    brokenService ? Map.of(PluginJarFixture.SERVICE, "example.plugin.Missing\n".getBytes(StandardCharsets.UTF_8)) : Map.of());
            var catalog = new PluginCatalog();
            try (var runtime = new Pf4jPluginRuntime(root(), cache(), catalog)) {
                var id = runtime.install(jar);
                assertThatThrownBy(() -> runtime.start(id.id())).isInstanceOf(IllegalStateException.class);
                assertThat(catalog.snapshot().extensions()).isEmpty(); assertThat(runtime.openClassLoaders()).isZero();
                assertThat(runtime.installed()).isEmpty();
            }
        }
    }
    @Test void immutableAdmissionRejectsAChangedManifestIdentityBeforeLoading() throws Exception {
        Path artifact=PluginJarFixture.create(root(),"changing.jar","example.original");
        var expected=new PluginArtifactValidator().validate(artifact).identity();
        PluginJarFixture.create(root(),"changing.jar","example.replacement");
        try(var runtime=new Pf4jPluginRuntime(root(),cache(),new PluginCatalog())) {
            assertThatThrownBy(() -> runtime.install(artifact,expected)).isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.installed()).isEmpty();assertThat(runtime.openClassLoaders()).isZero();
        }
    }
    @Test void failedStartAndLinkageFailureDuringCloseStillReleaseTheLoader() throws Exception {
        String code="""
            package example.plugin;
            public class ExamplePlugin implements com.taxonomy.extension.api.plugin.TaxonomyPlugin {
                public java.util.List<com.taxonomy.shared.extension.TaxonomyExtension> extensions() {throw new IllegalStateException("broken start");}
                public void close() {throw new NoClassDefFoundError("broken cleanup");}
            }
            """;
        Path artifact=PluginJarFixture.createSource(root(),"broken-close.jar","example.broken",Map.of(),code,Map.of());
        try(var runtime=new Pf4jPluginRuntime(root(),cache(),new PluginCatalog())) {
            runtime.install(artifact);
            assertThatThrownBy(() -> runtime.start("example.broken")).isInstanceOf(IllegalStateException.class);
            assertThat(runtime.installed()).isEmpty();assertThat(runtime.openClassLoaders()).isZero();
        }
    }
    @Test void outsideFilesAndEscapingSymlinksAreRejected() throws Exception {
        Path outside = PluginJarFixture.create(temporary.resolve("elsewhere"), "outside.jar", "example.outside");
        var catalog = new PluginCatalog();
        try (var runtime = new Pf4jPluginRuntime(root(), cache(), catalog)) {
            assertThatThrownBy(() -> runtime.install(outside)).isInstanceOf(IllegalArgumentException.class);
            Path link = root().resolve("linked.jar"); Files.createSymbolicLink(link, outside);
            assertThatThrownBy(() -> runtime.install(link)).isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.openClassLoaders()).isZero();
        }
    }
}
