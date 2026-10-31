import test from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { groupScenarios } from './ui-suite-plan.mjs';

import {
  scenarioKey,
  shardScenarioKeys,
  validateShardPlan
} from './ui-shard-plan.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const matrix = JSON.parse(await readFile(
  path.join(repoRoot, '.github', 'ui-acceptance-matrix.json'), 'utf8'));
const plan = JSON.parse(await readFile(
  path.join(repoRoot, '.github', 'ui-shards.json'), 'utf8'));

const expected = [
  'ui/desktop-chromium',
  'ui/desktop-firefox',
  'ui/tablet-chromium',
  'ui/mobile-chromium',
  'ui/login-context-chromium',
  'accessibility/desktop',
  'accessibility/tablet',
  'accessibility/mobile',
  ...matrix.primaryWorkflowProfiles.map(profile => scenarioKey('primary', profile.id)),
  ...matrix.profiles
    .filter(profile => !profile.textSpacing)
    .map(profile => scenarioKey('role-state', profile.id)),
  'special-modes/text-spacing-and-offline'
];

test('authoritative shard plan covers all nineteen browser scenarios exactly once', () => {
  assert.doesNotThrow(() => validateShardPlan(plan, expected));
  assert.equal(expected.length, 19);
  assert.equal(plan.shards.length, 6);
});

test('JUnit discovery includes the context login profile in the existing browser shard', () => {
  const result = spawnSync(process.execPath,
    [path.join(scriptDir, 'run-ui-suite.mjs'), '--list-scenarios'], {
      cwd: repoRoot, encoding: 'utf8',
      env: { ...process.env, TAXONOMY_UI_SUITE: 'all', TAXONOMY_UI_PROFILE_FILTER: '',
        TAXONOMY_UI_SHARD: 'mobile-admin-and-browser',
        TAXONOMY_UI_SOURCE_SHA: 'a'.repeat(40), TAXONOMY_UI_ARTIFACT_SHA256: 'b'.repeat(64) }
    });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  const discovered = JSON.parse(result.stdout.trim().replace(/^JUNIT_UI_SCENARIOS /, ''));
  assert.deepEqual(discovered.sort(), [
    'role-state/mobile-admin-webkit', 'ui/desktop-chromium', 'ui/desktop-firefox',
    'ui/tablet-chromium', 'ui/mobile-chromium', 'ui/login-context-chromium'
  ].sort());
});

test('only the shard containing a WebKit scenario requests WebKit packages and binaries', () => {
  assert.deepEqual(plan.shards.filter(shard => shard.requiresWebKit).map(shard => shard.id),
    ['mobile-admin-and-browser']);
  for (const flag of [undefined, 'false', false]) {
    const invalid = structuredClone(plan);
    invalid.shards[0].requiresWebKit = flag;
    assert.throws(() => validateShardPlan(invalid, expected), /requiresWebKit/);
  }
  const unnecessary = structuredClone(plan);
  unnecessary.shards[1].requiresWebKit = true;
  assert.throws(() => validateShardPlan(unnecessary, expected), /requiresWebKit/);
});

test('CI runs successive user browser profiles against one application', () => {
  const profiles = ['desktop-user-chromium', 'landscape-user-firefox'];
  const shard = plan.shards.find(candidate =>
    candidate.scenarios.includes(scenarioKey('role-state', profiles[0])));
  for (const profile of profiles) {
    assert.ok(shard.scenarios.includes(scenarioKey('role-state', profile)),
      `Shared-draft regression requires ${profile} in the same CI shard`);
  }
  const scenarios = matrix.profiles
    .filter(profile => shard.scenarios.includes(scenarioKey('role-state', profile.id)))
    .map(profile => ({ suite: 'role-state', id: profile.id,
      env: { TAXONOMY_ROLE: profile.role } }));
  const group = groupScenarios(scenarios).find(candidate => candidate.id === 'role-state-user');
  assert.deepEqual(group.scenarios.map(scenario => scenario.id), profiles);
});

test('shard selection is repository-defined and rejects unknown ids', () => {
  assert.deepEqual(
    [...shardScenarioKeys(plan, 'architect-and-a11y-desktop')],
    ['role-state/desktop-architect-firefox', 'accessibility/desktop']);
  assert.throws(() => shardScenarioKeys(plan, 'not-a-shard'), /Unknown UI shard/);
});

test('duplicate and missing assignments fail closed', () => {
  const duplicate = structuredClone(plan);
  duplicate.shards[1].scenarios.push(duplicate.shards[0].scenarios[0]);
  assert.throws(() => validateShardPlan(duplicate, expected), /assigned to both/);

  const missing = structuredClone(plan);
  missing.shards[0].scenarios.shift();
  assert.throws(() => validateShardPlan(missing, expected), /missing scenarios/);
});

// Contract tests need the pinned Node packages, not native browser packages.
// Each shard runs on a different machine and must install its own runtime.
function maskXmlComments(xml) {
  // Mask rather than delete: joining fragments can invent XML delimiters.
  // This only prepares the repository-owned POM for assertions, not untrusted HTML.
  return xml.replace(/<!--[\s\S]*?-->/g, comment => comment.replace(/[^\r\n]/g, ' '));
}

const verificationPom = maskXmlComments(await readFile(
  path.join(repoRoot, '.github', 'ui-verification-pom.xml'), 'utf8'));

function verificationProfile(id) {
  const profiles = [...verificationPom.matchAll(/<profile>([\s\S]*?)<\/profile>/g)]
    .map(match => match[1])
    .filter(profile => profile.match(/<id>\s*([^<]+?)\s*<\/id>/)?.[1] === id);
  assert.equal(profiles.length, 1, `Exactly one ${id} verification profile is required`);
  return profiles[0];
}

function verificationExecutions(id) {
  return [...verificationProfile(id).matchAll(/<execution>([\s\S]*?)<\/execution>/g)]
    .map(match => {
      const text = tag => match[1].match(new RegExp(`<${tag}>\\s*([^<]*?)\\s*</${tag}>`))?.[1];
      assert.doesNotMatch(match[1], /<skip>\s*true\s*<\/skip>/);
      return { id: text('id'), phase: text('phase'), goal: text('goal'), arguments: text('arguments') };
    });
}

test('contract verification runs the complete contracts without native browser setup', () => {
  const executions = verificationExecutions('contracts');
  assert.ok(executions.some(step => step.goal === 'install-node-and-npm'));
  assert.ok(executions.some(step => step.arguments === 'ci --ignore-scripts --no-audit --no-fund'));
  for (const command of ['run verify:ui-contracts']) {
    assert.ok(executions.some(step => step.phase === 'test' && step.arguments === command), command);
  }
  assert.doesNotMatch(verificationProfile('contracts'), /install:playwright|<phase>\s*pre-integration-test\s*<\/phase>/,
    'A slow native package mirror must not block already-passing Node contracts');
});

test('browser shards still install their pinned runtime before executing acceptance', () => {
  const executions = verificationExecutions('shard');
  const setup = executions.find(step => step.arguments === 'run install:playwright:github');
  const shardProfile = verificationProfile('shard');
  assert.ok(setup, 'The browser-runtime installation remains mandatory on shard runners');
  assert.match(shardProfile, /UiScenarioIT\.java/);
  assert.match(shardProfile, /<goal>integration-test<\/goal>/);
  assert.equal(setup.phase, 'pre-integration-test');
  assert.ok(executions.some(step => step.goal === 'install-node-and-npm'));
});

test('CI prepares packages once, restores an exact image cache and bounds offline setup separately', async t => {
  const workflow = await readFile(path.join(repoRoot, '.github', 'workflows', 'ci-cd.yml'), 'utf8');
  const consumers = workflow.split('\n  ui-shards:')[1].split('\n  interoperability-products:')[0];
  assert.doesNotMatch(workflow, /^  ui-system-packages:/m,
    'Separate hosted jobs can receive different runner image revisions');
  assert.match(consumers, /needs: \[application, ui-plan, ui-contracts\]/);
  const step = name => {
    const parts = consumers.split(`- name: ${name}\n`);
    assert.equal(parts.length, 2, `Exactly one ${name} step is required`);
    return parts[1].split('\n      - ')[0];
  };
  const identify = step('Identify the exact runner image');
  const cache = step('Restore UI system packages (exact runner image)');
  const check = step('Check package cache runner compatibility');
  const download = step('UI system packages / Paketinstallation (download only)');
  const offline = step('UI system packages / Paketinstallation (offline)');
  const save = step('Save UI system packages (exact runner image)');
  for (const phase of [identify, cache, check, download, offline, save]) {
    assert.match(phase, /if: matrix\.requiresWebKit == true/,
      'The other five shards must not restore, prepare or install system packages');
    assert.doesNotMatch(phase, /continue-on-error:/);
  }
  assert.match(identify, /hashFiles\('\.github\/scripts\/install-playwright-github\.sh', '\.github\/scripts\/apt-packages\.sh', '\.github\/package-lock\.json'\)/);
  assert.match(identify, /\$\{ImageOS\}-\$\{ImageVersion\}/);
  assert.match(cache, /actions\/cache\/restore@/);
  assert.match(save, /actions\/cache\/save@/);
  for (const phase of [cache, save]) {
    assert.match(phase, /key: \$\{\{ steps\.cache-key\.outputs\.key \}\}/);
    assert.match(phase, /\.cache\/playwright-system-packages\/SHA256SUMS/);
  }
  assert.doesNotMatch(consumers, /restore-keys:|needs\.ui-system-packages/);
  assert.match(check, /PACKAGE_KEY: \$\{\{ steps\.cache-key\.outputs\.key \}\}/);
  assert.match(check, /PACKAGE_CACHE_HIT: \$\{\{ steps\.packages\.outputs\.cache-hit \}\}/);
  assert.match(check, /PACKAGE_CACHE_MATCHED_KEY: \$\{\{ steps\.packages\.outputs\.cache-matched-key \}\}/);
  for (const phase of [download, save]) {
    assert.match(phase, /if: matrix\.requiresWebKit == true && steps\.packages\.outputs\.cache-hit != 'true'/);
  }
  for (const phase of [download, offline]) {
    assert.match(phase, /timeout-minutes: 8/);
    assert.match(phase, /timeout --foreground 470s/);
  }
  assert.match(download, /--only-download/);
  assert.match(offline, /--offline-mode --system-packages-only/);
  assert.doesNotMatch(offline, /--only-download|apt-get update/);
  assert.match(consumers, /TAXONOMY_PLAYWRIGHT_BROWSERS_ONLY: 'true'/);
  assert.match(consumers, /TAXONOMY_PLAYWRIGHT_SKIP_WEBKIT: \$\{\{ !matrix\.requiresWebKit \}\}/);
  assert.match(consumers, /-webkit-\$\{\{ matrix\.requiresWebKit \}\}/);
  assert.match(workflow, /\{include:\[\.shards\[\] \| \{shard:\.id, requiresWebKit\}\]\}/);
  const phases = ['Prepare pinned Node and Playwright CLI through Maven',
    'Identify the exact runner image', 'Restore UI system packages (exact runner image)',
    'Check package cache runner compatibility', 'UI system packages / Paketinstallation (download only)',
    'UI system packages / Paketinstallation (offline)', 'Save UI system packages (exact runner image)',
    'Run Maven-owned authoritative UI shard'];
  const positions = phases.map(name => consumers.indexOf(`- name: ${name}\n`));
  assert.ok(positions.every((position, index) => position >= 0 &&
    (index === 0 || position > positions[index - 1])), 'Prepare, verify and consume on the same runner');

  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-runner-cache-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const output = path.join(directory, 'output');
  const compatibility = check.split('run: |')[1];
  for (const imageVersion of ['20261012.334.1', '20261023.334.3']) {
    await writeFile(output, '');
    const env = { ...process.env, ImageOS: 'ubuntu24', ImageVersion: imageVersion,
      PACKAGE_KEY_PREFIX: 'Linux-taxonomy-ui-playwright-packages-v1-hash', GITHUB_OUTPUT: output };
    const keyResult = spawnSync('bash', ['-ec', identify.split('run: |')[1]], { env, encoding: 'utf8' });
    assert.equal(keyResult.status, 0, keyResult.stderr);
    const key = (await readFile(output, 'utf8')).trim().replace(/^key=/, '');
    assert.equal(key, `${env.PACKAGE_KEY_PREFIX}-ubuntu24-${imageVersion}`);
    // A cold miss prepares locally; an exact hit skips downloads. A partial hit is never consumed.
    for (const [hit, matchedKey, status] of [
      ['', '', 0], ['false', '', 0], ['true', key, 0],
      ['false', `${key}-partial`, 1], ['true', `${key}-partial`, 1], ['true', '', 1]
    ]) {
      const result = spawnSync('bash', ['-ec', compatibility], {
        encoding: 'utf8', env: { ...env, PACKAGE_KEY: key, PACKAGE_CACHE_HIT: hit, PACKAGE_CACHE_MATCHED_KEY: matchedKey }
      });
      assert.equal(result.status, status, result.stderr);
    }
    const otherImage = imageVersion === '20261012.334.1' ? '20261023.334.3' : '20261012.334.1';
    const mismatch = spawnSync('bash', ['-ec', compatibility], {
      encoding: 'utf8', env: { ...env, PACKAGE_KEY: `${env.PACKAGE_KEY_PREFIX}-ubuntu24-${otherImage}`,
        PACKAGE_CACHE_HIT: 'true', PACKAGE_CACHE_MATCHED_KEY: key }
    });
    assert.equal(mismatch.status, 1, mismatch.stderr);
    const unknownImage = spawnSync('bash', ['-ec', identify.split('run: |')[1]], {
      encoding: 'utf8', env: { ...env, ImageVersion: '' }
    });
    assert.notEqual(unknownImage.status, 0);
  }
});

async function installerFixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-playwright-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const bin = path.join(directory, 'bin');
  const cli = path.join(directory, 'node_modules', '.bin');
  await mkdir(bin, { recursive: true });
  await mkdir(cli, { recursive: true });
  const log = path.join(directory, 'commands.log');
  const cache = path.join(directory, 'packages');
  // Keep hosted-runner configuration inside the fixture, just like apt and browsers.
  const mirrorFile = path.join(directory, 'apt-mirrors.txt');
  await writeFile(mirrorFile, 'https://archive.ubuntu.com/ubuntu/\tpriority:2\n');
  const executable = async (file, content) => {
    await writeFile(file, `#!/usr/bin/env bash\nset -euo pipefail\n${content}`);
    await chmod(file, 0o755);
  };
  await executable(path.join(cli, 'playwright'), `
echo "playwright $*" >> "$COMMAND_LOG"
if [[ "$*" == "install-deps --dry-run webkit" ]]; then
  if [[ "\${EMPTY_PACKAGES:-false}" == true ]]; then
    echo "All system dependencies are installed."
    exit 0
  fi
  printf 'Missing system dependencies (2):\\n  libwebkit-test\\n  libtransitive-test\\n'
  exit 1
fi
`);
  await executable(path.join(bin, 'sudo'), 'exec "$@"\n');
  await executable(path.join(bin, 'apt-get'), `
echo "apt-get $*" >> "$COMMAND_LOG"
[[ "\${APT_FAILURE:-false}" != true ]] || exit 42
if [[ "$*" == *--download-only* ]]; then
  printf 'fixture deb' > "$TAXONOMY_PLAYWRIGHT_PACKAGES_DIR/libwebkit-test.deb"
fi
`);
  return {
    cache,
    async run(options, extraEnv = {}) {
      await writeFile(log, '');
      const result = spawnSync('bash',
        [path.join(repoRoot, '.github', 'scripts', 'install-playwright-github.sh'), ...options], {
          cwd: directory, encoding: 'utf8',
          env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, COMMAND_LOG: log,
            GITHUB_ACTIONS: 'true', RUNNER_ENVIRONMENT: 'github-hosted',
            TAXONOMY_APT_MIRROR_LIST: mirrorFile,
            TAXONOMY_PLAYWRIGHT_PACKAGES_DIR: cache,
            TAXONOMY_PLAYWRIGHT_SKIP_WEBKIT: 'false',
            TAXONOMY_PLAYWRIGHT_BROWSERS_ONLY: 'false', ...extraEnv }
        });
      return { ...result, commands: await readFile(log, 'utf8') };
    }
  };
}

test('package preparation downloads the dependency closure but installs no packages or browsers', async t => {
  const fixture = await installerFixture(t);
  const result = await fixture.run(['--only-download']);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /apt-get .* update/);
  assert.match(result.commands, /playwright install-deps --dry-run webkit/);
  assert.match(result.commands, /apt-get .*--download-only.*libwebkit-test libtransitive-test/);
  assert.doesNotMatch(result.commands, /playwright install /);
  assert.match(await readFile(path.join(fixture.cache, 'SHA256SUMS'), 'utf8'), /libwebkit-test\.deb/);
});

test('offline setup installs cached archives without update, downloads or browser setup', async t => {
  const fixture = await installerFixture(t);
  assert.equal((await fixture.run(['--only-download'])).status, 0);
  const result = await fixture.run(['--offline-mode', '--system-packages-only']);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /apt-get .*install --no-download.*libwebkit-test\.deb/);
  assert.ok(result.commands.includes(`Dir::Cache::archives=${fixture.cache}`),
    '--no-download must point apt at the restored archive directory');
  assert.doesNotMatch(result.commands, /update|--download-only|^playwright /m);
});

test('offline installation fails closed for missing or corrupted archives without mirror fallback', async t => {
  const fixture = await installerFixture(t);
  assert.notEqual((await fixture.run(['--offline-mode'])).status, 0);
  assert.equal((await fixture.run(['--only-download'])).status, 0);
  await writeFile(path.join(fixture.cache, 'libwebkit-test.deb'), 'corrupt');
  const result = await fixture.run(['--offline-mode']);
  assert.notEqual(result.status, 0);
  assert.equal(result.commands, '');
  assert.match(result.stderr, /UI system packages \/ Paketinstallation/);
});

test('offline installation rejects unlisted archives before invoking apt', async t => {
  const fixture = await installerFixture(t);
  assert.equal((await fixture.run(['--only-download'])).status, 0);
  await writeFile(path.join(fixture.cache, 'unlisted-extra.deb'), 'unverified bytes');
  const result = await fixture.run(['--offline-mode', '--system-packages-only']);
  assert.notEqual(result.status, 0);
  assert.equal(result.commands, '');
  assert.match(result.stderr, /Unexpected or unlisted system package archives/);
});

test('offline installation accepts checksum-listed Debian archives with epoch and tilde versions', async t => {
  const fixture = await installerFixture(t);
  assert.equal((await fixture.run(['--only-download'])).status, 0);
  const filename = 'zz-test_1%3a2~ubuntu1_amd64.deb';
  await writeFile(path.join(fixture.cache, filename), 'fixture deb versioned');
  const digest = spawnSync('sha256sum', [filename], {
    cwd: fixture.cache, encoding: 'utf8'
  });
  assert.equal(digest.status, 0, digest.stderr);
  const manifest = path.join(fixture.cache, 'SHA256SUMS');
  await writeFile(manifest, (await readFile(manifest, 'utf8')) + digest.stdout);
  const result = await fixture.run(['--offline-mode', '--system-packages-only']);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.commands, /apt-get .*install --no-download/);
});

test('an image with all dependencies installed can prepare and restore an empty package set', async t => {
  const fixture = await installerFixture(t);
  assert.equal((await fixture.run(['--only-download'], { EMPTY_PACKAGES: 'true' })).status, 0);
  const result = await fixture.run(['--offline-mode', '--system-packages-only']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.commands, '');
});

test('package errors retain their exit status and never continue to browser installation', async t => {
  const fixture = await installerFixture(t);
  const result = await fixture.run(['--only-download'], { APT_FAILURE: 'true' });
  assert.equal(result.status, 42);
  assert.match(result.stderr, /UI system packages \/ Paketinstallation/);
  assert.doesNotMatch(result.commands, /playwright/);
});

test('non-WebKit shards skip apt and WebKit while Maven still installs pinned Chromium and Firefox', async t => {
  const fixture = await installerFixture(t);
  assert.equal((await fixture.run(['--skip-webkit', '--offline-mode', '--system-packages-only'])).commands, '');
  const result = await fixture.run(['--skip-webkit']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.commands, 'playwright install chromium firefox\n');
  const webkit = await fixture.run(['--browsers-only']);
  assert.equal(webkit.status, 0, webkit.stderr);
  assert.equal(webkit.commands, 'playwright install chromium firefox webkit\n');
  const standalone = await fixture.run([]);
  assert.equal(standalone.status, 0, standalone.stderr);
  assert.equal(standalone.commands,
    'playwright install-deps webkit\nplaywright install chromium firefox webkit\n');
});

test('unknown and incompatible installer flags fail before invoking apt or browsers', async t => {
  const fixture = await installerFixture(t);
  for (const flags of [['--unknown'], ['--only-download', '--offline-mode'],
    ['--only-download', '--skip-webkit'], ['--offline-mode', '--browsers-only'],
    ['--chromium-only', '--only-download'], ['--chromium-only', '--system-packages-only'],
    ['--chromium-only', '--offline-mode']]) {
    const result = await fixture.run(flags);
    assert.equal(result.status, 2);
    assert.equal(result.commands, '');
  }
});

test('Chromium-only document preparation uses the pinned browser without apt or unrelated browsers', async t => {
  const fixture = await installerFixture(t);
  const result = await fixture.run(['--chromium-only']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.commands, 'playwright install chromium\n');
  for (const name of ['document-template-report-e2e.yml', 'documentation-screenshots.yml']) {
    const workflow = await readFile(path.join(repoRoot, '.github/workflows', name), 'utf8');
    assert.match(workflow, /-Dtaxonomy\.playwright\.install\.arguments="run install:playwright:github -- --chromium-only"/);
    assert.doesNotMatch(workflow, /install --with-deps/);
    if (workflow.includes('pull_request:')) {
      assert.ok(workflow.includes("'.github/scripts/install-playwright-github.sh'"));
    }
  }
});

test('native analysis/navigation component tests run in the installed-browser UI shard', async () => {
  const runner = await readFile(
    path.join(repoRoot, '.github', 'scripts', 'run-ui-suite.mjs'), 'utf8');
  const specialModeShard = plan.shards.find(shard =>
    shard.scenarios.includes('special-modes/text-spacing-and-offline'));

  assert.ok(specialModeShard);
  assert.match(runner, /analysis-navigation\.browser\.test\.mjs/);
  assert.match(runner, /analysis-ux\.browser\.test\.mjs/);
  assert.match(runner, /BOOTSTRAP_CSS:\s*bootstrapCss/);
  assert.match(runner, /webjars\/bootstrap\/5\.3\.8\/dist\/css\/bootstrap\.min\.css/);
  assert.ok(specialModeShard.scenarios.includes('special-modes/text-spacing-and-offline'));
});

test('final evidence gate still validates all shard evidence without installing browsers', () => {
  const executions = verificationExecutions('evidence-gate');
  assert.ok(executions.some(step => step.phase === 'test' && step.arguments === 'run verify:ui-shards'));
  assert.doesNotMatch(verificationProfile('evidence-gate'), /install:playwright/);
  assert.match(verificationPom, /<maven\.build\.cache\.enabled>\s*false\s*<\/maven\.build\.cache\.enabled>/);
});


test('XML comment masking preserves boundaries and source positions', () => {
  const xml = '<project>\r\n<!-- <profile>ignored</profile>\ncomment -->\r\n<profile>real</profile></project>';
  const masked = maskXmlComments(xml);
  assert.equal(masked.length, xml.length);
  assert.equal(masked.indexOf('<profile>real'), xml.indexOf('<profile>real'));
  assert.equal(masked.split('\n').length, xml.split('\n').length);
  assert.doesNotMatch(masked, /ignored|comment/);
  assert.match(masked, /<profile>real<\/profile>/);
});

for (const [xml, token] of [
  ['<pro<!-- first -->file>not a profile</pro<!-- second -->file>', '<profile>'],
  ['<!<!-- nested fragments -->-->', '<!--'],
  ['--<!-- nested fragments -->>', '-->']
]) test(`XML comment masking cannot join fragments into ${token}`, () => {
  assert.equal(maskXmlComments(xml).includes(token), false);
});

test('XML comment masking leaves non-comment source unchanged and handles adjacent comments', () => {
  const source = '<profile><id>contracts</id></profile>';
  assert.equal(maskXmlComments(source), source);
  assert.equal(maskXmlComments('<!-- first --><!-- second -->').trim(), '');
});

for (const gate of ['verify:ui', 'verify:ui-contracts']) test(`${gate} preserves localization/state and search-readiness through JUnit ownership`, async () => {
  const {scripts} = JSON.parse(await readFile(path.join(repoRoot,'.github','package.json'),'utf8'));
  assert.match(scripts['verify:ui-contracts'], /-Dtest=JavascriptContractTest/);
  assert.equal(scripts['verify:ui'], '../mvnw -B -f ui-verification-pom.xml verify -Pshard');
  const inventory=await readFile(path.join(repoRoot,'.mvn','javascript-junit-sources.txt'),'utf8');
  for(const file of ['ui-localization-regression.test.mjs','ux-state-recovery.cjs','search-mode-readiness.test.mjs','backup-export.test.mjs','quality-dashboard-api.test.mjs','integration-startup.test.mjs','diagram-keyboard-controls.cjs','architecture-workbench-print.test.mjs']) assert.ok(inventory.includes(file),file);
  for(const command of Object.values(scripts).filter(value => value.includes('JavascriptContractTest'))) assert.doesNotMatch(command,/node --test/);
});
