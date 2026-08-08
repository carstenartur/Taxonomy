#!/usr/bin/env python3
"""Avoid prototype-sensitive deep equality for VM-created event detail objects."""

from pathlib import Path

path = Path('.github/scripts/apply-api-transport.py')
text = path.read_text(encoding='utf-8')
old = r'''  assert.deepEqual(events[0].detail, {
    status: 403,
    url: '/api/admin',
    requestId: 'client-request-id',
    code: 'HTTP_ERROR'
  });
'''
new = r'''  assert.equal(events[0].detail.status, 403);
  assert.equal(events[0].detail.url, '/api/admin');
  assert.equal(events[0].detail.requestId, 'client-request-id');
  assert.equal(events[0].detail.code, 'HTTP_ERROR');
'''
if text.count(old) != 1:
    raise SystemExit(f'Expected one cross-realm event assertion, found {text.count(old)}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Replaced cross-realm deep equality with field-level event assertions.')
