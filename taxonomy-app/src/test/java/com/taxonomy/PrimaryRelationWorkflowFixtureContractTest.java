package com.taxonomy;

import com.taxonomy.dsl.validation.RelationMetamodel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The native proposal workflow must submit four distinct, metamodel-legal requests. */
class PrimaryRelationWorkflowFixtureContractTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void actualPrimaryWorkflowProducesFourIndependentLegalProposalsAfterExistingPairs(int duplicates) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/scripts/ui-primary-proposal-workflows.mjs"))) {
            root = root.getParent();
        }
        assertThat(root).as("Repository root for the actual proposal workflow").isNotNull();
        Path source = root.resolve(".github/scripts/ui-primary-proposal-workflows.mjs");
        Path requests = directory.resolve("proposal-requests.json");
        Path log = directory.resolve("generate.log");
        String script = """
                const fs = require('node:fs');
                const vm = require('node:vm');
                const assert = require('node:assert/strict');
                const source = fs.readFileSync(process.argv[1], 'utf8');
                const requests = [];
                const scope = vm.createContext({
                  csrfJson: async (_page, endpoint, options) => {
                    assert.equal(endpoint, '/api/proposals/from-hypothesis');
                    requests.push(options.body);
                    return requests.length <= Number(process.argv[3])
                      ? {status: 409, json: null} : {status: 200, json: {id: requests.length}};
                  }
                });
                vm.runInContext(source.replace(/^import .*;\\n/gm, '').replace(/^export /gm, ''), scope);
                // Provisioning/reload has its own owner. Execute the actual request producer,
                // without importing Playwright or duplicating its candidate list here.
                scope.ensureWorkspaceProjection = async () => {};
                scope.createPendingProposals({}, assert.ok).then(ids => {
                  assert.equal(ids.length, 4);
                  fs.writeFileSync(process.argv[2], JSON.stringify(requests));
                }).catch(error => {console.error(error); process.exitCode = 1;});
                """;
        String node = System.getProperty("taxonomy.node.executable");
        assertThat(node).as("Maven supplies the pinned JavaScript runtime").isNotBlank();
        Process process = new ProcessBuilder(node, "-e", script, source.toString(), requests.toString(), Integer.toString(duplicates))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("Proposal generation: " + log).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> proposals = new ObjectMapper().readValue(Files.readString(requests), List.class);
            assertThat(proposals).hasSize(4 + duplicates);
            assertThat(proposals).extracting(proposal -> proposal.get("sourceCode") + "/"
                    + proposal.get("relationType") + "/" + proposal.get("targetCode")).doesNotHaveDuplicates();
            assertThat(proposals).allSatisfy(proposal -> {
                String sourceRoot = (String) proposal.get("sourceCode");
                String targetRoot = (String) proposal.get("targetCode");
                String relationType = (String) proposal.get("relationType");
                // These actual requests select catalogue roots, exactly the endpoint types
                // passed by RelationProposalService to this same Java metamodel.
                assertThat(RelationMetamodel.isCompatible(sourceRoot, targetRoot, relationType))
                        .as("Actual proposal %s %s %s", sourceRoot, relationType, targetRoot).isTrue();
                assertThat(((Number) proposal.get("confidence")).doubleValue()).isEqualTo(0.73);
                assertThat(proposal.get("rationale")).isEqualTo("Primary workflow "
                        + sourceRoot + "-" + targetRoot + "-" + relationType);
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void actualManualWorkflowCreatesReplaysAndDeletesAMetamodelLegalRelation() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/scripts/ui-primary-relation-workflows.mjs"))) {
            root = root.getParent();
        }
        assertThat(root).as("Repository root for the actual manual relation workflow").isNotNull();
        Path source = root.resolve(".github/scripts/ui-primary-relation-workflows.mjs");
        Path requests = directory.resolve("manual-relation-requests.json");
        Path log = directory.resolve("manual-generate.log");
        String script = """
                const fs = require('node:fs');
                const vm = require('node:vm');
                const assert = require('node:assert/strict');
                const source = fs.readFileSync(process.argv[1], 'utf8');
                const template = fs.readFileSync(process.argv[3], 'utf8');
                const select = template.match(/<select\\b[^>]*id="newRelType"[^>]*>([\\s\\S]*?)<\\/select>/i);
                assert.ok(select, 'Use the actual manual relation type control');
                const offeredTypes = [...select[1].matchAll(/<option\\b[^>]*value="([^"]+)"[^>]*>/gi)]
                  .map(match => match[1]);
                assert.ok(offeredTypes.length > 0, 'The actual type control must offer its declared choices');
                const writes = [], events = [], fields = {}, commit = 'c'.repeat(40);
                let present = false, responseWaiter = null;
                const relation = () => ({sourceCode: fields['#newRelSource'], targetCode: fields['#newRelTarget'],
                  relationType: fields['#newRelType']});
                const scope = vm.createContext({URL, navigateArchitectureSubtab: async () => {},
                  fetch: async endpoint => {
                    assert.equal(endpoint, '/api/relations');
                    return {ok: true, json: async () => present ? [relation()] : []};
                  },
                  document: {querySelectorAll: selector => {
                    assert.equal(selector, '#relationsTableContainer tbody tr');
                    return present ? [{querySelectorAll: selector => {
                      assert.equal(selector, 'td');
                      return [relation().sourceCode, relation().targetCode, relation().relationType]
                        .map(textContent => ({textContent}));
                    }}] : [];
                  }}
                });
                function invoke(callback, argument) {
                  scope.argument = argument;
                  return vm.runInContext('(' + callback.toString() + ')(argument)', scope);
                }
                function locator(selector, options = {}) {
                  const filters = [];
                  return {
                    selector, options, getAttribute: async () => 'open', waitFor: async () => {},
                    fill: async value => {fields[selector] = value;},
                    selectOption: async value => {
                      assert.equal(selector, '#newRelType');
                      assert.ok(offeredTypes.includes(value), 'Actual manual relation type option is not offered: ' + value);
                      fields[selector] = value;
                    },
                    click: async () => {
                      if (selector === '#createRelationBtn') return;
                      if (selector === '.relation-delete-btn') {assert.equal(present, true); present = false; return;}
                      assert.equal(selector, '#createRelationSubmit');
                      const body = relation(), created = !present;
                      writes.push(body); present = true;
                      const response = {
                        url: () => 'https://taxonomy.example/taxonomy/api/architecture/relations/'
                          + body.sourceCode + '/' + body.relationType + '/' + body.targetCode + '?workspaceId=fixture',
                        request: () => ({method: () => 'PUT'}),
                        status: () => created ? 201 : 200,
                        headers: () => ({etag: '"' + commit + '"'}),
                        json: async () => ({changeKind: created ? 'ADDED' : 'UNCHANGED',
                          commitCreated: created, authoritativeCommitId: commit})
                      };
                      assert.ok(responseWaiter, 'The real workflow must await its exact mutation response');
                      assert.equal(responseWaiter.predicate(response), true);
                      responseWaiter.resolve(response); responseWaiter = null;
                    },
                    filter(option) {filters.push(option.has); return this;},
                    count: async () => Number(present && filters.every((filter, index) => {
                      assert.equal(filter.selector, 'td:nth-child(' + (index + 1) + ')');
                      return filter.options.hasText.test([relation().sourceCode, relation().targetCode, relation().relationType][index]);
                    })),
                    first() {return this;}, locator: child => locator(child)
                  };
                }
                const page = {locator,
                  waitForResponse: (predicate, options) => {
                    assert.equal(options.timeout, 15000);
                    return new Promise(resolve => {responseWaiter = {predicate, resolve};});
                  },
                  waitForFunction: async (callback, argument, options) => {
                    assert.equal(options.timeout, 15000);
                    assert.equal(invoke(callback, argument), true);
                  },
                  evaluate: (callback, argument) => invoke(callback, argument),
                  once: (event, callback) => {assert.equal(event, 'dialog'); callback({accept: () => {}});}
                };
                vm.runInContext(source.replace(/^import .*;\\n/gm, '').replace(/^export /gm, ''), scope);
                scope.runRelationWorkflows({page, evidence: {
                  passed: event => events.push(event), axeState: async () => {}, saveState: async () => {}
                }}).then(() => {
                  assert.deepEqual(events, ['relation create', 'relation idempotent duplicate upsert', 'relation delete confirmation']);
                  assert.equal(writes.length, 2); assert.deepEqual(writes[0], writes[1]);
                  assert.equal(present, false); assert.equal(responseWaiter, null);
                  fs.writeFileSync(process.argv[2], JSON.stringify(writes));
                }).catch(error => {console.error(error); process.exitCode = 1;});
                """;
        String node = System.getProperty("taxonomy.node.executable");
        assertThat(node).as("Maven supplies the pinned JavaScript runtime").isNotBlank();
        Process process = new ProcessBuilder(node, "-e", script, source.toString(), requests.toString(),
                root.resolve("taxonomy-app/src/main/resources/templates/index.html").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("Manual relation generation: " + log).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> writes = new ObjectMapper().readValue(Files.readString(requests), List.class);
            assertThat(writes).hasSize(2);
            assertThat(writes.get(1)).isEqualTo(writes.getFirst());
            Map<String, Object> relation = writes.getFirst();
            assertThat(RelationMetamodel.isCompatible((String) relation.get("sourceCode"),
                    (String) relation.get("targetCode"), (String) relation.get("relationType")))
                    .as("Actual manual relation %s", relation).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

}
