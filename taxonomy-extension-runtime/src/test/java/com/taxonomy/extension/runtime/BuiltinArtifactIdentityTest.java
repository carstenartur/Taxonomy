package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class BuiltinArtifactIdentityTest {
    @TempDir Path temporary;

    @Test void exactArtifactNameOwnsTheBuiltinContribution() throws Exception {
        Path artifact = PluginJarFixture.create(temporary, "taxonomy-fixture-1.0.0.jar", "example.fixture");
        try (var loader = new URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, TaxonomyPlugin.class.getClassLoader())) {
            var provider = (TaxonomyPlugin) loader.loadClass("example.plugin.ExamplePlugin").getConstructor().newInstance();
            var catalog = BuiltinCatalog.create(provider.extensions());
            assertThat(catalog.snapshot().plugins()).extracting(p -> p.identity().id())
                    .containsExactly("taxonomy.builtin.taxonomy-fixture");
            assertThat(catalog.snapshot().plugins().getFirst().identity().artifactSha256())
                    .isEqualTo(BuiltinCatalog.fingerprint(artifact.toUri().toURL()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "unrelated-taxonomy-fixture-1.0.0.jar", "taxonomy-fixture-1.0.0.jar.bak",
            "taxonomy-fixture-1.0.0.jar!extra"})
    void embeddedModuleNameCannotImpersonateABuiltinArtifact(String filename) throws Exception {
        Path artifact = PluginJarFixture.create(temporary, filename, "example.fixture");
        try (var loader = new URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, TaxonomyPlugin.class.getClassLoader())) {
            var provider = (TaxonomyPlugin) loader.loadClass("example.plugin.ExamplePlugin").getConstructor().newInstance();
            assertThatThrownBy(() -> BuiltinCatalog.create(provider.extensions()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact location");
        }
    }
}
