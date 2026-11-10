import test from 'node:test';
import assert from 'node:assert/strict';
import { createPendingProposalFixtures, primaryProposalCandidates } from './ui-proposal-fixtures.mjs';
import { createReviewCandidate } from './ui-role-state-flow.mjs';

test('primary lifecycle receives four distinct proposal identities and retains valid conflict fallbacks', async () => {
  const submitted = [];
  const ids = await createPendingProposalFixtures(async body => {
    submitted.push(body);
    return submitted.length <= 2 ? {status:409} : {status:200,json:{id:submitted.length}};
  }, assert.ok);
  assert.deepEqual(ids,[3,4,5,6]);
  assert.deepEqual(submitted.map(body=>[body.sourceCode,body.targetCode,body.relationType]),primaryProposalCandidates);
  assert.ok(submitted.every(body=>body.confidence===0.73&&body.rationale.startsWith('Primary workflow ')));
});

test('repeated response IDs cannot masquerade as four independent lifecycle proposals', async () => {
  await assert.rejects(createPendingProposalFixtures(async()=>({status:200,json:{id:7}}),assert.ok),
    /four independent proposals.*created 1/);
});

test('an unexpected rejected hypothesis fails with its response evidence before another proposal is attempted', async () => {
  let requests = 0;
  await assert.rejects(createPendingProposalFixtures(async()=>{
    requests++;
    return requests===1 ? {status:400,text:'incompatible endpoint types'} : {status:200,json:{id:requests}};
  },assert.ok),/400.*incompatible endpoint types/);
  assert.equal(requests,1);
});

for (const role of ['ARCHITECT','ADMIN']) {
  test(`${role} role-state conflicts advance to another legal RELATED_TO pair`, async () => {
    const page = {locator:selector=>{
      assert.equal(selector,'#taxonomyTree .tax-node[data-code]');
      return {evaluateAll:async callback=>callback([
        {dataset:{code:'BP-1000'}},{dataset:{code:'BP-1001'}},{dataset:{code:'BP-1002'}}])};
    }};
    const attempts=[];
    const result=await createReviewCandidate(page,role,async(_page,path,{body})=>{
      assert.equal(path,'/api/proposals/from-hypothesis');
      attempts.push(body);
      assert.equal(body.relationType,'RELATED_TO');
      return attempts.length===1 ? {status:409} : {status:200,json:{id:9}};
    });
    assert.equal(result.json.id,9);
    assert.deepEqual(attempts.map(body=>[body.sourceCode,body.targetCode]),
      [['BP-1000','BP-1001'],['BP-1000','BP-1002']]);
    assert.ok(result.rationale.startsWith('Role acceptance '));
  });
}

test('USER role-state setup preserves the denied request without acquiring proposal write authority', async () => {
  let requests=0;
  const response=await createReviewCandidate({},'USER',async(_page,_path,{body})=>{
    requests++;
    assert.equal(body.relationType,'RELATED_TO');
    return {status:403,text:'Forbidden'};
  });
  assert.equal(response.status,403);
  assert.equal(requests,1);
});
