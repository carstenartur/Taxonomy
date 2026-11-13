import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {copyFileSync, mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {request} from 'node:https';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const origin = 'https://localhost';

export async function verifySecureSession(port, ca, password) {
  let cookie = '';
  function visit(method, pathname, form) {
    const body = form && new URLSearchParams(form).toString();
    const headers = {Host: 'localhost', Accept: 'text/html'};
    if (cookie) headers.Cookie = cookie;
    if (body) headers['Content-Type'] = 'application/x-www-form-urlencoded';
    return new Promise((resolve, reject) => {
      const req = request({hostname: '127.0.0.1', servername: 'localhost', port, ca, method,
        path: pathname, headers, timeout: 15000}, res => {
        let html = '';
        res.setEncoding('utf8');
        res.on('data', chunk => { html += chunk; });
        res.on('error', reject);
        res.on('end', () => resolve({status: res.statusCode, headers: res.headers, html}));
      });
      req.on('error', reject);
      req.on('timeout', () => req.destroy(new Error('TLS request timed out')));
      req.end(body);
    });
  }

  function readSession(response) {
    const entry = response.headers['set-cookie']?.find(value => value.startsWith('JSESSIONID='));
    assert.ok(entry, 'Expected a session cookie');
    assert.ok(/;\s*Secure(?:;|$)/i.test(entry), 'Session cookie must be Secure over the trusted proxy');
    assert.ok(/;\s*HttpOnly(?:;|$)/i.test(entry), 'Session cookie must be HttpOnly');
    return entry.split(';', 1)[0];
  }
  function redirect(response, pathname) {
    assert.equal(response.status, 302);
    assert.ok(response.headers.location, 'Missing redirect location');
    const target = new URL(response.headers.location, origin);
    assert.equal(target.origin, origin);
    assert.equal(target.pathname, pathname);
  }

  const protectedPage = await visit('GET', '/admin/sessions');
  redirect(protectedPage, '/login');
  cookie = readSession(protectedPage);
  assert.match(protectedPage.headers['strict-transport-security'] ?? '', /max-age=[1-9]\d*/);
  const login = await visit('GET', '/login');
  assert.equal(login.status, 200);
  const csrfInput = login.html.match(/<input\b[^>]*\bname="_csrf"[^>]*>/i)?.[0];
  const csrf = csrfInput?.match(/\bvalue="([^"]+)"/i)?.[1];
  assert.ok(csrf, 'Expected a CSRF token in the real login form');
  const success = await visit('POST', '/login', {username: 'admin', password, _csrf: csrf});
  redirect(success, '/admin/sessions');
  const authenticated = readSession(success);
  assert.ok(authenticated !== cookie, 'Login must rotate the saved-request session');
  console.log('Verified TLS certificate, HTTPS saved request, HSTS, Secure/HttpOnly cookies and login session rotation.');
}

async function verifyCompose(image) {
  assert.ok(image, 'Pass the already verified production image tag');
  const directory = mkdtempSync(path.join(tmpdir(), 'taxonomy-https-'));
  const project = 'taxonomy-https-' + randomUUID();
  const password = randomUUID();
  const compose = (...args) => execFileSync('docker', ['compose', '-p', project, '-f', 'compose.yml',
    '-f', 'verify.yml', ...args], {cwd: directory, encoding: 'utf8', env: {...process.env,
      DOMAIN: 'localhost', TAXONOMY_ADMIN_PASSWORD: password, VERIFIED_IMAGE: image}}).trim();
  try {
    copyFileSync('docker-compose.prod.yml', path.join(directory, 'compose.yml'));
    copyFileSync('Caddyfile', path.join(directory, 'Caddyfile'));
    writeFileSync(path.join(directory, '.env'), '', {mode: 0o600});
    // Reuse the Maven-built image. Only the proxy receives a loopback host port.
    writeFileSync(path.join(directory, 'verify.yml'), [
      'services:', '  taxonomy:', '    image: ${VERIFIED_IMAGE}', '  caddy:',
      '    ports: !override', '      - "127.0.0.1::443"', ''
    ].join('\n'));
    compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '420');
    const endpoint = compose('port', 'caddy', '443');
    assert.match(endpoint, /^127\.0\.0\.1:\d+$/);
    // Compose reports the proxy running before Caddy has finished creating its local CA.
    const certificate = '/data/caddy/pki/authorities/local/root.crt';
    const ca = compose('exec', '-T', 'caddy', 'sh', '-ec',
      'for n in $(seq 1 30); do if test -s "$1"; then cat "$1"; exit; fi; sleep 1; done; exit 1',
      'sh', certificate);
    await verifySecureSession(Number(endpoint.split(':')[1]), ca, password);
  } finally {
    try { compose('down', '--volumes', '--remove-orphans'); }
    finally { rmSync(directory, {recursive: true, force: true}); }
  }
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  await verifyCompose(process.argv[2]);
}
