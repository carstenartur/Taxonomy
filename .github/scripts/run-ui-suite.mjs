import { spawn } from 'node:child_process';
import { mkdir, readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const matrix = JSON.parse(await readFile(path.join(repoRoot, '.github', 'ui-acceptance-matrix.json'), 'utf8'));
const requestedSuite = process.env.TAXONOMY_UI_SUITE || 'all';
const requestedProfile = process.env.TAXONOMY_UI_PROFILE_FILTER || '';
const adminPassword = process.env.TAXONOMY_ADMIN_PASSWORD || 'ui-primary-admin-password';
const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const outputRoot = path.resolve(repoRoot, process.env.TAXONOMY_UI_OUTPUT_ROOT || 'target/ui-verification');

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
    throw new Error(`Expected one executable Taxonomy JAR in ${target}, found: ${candidates.join(', ') || 'none'}`);
  }
  return path.join(target, candidates[0]);
}

function runProcess(executable, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { stdio: 'inherit', ...options });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`${executable} ${args.join(' ')} failed with ${signal || `exit ${code}`}`));
    });
  });
}

async function waitForApplication(application, logPath) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    if (application.exitCode !== null) {
      throw new Error(`Taxonomy application exited early with code ${application.exitCode}; see ${logPath}`);
    }
    try {
      const response = await fetch(`${baseUrl}/login`, { redirect: 'manual' });
      if (response.status >= 200 && response.status < 500) return;
    } catch {
      // The process is still starting.
    }
    await new Promise(resolve => setTimeout(resolve, 2_000));
  }
  throw new Error(`Taxonomy application did not become ready within 180 seconds; see ${logPath}`);
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

async function runScenario({ suite, id, script, env = {} }) {
  const outputDir = path.join(outputRoot, suite, id);
  await mkdir(outputDir, { recursive: true });
  const logPath = path.join(outputDir, 'application.log');
  const jar = await findApplicationJar();
  const logHandle = await import('node:fs').then(({ openSync }) => openSync(logPath, 'a'));
  const applicationEnv = {
    ...process.env,
    TAXONOMY_ADMIN_PASSWORD: adminPassword,
    TAXONOMY_REQUIRE_PASSWORD_CHANGE: 'false',
    TAXONOMY_EMBEDDING_ENABLED: 'false',
    TAXONOMY_INIT_ASYNC: 'true',
    TAXONOMY_THYMELEAF_CACHE: 'false',
    LLM_MOCK: 'true'
  };
  const application = spawn('java', ['-jar', jar], {
    cwd: repoRoot,
    env: applicationEnv,
    stdio: ['ignore', logHandle, logHandle]
  });
  try {
    await waitForApplication(application, logPath);
    const childEnv = {
      ...process.env,
      TAXONOMY_BASE_URL: baseUrl,
      TAXONOMY_UI_USERNAME: 'admin',
      TAXONOMY_UI_PASSWORD: adminPassword,
      TAXONOMY_A11Y_USERNAME: 'admin',
      TAXONOMY_A11Y_PASSWORD: adminPassword,
      TAXONOMY_UI_ADMIN_USERNAME: 'admin',
      TAXONOMY_UI_ADMIN_PASSWORD: adminPassword,
      ...env
    };
    console.log(`\n=== Maven-owned UI scenario: ${suite}/${id} ===`);
    await runProcess(process.execPath, [path.join(scriptDir, script)], {
      cwd: repoRoot,
      env: childEnv
    });
  } finally {
    await stopApplication(application);
    await import('node:fs').then(({ closeSync }) => closeSync(logHandle));
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
  suite: 'special-modes', id: 'text-spacing-and-offline', script: 'ui-special-modes-acceptance.mjs', env: {
    TAXONOMY_UI_OUTPUT_DIR: path.join(outputRoot, 'special-modes', 'text-spacing-and-offline')
  }
});

if (scenarios.length === 0) {
  throw new Error(`No UI scenario matches suite=${requestedSuite}, profile=${requestedProfile || '<all>'}`);
}
for (const scenario of scenarios) await runScenario(scenario);
console.log(`\nMaven-owned UI verification passed: ${scenarios.length} scenario(s).`);
