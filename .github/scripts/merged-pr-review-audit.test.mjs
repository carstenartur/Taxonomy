import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

import {
    auditMergedPullRequest,
    classifyReview,
    findingsAtOrAbove,
    parseReviewCommentCount,
    parseReviewCoverage,
    parseReviewerLogins
} from './merged-pr-review-audit.mjs';

const HEAD = 'a'.repeat(40);
const REVIEWERS = parseReviewerLogins('copilot-pull-request-reviewer[bot]');

function pullRequest(overrides = {}) {
    return {
        number: 933,
        state: 'closed',
        title: 'Example',
        html_url: 'https://github.example/pull/933',
        head: { sha: HEAD },
        base: { ref: 'main' },
        changed_files: 2,
        merged_at: '2026-09-01T11:00:00Z',
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

- **Files reviewed:** 2/2 changed files
- **Comments generated:** 0`;

const CHANGES = `### 🟡 Changes recommended

- **Files reviewed:** 2/2 changed files
- **Comments generated:** 1`;

const CLOSER = `### 🔵 Needs a closer look

- **Files reviewed:** 2/2 changed files
- **Comments generated:** 1`;

test('parses and classifies the review evidence contract', () => {
    assert.deepEqual(parseReviewCoverage(APPROVAL), { reviewed: 2, total: 2 });
    assert.equal(parseReviewCommentCount(APPROVAL), 0);
    assert.equal(classifyReview(review(APPROVAL)), 'approval-recommended');
    assert.equal(classifyReview(review(CHANGES)), 'changes-recommended');
    assert.equal(classifyReview(review(CLOSER)), 'needs-closer-look');
});

test('accepts a clean complete exact-head review before merge', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL)],
        threads: [],
        reviewerLogins: REVIEWERS
    });
    assert.deepEqual(result.findings, []);
});

test('reports no exact-head pre-merge review without making it high severity', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL, { commit_id: 'b'.repeat(40) })],
        threads: [],
        reviewerLogins: REVIEWERS
    });
    assert.ok(result.findings.some(item =>
        item.code === 'NO_EXACT_HEAD_REVIEW_BEFORE_MERGE'
            && item.severity === 'medium'));
    assert.equal(findingsAtOrAbove(result.findings, 'high').length, 0);
});

test('reports a changes-recommended review submitted after merge as high', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest({ merged_at: '2026-09-01T09:00:00Z' }),
        reviews: [review(CHANGES)],
        threads: [],
        reviewerLogins: REVIEWERS
    });
    assert.ok(result.findings.some(item =>
        item.code === 'ACTIONABLE_REVIEW_SUBMITTED_AFTER_MERGE'
            && item.severity === 'high'));
});

test('reports an approval submitted after merge as medium when it is complete', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest({ merged_at: '2026-09-01T09:00:00Z' }),
        reviews: [review(APPROVAL)],
        threads: [],
        reviewerLogins: REVIEWERS
    });
    assert.ok(result.findings.some(item =>
        item.code === 'APPROVING_REVIEW_SUBMITTED_AFTER_MERGE'
            && item.severity === 'medium'));
});

test('reports partial coverage and review comments without a clean re-review', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL
            .replace('2/2', '1/2')
            .replace('Comments generated:** 0', 'Comments generated:** 2'))],
        threads: [],
        reviewerLogins: REVIEWERS
    });
    assert.ok(result.findings.some(item =>
        item.code === 'INCOMPLETE_EXACT_HEAD_REVIEW'));
    assert.ok(result.findings.some(item =>
        item.code === 'PRE_MERGE_REVIEW_FINDINGS_NOT_RECHECKED'));
});

test('reports unresolved current threads after merge but ignores outdated ones', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest(),
        reviews: [review(APPROVAL)],
        threads: [
            { isResolved: false, isOutdated: false, path: 'A.java' },
            { isResolved: false, isOutdated: true, path: 'Old.java' }
        ],
        reviewerLogins: REVIEWERS
    });
    const finding = result.findings.find(item =>
        item.code === 'UNRESOLVED_CURRENT_THREAD_AFTER_MERGE');
    assert.deepEqual(finding.paths, ['A.java']);
});

test('scheduled workflow audits merged PRs using trusted default-branch code', async () => {
    const workflow = await readFile(
        new URL('../workflows/merged-pr-review-audit.yml', import.meta.url), 'utf8');
    assert.match(workflow, /pull_request_review:/u);
    assert.match(workflow, /schedule:/u);
    assert.match(workflow, /ref: \$\{\{ github\.sha \}\}/u);
    assert.match(workflow, /pull-requests: read/u);
    assert.match(workflow, /issues: write/u);
    assert.match(workflow, /AUDIT_COMMENT: 'true'/u);
    assert.match(workflow, /merged-pr-review-audit\.mjs/u);
});
