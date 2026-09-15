import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const paths={
 'taxonomy-app/src/main/java/com/taxonomy/analysis/controller/AnalysisProgressController.java':'0eb42e70595a6e4881dcf01f466d71bba21cbd7d',
 'taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js':'98d28a6334eff860d52c209b97264943cc6910bb',
 'taxonomy-app/src/test/java/com/taxonomy/analysis/controller/AnalysisProgressAdmissionTest.java':'713d21b5138e2e3730c3e3a3ca3f5f5f1088dc84',
 '.github/scripts/analysis-live-progress.test.mjs':'521d2968840975aeabc26d32691219d9d2f0ad5f'
};
const hash=content=>createHash('sha1').update('blob '+Buffer.byteLength(content)+'\0').update(content).digest('hex');
if(process.argv[2]==='prepare') {
 const path='taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js';
 let text=readFileSync(path,'utf8');
 assert.equal(hash(text),'65868e73e0984ffda011f8dbf1f8c01dde350ea5');
 const before='            return options.resolveUrl ? options.resolveUrl(target) : target;';
 assert.equal(text.split(before).length,2);
 text=text.replace(before,"            // The POST may not have registered this run yet. Observation must not log a 404.\n            if (!suffix && !seen) target += (target.indexOf('?') >= 0 ? '&' : '?') + 'waitForRegistration=true';\n"+before)
  .replace('if (response.status === 404 && !seen)','if ((response.status === 202 || response.status === 404) && !seen)');
 writeFileSync(path,text);
 const testPath='.github/scripts/analysis-live-progress.test.mjs';
 const tests=readFileSync(testPath,'utf8');
 assert.equal(hash(tests),'8de70ce5849877453521e2f98b1241df062fa2e5');
 writeFileSync(testPath,tests+`
test('early admission polls accept an empty pending response without starting a run', async () => {
    let count = 0;
    const f = fixture(async () => ++count === 1
        ? { ok: true, status: 202, json: async () => { throw new Error('pending response has no JSON body'); } }
        : response(snapshot(count)));
    await f.step(0);
    assert.deepEqual(f.unavailable, ['WAITING_FOR_RUN']);
    assert.equal(f.snapshots.length, 0);
    await f.step(1000); await f.step(1000);
    assert.equal(f.snapshots.length, 2);
    assert.ok(f.calls.slice(0, 2).every(call => call.url.endsWith('&waitForRegistration=true')));
    assert.ok(!f.calls[2].url.includes('waitForRegistration'));
    assert.ok(f.calls.every(call => !call.options.method));
    f.monitor.stop();
});

test('a known run disappearing is not reported as pending admission', async () => {
    let count = 0;
    const f = fixture(async () => response(++count === 1 ? snapshot() : {}, count === 1 ? 200 : 404));
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.unavailable, ['HTTP 404']);
    assert.equal(f.snapshots.length, 1);
    assert.ok(!f.calls[1].url.includes('waitForRegistration'));
    f.monitor.stop();
});
`);
}
for(const [path,sha] of Object.entries(paths)) assert.equal(hash(readFileSync(path,'utf8')),sha,path);
if(process.argv[2]==='publish') {
 for(const [path,sha] of Object.entries(paths)) {
  const r=await fetch('https://api.github.com/repos/carstenartur/Taxonomy/git/blobs',{
   method:'POST',headers:{Authorization:'Bearer '+process.env.GH_TOKEN,'Content-Type':'application/json',Accept:'application/vnd.github+json'},
   body:JSON.stringify({content:readFileSync(path,'utf8'),encoding:'utf-8'})});
  assert.equal(r.status,201); assert.equal((await r.json()).sha,sha);
 }
 writeFileSync('target/admission-manifest.json',JSON.stringify({base:'8eeb3f6ae8f9e6982bc6a1f89b86dfa69907b83b',files:paths},null,2));
}
