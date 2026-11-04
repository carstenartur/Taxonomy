import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { validateSystemSnapshot } from './system-information-acceptance.mjs';

function snapshot() {
  return {
    timestamp: '2026-09-06T12:00:00Z', instanceId: 'disposable-test-instance',
    runtime: { availableProcessors: 2 },
    database: { status: 'AVAILABLE', product: 'fixture database', version: '1.2.3',
      versionSource: 'DATABASE_QUERY', storage: 'IN_MEMORY', lifetime: 'APPLICATION_PROCESS',
      schemaAction: 'CREATE', warnings: ['IN_MEMORY_APPLICATION_PROCESS', 'DESTRUCTIVE_SCHEMA_ACTION'],
      identity: {status: 'AVAILABLE', user: 'connected_user', sessionUser: 'connected_user', database: 'PUBLIC', schema: 'PUBLIC', source: 'DATABASE_QUERY'},
      engineStart: {status: 'NOT_SUPPORTED', value: null, source: 'UNAVAILABLE', timeBasis: null} },
    disks: [{ purposes: ['TEMPORARY_FILES'], status: 'AVAILABLE', totalBytes: 1024, usableBytes: 0 }]
  };
}

test('requires both independent data-loss warnings and accepts a full disk', () => {
  assert.doesNotThrow(() => validateSystemSnapshot(snapshot()));
  for (const warning of ['IN_MEMORY_APPLICATION_PROCESS', 'DESTRUCTIVE_SCHEMA_ACTION']) {
    const value = snapshot();
    value.database.warnings = value.database.warnings.filter(item => item !== warning);
    assert.throws(() => validateSystemSnapshot(value));
  }
});

test('separates remote in-memory database process from application process', () => {
  const value = snapshot();
  value.database.lifetime = 'DATABASE_PROCESS';
  assert.throws(() => validateSystemSnapshot(value));
  value.database.warnings[0] = 'IN_MEMORY_DATABASE_PROCESS';
  assert.doesNotThrow(() => validateSystemSnapshot(value));
});

test('file and server storage still require the unverified-durability warning', () => {
  for (const storage of ['FILE_BACKED', 'SERVER_MANAGED']) {
    const value = snapshot();
    Object.assign(value.database, { storage, lifetime: 'STORAGE_DEPENDENT', schemaAction: 'VALIDATE', warnings: [] });
    assert.throws(() => validateSystemSnapshot(value));
    value.database.warnings.push('STORAGE_DURABILITY_UNVERIFIED');
    assert.doesNotThrow(() => validateSystemSnapshot(value));
  }
});

test('native probe failure cannot pass merely with nonempty metadata', () => {
  for (const [key, replacement] of [['status', 'PARTIAL'], ['versionSource', 'JDBC_METADATA_FALLBACK'],
    ['storage', 'UNKNOWN'], ['schemaAction', 'UNKNOWN'], ['version', '']]) {
    const value = snapshot();
    value.database[key] = replacement;
    assert.throws(() => validateSystemSnapshot(value));
  }
});

test('unavailable capacity is not represented as a measured zero', () => {
  const value = snapshot();
  value.disks = [{ status: 'UNAVAILABLE', totalBytes: null, usableBytes: null }];
  assert.doesNotThrow(() => validateSystemSnapshot(value));
  value.disks[0].usableBytes = 0;
  assert.throws(() => validateSystemSnapshot(value));
});

test('invalid runtime and timestamp cannot produce a valid acceptance record', () => {
  const value = snapshot();
  value.runtime.availableProcessors = 0;
  assert.throws(() => validateSystemSnapshot(value));
  value.runtime.availableProcessors = 2;
  value.timestamp = 'not a measurement';
  assert.throws(() => validateSystemSnapshot(value));
});

test('unavailable database startup never passes with an invented timestamp or source', () => {
  for (const [key, value] of [['value', '2026-09-06T12:00:00Z'], ['timeBasis', 'UTC'], ['source', 'DATABASE_QUERY']]) {
    const data = snapshot();
    data.database.engineStart[key] = value;
    assert.throws(() => validateSystemSnapshot(data));
  }
});

test('measured startup requires a timestamp matching its declared time basis', () => {
  const data = snapshot();
  data.database.engineStart = {status: 'AVAILABLE', source: 'DATABASE_QUERY', timeBasis: 'UTC', value: '2026-07-01T12:20:30Z'};
  assert.doesNotThrow(() => validateSystemSnapshot(data));
  data.database.engineStart.value = '2026-07-01T12:20:30';
  assert.throws(() => validateSystemSnapshot(data));
  data.database.engineStart.timeBasis = 'SERVER_LOCAL_TIME';
  assert.doesNotThrow(() => validateSystemSnapshot(data));
  data.database.engineStart.value = '2026-07-01T12:20:30Z';
  assert.throws(() => validateSystemSnapshot(data));
});

// Execute the production renderer. Native browser/a11y/reflow coverage stays in
// runSystemInformationAcceptance; this fixture isolates values and request timing.
function element(tagName = 'div') {
  const listeners = new Map(), attributes = new Map();
  return {
    tagName, children: [], ownText: '', open: false, disabled: false,
    set textContent(value) { this.ownText = String(value); this.children = []; },
    get textContent() { return this.ownText + this.children.map(child => child.textContent).join(''); },
    set innerHTML(_) { throw new Error('Database identifiers must be rendered as text'); },
    appendChild(child) { this.children.push(child); return child; },
    replaceChildren(...children) { this.children = children; this.ownText = ''; },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    getAttribute(name) { return attributes.get(name) ?? null; },
    addEventListener(name, listener) { listeners.set(name, listener); },
    fire(name) { listeners.get(name)?.(); }
  };
}
const descendants = node => [node, ...node.children.flatMap(descendants)];
const settle = () => new Promise(resolve => setImmediate(resolve));

async function renderer(locale = 'en', response = null) {
  const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
  const translations = {};
  for (const name of ['messages_observability.properties',
    ...(locale === 'de' ? ['messages_observability_de.properties'] : [])]) {
    for (const line of readFileSync(new URL(`i18n/${name}`, resources), 'utf8').split('\n')) {
      const match = line.match(/^([^#!\s][^=]*)=(.*)$/);
      if (match) translations[match[1]] = match[2].replace(/\\u([0-9a-fA-F]{4})/g,
        (_, value) => String.fromCharCode(parseInt(value, 16)));
    }
  }
  const health = element('details'); health.id = 'healthDashboard';
  const document = {readyState: 'complete', createElement: element,
    getElementById: id => descendants(health).find(node => node.id === id) || null};
  const requests = [];
  const errors = [];
  const value = response || {
    ...snapshot(), applicationVersion: 'test application', indexStorage: 'local-heap',
    runtime: {availableProcessors: 2, javaVersion: '25', javaVendor: 'fixture',
      osName: 'Linux', osArchitecture: 'amd64', startTime: '2026-09-06T11:00:00Z', uptimeMillis: 3600000},
    database: {...snapshot().database, driver: 'fixture driver', driverVersion: '1.0', storageSource: 'DATABASE_QUERY',
      identity: {status: 'AVAILABLE', user: 'connected_user', sessionUser: 'connected_user', database: 'runtime_catalog', schema: 'runtime_schema', source: 'DATABASE_QUERY'},
      engineStart: {status: 'AVAILABLE', value: '2026-07-01T14:20:30', source: 'DATABASE_QUERY', timeBasis: 'SERVER_LOCAL_TIME'}}
  };
  class OffsetAwareDate extends Date {
    constructor(value) {
      assert.ok(typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d(?:\.\d+)?)?$/.test(value),
        'A database server-local timestamp must not be parsed in the browser time zone');
      super(value);
    }
  }
  const context = {
    window: {}, document, Date: OffsetAwareDate,
    TaxonomyI18n: {t: key => translations[key] || key, getLocale: () => locale, ready: async () => {}},
    TaxonomyApiClient: {getJson: url => {
      requests.push(url);
      const response = Promise.resolve(value);
      const then = response.then.bind(response);
      response.then = callback => then(data => {
        try { return callback(data); } catch (error) { errors.push(error); throw error; }
      });
      return response;
    }}
  };
  vm.runInNewContext(readFileSync(new URL('static/js/shared/taxonomy-system-information.js', resources), 'utf8'), context);
  await settle();
  return {
    document, requests, value, translations,
    open: async () => { const panel = document.getElementById('systemInformation'); panel.open = true; panel.fire('toggle'); await settle(); },
    row: key => {
      assert.deepEqual(errors, []);
      const nodes = descendants(document.getElementById('systemInformationContent'));
      const index = nodes.findIndex(node => node.tagName === 'dt' && node.textContent === translations[key]);
      return index < 0 ? undefined : nodes[index + 1].textContent;
    }
  };
}

for (const locale of ['en', 'de']) {
  test(`${locale}: actual session identity and server-local engine start are distinct from application start`, async () => {
    const ui = await renderer(locale);
    assert.equal(ui.requests.length, 0);
    await ui.open();
    assert.equal(ui.row('system.identity.user'), 'connected_user');
    assert.equal(ui.row('system.identity.database'), 'runtime_catalog');
    assert.equal(ui.row('system.identity.schema'), 'runtime_schema');
    assert.match(ui.row('system.engineStarted'), /2026-07-01 14:20:30/);
    assert.ok(ui.row('system.engineStarted').includes(ui.translations['system.timeBasis.SERVER_LOCAL_TIME']));
    assert.notEqual(ui.row('system.engineStarted'), ui.row('system.started'));
    assert.deepEqual(ui.requests, ['/api/admin/system-information']);
    ui.document.getElementById('systemInformationRefresh').fire('click');
    await settle();
    assert.equal(ui.requests.length, 2);
  });
}

test('optional unavailable engine start preserves identity and never displays a guessed application timestamp', async () => {
  const ui = await renderer();
  ui.value.database.engineStart = {status: 'NOT_PERMITTED', value: null, source: 'UNAVAILABLE', timeBasis: null};
  await ui.open();
  assert.equal(ui.row('system.identity.user'), 'connected_user');
  assert.equal(ui.row('system.engineStarted'), ui.translations['system.probe.NOT_PERMITTED']);
  assert.notEqual(ui.row('system.engineStarted'), ui.row('system.started'));
  assert.equal(ui.document.getElementById('systemInformationContent').getAttribute('aria-busy'), 'false');
});

test('database identifiers remain literal text and an unsupported HSQL start has an explicit explanation', async () => {
  const ui = await renderer('de');
  ui.value.database.identity.user = '<img src=x onerror=alert(1)>';
  ui.value.database.engineStart = {status: 'NOT_SUPPORTED', value: null, source: 'UNAVAILABLE', timeBasis: null};
  await ui.open();
  assert.equal(ui.row('system.identity.user'), '<img src=x onerror=alert(1)>');
  assert.equal(ui.row('system.engineStarted'), ui.translations['system.probe.NOT_SUPPORTED']);
});

test('a distinct authenticated login stays visible alongside the effective database principal', async () => {
  const ui = await renderer();
  ui.value.database.identity.user = 'dbo';
  ui.value.database.identity.sessionUser = 'taxonomy_app';
  await ui.open();
  assert.equal(ui.row('system.identity.user'), 'dbo');
  assert.equal(ui.row('system.identity.sessionUser'), 'taxonomy_app');
});

test('an offset-backed database startup is displayed explicitly in UTC', async () => {
  const ui = await renderer('de');
  ui.value.database.engineStart = {status: 'AVAILABLE', value: '2026-07-01T12:20:30Z', source: 'DATABASE_QUERY', timeBasis: 'UTC'};
  await ui.open();
  assert.match(ui.row('system.engineStarted'), /12:20:30 \(UTC\)/);
  assert.equal(ui.row('system.identity.sessionUser'), undefined, 'Equal session/effective users are not duplicated');
});
