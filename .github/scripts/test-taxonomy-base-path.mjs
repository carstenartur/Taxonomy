import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(
  new URL('../../taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js', import.meta.url),
  'utf8'
);

async function loadBootstrap(scriptUrl) {
  const requests = [];
  const nativeFetch = async input => {
    requests.push(input);
    return { ok: true, json: async () => ({}) };
  };
  const document = {
    currentScript: { src: scriptUrl },
    cookie: '',
    documentElement: { lang: 'en' },
    querySelector: () => null,
    createElement: () => ({ dataset: {} }),
    head: { appendChild: () => undefined },
    dispatchEvent: () => undefined
  };
  const window = {
    fetch: nativeFetch,
    location: { href: 'https://taxonomy.example.test/taxonomy/' }
  };
  const context = vm.createContext({
    window,
    document,
    localStorage: { getItem: () => null, setItem: () => undefined },
    URL,
    CustomEvent: class CustomEvent {},
    encodeURIComponent,
    console
  });
  context.fetch = (...args) => window.fetch(...args);
  vm.runInContext(source, context, { filename: 'taxonomy-i18n.js' });
  await window.TaxonomyI18n.ready();
  return { window, requests };
}

{
  const { window, requests } = await loadBootstrap(
    'https://taxonomy.example.test/taxonomy/js/taxonomy-i18n.js'
  );
  assert.equal(window.TaxonomyI18n.getBasePath(), '/taxonomy');
  assert.equal(requests[0], '/taxonomy/api/i18n/en');

  await window.fetch('/api/status');
  await window.fetch('/taxonomy/api/status');
  await window.fetch('https://provider.example.test/v1/status');
  assert.deepEqual(requests.slice(1), [
    '/taxonomy/api/status',
    '/taxonomy/api/status',
    'https://provider.example.test/v1/status'
  ]);
}

{
  const { window, requests } = await loadBootstrap(
    'https://taxonomy.example.test/js/taxonomy-i18n.js'
  );
  assert.equal(window.TaxonomyI18n.getBasePath(), '');
  assert.equal(requests[0], '/api/i18n/en');
}

console.log('Taxonomy base-path bootstrap tests passed.');
