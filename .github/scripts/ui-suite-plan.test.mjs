import test from 'node:test';
import assert from 'node:assert/strict';

import { applicationConfiguration, groupScenarios, isolationGroupId } from './ui-suite-plan.mjs';

function scenario(suite, id, role) {
  return {
    suite,
    id,
    env: role ? { TAXONOMY_ROLE: role } : {}
  };
}

test('groups read-only browser suites into one shared application', () => {
  const groups = groupScenarios([
    scenario('ui', 'desktop-chromium'),
    scenario('accessibility', 'mobile')
  ]);

  assert.equal(groups.length, 1);
  assert.equal(groups[0].id, 'shared-browser-nonmutating');
  assert.deepEqual(groups[0].scenarios.map(item => item.id), [
    'desktop-chromium',
    'mobile'
  ]);
});

test('context-path browser scenarios cannot share the root application', () => {
  const groups = groupScenarios([
    scenario('ui', 'desktop-chromium'),
    { ...scenario('ui', 'login-context-chromium'), contextPath: '/taxonomy' },
    scenario('accessibility', 'mobile')
  ]);

  assert.equal(groups.length, 2);
  assert.deepEqual(groups.map(group => ({
    contextPath: group.contextPath,
    scenarios: group.scenarios.map(item => item.id)
  })), [
    { contextPath: '', scenarios: ['desktop-chromium', 'mobile'] },
    { contextPath: '/taxonomy', scenarios: ['login-context-chromium'] }
  ]);
});

test('context paths preserve role-state isolation and group equal contexts together', () => {
  const groups = groupScenarios([
    scenario('role-state', 'root-user', 'USER'),
    { ...scenario('role-state', 'context-user', 'USER'), contextPath: '/taxonomy' },
    { ...scenario('role-state', 'context-user-repeat', 'USER'), contextPath: '/taxonomy' },
    { ...scenario('role-state', 'context-architect', 'ARCHITECT'), contextPath: '/taxonomy' }
  ]);

  assert.deepEqual(groups.map(group => group.scenarios.map(item => item.id)), [
    ['root-user'], ['context-user', 'context-user-repeat'], ['context-architect']
  ]);
});

test('context paths reject ambiguous URL components before application startup', () => {
  for (const contextPath of ['taxonomy', '//example.test', '/taxonomy?lang=en', '/taxonomy#login', '/..']) {
    assert.throws(() => groupScenarios([
      { ...scenario('ui', 'invalid-context'), contextPath }
    ]), /Invalid UI servlet context path/);
  }
});

test('application configuration binds the real servlet context to the browser base URL', () => {
  assert.deepEqual(applicationConfiguration(
    { contextPath: '/taxonomy' }, 'http://127.0.0.1:8080/'), {
    baseUrl: 'http://127.0.0.1:8080/taxonomy',
    environment: { SERVER_SERVLET_CONTEXT_PATH: '/taxonomy' }
  });
  assert.deepEqual(applicationConfiguration(
    { contextPath: '' }, 'http://127.0.0.1:8080'), {
    baseUrl: 'http://127.0.0.1:8080',
    environment: {}
  });
});

test('ordinary application groups preserve a caller-configured servlet context', () => {
  const configuration = applicationConfiguration(
    { contextPath: '' }, 'http://127.0.0.1:8080/custom');
  assert.equal(configuration.baseUrl, 'http://127.0.0.1:8080/custom');
  assert.deepEqual({ SERVER_SERVLET_CONTEXT_PATH: '/custom', ...configuration.environment },
    { SERVER_SERVLET_CONTEXT_PATH: '/custom' });
});

test('explicit context application configuration replaces the caller path without double-prefixing', () => {
  for (const baseUrl of ['http://127.0.0.1:8080/custom', 'http://127.0.0.1:8080/taxonomy']) {
    assert.deepEqual(applicationConfiguration({ contextPath: '/taxonomy' }, baseUrl), {
      baseUrl: 'http://127.0.0.1:8080/taxonomy',
      environment: { SERVER_SERVLET_CONTEXT_PATH: '/taxonomy' }
    });
  }
});

test('isolates special modes because partial analysis mutates application state', () => {
  const groups = groupScenarios([
    scenario('ui', 'desktop-chromium'),
    scenario('special-modes', 'text-spacing-and-offline')
  ]);

  assert.deepEqual(groups.map(group => group.id), [
    'shared-browser-nonmutating',
    'special-modes-text-spacing-and-offline'
  ]);
});

test('shares role-state applications only inside the same role', () => {
  const groups = groupScenarios([
    scenario('role-state', 'desktop-user-chromium', 'USER'),
    scenario('role-state', 'zoom-400-user-chromium', 'USER'),
    scenario('role-state', 'desktop-architect-firefox', 'ARCHITECT'),
    scenario('role-state', 'forced-colors-architect-chromium', 'ARCHITECT'),
    scenario('role-state', 'mobile-admin-webkit', 'ADMIN')
  ]);

  assert.deepEqual(groups.map(group => group.id), [
    'role-state-user',
    'role-state-architect',
    'role-state-admin'
  ]);
  assert.deepEqual(groups[0].scenarios.map(item => item.id), [
    'desktop-user-chromium',
    'zoom-400-user-chromium'
  ]);
});

test('keeps every primary mutation workflow isolated', () => {
  const groups = groupScenarios([
    scenario('primary', 'primary-user-chromium', 'USER'),
    scenario('primary', 'primary-architect-chromium', 'ARCHITECT'),
    scenario('primary', 'primary-admin-chromium', 'ADMIN')
  ]);

  assert.deepEqual(groups.map(group => group.id), [
    'primary-primary-user-chromium',
    'primary-primary-architect-chromium',
    'primary-primary-admin-chromium'
  ]);
});

test('maps nineteen scenarios to the eight existing starts plus one context application', () => {
  const scenarios = [
    scenario('ui', 'desktop-chromium'),
    scenario('ui', 'desktop-firefox'),
    scenario('ui', 'tablet-chromium'),
    scenario('ui', 'mobile-chromium'),
    { ...scenario('ui', 'login-context-chromium'), contextPath: '/taxonomy' },
    scenario('accessibility', 'desktop'),
    scenario('accessibility', 'tablet'),
    scenario('accessibility', 'mobile'),
    scenario('special-modes', 'text-spacing-and-offline'),
    scenario('primary', 'primary-user-chromium', 'USER'),
    scenario('primary', 'primary-architect-chromium', 'ARCHITECT'),
    scenario('primary', 'primary-admin-chromium', 'ADMIN'),
    scenario('role-state', 'desktop-user-chromium', 'USER'),
    scenario('role-state', 'desktop-architect-firefox', 'ARCHITECT'),
    scenario('role-state', 'mobile-admin-webkit', 'ADMIN'),
    scenario('role-state', 'landscape-user-firefox', 'USER'),
    scenario('role-state', 'zoom-200-user-chromium', 'USER'),
    scenario('role-state', 'zoom-400-user-chromium', 'USER'),
    scenario('role-state', 'forced-colors-architect-chromium', 'ARCHITECT')
  ];

  const groups = groupScenarios(scenarios);

  assert.equal(scenarios.length, 19);
  assert.equal(groups.length, 9);
  assert.deepEqual(groups.map(group => group.id), [
    'shared-browser-nonmutating',
    'shared-browser-nonmutating-context-taxonomy',
    'special-modes-text-spacing-and-offline',
    'primary-primary-user-chromium',
    'primary-primary-architect-chromium',
    'primary-primary-admin-chromium',
    'role-state-user',
    'role-state-architect',
    'role-state-admin'
  ]);
});

test('rejects role-state scenarios without an explicit role', () => {
  assert.throws(
    () => isolationGroupId(scenario('role-state', 'broken-profile')),
    /has no TAXONOMY_ROLE/
  );
});
