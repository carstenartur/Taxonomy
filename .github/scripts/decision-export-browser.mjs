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
        await page.evaluate(() => { window.fetch = async () => ({ok:true,json:async()=>({features:['reporting'],exports:[],reports:
            ['docx','html','json'].map(id=>({id,reportType:'decision-rationale',displayName:id,fileExtension:id,contentType:id==='docx'?'application/vnd.openxmlformats-officedocument.wordprocessingml.document':id==='html'?'text/html':'application/json',plugin:{id:'fixture.reporting',version:'1.0.0',artifactSha256:'a'.repeat(64)}}))})}); });
        await page.addScriptTag({content:source});
        await page.evaluate(()=>{
            window.attempts=[];
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
        assert.equal(await page.locator('[name=profile]').inputValue(),'COMPACT');
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
        if(process.env.TAXONOMY_UI_OUTPUT) await page.screenshot({path:process.env.TAXONOMY_UI_OUTPUT+'/dialog-'+width+'.png'});
        await page.click('[type=submit]');await dialog.waitFor({state:'detached'});
        assert.equal(await page.locator('#open').evaluate(el=>el===document.activeElement),true);
        const attempts=await page.evaluate(()=>window.attempts);
        assert.deepEqual(attempts[1].options.taxonomyRoots,['BP']);
        await page.click('#open');await page.keyboard.press('Escape');await page.locator('dialog').waitFor({state:'detached'});
        assert.equal(await page.locator('#open').evaluate(el=>el===document.activeElement),true);
        // Capability reload must remove unavailable renderers, accept a new SDK format,
        // and keep the same host-owned keyboard/focus and error controls.
        await page.evaluate(() => {window.fetch=async()=>({ok:true,json:async()=>({features:[],exports:[],reports:[]})});});
        await page.click('#open');
        await page.locator('[role=alert]').filter({hasText:'Format'}).waitFor();
        assert.equal(await page.locator('[type=submit]').isDisabled(),true);
        assert.equal(await page.locator('[name=format] option').count(),0);
        await page.keyboard.press('Escape');await page.locator('dialog').waitFor({state:'detached'});
        await page.evaluate(() => {window.fetch=async()=>({ok:true,json:async()=>({features:['reporting'],exports:[],reports:[{
            id:'example-markdown',reportType:'decision-rationale',displayName:'Markdown',fileExtension:'md',contentType:'text/markdown',
            plugin:{id:'fixture.external',version:'1.0.0',artifactSha256:'b'.repeat(64)}
        }]})});});
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
        await page.evaluate(() => { window.fetch = async () => ({ok:true,json:async()=>({features:['reporting'],exports:[],reports:
            ['docx','html','json'].map(id=>({id,reportType:'decision-rationale',displayName:id,fileExtension:id,contentType:id==='docx'?'application/vnd.openxmlformats-officedocument.wordprocessingml.document':id==='html'?'text/html':'application/json',plugin:{id:'fixture.reporting',version:'1.0.0',artifactSha256:'a'.repeat(64)}}))})}); });
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
