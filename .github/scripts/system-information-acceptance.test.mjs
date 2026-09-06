import assert from 'node:assert/strict';
import { test } from 'node:test';
import { validateSystemSnapshot } from './system-information-acceptance.mjs';

function snapshot() {
  return {
    timestamp: '2026-09-06T12:00:00Z', instanceId: 'disposable-test-instance',
    runtime: { availableProcessors: 2 },
    database: { status: 'AVAILABLE', product: 'fixture database', version: '1.2.3',
      versionSource: 'DATABASE_QUERY', storage: 'IN_MEMORY', lifetime: 'APPLICATION_PROCESS',
      schemaAction: 'CREATE', warnings: ['IN_MEMORY_APPLICATION_PROCESS', 'DESTRUCTIVE_SCHEMA_ACTION'] },
    disks: [{ status: 'AVAILABLE', totalBytes: 1024, usableBytes: 0 }]
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
