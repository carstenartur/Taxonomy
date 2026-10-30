import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, appendFile, readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const execute = promisify(execFile);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const out = path.join(root, 'target/interoperability-products');
const archiSha = 'f9422455a00a22f5340dc28692ceafe0ad720c8cde839eaafb0fab1cea57287f';
const report = { schemaVersion: 2, sourceCommit: process.env.GITHUB_SHA, executedAt: new Date().toISOString(), results: [], status: 'running' };
await mkdir(out, { recursive: true });
await writeFile(path.join(out, 'execution.log'), '');
const productEnvironment = { ...process.env, PIPX_HOME: path.join(out, 'product-applications'), PIPX_BIN_DIR: path.join(out, 'product-bin') };

async function run(command, args = [], cwd = root) {
  let result;
  try {
    result = await execute(command, args, { cwd, env: productEnvironment, timeout: 300_000, maxBuffer: 32 * 1024 * 1024 });
    await appendFile(path.join(out, 'execution.log'), `$ ${[command, ...args].join(' ')}\n${result.stdout}${result.stderr}\n`);
    return result.stdout;
  } catch (error) {
    const details = `${error.stdout || ''}${error.stderr || ''}`;
    await appendFile(path.join(out, 'execution.log'), `$ ${[command, ...args].join(' ')}\n${details}\n`);
    console.error(details.slice(-30_000));
    throw new Error(`Product verification command failed (${error.code}): ${command}`);
  }
}
async function digest(file) {
  const hash = createHash('sha256'); for await (const chunk of createReadStream(file)) hash.update(chunk); return hash.digest('hex');
}
async function launchers(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await launchers(file));
    else if (entry.isFile() && entry.name === 'Archi') result.push(file);
  }
  return result;
}
try {
  const manifest = JSON.parse(await readFile(path.join(root, 'target/ui-application/manifest.json'), 'utf8'));
  if (manifest.sourceCommit !== report.sourceCommit || manifest.sourceTree !== (await run('git', ['rev-parse', `${report.sourceCommit}^{tree}`])).trim()) throw new Error('Application artifact belongs to another source revision');
  if (path.basename(manifest.jarName) !== manifest.jarName || !manifest.jarName.endsWith('.jar')) throw new Error('Invalid application artifact name');
  const jar = path.join(root, 'target/ui-application', manifest.jarName);
  if (await digest(jar) !== manifest.sha256) throw new Error('Application artifact digest differs');
  report.applicationSha256 = manifest.sha256;
  report.sourceTree = manifest.sourceTree;
  report.applicationArtifact = manifest.jarName;
  report.taxonomyVersion = manifest.jarName.replace(/^taxonomy-app-/, '').replace(/\.jar$/, '');
  const featureIds = new Set(), featureJars = [];
  for (const feature of manifest.features || []) {
    if (featureIds.has(feature.id) || path.basename(feature.name) !== feature.name || !feature.name.endsWith('.jar')) throw new Error('Invalid startup feature identity');
    featureIds.add(feature.id);
    const featureJar = path.join(root, 'target/ui-application/features', feature.name);
    if (await digest(featureJar) !== feature.sha256) throw new Error('Startup feature digest differs');
    featureJars.push(featureJar);
  }
  if (!featureIds.has('portfolio') || !featureIds.has('architecture')) throw new Error('Actual workbench startup features are missing');
  report.featureArtifacts = manifest.features;
  const runtime = path.join(out, 'application'), classes = path.join(out, 'probe-classes');
  await rm(runtime, { recursive: true, force: true }); await rm(classes, { recursive: true, force: true });
  await mkdir(runtime, { recursive: true }); await mkdir(classes, { recursive: true });
  await run('jar', ['xf', jar, 'BOOT-INF/classes', 'BOOT-INF/lib'], runtime);
  const classpath = [classes, path.join(runtime, 'BOOT-INF/classes'), path.join(runtime, 'BOOT-INF/lib/*'), ...featureJars].join(path.delimiter);
  await run('javac', ['--release', '25', '-cp', classpath, '-d', classes,
    path.join(root, '.github/scripts/InteroperabilityProductProbe.java'), path.join(root, '.github/scripts/WorkbenchInteroperabilityProbe.java')]);
  const probe = (profile, action, source, target) => run('java', ['-cp', classpath, 'InteroperabilityProductProbe', profile, action, source, target]);
  const workbenchProbe = (action, source, target) => run('java', ['-cp', classpath, 'WorkbenchInteroperabilityProbe', action, source, target]);

  // The verifier is Node/Java. pipx installs the independently versioned external product in an isolated application environment.
  try {
    for (const directory of ['strictdoc-original', 'strictdoc-returned', 'strictdoc-final']) await rm(path.join(out, directory), { recursive: true, force: true });
    await run('pipx', ['install', 'strictdoc==0.29.0']);
    await writeFile(path.join(out, 'strictdoc-dependencies.txt'), await run('pipx', ['runpip', 'strictdoc', 'freeze']));
    const strictdoc = path.join(productEnvironment.PIPX_BIN_DIR, 'strictdoc');
    const fixture = path.join(root, 'taxonomy-export/src/test/resources/interoperability/strictdoc-product.sdoc');
    await run(strictdoc, ['export', '--formats=reqif-sdoc', '--reqif-enable-mid', '--reqif-multiline-is-xhtml', fixture, '--output-dir', path.join(out, 'strictdoc-original'), '--no-parallelization']);
    const original = path.join(out, 'strictdoc-original/reqif/output.reqif'), exported = path.join(out, 'taxonomy.reqif');
    await probe('reqif', 'export', original, exported);
    await run(strictdoc, ['convert', '--reqif-enable-mid', exported, path.join(out, 'strictdoc-returned')]);
    await run(strictdoc, ['export', '--formats=reqif-sdoc', '--reqif-enable-mid', '--reqif-multiline-is-xhtml', path.join(out, 'strictdoc-returned'), '--output-dir', path.join(out, 'strictdoc-final'), '--no-parallelization']);
    const returned = path.join(out, 'strictdoc-final/reqif/output.reqif');
    await probe('reqif', 'compare', original, returned);
    report.results.push({ product: 'StrictDoc', version: '0.29.0', profile: 'reqif-1.2/1', direction: 'product -> Taxonomy -> product -> Taxonomy', fixture: path.basename(fixture), inputSha256: await digest(original), outputSha256: await digest(returned), result: 'passed', knownLosses: ['StrictDoc regenerates type, hierarchy and relation identifiers; stable requirement/specification MIDs, mapped fields and topology are compared.'] });
  } catch (error) { report.results.push({ product: 'StrictDoc', profile: 'reqif-1.2/1', result: 'failed', failure: error.message }); }

  try {
    const archive = path.join(out, 'archi.tgz');
    const response = await fetch('https://github.com/archimatetool/archi.io/releases/download/5.10/Archi-Linux64-5.10.0.tgz', { signal: AbortSignal.timeout(120_000) });
    if (!response.ok) throw new Error(`Archi download returned ${response.status}`);
    await pipeline(Readable.fromWeb(response.body), createWriteStream(archive));
    if (await digest(archive) !== archiSha) throw new Error('Archi release digest differs from pinned upstream asset');
    const directory = path.join(out, 'archi'); await mkdir(directory, { recursive: true });
    const members = (await run('tar', ['tf', archive])).trim().split('\n');
    if (members.some(member => path.isAbsolute(member) || member.split('/').includes('..'))) throw new Error('Unsafe product archive member');
    await run('tar', ['xf', archive, '--no-same-owner', '-C', directory]);
    const executables = await launchers(directory); if (executables.length !== 1) throw new Error('Expected one Archi launcher');
    const fixture = path.join(root, 'taxonomy-export/src/test/resources/interoperability/archi-bendpoints.xml');
    const exported = path.join(out, 'taxonomy-archimate.xml'), returned = path.join(out, 'archi-returned.xml');
    await rm(returned, { force: true });
    await probe('archimate', 'export', fixture, exported);
    await run('xvfb-run', ['-a', executables[0], '-nosplash', '-consoleLog', '-application', 'com.archimatetool.commandline.app', '-data', path.join(out, 'archi-workspace'), '--abortOnException', '--xmlexchange.import', exported, '--xmlexchange.export', returned]);
    const comparison = await probe('archimate', 'compare', fixture, returned);
    const lossLine = comparison.split('\n').find(line => line.startsWith('PRODUCT_MAPPING_LOSSES '));
    if (!lossLine) throw new Error('Product comparison did not return its layout loss report');
    const mappingLosses = JSON.parse(lossLine.slice('PRODUCT_MAPPING_LOSSES '.length));
    report.results.push({ product: 'Archi', version: '5.10.0', releaseSha256: archiSha, profile: 'archimate-3.1/1', direction: 'Archi fixture -> Taxonomy -> Archi -> Taxonomy', fixture: path.basename(fixture), inputSha256: await digest(fixture), outputSha256: await digest(returned), result: 'passed', mappingLosses, knownLosses: ['Archi 5.10.0 may replace two straight-line attachment points with their midpoint bendpoint; every such transformation is recorded. Unknown geometry changes fail. This fixture does not certify every Archi feature.'] });

    const workbenchBundle = path.join(out, 'workbench.zip');
    const workbenchXml = path.join(out, 'workbench-model.archimate.xml');
    const workbenchReturned = path.join(out, 'archi-workbench-returned.xml');
    await rm(workbenchReturned, { force: true });
    await workbenchProbe('export', workbenchBundle, workbenchXml);
    await run('xvfb-run', ['-a', executables[0], '-nosplash', '-consoleLog', '-application', 'com.archimatetool.commandline.app',
      '-data', path.join(out, 'archi-workbench-workspace'), '--abortOnException', '--xmlexchange.import', workbenchXml, '--xmlexchange.export', workbenchReturned]);
    const workbenchComparison = await workbenchProbe('compare', workbenchXml, workbenchReturned);
    if (!workbenchComparison.split('\n').includes('RETURN_IMPORT_PROFILE taxonomy-archi-5.10-return-v1'))
      throw new Error('Workbench comparison did not execute the expected bounded return profile');
    const workbenchLossLine = workbenchComparison.split('\n').find(line => line.startsWith('PRODUCT_MAPPING_LOSSES '));
    if (!workbenchLossLine) throw new Error('Workbench product comparison did not return its transformation report');
    report.results.push({ product: 'Archi', version: '5.10.0', releaseSha256: archiSha, profile: 'taxonomy-archimate-3.1-v2',
      direction: 'Taxonomy workbench bundle -> Archi -> bounded original-bound return reader and semantic comparison',
      returnProfile: 'taxonomy-archi-5.10-return-v1', fixture: 'authored frozen workbench projection',
      bundleSha256: await digest(workbenchBundle), inputSha256: await digest(workbenchXml), outputSha256: await digest(workbenchReturned),
      result: 'passed', mappingLosses: JSON.parse(workbenchLossLine.slice('PRODUCT_MAPPING_LOSSES '.length)),
      returnLosses: JSON.parse(workbenchComparison.split('\n').find(line => line.startsWith('RETURN_IMPORT_LOSSES ')).slice('RETURN_IMPORT_LOSSES '.length)),
      checked: ['frozen provenance', 'exact property values and reported type transformations', 'model/element/relationship/view/property-definition names and language attributes',
        'node labels and complete node/connection styles with only the observed reported transitions', 'element and relationship identities', 'endpoints', 'view membership', 'geometry'],
      knownLosses: ['Archi omits en on property values and names; whole node labels and flat source-type folders are also omitted, including their label language markers. Each affected item is recorded.',
        'The observed lineWidth=3 is omitted. Node styles gain exactly fill alpha 100, line RGBA 92/92/92/100 and Sans 9 black font; the connection gains the observed black line and Sans 9 black font. Unknown style or language changes fail.',
        'Property-definition identifiers are product-owned and matched by unique semantic property name; identifier rewrites do not permit name, value or declaration changes beyond the recorded type erasure.'],
      limitations: ['Archi 5.10.0 converts number/boolean declarations to string. This is a bounded import/export check with recorded losses, not preservation of the typed Taxonomy profile; the strict reader still rejects the returned type-erased contract. The separate original-bound return profile requires exact unchanged semantic content, records every transformation and does not certify semantic editing or relation application.',
        'Bounded authored projection; no live analysis, authorization, independent consumer or Visio acceptance is implied.'] });
  } catch (error) {
    for (const profile of ['archimate-3.1/1', 'taxonomy-archimate-3.1-v2']) {
      if (!report.results.some(result => result.product === 'Archi' && result.profile === profile))
        report.results.push({ product: 'Archi', profile, result: 'failed', failure: error.message });
    }
  }
  if (report.results.some(result => result.result !== 'passed')) throw new Error('At least one real product round trip failed; inspect execution.log');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed'; report.failure = error.message; console.error(error); process.exitCode = 1;
} finally {
  await writeFile(path.join(out, 'evidence.json'), `${JSON.stringify(report, null, 2)}\n`);
  console.log(`INTEROPERABILITY_EVIDENCE ${JSON.stringify(report)}`);
}
