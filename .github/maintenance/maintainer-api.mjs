import assert from 'node:assert/strict';
export const repository = 'carstenartur/Taxonomy';
export const mergeHead = '3eac42dbb992008cbb05b18c8e7814a19743c9db';
const reviewedFiles = [
  '.github/scripts/stage-core-quality-reports.sh',
  'taxonomy-build/src/test/java/com/taxonomy/workspace/storage/JgitStorageDocumentationContractTest.java',
  'taxonomy-workspace/pom.xml',
  'taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/WorkspaceManagerConcurrencyTest.java'
];
export async function mergeReviewed(request, delay = async () => {}) {
  const pr = await request('/pulls/1125');
  assert.equal(pr.head.sha, mergeHead, 'Reviewed head changed');
  if (pr.merged) return { status: 'merged', sha: pr.merge_commit_sha };
  assert.equal(pr.state, 'open'); assert.equal(pr.draft, false);
  assert.equal(pr.base.ref, 'main'); assert.equal(pr.head.repo.full_name, repository);
  const files = await request('/pulls/1125/files?per_page=100');
  assert.deepEqual(files.map(f => f.filename).sort(), [...reviewedFiles].sort(), 'Reviewed diff changed');
  if (pr.stack) {
    const stack = await request('/stacks/' + pr.stack.number);
    assert.equal(stack.base.ref, 'main');
    const index = stack.pull_requests.findIndex(p => p.number === 1125);
    assert(index >= 0, 'PR missing from stack');
    assert(stack.pull_requests.slice(0, index).every(p => p.merged_at), 'Unreviewed lower PR would also merge');
  }
  const data = await request('/actions/runs?event=pull_request&head_sha=' + mergeHead + '&per_page=100');
  assert(data.total_count <= 100, 'Paginate workflow runs before deciding');
  const latest = new Map();
  for (const r of [...data.workflow_runs].sort((a,b) => b.id-a.id)) if (!latest.has(r.name)) latest.set(r.name, r);
  for (const name of ['CI / CD','Database Compatibility','JGit Storage Consumer Contract','Security Scan',
    'Civilian architecture acceptance','Kubernetes Constrained Smoke','Document Template Report E2E']) {
    assert.equal(latest.get(name)?.conclusion, 'success', name + ' is not successful');
  }
  assert([...latest.values()].every(r => r.status === 'completed' && ['success','skipped'].includes(r.conclusion)), 'Workflow not complete/green');
  const checks = await request('/commits/' + mergeHead + '/check-runs?per_page=100');
  assert(checks.total_count > 0 && checks.total_count <= 100, 'Missing or truncated check evidence');
  assert(checks.check_runs.every(c => c.status === 'completed' && ['success','neutral','skipped'].includes(c.conclusion)), 'Failing or pending check');
  const reviews = await request('/pulls/1125/reviews?per_page=100');
  assert(reviews.length < 100, 'Paginate reviews before deciding');
  const latestReview = new Map();
  for (const r of reviews) if (['APPROVED','CHANGES_REQUESTED','DISMISSED'].includes(r.state)) latestReview.set(r.user.login,r);
  assert(![...latestReview.values()].some(r => r.state === 'CHANGES_REQUESTED'), 'Changes still requested');
  let result = await request('/pulls/1125/merge-async', 'PUT', {
    sha: mergeHead, merge_method: 'squash', merge_action: 'default',
    commit_title: 'fix(ci): harden report collection and workspace test contracts (#1125)'
  });
  const uuid = result.details?.uuid;
  for (let i=0; result.status === 'pending' && uuid && i<30; i++) {
    await delay(2000);
    result = await request('/pulls/1125/merge-async/' + encodeURIComponent(uuid));
  }
  return result;
}
export async function approveReviewed(request) {
  const head = '613df366714e677f8b57b43d65cf1ffd6db6dfeb';
  const pr = await request('/pulls/1129');
  assert.equal(pr.head.sha, head); assert.equal(pr.state,'open');
  assert.equal(pr.head.repo.full_name, repository);
  const files = await request('/pulls/1129/files?per_page=100');
  const root='taxonomy-app/src/test/java/com/taxonomy/portfolio/reformulation/';
  assert.deepEqual(files.map(f => f.filename).sort(), [root+'ReformulationAdoptionSchemaContract.java',root+'ReformulationCheckpointIndexContract.java']);
  const results=[];
  for (const id of [36228015488,36228015492,36228015502,36228015510,36228015506,36228015500,36228015556]) {
    const run = await request('/actions/runs/'+id);
    assert.equal(run.head_sha,head); assert.equal(run.event,'pull_request');
    if (run.conclusion === 'action_required') results.push({id, response: await request('/actions/runs/'+id+'/approve','POST')});
    else results.push({id, status:run.status, conclusion:run.conclusion});
  }
  return results;
}
