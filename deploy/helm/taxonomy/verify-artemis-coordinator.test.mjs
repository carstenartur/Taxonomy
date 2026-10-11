import assert from 'node:assert/strict';
import { test } from 'node:test';
import { matchingConnectionCount } from './verify-artemis-coordinator.mjs';

const connection = (clientAddress, extra = {}) => ({connectionID:'opaque', clientAddress, sessionCount:1, ...extra});
const response = values => ({status:200, value:JSON.stringify(values)});

test('Coordinator broker observation counts only the exact source IP, including multiple sessions', () => {
  assert.equal(matchingConnectionCount(response([connection('/10.244.0.11:42000'), connection('/10.244.0.11:42001'),
    connection('/10.244.0.110:42000'), connection('/10.244.0.12:42000')]), '10.244.0.11'), 2);
});
test('Coordinator broker observation handles bracketed IPv6 and normalized source addresses', () => {
  assert.equal(matchingConnectionCount(response([connection('/[fd00:0:0::11]:42000'), connection('/[fd00::12]:42000')]), 'fd00::11'), 1);
});
test('Empty freshly observed broker connection set confirms disconnection', () => {
  assert.equal(matchingConnectionCount(response([]), '10.244.0.11'), 0);
});
for (const [name, value] of [
  ['failed management request', {status:500,value:'[]'}],
  ['malformed JSON', {status:200,value:'not-json'}],
  ['non-array value', response({})],
  ['missing client address', response([{}])],
  ['unparseable client address', response([connection('unproven-host:42000')])],
  ['substring-only address', response([connection('/10.244.0.1111:42000')])],
  ['missing sessions', response([connection('/10.244.0.12:42000',{sessionCount:undefined})])],
  ['negative sessions', response([connection('/10.244.0.12:42000',{sessionCount:-1})])],
]) test(`Coordinator broker observation fails closed: ${name}`, () => {
  assert.throws(() => matchingConnectionCount(value, '10.244.0.11'));
});
test('Malformed lost pod IP cannot authorize a broker disconnection', () => {
  assert.throws(() => matchingConnectionCount(response([]), '10.244.0.11.*'));
});
