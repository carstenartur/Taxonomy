/** Native Chromium component acceptance of the production header and controllers.
 * BOOTSTRAP_CSS must name the exact Bootstrap 5.3.8 stylesheet from the project's WebJar.
 * No server/provider requests; account responses are explicit test fixtures, not auth acceptance.
 */
import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import {chromium} from 'playwright';
import {navigateToPage,openSharedNavigation} from './ui-role-fixtures.mjs';
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
async function fixture(locale='de',width=1366,entry=null){
    const page=await browser.newPage({viewport:{width,height:768}});page.setDefaultTimeout(2500);
    page.uiErrors=[]; page.on('pageerror',error=>page.uiErrors.push(error.message));
    await page.route('http://taxonomy.test/taxonomy/',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html></html>'}));
    await page.goto('http://taxonomy.test/taxonomy/');
    if(entry)await page.evaluate(url=>history.replaceState(null,'',url),entry);
    let dict={};
    for(const name of ['messages.properties',...(locale==='de'?['messages_de.properties']:[])]){
        for(const line of (await readFile(path.join(resources,'i18n',name),'utf8')).split('\n')){
            const m=line.match(/^([^#!\s][^=]*)=(.*)$/);if(m)dict[m[1]]=m[2].replace(/\\n/g,'\n');
        }
    }
    const pages=['analyze','architecture','graph','versions','dsl-editor','help','admin','preferences'];
    const nextSteps=template.match(/<section id="analysisNextSteps"[\s\S]*?<\/section>/)?.[0];
    assert.ok(nextSteps,'Use the actual next-steps controls from the analysis page');
    await page.setContent(`<html lang="${locale}"><head><link data-taxonomy-ergonomics></head><body>${headerMarkup(locale)}
        <main id="mainContent" class="container-fluid px-3"><div class="row"><div id="leftPanel"></div><div id="rightPanel">${pages.map(p=>`<section id="tab-${p}" class="${p==='analyze'?'':'d-none'}"><h1>${p}</h1>${p==='analyze'?'<div class="card"><div class="card-body"><textarea id="businessText"></textarea><button id="analyzeBtn">Analyze</button><div id="statusArea"></div></div></div>'+nextSteps:''}</section>`).join('')}</div></div></main></body></html>`);
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
    await page.addScriptTag({path:path.join(resources,'static/js/shared/application-chrome.js')});
    const nav=template.match(/<!-- Page Navigation Script -->\s*<script>([\s\S]*?)<\/script>/);
    assert.ok(nav);await page.addScriptTag({content:nav[1]});
    return page;
}
test('primary destinations and menu launchers use one readable control style',async()=>{
    const page=await fixture('de',1366);try{
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
        await page.locator('#operationalContextDetails > summary').waitFor({state:'attached'});
        const controls=await page.evaluate(()=>[
            '#applicationProjectsLink','#mainNavTabs [data-page="graph"]','#fileMenuButton',
            '#applicationSettings > summary','#operationalContextDetails > summary'
        ].map(selector=>{
            const node=document.querySelector(selector),style=getComputedStyle(node);
            return {selector,height:node.getBoundingClientRect().height,font:style.font,
                padding:style.padding,borderRadius:style.borderRadius,color:style.color};
        }));
        for(const control of controls){
            assert.ok(control.height>=44,`A readable touch target: ${JSON.stringify(control)}`);
            for(const property of ['font','padding','borderRadius','color'])
                assert.equal(control[property],controls[0][property],`${control.selector}: ${property}`);
        }
    }finally{await finish(page);}
});
test('the compact Menu and Help launchers use the same control style',async()=>{
    const page=await fixture('de',390);try{
        const styles=await page.evaluate(()=>['#applicationNavigationMenu > summary','#applicationHelp > summary'].map(selector=>{
            const style=getComputedStyle(document.querySelector(selector));
            return {font:style.font,padding:style.padding,border:style.border,borderRadius:style.borderRadius,color:style.color};
        }));
        assert.deepEqual(styles[0],styles[1]);
    }finally{await finish(page);}
});
test('File opens without a second menu framework and follows the shared close and focus behavior',async()=>{
    const page=await fixture('de',1366);try{
        await page.locator('#applicationSettings > summary').click();
        await page.locator('#fileMenuButton').click();
        assert.equal(await page.locator('#fileSaveDraftAction').isVisible(),true);
        assert.equal(await page.locator('#applicationSettings').evaluate(e=>e.open),false);
        await page.locator('#fileSaveDraftAction').focus();await page.keyboard.press('Escape');
        assert.equal(await page.locator('#fileSaveDraftAction').isVisible(),false);
        assert.equal(await page.locator('#fileMenuButton').evaluate(e=>e===document.activeElement),true);
        await page.locator('#fileMenuButton').click();
        assert.equal(await page.locator('#fileCancelAnalysisAction').isDisabled(),true);
        await page.evaluate(()=>{window.saved=0;document.getElementById('fileSaveDraftAction').addEventListener('click',()=>saved++);});
        await page.locator('#fileSaveDraftAction').click();
        assert.equal(await page.evaluate(()=>saved),1);
        assert.equal(await page.locator('#fileSaveDraftAction').isVisible(),false);
    }finally{await finish(page);}
});
test('language and help each have one shared entry, including contextual help and About',async()=>{
    const page=await fixture('de',1366);try{
        assert.equal(await page.locator('#langSelector').count(),0,'Language is offered once in the shared language controls');
        assert.equal(await page.locator('.taxonomy-app-languages [data-app-locale]').count(),2);
        await page.locator('#applicationHelp > summary').click();
        for(const selector of ['#main-tab-help','#aboutBtn','#applicationHelp .context-help'])
            assert.equal(await page.locator(selector).isVisible(),true,selector);
        await page.locator('#applicationSettings > summary').click();
        assert.equal(await page.locator('#main-tab-help').isVisible(),false);
        assert.equal(await page.locator('#aboutBtn').isVisible(),false);
    }finally{await finish(page);}
});
test('closing About returns keyboard focus to the visible Help launcher',async()=>{
    const page=await fixture('de',1366);try{
        const jar=path.join(process.env.MAVEN_REPO_LOCAL||path.join(os.homedir(),'.m2/repository'),
            'org/webjars/npm/bootstrap/5.3.8/bootstrap-5.3.8.jar');
        await page.addScriptTag({content:execFileSync('unzip',['-p',jar,
            'META-INF/resources/webjars/bootstrap/5.3.8/dist/js/bootstrap.bundle.min.js'],{encoding:'utf8'})});
        await page.evaluate(source=>{
            const documentSource=new DOMParser().parseFromString(source,'text/html');
            document.body.append(documentSource.getElementById('aboutModal'));
        },template);
        await page.route('http://taxonomy.test/taxonomy/api/about',route=>route.fulfill({contentType:'application/json',body:'{}'}));
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-about.js')});
        await page.evaluate(()=>document.dispatchEvent(new Event('DOMContentLoaded')));
        await page.locator('#applicationHelp > summary').click();await page.locator('#aboutBtn').click();
        await page.waitForFunction(()=>document.getElementById('aboutModal').contains(document.activeElement));
        await page.keyboard.press('Escape');await page.locator('#aboutModal').waitFor({state:'hidden'});
        assert.equal(await page.locator('#applicationHelp > summary').evaluate(node=>node===document.activeElement),true);
    }finally{await finish(page);}
});
for(const locale of ['de','en'])for(const width of [320,390,700,900])test(`${locale}: a compact ${width}px menu keeps every primary and local action reachable`,async()=>{
    const page=await fixture(locale,width);try{
        const menu=page.locator('#applicationNavigationMenu'), toggle=page.locator('#applicationNavigationMenu > summary');
        assert.equal(await menu.getAttribute('open'),null);
        const header=await page.locator('#applicationNavigation').boundingBox(), button=await toggle.boundingBox();
        assert.ok(header&&header.height<=64,JSON.stringify(header));
        assert.ok(button&&button.width>=44&&button.height>=44,JSON.stringify(button));
        assert.equal(await page.locator('.taxonomy-app-primary:visible').count(),0);
        await toggle.click();
        assert.notEqual(await menu.getAttribute('open'),null);
        for(const route of ['projects','analysis','architecture','graph','versions','dsl-editor']){
            const link=page.locator(`[data-app-route="${route}"]`),box=await link.boundingBox();
            assert.ok(box&&box.width>=44&&box.height>=44,`${route}: ${JSON.stringify(box)}`);
        }
        assert.equal(await page.locator('#fileMenuButton').isVisible(),true);
        assert.equal(await page.locator('#applicationSettings > summary').isVisible(),true);
        for(const selector of ['#fileMenuButton','#applicationProjectsLink','#applicationSettings > summary','#applicationHelp > summary']){
            const box=await page.locator(selector).boundingBox();
            assert.ok(box&&box.height>=44&&box.width>=44,`${selector} must remain a 44px touch target: ${JSON.stringify(box)}`);
        }
        await toggle.focus();await page.keyboard.press('Escape');
        assert.equal(await menu.getAttribute('open'),null);
        assert.equal(await toggle.evaluate(element=>document.activeElement===element),true);
        assert.deepEqual(page.uiErrors,[]);
    }finally{await finish(page);}
});
test('mobile menu exposes its label, expanded state, and controlled region to assistive technology',async()=>{
    const page=await fixture('de',390);try{
        const session=await page.context().newCDPSession(page);
        const documentNode=await session.send('DOM.getDocument');
        const {nodeId}=await session.send('DOM.querySelector',{
            nodeId:documentNode.root.nodeId,selector:'#applicationNavigationMenu > summary'});
        async function accessibleState(){
            const {nodes}=await session.send('Accessibility.getPartialAXTree',{nodeId,fetchRelatives:false});
            const entry=nodes[0],properties=Object.fromEntries(entry.properties.map(property=>[property.name,property.value.value]));
            return {ignored:entry.ignored,name:entry.name.value,...properties};
        }
        const closed=await accessibleState();
        assert.equal(closed.ignored,false);assert.equal(closed.name,'Menü');
        assert.equal(closed.expanded,false);
        assert.equal(await page.locator('#applicationNavigationMenu > summary').getAttribute('aria-controls'),'applicationNavigationMenuContent');
        await page.locator('#applicationNavigationMenu > summary').click();
        const opened=await accessibleState();
        assert.equal(opened.expanded,true);assert.equal(opened.controls,'applicationNavigationMenuContent');
        await session.detach();
    }finally{await finish(page);}
});
test('mobile navigation uses the configured foreground and background together',async()=>{
    const page=await fixture('de',390);try{
        await page.evaluate(()=>{
            document.documentElement.style.setProperty('--taxonomy-navigation-foreground','#19304a');
            document.documentElement.style.setProperty('--taxonomy-navigation-background','#f4f7fb');
        });
        await page.locator('#applicationNavigationMenu > summary').click();
        assert.equal(await page.locator('#applicationNavigationMenuContent').evaluate(e=>getComputedStyle(e).backgroundColor),'rgb(244, 247, 251)');
        for(const selector of ['.taxonomy-app-brand','#mainNavTabs [data-page="graph"]']){
            assert.equal(await page.locator(selector).evaluate(e=>getComputedStyle(e).color),'rgb(25, 48, 74)');
        }
    }finally{await finish(page);}
});
test('mobile primary navigation closes after selection while arrow navigation retains focus',async()=>{
    const page=await fixture('de',390);try{
        const menu=page.locator('#applicationNavigationMenu'),toggle=page.locator('#applicationNavigationMenu > summary');
        await toggle.click();
        const analysis=page.locator('#mainNavTabs [data-page="analyze"]');
        assert.equal(await page.locator('#mainNavTabs').getAttribute('aria-orientation'),'vertical');
        await analysis.focus();await page.keyboard.press('ArrowDown');
        assert.notEqual(await menu.getAttribute('open'),null);
        assert.equal(await page.locator('#mainNavTabs [data-page="architecture"]').evaluate(e=>e===document.activeElement),true);
        await page.keyboard.press('ArrowUp');
        assert.equal(await analysis.evaluate(e=>e===document.activeElement),true);
        await page.locator('#mainNavTabs [data-page="graph"]').click();
        assert.equal(await page.locator('#tab-graph').isVisible(),true);
        await page.waitForFunction(()=>!document.getElementById('applicationNavigationMenu').open);
        assert.equal(await menu.getAttribute('open'),null);
    }finally{await finish(page);}
});
test('mobile Settings and the outer menu close in keyboard order',async()=>{
    const page=await fixture('de',390);try{
        const menu=page.locator('#applicationNavigationMenu'),toggle=page.locator('#applicationNavigationMenu > summary');
        const settings=page.locator('#applicationSettings'),settingsToggle=page.locator('#applicationSettings > summary');
        await toggle.click();await settingsToggle.click();await settingsToggle.focus();
        await page.keyboard.press('Escape');
        assert.equal(await settings.getAttribute('open'),null);
        assert.notEqual(await menu.getAttribute('open'),null);
        assert.equal(await settingsToggle.evaluate(element=>document.activeElement===element),true);
        await page.keyboard.press('Escape');
        assert.equal(await menu.getAttribute('open'),null);
        assert.equal(await toggle.evaluate(element=>document.activeElement===element),true);
    }finally{await finish(page);}
});
test('the operational status shortcut opens its mobile menu and focuses the status control',async()=>{
    const page=await fixture('de',390);try{
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
        await page.locator('#operationalContextDetails > summary').waitFor({state:'attached'});
        await page.keyboard.press('Alt+Shift+O');
        const menu=page.locator('#applicationNavigationMenu'),status=page.locator('#operationalContextDetails');
        assert.notEqual(await menu.getAttribute('open'),null);
        assert.notEqual(await status.getAttribute('open'),null);
        assert.equal(await status.locator('summary').evaluate(element=>document.activeElement===element),true);
        await page.keyboard.press('Alt+Shift+O');
        assert.equal(await status.getAttribute('open'),null);
    }finally{await finish(page);}
});
test('resizing retains a single navigation and keeps every destination reachable',async()=>{
    const page=await fixture('de',1280);try{
        const menu=page.locator('#applicationNavigationMenu'),toggle=page.locator('#applicationNavigationMenu > summary');
        const tabs=page.locator('#mainNavTabs'),analysis=tabs.locator('[data-page="analyze"]');
        assert.equal(await tabs.isVisible(),true);
        assert.equal(await tabs.getAttribute('aria-orientation'),'horizontal');
        await analysis.focus();
        await page.setViewportSize({width:390,height:768});
        await page.waitForFunction(()=>!document.getElementById('applicationNavigationMenu').open
            && document.getElementById('mainNavTabs').getAttribute('aria-orientation')==='vertical');
        assert.equal(await tabs.isVisible(),false);
        assert.equal(await toggle.evaluate(element=>document.activeElement===element),true);
        assert.equal(await tabs.getAttribute('aria-orientation'),'vertical');
        await toggle.click();assert.equal(await tabs.isVisible(),true);
        await toggle.focus();
        await page.setViewportSize({width:1280,height:768});
        await page.waitForFunction(()=>document.getElementById('applicationNavigationMenu').open
            && document.getElementById('mainNavTabs').getAttribute('aria-orientation')==='horizontal'
            && document.activeElement?.matches('#mainNavTabs [data-page="analyze"]'));
        assert.equal(await toggle.isVisible(),false);
        assert.equal(await tabs.isVisible(),true);
        assert.equal(await tabs.getAttribute('aria-orientation'),'horizontal');
        assert.equal(await analysis.evaluate(element=>document.activeElement===element),true);
    }finally{await finish(page);}
});
test('leaving the compact menu closes it without taking focus back',async()=>{
    const page=await fixture('de',390);try{
        const menu=page.locator('#applicationNavigationMenu');
        await page.locator('#applicationNavigationMenu > summary').click();
        await page.locator('#fileMenuButton').focus();
        const language=page.locator('.taxonomy-app-languages [hreflang="en"]');
        await language.focus();
        assert.equal(await menu.getAttribute('open'),null);
        assert.equal(await language.evaluate(element=>document.activeElement===element),true);
    }finally{await finish(page);}
});
test('mobile navigation remains operable without JavaScript',async()=>{
    const page=await browser.newPage({viewport:{width:390,height:768},javaScriptEnabled:false});try{
        let cssText=bootstrapCss;
        for(const css of ['taxonomy.css','taxonomy-ergonomics.css','context-help.css','application-chrome.css'])
            cssText+='\n'+await readFile(path.join(resources,'static/css',css),'utf8');
        await page.setContent('<!doctype html><html><head><style>'+cssText+'</style></head><body>'+headerMarkup('de')+'</body></html>');
        const menu=page.locator('#applicationNavigationMenu'),toggle=page.locator('#applicationNavigationMenu > summary');
        assert.notEqual(await menu.getAttribute('open'),null);
        assert.equal(await page.locator('#mainNavTabs').isVisible(),true);
        await toggle.click();assert.equal(await page.locator('#mainNavTabs').isVisible(),false);
        await toggle.click();assert.equal(await page.locator('#mainNavTabs').isVisible(),true);
    }finally{await page.close();}
});
async function firstVisitWelcome(page,databasePrompt=false){
    await page.evaluate(prompt=>{
        localStorage.removeItem('taxonomy_onboarded');
        window.welcomePromptDismissals=0;
        window.TaxonomyDatabaseSetup={
            prepareWelcome:()=>Promise.resolve(prompt),
            decorateWelcome:dialog=>{dialog.dataset.databaseSetupPrompt='true';},
            dismissPrompt:()=>{window.welcomePromptDismissals++;}
        };
    },databasePrompt);
    await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
    await page.locator('#analysisTaskProgress').waitFor({state:'attached'});
}
for(const [source,entry] of [
    ['current','/taxonomy/#architecture'],
    ['analysis','/taxonomy/?architectureSource=analysis#architecture'],
    ['snapshot','/taxonomy/?architectureSource=snapshot&projectId=42&snapshotId=recorded-A#architecture'],
    ['working','/taxonomy/?architectureSource=working&repositoryId=repo&workspaceId=&workspaceScopeKey=__shared__&branch=draft#architecture']
])for(const databasePrompt of [false,true])test(`${source} architecture entry remains usable before onboarding (${databasePrompt?'database prompt':'welcome only'})`,async()=>{
    const page=await fixture('en',1366,entry);try{
        await firstVisitWelcome(page,databasePrompt);
        assert.equal(await page.locator('#onboardingOverlay').count(),0,'An explicit architecture destination must not become inert behind the first-visit dialog');
        assert.equal(await page.evaluate(()=>localStorage.getItem('taxonomy_onboarded')),null,'Opening architecture must not pretend that the introduction was seen');
        assert.equal(await page.evaluate(()=>window.welcomePromptDismissals),0,'A retained database setup prompt must not be dismissed implicitly');
        assert.equal(await page.locator('#tab-architecture').isVisible(),true);
        const architecture=page.locator('#mainNavTabs [data-page="architecture"]');
        await architecture.click();
        await navigateToPage(page,'help');
        assert.equal(await page.locator('#tab-help').isVisible(),true,'Help remains explicitly available');
        await architecture.click();
        await page.evaluate(async()=>{TaxonomyOnboarding.reset();await TaxonomyOnboarding.init();});
        await page.locator('#onboardingOverlay[open]').waitFor({state:'visible'});
        await page.waitForFunction(()=>document.activeElement?.id==='onboardingDismiss');
        await page.keyboard.press('Escape');
        await page.locator('#onboardingOverlay').waitFor({state:'detached'});
        await page.waitForFunction(()=>document.activeElement?.matches('#mainNavTabs [data-page="architecture"]'));
        assert.equal(await page.evaluate(()=>localStorage.getItem('taxonomy_onboarded')),'1');
        assert.equal(await page.evaluate(()=>window.welcomePromptDismissals),databasePrompt?1:0);
    }finally{await finish(page);}
});
for(const entry of ['/taxonomy/','/taxonomy/?architectureSource=snapshot#analyze'])test(`first analysis visit still opens its welcome dialog (${entry})`,async()=>{
    const page=await fixture('en',1366,entry);try{
        await firstVisitWelcome(page,true);
        await page.locator('#onboardingOverlay[open]').waitFor({state:'visible'});
        assert.equal(await page.evaluate(()=>localStorage.getItem('taxonomy_onboarded')),null);
        await page.locator('#onboardingDismiss').click();
        await page.locator('#onboardingOverlay').waitFor({state:'detached'});
        assert.equal(await page.locator('#tab-analyze').isVisible(),true);
        assert.equal(await page.evaluate(()=>localStorage.getItem('taxonomy_onboarded')),'1');
        assert.equal(await page.evaluate(()=>window.welcomePromptDismissals),1);
    }finally{await finish(page);}
});
for(const locale of ['de','en']){
    test(`${locale}: projects have one navigation entry and analysis actions stay with the requirement`,async()=>{
        const page=await fixture(locale);try{
            assert.equal(await page.locator('#applicationNavigation [data-app-route="projects"]').count(),1);
            assert.equal(await page.locator('#projectMenuButton').count(),0,'Do not add a second project menu');
            for(const id of ['projectNewAction','projectAddRequirementAction']){
                const action=page.locator(`#analysisNextSteps #${id}`);
                assert.equal(await action.isVisible(),true,`${id} remains directly usable with the requirement`);
                assert.equal(await action.getAttribute('type'),'button');
            }
            assert.equal(await page.locator('#analysisNextSteps #analysisReformulationAction').isVisible(),true);
        }finally{await finish(page);}
    });
    test(`${locale}: Architecture has one destination and switching sections retains the analysis draft`,async()=>{
        const page=await fixture(locale);try{
            await page.locator('#businessText').fill('Retained requirement draft');
            const architecture=page.locator('#applicationNavigation a[data-app-route="architecture"], #applicationNavigation a[data-page="architecture"]');
            assert.equal(await architecture.count(),1,'One Architecture entry must have one coherent destination');
            await architecture.click();
            assert.equal(await page.locator('#tab-architecture').isVisible(),true);
            await page.locator('#mainNavTabs [data-page="analyze"]').click();
            assert.equal(await page.locator('#businessText').inputValue(),'Retained requirement draft');
        }finally{await finish(page);}
    });
    for(const width of [1440,1920])test(`${locale}: ${width}px primary navigation and application actions share one desktop row`,async()=>{
        const page=await fixture(locale,width);try{
            await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
            const selectors=['.taxonomy-app-brand','#mainNavTabs [data-page="architecture"]',
                '#applicationProjectsLink','#applicationSettings > summary','#operationalContextDetails > summary','#applicationHelp > summary'];
            const boxes=await Promise.all(selectors.map(selector=>page.locator(selector).boundingBox()));
            assert.ok(boxes.every(Boolean),'All existing navigation and application actions stay available');
            const center=box=>box.y+box.height/2;
            for(let i=1;i<boxes.length;i++)assert.ok(Math.abs(center(boxes[i])-center(boxes[0]))<=2,
                `${selectors[i]} is not aligned with the main navigation: ${JSON.stringify(boxes)}`);
            const nav=await page.locator('#applicationNavigation').boundingBox();
            assert.ok(nav.height<=64,'The desktop navigation must not reserve a second row: '+JSON.stringify(nav));
            assert.equal(await page.locator('#applicationNavigation [data-app-route="architecture"]').count(),1);
        }finally{await finish(page);}
    });
    for(const width of [992,1024,1100,1280,1366,1408,1409,1439,1500,1632,1633])test(`${locale}: ${width}px navigation stays on one row with reachable language and view help`,async()=>{
        const page=await fixture(locale,width);try{
            await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
            assert.equal(await page.locator('#operationalStatusMenuSlot #operationalContextDetails').count(),1);
            const selectors=width<1200?['.taxonomy-app-brand','#applicationNavigationMenu > summary','#applicationHelp > summary']:['.taxonomy-app-brand','#mainNavTabs [data-page="architecture"]',
                '#applicationProjectsLink','#applicationSettings > summary','#operationalContextDetails > summary','#applicationHelp > summary'];
            const boxes=await Promise.all(selectors.map(selector=>page.locator(selector).boundingBox()));
            assert.ok(boxes.every(Boolean),'Navigation and context help must remain visible');
            const center=box=>box.y+box.height/2;
            for(let i=1;i<boxes.length;i++)assert.ok(Math.abs(center(boxes[i])-center(boxes[0]))<=2,
                `${selectors[i]} is not aligned with the main navigation: ${JSON.stringify(boxes)}`);
            assert.ok((await page.locator('#applicationNavigation').boundingBox()).height<=64);
            await openSharedNavigation(page);
            for(const link of await page.locator('#applicationNavigation .taxonomy-app-primary a').all()){
                const box=await link.boundingBox();
                assert.ok(box&&box.width>=44&&box.height>=44,'Primary links remain usable touch targets: '+JSON.stringify(box));
            }
            assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'The compact row must not widen the page');
            await page.locator('#applicationHelp > summary').click();
            const help=page.locator('#applicationNavigation .taxonomy-app-utilities .context-help');
            assert.ok(await help.getAttribute('href'),'Contextual help keeps its exact link');
            assert.equal(await help.getAttribute('target'),'_blank');
            assert.ok((await help.innerText()).includes(locale==='de'?'Hilfe':'Help'),'Help text stays available to assistive technology');
            assert.equal(await page.getByRole('link',{name:locale==='de'?/Hilfe zu dieser Sicht/:/Help for this view/}).count(),1);
            await page.evaluate(()=>{window.localeChanges=[];TaxonomyI18n.setLocale=value=>localeChanges.push(value);});
            await page.locator(`[data-app-locale="${locale==='de'?'en':'de'}"]`).click();
            assert.deepEqual(await page.evaluate(()=>localeChanges),[locale==='de'?'en':'de']);
        }finally{await finish(page);}
    });
    for(const [view,width] of [['reports',992],['reports',1121],['reports',1441],['versioning',1024],['versioning',1121],['versioning',1441],['versioning',1632],['versioning',1633]])test(`${locale}: ${width}px ${view} navigation keeps direct language links when no settings selector exists`,async()=>{
        const page=await fixture(locale,width);try{
            // Standalone portfolio pages have source-specific actions but no settings selector.
            await page.locator('.taxonomy-app-local').evaluate((node,view)=>{node.innerHTML=view==='reports'
                ? '<div><a id="versioningLink" class="btn btn-sm btn-outline-light" href="/versions">Versionierung</a></div>'
                : '<span id="versioningPageTitle">Versionierung und Zusammenarbeit</span><a id="versioningLink" href="/reports">Berichte</a>';},view);
            assert.equal(await page.locator('.taxonomy-app-languages a[hreflang="de"]').isVisible(),true);
            assert.equal(await page.locator('.taxonomy-app-languages a[hreflang="en"]').isVisible(),true);
            assert.equal(await page.locator('#applicationHelp > summary').isVisible(),true);
            await openSharedNavigation(page);
            assert.equal(await page.locator('#versioningLink').isVisible(),true);
            assert.equal(await page.locator('#applicationNavigation [data-app-route="projects"]').count(),1);
            const boxes=await Promise.all((width<1200?['.taxonomy-app-brand','#applicationNavigationMenu > summary','.taxonomy-app-languages [hreflang="de"]','.taxonomy-app-languages [hreflang="en"]','#applicationHelp > summary']:['.taxonomy-app-brand','#mainNavTabs [data-page="architecture"]','#applicationProjectsLink','#versioningLink',
                '.taxonomy-app-languages [hreflang="de"]','.taxonomy-app-languages [hreflang="en"]','#applicationHelp > summary']).map(selector=>page.locator('#applicationNavigation '+selector).boundingBox()));
            const center=box=>box.y+box.height/2;
            assert.ok(boxes.every(box=>box&&Math.abs(center(box)-center(boxes[0]))<=2),'Portfolio actions, language and help must share the navigation row: '+JSON.stringify(boxes));
            assert.ok((await page.locator('#applicationNavigation').boundingBox()).height<=64);
            if(view==='versioning')assert.ok(await page.locator('#versioningPageTitle').textContent(),'The title remains available to assistive technology');
        }finally{await finish(page);}
    });
    for(const width of [700,900,1100,1280])test(`${locale}: ${width}px settings panel stays inside the viewport`,async()=>{
        const page=await fixture(locale,width);try{
            await openSharedNavigation(page);
            await page.locator('#applicationSettings > summary').click();
            const panel=page.locator('#applicationSettings .application-menu-content');
            assert.equal(await panel.isVisible(),true);
            const box=await panel.boundingBox();
            assert.ok(box&&box.x>=0&&box.x+box.width<=width,
                `Settings must remain fully reachable after header wrapping: ${JSON.stringify(box)}`);
            assert.equal(await page.locator('#darkModeToggle').isVisible(),true);
        }finally{await finish(page);}
    });
    test(`${locale}: one Help menu groups the manual, view help and About`,async()=>{
        const page=await fixture(locale);try{
            await page.locator('#applicationHelp > summary').click();
            for(const selector of ['#main-tab-help','#aboutBtn','#applicationHelp .context-help'])
                assert.equal(await page.locator(selector).isVisible(),true,selector);
            assert.equal((await page.locator('.taxonomy-app-brand').textContent()).trim(),'Taxonomy');
            assert.ok((await page.locator('#applicationNavigation').boundingBox()).height<=64);
        }finally{await finish(page);}
    });
    test(`${locale}: support navigation activates Help and primary keyboard navigation remains reachable`,async()=>{
        const page=await fixture(locale);try{
            await navigateToPage(page,'help');
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
            const toggle=page.locator('#applicationNavigationMenu > summary');
            await page.locator('#applicationHelp > summary').click();
            assert.equal(await help.isVisible(),true);assert.equal(await about.isVisible(),true);
            assert.equal(await help.evaluate(e=>e.parentElement===document.getElementById('aboutBtn').parentElement),true);
            const sizes=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
            assert.ok(sizes.scroll<=sizes.width,JSON.stringify(sizes));
            assert.equal(await page.locator('#mobileMainNavigationSelect:visible').count(),0,'One visible primary navigation');
            for(const destination of ['analyze','architecture','graph','versions','dsl-editor']){
                if(await page.locator('#applicationNavigationMenu').getAttribute('open')===null) await toggle.click();
                const link=page.locator('#mainNavTabs [data-page="'+destination+'"]');
                const box=await link.boundingBox();
                assert.ok(box&&box.height>=44,`Primary destination retains its 44px touch target: ${destination} ${JSON.stringify(box)}`);
                await link.focus();
                await page.waitForFunction(destination=>{
                    const link=document.querySelector('#mainNavTabs [data-page="'+destination+'"]');
                    const box=link.getBoundingClientRect(),row=link.closest('#mainNavTabs').getBoundingClientRect();
                    return document.activeElement===link && box.left>=Math.max(0,row.left)-1
                        && box.right<=Math.min(innerWidth,row.right)+1;
                },destination).catch(async()=>{
                    const geometry=await link.evaluate(link=>{
                        const row=link.closest('#mainNavTabs'),box=link.getBoundingClientRect(),bounds=row.getBoundingClientRect();
                        return {destination:link.dataset.page,focused:document.activeElement===link,
                            left:box.left,right:box.right,rowLeft:bounds.left,rowRight:bounds.right,
                            scrollLeft:row.scrollLeft,scrollWidth:row.scrollWidth,clientWidth:row.clientWidth,
                            snap:getComputedStyle(row).scrollSnapType,overflow:getComputedStyle(row).overflowX,viewport:innerWidth};
                    });
                    assert.fail('Focused destination must be completely visible: '+JSON.stringify(geometry));
                });
                await link.click();
                assert.equal(await page.locator('#tab-'+destination).isVisible(),true);
                await page.waitForFunction(()=>!document.getElementById('applicationNavigationMenu').open);
            }
            await toggle.click();
            await page.locator('#mainNavTabs [data-page="analyze"]').focus();await page.keyboard.press('End');
            assert.equal(await page.locator('#tab-dsl-editor').isVisible(),true);
            await navigateToPage(page,'help');assert.equal(await page.locator('#tab-help').isVisible(),true);
        }finally{await finish(page);}
    });
}
async function roles(page){
    await page.addScriptTag({path:path.join(resources,'static/js/security/taxonomy-role-surface.js')});
    await page.evaluate(()=>TaxonomyRoleSurface.ready);await page.evaluate(()=>Promise.resolve());
}
test('mobile administrator can switch Settings and primary destinations without an obscuring menu',async()=>{
    const page=await fixture('de',390);try{
        await roles(page);
        for(const destination of ['admin','preferences','analyze','architecture']){
            await navigateToPage(page,destination);
            assert.equal(await page.locator('#tab-'+destination).isVisible(),true);
            assert.equal(await page.locator('#applicationNavigationMenu').getAttribute('open'),null);
        }
        assert.deepEqual(page.uiErrors,[]);
    }finally{await finish(page);}
});
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
for(const width of [1366,390])test(`operational warnings remain discoverable and aligned without opening the menu at ${width}px`,async()=>{
    const page=await fixture('de',width);try{
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
        await page.evaluate(()=>Promise.resolve());
        const details=page.locator('#operationalContextDetails');assert.equal(await details.count(),1);
        assert.equal(await page.locator('#operationalStatusMenuSlot #operationalContextDetails').count(),1);
        await page.evaluate(()=>{document.getElementById('aiStatusBadge').textContent='Warning';});
        await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));
        assert.equal(await details.getAttribute('open'),null);
        assert.match(await details.getAttribute('class'),/has-actionable-status/);
        await openSharedNavigation(page);
        const summary=await details.locator('summary').boundingBox(),label=await details.locator('summary > span').first().boundingBox();
        assert.ok(label.x-summary.x<40,'A warning must not push the Status label into the middle of the menu row');
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
        await navigateToPage(page,'help');
        await page.waitForFunction(()=>document.getElementById('helpDocBody').textContent==='Bedienung der Anwendung');
        const expectedRequests=[`/taxonomy/help?lang=${locale}`,`/taxonomy/help/USER_GUIDE?lang=${locale}`];
        assert.deepEqual(await page.evaluate(()=>helpRequests),expectedRequests);
        await page.evaluate(()=>navigateToPage('analyze'));await page.keyboard.press('F1');
        assert.equal(await page.locator('#tab-help').isVisible(),true);
        assert.deepEqual(await page.evaluate(()=>helpRequests),expectedRequests);
    }finally{await finish(page);}
});

for (const locale of ['de','en']) test(`${locale}: bundled Markdown links open the localized help document even when the table of contents arrives late`,async()=>{
    const page=await fixture(locale);try{
        await page.evaluate(()=>{
            document.getElementById('tab-help').innerHTML='<ul id="helpTocList"></ul><h2 id="helpDocTitle"></h2><div id="helpDocBody"></div><button id="helpBackToTop">Top</button>';
            window.helpRequests=[];
            window.fetch=url=>{
                helpRequests.push(String(url));
                if (String(url).includes('/help?')) return new Promise(resolve=>{window.finishToc=()=>resolve({ok:true,json:async()=>[
                    {filename:'USER_GUIDE',icon:'',title:'Guide',audience:'Users'},
                    {filename:'CUSTOM_STYLES',icon:'',title:'Styles',audience:'Operators'}]});});
                return Promise.resolve({ok:true,text:async()=>String(url).includes('/CUSTOM_STYLES?')
                    ? '<h2 id="rancher">Rancher</h2>'
                    : '<a id="stylesLink" href="CUSTOM_STYLES.md#rancher">Styles</a><a id="unknownLink" href="OTHER.md">Other</a><a id="externalLink" href="https://example.org/">External</a>'});
            };
        });
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-help.js')});
        await navigateToPage(page,'help');
        await page.locator('#stylesLink').waitFor();
        await page.evaluate(()=>finishToc());
        await page.waitForFunction(()=>document.getElementById('stylesLink').getAttribute('href')==='#help/CUSTOM_STYLES/rancher');
        assert.equal(await page.locator('#stylesLink').getAttribute('target'),null);
        assert.equal(await page.locator('#unknownLink').getAttribute('href'),'OTHER.md');
        assert.equal(await page.locator('#externalLink').getAttribute('target'),'_blank');
        await page.locator('#stylesLink').click();
        await page.locator('#helpDocBody #rancher').waitFor();
        assert.equal(await page.evaluate(()=>location.hash),'#help/CUSTOM_STYLES/rancher');
        assert.ok((await page.evaluate(()=>helpRequests)).includes(`/taxonomy/help/CUSTOM_STYLES?lang=${locale}`));
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
