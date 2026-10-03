// Focused native-browser contract for the shared dialog, independent of external services.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const {chromium} = await import(process.env.TAXONOMY_PLAYWRIGHT_MODULE || '@playwright/test');
const browser = await chromium.launch({headless:true, ...(process.env.TAXONOMY_CHROME ? {executablePath:process.env.TAXONOMY_CHROME} : {}),args:['--no-sandbox']});
try {
    for (const width of [1280,390]) {
        const page = await browser.newPage({viewport:{width,height:850}});
        await page.setContent('<html lang="de"><head></head><body><button id="open">Bericht</button></body></html>');
        await page.addScriptTag({content:await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/decision-export-dialog.js',import.meta.url),'utf8')});
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
        await page.close();
    }
    console.log('Decision export dialog: desktop/mobile, scope, failure/retry, format options and focus passed');
} finally {await browser.close();}
