package com.taxonomy.shared.features;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.jar.*;
import static org.assertj.core.api.Assertions.*;

class FeatureVersionPreflightTest {
    @TempDir Path temporary;
    @Test void rejectsMismatchedOrUnversionedFeatureBeforeResolvingAnyClasses() throws Exception {
        for (String version : new String[]{"9.0.0", null}) {
            try (var loader = featureJar(version)) {
                assertThatThrownBy(() -> FeatureAssembly.discover(loader, "1.4.1-SNAPSHOT"))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Incompatible startup feature version: templates");
            }
        }
    }
    @Test void acceptsExactVersionWithoutLinkingTheFeatureClass() throws Exception {
        try (var loader = featureJar("1.4.1-SNAPSHOT")) {
            assertThat(FeatureAssembly.discover(loader, "1.4.1-SNAPSHOT").has("templates")).isTrue();
        }
    }
    private URLClassLoader featureJar(String version) throws Exception {
        Path file = Files.createTempFile(temporary, "feature-", ".jar");
        var manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (version != null) manifest.getMainAttributes().putValue("Implementation-Version", version);
        try (var jar = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            jar.putNextEntry(new JarEntry("com/taxonomy/templates/DocumentTemplateService.class"));
            jar.write(new byte[]{0, 1}); jar.closeEntry(); // Deliberately cannot be linked as Java bytecode.
        }
        return new URLClassLoader(new java.net.URL[]{file.toUri().toURL()}, null);
    }
}
