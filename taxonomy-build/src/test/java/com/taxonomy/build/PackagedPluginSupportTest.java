package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

class PackagedPluginSupportTest {
    @TempDir Path temporary;

    @Test
    void cachedThirdPartyArtifactsAreVisibleButHostArtifactsRemainIsolated() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("reactor"));
        for (String directory : new String[]{"org/example", "com/vendor", "com/taxonomy", ".cache"}) {
            Files.writeString(Files.createDirectories(cache.resolve(directory)).resolve("artifact.jar"), directory);
        }

        Path tail = temporary.resolve("third-party-cache");
        try {
            Files.createSymbolicLink(temporary.resolve("link-capability"), cache);
        } catch (UnsupportedOperationException | FileSystemException unavailable) {
            abort("Directory symlinks are unavailable");
        }
        assertThat(PackagedPluginSupport.linkThirdPartyCache(cache, tail)).isTrue();
        assertThat(tail.resolve("org/example/artifact.jar")).hasContent("org/example");
        assertThat(tail.resolve("com/vendor/artifact.jar")).hasContent("com/vendor");
        assertThat(tail.resolve("com/taxonomy")).doesNotExist();
        assertThat(tail.resolve(".cache")).doesNotExist();
    }

    @Test
    void missingOrUnrepresentableCacheUsesNormalMavenResolution() throws Exception {
        assertThat(PackagedPluginSupport.linkThirdPartyCache(temporary.resolve("missing"), temporary.resolve("tail"))).isFalse();
        assertThat(PackagedPluginSupport.linkThirdPartyCache(temporary, temporary.resolve("comma,in,path"))).isFalse();
    }
}
