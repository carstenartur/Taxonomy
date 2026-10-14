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
  const expectedClasses = requiredClasses(assignments, sources, groups.excludedTags ?? []);
  const evidence = evidenceDirectory;
  const artifacts = artifactDirectories(evidence);
  for (const artifact of artifacts) {
    if (!assignments.has(artifact)) throw new Error(`unknown shard artifact: ${artifact}`);
  }
  for (const group of assignments.keys()) {
    if (!artifacts.includes(group)) throw new Error(`missing shard artifact: ${group}`);
  }

  const reportsByClass = new Map();
  const reportedSources = new Set();
  let skipped = 0;
  let reportCount = 0;
  let caseCount = 0;
  let totalDuration = 0;
  const coverage = [];
  const runIds = new Set();
  const casesBySource = new Map();

  for (const [group, requiredForGroup] of expectedClasses) {
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
      verifyExecutionIdentity(groups.requiredExecutions ?? [], parsed.className, parsed.executionId);
      if (parsed.failures || parsed.errors) throw new Error(`${group} report is not successful: ${report.path}`);
      const sourceClass = sourceClassForReport(parsed.className, sources);
      if (!sourceClass) {
        throw new Error(`${group} report has no matching test source: ${parsed.className}`);
      }
      assertTestExecution(parsed, sources.get(sourceClass), report.path);
      if (isLegitimatelyExcluded(sources.get(sourceClass), groups.excludedTags ?? [])) {
        throw new Error(`${group} reported profile-excluded test class: ${sourceClass}`);
      }
      const records = reportsByClass.get(parsed.className) ?? [];
      if (records.some(record => record.group !== group)) {
        throw new Error(`${parsed.className} appears in more than one shard`);
      }
      records.push({ group, report: report.path, executionId: parsed.executionId });
      reportsByClass.set(parsed.className, records);
      reportedSources.add(sourceClass);
      casesBySource.set(sourceClass, (casesBySource.get(sourceClass) ?? 0) + parsed.tests);
      skipped += parsed.skipped;
      caseCount += parsed.tests;
      totalDuration += parsed.duration;
      reportCount++;
    }
    if (listedReports.size !== actualReports.size
        || [...actualReports].some(path => !listedReports.has(path))) {
      throw new Error(`${group} has missing or unlisted JUnit reports`);
    }
    for (const className of requiredForGroup) {
      if (!reportedSources.has(className)) throw new Error(`${group} missing report for ${className}`);
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
  verifyRequiredExecutions(groups.requiredExecutions ?? [], reportsByClass);
  for (const [className, records] of reportsByClass) {
    const sourceClass = sourceClassForReport(className, sources);
    const expectedGroup = groupForClass(sourceClass, assignments);
    if (records.some(record => record.group !== expectedGroup)) {
      if (new Set(records.map(record => record.group)).size > 1) {
        throw new Error(`${className} appears in more than one shard`);
      }
      throw new Error(`${className} was reported by ${records[0].group}; it belongs to ${expectedGroup}`);
    }
    if (records.length > 1) {
      const allowed = (groups.requiredExecutions ?? [])
        .find(execution => execution.className === className)?.identities ?? [];
      if (records.length !== allowed.length
          || new Set(records.map(record => record.executionId)).size !== records.length
          || records.some(record => !allowed.includes(record.executionId))) {
        throw new Error(`${className} has duplicate report execution identities`);
      }
    }
  }
  const missing = [];
  let excluded = 0;
  for (const [className, source] of sources) {
    const hasReport = [...reportsByClass.keys()].some(reportClass =>
      sourceClassForReport(reportClass, sources) === className);
    if (hasReport) {
      if (source.containerOnly && (casesBySource.get(className) ?? 0) === 0) {
        throw new Error(`empty required test execution: ${className}`);
      }
      continue;
    }
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
      classes: reportedSources.size,
      suites: reportsByClass.size,
      reports: reportCount,
      cases: caseCount,
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
  const unexpectedGroups = actualGroups.filter(group => !Object.hasOwn(shardGroups, group));
  const actualRequiredGroups = actualGroups.filter(group => group !== defaultGroup);
  if (unexpectedGroups.length || requiredGroups.join('\n') !== actualRequiredGroups.join('\n')) {
    throw new Error(`expected shard inputs ${requiredGroups.join(', ')}, found ${actualGroups.join(', ')}`);
  }
  const sourceDirectory = join(resolve(repositoryRoot), 'taxonomy-app/src/test/java');
  const sources = testSources(sourceDirectory);
  const assignments = assignSources(sources, shardGroups, defaultGroup);
  const expectedClasses = requiredClasses(assignments, sources, groups.excludedTags ?? []);
  const classes = new Set();
  const durations = [];
  const casesBySource = new Map();

  for (const group of requiredGroups) {
    const directory = join(evidence, group);
    const manifest = readManifest(directory, group);
    verifyManifestIdentity(manifest, group, expectedCommit, expectedTree);
    const expectedForGroup = expectedClasses.get(group);
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
      verifyExecutionIdentity(groups.requiredExecutions ?? [], suite.className, suite.executionId);
      const sourceClass = sourceClassForReport(suite.className, sources);
      if (report.className !== suite.className || !sourceClass) {
        throw new Error(`${group} report has no matching test source: ${suite.className}`);
      }
      assertTestExecution(suite, sources.get(sourceClass), report.path);
      if (isLegitimatelyExcluded(sources.get(sourceClass), groups.excludedTags ?? [])) {
        throw new Error(`${group} reported profile-excluded test class: ${sourceClass}`);
      }
      if (suite.failures || suite.errors) throw new Error(`${group} report is not successful: ${report.path}`);
      if (!expectedForGroup.has(sourceClass)) {
        throw new Error(`${suite.className} is not assigned to shard ${group}`);
      }
      if (reportedClasses.has(suite.className) || classes.has(suite.className)) {
        throw new Error(`${suite.className} appears in more than one shard`);
      }
      reportedClasses.add(sourceClass);
      classes.add(suite.className);
      casesBySource.set(sourceClass, (casesBySource.get(sourceClass) ?? 0) + suite.tests);
      durations.push({ group, className: suite.className, durationSeconds: suite.duration });
    }
    if (listedReports.size !== actualReports.size) {
      throw new Error(`${group} has unlisted JUnit reports`);
    }
    for (const className of expectedForGroup) {
      if (!reportedClasses.has(className)) throw new Error(`${group} missing report for ${className}`);
      if (sources.get(className).containerOnly && (casesBySource.get(className) ?? 0) === 0) {
        throw new Error(`${group} empty required test execution: ${className}`);
      }
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
      const parsed = parseSuite(readFileSync(outputPath, 'utf8'), reportPath, { allowFailures: true });
      files.push({
        path: `tests/${reportPath.split(sep).join('/')}`,
        className: parsed.className,
        sha256: sha256(outputPath),
        tests: parsed.tests,
        skipped: parsed.skipped,
        failures: parsed.failures,
        errors: parsed.errors,
        failureElements: parsed.failureElements,
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
    reports: files.map(({ path, className, sha256: digest, tests, skipped, failures, errors, failureElements }) => ({
      path, className, sha256: digest, tests, skipped, failures, errors, failureElements,
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

export function discoverAppTestSourceIdentities(sourceRoot) {
  return [...testSources(resolve(sourceRoot)).keys()].sort();
}

function testSources(sourceRoot) {
  const types = [];
  for (const file of filesBelow(sourceRoot).filter(path => path.endsWith('.java'))) {
    const name = file.split(sep).at(-1);
    const source = readFileSync(file, 'utf8');
    const code = stripJavaLiteralsAndComments(source);
    const packageName = source.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1] ?? '';
    const fileTypes = [];
    for (const declaration of code.matchAll(/\b(abstract\s+)?(?:class|record|enum)\s+([A-Za-z_$][\w$]*)([^{]*)\{/g)) {
      const simpleName = declaration[2];
      const bodyStart = declaration.index + declaration[0].length - 1;
      const bodyEnd = matchingBrace(code, bodyStart);
      if (bodyEnd < 0) throw new Error(`unclosed test source type in ${file}`);
      fileTypes.push({
        file,
        path: file,
        source,
        code,
        className: '',
        simpleName,
        start: declaration.index,
        bodyStart,
        bodyEnd,
        abstract: Boolean(declaration[1]),
        candidate: TEST_SOURCE_NAME.test(name) && name === `${simpleName}.java`,
        parent: declaration[3].match(/\bextends\s+([\w.$]+)/)?.[1] ?? '',
      });
    }
    for (const type of fileTypes.sort((left, right) => left.start - right.start)) {
      const enclosing = fileTypes.filter(candidate =>
        candidate !== type && candidate.start < type.start && candidate.bodyEnd > type.bodyEnd)
        .sort((left, right) => right.start - left.start)[0];
      type.className = enclosing
        ? `${enclosing.className}$${type.simpleName}`
        : [packageName, type.simpleName].filter(Boolean).join('.');
      type.hasTests = TEST_ANNOTATION.test(typeBodyWithoutNestedTypes(code, type, fileTypes));
    }
    types.push(...fileTypes);
  }

  const testTypes = new Set(types.filter(type => type.hasTests).map(type => type.className));
  const inheritedTestTypes = new Set();
  const simpleNames = new Map();
  for (const type of types) {
    const matches = simpleNames.get(type.simpleName) ?? [];
    matches.push(type.className);
    simpleNames.set(type.simpleName, matches);
  }
  let changed;
  do {
    changed = false;
    for (const type of types) {
      if (testTypes.has(type.className) || !type.parent) continue;
      const parentType = type.parent.split('.').at(-1);
      const parents = type.parent.includes('.') ? [type.parent] : simpleNames.get(parentType) ?? [];
      if (parents.some(parent => testTypes.has(parent))) {
        testTypes.add(type.className);
        inheritedTestTypes.add(type.className);
        changed = true;
      }
    }
  } while (changed);

  const sources = new Map();
  for (const type of types) {
    const nestedTests = types.some(child =>
      child !== type && child.file === type.file
      && child.start > type.bodyStart && child.start < type.bodyEnd
      && testTypes.has(child.className));
    if (type.candidate && nestedTests) testTypes.add(type.className);
    if (!type.candidate || type.abstract || !testTypes.has(type.className)) continue;
    if (sources.has(type.className)) throw new Error(`duplicate test source identity: ${type.className}`);
    sources.set(type.className, {
      path: type.path,
      code: type.code,
      annotationText: stripJavaComments(type.source),
      simpleName: type.simpleName,
      containerOnly: !type.hasTests && !inheritedTestTypes.has(type.className) && nestedTests,
    });
  }
  return sources;
}

const TEST_ANNOTATION = /@(?:(?:org\.junit(?:\.jupiter)?(?:\.api)?\.)?Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|ArchTest)\b/;

function typeBodyWithoutNestedTypes(code, type, fileTypes) {
  let cursor = type.bodyStart + 1;
  let body = '';
  const nestedTypes = fileTypes
    .filter(candidate => candidate.start > type.start && candidate.start < type.bodyEnd)
    .sort((left, right) => left.start - right.start);
  for (const nested of nestedTypes) {
    if (nested.start < cursor) continue;
    body += code.slice(cursor, nested.start);
    cursor = nested.bodyEnd + 1;
  }
  return body + code.slice(cursor, type.bodyEnd);
}

function matchingBrace(code, openingBrace) {
  let depth = 0;
  for (let index = openingBrace; index < code.length; index++) {
    if (code[index] === '{') depth++;
    else if (code[index] === '}' && --depth === 0) return index;
  }
  return -1;
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

function parseSuite(xml, reportPath, { allowFailures = false } = {}) {
  const root = parseXmlDocument(xml, reportPath);
  if (root.name !== 'testsuite') throw new Error(`malformed JUnit report: ${reportPath}`);
  const attributes = root.attributes;
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
  const suiteName = required('name');
  const reportName = reportPath.split(/[\\/]/).at(-1);
  const reportBase = reportName.endsWith('.xml') ? reportName.slice(0, -4) : reportName;
  const suffixStart = suiteName.lastIndexOf('(');
  const hasSuiteSuffix = suffixStart > 0 && suiteName.endsWith(')');
  const className = hasSuiteSuffix ? suiteName.slice(0, suffixStart) : suiteName;
  const xmlSuffix = hasSuiteSuffix ? suiteName.slice(suffixStart + 1, -1) : '';
  const suiteReportPrefix = `TEST-${className}`;
  if (reportBase !== suiteReportPrefix && !reportBase.startsWith(`${suiteReportPrefix}-`)) {
    throw new Error(`JUnit report filename does not identify ${className}: ${reportPath}`);
  }
  const executionId = reportBase === suiteReportPrefix
    ? 'default'
    : reportBase.slice(suiteReportPrefix.length + 1);
  if ((executionId === 'default' && xmlSuffix !== '')
      || (executionId !== 'default' && executionId !== xmlSuffix)) {
    throw new Error(`JUnit report execution suffix mismatch: ${reportPath}`);
  }
  const declaredTests = number('tests');
  const failures = number('failures');
  const errors = number('errors');
  const declaredSkipped = number('skipped');
  const cases = descendants(root, 'testcase');
  const failedCases = cases.flatMap(testCase => descendants(testCase)
    .filter(node => ['failure', 'error', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError']
      .includes(node.name)));
  const validCaseClassNames = new Set([className]);
  if (executionId !== 'default') {
    validCaseClassNames.add(`${className}(${executionId})`);
    validCaseClassNames.add(`${className}-${executionId}`);
  }
  for (const testCase of cases) {
    const caseClassName = testCase.attributes.get('classname');
    if (caseClassName && !validCaseClassNames.has(caseClassName)) {
      throw new Error(`JUnit testcase source mismatch: ${reportPath}`);
    }
  }
  if (!allowFailures && (failures || errors || failedCases.length)) {
    const details = [...new Set(failedCases.map(node => node.name))].join(', ');
    throw new Error(`${reportPath} contains failure evidence${details ? `: ${details}` : ''}`);
  }
  const skipped = cases.filter(testCase => descendants(testCase, 'skipped').length > 0).length;
  if (declaredTests !== cases.length || declaredSkipped !== skipped) {
    throw new Error(`inconsistent JUnit testcase evidence: ${reportPath}`);
  }
  const durationText = attributes.get('time') ?? '0';
  const duration = Number(durationText);
  if (!Number.isFinite(duration) || duration < 0) throw new Error(`invalid JUnit duration: ${reportPath}`);
  return {
    className, executionId, tests: cases.length, failures, errors, skipped,
    failureElements: failedCases.map(node => node.name), duration,
  };
}

function assertTestExecution(suite, source, reportPath) {
  if (suite.tests === 0 && !source.containerOnly) {
    throw new Error(`empty required test execution: ${reportPath}`);
  }
}

function parseXmlDocument(xml, reportPath) {
  let offset = 0;
  let root;
  const stack = [];
  while (offset < xml.length) {
    if (xml[offset] !== '<') {
      const start = offset;
      while (offset < xml.length && xml[offset] !== '<') offset++;
      const text = xml.slice(start, offset);
      decodeXml(text, reportPath);
      if (!stack.length && text.trim()) throw new Error(`malformed JUnit XML text: ${reportPath}`);
      continue;
    }
    if (xml.startsWith('<!--', offset)) {
      const end = xml.indexOf('-->', offset + 4);
      if (end < 0) throw new Error(`malformed JUnit XML comment: ${reportPath}`);
      offset = end + 3;
      continue;
    }
    if (xml.startsWith('<![CDATA[', offset)) {
      const end = xml.indexOf(']]>', offset + 9);
      if (end < 0 || !stack.length) throw new Error(`malformed JUnit XML CDATA: ${reportPath}`);
      offset = end + 3;
      continue;
    }
    if (xml.startsWith('<?', offset)) {
      const end = xml.indexOf('?>', offset + 2);
      if (end < 0) throw new Error(`malformed JUnit XML processing instruction: ${reportPath}`);
      offset = end + 2;
      continue;
    }
    if (xml.startsWith('<!', offset)) {
      throw new Error(`unsafe JUnit report declaration: ${reportPath}`);
    }
    if (xml.startsWith('</', offset)) {
      offset += 2;
      const start = offset;
      if (!isXmlNameStart(xml[offset])) throw new Error(`malformed JUnit XML name: ${reportPath}`);
      offset++;
      while (isXmlNameCharacter(xml[offset])) offset++;
      const name = xml.slice(start, offset);
      offset = skipXmlWhitespace(xml, offset);
      if (xml[offset] !== '>' || stack.pop()?.name !== name) {
        throw new Error(`malformed JUnit XML nesting: ${reportPath}`);
      }
      offset++;
      continue;
    }
    offset++;
    const start = offset;
    if (!isXmlNameStart(xml[offset])) throw new Error(`malformed JUnit XML name: ${reportPath}`);
    offset++;
    while (isXmlNameCharacter(xml[offset])) offset++;
    const name = xml.slice(start, offset);
    const attributes = new Map();
    let selfClosing = false;
    while (offset < xml.length) {
      offset = skipXmlWhitespace(xml, offset);
      if (xml.startsWith('/>', offset)) {
        selfClosing = true;
        offset += 2;
        break;
      }
      if (xml[offset] === '>') {
        offset++;
        break;
      }
      const attributeStart = offset;
      if (!isXmlNameStart(xml[offset])) throw new Error(`malformed JUnit attribute: ${reportPath}`);
      offset++;
      while (isXmlNameCharacter(xml[offset])) offset++;
      const attributeName = xml.slice(attributeStart, offset);
      if (attributes.has(attributeName)) throw new Error(`duplicate JUnit attribute: ${reportPath}`);
      offset = skipXmlWhitespace(xml, offset);
      if (xml[offset] !== '=') throw new Error(`malformed JUnit attribute: ${reportPath}`);
      offset = skipXmlWhitespace(xml, offset + 1);
      const quote = xml[offset++];
      if (quote !== '"' && quote !== "'") throw new Error(`malformed JUnit attribute: ${reportPath}`);
      const valueStart = offset;
      while (offset < xml.length && xml[offset] !== quote && xml[offset] !== '<') offset++;
      if (xml[offset] !== quote) throw new Error(`malformed JUnit attribute: ${reportPath}`);
      attributes.set(attributeName, decodeXml(xml.slice(valueStart, offset), reportPath));
      offset++;
    }
    if (offset > xml.length) throw new Error(`malformed JUnit element: ${reportPath}`);
    const node = { name, attributes, children: [] };
    if (stack.length) stack.at(-1).children.push(node);
    else if (root) throw new Error(`multiple JUnit XML roots: ${reportPath}`);
    else root = node;
    if (!selfClosing) stack.push(node);
  }
  if (stack.length || !root) throw new Error(`malformed JUnit report: ${reportPath}`);
  return root;
}

function isXmlNameStart(character) {
  return character !== undefined && /[A-Za-z_:]/.test(character);
}

function isXmlNameCharacter(character) {
  return character !== undefined && /[A-Za-z0-9_.:-]/.test(character);
}

function skipXmlWhitespace(xml, offset) {
  while (offset < xml.length && /\s/.test(xml[offset])) offset++;
  return offset;
}

function decodeXml(value, reportPath) {
  let decoded = '';
  for (let index = 0; index < value.length; index++) {
    if (value[index] !== '&') {
      decoded += value[index];
      continue;
    }
    const end = value.indexOf(';', index + 1);
    if (end < 0) throw new Error(`malformed JUnit XML entity: ${reportPath}`);
    const entity = value.slice(index + 1, end);
    const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
    let replacement = named[entity];
    if (replacement === undefined && entity.startsWith('#')) {
      const hexadecimal = entity[1]?.toLowerCase() === 'x';
      const digits = entity.slice(hexadecimal ? 2 : 1);
      const codePoint = Number.parseInt(digits, hexadecimal ? 16 : 10);
      if (!digits || !Number.isInteger(codePoint) || codePoint < 0
          || codePoint > 0x10ffff || (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
        throw new Error(`invalid JUnit XML character reference: ${reportPath}`);
      }
      replacement = String.fromCodePoint(codePoint);
    }
    if (replacement === undefined) throw new Error(`unsupported JUnit XML entity: ${reportPath}`);
    decoded += replacement;
    index = end;
  }
  return decoded;
}

function descendants(node, name) {
  if (!node || typeof node !== 'object') return [];
  return node.children.flatMap(child => [
    ...(name === undefined || child.name === name ? [child] : []),
    ...descendants(child, name),
  ]);
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
  return stripJavaComments(source)
    .replace(/"""[\s\S]*?"""/g, '""')
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    .replace(/'(?:\\.|[^'\\])*'/g, "''");
}

function stripJavaComments(source) {
  let result = '';
  let index = 0;
  while (index < source.length) {
    if (source.startsWith('//', index)) {
      const end = source.indexOf('\n', index + 2);
      const commentEnd = end < 0 ? source.length : end;
      result += [...source.slice(index, commentEnd)]
        .map(character => character === '\r' ? '\r' : ' ').join('');
      index = commentEnd;
    } else if (source.startsWith('/*', index)) {
      const end = source.indexOf('*/', index + 2);
      if (end < 0) break;
      const comment = source.slice(index, end + 2);
      result += [...comment].map(character => '\r\n'.includes(character) ? character : ' ').join('');
      index = end + 2;
    } else if (source.startsWith('"""', index)) {
      const end = source.indexOf('"""', index + 3);
      if (end < 0) {
        result += source.slice(index);
        break;
      }
      result += source.slice(index, end + 3);
      index = end + 3;
    } else if (source[index] === '"' || source[index] === "'") {
      const start = index;
      const quote = source[index++];
      while (index < source.length) {
        if (source[index] === '\\') index += 2;
        else if (source[index++] === quote) break;
      }
      result += source.slice(start, index);
    } else {
      result += source[index++];
    }
  }
  return result;
}

function sourceClassForReport(className, sources) {
  let candidate = className;
  while (candidate) {
    if (sources.has(candidate)) return candidate;
    const nested = candidate.lastIndexOf('$');
    if (nested < 0) break;
    candidate = candidate.slice(0, nested);
  }
  return undefined;
}

function requiredClasses(assignments, sources, excludedTags) {
  return new Map([...assignments].map(([group, classNames]) => [group,
    new Set([...classNames].filter(className =>
      !isLegitimatelyExcluded(sources.get(className), excludedTags)))]));
}

function verifyRequiredExecutions(requiredExecutions, reportsByClass) {
  for (const required of requiredExecutions) {
    if (!required.className || !Array.isArray(required.identities)
        || required.identities.length === 0) {
      throw new Error('required app test execution identity is malformed');
    }
    const actual = (reportsByClass.get(required.className) ?? [])
      .map(report => report.executionId).sort();
    const expected = [...required.identities].sort();
    if (actual.length !== expected.length
        || actual.some((identity, index) => identity !== expected[index])) {
      throw new Error(`${required.className} execution identities mismatch: expected ${expected.join(', ')}, found ${actual.join(', ')}`);
    }
  }
}

function verifyExecutionIdentity(requiredExecutions, className, executionId) {
  if (executionId === 'default') return;
  const required = requiredExecutions.find(execution =>
    execution.className === className && execution.identities?.includes(executionId));
  if (!required) throw new Error(`${className} has an unauthorized execution identity: ${executionId}`);
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
        requiredExecutions: plan.requiredExecutions ?? [],
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
      requiredExecutions: plan.requiredExecutions ?? [],
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
        reports: manifest.reports.length,
        cases: manifest.reports.reduce((sum, report) => sum + report.tests, 0),
        skipped: manifest.reports.reduce((sum, report) => sum + report.skipped, 0),
        durationSeconds: manifest.timing.durationSeconds,
      };
    });
    console.log(`Application test inventory: ${result.tests.classes} source classes, `
      + `${result.tests.suites} JUnit suites and ${result.tests.cases} cases across `
      + `${result.coverage.length} shards; ${result.tests.skipped} skipped cases `
      + `and ${result.tests.excludedClasses} profile-excluded classes.`);
    groupTimings.forEach(group => console.log(
      `  group ${group.group}: ${group.reports} reports, ${group.cases} cases, `
        + `${group.skipped} skipped, ${group.durationSeconds}s`));
    for (const suite of result.slowSuites) {
      console.log(`  ${suite.group}: ${suite.className} ${suite.durationSeconds}s`);
    }
    appendStepSummary([
      '### Application test shards',
      '',
      `Executed inventory: ${result.tests.classes} source classes, ${result.tests.suites} JUnit suites, `
        + `${result.tests.cases} cases in ${result.coverage.length} isolated groups; `
        + `${result.tests.skipped} skipped cases and ${result.tests.excludedClasses} profile-excluded classes.`,
      '',
      '| Group | Reports and cases | Suite seconds |',
      '|---|---:|---:|',
      ...groupTimings.map(group =>
        `| ${group.group} | ${group.reports} / ${group.cases} cases (${group.skipped} skipped) | ${group.durationSeconds} |`),
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
