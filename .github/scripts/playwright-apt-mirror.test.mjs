import test from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const installer = fileURLToPath(new URL('./install-playwright-github.sh', import.meta.url));
const mirrors = '# Hosted runner mirrors\n'
  + 'http://azure.archive.ubuntu.com/ubuntu/\tpriority:1\n'
  + 'https://archive.ubuntu.com/ubuntu/\tpriority:2\n';

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-apt-mirror-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const bin = path.join(directory, 'bin');
  const cli = path.join(directory, 'node_modules', '.bin');
  const packages = path.join(directory, 'packages');
  const mirrorFile = path.join(directory, 'apt-mirrors.txt');
  const log = path.join(directory, 'commands.log');
  for (const folder of [bin, cli, packages]) await mkdir(folder, { recursive: true });
  await writeFile(mirrorFile, mirrors);
  await writeFile(path.join(packages, 'SHA256SUMS'), '');
  const executable = async (name, content) => {
    await writeFile(name, '#!/usr/bin/env bash\nset -euo pipefail\n' + content);
    await chmod(name, 0o755);
  };
  // Never run a package manager or touch the real runner configuration in a test.
  await executable(path.join(bin, 'sudo'), 'exec "$@"\n');
  await executable(path.join(bin, 'apt-get'), `
echo "apt-get $*" >> "$COMMAND_LOG"
if [[ -f "$TAXONOMY_APT_MIRROR_LIST" ]]; then
  cat "$TAXONOMY_APT_MIRROR_LIST" >> "$COMMAND_LOG"
fi
[[ "\${APT_FAILURE:-false}" != true ]] || exit 42
`);
  await executable(path.join(cli, 'playwright'), `
echo "playwright $*" >> "$COMMAND_LOG"
if [[ "$*" == 'install-deps --dry-run webkit' ]]; then
  echo 'All system dependencies are installed.'
fi
`);
  return {
    mirrorFile,
    async run(options = ['--only-download'], extraEnv = {}) {
      await writeFile(log, '');
      const result = spawnSync('bash', [installer, ...options], {
        cwd: directory, encoding: 'utf8', timeout: 10000,
        env: {
          ...process.env, PATH: `${bin}:${process.env.PATH}`, COMMAND_LOG: log,
          GITHUB_ACTIONS: 'true', RUNNER_ENVIRONMENT: 'github-hosted',
          TAXONOMY_APT_MIRROR_LIST: mirrorFile,
          TAXONOMY_PLAYWRIGHT_PACKAGES_DIR: packages,
          TAXONOMY_PLAYWRIGHT_SKIP_WEBKIT: 'false',
          TAXONOMY_PLAYWRIGHT_BROWSERS_ONLY: 'false', ...extraEnv
        }
      });
      return { ...result, commands: await readFile(log, 'utf8') };
    }
  };
}

test('hosted package preparation prefers the official HTTPS archive before apt runs', async t => {
  const f = await fixture(t);
  const result = await f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /https:\/\/archive\.ubuntu\.com\/ubuntu\/\tpriority:0/);
  assert.equal(await readFile(f.mirrorFile, 'utf8'), mirrors.replace('priority:2', 'priority:0'));
  assert.doesNotMatch(result.commands, /playwright install /);
  assert.equal((await f.run()).status, 0);
  assert.equal(await readFile(f.mirrorFile, 'utf8'), mirrors.replace('priority:2', 'priority:0'));
});

test('local and self-hosted installations retain their mirror configuration', async t => {
  const f = await fixture(t);
  for (const env of [
    { GITHUB_ACTIONS: 'false' }, { RUNNER_ENVIRONMENT: 'self-hosted' },
    { RUNNER_ENVIRONMENT: '' }
  ]) {
    assert.equal((await f.run(['--only-download'], env)).status, 0);
    assert.equal(await readFile(f.mirrorFile, 'utf8'), mirrors);
  }
});

test('offline and browser-only modes never change mirrors or contact apt', async t => {
  const f = await fixture(t);
  for (const options of [
    ['--offline-mode', '--system-packages-only'], ['--browsers-only'], ['--skip-webkit']
  ]) {
    const result = await f.run(options);
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotMatch(result.commands, /apt-get/);
    assert.equal(await readFile(f.mirrorFile, 'utf8'), mirrors);
  }
});

test('standalone hosted WebKit installation uses the same mirror preference', async t => {
  const f = await fixture(t);
  const result = await f.run([]);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /playwright install-deps webkit/);
  assert.equal(await readFile(f.mirrorFile, 'utf8'), mirrors.replace('priority:2', 'priority:0'));
});

test('unknown mirror entries are preserved and a missing mirrorlist is supported', async t => {
  const f = await fixture(t);
  const custom = '# Keep custom sources\nhttps://mirror.example/ubuntu/\tpriority:2\n'
    + 'https://archive.ubuntu.com.example/ubuntu/\tpriority:2\n';
  await writeFile(f.mirrorFile, custom);
  assert.equal((await f.run()).status, 0);
  assert.equal(await readFile(f.mirrorFile, 'utf8'), custom);
  await rm(f.mirrorFile);
  assert.equal((await f.run()).status, 0);
});

test('package download failures still stop before browser installation', async t => {
  const f = await fixture(t);
  const result = await f.run(['--only-download'], { APT_FAILURE: 'true' });
  assert.equal(result.status, 42);
  assert.doesNotMatch(result.commands, /playwright/);
});
