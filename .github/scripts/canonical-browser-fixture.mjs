/* Browser component fixtures load the real named boundaries over controlled HTTP responses. */
import {readFile} from 'node:fs/promises';
import path from 'node:path';
export async function installBrowserApiFixture(page, resources) {
    await page.evaluate(() => {
        const nativeFetch = window.fetch.bind(window);
        window.__taxonomyFixtureNativeFetch = nativeFetch;
        window.fetch = async function (input, init) {
            const response = window.__taxonomyFixtureFetch
                ? await window.__taxonomyFixtureFetch(input, init)
                : await nativeFetch(input, init);
            if (response.status === undefined) response.status = response.ok === false ? 500 : 200;
            if (response.ok === undefined) response.ok = response.status >= 200 && response.status < 300;
            if (!response.headers) response.headers = new Headers({'Content-Type': 'application/json'});
            if (!response.clone) response.clone = () => response;
            if (!response.text && response.json) response.text = async () => JSON.stringify(await response.json());
            return response;
        };
    });
    const source = await readFile(path.join(resources, 'static/js/api/taxonomy-api-client.js'), 'utf8');
    await page.addScriptTag({content: source.slice(0, source.indexOf('(function loadAuthenticatedUiSurfaces'))});
    for (const name of ['reference', 'analysis', 'export', 'proposals', 'search', 'relations']) {
        await page.addScriptTag({path: path.join(resources, 'static/js/api/' + name + '-api.js')});
    }
}
