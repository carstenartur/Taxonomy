/** Native Chromium component acceptance of the production header and controllers.
 * BOOTSTRAP_CSS must name the exact Bootstrap 5.3.8 stylesheet from the project's WebJar.
 * No server/provider requests; account responses are explicit test fixtures, not auth acceptance.
 */
import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
const resources=process.env.TAXONOMY_TEST_RESOURCE_ROOT||path.resolve('taxonomy-app/src/main/resources');
let browser,template,chromeTemplate,helpTemplate,bootstrapCss;
before(async()=>{
    assert.ok(process.env.BOOTSTRAP_CSS,'Supply BOOTSTRAP_CSS from the pinned project WebJar');
    bootstrapCss=await readFile(process.env.BOOTSTRAP_CSS,'utf8');
    template=await readFile(path.join(resources,'templates/index.html'),'utf8');
    chromeTemplate=await readFile(path.join(resources,'templates/fragments/application-chrome.html'),'utf8');
    helpTemplate=await readFile(path.join(resources,'templates/fragments/context-help.html'),'utf8');
    browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{}),args:['--no-sandbox','--disable-dev-shm-usage']});
});
after(async()=>{await browser?.close();});
function headerMarkup(locale){
    const host=template.match(/<header\b[^>]*th:replace="~\{fragments\/application-chrome :: navigation\('analysis', ~\{:: localActions\}, '([^']+)', '([^']+)'\)\}"[^>]*>([\s\S]*?)<\/header>/);
    const local=host?.[3].match(/<th:block\b[^>]*th:fragment="localActions"[^>]*>([\s\S]*?)<\/th:block>/);
    const navigation=chromeTemplate.match(/<header\b[^>]*th:fragment="navigation\([^"]*\)"[^>]*>[\s\S]*?<\/header>/);
    const help=helpTemplate.match(/<a\b[^>]*th:fragment="button\([^"]*\)"[^>]*>[\s\S]*?<\/a>/);
    assert.ok(host&&local&&navigation&&help,'Compose the production analysis header and its shared fragments');
    const helpLink=help[0].replace('<a ',`<a href="/taxonomy/?lang=${locale}#help/${host[1]}/${host[2]}" `);
    const localSlot=/<th:block\b[^>]*th:replace="\$\{localActions\}"[^>]*><\/th:block>/;
    const helpSlot=/<a\b[^>]*th:replace="~\{fragments\/context-help :: button\([^"]*\)\}"[^>]*><\/a>/;
    assert.match(navigation[0],localSlot);assert.match(navigation[0],helpSlot);
    const header=navigation[0].replace(localSlot,()=>local[1]).replace(helpSlot,()=>helpLink);
    const afterHeader=host.index+host[0].length,end=template.indexOf('<!-- Global startup loading banner');
    assert.ok(end>afterHeader,'Retain the production operational-status staging area');
    return header+template.slice(afterHeader,end);
}
async function fixture(locale='de',width=1366){
    const page=await browser.newPage({viewport:{width,height:768}});page.setDefaultTimeout(2500);
    page.uiErrors=[]; page.on('pageerror',error=>page.uiErrors.push(error.message));
    let dict={};
    for(const name of ['messages.properties',...(locale==='de'?['messages_de.properties']:[])]){
        for(const line of (await readFile(path.join(resources,'i18n',name),'utf8')).split('\n')){
            const m=line.match(/^([^#!\s][^=]*)=(.*)$/);if(m)dict[m[1]]=m[2].replace(/\\n/g,'\n');
        }
    }
    const pages=['analyze','architecture','graph','versions','dsl-editor','help','admin','preferences'];
    await page.setContent(`<html lang="${locale}"><head><link data-taxonomy-ergonomics></head><body>${headerMarkup(locale)}
        <main id="mainContent" class="container-fluid px-3"><div class="row"><div id="leftPanel"></div><div id="rightPanel">${pages.map(p=>`<section id="tab-${p}" class="${p==='analyze'?'':'d-none'}"><h1>${p}</h1>${p==='analyze'?'<div class="card"><div class="card-body"><textarea id="businessText"></textarea><button id="analyzeBtn">Analyze</button><div id="statusArea"></div></div></div>':''}</section>`).join('')}</div></div></main></body></html>`);
    await page.evaluate(dict=>{
        const store=new Map([['taxonomy_onboarded','true']]);
        Object.defineProperty(window,'localStorage',{value:{getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,v),removeItem:k=>store.delete(k)}});
        window.TaxonomyI18n={ready:()=>Promise.resolve(),getLocale:()=>document.documentElement.lang,resolveUrl:p=>'/taxonomy'+p,
            t:(key,...args)=>(dict[key]||key).replace(/\{(\d+)\}/g,(_,n)=>String(args[n]??''))};
        document.querySelectorAll('[th\\:text]').forEach(e=>{const m=e.getAttribute('th:text').match(/^#\{(.+)\}$/);if(m)e.textContent=TaxonomyI18n.t(m[1]);});
        window.account={username:'admin',roles:['ADMIN'],administrator:true,architectureMutationAllowed:true,localUserManagementAllowed:false};
        window.TaxonomyApiClient={getJson:()=>window.accountError?Promise.reject(new Error('Account unavailable')):Promise.resolve(window.account)};
    },dict);
    await page.addStyleTag({content:bootstrapCss});
    for(const css of ['taxonomy.css','taxonomy-ergonomics.css','context-help.css','application-chrome.css','architecture-lifecycle.css'])
        await page.addStyleTag({path:path.join(resources,'static/css',css)});
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-state.js')});
    await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-utils.js')});
    const nav=template.match(/<!-- Page Navigation Script -->\s*<script>([\s\S]*?)<\/script>/);
    assert.ok(nav);await page.addScriptTag({content:nav[1]});
    return page;
}
for(const locale of ['de','en']){
    test(`${locale}: compact desktop header keeps Help and About together`,async()=>{
        const page=await fixture(locale);try{
            const help=page.locator('[data-page="help"]').first(),about=page.locator('#aboutBtn');
            const h=await help.boundingBox(),a=await about.boundingBox();
            assert.ok(h&&a);assert.ok(Math.abs((h.y+h.height/2)-(a.y+a.height/2))<=2,'Help/About occupy different rows');
            assert.ok(a.x>=h.x+h.width&&a.x-h.x-h.width<24,'Help/About must be adjacent');
            assert.equal((await page.locator('.taxonomy-app-brand').textContent()).trim(),'Taxonomy');
            const header=await page.locator('#applicationNavigation .application-shell').boundingBox();
            assert.ok(header.height<=64,'The analysis action row stays compact: '+JSON.stringify(header));
            assert.ok(h.x>900,'Help belongs on the right');
        }finally{await finish(page);}
    });
    test(`${locale}: support navigation activates Help and primary keyboard navigation remains reachable`,async()=>{
        const page=await fixture(locale);try{
            await page.locator('[data-page="help"]').first().click();
            assert.equal(await page.locator('#tab-help').isVisible(),true);
            const first=page.locator('#mainNavTabs [data-page="analyze"]');
            assert.equal(await first.getAttribute('tabindex'),'0','Primary tablist needs an entry while viewing Help');
            await first.focus();await page.keyboard.press('ArrowRight');
            assert.equal(await page.locator('#tab-architecture').isVisible(),true);
        }finally{await finish(page);}
    });
    for(const width of [390,320])test(`${locale}: ${width}px header retains adjacent support and all work destinations`,async()=>{
        const page=await fixture(locale,width);try{
            const help=page.locator('[data-page="help"]').first(),about=page.locator('#aboutBtn');
            assert.equal(await help.isVisible(),true);assert.equal(await about.isVisible(),true);
            const h=await help.boundingBox(),a=await about.boundingBox();assert.ok(Math.abs(h.y-a.y)<=2);
            const sizes=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
            assert.ok(sizes.scroll<=sizes.width,JSON.stringify(sizes));
            const selector=page.locator('#mobileMainNavigationSelect');
            const selectorBox=await selector.boundingBox();
            assert.ok(selectorBox&&selectorBox.height>=44,
                `Mobile section selector must retain its 44px touch target: ${JSON.stringify(selectorBox)}`);
            await selector.selectOption('versions');
            assert.equal(await page.locator('#tab-versions').isVisible(),true);
            await help.click();assert.equal(await page.locator('#tab-help').isVisible(),true);
        }finally{await finish(page);}
    });
}
async function roles(page){
    await page.addScriptTag({path:path.join(resources,'static/js/security/taxonomy-role-surface.js')});
    await page.evaluate(()=>TaxonomyRoleSurface.ready);await page.evaluate(()=>Promise.resolve());
}
test('administrator sees a usable account-management entry even when local creation is disabled',async()=>{
    const page=await fixture();try{
        await roles(page);
        assert.equal(await page.locator('#localUserManagementAdminEntry').count(),1,'Disabled installation still needs an explanation');
        assert.equal(await page.locator('#localUserManagementLink').count(),0,'Never expose a nonfunctional create route');
        assert.match(await page.locator('#localUserManagementAdminEntry').textContent(),/local-user-management/);
        const entry=page.locator('#userManagementMenuAction');assert.equal(await entry.count(),1);
        await page.locator('#applicationSettings > summary').click();await entry.click();
        assert.equal(await page.locator('#tab-admin').isVisible(),true);
        await page.locator('#applicationSettings > summary').click();
        const administration=page.locator('#adminNavTab');
        const preferences=page.locator('#preferencesNavTab');
        assert.equal(await administration.isVisible(),true);
        assert.equal(await preferences.isVisible(),true);
        await administration.click();
        assert.equal(await page.locator('#tab-admin').isVisible(),true);
        await page.locator('#applicationSettings > summary').click();
        await preferences.click();
        assert.equal(await page.locator('#tab-preferences').isVisible(),true);
    }finally{await finish(page);}
});
test('local account route updates with capabilities; role loss and failed refresh fail closed',async()=>{
    const page=await fixture();try{
        await roles(page);
        assert.equal(await page.locator('#documentTemplateAdminEntry').count(),1);
        await page.evaluate(async()=>{window.account.localUserManagementAllowed=true;await TaxonomyRoleSurface.refresh();});
        assert.equal(await page.locator('#localUserManagementLink').getAttribute('href'),'/taxonomy/admin/users');
        assert.equal(await page.locator('#userManagementMenuAction').getAttribute('href'),'/taxonomy/admin/users');
        await page.evaluate(async()=>{window.accountError=true;await TaxonomyRoleSurface.refresh();});
        assert.equal(await page.locator('#documentTemplateAdminEntry').count(),0);
        assert.equal(await page.locator('#userManagementMenuAction').isVisible(),false);
        assert.equal(await page.locator('#localUserManagementAdminEntry').count(),0);
        assert.equal(await page.evaluate(()=>document.body.classList.contains('admin-unlocked')),false);
        await page.locator('#applicationSettings > summary').click();
        assert.equal(await page.locator('#adminNavTab').isVisible(),false);
        assert.equal(await page.locator('#preferencesNavTab').isVisible(),false);
        await page.locator('#applicationSettings > summary').click();
        await page.evaluate(async()=>{window.accountError=false;window.account={username:'reader',roles:['USER'],administrator:false,localUserManagementAllowed:false};await TaxonomyRoleSurface.refresh();});
        assert.equal(await page.locator('#documentTemplateAdminEntry').count(),0);
        assert.equal(await page.locator('#localUserManagementAdminEntry').count(),0);
        await page.locator('#applicationSettings > summary').click();
        assert.equal(await page.locator('#adminNavTab').isVisible(),false);
        assert.equal(await page.locator('#preferencesNavTab').isVisible(),false);
        assert.equal(await page.locator('#userManagementMenuAction').isVisible(),false);
    }finally{await finish(page);}
});
test('operational warnings remain discoverable without repeatedly expanding or consuming a header row',async()=>{
    const page=await fixture();try{
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
        await page.evaluate(()=>Promise.resolve());
        const details=page.locator('#operationalContextDetails');assert.equal(await details.count(),1);
        assert.equal(await page.locator('#operationalStatusMenuSlot #operationalContextDetails').count(),1);
        await page.evaluate(()=>{document.getElementById('aiStatusBadge').textContent='Warning';});
        await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));
        assert.equal(await details.getAttribute('open'),null);
        assert.match(await details.getAttribute('class'),/has-actionable-status/);
        await details.locator('summary').click();await details.locator('summary').click();
        await page.evaluate(()=>{document.getElementById('aiStatusBadge').textContent='Warning: still pending';});
        await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));
        assert.equal(await details.getAttribute('open'),null);
    }finally{await finish(page);}
});
for(const legacyStatus of ['unavailable','password-off'])test(`browse bootstrap does not unlock roles on ${legacyStatus} legacy admin status`,async()=>{
    const page=await fixture();try{
        await page.evaluate(status=>{
            window.account={username:'reader',roles:['USER'],administrator:false,architectureMutationAllowed:false,localUserManagementAllowed:false};
            Object.defineProperty(window,'sessionStorage',{value:{getItem:()=> 'old-admin-token',setItem:()=>{},removeItem:()=>{}}});
            for(const id of ['expandAll','collapseAll']){const b=document.createElement('button');b.id=id;document.body.append(b);}
            for(const id of ['taxonomyTree','aiProviderInfo']){const d=document.createElement('div');d.id=id;document.body.append(d);}
            window.requests=[];
            window.fetch=url=>{
                requests.push(String(url));
                if(String(url).includes('/api/admin/status'))return status==='unavailable'?Promise.reject(new Error('status unavailable')):Promise.resolve({ok:true,json:async()=>({passwordRequired:false})});
                if(String(url).includes('/api/account/me'))return Promise.resolve({ok:true,json:async()=>window.account});
                return Promise.resolve({ok:true,json:async()=>String(url).includes('ai-status')?{available:false,availableProviders:[]}:[]});
            };
        },legacyStatus);
        await roles(page);
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-scoring.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-browse.js')});
        await page.evaluate(()=>{document.dispatchEvent(new Event('DOMContentLoaded'));});
        await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));
        assert.equal(await page.evaluate(()=>document.body.classList.contains('admin-unlocked')),false);
        assert.equal(await page.evaluate(()=>requests.some(u=>u.includes('/api/admin/verify'))),false);
    }finally{await finish(page);}
});

test('dismissing inline feedback returns focus to the closed settings launcher',async()=>{
    const page=await fixture();try{
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-recovery-viewport.js')});
        await page.evaluate(()=>{window.feedback=TaxonomyRecoveryViewport.mount({text:(de,en)=>de,scopeKey:()=> 'workspace/run',open:()=>{},cancel:()=>{}});feedback.update('Running',true,true);});
        await page.locator('#analysisRecoveryDismiss').click();
        assert.equal(await page.locator('#applicationSettings > summary').evaluate(e=>document.activeElement===e),true);
        assert.equal(await page.locator('#applicationSettings').getAttribute('open'),null);
    }finally{await finish(page);}
});
for (const locale of ['de','en']) test(`${locale}: Help loads once through the common navigation lifecycle and keeps F1 functional`,async()=>{
    const page=await fixture(locale);try{
        await page.evaluate(()=>{
            document.getElementById('tab-help').innerHTML='<ul id="helpTocList"></ul><h2 id="helpDocTitle"></h2><div id="helpDocBody"></div><button id="helpBackToTop">Top</button>';
            window.helpRequests=[];
            window.fetch=async url=>{helpRequests.push(String(url));return {ok:true,json:async()=>[{filename:'USER_GUIDE',icon:'',title:'Handbuch',audience:'Users'}],text:async()=>'<h2>Bedienung der Anwendung</h2>'};};
        });
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-help.js')});
        await page.locator('[data-page="help"]').first().click();
        await page.waitForFunction(()=>document.getElementById('helpDocBody').textContent==='Bedienung der Anwendung');
        const expectedRequests=[`/taxonomy/help?lang=${locale}`,`/taxonomy/help/USER_GUIDE?lang=${locale}`];
        assert.deepEqual(await page.evaluate(()=>helpRequests),expectedRequests);
        await page.evaluate(()=>navigateToPage('analyze'));await page.keyboard.press('F1');
        assert.equal(await page.locator('#tab-help').isVisible(),true);
        assert.deepEqual(await page.evaluate(()=>helpRequests),expectedRequests);
    }finally{await finish(page);}
});

async function finish(page) {
    await page.close();
    assert.deepEqual(page.uiErrors, [], 'Unexpected browser script errors');
}

for (const locale of ['en', 'de']) test(`bounded Archi return review is operable in the native browser (${locale})`, async () => {
    const page = await browser.newPage();
    try {
        const start = template.indexOf('<details id="archiReturnPanel"');
        assert.ok(start >= 0, 'Production return panel exists');
        const markup = template.slice(start, template.indexOf('</details>', start) + '</details>'.length);
        const dictionary = {};
        for (const file of ['messages.properties', ...(locale === 'de' ? ['messages_de.properties'] : [])]) {
            for (const line of (await readFile(path.join(resources, 'i18n', file), 'utf8')).split('\n')) {
                const match = line.match(/^([^#!\s][^=]*)=(.*)$/); if (match) dictionary[match[1]] = match[2];
            }
        }
        await page.setContent(`<html lang="${locale}"><head><style>${bootstrapCss}</style></head><body><main>${markup}</main></body></html>`);
        await page.evaluate(dict => {
            window.returnCalls = [];
            window.TaxonomyI18n = {t: (key, ...args) => (dict[key] || key).replace(/\{(\d+)\}/g, (_, n) => args[n])};
            for (const element of document.querySelectorAll('[th\\:text]')) {
                const key = element.getAttribute('th:text').slice(2, -1); element.textContent = dict[key] || key;
            }
            window.TaxonomyRoleSurface = {canMutateArchitecture: () => true};
            window.ImportApi = {
                previewArchiReturn: async form => {
                    window.returnCalls.push({operation: 'preview', original: form.get('original').name, returned: form.get('file').name});
                    return {writableContext: true, reviewFingerprint: 'native-review', elementsMatched: 2, relationsParsed: 1, relationsSkipped: 0, relationsRejected: 0,
                        losses: [{scope: 'return', id: 'CP', field: 'label', kind: 'ARCHI_NODE_LABEL_OMITTED', rationale: '<img src=x onerror=alert(1)> lost label'}]};
                },
                applyArchiReturn: async form => {window.returnCalls.push({operation: 'apply', fingerprint: form.get('reviewedFingerprint')}); return {relationsImported: 1, relationsSkipped: 0, relationsRejected: 0};}
            };
        }, dictionary);
        await page.addScriptTag({content: await readFile(path.join(resources, 'static/js/shared/archimate-return.js'), 'utf8')});
        await page.locator('#archiReturnPanel summary').click();
        await page.locator('#archiReturnOriginal').setInputFiles({name: 'original.xml', mimeType: 'application/xml', buffer: Buffer.from('<model/>')});
        await page.locator('#archiReturnFile').setInputFiles({name: 'returned.xml', mimeType: 'application/xml', buffer: Buffer.from('<model/>')});
        await page.locator('#archiReturnPreview').click();
        await page.waitForFunction(() => !document.getElementById('archiReturnAccept').disabled);
        assert.equal(await page.locator('#archiReturnApply').isDisabled(), true);
        assert.match(await page.locator('#archiReturnLosses').innerText(), /<img src=x/);
        assert.equal(await page.locator('#archiReturnLosses img').count(), 0, 'Loss content remains escaped text');
        await page.locator('#archiReturnAccept').check();
        await page.locator('#archiReturnApply').click();
        await page.waitForFunction(() => window.returnCalls.length === 2);
        assert.deepEqual(await page.evaluate(() => window.returnCalls), [
            {operation: 'preview', original: 'original.xml', returned: 'returned.xml'}, {operation: 'apply', fingerprint: 'native-review'}]);
        assert.equal(await page.locator('#archiReturnApply').isDisabled(), true);
        assert.match(await page.locator('#archiReturnStatus').innerText(), locale === 'de' ? /Angelegte Beziehungen: 1/ : /Created relations: 1/);
    } finally { await page.close(); }
});
