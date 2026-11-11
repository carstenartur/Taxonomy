import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const script = fileURLToPath(new URL('../../deploy/container/apt/ubuntu-archive.sh', import.meta.url));
const primary = 'https://archive.ubuntu.com/ubuntu/';
const secondary = 'https://mirrors.kernel.org/ubuntu/';

async function select(t, reachable, signedSuite = 'noble') {
  const dir = await mkdtemp(path.join(tmpdir(), 'docker-ubuntu-archive-'));
  t.after(() => rm(dir, {recursive:true,force:true}));
  const bin = path.join(dir, 'bin');
  await mkdir(bin);
  await writeFile(path.join(bin,'apt-get'), `#!/usr/bin/env bash
set -eu
for arg; do
  case "$arg" in
    Dir::Etc::sourcelist=*) source_file=$(cut -d= -f2- <<< "$arg");;
    Dir::State::lists=*) lists=$(cut -d= -f2- <<< "$arg");;
  esac
done
uri=$(sed -n 's/^URIs: //p' "$source_file")
printf '%s\\n' "$uri" >> "$PROBE_LOG"
grep -Fxq 'Signed-By: /test/ubuntu-keyring.gpg' "$source_file"
grep -Fxq 'Targets: InRelease' "$source_file"
[[ "$uri" == "$REACHABLE" ]] || exit 100
printf 'Origin: Ubuntu\\nSuite: %s\\n' "$SIGNED_SUITE" > "$lists/probe_InRelease"
`, {mode:0o755});
  const probeLog = path.join(dir, 'probes');
  await writeFile(probeLog, '');
  const result = spawnSync('bash', ['-c','source "$1"; select_ubuntu_archive "$2" noble /test/ubuntu-keyring.gpg "$3" "$4"',
    'test',script,dir,primary,secondary], {encoding:'utf8', env:{...process.env,PATH:bin+':'+process.env.PATH,
      REACHABLE:reachable,PROBE_LOG:probeLog,SIGNED_SUITE:signedSuite}});
  return {code:result.status, selected:result.stdout.trim(), probes:(await readFile(probeLog,'utf8')).trim().split('\n')};
}

test('Docker uses a verified Canonical suite without retrying an unavailable secondary for each index', async t => {
  const result = await select(t, primary);
  assert.deepEqual(result, {code:0,selected:primary,probes:[primary]});
});
test('Docker falls back once when Canonical cannot serve the signed suite', async t => {
  const result = await select(t, secondary);
  assert.deepEqual(result, {code:0,selected:secondary,probes:[primary,secondary]});
});
test('Docker fails closed when neither source can serve the suite', async t => {
  const result = await select(t, '');
  assert.notEqual(result.code,0); assert.equal(result.selected,'');
  assert.deepEqual(result.probes,[primary,secondary]);
});
test('an authenticated release for a different suite cannot select the mirror', async t => {
  const result = await select(t, secondary, 'jammy');
  assert.notEqual(result.code,0); assert.equal(result.selected,'');
  assert.deepEqual(result.probes,[primary,secondary]);
});

test('Docker preserves package suites, signing options, unrelated origins and comments', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'docker-ubuntu-lines-'));
  t.after(() => rm(dir, {recursive:true, force:true}));
  await mkdir(path.join(dir,'sources.list.d'));
  const legacy = 'deb [signed-by=/key arch=amd64] http://archive.ubuntu.com/ubuntu/ noble-updates main universe\n';
  const deb822 = 'Types: deb\nURIs: http://security.ubuntu.com/ubuntu/\nSuites: noble-security\nComponents: main\nSigned-By: /key\n';
  const ports = '# https://archive.ubuntu.com/ubuntu/ retained comment\ndeb http://ports.ubuntu.com/ubuntu-ports noble main\n';
  const legacyPath=path.join(dir,'sources.list'), deb822Path=path.join(dir,'sources.list.d','security.sources');
  await writeFile(legacyPath,legacy+ports); await writeFile(deb822Path,deb822);
  const configure = () => spawnSync('bash',['-c',`source "$1"
select_ubuntu_archive() { echo "$2" >> "$PROBE_LOG"; printf %s "$5"; }
configure_ubuntu_archives "$2" noble /key "$3"`,'test',script,dir,secondary],
    {encoding:'utf8',env:{...process.env,PROBE_LOG:path.join(dir,'probes')}});
  assert.equal(configure().status,0);
  assert.equal(await readFile(legacyPath,'utf8'),legacy.replace('http://archive.ubuntu.com/ubuntu/',secondary)+ports);
  assert.equal(await readFile(deb822Path,'utf8'),deb822.replace('http://security.ubuntu.com/ubuntu/',secondary));
  assert.equal(await readFile(path.join(dir,'probes'),'utf8'),'noble\nnoble-security\n');
  await writeFile(legacyPath,ports); await writeFile(deb822Path,'');
  assert.equal(configure().status,0);
  assert.equal(await readFile(path.join(dir,'probes'),'utf8'),'noble\nnoble-security\n');
});
