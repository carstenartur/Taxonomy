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

async function select(t, outcomes) {
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
grep -Fxq 'Signed-By: /test/ubuntu-keyring.gpg' "$source_file"
grep -Fxq 'Targets: InRelease' "$source_file"
[[ -d "$lists/partial" ]] || exit 101
if compgen -G "$lists/*_InRelease" >/dev/null; then exit 101; fi
uri=$(sed -n 's/^URIs: //p' "$source_file")
attempt=$(wc -l < "$PROBE_LOG")
printf '%s\\t%s\\n' "$uri" "$lists" >> "$PROBE_LOG"
read -ra outcomes <<< "$PROBE_OUTCOMES"
outcome=\${outcomes[attempt]:-unavailable}
origin=Ubuntu suite=noble
case "$outcome" in
  unavailable) exit 100 ;;
  missing-release) exit 0 ;;
  wrong-origin) origin=Debian ;;
  wrong-suite) suite=jammy ;;
  verified|failed-apt|duplicate-release) ;;
  *) exit 102 ;;
esac
printf 'Origin: %s\\nSuite: %s\\n' "$origin" "$suite" > "$lists/probe_InRelease"
case "$outcome" in
  failed-apt) exit 100 ;;
  duplicate-release) cp "$lists/probe_InRelease" "$lists/another_InRelease" ;;
esac
`, {mode:0o755});
  const probeLog = path.join(dir, 'probes');
  await writeFile(probeLog, '');
  const result = spawnSync('bash', ['-c','source "$1"; shopt -s nullglob; select_ubuntu_archive "$2" noble /test/ubuntu-keyring.gpg "$3" "$4"',
    'test',script,dir,primary,secondary], {encoding:'utf8', timeout:10_000, env:{...process.env,PATH:bin+':'+process.env.PATH,
      PROBE_OUTCOMES:outcomes.join(' '),PROBE_LOG:probeLog}});
  assert.ifError(result.error);
  const records = (await readFile(probeLog,'utf8')).split('\n').filter(Boolean).map(record => record.split('\t'));
  const probes = records.map(([uri]) => uri);
  const lists = records.map(([, list]) => list);
  assert.equal(new Set(lists).size, lists.length, 'each probe must use a distinct list directory');
  const retryNotices = result.stderr.match(/^Retrying Ubuntu release probes for noble$/gm)?.length ?? 0;
  return {code:result.status, selected:result.stdout.trim(), probes, retryNotices};
}

test('Docker uses a verified Canonical suite without retrying an unavailable secondary for each index', async t => {
  const result = await select(t, ['verified']);
  assert.deepEqual(result, {code:0,selected:primary,probes:[primary],retryNotices:0});
});
test('Docker falls back once when Canonical cannot serve the signed suite', async t => {
  const result = await select(t, ['unavailable', 'verified']);
  assert.deepEqual(result, {code:0,selected:secondary,probes:[primary,secondary],retryNotices:0});
});
test('Docker recovers when Canonical serves a verified release in the second round', async t => {
  const result = await select(t, ['unavailable', 'unavailable', 'verified']);
  assert.deepEqual(result, {code:0,selected:primary,probes:[primary,secondary,primary],retryNotices:1});
});
test('Docker recovers when the fallback serves a verified release in the second round', async t => {
  const result = await select(t, ['unavailable', 'unavailable', 'unavailable', 'verified']);
  assert.deepEqual(result, {code:0,selected:secondary,probes:[primary,secondary,primary,secondary],retryNotices:1});
});
test('Docker fails closed when neither source can serve the suite', async t => {
  const result = await select(t, ['unavailable']);
  assert.deepEqual(result, {code:1,selected:'',probes:[primary,secondary,primary,secondary],retryNotices:1});
});
for (const [description, outcome] of [
  ['a release left by a failed APT update', 'failed-apt'],
  ['an authenticated release for a different suite', 'wrong-suite'],
  ['an authenticated release from a different origin', 'wrong-origin'],
  ['a successful APT update without an InRelease', 'missing-release'],
  ['an APT update leaving multiple InRelease files', 'duplicate-release'],
]) {
  test(`${description} cannot select the mirror`, async t => {
    const result = await select(t, ['unavailable', outcome, 'unavailable', outcome]);
    assert.deepEqual(result, {code:1,selected:'',probes:[primary,secondary,primary,secondary],retryNotices:1});
  });
}

test('Docker chooses a signed origin for every active suite in legacy and deb822 sources', async t => {
  const dir = await mkdtemp(path.join(tmpdir(), 'docker-ubuntu-suites-'));
  t.after(() => rm(dir, {recursive:true, force:true}));
  const parts = path.join(dir,'sources.list.d');
  await mkdir(parts);
  const legacy = 'deb [signed-by=/key arch=amd64] http://archive.ubuntu.com/ubuntu/ jammy-updates main universe'
    + ' # http://archive.ubuntu.com/ubuntu/ comment\n';
  const ports = '# http://archive.ubuntu.com/ubuntu/\ndeb http://ports.ubuntu.com/ubuntu-ports jammy main\n';
  const archive = 'Types: deb\nURIs: https://third.example/ubuntu/\n http://archive.ubuntu.com/ubuntu/\n'
    + 'Suites: noble noble-updates\n noble-backports\nComponents: main universe\nSigned-By: /key\n';
  const security = 'Types: deb\nURIs: http://security.ubuntu.com/ubuntu/\nSuites: noble-security\nComponents: main\nSigned-By: /key\n';
  const disabled = 'Enabled: no\nTypes: deb\nURIs: https://archive.ubuntu.com/ubuntu/\nSuites: unused\nComponents: main\n';
  const legacyPath = path.join(dir,'sources.list');
  const deb822Path = path.join(parts,'ubuntu.sources');
  await writeFile(legacyPath,legacy+ports);
  await writeFile(deb822Path,[archive,security,disabled].join('\n'));
  const probes=path.join(dir,'probes');
  const configure = unavailable => spawnSync('bash',['-c',`source "$1"
select_ubuntu_archive() {
  echo "$2" >> "$PROBE_LOG"
  [[ "$2" != "$UNAVAILABLE" ]] || return 1
  case "$2" in *-updates) printf %s "$5" ;; *) printf %s "$4" ;; esac
}
configure_ubuntu_archives "$2" /key "$3"`,'test',script,dir,secondary],
    {encoding:'utf8',env:{...process.env,PROBE_LOG:probes,UNAVAILABLE:unavailable}});

  assert.notEqual(configure('noble-backports').status,0,'an unavailable configured pocket must fail source selection');
  await writeFile(probes,'');
  const success = configure('');
  assert.equal(success.status,0,success.stderr);
  assert.deepEqual((await readFile(probes,'utf8')).trim().split('\n').sort(),
    ['jammy-updates','noble','noble-backports','noble-security','noble-updates']);
  const archiveList = path.join(dir,'archive.mirrors');
  const securityList = path.join(dir,'security.mirrors');
  assert.deepEqual((await readFile(archiveList,'utf8')).trim().split('\n').sort(),
    [primary+'\tsuite:noble', primary+'\tsuite:noble-backports',
      secondary+'\tsuite:jammy-updates', secondary+'\tsuite:noble-updates'].sort());
  assert.equal(await readFile(securityList,'utf8'), 'https://security.ubuntu.com/ubuntu/\tsuite:noble-security\n');
  assert.equal(await readFile(legacyPath,'utf8'),legacy.replace('http://archive.ubuntu.com/ubuntu/', 'mirror+file:'+archiveList)+ports);
  assert.equal(await readFile(deb822Path,'utf8'),
    [archive.replace('http://archive.ubuntu.com/ubuntu/', 'mirror+file:'+archiveList),
      security.replace('http://security.ubuntu.com/ubuntu/', 'mirror+file:'+securityList), disabled].join('\n'));
});

test('Docker leaves sources unchanged without an active Canonical Ubuntu archive', async t => {
  const dir=await mkdtemp(path.join(tmpdir(),'docker-ubuntu-inactive-'));
  t.after(() => rm(dir,{recursive:true,force:true}));
  await mkdir(path.join(dir,'sources.list.d'));
  const legacy=path.join(dir,'sources.list');
  const deb822=path.join(dir,'sources.list.d','inactive.sources');
  const ports='# https://archive.ubuntu.com/ubuntu/ retained comment\ndeb http://ports.ubuntu.com/ubuntu-ports jammy main\n';
  const disabled='Enabled: no\nTypes: deb\nURIs: https://archive.ubuntu.com/ubuntu/\nSuites: unused\nComponents: main\n';
  await writeFile(legacy,ports);
  await writeFile(deb822,disabled);
  const result=spawnSync('bash',['-c',`source "$1"
select_ubuntu_archive() { echo "unexpected probe" >&2; return 1; }
configure_ubuntu_archives "$2" /key "$3"`,'test',script,dir,secondary],{encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);
  assert.equal(await readFile(legacy,'utf8'),ports);
  assert.equal(await readFile(deb822,'utf8'),disabled);
});
