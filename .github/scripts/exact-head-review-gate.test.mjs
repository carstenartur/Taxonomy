import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

import {
    classifyReview,
    evaluateExactHeadReview,
    normalizeLogin,
    parseReviewCoverage,
    parseReviewerLogins
} from './exact-head-review-gate.mjs';

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
        merged_at: null,
        ...overrides
    };
}

function review(body, overrides = {}) {
    return {
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
</details>`;

const CHANGES = `### 🟡 Changes recommended

- **Files reviewed:** 2/2 changed files`;

const CLOSER = `### 🔵 Needs a closer look

- **Files reviewed:** 2/2 changed files`;

test('normalizes bot logins and reviewer configuration', () => {
    assert.equal(normalizeLogin('Copilot-Pull-Request-Reviewer[bot]'),
        'copilot-pull-request-reviewer');
    assert.deepEqual(
        [...parseReviewerLogins(' Copilot[bot],reviewer ')],
        ['copilot', 'reviewer']);
});

test('parses the changed-file coverage published by Copilot', () => {
    assert.deepEqual(parseReviewCoverage(APPROVAL), { reviewed: 2, total: 2 });
    assert.equal(parseReviewCoverage('no coverage'), null);
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
        /types: \[opened, synchronize, reopened, ready_for_review\]/u);
    assert.match(workflow,
        /name: Require complete review of the exact pull-request head/u);
    assert.match(workflow,
        /pull-requests: read/u);
    assert.match(workflow,
        /git show "\$\{\{ github\.event\.pull_request\.base\.sha \}\}:\$gate"/u);
    assert.match(workflow,
        /\.cache\/ui-frontend\/node\/node "\$gate"/u);
});
