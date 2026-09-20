package com.taxonomy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.taxonomy.ArchitectureModuleGraph.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ordinary full-reactor test; reuses the module gate rather than inventing a POM parser. */
class ArchitectureDocumentationTest {
    private static final Set<String> MODULES = Set.of("taxonomy-a", "taxonomy-b");
    private static final Set<String> EDGES = Set.of("taxonomy-a -> taxonomy-b");
    private static final String TABLE = "| `taxonomy-a` | A |\n| `taxonomy-b` | B |\n";
    private static final String GRAPH = """
            <!-- architecture-feature-graph:start -->
            ```mermaid
            flowchart TB
                taxonomy-a["taxonomy-a"]
                taxonomy-b["taxonomy-b"]
                taxonomy-a --> taxonomy-b
            ```
            <!-- architecture-feature-graph:end -->
            """;

    @Test
    void currentInventoriesAndFeatureGraphsMatchTheActualReactorAndPomDependencies() throws Exception {
        Path root = repositoryRoot();
        JsonNode json = new ObjectMapper().readTree(Files.readString(root.resolve(".github/architecture-contexts.json")));
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        List<Context> contexts = new ArrayList<>();
        for (JsonNode context : json.path("contexts")) {
            contexts.add(new Context(context.path("id").asString(),
                    context.path("targetModule").isNull() ? null : context.path("targetModule").asString(),
                    strings(context.path("packages"))));
        }
        Policy policy = new Policy(json.path("compositionModule").asString(),
                new HashSet<>(strings(json.path("rootCompositionClasses"))), contexts);
        var modules = ArchitectureModuleExtractionTest.discoverModules(root, policy);
        Set<String> inventory = modules.entrySet().stream()
                .filter(entry -> !entry.getValue().toAbsolutePath().normalize().equals(root))
                .map(java.util.Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> features = contexts.stream().map(Context::targetModule)
                .filter(target -> target != null && !target.equals(policy.compositionModule()))
                .filter(modules::containsKey).collect(Collectors.toSet());
        Set<String> edges = ArchitectureModuleExtractionTest.readProductionModuleDependencies(root, modules, policy).stream()
                .filter(edge -> features.contains(edge.origin()) && features.contains(edge.target()))
                .map(edge -> edge.origin() + " -> " + edge.target()).collect(Collectors.toSet());
        assertThat(inventory).isNotEmpty();
        assertThat(features).isNotEmpty();
        for (String file : List.of("README.md", "docs/en/MODULE_BOUNDARIES.md", "docs/de/MODULE_BOUNDARIES.md")) {
            String document = Files.readString(root.resolve(file));
            assertThatCode(() -> ArchitectureDocumentation.checkInventory(document, inventory))
                    .as(file).doesNotThrowAnyException();
        }
        for (String file : List.of("docs/en/ARCHITECTURE.md", "docs/de/ARCHITECTURE.md")) {
            String document = Files.readString(root.resolve(file));
            assertThatCode(() -> ArchitectureDocumentation.checkFeatureGraph(document, features, edges))
                    .as(file).doesNotThrowAnyException();
        }
    }

    @Test
    void exactInventoryAndGraphAreAcceptedIncludingWindowsLineEndings() {
        ArchitectureDocumentation.checkInventory(TABLE, MODULES);
        ArchitectureDocumentation.checkFeatureGraph(GRAPH, MODULES, EDGES);
        ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("\n", "\r\n"), MODULES, EDGES);
    }

    @Test
    void missingExtraAndDuplicateModuleRowsAreRejected() {
        assertThatThrownBy(() -> ArchitectureDocumentation.checkInventory(TABLE.replace("| `taxonomy-b` | B |\n", ""), MODULES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("missing=[taxonomy-b]");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkInventory(TABLE + "| `taxonomy-c` | C |\n", MODULES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("extra=[taxonomy-c]");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkInventory(TABLE + TABLE, MODULES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Duplicate module row");
    }

    @Test
    void missingOrDuplicatedMarkersCannotMakeTheGraphCheckVacuous() {
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph("```mermaid\nflowchart TB\n```", MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("markers");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH + GRAPH, MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Duplicate feature-graph markers");
    }

    @Test
    void missingReversedAndExtraDependenciesAreRejected() {
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a --> taxonomy-b", ""), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("missing=[taxonomy-a -> taxonomy-b]");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a --> taxonomy-b", "taxonomy-b --> taxonomy-a"), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("extra=[taxonomy-b -> taxonomy-a]");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a --> taxonomy-b", "taxonomy-a --> taxonomy-b\n taxonomy-b --> taxonomy-a"), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("extra=[taxonomy-b -> taxonomy-a]");
    }

    @Test
    void undeclaredEndpointsAndDuplicateNodesOrEdgesAreRejected() {
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a --> taxonomy-b", "taxonomy-a --> taxonomy-c"), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Undeclared edge endpoint");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a --> taxonomy-b", "taxonomy-a[\"taxonomy-a\"]\n taxonomy-a --> taxonomy-b"), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Duplicate graph node");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a --> taxonomy-b", "taxonomy-a --> taxonomy-b\n taxonomy-a --> taxonomy-b"), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Duplicate graph edge");
    }

    @Test
    void misleadingLabelsAndUnsupportedSyntaxAreRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a[\"taxonomy-a\"]", "taxonomy-a[\"taxonomy-b\"]"), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("label differs");
        assertThatThrownBy(() -> ArchitectureDocumentation.checkFeatureGraph(GRAPH.replace("taxonomy-a --> taxonomy-b", "taxonomy-a -.-> taxonomy-b"), MODULES, EDGES))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Unrecognized");
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static Path repositoryRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Cannot find repository architecture policy");
        return root;
    }
}
