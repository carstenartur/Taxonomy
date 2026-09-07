import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

import {
    classifyReview,
    evaluateExactHeadReview,
    GitHubClient,
    loadHumanPermissions,
    loadHumanEvidence,
    normalizeLogin,
    parseReviewCommentCount,
    parseReviewCoverage,
    parseReviewerLogins,
    parseReviewConfirmation
} from './exact-head-review-gate.mjs';
import {
    eventPullRequests, latestPullRequestRun, refreshPullRequest, verificationJobToRefresh
} from './refresh-review-gate.mjs';

const HEAD = 'a'.repeat(40);
const REVIEWERS = parseReviewerLogins('copilot-pull-request-reviewer[bot]');

function pullRequest(overrides = {}) {
    return {
        number: 933,
        state: 'open',
        draft: false,
        title: 'Example',
        html_url: 'https://github.example/pull/933',
        head: { sha: HEAD },
        base: { ref: 'main' },
        changed_files: 2,
        merged_at: null,
        ...overrides
    };
}

function review(body, overrides = {}) {
    return {
        id: 101,
        user: { login: 'copilot-pull-request-reviewer[bot]' },
        state: 'COMMENTED',
        commit_id: HEAD,
        submitted_at: '2026-09-01T10:00:00Z',
        body,
        ...overrides
    };
}

const APPROVAL = `### 🟢 Approval recommended

<details>
<summary>Review details</summary>

- **Files reviewed:** 2/2 changed files
- **Comments generated:** 0
</details>`;

const CHANGES = `### 🟡 Changes recommended

- **Files reviewed:** 2/2 changed files
- **Comments generated:** 1`;

const CLOSER = `### 🔵 Needs a closer look

- **Files reviewed:** 2/2 changed files
- **Comments generated:** 1`;

test('normalizes bot logins and reviewer configuration', () => {
    assert.equal(normalizeLogin('Copilot-Pull-Request-Reviewer[bot]'),
        'copilot-pull-request-reviewer');
    assert.deepEqual(
        [...parseReviewerLogins(' Copilot[bot],reviewer ')],
        ['copilot', 'reviewer']);
});

const CLEAN_CLOSER = APPROVAL.replace('Approval recommended', 'Needs a closer look');
const HUMAN = { login: 'maintainer', type: 'User' };
const PERMISSIONS = new Map([['maintainer', 'admin']]);
function confirmation(overrides = {}) {
    return {
        id: 201, node_id: 'IC_fixture', user: HUMAN, last_edited_at: null,
        body: `/confirm-review ${HEAD} 101`,
        created_at: '2026-09-01T10:01:00Z', updated_at: '2026-09-01T10:01:00Z',
        html_url: 'https://github.example/issuecomment-201', ...overrides
    };
}
function humanGate(overrides = {}) {
    return evaluateExactHeadReview({
        pullRequest: pullRequest({ user: HUMAN }), reviews: [review(CLEAN_CLOSER)],
        threads: [], comments: [confirmation()], humanPermissions: PERMISSIONS,
        expectedHeadSha: HEAD, reviewerLogins: REVIEWERS, ...overrides
    });
}

test('maintainer author can explicitly confirm an exact-head closer-look review', () => {
    const gate = humanGate();
    assert.equal(gate.status, 'passed');
    assert.equal(gate.humanConfirmation.source, 'issue_comment');
    assert.equal(gate.humanConfirmation.login, HUMAN.login);
    assert.equal(gate.humanConfirmation.headSha, HEAD);
    assert.equal(gate.humanConfirmation.reviewId, '101');
});

test('uppercase SHA input is normalized before matching the exact current head', () => {
    const body = `/confirm-review ${HEAD.toUpperCase()} 101`;
    assert.deepEqual(parseReviewConfirmation(body), { headSha: HEAD, reviewId: '101' });
    assert.equal(humanGate({ comments: [confirmation({ body })] }).status, 'passed');
});

test('missing confirmation explains the exact command for the current review', () => {
    const gate = humanGate({ comments: [] });
    assert.equal(gate.code, 'CLOSER_REVIEW_REQUIRED');
    assert.match(gate.message, new RegExp(`/confirm-review ${HEAD} 101`, 'u'));
});

for (const [name, overrides] of Object.entries({
    'old head': { body: `/confirm-review ${'b'.repeat(40)} 101` },
    'old review': { body: `/confirm-review ${HEAD} 100` },
    'abbreviated head': { body: `/confirm-review ${HEAD.slice(0, 7)} 101` },
    'quoted command': { body: `> /confirm-review ${HEAD} 101` },
    'embedded command': { body: `I agree\n/confirm-review ${HEAD} 101` },
    'edited comment': { updated_at: '2026-09-01T10:02:00Z' },
    'edit within creation second': { last_edited_at: '2026-09-01T10:01:00Z' },
    'unverified edit history': { last_edited_at: undefined },
    'confirmation before review': {
        created_at: '2026-09-01T09:59:00Z', updated_at: '2026-09-01T09:59:00Z'
    },
    'missing creation time': { created_at: undefined, updated_at: undefined },
    'bot comment': { user: { login: 'maintainer', type: 'Bot' } },
    'app acting for user': { performed_via_github_app: { id: 3 } },
    'author association without permission': {
        user: { login: 'outsider', type: 'User' }, author_association: 'OWNER'
    }
})) {
    test(`rejects ${name}`, () => {
        assert.equal(humanGate({ comments: [confirmation(overrides)] }).status, 'blocked');
    });
}

test('only current repository write, maintain or admin access can confirm', () => {
    for (const permission of ['none', 'read', 'triage', undefined]) {
        assert.equal(humanGate({
            humanPermissions: new Map([['maintainer', permission]])
        }).status, 'blocked');
    }
    for (const permission of ['write', 'maintain', 'admin']) {
        assert.equal(humanGate({
            humanPermissions: new Map([['maintainer', permission]])
        }).status, 'passed');
    }
});

test('deleting the only confirmation revokes it; a new Copilot review needs new confirmation', () => {
    assert.equal(humanGate({ comments: [] }).status, 'blocked');
    assert.equal(humanGate({ reviews: [review(CLEAN_CLOSER, {
        id: 102, submitted_at: '2026-09-01T10:02:00Z'
    })] }).status, 'blocked');
});

for (const [name, overrides, code] of [
    ['changes', { reviews: [review(CHANGES)] }, 'CHANGES_RECOMMENDED'],
    ['partial coverage', { reviews: [review(CLEAN_CLOSER.replace('2/2', '1/2'))] }, 'REVIEW_FILE_COVERAGE_INCOMPLETE'],
    ['changed-file mismatch', { pullRequest: pullRequest({ changed_files: 3 }) }, 'REVIEW_FILE_COVERAGE_INCOMPLETE'],
    ['generated comments', { reviews: [review(CLOSER)] }, 'REVIEW_FOLLOW_UP_REQUIRED'],
    ['missing comment count', { reviews: [review(CLEAN_CLOSER.replace('Comments generated', 'Notes'))] }, 'REVIEW_COMMENT_COUNT_MISSING'],
    ['unresolved thread', { threads: [{ isResolved: false, isOutdated: false }] }, 'UNRESOLVED_REVIEW_THREADS'],
    ['draft PR', { pullRequest: pullRequest({ draft: true }) }, 'PULL_REQUEST_IS_DRAFT'],
    ['closed PR', { pullRequest: pullRequest({ state: 'closed' }) }, 'PULL_REQUEST_NOT_OPEN'],
    ['advanced head', { pullRequest: pullRequest({ head: { sha: 'b'.repeat(40) } }) }, 'STALE_REVIEW_GATE_RUN'],
    ['dismissed bot review', { reviews: [review(CLEAN_CLOSER, { state: 'DISMISSED' })] }, 'EXACT_HEAD_REVIEW_MISSING']
]) {
    test(`human confirmation cannot override ${name}`, () => {
        assert.equal(humanGate(overrides).code, code);
    });
}

test('a subsequent genuine peer approval satisfies closer review without bot metadata', () => {
    const approval = review('Reviewed the complete change and risk areas.', {
        id: 202, user: HUMAN, state: 'APPROVED', submitted_at: '2026-09-01T10:02:00Z'
    });
    const gate = humanGate({
        pullRequest: pullRequest({ user: { login: 'author', type: 'User' } }),
        comments: [], reviews: [review(CLEAN_CLOSER), approval]
    });
    assert.equal(gate.status, 'passed');
    assert.equal(gate.humanConfirmation.source, 'pull_request_review');
    assert.equal(humanGate({ comments: [], reviews: [review(CLEAN_CLOSER), approval] }).status,
        'blocked', 'native self-approval is not accepted');
});

test('human changes requested block even Copilot approval and maintainer confirmation', () => {
    const objection = review('Fix the access control.', {
        id: 202, user: HUMAN, state: 'CHANGES_REQUESTED', submitted_at: '2026-09-01T10:02:00Z'
    });
    for (const body of [CLEAN_CLOSER, APPROVAL]) {
        assert.equal(humanGate({ reviews: [review(body), objection] }).code, 'HUMAN_CHANGES_REQUESTED');
    }
    assert.equal(humanGate({ reviews: [review(CLEAN_CLOSER), {
        ...objection, state: 'DISMISSED'
    }] }).status, 'passed');
});

test('a dismissed human approval cannot resurrect an earlier approval', () => {
    const approval = review('Approved.', { id: 202, user: HUMAN, state: 'APPROVED', submitted_at: '2026-09-01T10:02:00Z' });
    const dismissed = { ...approval, id: 203, state: 'DISMISSED', submitted_at: '2026-09-01T10:03:00Z' };
    assert.equal(humanGate({
        pullRequest: pullRequest({ user: { login: 'author' } }),
        reviews: [review(CLEAN_CLOSER), approval, dismissed], comments: []
    }).status, 'blocked');
});

test('permission lookup uses API permissions, deduplicates principals and ignores ordinary comments', async () => {
    const paths = [];
    const client = { repository: 'owner/repo', request: async path => {
        paths.push(path); return { permission: 'write' };
    } };
    const permissions = await loadHumanPermissions(client, {
        reviews: [], headSha: HEAD,
        comments: [confirmation(), confirmation(), confirmation({ body: 'hello', user: { login: 'other', type: 'User' } })]
    });
    assert.deepEqual(paths, ['/repos/owner/repo/collaborators/maintainer/permission']);
    assert.equal(permissions.get('maintainer'), 'write');
    client.request = async () => { throw Object.assign(new Error('missing'), { status: 404 }); };
    assert.equal((await loadHumanPermissions(client, {
        reviews: [], comments: [confirmation()], headSha: HEAD
    })).get('maintainer'), 'none');
    client.request = async () => { throw Object.assign(new Error('denied'), { status: 403 }); };
    await assert.rejects(loadHumanPermissions(client, {
        reviews: [], comments: [confirmation()], headSha: HEAD
    }), /denied/u);
});

test('comment pagination includes confirmations after the first page and fails closed at its bound', async () => {
    const client = new GitHubClient('fixture', 'owner/repo');
    const first = Array.from({ length: 100 }, (_, id) => ({ id, body: 'ordinary comment' }));
    client.request = async path => path.endsWith('page=1') ? first : [confirmation()];
    assert.equal((await client.issueComments(1)).at(-1).id, 201);
    client.request = async () => first;
    await assert.rejects(client.issueComments(1), /exceeded/u);
    assert.equal(parseReviewConfirmation('```\n/confirm-review ' + HEAD + ' 101\n```'), null);
});

const CI_RUN = {
    id: 301, run_attempt: 1, status: 'completed', event: 'pull_request',
    path: '.github/workflows/ci-cd.yml', head_sha: HEAD,
    pull_requests: [{ number: 933, head: { sha: HEAD },
        base: { sha: 'b'.repeat(40), ref: 'main', repo: { id: 1 } } }]
};
const CI_PR = pullRequest({ base: { ref: 'main', repo: { id: 1 } } });
const FINAL_JOB = {
    id: 401, run_id: 301, name: 'Maven verification', status: 'completed', conclusion: 'failure',
    steps: [
        { name: 'Require every authoritative lane', conclusion: 'success' },
        { name: 'Verify complete digest-bound UI evidence', conclusion: 'success' },
        { name: 'Require complete review of the exact pull-request head', conclusion: 'failure' }
    ]
};

test('refresh selects only the latest matching pull-request CI, including running attempts', () => {
    const unrelated = [
        { ...CI_RUN, id: 999, event: 'push' },
        { ...CI_RUN, id: 998, head_sha: 'c'.repeat(40) },
        { ...CI_RUN, id: 997, path: '.github/workflows/other.yml' },
        { ...CI_RUN, id: 996, pull_requests: [] }
    ];
    assert.equal(latestPullRequestRun([...unrelated, CI_RUN], CI_PR).id, 301);
    assert.equal(latestPullRequestRun([CI_RUN, { ...CI_RUN, id: 302, status: 'in_progress' }], CI_PR).id, 302);
});

test('refresh only retries the final review failure with successful authoritative evidence', () => {
    assert.equal(verificationJobToRefresh(CI_RUN, [FINAL_JOB], { status: 'passed' }).id, 401);
    for (const job of [
        { ...FINAL_JOB, run_id: 999 }, { ...FINAL_JOB, status: 'in_progress' },
        { ...FINAL_JOB, steps: FINAL_JOB.steps.slice(1) },
        { ...FINAL_JOB, steps: FINAL_JOB.steps.map(step => ({ ...step, conclusion: 'failure' })) },
        { ...FINAL_JOB, steps: FINAL_JOB.steps.map(step => ({ ...step, conclusion: 'success' })) }
    ]) {
        assert.equal(verificationJobToRefresh(CI_RUN, [job], { status: 'passed' }), null);
    }
    assert.equal(verificationJobToRefresh({ ...CI_RUN, status: 'in_progress' }, [FINAL_JOB], { status: 'passed' }), null);
    assert.equal(verificationJobToRefresh(CI_RUN, [FINAL_JOB], { status: 'blocked' }), null);
});

test('revocation refreshes a successful final job once and does not loop on its failed retry', () => {
    const success = { ...FINAL_JOB, conclusion: 'success', steps: FINAL_JOB.steps.map(step => ({ ...step, conclusion: 'success' })) };
    assert.equal(verificationJobToRefresh(CI_RUN, [success], { status: 'blocked' }).id, 401);
    assert.equal(verificationJobToRefresh(CI_RUN, [success], { status: 'passed' }), null);
    assert.equal(verificationJobToRefresh(CI_RUN, [success, { ...FINAL_JOB, id: 402 }], { status: 'blocked' }), null);
});

test('refresh leaves old-base policy and ongoing CI untouched without writes', async () => {
    const client = { repository: 'owner/repo', request: async (path, options) => {
        assert.equal(options?.method, undefined);
        if (path.endsWith('/pulls/933')) return CI_PR;
        if (path.includes('/runs?')) return { total_count: 1, workflow_runs: [CI_RUN] };
        if (path.includes('/contents/')) return { encoding: 'base64', content: Buffer.from('// old policy').toString('base64') };
        throw new Error(`Unexpected API call: ${path}`);
    } };
    assert.match(await refreshPullRequest(client, 933), /update the PR from main/u);
    assert.deepEqual(await eventPullRequests(client, 'issue_comment', { issue: { number: 933 } }), []);
    assert.deepEqual(await eventPullRequests(client, 'issue_comment', { issue: { number: 933, pull_request: {} } }), [933]);
    assert.deepEqual(await eventPullRequests(client, 'workflow_run', { workflow_run: { ...CI_RUN, event: 'push' } }), []);
});

test('refresh workflow executes default-branch code with narrowly scoped write permission', async () => {
    const workflow = await readFile(new URL('../workflows/refresh-review-gate.yml', import.meta.url), 'utf8');
    assert.match(workflow, /ref: \$\{\{ github.event.repository.default_branch \}\}/u);
    assert.match(workflow, /pull-requests: read\n      actions: write/u);
    assert.doesNotMatch(workflow, /pull_request_target:|pull_request_review:|actions\/download-artifact|actions\/cache|contents: write/u);
    assert.match(workflow, /types: \[created, edited, deleted\]/u);
    assert.match(workflow, /workflows: \['CI \/ CD'\]/u);
    const ci = await readFile(new URL('../workflows/ci-cd.yml', import.meta.url), 'utf8');
    const reviewers = text => text.match(/REVIEW_GATE_REVIEWERS: '([^']+)'/u)?.[1];
    assert.ok(reviewers(ci));
    assert.equal(reviewers(workflow), reviewers(ci));
});

function mockGitHub(t, { editedAt = null, advanceHead = false, newRun = false, graphqlError = false } = {}) {
    const writes = [];
    let prReads = 0;
    let runListReads = 0;
    t.mock.method(globalThis, 'fetch', async (url, options = {}) => {
        assert.equal(options.headers.Authorization, 'Bearer fixture');
        const path = new URL(url).pathname;
        let payload;
        if (path === '/graphql') {
            const body = JSON.parse(options.body);
            if (body.query.includes('ConfirmationEdits')) {
                assert.deepEqual(body.variables.ids, ['IC_fixture']);
                payload = graphqlError ? { errors: [{ message: 'denied' }] }
                    : { data: { nodes: [{ id: 'IC_fixture', lastEditedAt: editedAt }] } };
            } else {
                payload = { data: { repository: { pullRequest: { reviewThreads: {
                    pageInfo: { hasNextPage: false }, nodes: []
                } } } } };
            }
        } else if (path.endsWith('/pulls/933')) {
            prReads++;
            payload = advanceHead && prReads >= 3
                ? { ...CI_PR, head: { sha: 'c'.repeat(40) } } : CI_PR;
        } else if (path.endsWith('/reviews')) {
            payload = [review(CLEAN_CLOSER)];
        } else if (path.endsWith('/comments')) {
            const { last_edited_at, ...restComment } = confirmation();
            payload = [restComment];
        } else if (path.endsWith('/collaborators/maintainer/permission')) {
            payload = { permission: 'admin' };
        } else if (path.endsWith('/workflows/ci-cd.yml/runs')) {
            runListReads++;
            payload = { total_count: 1, workflow_runs: newRun && runListReads >= 2
                ? [{ ...CI_RUN, id: 302, status: 'in_progress' }, CI_RUN] : [CI_RUN] };
        } else if (path.includes('/contents/')) {
            payload = { encoding: 'base64', content: Buffer.from('export const HUMAN_CONFIRMATION_POLICY_VERSION = 1;').toString('base64') };
        } else if (path.endsWith('/runs/301/jobs')) {
            payload = { total_count: 1, jobs: [FINAL_JOB] };
        } else if (path.endsWith('/runs/301')) {
            payload = CI_RUN;
        } else if (path.endsWith('/jobs/401/rerun')) {
            assert.equal(options.method, 'POST');
            writes.push(path);
            return new Response(null, { status: 201 });
        } else {
            throw new Error(`Unexpected GitHub request: ${path}`);
        }
        return new Response(JSON.stringify(payload), { status: 200 });
    });
    return { client: new GitHubClient('fixture', 'owner/repo'), writes };
}

test('live REST and GraphQL evidence causes exactly one final-job rerun', async t => {
    const { client, writes } = mockGitHub(t);
    assert.match(await refreshPullRequest(client, 933), /rerunning Maven verification job 401/u);
    assert.deepEqual(writes, ['/repos/owner/repo/actions/jobs/401/rerun']);
});

test('live refresh honors the configured trusted reviewer set', async t => {
    const { client, writes } = mockGitHub(t);
    const result = await refreshPullRequest(client, 933, parseReviewerLogins('different-reviewer[bot]'));
    assert.match(result, /EXACT_HEAD_REVIEW_MISSING/u);
    assert.deepEqual(writes, []);
});

for (const [name, options] of [
    ['same-second edit', { editedAt: confirmation().created_at }],
    ['head advancing before rerun', { advanceHead: true }],
    ['new CI starting before rerun', { newRun: true }]
]) {
    test(`live refresh does not write after ${name}`, async t => {
        const { client, writes } = mockGitHub(t, options);
        await refreshPullRequest(client, 933);
        assert.deepEqual(writes, []);
    });
}

test('edit-history API failures fail closed instead of accepting REST timestamps', async t => {
    const { client, writes } = mockGitHub(t, { graphqlError: true });
    await assert.rejects(loadHumanEvidence(client, {
        reviews: [], comments: [confirmation()], headSha: HEAD
    }), /edit history/u);
    assert.deepEqual(writes, []);
});

test('parses the changed-file coverage and comment count published by Copilot', () => {
    assert.deepEqual(parseReviewCoverage(APPROVAL), { reviewed: 2, total: 2 });
    assert.equal(parseReviewCoverage('no coverage'), null);
    assert.equal(parseReviewCommentCount(APPROVAL), 0);
    assert.equal(parseReviewCommentCount('no count'), null);
});

test('classifies explicit review outcomes', () => {
    assert.equal(classifyReview(review(APPROVAL)), 'approval-recommended');
    assert.equal(classifyReview(review(CHANGES)), 'changes-recommended');
    assert.equal(classifyReview(review(CLOSER)), 'needs-closer-look');
});

test('passes only a complete approval review of the exact current head', () => {
    const result = evaluateExactHeadReview({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL)],
        threads: [],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(result.status, 'passed');
    assert.equal(result.code, 'EXACT_HEAD_REVIEW_COMPLETE');
});

test('keeps waiting when only a stale-head review exists', () => {
    const result = evaluateExactHeadReview({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL, { commit_id: 'b'.repeat(40) })],
        threads: [],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(result.status, 'pending');
    assert.equal(result.code, 'EXACT_HEAD_REVIEW_MISSING');
});

test('blocks changes recommended and closer-look outcomes', () => {
    for (const body of [CHANGES, CLOSER]) {
        const result = evaluateExactHeadReview({
            pullRequest: pullRequest(),
            reviews: [review(body)],
            threads: [],
            expectedHeadSha: HEAD,
            reviewerLogins: REVIEWERS
        });
        assert.equal(result.status, 'blocked');
    }
});

test('blocks partial file coverage', () => {
    const result = evaluateExactHeadReview({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL.replace('2/2', '1/2'))],
        threads: [],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(result.code, 'REVIEW_FILE_COVERAGE_INCOMPLETE');
});

test('blocks a review whose reported total differs from the pull request', () => {
    const result = evaluateExactHeadReview({
        pullRequest: pullRequest({ changed_files: 3 }),
        reviews: [review(APPROVAL)],
        threads: [],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(result.code, 'REVIEW_FILE_COVERAGE_INCOMPLETE');
});

test('requires a fresh comment-free exact-head review', () => {
    const result = evaluateExactHeadReview({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL.replace('Comments generated:** 0',
            'Comments generated:** 2'))],
        threads: [],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(result.code, 'REVIEW_FOLLOW_UP_REQUIRED');
});

test('blocks unresolved current threads but ignores outdated threads', () => {
    const blocked = evaluateExactHeadReview({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL)],
        threads: [{ isResolved: false, isOutdated: false, path: 'A.java' }],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(blocked.code, 'UNRESOLVED_REVIEW_THREADS');

    const passed = evaluateExactHeadReview({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL)],
        threads: [{ isResolved: false, isOutdated: true, path: 'A.java' }],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(passed.status, 'passed');
});

test('blocks a stale workflow after the pull-request head advances', () => {
    const result = evaluateExactHeadReview({
        pullRequest: pullRequest({ head: { sha: 'b'.repeat(40) } }),
        reviews: [review(APPROVAL)],
        threads: [],
        expectedHeadSha: HEAD,
        reviewerLogins: REVIEWERS
    });
    assert.equal(result.code, 'STALE_REVIEW_GATE_RUN');
});

test('required Maven verification invokes the trusted exact-head gate', async () => {
    const workflow = await readFile(
        new URL('../workflows/ci-cd.yml', import.meta.url), 'utf8');
    assert.match(workflow,
        /types: \[opened, synchronize, reopened, ready_for_review, converted_to_draft\]/u);
    assert.match(workflow,
        /name: Keep draft heads non-mergeable/u);
    assert.match(workflow,
        /github\.event\.pull_request\.draft == true/u);
    assert.match(workflow,
        /name: Require complete review of the exact pull-request head/u);
    assert.match(workflow,
        /pull-requests: read/u);
    assert.match(workflow,
        /git show "\$\{\{ github\.event\.pull_request\.base\.sha \}\}:\$gate"/u);
    assert.match(workflow,
        /\.cache\/ui-frontend\/node\/node "\$gate"/u);
});
