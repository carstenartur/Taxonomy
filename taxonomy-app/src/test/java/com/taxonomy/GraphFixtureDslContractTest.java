package com.taxonomy;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The native graph fixture must produce a nonempty model understood by the real Java parser. */
class GraphFixtureDslContractTest {
    @TempDir Path directory;

    @Test
    void screenshotGraphRetainsAllFourCatalogueElementsAndThreeAcceptedRelations() {
        String text = SavedGraphFixtureDsl.create(Map.of("CP-1023", "Capability",
                "CR-1047", "Core \"service\"", "BP-1327", "First process", "BP-1000", "Second process"));
        var commands = new ArchitectureDslCommands();
        var model = commands.model(text);
        assertThat(model.getElements()).extracting(ArchitectureElement::getId)
                .containsExactlyInAnyOrder("CP-1023", "CR-1047", "BP-1327", "BP-1000");
        assertThat(model.getRelations()).hasSize(3).extracting(ArchitectureRelation::getStatus)
                .containsOnly("accepted");
        assertThat(model.findElement("CR-1047").orElseThrow().getTitle()).isEqualTo("Core \"service\"");
        commands.validateReconciledSource(text);
    }

    @Test
    void primaryWorkflowPersistsTheActualCatalogueElementsAndAcceptedRelation() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/scripts/ui-primary-basic-workflows.mjs"))) {
            root = root.getParent();
        }
        assertThat(root).as("Repository root for the actual primary workflow").isNotNull();
        Path source = root.resolve(".github/scripts/ui-primary-basic-workflows.mjs");
        Path dsl = directory.resolve("primary-graph.taxdsl");
        Path log = directory.resolve("generate.log");
        String script = """
                const fs = require('node:fs');
                const vm = require('node:vm');
                const source = fs.readFileSync(process.argv[1], 'utf8');
                const scope = vm.createContext({URLSearchParams});
                vm.runInContext(source.replace(/^import .*;\\n/gm, '').replace(/^export /gm, ''), scope);
                const commit = 'c'.repeat(40), repositoryId = 'fixture-repository';
                const response = value => ({ok: () => true, json: async () => value});
                const central = {repositoryId, workspaceId: null, branch: 'fixture', currentHeadCommit: commit};
                const api = {
                  get: async path => {
                    if (path === 'taxonomy') return response([
                      {code: 'CR', children: [{code: 'CR-321', nameEn: 'Catalogue "service"'}]},
                      {code: 'BP', children: [{code: 'BP-654', nameEn: 'Catalogue process'}]}
                    ]);
                    if (path.startsWith('architecture/relations/projection/readiness?')) return response(central);
                    throw new Error('Unexpected graph fixture GET: ' + path);
                  },
                  post: async (path, options) => {
                    if (path === 'repositories') return response({repositoryId});
                    if (path.startsWith('architecture/editor/workspaces?')) return response({
                      workspaceId: 'fixture-workspace', currentBranch: 'private-fixture', provisioningStatus: 'READY',
                      sourceRepositoryId: repositoryId, sourceBranch: 'fixture'});
                    if (path.startsWith('dsl/commit?')) {
                      fs.writeFileSync(process.argv[2], options.data);
                      return response({valid: true, commitId: 'a'.repeat(40)});
                    }
                    if (path.startsWith('workspace/publish?')) return response({success: true, mergeCommit: commit});
                    if (path.startsWith('architecture/relations/projection/rebuild?')) return response({
                      ...central, readinessState: 'READY', projectedCommit: commit, relationCount: 1});
                    throw new Error('Unexpected graph fixture POST: ' + path);
                  }
                };
                scope.seedSavedGraph(api, 'fixture').catch(error => {console.error(error); process.exitCode = 1;});
                """;
        String node = System.getProperty("taxonomy.node.executable");
        assertThat(node).as("Maven supplies the pinned JavaScript runtime").isNotBlank();
        Process process = new ProcessBuilder(node, "-e", script, source.toString(), dsl.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("Fixture generation: " + log).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            String text = Files.readString(dsl);
            var commands = new ArchitectureDslCommands();
            var model = commands.model(text);
            assertThat(model.getElements()).extracting(ArchitectureElement::getId)
                    .containsExactlyInAnyOrder("CR-321", "BP-654");
            assertThat(model.getRelations()).hasSize(1);
            assertThat(model.getRelations().getFirst().getStatus()).isEqualTo("accepted");
            assertThat(model.getRelations().getFirst().getSourceId()).isEqualTo("CR-321");
            assertThat(model.getRelations().getFirst().getTargetId()).isEqualTo("BP-654");
            assertThat(model.getElements()).extracting(ArchitectureElement::getTitle)
                    .containsExactlyInAnyOrder("Catalogue \"service\"", "Catalogue process");
            commands.validateReconciledSource(text);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
