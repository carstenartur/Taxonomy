package com.taxonomy.composition.analysis.artemis;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Whole production Spring startup, not the separate frozen-data/cache microbenchmark. */
@Tag("onnx")
class WorkerRuntimeFootprintTest {
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void recordsWorkerAndFullCatalogueStartupWithoutAssumingAMemorySaving() throws Exception {
        String model = System.getenv("TAXONOMY_EMBEDDING_MODEL_DIR");
        if (model == null || model.isBlank()) throw new AssertionError("The pinned local ONNX model is required");
        WorkerRuntimeFootprintHarness.measure(Path.of(System.getProperty("taxonomy.footprint.output-directory",
                "target/worker-runtime-footprint")), Path.of(model));
    }
}
