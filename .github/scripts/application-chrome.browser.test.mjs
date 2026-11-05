/** Native checks of the actual common Chrome fragment, navigation script and responsive styles. */
import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import {chromium} from 'playwright';
const resources=process.env.TAXONOMY_TEST_RESOURCE_ROOT||path.resolve('taxonomy-app/src/main/resources');
let browser,header,index,bootstrap;
before(async()=>{
    const fragment=await readFile(path.join(resources,'templates/fragments/application-chrome.html'),'utf8');
    header=fragment.match(/<header\b[^>]*th:fragment="navigation\([^"]*\)"[^>]*>[\s\S]*?<\/header>/)?.[0];
    assert.ok(header,'Production navigation fragment');
    index=await readFile(path.join(resources,'templates/index.html'),'utf8');
    bootstrap=process.env.BOOTSTRAP_CSS ? await readFile(process.env.BOOTSTRAP_CSS,'utf8') : execFileSync('unzip',['-p',path.join(process.env.MAVEN_REPO_LOCAL||path.join(os.homedir(),'.m2/repository'),'org/webjars/npm/bootstrap/5.3.8/bootstrap-5.3.8.jar'),'META-INF/resources/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css'],{encoding:'utf8'});
    browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{}),args:['--no-sandbox','--disable-dev-shm-usage']});
});
after(async()=>{await browser?.close();});
async function fixture({mode='requirement',locale='en',width=1200}={}){
    const page=await browser.newPage({viewport:{width,height:900},hasTouch:true});
    page.errors=[];page.on('pageerror',error=>page.errors.push(error.message));
    await page.route('http://chrome.test/**',route=>route.fulfill({body:'',contentType:'text/html'}));await page.goto('http://chrome.test/');
    const panes=['analyze','architecture','graph','versions','dsl-editor','help','admin','preferences'];
    const content=mode==='index'?`<div id="architectureWorkspace"></div><div id="leftPanel"></div><div id="rightPanel">${panes.map(p=>`<section id="tab-${p}">${p}</section>`).join('')}</div>`
        :mode==='projects'?'<input id="projectFilter">':mode==='editor'?'<main id="architectureEditor"></main>':mode==='snapshot'?'<main id="architectureWorkbench"></main>':'<main id="requirementDetails"></main>';
    const standalone={editor:'architecture-editor',integrations:'integrations',snapshot:'architecture-workbench'}[mode];
    let navigation=header;
    if(standalone){
        const source=await readFile(path.join(resources,`templates/${standalone}.html`),'utf8');
        const local=source.match(/<th:block\b[^>]*th:fragment="localActions"[^>]*>([\s\S]*?)<\/th:block>/);
        assert.ok(local,'Use the actual standalone page actions');
        navigation=navigation.replace(/<th:block\b[^>]*th:replace="\$\{localActions\}"[^>]*><\/th:block>/,()=>local[1]);
    }
    await page.setContent(`<html lang="${locale}"><head></head><body${mode==='editor'?' class="architecture-editor-surface"':mode==='snapshot'?' class="architecture-snapshot-surface"':''}>${navigation}${content}</body></html>`);
    let translations={};
    for(const name of ['messages.properties',...(locale==='de'?['messages_de.properties']:[])])for(const line of (await readFile(path.join(resources,'i18n',name),'utf8')).split('\n')){const m=line.match(/^([^#!\s][^=]*)=(.*)$/);if(m)translations[m[1]]=m[2].replace(/\\u([0-9a-fA-F]{4})/g,(_,code)=>String.fromCharCode(parseInt(code,16)));}
    await page.evaluate(translations=>{
        window.TaxonomyI18n={t:key=>translations[key]||key,resolveUrl:p=>p,getLocale:()=>document.documentElement.lang,formatEnum:value=>value,ready:()=>Promise.resolve()};
        document.querySelectorAll('[th\\:text]').forEach(node=>{const key=node.getAttribute('th:text').match(/^#\{(.+)\}$/)?.[1];if(key)node.textContent=TaxonomyI18n.t(key);});
    },translations);
    if(!standalone||mode==='snapshot')await page.addStyleTag({content:bootstrap});
    for(const css of [...(standalone?[`${standalone}.css`]:['taxonomy.css','taxonomy-ergonomics.css']),'application-chrome.css','architecture-lifecycle.css'])await page.addStyleTag({path:path.join(resources,'static/css',css)});
    await page.addScriptTag({path:path.join(resources,'static/js/shared/application-chrome.js')});
    if(mode==='index'){
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-utils.js')});
        const nav=index.match(/<!-- Page Navigation Script -->\s*<script>([\s\S]*?)<\/script>/)?.[1];assert.ok(nav);
        await page.addScriptTag({content:nav});
    }
    return page;
}
for(const mode of ['editor','integrations'])for(const width of [390,1440])test(`${mode}: real standalone actions retain the shared foreground and touch target at ${width}px`,async()=>{
    const page=await fixture({mode,width});try{
        if(width<1200)await page.locator('#applicationNavigationMenu > summary').click();
        const links=page.locator('.taxonomy-app-local a');
        assert.ok(await links.count()>0);
        for(const link of await links.all()){
            assert.equal(await link.isVisible(),true);
            const style=await link.evaluate(node=>({color:getComputedStyle(node).color,height:node.getBoundingClientRect().height}));
            assert.equal(style.color,'rgb(248, 250, 252)','Local links must contrast with the shared dark header');
            assert.ok(style.height>=44,`Standalone actions retain a touch target: ${JSON.stringify(style)}`);
        }
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    }finally{await page.close();}
});
test('snapshot Export uses the common menu launcher style with its existing export form',async()=>{
    const page=await fixture({mode:'snapshot',width:1440});try{
        const style=await page.locator('#architectureExportOptions > summary').evaluate(node=>{
            const value=getComputedStyle(node),reference=getComputedStyle(document.querySelector('#applicationHelp > summary'));
            return {font:value.font,color:value.color,padding:value.padding,radius:value.borderRadius,
                expected:{font:reference.font,color:reference.color,padding:reference.padding,radius:reference.borderRadius}};
        });
        assert.deepEqual({font:style.font,color:style.color,padding:style.padding,radius:style.radius},style.expected);
        await page.locator('#architectureExportOptions > summary').click();
        assert.equal(await page.locator('#workbenchDocumentLanguage').isVisible(),true);
    }finally{await page.close();}
});
for(const mode of ['requirement','editor','snapshot'])test(`${mode}: common tabs do not collapse source details or lifecycle actions`,async()=>{
    const page=await fixture({mode});try{
        await page.evaluate(()=>{
            TaxonomyAppChrome.updateContext({requirement:{id:42,key:'REQ-42',title:'Recorded requirement context'},architecture:{branch:'main'},editable:true});
            const lifecycle=document.getElementById('architectureLifecycle');lifecycle.hidden=false;lifecycle.innerHTML='<button>Existing lifecycle action</button>';
        });
        assert.equal(await page.locator('#applicationContextDisclosure').evaluate(e=>e.open),true);
        assert.equal(await page.locator('#applicationCurrentDetails').isVisible(),true);
        assert.equal(await page.getByRole('button',{name:'Existing lifecycle action'}).isVisible(),true);
        assert.equal(await page.locator('#applicationContextDisclosure').getAttribute('data-compact-context'),null);
        assert.deepEqual(page.errors,[]);
    }finally{await page.close();}
});
for(const mode of ['index','projects'])test(`${mode}: the existing compact context remains user-expandable`,async()=>{
    const page=await fixture({mode});try{
        assert.equal(await page.locator('#applicationContextDisclosure').evaluate(e=>e.open),false);
        await page.locator('#applicationContextSummary').click();
        assert.equal(await page.locator('#applicationContextDisclosure').evaluate(e=>e.open),true);
        assert.deepEqual(page.errors,[]);
    }finally{await page.close();}
});
test('the shared Projects link retains the mounted project and workspace before and after loading',async()=>{
    const page=await fixture({mode:'requirement',locale:'de'});try{
        await page.evaluate(()=>{
            history.replaceState(null,'','/taxonomy/projects/42/requirements/7?projectId=11&workspaceId=private-a&lang=de');
            TaxonomyI18n.resolveUrl=path=>'/taxonomy'+path;
            TaxonomyAppChrome.refreshNavigation();
        });
        const link=page.locator('#applicationProjectsLink');
        for (const loaded of [false,true]) {
            if(loaded)await page.evaluate(()=>TaxonomyAppChrome.updateContext({project:{id:42,key:'P-42'}}));
            const target=new URL(await link.getAttribute('href'),'http://chrome.test');
            assert.equal(target.pathname,'/taxonomy/projects');
            assert.equal(target.searchParams.get('projectId'),'42');
            assert.equal(target.searchParams.get('workspaceId'),'private-a');
            assert.equal(target.searchParams.get('lang'),'de');
        }
        assert.deepEqual(page.errors,[]);
    }finally{await page.close();}
});
for(const locale of ['de','en'])for(const width of [390,700,1100])test(`${locale}: ${width}px common navigation exposes Projects and five keyboard/touch destinations`,async()=>{
    const page=await fixture({mode:'index',locale,width});try{
        const links=page.locator('#mainNavTabs a[data-page]');
        assert.equal(await links.count(),5);
        assert.equal(await page.locator('#applicationNavigation [data-app-route="projects"]').count(),1);
        const toggle=page.locator('#applicationNavigationMenu > summary');
        if(width<1200){
            assert.equal(await page.locator('#mainNavTabs').isVisible(),false);
            assert.ok((await page.locator('#applicationNavigation').boundingBox()).height<=64,
                'Keep the analysis action visible below a compact mobile header');
            await toggle.click();
        }
        assert.equal(await page.locator('#mainNavTabs').isVisible(),true);
        assert.equal(await page.locator('.mobile-main-navigation:visible, #mobileMainNavigationSelect:visible').count(),0,'No second visible or keyboard-accessible primary menu');
        for(const destination of ['analyze','architecture','graph','versions','dsl-editor']){
            const link=page.locator(`#mainNavTabs [data-page="${destination}"]`);
            assert.equal(await link.isVisible(),true);
            const box=await link.boundingBox();assert.ok(box.height>=44,'44px touch target: '+destination);
            await link.tap();assert.equal(await page.locator('#tab-'+destination).isVisible(),true);
            if(width<1200){await page.waitForFunction(()=>!document.getElementById('applicationNavigationMenu').open);await toggle.click();}
            await link.focus();await page.keyboard.press('Enter');assert.equal(await page.locator('#tab-'+destination).isVisible(),true);
            if(width<1200){
                await page.waitForFunction(()=>!document.getElementById('applicationNavigationMenu').open);
                assert.equal(await toggle.evaluate(node=>document.activeElement===node),true);
                await toggle.click();
            } else assert.equal(await link.evaluate(node=>document.activeElement===node),true);
        }
        const dimensions=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
        assert.ok(dimensions.scroll<=dimensions.width,'Navigation must not force horizontal page overflow: '+JSON.stringify(dimensions));
        const projects=page.locator('#applicationNavigation [data-app-route="projects"]');
        assert.equal(await projects.isVisible(),true);await projects.focus();
        assert.equal(await projects.evaluate(node=>document.activeElement===node),true);
        assert.deepEqual(page.errors,[]);
    }finally{await page.close();}
});
