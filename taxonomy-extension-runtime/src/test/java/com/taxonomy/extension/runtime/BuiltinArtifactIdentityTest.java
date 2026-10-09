package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.stream.Stream;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class BuiltinArtifactIdentityTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exactArtifactNameOwnsTheBuiltinContribution(boolean jarRoot) throws Exception {
        Path artifact = PluginJarFixture.create(temporary, "taxonomy-fixture-1.0.0.jar", "example.fixture");
        try (var loader = loader(artifact, jarRoot)) {
            var provider = (TaxonomyPlugin) loader.loadClass("example.plugin.ExamplePlugin").getConstructor().newInstance();
            var catalog = BuiltinCatalog.create(provider.extensions());
            assertThat(catalog.snapshot().plugins()).extracting(p -> p.identity().id())
                    .containsExactly("taxonomy.builtin.taxonomy-fixture");
            assertThat(catalog.snapshot().plugins().getFirst().identity().artifactSha256())
                    .isEqualTo(BuiltinCatalog.fingerprint(artifact.toUri().toURL()));
        }
    }

    static Stream<Arguments> invalidArtifacts() {
        return Stream.of("unrelated-taxonomy-fixture-1.0.0.jar", "taxonomy-fixture-1.0.0.jar.bak",
                "taxonomy-fixture-1.0.0.jar!extra", "unrelated.jar!taxonomy-fixture-1.0.0.jar")
                .flatMap(name -> Stream.of(false, true).map(jarRoot -> Arguments.of(name, jarRoot)));
    }

    @ParameterizedTest @MethodSource("invalidArtifacts")
    void embeddedModuleNameCannotImpersonateABuiltinArtifact(String filename, boolean jarRoot) throws Exception {
        Path artifact = PluginJarFixture.create(temporary, filename, "example.fixture");
        try (var loader = loader(artifact, jarRoot)) {
            var provider = (TaxonomyPlugin) loader.loadClass("example.plugin.ExamplePlugin").getConstructor().newInstance();
            assertThatThrownBy(() -> BuiltinCatalog.create(provider.extensions()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact location");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"jar:file:/opt/taxonomy-app-1.4.1.jar!/BOOT-INF/lib/",
            "jar:nested:/opt/taxonomy-app-1.4.1.jar/!BOOT-INF/lib/"})
    void nestedArchiveNameCannotImpersonateABuiltinArtifact(String prefix) throws Exception {
        String filename = "unrelated.jar!taxonomy-fixture-1.0.0.jar";
        Path artifact = PluginJarFixture.create(temporary, filename, "example.fixture");
        URL source = new URL(null, prefix + filename + "!/", new java.net.URLStreamHandler() {
            @Override protected java.net.URLConnection openConnection(URL url) {
                throw new AssertionError("Invalid artifact identity must be rejected before reading it");
            }
        });
        try (var loader = loader(artifact, source)) {
            var provider = (TaxonomyPlugin) loader.loadClass("example.plugin.ExamplePlugin").getConstructor().newInstance();
            assertThatThrownBy(() -> BuiltinCatalog.create(provider.extensions()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact location");
        }
    }

    // Exercise both actual CodeSource representations. URLClassLoader itself normalizes
    // jar:file roots to file URLs, unlike other launchers using the same JAR bytes.
    private static URLClassLoader loader(Path artifact, boolean jarRoot) throws Exception {
        URL file = artifact.toUri().toURL();
        URL source = jarRoot ? new URL("jar:" + file + "!/") : file;
        return loader(artifact, source);
    }

    private static URLClassLoader loader(Path artifact, URL source) throws Exception {
        return new URLClassLoader(new URL[] {artifact.toUri().toURL()}, TaxonomyPlugin.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                URL resource = findResource(name.replace('.', '/') + ".class");
                if (resource == null) throw new ClassNotFoundException(name);
                try (var input = resource.openStream()) {
                    byte[] bytes = input.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length, new java.security.ProtectionDomain(
                            new CodeSource(source, (Certificate[]) null), null, this, null));
                } catch (java.io.IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
        };
    }
}
