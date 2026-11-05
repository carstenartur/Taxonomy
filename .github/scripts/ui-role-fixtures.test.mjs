import test from 'node:test';
import assert from 'node:assert/strict';

import { provisionRoleAccount } from './ui-role-fixtures.mjs';

const ACCOUNT = Object.freeze({
  username: 'qa-user',
  password: 'qa-password',
  roles: ['USER']
});

function response(status, body = '') {
  return {
    status() { return status; },
    async text() { return body; }
  };
}

function fakeApi(responses) {
  const calls = [];
  return {
    calls,
    async post(path, options) {
      calls.push({ path, options });
      const next = responses.shift();
      if (!next) throw new Error('No fake response remains');
      return next;
    }
  };
}

test('retries a startup-transient 401 before provisioning succeeds', async () => {
  const api = fakeApi([
    response(401, 'admin account is still initializing'),
    response(201)
  ]);
  const delays = [];

  await provisionRoleAccount(api, ACCOUNT, {
    maxAttempts: 2,
    retryDelayMs: 25,
    sleep: async delay => delays.push(delay)
  });

  assert.equal(api.calls.length, 2);
  assert.deepEqual(delays, [25]);
  assert.equal(api.calls[0].path, '/api/admin/users');
  assert.deepEqual(api.calls[0].options.data, {
    username: ACCOUNT.username,
    password: ACCOUNT.password,
    displayName: 'QA USER',
    email: 'qa-user@example.invalid',
    roles: ACCOUNT.roles
  });
});

test('fails immediately for a non-transient authorization response', async () => {
  const api = fakeApi([response(403, 'forbidden')]);
  const delays = [];

  await assert.rejects(
    () => provisionRoleAccount(api, ACCOUNT, {
      maxAttempts: 5,
      retryDelayMs: 25,
      sleep: async delay => delays.push(delay)
    }),
    /Unable to provision qa-user after 1 attempt: 403 forbidden/
  );

  assert.equal(api.calls.length, 1);
  assert.deepEqual(delays, []);
});

test('bounds repeated startup-transient failures and retains terminal evidence', async () => {
  const api = fakeApi([
    response(401, 'not ready 1'),
    response(503, 'not ready 2'),
    response(401, 'not ready 3')
  ]);
  const delays = [];

  await assert.rejects(
    () => provisionRoleAccount(api, ACCOUNT, {
      maxAttempts: 3,
      retryDelayMs: 10,
      sleep: async delay => delays.push(delay)
    }),
    /Unable to provision qa-user after 3 attempts: 401 not ready 3/
  );

  assert.equal(api.calls.length, 3);
  assert.deepEqual(delays, [10, 10]);
});

test('rejects invalid retry bounds before issuing a request', async () => {
  const api = fakeApi([response(201)]);

  await assert.rejects(
    () => provisionRoleAccount(api, ACCOUNT, { maxAttempts: 0 }),
    /maxAttempts must be a positive integer/
  );
  await assert.rejects(
    () => provisionRoleAccount(api, ACCOUNT, { retryDelayMs: -1 }),
    /retryDelayMs must be a non-negative number/
  );

  assert.equal(api.calls.length, 0);
});
