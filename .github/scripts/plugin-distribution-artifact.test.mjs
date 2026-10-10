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
  mkdirSync(path.join(target, 'features'), { recursive: true });
  for (const id of ['templates', 'architecture', 'reporting', 'analysis', 'portfolio', 'interop'])
    writeFileSync(path.join(target, `features/taxonomy-${id}-1.0.jar`), `feature ${id}`);
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

test('full distribution includes all startup features with independent digests', t => {
  const f = fixture(t), manifest = JSON.parse(readFileSync(f.manifest));
  assert.deepEqual(manifest.features?.map(x => x.id).sort(), ['analysis', 'architecture', 'interop', 'portfolio', 'reporting', 'templates']);
  for (const feature of manifest.features) assert.match(feature.sha256, /^[a-f0-9]{64}$/);
});
test('substituted startup feature bytes are rejected', t => {
  const f = fixture(t);
  appendFileSync(path.join(f.root, 'target/ui-application/features/taxonomy-analysis-1.0.jar'), 'substituted');
  assert.notEqual(f.run('verify-ui-application.sh').status, 0);
});
test('missing startup feature is not a complete default installation', t => {
  const f = fixture(t);
  rmSync(path.join(f.root, 'target/ui-application/features/taxonomy-reporting-1.0.jar'), { force: true });
  assert.notEqual(f.run('verify-ui-application.sh').status, 0);
});

test('unlisted installed code cannot survive verification of a new distribution', t => {
  const f=fixture(t);
  writeFileSync(path.join(f.root,'taxonomy-app/target/plugins/taxonomy-stale-0.1.jar'),'old executable code');
  assert.notEqual(f.run('verify-ui-application.sh').status,0);
});
test('installed feature symlinks cannot redirect verified bytes', t => {
  const f=fixture(t), directory=path.join(f.root,'taxonomy-app/target/features');
  rmSync(path.join(directory,'taxonomy-analysis-1.0.jar'));
  symlinkSync(path.join(f.root,'taxonomy-app/target/taxonomy-app-1.0.jar'),path.join(directory,'taxonomy-analysis-1.0.jar'));
  assert.notEqual(f.run('verify-ui-application.sh').status,0);
});
