import test from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const installer = path.join(root, '.github/scripts/install-document-rendering.sh');
const actionFile = path.join(root, '.github/actions/document-rendering/action.yml');
const mirrors = 'http://azure.archive.ubuntu.com/ubuntu/\tpriority:1\n'
  + 'https://archive.ubuntu.com/ubuntu/\tpriority:2\n';

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-document-packages-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const bin = path.join(directory, 'bin');
  const cache = path.join(directory, 'cache');
  const mirrorFile = path.join(directory, 'apt-mirrors.txt');
  const log = path.join(directory, 'commands.log');
  await mkdir(bin);
  await writeFile(mirrorFile, mirrors);
  for (const [name, script] of Object.entries({
    sudo: 'exec "$@"',
    'apt-get': `
echo "apt-get $*" >> "$COMMAND_LOG"
cat "$TAXONOMY_APT_MIRROR_LIST" >> "$COMMAND_LOG"
[[ "\${APT_FAILURE:-0}" == 0 ]] || exit "$APT_FAILURE"
if [[ "$*" == *--download-only* && "\${PREINSTALLED:-false}" != true ]]; then
  for option in "$@"; do
    if [[ "$option" == Dir::Cache::archives=* ]]; then cache="\${option#Dir::Cache::archives=}"; fi
  done
  printf 'writer fixture' > "$cache/writer.deb"
  printf 'transitive fixture' > "$cache/dependency.deb"
fi`,
    soffice: 'echo "soffice $*" >> "$COMMAND_LOG"',
    pdftoppm: 'echo "pdftoppm $*" >> "$COMMAND_LOG"'
  })) {
    const file = path.join(bin, name);
    await writeFile(file, `#!/usr/bin/env bash\nset -euo pipefail\n${script}\n`);
    await chmod(file, 0o755);
  }
  return {
    directory, cache, mirrorFile,
    async run(mode, variant = 'writer', extraEnv = {}) {
      await writeFile(log, '');
      const result = spawnSync('bash', [installer, mode, variant], {
        cwd: directory, encoding: 'utf8', timeout: 10000,
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, COMMAND_LOG: log,
          GITHUB_ACTIONS: 'true', RUNNER_ENVIRONMENT: 'github-hosted',
          TAXONOMY_APT_MIRROR_LIST: mirrorFile, TAXONOMY_DOCUMENT_PACKAGES_DIR: cache,
          ...extraEnv }
      });
      return { ...result, commands: await readFile(log, 'utf8') };
    }
  };
}

test('document cache preparation uses the protected mirror and retains the complete package closure', async t => {
  const f = await fixture(t);
  const result = await f.run('--only-download');
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /https:\/\/archive\.ubuntu\.com\/ubuntu\/\tpriority:0/);
  assert.match(result.commands, /Acquire::Retries=4/);
  assert.match(result.commands, /Acquire::https::Timeout=30/);
  assert.match(result.commands, /--download-only.*libreoffice-writer poppler-utils/);
  assert.doesNotMatch(result.commands, /libreoffice-draw|soffice|pdftoppm/);
  const manifest = await readFile(path.join(f.cache, 'SHA256SUMS'), 'utf8');
  assert.match(manifest, /dependency\.deb/);
  assert.match(manifest, /writer\.deb/);
});

test('a warm document cache installs offline and does not update apt or alter mirrors', async t => {
  const f = await fixture(t);
  assert.equal((await f.run('--only-download', 'writer-draw')).status, 0);
  await writeFile(f.mirrorFile, mirrors);
  const result = await f.run('--offline-mode', 'writer-draw');
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /install --no-download/);
  assert.match(result.commands, /libreoffice-writer poppler-utils libreoffice-draw/);
  assert.match(result.commands, /dependency\.deb.*writer\.deb/);
  assert.match(result.commands, /soffice --version/);
  assert.match(result.commands, /pdftoppm -v/);
  assert.doesNotMatch(result.commands, / update|--download-only/);
  assert.equal(await readFile(f.mirrorFile, 'utf8'), mirrors);
});

test('document installation fails before apt on absent, corrupt, extra or symlinked cache files', async t => {
  for (const damage of ['missing', 'corrupt', 'extra', 'symlink']) {
    const f = await fixture(t);
    if (damage !== 'missing') assert.equal((await f.run('--only-download')).status, 0);
    if (damage === 'corrupt') await writeFile(path.join(f.cache, 'writer.deb'), 'changed');
    if (damage === 'extra') await writeFile(path.join(f.cache, 'extra.deb'), 'unverified');
    if (damage === 'symlink') {
      await rm(path.join(f.cache, 'writer.deb'));
      await symlink('dependency.deb', path.join(f.cache, 'writer.deb'));
    }
    const result = await f.run('--offline-mode');
    assert.notEqual(result.status, 0, damage);
    assert.equal(result.commands, '', damage);
  }
});

test('preinstalled document tools need no archives but are still verified offline', async t => {
  const f = await fixture(t);
  assert.equal((await f.run('--only-download', 'writer', { PREINSTALLED: 'true' })).status, 0);
  assert.equal(await readFile(path.join(f.cache, 'SHA256SUMS'), 'utf8'), '');
  const result = await f.run('--offline-mode');
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /install --no-download.*libreoffice-writer poppler-utils/);
  assert.doesNotMatch(result.commands, / update|--download-only/);
});

test('document package failures retain their status and invalid requests perform no work', async t => {
  const f = await fixture(t);
  for (const status of [42, 124]) {
    const result = await f.run('--only-download', 'writer', { APT_FAILURE: String(status) });
    assert.equal(result.status, status, result.stderr);
    assert.doesNotMatch(result.commands, /soffice|pdftoppm/);
  }
  for (const args of [['--unknown', 'writer'], ['--only-download', 'unknown']]) {
    const result = await f.run(...args);
    assert.equal(result.status, 2, result.stderr);
    assert.equal(result.commands, '');
  }
});

test('all document consumers use the shared preparation before expensive verification', async () => {
  for (const [workflow, consumer, draw] of [
    ['scenario-acceptance.yml', 'Run sourced example scenario', true],
    ['document-template-report-e2e.yml', 'Build and install the packaged application reactor', false],
    ['documentation-screenshots.yml', 'Generate deterministic documentation screenshots', false],
    ['deploy-release.yml', 'Build Java release tooling', false]
  ]) {
    const source = await readFile(path.join(root, '.github/workflows', workflow), 'utf8');
    const preparation = source.indexOf('uses: ./.github/actions/document-rendering');
    assert.ok(preparation > 0 && preparation < source.indexOf(consumer), workflow);
    assert.doesNotMatch(source, /sudo apt-get/, workflow);
    assert.equal(source.includes('variant: writer-draw'), draw, workflow);
    if (source.includes('paths:') && source.includes('pull_request:')) {
      assert.ok(source.includes("'.github/actions/document-rendering/**'"), workflow);
      assert.ok(source.includes("'.github/scripts/apt-packages.sh'"), workflow);
      assert.ok(source.includes("'.github/scripts/install-document-rendering.sh'"), workflow);
    }
  }
});

test('document action rejects cache drift and bounds download and offline installation independently', async () => {
  const action = await readFile(actionFile, 'utf8');
  assert.doesNotMatch(action, /restore-keys:|continue-on-error:/);
  assert.match(action, /hashFiles\([^\n]*apt-packages\.sh[^\n]*install-document-rendering\.sh/);
  assert.match(action, /actions\/cache\/restore@/);
  assert.match(action, /actions\/cache\/save@/);
  assert.equal((action.match(/if: steps\.packages\.outputs\.cache-hit != 'true'/g) ?? []).length, 2);
  assert.equal((action.match(/timeout --foreground 470s/g) ?? []).length, 2);
  const phaseNames = ['Identify document package cache', 'Restore document system packages (exact runner image)',
    'Check package cache runner compatibility', 'Download document packages',
    'Install document packages offline', 'Save document system packages (exact runner image)'];
  const positions = phaseNames.map(name => action.indexOf(`- name: ${name}\n`));
  assert.ok(positions.every((position, index) => position >= 0 &&
    (index === 0 || position > positions[index - 1])));
  const check = action.split('- name: Check package cache runner compatibility\n')[1].split('\n    - ')[0];
  for (const [key, hit, matched, expected] of [
    ['fixture-ubuntu24-20261023.1-X64', '', '', 0],
    ['fixture-ubuntu24-20261023.1-X64', 'true', 'fixture-ubuntu24-20261023.1-X64', 0],
    ['fixture-ubuntu24-20261023.1-X64', 'false', 'partial', 1],
    ['fixture-ubuntu24-20261023.1-X64', 'true', '', 1],
    ['fixture-ubuntu24-other-X64', 'true', 'fixture-ubuntu24-other-X64', 1]
  ]) {
    const result = spawnSync('bash', ['-ec', check.split('run: |')[1]], {
      encoding: 'utf8', env: { ...process.env, ImageOS: 'ubuntu24', ImageVersion: '20261023.1',
        RUNNER_ARCH: 'X64', PACKAGE_KEY: key, PACKAGE_CACHE_HIT: hit, PACKAGE_CACHE_MATCHED_KEY: matched }
    });
    assert.equal(result.status, expected, result.stderr);
  }
});

test('document package keys separate variants and runner revisions, rejecting unknown inputs', async t => {
  const action = await readFile(actionFile, 'utf8');
  const identify = action.split('- name: Identify document package cache\n')[1].split('\n    - ')[0];
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-document-key-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const output = path.join(directory, 'output');
  const keys = new Set();
  for (const variant of ['writer', 'writer-draw', 'unknown']) {
    for (const version of ['20261012.1', '20261023.1', '']) {
      await writeFile(output, '');
      const result = spawnSync('bash', ['-ec', identify.split('run: |')[1]], {
        encoding: 'utf8', env: { ...process.env, PACKAGE_KEY_PREFIX: 'fixture', VARIANT: variant,
          ImageOS: 'ubuntu24', ImageVersion: version, RUNNER_ARCH: 'X64', GITHUB_OUTPUT: output }
      });
      const key = (await readFile(output, 'utf8')).trim();
      if (variant === 'unknown' || !version) {
        assert.notEqual(result.status, 0);
        assert.equal(key, '');
      } else {
        assert.equal(result.status, 0, result.stderr);
        assert.equal(key, `key=fixture-${variant}-ubuntu24-${version}-X64`);
        keys.add(key);
      }
    }
  }
  assert.equal(keys.size, 4);
});

test('the hosted document cache stays outside source inventories, including apt private partial directories', async () => {
  const action = await readFile(actionFile, 'utf8');
  const location = '${{ runner.temp }}/taxonomy-document-system-packages';
  for (const name of ['Restore document system packages (exact runner image)',
    'Save document system packages (exact runner image)']) {
    const step = action.split(`- name: ${name}\n`)[1].split('\n    - ')[0];
    assert.ok(step.includes(`${location}/*.deb`));
    assert.ok(step.includes(`${location}/SHA256SUMS`));
  }
  for (const name of ['Download document packages', 'Install document packages offline']) {
    const step = action.split(`- name: ${name}\n`)[1].split('\n    - ')[0];
    assert.ok(step.includes(`TAXONOMY_DOCUMENT_PACKAGES_DIR: ${location}`));
  }
  assert.doesNotMatch(action, /\.cache\/document-system-packages/);
});

test('the standalone installer defaults to runner temp and never silently caches inside the checkout', async t => {
  const f = await fixture(t);
  const runnerTemp = path.join(f.directory, 'runner-temp');
  const env = { TAXONOMY_DOCUMENT_PACKAGES_DIR: '', RUNNER_TEMP: runnerTemp };
  assert.equal((await f.run('--only-download', 'writer', env)).status, 0);
  const result = await f.run('--offline-mode', 'writer', env);
  assert.equal(result.status, 0, result.stderr);
  assert.ok(result.commands.includes(`Dir::Cache::archives=${runnerTemp}/taxonomy-document-system-packages`));
  assert.doesNotMatch(result.commands, / update|--download-only/);
  const noLocation = await f.run('--only-download', 'writer', { TAXONOMY_DOCUMENT_PACKAGES_DIR: '', RUNNER_TEMP: '' });
  assert.notEqual(noLocation.status, 0);
  assert.equal(noLocation.commands, '');
});
