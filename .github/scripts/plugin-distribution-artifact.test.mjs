import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, copyFileSync, appendFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scripts = path.dirname(fileURLToPath(import.meta.url));
function fixture(t) {
  const root = mkdtempSync(path.join(tmpdir(), 'taxonomy-plugin-artifacts-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const target = path.join(root, 'taxonomy-app/target');
  mkdirSync(path.join(target, 'plugins'), { recursive: true });
  writeFileSync(path.join(target, 'taxonomy-app-1.0.jar'), 'host fixture');
  writeFileSync(path.join(target, 'plugins/taxonomy-mermaid-plugin-1.0.jar'), 'plugin fixture');
  execFileSync('git', ['init', '-q'], { cwd: root });
  execFileSync('git', ['add', '.'], { cwd: root });
  execFileSync('git', ['-c', 'user.name=Artifact Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture'], { cwd: root });
  const env = { ...process.env, GITHUB_SHA: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    GITHUB_ENV: path.join(root, 'github-env') };
  function run(script) { return spawnSync('bash', [path.join(scripts, script)], { cwd: root, env, encoding: 'utf8' }); }
  const staged = run('stage-ui-application.sh');
  assert.equal(staged.status, 0, staged.stdout + staged.stderr);
  return { root, run, manifest: path.join(root, 'target/ui-application/manifest.json'),
    plugin: path.join(root, 'target/ui-application/plugins/taxonomy-mermaid-plugin-1.0.jar') };
}
test('commit-bound distribution verifies both host and external plugin', t => {
  const f = fixture(t); const manifest = JSON.parse(readFileSync(f.manifest));
  assert.equal(manifest.plugins.length, 1); assert.match(manifest.plugins[0].sha256, /^[a-f0-9]{64}$/);
  const result = f.run('verify-ui-application.sh'); assert.equal(result.status, 0, result.stdout + result.stderr);
});
test('changed plugin bytes are rejected', t => {
  const f = fixture(t); appendFileSync(f.plugin, 'changed');
  assert.notEqual(f.run('verify-ui-application.sh').status, 0);
});
test('missing external plugin cannot silently become a full distribution', t => {
  const f = fixture(t); rmSync(f.plugin);
  assert.notEqual(f.run('verify-ui-application.sh').status, 0);
});
test('duplicated manifest names cannot conceal an unlisted external JAR', t => {
  const f = fixture(t); const manifest = JSON.parse(readFileSync(f.manifest));
  manifest.plugins.push(manifest.plugins[0]); writeFileSync(f.manifest, JSON.stringify(manifest));
  copyFileSync(f.plugin, path.join(path.dirname(f.plugin), 'taxonomy-unlisted-1.0.jar'));
  assert.notEqual(f.run('verify-ui-application.sh').status, 0);
});

test('unlisted symbolic link is rejected before copying plugin artifacts', t => {
  const f = fixture(t); symlinkSync(f.plugin, path.join(path.dirname(f.plugin), 'taxonomy-unlisted-1.0.jar'));
  assert.notEqual(f.run('verify-ui-application.sh').status, 0);
});
