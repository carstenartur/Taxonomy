package com.taxonomy.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/** Placement only; execution of the moved tests remains the responsibility of Surefire. */
final class FeatureTestOwnershipContract {
    static final Map<String, String> OWNERS = Map.ofEntries(
        Map.entry("com/taxonomy/editor/ArchitectureEditorControllerTest.java", "taxonomy-workspace"),
        Map.entry("com/taxonomy/editor/ArchitectureEditorVersionContextTest.java", "taxonomy-workspace"),
        Map.entry("com/taxonomy/editor/ArchitectureEditorRestartTest.java", "taxonomy-workspace"),
        Map.entry("com/taxonomy/editor/EditorWorkspaceArchitectureIntegrationAdapterTest.java", "taxonomy-workspace"),
        Map.entry("com/taxonomy/editor/ArchitectureEditorServiceTest.java", "taxonomy-workspace"),
        Map.entry("com/taxonomy/editor/EditorJournalFailureAtomicityTest.java", "taxonomy-workspace"),
        Map.entry("com/taxonomy/editor/EditorPersistenceFixture.java", "taxonomy-workspace"),
        Map.entry("com/taxonomy/ArchitectureHypothesisPublicationBoundaryTest.java", "taxonomy-knowledge"),
        Map.entry("com/taxonomy/ArchitectureProjectionReadBoundaryTest.java", "taxonomy-knowledge"),
        Map.entry("com/taxonomy/ArchitectureHypothesisHttpBoundaryTest.java", "taxonomy-knowledge"),
        Map.entry("com/taxonomy/ArchitectureRelationCommandBoundaryTest.java", "taxonomy-knowledge"),
        Map.entry("com/taxonomy/ArchitectureRelationStorageBoundaryTest.java", "taxonomy-knowledge"),
        Map.entry("com/taxonomy/ArchiMateIdentityImportTest.java", "taxonomy-knowledge"),
        Map.entry("com/taxonomy/dsl/DslAnalyzerTest.java", "taxonomy-knowledge"),
        Map.entry("com/taxonomy/VisioConverterTests.java", "taxonomy-export"),
        Map.entry("com/taxonomy/StructurizrInteroperabilityTest.java", "taxonomy-export"),
        Map.entry("com/taxonomy/analysis/service/LlmRecordReplayServiceTest.java", "taxonomy-analysis"),
        Map.entry("com/taxonomy/extension/api/report/ReportRenderResultTest.java", "taxonomy-extension-api"),
        Map.entry("com/taxonomy/interop/IntegrationJournalTest.java", "taxonomy-interop")
    );

    static void verify(Path root) throws Exception {
        var failures = new ArrayList<String>();
        for (var entry : OWNERS.entrySet()) {
            Path expected = root.resolve(entry.getValue()).resolve("src/test/java").resolve(entry.getKey());
            if (!Files.isRegularFile(expected)) failures.add("Missing owned test source: " + expected);
            try (var modules = Files.newDirectoryStream(root, "taxonomy-*")) {
                for (Path module : modules) {
                    Path candidate = module.resolve("src/test/java").resolve(entry.getKey());
                    if (!module.getFileName().toString().equals(entry.getValue()) && Files.exists(candidate))
                        failures.add("Test belongs to " + entry.getValue() + ", not " + module.getFileName() + ": " + entry.getKey());
                }
            }
        }
        if (!Files.isRegularFile(root.resolve("taxonomy-analysis/src/test/resources/llm-recordings/"
                + "sha256-c292fe61849d1dc5e334b26da23e4c25e326386cc2f767100acbb80ea9a3a575.json")))
            failures.add("Missing owned replay recording");
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected repository root");
        verify(Path.of(args[0]));
        System.out.println("FEATURE_TEST_OWNERSHIP_OK: " + OWNERS.size() + " test/fixture sources");
    }
}
