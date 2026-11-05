import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {chromium} from 'playwright';

const resources=process.env.TAXONOMY_TEST_RESOURCE_ROOT||path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_EXECUTABLE?{executablePath:process.env.CHROMIUM_EXECUTABLE}:{}),args:['--no-sandbox','--disable-dev-shm-usage']});});
after(async()=>{await browser?.close();});

async function fixture(prefillPassword=false){
    const page=await browser.newPage();
    await page.route('http://admin.test/**',route=>{
        const url=new URL(route.request().url());
        return route.fulfill({contentType:'text/html',body:url.pathname==='/edit' ? `<!doctype html><html><body>
            <a id="leave" href="/next">Architecture</a>
            <form id="userProfileForm" action="/saved" method="get">
                <input name="displayName" id="displayName" value="Alice">
                <label><input name="roles" id="role" type="checkbox">Architect</label>
                <button type="submit">Save profile</button>
            </form>
            <form id="userPasswordForm" action="/password" method="get">
                <input name="newPassword" id="newPassword" type="password">
                <button type="submit">Save password</button>
            </form></body></html>` : '<!doctype html><html><body>Destination</body></html>'});
    });
    await page.goto('http://admin.test/edit');
    if(prefillPassword) await page.locator('#newPassword').fill('autofilled-secret');
    await page.addScriptTag({path:path.join(resources,'static/js/security/unsaved-user-form.js')});
    return page;
}

test('leaving a changed user form asks before discarding it; reverting removes the prompt',async()=>{
    const page=await fixture(),dialogs=[];
    try {
        page.on('dialog',dialog=>{dialogs.push(dialog.type());dialog.dismiss();});
        await page.locator('#displayName').fill('Bob');await page.locator('#leave').click();
        assert.deepEqual(dialogs,['beforeunload']);assert.match(page.url(),/\/edit$/);
        assert.equal(await page.locator('#displayName').inputValue(),'Bob');
        await page.locator('#displayName').fill('Alice');await page.locator('#leave').click();
        assert.deepEqual(dialogs,['beforeunload']);assert.match(page.url(),/\/next$/);
    } finally {await page.close();}
});

test('editing a prefilled password is detected without storing the secret',async()=>{
    const page=await fixture(true),dialogs=[];
    try {
        page.on('dialog',dialog=>{dialogs.push(dialog.type());dialog.dismiss();});
        await page.locator('#newPassword').fill('different-secret');await page.locator('#leave').click();
        assert.deepEqual(dialogs,['beforeunload']);assert.match(page.url(),/\/edit$/);
    } finally {await page.close();}
});

test('submitting one user form does not discard an edited second form',async()=>{
    const page=await fixture(),dialogs=[];
    try {
        page.on('dialog',dialog=>{dialogs.push(dialog.type());dialog.dismiss();});
        await page.locator('#displayName').fill('Bob');await page.locator('#newPassword').fill('secret');
        await page.getByRole('button',{name:'Save profile'}).click();
        assert.deepEqual(dialogs,['beforeunload']);assert.match(page.url(),/\/edit$/);
        assert.equal(await page.locator('#displayName').inputValue(),'Bob');
        await page.locator('#leave').click();
        assert.deepEqual(dialogs,['beforeunload','beforeunload']);assert.match(page.url(),/\/edit$/);
    } finally {await page.close();}
});

test('submitting the only edited user form proceeds normally',async()=>{
    const page=await fixture(),dialogs=[];
    try {
        page.on('dialog',dialog=>{dialogs.push(dialog.type());dialog.dismiss();});
        await page.locator('#role').check();await page.getByRole('button',{name:'Save profile'}).click();
        assert.deepEqual(dialogs,[]);assert.match(page.url(),/\/saved\?/);
    } finally {await page.close();}
});

test('a canceled submission keeps the edited form protected',async()=>{
    const page=await fixture(),dialogs=[];
    try {
        page.on('dialog',dialog=>{dialogs.push(dialog.type());dialog.dismiss();});
        await page.evaluate(()=>document.getElementById('userProfileForm').addEventListener('submit',event=>event.preventDefault()));
        await page.locator('#displayName').fill('Bob');await page.getByRole('button',{name:'Save profile'}).click();
        await page.locator('#leave').click();
        assert.deepEqual(dialogs,['beforeunload']);assert.match(page.url(),/\/edit$/);
        assert.equal(await page.locator('#displayName').inputValue(),'Bob');
    } finally {await page.close();}
});
