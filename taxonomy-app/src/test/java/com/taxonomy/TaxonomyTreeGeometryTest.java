package com.taxonomy;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast real-D3 regression owned by JUnit, independent of browser/server startup. */
class TaxonomyTreeGeometryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"expansion", "expand-all", "geometry", "empty", "contrast", "evidence"})
    void treeLayoutHonoursVisibleDepthAndOccupiedBounds(String scenario) throws Exception {
        Path script = copy("/tree-layout.cjs", "contract.cjs");
        Path views = copy("/static/js/core/taxonomy-views.js", "views.js");
        Path d3 = copy("/META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js", "d3.js");
        Path utils = copy("/static/js/shared/taxonomy-utils.js", "utils.js");
        Path log = directory.resolve("result.log");
        Process process = new ProcessBuilder(System.getProperty("taxonomy.node.executable", "node"),
                script.toString(), views.toString(), d3.toString(), scenario, utils.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertThat(finished).as(Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
    }

    private Path copy(String resource, String name) throws Exception {
        Path destination = directory.resolve(name);
        try (var input = getClass().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing tree regression resource " + resource);
            Files.copy(input, destination);
        }
        return destination;
    }
}
