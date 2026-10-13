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
let browser,template,bootstrapCss;
before(async()=>{
    assert.ok(process.env.BOOTSTRAP_CSS,'Supply BOOTSTRAP_CSS from the pinned project WebJar');
    bootstrapCss=await readFile(process.env.BOOTSTRAP_CSS,'utf8');
    template=await readFile(path.join(resources,'templates/index.html'),'utf8');
    browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{}),args:['--no-sandbox','--disable-dev-shm-usage']});
});
after(async()=>{await browser?.close();});
async function fixture(locale='de',width=1366){
    const page=await browser.newPage({viewport:{width,height:768}});page.setDefaultTimeout(2500);
    page.uiErrors=[]; page.on('pageerror',error=>page.uiErrors.push(error.message));
    const start=template.search(/<nav\b[^>]*class="navbar/),end=template.indexOf('<!-- Global startup loading banner');
    assert.ok(start>=0&&end>start);
    let dict={};
    for(const name of ['messages.properties',...(locale==='de'?['messages_de.properties']:[])]){
        for(const line of (await readFile(path.join(resources,'i18n',name),'utf8')).split('\n')){
            const m=line.match(/^([^#!\s][^=]*)=(.*)$/);if(m)dict[m[1]]=m[2].replace(/\\n/g,'\n');
        }
    }
    const pages=['analyze','architecture','graph','versions','dsl-editor','help','admin','preferences'];
    await page.setContent(`<html lang="${locale}"><head><link data-taxonomy-ergonomics></head><body>${template.slice(start,end)}
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
    for(const css of ['taxonomy.css','taxonomy-ergonomics.css']) await page.addStyleTag({path:path.join(resources,'static/css',css)});
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
            assert.equal((await page.locator('.navbar-brand').textContent()).trim(),'Taxonomy');
            const header=await page.locator('nav.navbar').boundingBox();assert.ok(header.height<=64,JSON.stringify(header));
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
            await page.locator('#mobileMainNavigationSelect').selectOption('versions');
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
test('Help loads once through the common navigation lifecycle and keeps F1 functional',async()=>{
    const page=await fixture();try{
        await page.evaluate(()=>{
            document.getElementById('tab-help').innerHTML='<ul id="helpTocList"></ul><h2 id="helpDocTitle"></h2><div id="helpDocBody"></div><button id="helpBackToTop">Top</button>';
            window.helpRequests=[];
            window.fetch=async url=>{helpRequests.push(String(url));return {ok:true,json:async()=>[{filename:'USER_GUIDE',icon:'',title:'Handbuch',audience:'Users'}],text:async()=>'<h2>Bedienung der Anwendung</h2>'};};
        });
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-help.js')});
        await page.locator('[data-page="help"]').first().click();
        await page.waitForFunction(()=>document.getElementById('helpDocBody').textContent==='Bedienung der Anwendung');
        assert.deepEqual(await page.evaluate(()=>helpRequests),['/help','/help/USER_GUIDE']);
        await page.evaluate(()=>navigateToPage('analyze'));await page.keyboard.press('F1');
        assert.equal(await page.locator('#tab-help').isVisible(),true);
        assert.deepEqual(await page.evaluate(()=>helpRequests),['/help','/help/USER_GUIDE']);
    }finally{await finish(page);}
});

async function finish(page) {
    await page.close();
    assert.deepEqual(page.uiErrors, [], 'Unexpected browser script errors');
}
