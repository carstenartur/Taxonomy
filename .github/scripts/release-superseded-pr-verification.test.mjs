import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {releaseSuperseded} from './release-superseded-pr-verification.mjs';

function fixture() {
    const repository = {id: 42, default_branch: 'main'};
    const defaultRef = {object: {sha: 'e'.repeat(40)}};
    const branch = 'feature/architecture';
    const pr = {number: 7, state: 'open', head: {ref: branch, repo: repository, sha: 'b'.repeat(40)},
        base: {repo: repository, sha: 'e'.repeat(40)}};
    const common = {repository, head_repository: repository, head_branch: branch, pull_requests: [pr],
        path: '.github/workflows/ci-cd.yml', event: 'pull_request', workflow_id: 10, run_attempt: 1, conclusion: null};
    const current = {...common, id: 222, run_number: 12, head_sha: pr.head.sha, status: 'pending'};
    const old = {...common, id: 111, run_number: 11, head_sha: 'a'.repeat(40), status: 'queued'};
    const evidence = [
        {run_id: 111, name:'Core reactor verification', status:'completed', conclusion:'cancelled'},
        {run_id: 111, name:'Maven verification', status:'queued', conclusion:null, runner_id:0, runner_name:'', steps:[]}
    ];
    const event = {action:'requested', repository, workflow_run: structuredClone(current)};
    const finalizer = evidence[1], posts = [], waits = [], open = [pr];
    const api = {
        get(path) {
            if (path === 'actions/runs/111') return structuredClone(old);
            if (path === 'actions/runs/222') return structuredClone(current);
            if (path === 'git/ref/heads/main') return structuredClone(defaultRef);
            assert.equal(path, 'pulls/7');
            return structuredClone(pr);
        },
        pages(path, key) {
            if (path === 'actions/runs/111/attempts/1/jobs') { assert.equal(key, 'jobs'); return structuredClone(evidence); }
            if (path === 'pulls?state=open') return structuredClone(open);
            assert.match(path, /^actions\/workflows\/10\/runs\?event=pull_request&branch=feature%2Farchitecture&status=(queued|in_progress)$/);
            assert.equal(key, 'workflow_runs');
            return path.endsWith('=queued') ? structuredClone([old, current]) : [];
        },
        post(path) { posts.push(path); }
    };
    return {event, current, old, evidence, finalizer, open, pr, defaultRef, posts, waits, api,
        async run(afterWait) { await releaseSuperseded(event, api, ms => { waits.push(ms); afterWait?.(); }); }};
}

test('a requested pending canonical PR run releases only its superseded, runnerless finalizer', async () => {
    const f = fixture(); await f.run();
    assert.deepEqual(f.posts, ['actions/runs/111/force-cancel']);
    assert.deepEqual(f.waits, []);
});
test('a pending new run tolerates late registration of the old finalizer', async () => {
    const f = fixture(); f.evidence.pop();
    await f.run(() => f.evidence.push(f.finalizer));
    assert.deepEqual(f.waits, [10000]);
    assert.deepEqual(f.posts, ['actions/runs/111/force-cancel']);
});
test('ordinary active work, an assigned finalizer and an uncancelled predecessor are preserved', async () => {
    for (const mutate of [f => { f.evidence[0].status='in_progress'; },
        f => { f.finalizer.runner_id=23; }, f => { f.evidence[0].conclusion='success'; }]) {
        const f = fixture(); mutate(f); await f.run();
        assert.deepEqual(f.posts, []);
        assert.equal(f.waits.length, 3);
    }
});
test('current revisions, foreign pulls, other workflows, pushes, dispatches and later runs are preserved', async () => {
    for (const mutate of [f => { f.old.head_sha=f.current.head_sha; }, f => { f.old.pull_requests=[{...f.pr, number:8}]; },
        f => { f.old.workflow_id=99; }, f => { f.old.path='.github/workflows/release.yml'; },
        f => { f.old.event='push'; }, f => { f.event.workflow_run.event='workflow_dispatch'; },
        f => { f.old.run_number=f.current.run_number+1; }, f => { f.old.head_repository={id:99}; }]) {
        const f = fixture(); mutate(f); await f.run();
        assert.deepEqual(f.posts, []); assert.deepEqual(f.waits, []);
    }
});
test('protect the actual default and open PR heads, while permitting a stacked PR’s obsolete recorded base', async () => {
    const main=fixture(); main.defaultRef.object.sha=main.old.head_sha; await main.run();
    assert.deepEqual(main.posts, []);
    const shared=fixture(); shared.open.push({head:{sha:shared.old.head_sha}}); await shared.run();
    assert.deepEqual(shared.posts, []);
    const stacked=fixture();
    stacked.open.push({number:8, head:{sha:'c'.repeat(40)}, base:{ref:stacked.current.head_branch, sha:stacked.old.head_sha}});
    await stacked.run();
    assert.deepEqual(stacked.posts, ['actions/runs/111/force-cancel']);
});
test('re-reads prevent cancellation after a force-push, a new attempt, a replacement start, or runner pickup', async () => {
    for (const mutate of [f => {f.pr.head.sha=f.old.head_sha;}, f => {f.old.run_attempt++;},
        f => {f.current.status='in_progress';}, f => {f.finalizer.runner_id=45;}]) {
        const f=fixture(), pages=f.api.pages;
        f.api.pages=(path,key) => {
            const response=pages(path,key);
            if (path==='pulls?state=open') mutate(f);
            return response;
        };
        await f.run(); assert.deepEqual(f.posts, []);
    }
});
test('a verification request can run only trusted maintenance and cannot select retention', () => {
    const workflow = readFileSync(new URL('../workflows/cleanup-workflow-runs.yml', import.meta.url),'utf8');
    assert.match(workflow, /workflow_run:\s+workflows: \['CI \/ CD'\]\s+types: \[requested\]/);
    const [maintenance, retention] = workflow.split(/^  cleanup:$/m);
    assert.match(maintenance, /ref: \$\{\{ github\.sha \}\}/);
    assert.doesNotMatch(maintenance, /run: bash .*cleanup-workflow-runs/);
    assert.match(retention, /^\s+if: github\.event_name == 'schedule' \|\| github\.event_name == 'workflow_dispatch'/);
});
