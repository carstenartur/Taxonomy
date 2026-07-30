import { closeSync, openSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { groupScenarios } from './ui-suite-plan.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const matrix = JSON.parse(await readFile(
  path.join(repoRoot, '.github', 'ui-acceptance-matrix.json'), 'utf8'));
const requestedSuite = process.env.TAXONOMY_UI_SUITE || 'all';
const requestedProfile = process.env.TAXONOMY_UI_PROFILE_FILTER || '';
const adminPassword = process.env.TAXONOMY_ADMIN_PASSWORD || 'ui-primary-admin-password';
const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const outputRoot = path.resolve(
  repoRoot, process.env.TAXONOMY_UI_OUTPUT_ROOT || 'target/ui-verification');

const uiProfiles = [
  { id: 'desktop-chromium', browser: 'chromium', width: 1440, height: 1000, mode: 'full' },
  { id: 'desktop-firefox', browser: 'firefox', width: 1440, height: 1000, mode: 'full' },
  { id: 'tablet-chromium', browser: 'chromium', width: 1024, height: 768, mode: 'responsive' },
  { id: 'mobile-chromium', browser: 'chromium', width: 390, height: 844, mode: 'responsive' }
];
const accessibilityProfiles = [
  { id: 'desktop', width: 1440, height: 1000 },
  { id: 'tablet', width: 1024, height: 768 },
  { id: 'mobile', width: 390, height: 844 }
];

function selected(suite, id) {
  return (requestedSuite === 'all' || requestedSuite === suite)
    && (!requestedProfile || requestedProfile === id);
}

async function findApplicationJar() {
  const target = path.join(repoRoot, 'taxonomy-app', 'target');
  const entries = await readdir(target);
  const candidates = entries
    .filter(name => /^taxonomy-app-.*\.jar$/.test(name) && !name.startsWith('original-'))
    .sort();
  if (candidates.length !== 1) {
    throw new Error(
      `Expected one executable Taxonomy JAR in ${target}, found: ${candidates.join(', ') || 'none'}`);
  }
  return path.join(target, candidates[0]);
}

function runProcess(executable, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { stdio: 'inherit', ...options });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(
        `${executable} ${args.join(' ')} failed with ${signal || `exit ${code}`}`));
    });
  });
}

async function waitForApplication(application, logPath) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    if (application.exitCode !== null) {
      throw new Error(
        `Taxonomy application exited early with code ${application.exitCode}; see ${logPath}`);
    }
    try {
      const response = await fetch(`${baseUrl}/login`, { redirect: 'manual' });
      if (response.status >= 200 && response.status < 500) return;
    } catch {
      // The process is still starting.
    }
    await new Promise(resolve => setTimeout(resolve, 2_000));
  }
  throw new Error(
    `Taxonomy application did not become ready within 180 seconds; see ${logPath}`);
}

async function stopApplication(application) {
  if (!application || application.exitCode !== null) return;
  application.kill('SIGTERM');
  await Promise.race([
    new Promise(resolve => application.once('exit', resolve)),
    new Promise(resolve => setTimeout(resolve, 10_000))
  ]);
  if (application.exitCode === null) application.kill('SIGKILL');
}

function applicationEnvironment() {
  return {
    ...process.env,
    TAXONOMY_ADMIN_PASSWORD: adminPassword,
    TAXONOMY_REQUIRE_PASSWORD_CHANGE: 'false',
    TAXONOMY_EMBEDDING_ENABLED: 'false',
    TAXONOMY_INIT_ASYNC: 'true',
    TAXONOMY_THYMELEAF_CACHE: 'false',
    LLM_MOCK: 'true'
  };
}

function scenarioEnvironment(scenario) {
  return {
    ...process.env,
    TAXONOMY_BASE_URL: baseUrl,
    TAXONOMY_UI_USERNAME: 'admin',
    TAXONOMY_UI_PASSWORD: adminPassword,
    TAXONOMY_A11Y_USERNAME: 'admin',
    TAXONOMY_A11Y_PASSWORD: adminPassword,
    TAXONOMY_UI_ADMIN_USERNAME: 'admin',
    TAXONOMY_UI_ADMIN_PASSWORD: adminPassword,
    ...scenario.env
  };
}

function safeFileName(value) {
  return value.replace(/[^a-zA-Z0-9._-]+/g, '-');
}

async function runScenario(application, scenario, applicationLog, groupTiming) {
  if (application.exitCode !== null) {
    throw new Error(
      `Taxonomy application exited before ${scenario.suite}/${scenario.id}; see ${applicationLog}`);
  }

  const outputDir = path.join(outputRoot, scenario.suite, scenario.id);
  await mkdir(outputDir, { recursive: true });
  await writeFile(
    path.join(outputDir, 'application-log.txt'),
    `${path.relative(outputDir, applicationLog)}\n`,
    'utf8');

  console.log(`\n=== Maven-owned UI scenario: ${scenario.suite}/${scenario.id} ===`);
  const started = performance.now();
  const timing = {
    suite: scenario.suite,
    id: scenario.id,
    startedAt: new Date().toISOString(),
    outcome: 'running'
  };
  groupTiming.scenarios.push(timing);
  try {
    await runProcess(process.execPath, [path.join(scriptDir, scenario.script)], {
      cwd: repoRoot,
      env: scenarioEnvironment(scenario)
    });
    timing.outcome = 'passed';
  } catch (error) {
    timing.outcome = 'failed';
    timing.error = error?.message || String(error);
    throw error;
  } finally {
    timing.durationMs = Math.round(performance.now() - started);
  }
}

async function runGroup(group, jar, report) {
  const applicationDirectory = path.join(outputRoot, '_applications');
  await mkdir(applicationDirectory, { recursive: true });
  const logPath = path.join(applicationDirectory, `${safeFileName(group.id)}.log`);
  const logHandle = openSync(logPath, 'a');
  const groupStarted = performance.now();
  const groupTiming = {
    id: group.id,
    applicationLog: path.relative(outputRoot, logPath),
    scenarioCount: group.scenarios.length,
    startedAt: new Date().toISOString(),
    outcome: 'running',
    scenarios: []
  };
  report.groups.push(groupTiming);

  const application = spawn('java', ['-jar', jar], {
    cwd: repoRoot,
    env: applicationEnvironment(),
    stdio: ['ignore', logHandle, logHandle]
  });

  try {
    const startupStarted = performance.now();
    await waitForApplication(application, logPath);
    groupTiming.startupMs = Math.round(performance.now() - startupStarted);
    console.log(
      `\n=== UI application group ${group.id}: ${group.scenarios.length} scenario(s), `
      + `startup ${groupTiming.startupMs} ms ===`);
    for (const scenario of group.scenarios) {
      await runScenario(application, scenario, logPath, groupTiming);
    }
    groupTiming.outcome = 'passed';
  } catch (error) {
    groupTiming.outcome = 'failed';
    groupTiming.error = error?.message || String(error);
    throw error;
  } finally {
    await stopApplication(application);
    closeSync(logHandle);
    groupTiming.durationMs = Math.round(performance.now() - groupStarted);
  }
}

const scenarios = [];
for (const profile of uiProfiles) {
  if (selected('ui', profile.id)) scenarios.push({
    suite: 'ui', id: profile.id, script: 'ui-acceptance.mjs', env: {
      TAXONOMY_BROWSER: profile.browser,
      TAXONOMY_UI_PROFILE: profile.id,
      TAXONOMY_VIEWPORT_WIDTH: String(profile.width),
      TAXONOMY_VIEWPORT_HEIGHT: String(profile.height),
      TAXONOMY_UI_MODE: profile.mode,
      TAXONOMY_UI_REPORT: path.join(outputRoot, 'ui', profile.id, 'report.json'),
      TAXONOMY_UI_SCREENSHOT: path.join(outputRoot, 'ui', profile.id, 'screenshot.png')
    }
  });
}
for (const profile of accessibilityProfiles) {
  if (selected('accessibility', profile.id)) scenarios.push({
    suite: 'accessibility', id: profile.id, script: 'accessibility-audit.mjs', env: {
      TAXONOMY_A11Y_PROFILE: profile.id,
      TAXONOMY_VIEWPORT_WIDTH: String(profile.width),
      TAXONOMY_VIEWPORT_HEIGHT: String(profile.height),
      TAXONOMY_A11Y_REPORT: path.join(outputRoot, 'accessibility', profile.id, 'report.json'),
      TAXONOMY_A11Y_TEXT_REPORT: path.join(outputRoot, 'accessibility', profile.id, 'report.txt'),
      TAXONOMY_A11Y_BASELINE: path.join(repoRoot, '.github', 'accessibility-baseline.json')
    }
  });
}
for (const profile of matrix.primaryWorkflowProfiles) {
  if (selected('primary', profile.id)) scenarios.push({
    suite: 'primary', id: profile.id, script: 'ui-primary-workflow-acceptance.mjs', env: {
      TAXONOMY_ROLE: profile.role,
      TAXONOMY_BROWSER: profile.browser,
      TAXONOMY_UI_OUTPUT_DIR: path.join(outputRoot, 'primary', profile.id)
    }
  });
}
for (const profile of matrix.profiles.filter(item => !item.textSpacing)) {
  if (selected('role-state', profile.id)) scenarios.push({
    suite: 'role-state', id: profile.id, script: 'ui-role-state-acceptance.mjs', env: {
      TAXONOMY_UI_PROFILE: profile.id,
      TAXONOMY_BROWSER: profile.browser,
      TAXONOMY_ROLE: profile.role,
      TAXONOMY_VIEWPORT_WIDTH: String(profile.width),
      TAXONOMY_VIEWPORT_HEIGHT: String(profile.height),
      TAXONOMY_ZOOM: String(profile.zoom),
      TAXONOMY_FORCED_COLORS: String(profile.forcedColors),
      TAXONOMY_UI_OUTPUT_DIR: path.join(outputRoot, 'role-state', profile.id),
      TAXONOMY_UI_MATRIX: path.join(repoRoot, '.github', 'ui-acceptance-matrix.json')
    }
  });
}
if (selected('special-modes', 'text-spacing-and-offline')) scenarios.push({
  suite: 'special-modes',
  id: 'text-spacing-and-offline',
  script: 'ui-special-modes-acceptance.mjs',
  env: {
    TAXONOMY_UI_OUTPUT_DIR: path.join(
      outputRoot, 'special-modes', 'text-spacing-and-offline')
  }
});

if (scenarios.length === 0) {
  throw new Error(
    `No UI scenario matches suite=${requestedSuite}, profile=${requestedProfile || '<all>'}`);
}

const groups = groupScenarios(scenarios);
const report = {
  schemaVersion: 1,
  requestedSuite,
  requestedProfile: requestedProfile || null,
  startedAt: new Date().toISOString(),
  scenarioCount: scenarios.length,
  applicationStartCount: groups.length,
  groups: []
};
const overallStarted = performance.now();
let failure = null;
try {
  const jar = await findApplicationJar();
  for (const group of groups) await runGroup(group, jar, report);
} catch (error) {
  failure = error;
  report.outcome = 'failed';
  report.error = error?.message || String(error);
} finally {
  report.finishedAt = new Date().toISOString();
  report.durationMs = Math.round(performance.now() - overallStarted);
  if (!report.outcome) report.outcome = 'passed';
  await mkdir(outputRoot, { recursive: true });
  await writeFile(
    path.join(outputRoot, 'timings.json'),
    `${JSON.stringify(report, null, 2)}\n`,
    'utf8');
}

if (failure) throw failure;
console.log(
  `\nMaven-owned UI verification passed: ${scenarios.length} scenario(s) `
  + `in ${groups.length} application start(s), ${report.durationMs} ms total.`);
