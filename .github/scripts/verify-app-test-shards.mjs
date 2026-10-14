import { createHash } from 'node:crypto';
import {
  copyFileSync, mkdirSync, readFileSync, realpathSync, readdirSync, rmSync, statSync, writeFileSync,
} from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(SCRIPT_DIR, '../..');
const REPORT_NAME = /^TEST-.+\.xml$/;
const TEST_SOURCE_NAME = /^(?:Test.*|.*Test|.*Tests|.*TestCase|.*IT)\.java$/;

export function verifyAppTestShards({
  repositoryRoot,
  sourceRoot,
  evidenceRoot,
  groups,
  expectedCommit,
  expectedTree,
}) {
  const root = resolve(repositoryRoot);
  const sourceDirectory = resolve(sourceRoot);
  const evidenceDirectory = resolve(evidenceRoot);
  if (!inside(root, sourceDirectory) || !inside(root, evidenceDirectory)) {
    throw new Error('test sources and shard evidence must be inside the repository');
  }
  if (!/^[0-9a-f]{40}$/i.test(expectedCommit ?? '')
      || !/^[0-9a-f]{40}$/i.test(expectedTree ?? '')) {
    throw new Error('expected commit and source tree must be full Git object IDs');
  }
  const sources = testSources(sourceDirectory);
  const shardGroups = groups.groups ?? Object.fromEntries(
    Object.entries(groups).filter(([, value]) => value && typeof value === 'object' && !Array.isArray(value)),
  );
  const groupEntries = Object.entries(shardGroups);
  const defaultGroups = groupEntries.filter(([, value]) => value.default === true);
  const defaultGroup = groups.defaultGroup ?? defaultGroups[0]?.[0];
  if (!defaultGroup || defaultGroups.length > 1) throw new Error('expected exactly one default shard');
  if (shardGroups[defaultGroup]?.default !== true) throw new Error('default shard is not declared');
  const assignments = assignSources(sources, shardGroups, defaultGroup);
  const evidence = evidenceDirectory;
  const artifacts = artifactDirectories(evidence);
  for (const artifact of artifacts) {
    if (!assignments.has(artifact)) throw new Error(`unknown shard artifact: ${artifact}`);
  }
  for (const group of assignments.keys()) {
    if (!artifacts.includes(group)) throw new Error(`missing shard artifact: ${group}`);
  }

  const reportsByClass = new Map();
  let skipped = 0;
  let reportCount = 0;
  let totalDuration = 0;
  const coverage = [];
  const runIds = new Set();

  for (const [group, expectedClasses] of assignments) {
    const directory = join(evidence, group);
    const manifest = readManifest(directory, group);
    verifyManifestIdentity(manifest, group, expectedCommit, expectedTree);
    runIds.add(manifest.buildId.split('.').slice(0, 2).join('.'));

    const listedReports = new Set();
    const actualReports = reportFiles(directory);
    for (const report of manifest.reports) {
      if (typeof report.path !== 'string' || listedReports.has(report.path)) {
        throw new Error(`${group} contains a duplicate or invalid report entry`);
      }
      listedReports.add(report.path);
      const file = resolve(directory, report.path);
      if (!actualReports.has(relative(directory, file))) {
        throw new Error(`${group} missing report: ${report.path}`);
      }
      safeArtifactPath(directory, report.path);
      if (sha256(file) !== report.sha256) throw new Error(`${group} report digest mismatch: ${report.path}`);
      const parsed = parseSuite(readFileSync(file, 'utf8'), report.path);
      if (report.className !== parsed.className) {
        throw new Error(`${group} report source mismatch: ${report.path}`);
      }
      if (parsed.failures || parsed.errors) throw new Error(`${group} report is not successful: ${report.path}`);
      if (!sources.has(parsed.className)) {
        throw new Error(`${group} report has no matching test source: ${parsed.className}`);
      }
      const records = reportsByClass.get(parsed.className) ?? [];
      if (records.some(record => record.group !== group)) {
        throw new Error(`${parsed.className} appears in more than one shard`);
      }
      records.push({ group, report: report.path });
      reportsByClass.set(parsed.className, records);
      skipped += parsed.skipped;
      totalDuration += parsed.duration;
      reportCount++;
    }
    if (listedReports.size !== actualReports.size
        || [...actualReports].some(path => !listedReports.has(path))) {
      throw new Error(`${group} has missing or unlisted JUnit reports`);
    }
    for (const className of expectedClasses) {
      if (!reportsByClass.has(className)) throw new Error(`${group} missing report for ${className}`);
    }

    const coverageInfo = manifest.coverage;
    if (!coverageInfo || typeof coverageInfo.path !== 'string') {
      throw new Error(`${group} coverage execution is missing`);
    }
    const coverageFile = resolve(directory, coverageInfo.path);
    if (!actualFiles(directory).has(relative(directory, coverageFile))) {
      throw new Error(`${group} coverage execution is missing: ${coverageInfo.path}`);
    }
    safeArtifactPath(directory, coverageInfo.path);
    if (sha256(coverageFile) !== coverageInfo.sha256) {
      throw new Error(`${group} coverage execution digest mismatch`);
    }
    coverage.push({ group, path: coverageFile });
  }

  if (runIds.size !== 1) throw new Error('shard artifacts do not belong to the same workflow run');
  for (const [className, records] of reportsByClass) {
    const expectedGroup = groupForClass(className, assignments);
    if (records.some(record => record.group !== expectedGroup)) {
      if (new Set(records.map(record => record.group)).size > 1) {
        throw new Error(`${className} appears in more than one shard`);
      }
      throw new Error(`${className} was reported by ${records[0].group}; it belongs to ${expectedGroup}`);
    }
    if (records.length > 1 && !shardGroups[expectedGroup].allowedRepeatedClasses?.includes(className)
        && !groups.allowedRepeatedClasses?.includes(className)) {
      throw new Error(`${className} appears in more than one shard`);
    }
  }

  const missing = [];
  let excluded = 0;
  for (const [className, source] of sources) {
    if (reportsByClass.has(className)) continue;
    if (isLegitimatelyExcluded(source, groups.excludedTags ?? [])) {
      excluded++;
      continue;
    }
    missing.push(`${className} (${groupForClass(className, assignments)})`);
  }
  if (missing.length) throw new Error(`missing test reports: ${missing.join(', ')}`);

  const result = {
    commit: expectedCommit,
    sourceTree: expectedTree,
    tests: {
      classes: reportsByClass.size,
      reports: reportCount,
      skipped,
      excludedClasses: excluded,
    },
    coverage: coverage.map(({ group }) => group),
    durationSeconds: Number(totalDuration.toFixed(3)),
    slowSuites: [...reportsByClass.entries()]
      .flatMap(([className, records]) => records.map(record => ({
        className,
        group: record.group,
        durationSeconds: suiteDuration(join(evidence, record.group, record.report)),
      })))
      .sort((left, right) => right.durationSeconds - left.durationSeconds)
      .slice(0, 10),
  };
  return result;
}

function verifyManifestIdentity(manifest, group, expectedCommit, expectedTree) {
  if (manifest.commit !== expectedCommit) {
    throw new Error(`${group} commit mismatch: expected ${expectedCommit}, got ${manifest.commit}`);
  }
  if (manifest.sourceTree !== expectedTree) {
    throw new Error(`${group} source tree mismatch: expected ${expectedTree}, got ${manifest.sourceTree}`);
  }
  if (!new RegExp(`^\\d+\\.\\d+\\.${escapeRegExp(group)}$`).test(manifest.buildId ?? '')) {
    throw new Error(`${group} build identity is invalid`);
  }
}

export function verifyAppTestShardInputs({
  repositoryRoot,
  evidenceRoot,
  groups,
  expectedCommit,
  expectedTree,
}) {
  const shardGroups = groups.groups;
  const defaultGroup = groups.defaultGroup;
  const requiredGroups = Object.keys(shardGroups).filter(group => group !== defaultGroup).sort();
  const evidence = resolve(evidenceRoot);
  const actualGroups = artifactDirectories(evidence);
  if (requiredGroups.join('\n') !== actualGroups.join('\n')) {
    throw new Error(`expected shard inputs ${requiredGroups.join(', ')}, found ${actualGroups.join(', ')}`);
  }
  const sourceDirectory = join(resolve(repositoryRoot), 'taxonomy-app/src/test/java');
  const sources = testSources(sourceDirectory);
  const assignments = assignSources(sources, shardGroups, defaultGroup);
  const classes = new Set();
  const durations = [];

  for (const group of requiredGroups) {
    const directory = join(evidence, group);
    const manifest = readManifest(directory, group);
    verifyManifestIdentity(manifest, group, expectedCommit, expectedTree);
    const expectedClasses = assignments.get(group);
    const actualReports = reportFiles(directory);
    const listedReports = new Set();
    const reportedClasses = new Set();
    for (const report of manifest.reports) {
      if (typeof report.path !== 'string' || listedReports.has(report.path)) {
        throw new Error(`${group} contains a duplicate or invalid report entry`);
      }
      listedReports.add(report.path);
      if (!actualReports.has(report.path)) throw new Error(`${group} missing report: ${report.path}`);
      const file = safeArtifactPath(directory, report.path);
      if (sha256(file) !== report.sha256) throw new Error(`${group} report digest mismatch: ${report.path}`);
      const suite = parseSuite(readFileSync(file, 'utf8'), report.path);
      if (report.className !== suite.className || !sources.has(suite.className)) {
        throw new Error(`${group} report has no matching test source: ${suite.className}`);
      }
      if (suite.failures || suite.errors) throw new Error(`${group} report is not successful: ${report.path}`);
      if (!expectedClasses.has(suite.className)) {
        throw new Error(`${suite.className} is not assigned to shard ${group}`);
      }
      if (reportedClasses.has(suite.className) || classes.has(suite.className)) {
        throw new Error(`${suite.className} appears in more than one shard`);
      }
      reportedClasses.add(suite.className);
      classes.add(suite.className);
      durations.push({ group, className: suite.className, durationSeconds: suite.duration });
    }
    if (listedReports.size !== actualReports.size) {
      throw new Error(`${group} has unlisted JUnit reports`);
    }
    for (const className of expectedClasses) {
      if (!reportedClasses.has(className)) throw new Error(`${group} missing report for ${className}`);
    }
    const coverage = manifest.coverage;
    if (!coverage || typeof coverage.path !== 'string') throw new Error(`${group} coverage execution is missing`);
    const coverageFile = resolve(directory, coverage.path);
    if (!actualFiles(directory).has(relative(directory, coverageFile))) {
      throw new Error(`${group} coverage execution is missing: ${coverage.path}`);
    }
    safeArtifactPath(directory, coverage.path);
    if (sha256(coverageFile) !== coverage.sha256) throw new Error(`${group} coverage execution digest mismatch`);
  }
  return { groups: requiredGroups, classes: [...classes].sort(), slowSuites: durations };
}

export function stageAppTestShard({
  repositoryRoot,
  shard,
  commit,
  sourceTree,
  buildId,
}) {
  if (!/^[a-z][a-z0-9-]*$/.test(shard)) throw new Error(`invalid shard name: ${shard}`);
  if (!/^[0-9a-f]{40}$/i.test(commit) || !/^[0-9a-f]{40}$/i.test(sourceTree)) {
    throw new Error('commit and source tree must be full Git object IDs');
  }
  if (!new RegExp(`^\\d+\\.\\d+\\.${escapeRegExp(shard)}$`).test(buildId)) {
    throw new Error(`${shard} build identity is invalid`);
  }
  const root = resolve(repositoryRoot);
  const appTarget = join(root, 'taxonomy-app/target');
  const destination = join(root, 'target/app-test-shards', shard);
  rmSync(destination, { recursive: true, force: true });
  const files = [];
  for (const reportDirectory of ['surefire-reports', 'failsafe-reports']) {
    const directory = join(appTarget, reportDirectory);
    for (const file of filesBelow(directory)) {
      if (!REPORT_NAME.test(file.split(sep).at(-1))) continue;
      const reportPath = relative(appTarget, file);
      const outputPath = join(destination, 'tests', reportPath);
      mkdirSync(dirname(outputPath), { recursive: true });
      copyFileSync(file, outputPath);
      const parsed = parseSuite(readFileSync(outputPath, 'utf8'), reportPath);
      files.push({
        path: `tests/${reportPath.split(sep).join('/')}`,
        className: parsed.className,
        sha256: sha256(outputPath),
        tests: parsed.tests,
        skipped: parsed.skipped,
        durationSeconds: parsed.duration,
      });
    }
  }
  if (files.length === 0) throw new Error(`${shard} produced no JUnit reports`);
  const coverageFile = join(appTarget, 'jacoco.exec');
  const coveragePath = join(destination, 'coverage.exec');
  if (!isFile(coverageFile)) throw new Error(`${shard} JaCoCo execution data is missing`);
  mkdirSync(destination, { recursive: true });
  copyFileSync(coverageFile, coveragePath);
  const manifest = {
    schemaVersion: 1,
    shard,
    commit: commit.toLowerCase(),
    sourceTree: sourceTree.toLowerCase(),
    buildId,
    reports: files.map(({ path, className, sha256: digest }) => ({
      path, className, sha256: digest,
    })),
    coverage: { path: 'coverage.exec', sha256: sha256(coveragePath) },
    timing: {
      durationSeconds: Number(files.reduce((sum, file) => sum + file.durationSeconds, 0).toFixed(3)),
      suites: files.map(({ className, durationSeconds }) => ({ className, durationSeconds }))
        .sort((left, right) => right.durationSeconds - left.durationSeconds),
    },
  };
  writeFileSync(join(destination, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  return manifest;
}

function assignSources(sources, groups, defaultGroup) {
  const assignments = new Map();
  for (const [group, definition] of Object.entries(groups)) {
    if (!definition || typeof definition !== 'object') continue;
    if (group !== defaultGroup && !Array.isArray(definition.tests)) {
      throw new Error(`unknown shard selector for ${group}`);
    }
    for (const className of definition.tests ?? []) {
      if (!sources.has(className)) throw new Error(`${group} selector has no matching source: ${className}`);
      if (assignments.has(className)) throw new Error(`${className} assigned more than once`);
      assignments.set(className, group);
    }
  }
  for (const [className, source] of sources) {
    const matchingTags = Object.entries(groups)
      .filter(([, definition]) => definition?.tag && hasTag(source, definition.tag));
    if (matchingTags.length > 1) throw new Error(`${className} assigned more than once`);
    if (matchingTags.length === 1) {
      const [taggedGroup, definition] = matchingTags[0];
      if (assignments.get(className) !== taggedGroup
          || !definition.tests.includes(className)) {
        throw new Error(`${className} shard tag is missing from the ${taggedGroup} catalogue selector`);
      }
    } else if (!assignments.has(className)) {
      assignments.set(className, defaultGroup);
    }
    if (assignments.has(className)) {
      const assigned = groups[assignments.get(className)];
      if (assigned?.tag && !hasTag(source, assigned.tag)) {
        throw new Error(`${className} has no source tag for shard ${assignments.get(className)}`);
      }
    }
  }
  const result = new Map([...Object.keys(groups)]
    .filter(group => groups[group] && typeof groups[group] === 'object')
    .map(group => [group, new Set()]));
  for (const [className, group] of assignments) {
    if (!result.has(group)) throw new Error(`unknown shard: ${group}`);
    result.get(group).add(className);
  }
  return result;
}

function groupForClass(className, assignments) {
  for (const [group, classes] of assignments) if (classes.has(className)) return group;
  return undefined;
}

function testSources(sourceRoot) {
  const sources = new Map();
  for (const file of filesBelow(sourceRoot)) {
    const name = file.split(sep).at(-1);
    if (!TEST_SOURCE_NAME.test(name)) continue;
    const source = readFileSync(file, 'utf8');
    const code = stripJavaLiteralsAndComments(source);
    const simpleName = name.slice(0, -'.java'.length);
    if (!new RegExp(`\\b(?:class|record|enum)\\s+${escapeRegExp(simpleName)}\\b`).test(code)
        || /\babstract\s+class\s+/.test(code)
        || !/@(?:(?:org\.junit(?:\.jupiter)?(?:\.api)?\.)?Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/.test(code)) {
      continue;
    }
    const packageName = source.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1] ?? '';
    const className = [packageName, simpleName].filter(Boolean).join('.');
    if (sources.has(className)) throw new Error(`duplicate test source identity: ${className}`);
    const annotationText = source
      .replace(/"""[\s\S]*?"""/g, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\r\n]*/g, '');
    sources.set(className, { path: file, code, annotationText, simpleName });
  }
  return sources;
}

function readManifest(directory, group) {
  const file = join(directory, 'manifest.json');
  if (!isFile(file)) throw new Error(`${group} shard manifest is missing`);
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${group} shard manifest is malformed: ${error.message}`);
  }
  if (manifest.schemaVersion !== 1 || manifest.shard !== group
      || !Array.isArray(manifest.reports) || manifest.reports.length === 0) {
    throw new Error(`${group} shard manifest is incomplete`);
  }
  return manifest;
}

function parseSuite(xml, reportPath) {
  if (/<!DOCTYPE|<!ENTITY/i.test(xml)) throw new Error(`unsafe JUnit report: ${reportPath}`);
  validateXmlMarkup(xml, reportPath);
  const opening = xml.match(/<testsuite\b([^>]*)>/i);
  if (!opening) throw new Error(`malformed JUnit report: ${reportPath}`);
  const attributes = new Map();
  for (const match of opening[1].matchAll(/([A-Za-z_:][\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')/g)) {
    if (attributes.has(match[1])) throw new Error(`duplicate JUnit attribute: ${reportPath}`);
    attributes.set(match[1], match[3] ?? match[4] ?? '');
  }
  const required = name => {
    const value = attributes.get(name);
    if (value === undefined || value === '') throw new Error(`JUnit report has no ${name}: ${reportPath}`);
    return value;
  };
  const number = name => {
    const value = required(name);
    if (!/^\d+$/.test(value)) throw new Error(`invalid JUnit ${name}: ${reportPath}`);
    return Number(value);
  };
  const className = decodeXml(required('name'));
  const reportName = reportPath.split(/[\\/]/).at(-1);
  const suiteReportPrefix = `TEST-${className}`;
  if (reportName !== `${suiteReportPrefix}.xml`
      && !reportName.startsWith(`${suiteReportPrefix}-`)) {
    throw new Error(`JUnit report filename does not identify ${className}: ${reportPath}`);
  }
  const tests = number('tests');
  const failures = number('failures');
  const errors = number('errors');
  const skipped = number('skipped');
  const reportBody = xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '');
  const cases = [...reportBody.matchAll(/<testcase\b/g)].length;
  const skippedElements = [...reportBody.matchAll(/<skipped\b/g)].length;
  if (tests < 1 || cases !== tests || skippedElements !== skipped
      || !/<\/testsuite>\s*$/i.test(xml)) {
    throw new Error(`inconsistent JUnit counters: ${reportPath}`);
  }
  const durationText = attributes.get('time') ?? '0';
  const duration = Number(durationText);
  if (!Number.isFinite(duration) || duration < 0) throw new Error(`invalid JUnit duration: ${reportPath}`);
  return { className, tests, failures, errors, skipped, duration };
}

function validateXmlMarkup(xml, reportPath) {
  const markup = xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<\?[\s\S]*?\?>/g, '');
  const tokens = [...markup.matchAll(/<\/?[A-Za-z_][\w:.-]*(?:\s+[^<>]*?)?\s*\/?>/g)];
  const stack = [];
  for (const [token] of tokens) {
    if (/^<\//.test(token)) {
      const name = token.match(/^<\/([A-Za-z_][\w:.-]*)/)[1];
      if (stack.pop() !== name) throw new Error(`malformed JUnit XML nesting: ${reportPath}`);
    } else if (!/\/>$/.test(token)) {
      stack.push(token.match(/^<([A-Za-z_][\w:.-]*)/)[1]);
    }
  }
  const text = markup.replace(/<\/?[A-Za-z_][\w:.-]*(?:\s+[^<>]*?)?\s*\/?>/g, '');
  if (stack.length || text.includes('<') || !/^<testsuite\b/i.test(markup.trim())) {
    throw new Error(`malformed JUnit report: ${reportPath}`);
  }
}

function isLegitimatelyExcluded(source, excludedTags) {
  if (!excludedTags.length) return false;
  return classTags(source).some(tag => excludedTags.includes(tag));
}

function hasTag(source, tag) {
  return classTags(source).includes(tag);
}

function classTags(source) {
  const declaration = new RegExp(`\\b(?:class|record|enum)\\s+${escapeRegExp(source.simpleName)}\\b`);
  const position = source.annotationText.search(declaration);
  if (position < 0) return [];
  return [...source.annotationText.slice(0, position)
    .matchAll(/@(?:(?:org\.junit(?:\.jupiter)?(?:\.api)?\.)?Tag)\(\s*"([^"]+)"\s*\)/g)]
    .map(match => match[1]);
}

function stripJavaLiteralsAndComments(source) {
  return source
    .replace(/"""[\s\S]*?"""/g, '""')
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\r\n]*/g, '')
    .replace(/'(?:\\.|[^'\\])*'/g, "''");
}

function reportFiles(directory) {
  return new Set([...filesBelow(join(directory, 'tests'))]
    .filter(file => REPORT_NAME.test(file.split(sep).at(-1)))
    .map(file => relative(directory, file)));
}

function actualFiles(directory) {
  return new Set(filesBelow(directory).map(file => relative(directory, file)));
}

function artifactDirectories(root) {
  if (!isDirectory(root)) return [];
  return readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => entry.name)
    .sort();
}

function filesBelow(directory) {
  if (!isDirectory(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? filesBelow(path) : entry.isFile() ? [path] : [];
  });
}

function safeArtifactPath(directory, path) {
  if (!path || path.includes('\\') || path.startsWith('/') || path.split('/').includes('..')) {
    throw new Error(`invalid shard artifact path: ${path}`);
  }
  const file = resolve(directory, path);
  if (!inside(directory, file)) throw new Error(`invalid shard artifact path: ${path}`);
  if (!isFile(file)) throw new Error(`shard artifact is missing: ${path}`);
  if (!inside(realpathSync(directory), realpathSync(file))) {
    throw new Error(`invalid shard artifact symlink: ${path}`);
  }
  return file;
}

function inside(parent, child) {
  const relativePath = relative(resolve(parent), resolve(child));
  return relativePath !== '' && relativePath !== '..' && !relativePath.startsWith(`..${sep}`);
}

function suiteDuration(path) {
  return parseSuite(readFileSync(path, 'utf8'), path).duration;
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function decodeXml(value) {
  return value.replaceAll('&quot;', '"').replaceAll('&apos;', "'")
    .replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&');
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function isFile(path) {
  try {
    return readdirSync(dirname(path), { withFileTypes: true })
      .some(entry => entry.isFile() && entry.name === path.split(sep).at(-1));
  } catch {
    return false;
  }
}

function isDirectory(path) {
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

function parseArguments(argumentsList) {
  const options = new Map();
  for (let index = 0; index < argumentsList.length; index++) {
    const name = argumentsList[index];
    if (!name.startsWith('--') || index + 1 >= argumentsList.length) {
      throw new Error(`expected --option value, got ${name}`);
    }
    if (options.has(name)) throw new Error(`duplicate option ${name}`);
    options.set(name, argumentsList[++index]);
  }
  return options;
}

function main(argumentsList) {
  try {
    const options = parseArguments(argumentsList);
    const repositoryRoot = resolve(options.get('--root') ?? ROOT);
    const sourceTree = options.get('--source-tree');
    if (options.get('--stage')) {
      const shard = options.get('--shard');
      const identity = sourceIdentity(repositoryRoot, options.get('--commit'), sourceTree);
      const buildId = options.get('--build-id')
        ?? `${process.env.GITHUB_RUN_ID}.${process.env.GITHUB_RUN_ATTEMPT}.${shard}`;
      if (!shard || !buildId || buildId.startsWith('undefined.')) {
        throw new Error('--stage requires --shard and a workflow build identity');
      }
      const manifest = stageAppTestShard({ repositoryRoot, shard, ...identity, buildId });
      console.log(`${shard}: ${manifest.reports.length} reports, `
        + `${manifest.timing.durationSeconds}s, coverage SHA-256 ${manifest.coverage.sha256}`);
      return 0;
    }
    if (options.get('--verify-inputs')) {
      const catalog = JSON.parse(readFileSync(join(repositoryRoot, '.mvn/verification-suites.json'), 'utf8'));
      const plan = catalog.applicationTestShards;
      if (!plan?.defaultGroup || !plan.groups) throw new Error('application test shard catalogue is missing or malformed');
      const groups = {
        groups: plan.groups,
        defaultGroup: plan.defaultGroup,
        excludedTags: plan.excludedTags ?? [],
        allowedRepeatedClasses: plan.allowedRepeatedClasses ?? [],
      };
      const identity = sourceIdentity(repositoryRoot, options.get('--commit'), sourceTree);
      const result = verifyAppTestShardInputs({
        repositoryRoot,
        evidenceRoot: join(repositoryRoot, 'target/app-test-shards'),
        groups,
        expectedCommit: identity.commit,
        expectedTree: identity.sourceTree,
      });
      console.log(`Validated shard inputs: ${result.classes.length} test classes in ${result.groups.join(', ')}.`);
      return 0;
    }
    if (!options.get('--verify')) throw new Error('expected --stage, --verify-inputs or --verify');
    const catalog = JSON.parse(readFileSync(join(repositoryRoot, '.mvn/verification-suites.json'), 'utf8'));
    const plan = catalog.applicationTestShards;
    if (!plan?.defaultGroup || !plan.groups) throw new Error('application test shard catalogue is missing or malformed');
    const groups = {
      ...plan.groups,
      groups: plan.groups,
      defaultGroup: plan.defaultGroup,
      excludedTags: plan.excludedTags ?? [],
      allowedRepeatedClasses: plan.allowedRepeatedClasses ?? [],
    };
    const identity = sourceIdentity(repositoryRoot, options.get('--commit'), sourceTree);
    const result = verifyAppTestShards({
      repositoryRoot,
      sourceRoot: join(repositoryRoot, 'taxonomy-app/src/test/java'),
      evidenceRoot: join(repositoryRoot, 'target/app-test-shards'),
      groups,
      expectedCommit: identity.commit,
      expectedTree: identity.sourceTree,
    });
    writeFileSync(join(repositoryRoot, 'target/app-test-shards/timings.json'),
      `${JSON.stringify(result, null, 2)}\n`);
    const groupTimings = Object.keys(plan.groups).map(group => {
      const manifest = readManifest(join(repositoryRoot, 'target/app-test-shards', group), group);
      return {
        group,
        classes: manifest.reports.length,
        durationSeconds: manifest.timing.durationSeconds,
      };
    });
    console.log(`Application test inventory: ${result.tests.classes} classes across `
      + `${result.coverage.length} shards; ${result.tests.skipped} skipped cases `
      + `and ${result.tests.excludedClasses} profile-excluded classes.`);
    groupTimings.forEach(group => console.log(
      `  group ${group.group}: ${group.classes} reports, ${group.durationSeconds}s`));
    for (const suite of result.slowSuites) {
      console.log(`  ${suite.group}: ${suite.className} ${suite.durationSeconds}s`);
    }
    appendStepSummary([
      '### Application test shards',
      '',
      `Executed inventory: ${result.tests.classes} classes in ${result.coverage.length} isolated groups; `
        + `${result.tests.skipped} skipped cases and ${result.tests.excludedClasses} profile-excluded classes.`,
      '',
      '| Group | Reports | Suite seconds |',
      '|---|---:|---:|',
      ...groupTimings.map(group => `| ${group.group} | ${group.classes} | ${group.durationSeconds} |`),
      '',
      'Slowest suites:',
      ...result.slowSuites.slice(0, 5).map(suite =>
        `- ${suite.group}: \`${suite.className}\` (${suite.durationSeconds}s)`),
      '',
    ].join('\n'));
    return 0;
  } catch (error) {
    console.error(`::error::${error.message}`);
    return 1;
  }

  function appendStepSummary(markdown) {
    const path = process.env.GITHUB_STEP_SUMMARY;
    if (path) writeFileSync(path, markdown, { flag: 'a' });
  }

  function sourceIdentity(root, commit, tree) {
    const sourceCommit = commit ?? process.env.GITHUB_SHA
      ?? execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
    const sourceTree = tree ?? execFileSync(
      'git', ['rev-parse', `${sourceCommit}^{tree}`], { cwd: root, encoding: 'utf8' },
    ).trim();
    return { commit: sourceCommit, sourceTree };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
