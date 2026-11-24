import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from 'playwright';

const resources = resolve(process.env.TAXONOMY_TEST_RESOURCE_ROOT || 'taxonomy-app/src/main/resources');

test('the editor previews a change set and only accepts it against the displayed Git stand', async () => {
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE,
        args: ['--no-sandbox', '--disable-dev-shm-usage']});
    try {
        const page = await browser.newPage();
        const template = await readFile(resolve(resources, 'templates/architecture-editor.html'), 'utf8');
        await page.setContent('<main id="editor-fixture"></main>');
        await page.evaluate(html => {
            const documentWithEditor = new DOMParser().parseFromString(html, 'text/html');
            const panel = documentWithEditor.querySelector('#editorChangeSetPanel');
            if (panel) document.getElementById('editor-fixture').append(panel);
        }, template);
        assert.equal(await page.locator('#editorChangeSetPanel').count(), 1, 'editor has a change set entry point');
        await page.addScriptTag({path: resolve(resources, 'static/js/architecture-change-set.js')});
        await page.evaluate(() => {
            const calls = window.changeSetCalls = [];
            const context = {repositoryId: 'repository', workspaceId: 'workspace', workspaceScopeKey: 'workspace',
                branch: 'draft', actor: 'reviewer', commit: '1'.repeat(40), revision: 7};
            const view = window.changeSetView = {mayEdit: true, document: {source: 'WORKSPACE_REVISION',
                projectionState: 'READY', context, checkpointRevision: 7},
                schema: {elementTypes: ['Capability'], relationTypes: ['SUPPORTS']},
                model: {elements: [{id: 'existing', title: 'Existing capability'}],
                    requirements: [{id: 'requirement-1', title: 'Retain readings'}]}};
            const panel = window.changeSetPanel = window.TaxonomyArchitectureChangeSet.create({
                root: document.getElementById('editor-fixture'),
                t: (key, ...parameters) => [key, ...parameters].join(' '), lock: () => {},
                newMetadata: rationale => ({commandId: `00000000-0000-0000-0000-${String(calls.length + 1).padStart(12, '0')}`, rationale}),
                report: (error, node) => { node.textContent = error.message; }, onAccepted: () => {},
                api: {
                    async previewChangeSet(request) {
                        calls.push(['preview', request]);
                        return {context: request.context, fingerprint: 'a'.repeat(64), changes: [
                            {id: 'element:retention', before: null, after: 'Create retention capability'},
                            {id: 'relation:retention SUPPORTS existing', before: null, after: 'Connect retention'},
                            {id: 'mapping:requirement-1 -> retention', before: null, after: 'Allocate requirement'}
                        ]};
                    },
                    async acceptChangeSet(request) {
                        calls.push(['accept', request]);
                        return {commitId: '3'.repeat(40)};
                    }
                }
            });
            panel.update(view, true);
        });

        const field = name => page.locator('#editorChangeSet' + name);
        await field('Panel').locator('summary').click();
        await field('Id').fill('retention');
        await field('Title').fill('Retain sensor readings');
        await field('Type').selectOption('Capability');
        await field('Endpoint').selectOption('existing');
        await field('Relation').selectOption('SUPPORTS');
        await field('Requirement').selectOption('requirement-1');
        await field('Rationale').fill('Use the existing sensor service');
        await field('Preview').click();
        await field('Review').waitFor({state: 'visible'});
        assert.equal(await field('Changes').locator('h3').count(), 3);
        assert.match(await field('Target').textContent(), /draft 1{40} Use the existing sensor service/);

        await page.evaluate(() => {
            const view = window.changeSetView;
            const context = {...view.document.context, commit: '2'.repeat(40), revision: 8};
            window.changeSetPanel.update({...view, document: {...view.document, checkpointRevision: 8, context}}, true);
        });
        assert.equal(await field('Review').isHidden(), true);
        assert.equal(await field('Accept').isDisabled(), true);
        assert.equal(await page.evaluate(() => window.changeSetCalls.length), 1);

        await field('Preview').click();
        await field('Review').waitFor({state: 'visible'});
        await field('Accept').click();
        await page.waitForFunction(() => document.getElementById('editorChangeSetReceipt').textContent.includes('3'.repeat(40)));
        const [initial, reviewed, published] = await page.evaluate(() => window.changeSetCalls);
        assert.deepEqual([initial[0], reviewed[0], published[0]], ['preview', 'preview', 'accept']);
        assert.equal(published[1].context.commit, '2'.repeat(40));
        assert.equal(published[1].allocation.requirementId, 'requirement-1');
        assert.deepEqual(published[1], {...reviewed[1], previewFingerprint: 'a'.repeat(64)});
    } finally {
        await browser.close();
    }
});
