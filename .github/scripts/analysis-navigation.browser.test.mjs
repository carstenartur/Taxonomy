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
        <main id="mainContent" class="container-fluid px-3"><div class="row"><div id="leftPanel"></div><div id="rightPanel">${pages.map(p=>`<section id="tab-${p}" class="${p==='analyze'?'':'d-none'}"><h1>${p}</h1>${p==='analyze'?'<div id="analysisRecoveryHost"></div><div id="analysisStatusActions"></div><div class="card"><div class="card-body"><textarea id="businessText"></textarea><button id="analyzeBtn">Analyze</button><div id="statusArea"></div></div></div>'+nextSteps:''}</section>`).join('')}</div></div></main></body></html>`);
    await page.evaluate(dict=>{
        const store=new Map([['taxonomy_onboarded','true']]);
        Object.defineProperty(window,'localStorage',{value:{getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,v),removeItem:k=>store.delete(k)}});
        window.TaxonomyI18n={ready:()=>Promise.resolve(),getLocale:()=>document.documentElement.lang,resolveUrl:p=>'/taxonomy'+p,
            t:(key,...args)=>(dict[key]||key).replace(/\{(\d+)\}/g,(_,n)=>String(args[n]??''))};
        document.querySelectorAll('[th\\:text]').forEach(e=>{const m=e.getAttribute('th:text').match(/^#\{(.+)\}$/);if(m)e.textContent=TaxonomyI18n.t(m[1]);});
        window.account={username:'admin',roles:['ADMIN'],administrator:true,architectureMutationAllowed:true,localUserManagementAllowed:false};
        window.TaxonomyApiClient={
            getJson:()=>window.accountError?Promise.reject(new Error('Account unavailable')):Promise.resolve(window.account),
            // Component tests stub the HTTP boundary, but load the production
            // reference client to verify its consumers instead of inventing globals.
            request:(url,init)=>window.fetch(window.TaxonomyI18n.resolveUrl(url),init)
        };
    },dict);
    await page.addStyleTag({content:bootstrapCss});
    for(const css of ['taxonomy.css','taxonomy-ergonomics.css','context-help.css','application-chrome.css','architecture-lifecycle.css'])
        await page.addStyleTag({path:path.join(resources,'static/css',css)});
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-state.js')});
    await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-utils.js')});
    await page.addScriptTag({path:path.join(resources,'static/js/api/reference-api.js')});
    await page.addScriptTag({path:path.join(resources,'static/js/shared/application-chrome.js')});
    await page.addScriptTag({path:path.join(resources,'static/js/shared/application-commands.js')});
    const nav=template.match(/<!-- Page Navigation Script -->\s*<script>([\s\S]*?)<\/script>/);
    assert.ok(nav);await page.addScriptTag({content:nav[1]});
    return page;
}
test('command categories and work areas have separate, predictable homes',async()=>{
    const page=await fixture('de',1366);try{
        assert.deepEqual(await page.locator('#applicationMenuGroups > details > summary').allTextContents(),
            ['Datei','Bearbeiten','Ansicht','Werkzeuge','Hilfe']);
        assert.deepEqual(await page.locator('#applicationWorkspaces [data-app-route]').evaluateAll(links=>links.map(link=>link.dataset.appRoute)),
            ['projects','analysis','architecture']);
        assert.equal(await page.locator('#applicationNavigation > [data-page], #mainNavTabs').count(),0);
        for(const destination of ['versions','dsl-editor'])
            assert.equal(await page.locator(`#applicationView [data-page="${destination}"]`).count(),1);
        assert.equal(await page.locator('#applicationTools [data-page="graph"]').count(),1);
        assert.equal(await page.locator('#applicationTools #adminNavTab').count(),1);
        assert.equal(await page.locator('#applicationNavigation #applicationSystemStatus').count(),0);
    }finally{await finish(page);}
});
test('menu shortcuts reuse the owner operation and its disabled and source state',async()=>{
    const page=await fixture('de',1366);try{
        assert.equal(await page.evaluate(()=>!!window.TaxonomyAppCommands),true);
        await page.evaluate(()=>{
            const source=document.createElement('section');source.id='commandSource';source.hidden=true;
            source.innerHTML='<button id="commandOwner" disabled>Owned operation</button>';document.body.append(source);
            document.getElementById('commandOwner').setAttribute('aria-pressed','false');
            window.executions=0;document.getElementById('commandOwner').onclick=()=>executions++;
            const proxy=document.createElement('button');proxy.id='commandProxy';proxy.dataset.appCommand='test';
            proxy.dataset.appTarget='commandOwner';proxy.textContent='Owned operation';
            document.querySelector('#applicationFile .application-menu-content').append(proxy);
            TaxonomyAppCommands.refresh();
        });
        const proxy=page.locator('#commandProxy');assert.equal(await proxy.isDisabled(),true);
        await page.evaluate(()=>{document.getElementById('commandOwner').disabled=false;TaxonomyAppCommands.refresh();});
        assert.equal(await proxy.isDisabled(),true,'An inactive source remains unavailable');
        await page.evaluate(()=>{document.getElementById('commandSource').hidden=false;TaxonomyAppCommands.refresh();});
        assert.equal(await proxy.isEnabled(),true);
        assert.equal(await proxy.getAttribute('aria-pressed'),'false');
        await page.evaluate(()=>document.getElementById('commandOwner').setAttribute('aria-pressed','true'));
        await page.waitForFunction(()=>document.getElementById('commandProxy').getAttribute('aria-pressed')==='true');
        await page.locator('#fileMenuButton').click();await proxy.click();
        assert.equal(await page.evaluate(()=>executions),1);
        assert.equal(await page.locator('#applicationFile').evaluate(e=>e.open),false);
        await page.evaluate(()=>{document.getElementById('commandOwner').disabled=true;});
        await page.waitForFunction(()=>document.getElementById('commandProxy').disabled);
    }finally{await finish(page);}
});
async function openCategory(page, name) {
    await openSharedNavigation(page);
    const menu=page.locator('#application'+name);
    if(await menu.getAttribute('open')===null)await menu.locator(':scope > summary').click();
}
for(const allowed of [false,true])test(`work navigation retains an architecture draft until its leave decision (${allowed})`,async()=>{
    const page=await fixture();try{
        await page.evaluate(allowed=>{
            navigateToPage('architecture');window.decisions=0;
            window.TaxonomyArchitectureWorkspace={isActive:()=>true,getSource:()=>({kind:'analysis'}),
                canLeave:async()=>{decisions++;return allowed;},restoreUrl:()=>history.replaceState(null,'','#architecture')};
        },allowed);
        await page.locator('#applicationRequirementLink').click();
        await page.waitForFunction(()=>decisions===1);
        assert.equal(await page.locator(allowed?'#tab-analyze':'#tab-architecture').isVisible(),true);
        assert.equal(await page.evaluate(()=>location.hash),allowed?'#analyze':'#architecture');
    }finally{await finish(page);}
});
test('all five command menus use the same readable native launcher style',async()=>{
    const page=await fixture();try{
        const styles=await page.locator('#applicationMenuGroups > details > summary').evaluateAll(nodes=>nodes.map(node=>{
            const style=getComputedStyle(node);return {font:style.font,padding:style.padding,color:style.color,radius:style.borderRadius,height:node.getBoundingClientRect().height};
        }));
        assert.equal(styles.length,5);for(const style of styles){assert.ok(style.height>=44);assert.deepEqual(style,styles[0]);}
    }finally{await finish(page);}
});
test('File follows the shared close, focus and single-execution behavior',async()=>{
    const page=await fixture();try{
        await openCategory(page,'Tools');await openCategory(page,'File');
        assert.equal(await page.locator('#applicationTools').evaluate(e=>e.open),false);
        await page.locator('#fileSaveDraftAction').focus();await page.keyboard.press('Escape');
        assert.equal(await page.locator('#fileSaveDraftAction').isVisible(),false);
        assert.equal(await page.locator('#fileMenuButton').evaluate(e=>e===document.activeElement),true);
        await openCategory(page,'File');assert.equal(await page.locator('#fileCancelAnalysisAction').isDisabled(),true);
        await page.evaluate(()=>{window.saved=0;document.getElementById('fileSaveDraftAction').addEventListener('click',()=>saved++);});
        await page.locator('#fileSaveDraftAction').click();assert.equal(await page.evaluate(()=>saved),1);
        assert.equal(await page.locator('#fileSaveDraftAction').isVisible(),false);
    }finally{await finish(page);}
});
test('menu keyboard traversal skips unavailable commands and restores focus on Escape',async()=>{
    const page=await fixture();try{
        const file=page.locator('#fileMenuButton');await file.focus();await page.keyboard.press('ArrowRight');
        assert.equal(await page.locator('#applicationEdit > summary').evaluate(e=>e===document.activeElement),true);
        await page.keyboard.press('ArrowLeft');await page.keyboard.press('ArrowDown');
        assert.equal(await page.locator('#applicationFile a[data-page="analyze"]').evaluate(e=>e===document.activeElement),true);
        await page.keyboard.press('ArrowDown');assert.equal(await page.locator('#fileNewAnalysisAction').evaluate(e=>e===document.activeElement),true);
        await page.keyboard.press('ArrowDown');assert.equal(await page.locator('#fileSaveDraftAction').evaluate(e=>e===document.activeElement),true);
        await page.keyboard.press('Escape');assert.equal(await file.evaluate(e=>e===document.activeElement),true);
    }finally{await finish(page);}
});
test('language and appearance are offered once under Tools; help has one coherent group',async()=>{
    const page=await fixture();try{
        assert.equal(await page.locator('#langSelector').count(),0);
        assert.equal(await page.locator('.taxonomy-app-languages [data-app-locale]').count(),2);
        await openCategory(page,'Tools');
        await page.locator('#darkModeToggle').click();assert.equal(await page.locator('html').getAttribute('data-bs-theme'),'dark');
        await openCategory(page,'Tools');assert.equal(await page.locator('#darkModeToggle').getAttribute('aria-pressed'),'true');
        await openCategory(page,'Help');
        for(const selector of ['#main-tab-help','#aboutBtn','#applicationHelp .context-help'])assert.equal(await page.locator(selector).isVisible(),true);
        await openCategory(page,'File');assert.equal(await page.locator('#aboutBtn').isVisible(),false);
    }finally{await finish(page);}
});
for(const width of [1366,390])test(`closing About returns focus to a visible navigation control at ${width}px`,async()=>{
    const page=await fixture('de',width);try{
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
        await openCategory(page,'Help');await page.locator('#aboutBtn').click();
        await page.waitForFunction(()=>document.getElementById('aboutModal').contains(document.activeElement));
        await page.keyboard.press('Escape');await page.locator('#aboutModal').waitFor({state:'hidden'});
        assert.equal(await page.locator(width<1024?'#applicationNavigationMenu > summary':'#applicationHelp > summary').evaluate(node=>node===document.activeElement),true);
    }finally{await finish(page);}
});

for(const delayed of [false,true])test(`mobile database dialog restores visible focus from Architecture (${delayed?'delayed':'cached'} setup read)`,async()=>{
    const page=await fixture('de',390,'/taxonomy/#architecture');try{
        await roles(page);
        const fragment=(await readFile(path.join(resources,'templates/fragments/database-setup.html'),'utf8')).match(/<dialog\b[\s\S]*?<\/dialog>/)[0];
        await page.evaluate(({fragment,delayed})=>{
            const parsed=new DOMParser().parseFromString(fragment,'text/html');document.body.append(parsed.querySelector('dialog'));
            if(!document.getElementById('databaseSetupNotice')){const notice=document.createElement('div');notice.id='databaseSetupNotice';notice.hidden=true;document.body.append(notice);}
            window.TaxonomyApiClient={getAccountContext:async()=>({administrator:true}),getJson:async()=>{
                const state={database:'hsqldb',inMemory:true,promptRequired:false,deploymentManaged:true};
                if(!delayed)return state;
                return new Promise(resolve=>{window.finishSetupRead=()=>resolve(state);});
            }};
        },{fragment,delayed});
        await page.addStyleTag({path:path.join(resources,'static/css/taxonomy-database-setup.css')});
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-database-setup.js')});
        if(!delayed)await page.evaluate(()=>TaxonomyDatabaseSetup.prepareWelcome());
        await openCategory(page,'Tools');await page.locator('#databaseSetupMenuAction').click();
        if(delayed){await page.waitForFunction(()=>!!window.finishSetupRead);await page.evaluate(()=>finishSetupRead());}
        await page.locator('#databaseSetupDialog').waitFor({state:'visible'});await page.locator('#databaseSetupClose').click();
        await page.waitForFunction(()=>document.activeElement?.matches('#applicationNavigationMenu > summary'));
        assert.equal(await page.locator('#tab-architecture').isVisible(),true);
    }finally{await finish(page);}
});
for(const locale of ['de','en'])for(const width of [320,390,700,768,900,1100,1366])test(`${locale}: commands and work areas remain reachable at ${width}px`,async()=>{
    const page=await fixture(locale,width);try{
        const toggle=page.locator('#applicationNavigationMenu > summary');
        assert.equal(await toggle.isVisible(),width<1024);
        assert.ok((await page.locator('#applicationNavigation').boundingBox()).height<=64);
        for(const link of await page.locator('#applicationWorkspaces a').all()){
            assert.equal(await link.isVisible(),true);const box=await link.boundingBox();assert.ok(box.height>=44&&box.width>=44);
        }
        await page.locator('#businessText').fill('Retained requirement draft');
        for(const destination of ['architecture','versions','dsl-editor','graph','help','analyze']){
            await navigateToPage(page,destination);assert.equal(await page.locator('#tab-'+destination).isVisible(),true);
            if(width<1024)assert.equal(await page.locator('#applicationNavigationMenu').getAttribute('open'),null);
        }
        assert.equal(await page.locator('#businessText').inputValue(),'Retained requirement draft');
        for(const category of ['File','Edit','View','Tools','Help']){
            await openCategory(page,category);
            const panel=await page.locator('#application'+category+' .application-menu-content').boundingBox();
            assert.ok(panel&&panel.x>=0&&panel.x+panel.width<=width+1,category+': '+JSON.stringify(panel));
        }
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    }finally{await finish(page);}
});
test('compact menu reports its expanded state and controlled region to assistive technology',async()=>{
    const page=await fixture('de',390);try{
        const toggle=page.locator('#applicationNavigationMenu > summary');
        assert.equal(await toggle.getAttribute('aria-controls'),'applicationMenuGroups');
        const session=await page.context().newCDPSession(page),doc=await session.send('DOM.getDocument');
        const {nodeId}=await session.send('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'#applicationNavigationMenu > summary'});
        async function state(){const {nodes}=await session.send('Accessibility.getPartialAXTree',{nodeId,fetchRelatives:false});return {name:nodes[0].name.value,...Object.fromEntries(nodes[0].properties.map(p=>[p.name,p.value.value]))};}
        assert.equal((await state()).expanded,false);await toggle.click();const opened=await state();
        assert.equal(opened.name,'Menü');assert.equal(opened.expanded,true);assert.equal(opened.controls,'applicationMenuGroups');await session.detach();
    }finally{await finish(page);}
});
test('customer navigation colors remain paired in the compact menu',async()=>{
    const page=await fixture('de',390);try{
        await page.evaluate(()=>{document.documentElement.style.setProperty('--taxonomy-navigation-foreground','#19304a');document.documentElement.style.setProperty('--taxonomy-navigation-background','#f4f7fb');});
        await openSharedNavigation(page);
        assert.equal(await page.locator('#applicationMenuGroups').evaluate(e=>getComputedStyle(e).backgroundColor),'rgb(244, 247, 251)');
        for(const selector of ['.taxonomy-app-brand','#applicationTools > summary'])assert.equal(await page.locator(selector).evaluate(e=>getComputedStyle(e).color),'rgb(25, 48, 74)');
    }finally{await finish(page);}
});
test('compact category and outer menu close in keyboard order',async()=>{
    const page=await fixture('de',390);try{
        await openCategory(page,'Tools');const summary=page.locator('#applicationTools > summary');await summary.focus();
        await page.keyboard.press('Escape');assert.equal(await page.locator('#applicationTools').getAttribute('open'),null);
        assert.notEqual(await page.locator('#applicationNavigationMenu').getAttribute('open'),null);
        await page.keyboard.press('Escape');assert.equal(await page.locator('#applicationNavigationMenu').getAttribute('open'),null);
        assert.equal(await page.locator('#applicationNavigationMenu > summary').evaluate(e=>e===document.activeElement),true);
    }finally{await finish(page);}
});
test('system status shortcut works independently of the compact command menu',async()=>{
    const page=await fixture('de',390);try{
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-onboarding.js')});
        await page.keyboard.press('Alt+Shift+O');
        assert.equal(await page.locator('#applicationNavigationMenu').getAttribute('open'),null);
        assert.notEqual(await page.locator('#operationalContextDetails').getAttribute('open'),null);
        assert.equal(await page.locator('#operationalContextDetails > summary').evaluate(e=>e===document.activeElement),true);
        await page.keyboard.press('Alt+Shift+O');assert.equal(await page.locator('#operationalContextDetails').getAttribute('open'),null);
    }finally{await finish(page);}
});
test('resizing keeps command focus reachable while work navigation stays visible',async()=>{
    const page=await fixture('de',1280);try{
        await page.locator('#fileMenuButton').focus();await page.setViewportSize({width:390,height:768});
        await page.waitForFunction(()=>document.activeElement?.matches('#applicationNavigationMenu > summary'));
        assert.equal(await page.locator('#applicationWorkspaces').isVisible(),true);
        await page.setViewportSize({width:1280,height:768});
        await page.waitForFunction(()=>document.activeElement?.id==='fileMenuButton');
        assert.equal(await page.locator('#applicationNavigationMenu > summary').isVisible(),false);
    }finally{await finish(page);}
});
test('leaving compact commands closes the panel without stealing focus',async()=>{
    const page=await fixture('de',390);try{
        await openCategory(page,'File');await page.locator('#applicationRequirementLink').focus();
        assert.equal(await page.locator('#applicationNavigationMenu').getAttribute('open'),null);
        assert.equal(await page.locator('#applicationRequirementLink').evaluate(e=>e===document.activeElement),true);
    }finally{await finish(page);}
});
test('native compact menus and work links remain available without JavaScript',async()=>{
    const page=await browser.newPage({viewport:{width:390,height:768},javaScriptEnabled:false});try{
        let css=bootstrapCss;for(const name of ['taxonomy.css','application-chrome.css'])css+='\n'+await readFile(path.join(resources,'static/css',name),'utf8');
        await page.setContent('<html><head><style>'+css+'</style></head><body>'+headerMarkup('de')+'</body></html>');
        assert.equal(await page.locator('#applicationWorkspaces').isVisible(),true);
        await page.locator('#fileMenuButton').click();assert.equal(await page.locator('#fileSaveDraftAction').isVisible(),true);
        await page.locator('#applicationNavigationMenu > summary').click();assert.equal(await page.locator('#fileMenuButton').isVisible(),false);
        assert.equal(await page.locator('#applicationWorkspaces').isVisible(),true);
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
        const architecture=page.locator('#applicationArchitectureLink');
        await architecture.click();
        await navigateToPage(page,'help');
        assert.equal(await page.locator('#tab-help').isVisible(),true,'Help remains explicitly available');
        await architecture.click();
        await page.evaluate(async()=>{TaxonomyOnboarding.reset();await TaxonomyOnboarding.init();});
        await page.locator('#onboardingOverlay[open]').waitFor({state:'visible'});
        await page.waitForFunction(()=>document.activeElement?.id==='onboardingDismiss');
        await page.keyboard.press('Escape');
        await page.locator('#onboardingOverlay').waitFor({state:'detached'});
        await page.waitForFunction(()=>document.activeElement?.matches('#applicationArchitectureLink'));
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
for(const locale of ['de','en'])test(`${locale}: requirement actions stay with their work object`,async()=>{
    const page=await fixture(locale);try{
        assert.equal(await page.locator('#applicationWorkspaces [data-app-route="projects"]').count(),1);
        assert.equal(await page.locator('#projectMenuButton').count(),0);
        for(const id of ['projectNewAction','projectAddRequirementAction','analysisReformulationAction'])assert.equal(await page.locator('#analysisNextSteps #'+id).isVisible(),true);
    }finally{await finish(page);}
});
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
        await page.locator('#applicationTools > summary').click();await entry.click();
        assert.equal(await page.locator('#tab-admin').isVisible(),true);
        await page.locator('#applicationTools > summary').click();
        const administration=page.locator('#adminNavTab');
        const preferences=page.locator('#preferencesNavTab');
        assert.equal(await administration.isVisible(),true);
        assert.equal(await preferences.isVisible(),true);
        await administration.click();
        assert.equal(await page.locator('#tab-admin').isVisible(),true);
        await page.locator('#applicationTools > summary').click();
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
        await page.locator('#applicationTools > summary').click();
        assert.equal(await page.locator('#adminNavTab').isVisible(),false);
        assert.equal(await page.locator('#preferencesNavTab').isVisible(),false);
        await page.locator('#applicationTools > summary').click();
        await page.evaluate(async()=>{window.accountError=false;window.account={username:'reader',roles:['USER'],administrator:false,localUserManagementAllowed:false};await TaxonomyRoleSurface.refresh();});
        assert.equal(await page.locator('#documentTemplateAdminEntry').count(),0);
        assert.equal(await page.locator('#localUserManagementAdminEntry').count(),0);
        await page.locator('#applicationTools > summary').click();
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
        assert.equal(await page.locator('#applicationSystemStatus #operationalContextDetails').count(),1);
        await page.evaluate(()=>{document.getElementById('aiStatusBadge').textContent='Warning';document.getElementById('aiStatusBadge').classList.remove('d-none');});
        await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));
        assert.equal(await details.getAttribute('open'),null);
        assert.match(await details.getAttribute('class'),/has-actionable-status/);
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

test('dismissing inline feedback returns focus to its visible analysis status control',async()=>{
    const page=await fixture();try{
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-recovery-viewport.js')});
        await page.evaluate(()=>{window.feedback=TaxonomyRecoveryViewport.mount({text:(de,en)=>de,scopeKey:()=> 'workspace/run',open:()=>{},cancel:()=>{}});feedback.update('Running',true,true);});
        await page.locator('#analysisRecoveryDismiss').click();
        assert.equal(await page.locator('#analysisRecoveryReopen').evaluate(e=>document.activeElement===e),true);
        assert.equal(await page.locator('#analysisRecoveryReopen').isVisible(),true);
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
