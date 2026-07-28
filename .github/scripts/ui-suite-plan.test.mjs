import test from 'node:test';
import assert from 'node:assert/strict';

import { groupScenarios, isolationGroupId } from './ui-suite-plan.mjs';

function scenario(suite, id, role) {
  return {
    suite,
    id,
    env: role ? { TAXONOMY_ROLE: role } : {}
  };
}

test('groups browser-only suites into one shared application', () => {
  const groups = groupScenarios([
    scenario('ui', 'desktop-chromium'),
    scenario('accessibility', 'mobile'),
    scenario('special-modes', 'text-spacing-and-offline')
  ]);

  assert.equal(groups.length, 1);
  assert.equal(groups[0].id, 'shared-browser-nonmutating');
  assert.deepEqual(groups[0].scenarios.map(item => item.id), [
    'desktop-chromium',
    'mobile',
    'text-spacing-and-offline'
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

test('rejects role-state scenarios without an explicit role', () => {
  assert.throws(
    () => isolationGroupId(scenario('role-state', 'broken-profile')),
    /has no TAXONOMY_ROLE/
  );
});
