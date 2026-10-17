package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JavascriptJUnitBridgeTest {
    @TempDir Path temp;

    @Test void keepsEveryDeclarationAndAsyncSubtestOutcomeWithoutNodeTestRunner() throws Exception {
        Path fixture = temp.resolve("contract.mjs");
        Files.writeString(fixture, """
            import test, {before,after} from 'node:test';
            import assert from 'node:assert/strict';
            let started = false;
            before(async () => { started = true; });
            after(() => { assert.ok(!started); });
            test('asynchronous parent', async t => {
                t.after(() => { started = false; });
                assert.ok(started);
                await t.test('successful nested assertion', async () => assert.equal(await Promise.resolve(2), 2));
                await t.test('failed nested assertion', () => assert.equal(2, 3));
            });
            test('skipped declaration', {skip:'fixture reason'}, () => assert.fail('must not execute'));
            """);
        try (var bridge = new JavascriptJUnitBridge(fixture)) {
            assertEquals(2, bridge.names().size());
            var outcomes = bridge.execute(0);
            assertEquals(3, outcomes.size());
            assertEquals("failed", outcomes.get(0).status());
            assertEquals("passed", outcomes.get(1).status());
            assertEquals("failed", outcomes.get(2).status());
            assertTrue(outcomes.get(2).detail().contains("2 !== 3"));
            assertEquals("skipped", bridge.execute(1).getFirst().status());
        }
    }

    @Test void refusesUnsupportedTestApisRatherThanDroppingAssertions() throws Exception {
        Path fixture = temp.resolve("contract.mjs");
        Files.writeString(fixture, "import test from 'node:test'; test.only('hidden', () => {});");
        var failure = assertThrows(Exception.class, () -> new JavascriptJUnitBridge(fixture));
        assertTrue(failure.getMessage().contains("only"), failure.getMessage());
    }

    @Test void ownsStandaloneAssertionsAndCommonJsDeclarations() throws Exception {
        Path fixture = temp.resolve("contract.cjs");
        Files.writeString(fixture, "const {test} = require('node:test'); test('commonjs', () => require('node:assert/strict').equal(1, 1));");
        try(var bridge = new JavascriptJUnitBridge(fixture)) {
            assertEquals("commonjs", bridge.names().getFirst());
            assertEquals("passed", bridge.execute(0).getFirst().status());
        }
        Path standalone = temp.resolve("standalone.mjs");
        Files.writeString(standalone, "import assert from 'node:assert/strict'; assert.fail('standalone assertion');");
        try(var bridge = new JavascriptJUnitBridge(standalone)) {
            assertEquals(1, bridge.names().size());
            assertEquals("failed", bridge.execute(0).getFirst().status());
        }
    }
}
