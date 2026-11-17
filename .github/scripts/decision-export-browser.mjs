// Focused native-browser contract for the shared dialog, independent of external services.
import assert from 'node:assert/strict';
import {readFile, mkdir, writeFile} from 'node:fs/promises';
import {resolve, join} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const {chromium} = await import(process.env.TAXONOMY_PLAYWRIGHT_MODULE || '@playwright/test');
const sourcePath = new URL('../../taxonomy-app/src/main/resources/static/js/shared/decision-export-dialog.js',import.meta.url);
const source = await readFile(sourcePath,'utf8');
const capabilitiesSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/plugin-capabilities.js',import.meta.url),'utf8');
const browser = await chromium.launch({headless:true, ...(process.env.TAXONOMY_CHROME ? {executablePath:process.env.TAXONOMY_CHROME} : {}),args:['--no-sandbox']});
try {
    for (const width of [1280,390]) {
        const page = await browser.newPage({viewport:{width,height:850}});
        await page.setContent('<html lang="de"><head></head><body><button id="open">Bericht</button></body></html>');
        await page.addScriptTag({content:capabilitiesSource});
        // This component fixture replaces the named API; transport has separate contract tests.
        await page.evaluate(() => { window.TaxonomyCapabilitiesApi = {load:async()=>({features:['reporting'],exports:[],reports:
            ['docx','html','json'].map(id=>({id,reportType:'decision-rationale',displayName:id,fileExtension:id,contentType:id==='docx'?'application/vnd.openxmlformats-officedocument.wordprocessingml.document':id==='html'?'text/html':'application/json',plugin:{id:'fixture.reporting',version:'1.0.0',artifactSha256:'a'.repeat(64)}}))})}; });
        await page.addScriptTag({content:source});
        await page.evaluate(()=>{
            window.attempts=[];
            window.TaxonomyResponsibilityDisplay = {enabled: () => true};
            document.getElementById('open').onclick=()=>window.TaxonomyDecisionExport.open({source:'Anforderung 42 · Snapshot saved-1',saved:true,
                roots:[{code:'CP',title:'Fähigkeiten'},{code:'BP',title:'Geschäftsprozesse'},{code:'IP',title:'Produkte',inAnalysisScope:false}],
                analysisScope:{taxonomyRoots:['CP','BP'],mode:'TAXONOMIES_ONLY'},selectedRoots:['CP'],
                submit:async value=>{window.attempts.push(value);if(window.attempts.length===1)throw new Error('Baum passt nicht auf A4. Wählen Sie Automatisch.');}
            });
        });
        await page.click('#open');
        const dialog=page.locator('dialog');await dialog.waitFor();
        assert.equal(await page.locator('[name=root]:checked').count(),1);
        assert.equal(await page.locator('[value=IP]').isDisabled(),true);
        assert.equal(await page.locator('[name=profile]').inputValue(),'STANDARD');
        const bounds=await dialog.boundingBox();assert.ok(bounds.x>=0&&bounds.x+bounds.width<=width);
        await page.uncheck('[value=CP]');await page.click('[type=submit]');
        assert.match(await page.locator('[role=alert]').textContent(),/mindestens/);
        assert.equal(await page.evaluate(()=>window.attempts.length),0);
        await page.check('[value=BP]');await page.click('[type=submit]');
        await page.locator('[role=alert]').filter({hasText:'Baum passt'}).waitFor();
        assert.equal(await page.locator('[value=BP]').isChecked(),true);
        assert.equal(await page.locator('[type=submit]').isEnabled(),true);
        await page.selectOption('[name=format]','json');
        assert.equal(await page.locator('[name=contents]').isVisible(),false);
        await page.selectOption('[name=format]','docx');
        await page.click('summary');
        assert.equal(await page.locator('[name=showResponsibilities]').isChecked(), true);
        await page.uncheck('[name=showResponsibilities]');
        if(process.env.TAXONOMY_UI_OUTPUT) await page.screenshot({path:process.env.TAXONOMY_UI_OUTPUT+'/dialog-'+width+'.png'});
        await page.click('[type=submit]');await dialog.waitFor({state:'detached'});
        assert.equal(await page.locator('#open').evaluate(el=>el===document.activeElement),true);
        const attempts=await page.evaluate(()=>window.attempts);
        assert.deepEqual(attempts[1].options.taxonomyRoots,['BP']);
        assert.equal(attempts[0].options.showResponsibilities, true);
        assert.equal('showResponsibilities' in attempts[1].options, false);
        await page.click('#open');await page.keyboard.press('Escape');await page.locator('dialog').waitFor({state:'detached'});
        assert.equal(await page.locator('#open').evaluate(el=>el===document.activeElement),true);
        // Drawings export only the evidence available for the selected snapshot.
        await page.evaluate(() => {
            const format = {id:'visio-pdf', reportType:'decision-rationale', displayName:'Visio PDF',
                fileExtension:'pdf', contentType:'application/pdf',
                plugin:{id:'fixture.reporting', version:'1.0.0', artifactSha256:'a'.repeat(64)}};
            window.TaxonomyCapabilitiesApi = {load:async()=>({features:['reporting'], exports:[], reports:[format]})};
            window.openDrawing = mode => window.TaxonomyDecisionExport.openSaved({
                projectId:41, snapshotId:'saved-1', api:{
                    decisionReportOptions:async()=>({roots:[{code:'BP', title:'Geschäftsprozesse'}],
                        analysisLanguage:'de', analysisScope:{mode, taxonomyRoots:['BP']}}),
                    downloadDecisionReport:async(project, snapshot, format, language, options, request) => {
                        window.drawingRequest = {project, snapshot, format, language, options, timeout:request.timeoutMillis};
                        return new Response('%PDF-fixture', {headers:{'Content-Type':'application/pdf',
                            'X-Taxonomy-Analysis-SHA256':'a'.repeat(64), 'X-Taxonomy-Snapshot-Id':snapshot}});
                    }
                }
            });
        });
        for (const [mode, sections] of [['TAXONOMIES_ONLY',['TREE']], ['FULL',['TREE','ARCHITECTURE']]]) {
            await page.evaluate(mode => { window.openDrawing(mode); }, mode);
            assert.equal(await page.getByRole('radio', {name:'Am Bildschirm erkunden'}).count(), 1);
            assert.equal(await page.getByRole('radio', {name:'Am Bildschirm erkunden'}).isChecked(), true);
            assert.equal(await page.locator('[name=visioAppendix]').isChecked(), false);
            const units = page.getByRole('checkbox', {name:'Zuständigkeiten anzeigen', exact:true});
            assert.equal(await units.isChecked(), true);
            assert.equal(await page.locator('[name=visioPaper]').isVisible(), false);
            const wall = mode === 'FULL';
            if (wall) {
                await page.getByRole('radio', {name:'Als Wandplan drucken'}).check();
                assert.equal(await page.locator('[name=visioPaper]').inputValue(), 'A1');
                assert.deepEqual(await page.locator('[name=visioPaper] option').evaluateAll(nodes => nodes.map(n => n.value)),
                    ['A4','A3','A2','A1','A0']);
                await page.selectOption('[name=visioPaper]','A2');
                await units.uncheck();
                await page.check('[name=visioShortReasons]');
                await page.check('[name=visioAppendix]');
                await page.getByRole('button', {name:'Druckvorschau erstellen'}).click();
                const link = page.getByRole('link', {name:'Druckvorschau öffnen (neuer Tab)'});
                await link.waitFor();
                assert.equal(await link.evaluate(async a => (await (await fetch(a.href)).text())), '%PDF-fixture');
                if (process.env.TAXONOMY_UI_OUTPUT) {
                    await page.getByRole('group', {name:'Zeichnungslayout',exact:true}).screenshot({
                        path:join(process.env.TAXONOMY_UI_OUTPUT, 'wall-print-options-' + width + '.png')
                    });
                }
                const preview = await page.evaluate(() => window.drawingRequest);
                assert.equal(preview.format,'visio-pdf');
                assert.deepEqual(preview.options.visio,{mode:'WALL',paper:'A2',appendix:true,shortReasons:true});
                await page.selectOption('[name=visioPaper]','A0');
                assert.equal(await link.isVisible(), false);
            }
            const downloaded = page.waitForEvent('download');
            await page.click('button[type=submit]');
            assert.equal((await downloaded).suggestedFilename(), 'taxonomy-decision-report-saved-1.pdf');
            await page.locator('dialog').waitFor({state:'detached'});
            const request = await page.evaluate(() => window.drawingRequest);
            assert.equal(request.format,'visio-pdf');
            assert.equal(request.options.showResponsibilities,!wall);
            assert.deepEqual(request.options.visio, wall
                ? {mode:'WALL',paper:'A0',appendix:true,shortReasons:true}
                : {mode:'SCREEN',paper:'A1',appendix:false,shortReasons:false});
            assert.equal(request.timeout,120000);
            assert.equal(request.snapshot,'saved-1');
            assert.deepEqual(request.options.taxonomyRoots,['BP']);
            assert.deepEqual(request.options.sections,sections);
        }
        // Closing and reopening within the same task precedes the native close event.
        await page.evaluate(() => {
            const first = window.openDrawing('FULL');
            first.close();
            window.reopenedDrawing = window.openDrawing('FULL');
        });
        assert.equal(await page.evaluate(()=>!!window.reopenedDrawing?.open),true);
        await page.keyboard.press('Escape');
        await page.locator('dialog').waitFor({state:'detached'});
        // Without the optional PDF renderer the paper summary remains available offline.
        await page.evaluate(() => {
            window.TaxonomyCapabilitiesApi = {load:async()=>({features:['reporting'],exports:[],reports:[{
                id:'visio', reportType:'decision-rationale',displayName:'Visio',fileExtension:'vsdx',
                contentType:'application/vnd.ms-visio.drawing',
                plugin:{id:'fixture.reporting',version:'1.0.0',artifactSha256:'a'.repeat(64)}
            }]})};
        });
        await page.click('#open');
        await page.getByRole('radio',{name:'Als Wandplan drucken'}).check();
        await page.getByText(/841 × 594 mm.*Druckertreiber A1/).waitFor();
        assert.equal(await page.getByRole('button',{name:'Druckvorschau erstellen'}).isVisible(),false);
        await page.getByText(/PDF-Vorschau.*nicht verfügbar/).waitFor();
        await page.selectOption('[name=visioPaper]','A4');
        await page.getByText(/297 × 210 mm/).waitFor();
        assert.equal(await page.getByText(/Druckertreiber A1/).count(),0);
        await page.click('button[type=submit]');
        await page.locator('dialog').waitFor({state:'detached'});
        assert.deepEqual(await page.evaluate(()=>window.attempts.at(-1).options.visio),
            {mode:'WALL',paper:'A4',appendix:false,shortReasons:false});
        // Capability reload must remove unavailable renderers, accept a new SDK format,
        // and keep the same host-owned keyboard/focus and error controls.
        await page.evaluate(() => {window.TaxonomyCapabilitiesApi={load:async()=>({features:[],exports:[],reports:[]})};});
        await page.click('#open');
        await page.locator('[role=alert]').filter({hasText:'Format'}).waitFor();
        assert.equal(await page.locator('[type=submit]').isDisabled(),true);
        assert.equal(await page.locator('[name=format] option').count(),0);
        await page.keyboard.press('Escape');await page.locator('dialog').waitFor({state:'detached'});
        await page.evaluate(() => {window.TaxonomyCapabilitiesApi={load:async()=>({features:['reporting'],exports:[],reports:[{
            id:'example-markdown',reportType:'decision-rationale',displayName:'Markdown',fileExtension:'md',contentType:'text/markdown',
            plugin:{id:'fixture.external',version:'1.0.0',artifactSha256:'b'.repeat(64)}
        }]})};});
        await page.click('#open');await page.locator('[type=submit]:enabled').waitFor();
        assert.equal(await page.locator('[name=format] option').count(),1);
        assert.equal(await page.locator('[name=format]').inputValue(),'example-markdown');
        await page.click('[type=submit]');await page.locator('dialog').waitFor({state:'detached'});
        const external=await page.evaluate(()=>window.attempts.at(-1));
        assert.equal(external.descriptor.fileExtension,'md');assert.equal(external.descriptor.plugin.artifactSha256,'b'.repeat(64));
        assert.equal(await page.locator('#open').evaluate(el=>el===document.activeElement),true);
        await page.close();
    }
    if (process.env.TAXONOMY_DOC_SCREENSHOTS) {
        const directory=resolve(process.env.TAXONOMY_DOC_SCREENSHOTS);
        await mkdir(directory,{recursive:true});
        const captures=[];
        for (const language of ['de','en']) {
            const page=await browser.newPage({viewport:{width:1280,height:1250}});
            // Render the production component with explicit sample input. This is not a full-app snapshot.
            await page.setContent(`<html lang="${language}"><head><meta charset="utf-8"><style>body{font-family:system-ui,sans-serif}</style></head><body></body></html>`);
            await page.addScriptTag({content:capabilitiesSource});
        await page.evaluate(() => { window.TaxonomyCapabilitiesApi = {load:async()=>({features:['reporting'],exports:[],reports:
            ['docx','html','json'].map(id=>({id,reportType:'decision-rationale',displayName:id,fileExtension:id,contentType:id==='docx'?'application/vnd.openxmlformats-officedocument.wordprocessingml.document':id==='html'?'text/html':'application/json',plugin:{id:'fixture.reporting',version:'1.0.0',artifactSha256:'a'.repeat(64)}}))})}; });
        await page.addScriptTag({content:source});
            await page.evaluate(language=>window.TaxonomyDecisionExport.open({language,saved:true,
                source:language==='de'?'Beispielanalyse · Snapshot export-demo':'Example analysis · Snapshot export-demo',
                roots:[{code:'CP',title:language==='de'?'Fähigkeiten':'Capabilities'},
                    {code:'BP',title:language==='de'?'Geschäftsprozesse':'Business processes'},
                    {code:'IP',title:language==='de'?'Produkte':'Products',inAnalysisScope:false}],
                analysisScope:{taxonomyRoots:['CP','BP'],mode:'TAXONOMIES_ONLY'},selectedRoots:['CP'],submit:async()=>{}
            }),language);
            const dialog=page.locator('dialog');await dialog.waitFor();
            await page.locator('[type=submit]:enabled').waitFor();
            async function capture(kind) {
                assert.equal(await dialog.evaluate(node=>node.scrollHeight<=node.clientHeight+1),true,'Documentation shows the complete dialog');
                const name=`decision-export-${kind}-${language}.png`;
                const bytes=await dialog.screenshot({path:join(directory,name)});
                captures.push({file:name,language,viewport:page.viewportSize(),sha256:createHash('sha256').update(bytes).digest('hex')});
            }
            await capture('dialog');
            await page.click('summary');
            await page.selectOption('[name=treeLayout]','A4_LANDSCAPE');
            await capture('options');
            await page.click('summary');
            await page.setViewportSize({width:390,height:1000});
            await capture('mobile');
            await page.close();
        }
        const repository=new URL('../../',import.meta.url);
        await writeFile(join(directory,'decision-export-screenshots.json'),JSON.stringify({
            capturedAt:new Date().toISOString(),sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:repository,encoding:'utf8'}).trim(),
            sourceFile:'taxonomy-app/src/main/resources/static/js/shared/decision-export-dialog.js',
            sourceSha256:createHash('sha256').update(source).digest('hex'),browser:browser.version(),
            kind:'production-component-with-authored-example-input',fullApplication:false,providerCalls:false,
            captures
        },null,2)+'\n');
        console.log('Documentation screenshots: six complete production-dialog views captured (DE/EN, sample input).');
    }
    console.log('Decision export dialog: desktop/mobile, scope, failure/retry, format options and focus passed');
} finally {await browser.close();}
