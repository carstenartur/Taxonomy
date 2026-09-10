import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, appendFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const execute = promisify(execFile);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const out = path.join(root, 'target/interoperability-products');
const archiSha = 'f9422455a00a22f5340dc28692ceafe0ad720c8cde839eaafb0fab1cea57287f';
const report = { schemaVersion: 1, sourceCommit: process.env.GITHUB_SHA, executedAt: new Date().toISOString(), results: [], status: 'running' };
await mkdir(out, { recursive: true });
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
  const runtime = path.join(out, 'application'), classes = path.join(out, 'probe-classes');
  await mkdir(runtime, { recursive: true }); await mkdir(classes, { recursive: true });
  await run('jar', ['xf', jar, 'BOOT-INF/classes', 'BOOT-INF/lib'], runtime);
  const classpath = [classes, path.join(runtime, 'BOOT-INF/classes'), path.join(runtime, 'BOOT-INF/lib/*')].join(path.delimiter);
  await run('javac', ['--release', '21', '-cp', classpath, '-d', classes, path.join(root, '.github/scripts/InteroperabilityProductProbe.java')]);
  const probe = (profile, action, source, target) => run('java', ['-cp', classpath, 'InteroperabilityProductProbe', profile, action, source, target]);

  // The verifier is Node/Java. pipx installs the independently versioned external product in an isolated application environment.
  try {
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
  } catch (error) { report.results.push({ product: 'StrictDoc', result: 'failed', failure: error.message }); }

  try {
    const archive = path.join(out, 'archi.tgz');
    const response = await fetch('https://github.com/archimatetool/archi.io/releases/download/5.10_0/Archi-Linux64-5.10.0.tgz', { signal: AbortSignal.timeout(120_000) });
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
    await probe('archimate', 'export', fixture, exported);
    await run('xvfb-run', ['-a', executables[0], '-nosplash', '-consoleLog', '-application', 'com.archimatetool.commandline.app', '-data', path.join(out, 'archi-workspace'), '--abortOnException', '--xmlexchange.import', exported, '--xmlexchange.export', returned]);
    const comparison = await probe('archimate', 'compare', fixture, returned);
    const lossLine = comparison.split('\n').find(line => line.startsWith('PRODUCT_MAPPING_LOSSES '));
    if (!lossLine) throw new Error('Product comparison did not return its layout loss report');
    const mappingLosses = JSON.parse(lossLine.slice('PRODUCT_MAPPING_LOSSES '.length));
    report.results.push({ product: 'Archi', version: '5.10.0', releaseSha256: archiSha, profile: 'archimate-3.1/1', direction: 'Archi fixture -> Taxonomy -> Archi -> Taxonomy', fixture: path.basename(fixture), inputSha256: await digest(fixture), outputSha256: await digest(returned), result: 'passed', mappingLosses, knownLosses: ['Archi 5.10.0 may replace two straight-line attachment points with their midpoint bendpoint; every such transformation is recorded. Unknown geometry changes fail. This fixture does not certify every Archi feature.'] });
  } catch (error) { report.results.push({ product: 'Archi', result: 'failed', failure: error.message }); }
  if (report.results.some(result => result.result !== 'passed')) throw new Error('At least one real product round trip failed; inspect execution.log');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed'; report.failure = error.message; console.error(error); process.exitCode = 1;
} finally {
  await writeFile(path.join(out, 'evidence.json'), `${JSON.stringify(report, null, 2)}\n`);
  console.log(`INTEROPERABILITY_EVIDENCE ${JSON.stringify(report)}`);
}
