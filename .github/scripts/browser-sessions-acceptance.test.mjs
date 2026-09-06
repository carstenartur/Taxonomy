import assert from 'node:assert/strict';
import test from 'node:test';
import { validateSessionSnapshot } from './browser-sessions-acceptance.mjs';

const instant = '2026-09-06T12:00:00Z';
function snapshot() {
  return { timestamp: instant, scope: 'LOCAL_INSTANCE', userCount: 1, sessionCount: 2,
    unidentifiedSessionCount: 0, truncated: false,
    users: [{ username: 'operator', authenticationType: 'LOCAL', sessionCount: 2, lastRequest: instant }] };
}

test('one account with two sessions remains distinct from two people', () => {
  validateSessionSnapshot(snapshot(), ['private-test-password']);
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), userCount: 2 }));
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), sessionCount: 3 }));
});

test('empty and unidentified sessions have honest counts and local scope', () => {
  validateSessionSnapshot({ ...snapshot(), userCount: 0, sessionCount: 0, users: [] });
  validateSessionSnapshot({ ...snapshot(), userCount: 0, sessionCount: 1,
    unidentifiedSessionCount: 1, users: [] });
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), scope: 'CLUSTER' }));
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), unidentifiedSessionCount: 2 }));
});

test('only the allowlisted projection is accepted, without raw private values', () => {
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), token: 'private' }));
  const data = snapshot();
  data.users[0].sessionId = 'private';
  assert.throws(() => validateSessionSnapshot(data));
  assert.throws(() => validateSessionSnapshot(snapshot(), ['operator']));
  assert.throws(() => validateSessionSnapshot(snapshot(), ['']));
});

test('truncation cannot conceal incorrect counts or exceed the row bound', () => {
  validateSessionSnapshot({ ...snapshot(), userCount: 201, sessionCount: 401, truncated: true,
    users: Array.from({ length: 200 }, () => ({ ...snapshot().users[0] })) });
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), truncated: true }));
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), userCount: 3,
    sessionCount: 3, unidentifiedSessionCount: 1, truncated: true }));
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), userCount: 201, sessionCount: 402,
    users: Array.from({ length: 201 }, () => ({ ...snapshot().users[0] })) }));
});

test('invalid timestamps, authentication types and numeric values fail acceptance', () => {
  for (const key of ['userCount', 'sessionCount', 'unidentifiedSessionCount']) {
    for (const value of [-1, 0.5, NaN, '2']) {
      assert.throws(() => validateSessionSnapshot({ ...snapshot(), [key]: value }));
    }
  }
  assert.throws(() => validateSessionSnapshot({ ...snapshot(), timestamp: 'not-a-date' }));
  for (const change of [{ authenticationType: 'BEARER' }, { sessionCount: 0 },
    { lastRequest: 'not-a-date' }, { username: '' }]) {
    assert.throws(() => validateSessionSnapshot({ ...snapshot(), users: [{ ...snapshot().users[0], ...change }] }));
  }
});

test('OIDC account names may be email-shaped without permitting extra claims', () => {
  const data = snapshot();
  data.users[0] = { ...data.users[0], username: 'operator@example.invalid', authenticationType: 'OIDC' };
  validateSessionSnapshot(data);
  data.users[0].claims = { email: 'private@example.invalid' };
  assert.throws(() => validateSessionSnapshot(data));
});
