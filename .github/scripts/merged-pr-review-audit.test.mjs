import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

import {
    auditMergedPullRequest,
    classifyReview,
    findingsAtOrAbove,
    isAuditableMergedPullRequest,
    mergedPullRequestSearchQuery,
    missingHighFindings,
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

test('searches every recently merged repository PR without a base-branch blind spot', () => {
    const query = mergedPullRequestSearchQuery(
        'carstenartur/Taxonomy', '2026-08-01');

    assert.match(query, /repo:carstenartur\/Taxonomy/u);
    assert.match(query, /is:pr/u);
    assert.match(query, /is:merged/u);
    assert.match(query, /merged:>=2026-08-01/u);
    assert.doesNotMatch(query, /\bbase:/u);
});

test('treats a merged PR on a non-main integration branch as auditable', () => {
    const escapedFindingPr = pullRequest({
        base: { ref: 'qa/922-complete-ai-session' },
        changed_files: 82,
        merged_at: '2026-08-29T19:04:37Z'
    });

    assert.equal(isAuditableMergedPullRequest(escapedFindingPr), true);
    assert.equal(isAuditableMergedPullRequest({ ...escapedFindingPr, merged_at: null }), false);

    const result = auditMergedPullRequest({
        pullRequest: escapedFindingPr,
        reviews: [review(`### 🟡 Changes recommended

- **Files reviewed:** 78/82 changed files
- **Comments generated:** 1`, {
            submitted_at: '2026-08-29T19:07:31Z'
        })],
        threads: [],
        reviewerLogins: REVIEWERS
    });

    assert.equal(result.baseRef, 'qa/922-complete-ai-session');
    assert.ok(result.findings.some(item =>
        item.code === 'ACTIONABLE_REVIEW_SUBMITTED_AFTER_MERGE'
            && item.severity === 'high'));
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

test('reports a changes-recommended exact-head review submitted after merge as high', () => {
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

test('reports an exact-head approval submitted after merge as medium when complete', () => {
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

test('distinguishes a stale-head late review from exact merged-head evidence', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest({ merged_at: '2026-09-01T09:00:00Z' }),
        reviews: [review(CHANGES, { commit_id: 'b'.repeat(40) })],
        threads: [],
        reviewerLogins: REVIEWERS
    });

    assert.ok(result.findings.some(item =>
        item.code === 'STALE_HEAD_REVIEW_SUBMITTED_AFTER_MERGE'
            && item.severity === 'medium'));
    assert.equal(result.findings.some(item =>
        item.code === 'ACTIONABLE_REVIEW_SUBMITTED_AFTER_MERGE'), false);
});

test('later clean exact-head approval reconciles an earlier commented pre-merge review', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest(),
        reviews: [
            review(CHANGES),
            review(APPROVAL, { submitted_at: '2026-09-01T12:00:00Z' })
        ],
        threads: [],
        reviewerLogins: REVIEWERS
    });

    assert.equal(findingsAtOrAbove(result.findings, 'high').length, 0);
    assert.equal(result.findings.some(item =>
        item.code === 'PRE_MERGE_REVIEW_FINDINGS_NOT_RECHECKED'), false);
    assert.ok(result.findings.some(item =>
        item.code === 'APPROVING_REVIEW_SUBMITTED_AFTER_MERGE'
            && item.severity === 'medium'));
});

test('later clean exact-head approval downgrades an actionable late review', () => {
    const result = auditMergedPullRequest({
        pullRequest: pullRequest({ merged_at: '2026-09-01T09:00:00Z' }),
        reviews: [
            review(CHANGES, { submitted_at: '2026-09-01T10:00:00Z' }),
            review(APPROVAL, { submitted_at: '2026-09-01T11:00:00Z' })
        ],
        threads: [],
        reviewerLogins: REVIEWERS
    });

    assert.equal(findingsAtOrAbove(result.findings, 'high').length, 0);
    assert.ok(result.findings.some(item =>
        item.code === 'ACTIONABLE_REVIEW_REMEDIATED_AFTER_MERGE'
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

test('comment selection ignores medium findings regardless of fail threshold', () => {
    const result = {
        headSha: HEAD,
        findings: [
            { severity: 'medium', code: 'HISTORICAL_GAP', message: 'medium' }
        ]
    };

    assert.deepEqual(missingHighFindings(result, []), []);
});

test('comment deduplication is per high finding code and trusts only the bot marker', () => {
    const result = {
        headSha: HEAD,
        findings: [
            { severity: 'high', code: 'FIRST_HIGH', message: 'first' },
            { severity: 'high', code: 'SECOND_HIGH', message: 'second' },
            { severity: 'medium', code: 'MEDIUM_ONLY', message: 'medium' }
        ]
    };
    const comments = [
        {
            user: { login: 'github-actions[bot]' },
            body: `<!-- taxonomy-merged-review-audit:${HEAD}:FIRST_HIGH -->`
        },
        {
            user: { login: 'untrusted-user' },
            body: `<!-- taxonomy-merged-review-audit:${HEAD}:SECOND_HIGH -->`
        }
    ];

    assert.deepEqual(
        missingHighFindings(result, comments).map(item => item.code),
        ['SECOND_HIGH']);
});

test('merged-close workflow executes only trusted default-branch code', async () => {
    const workflow = await readFile(
        new URL('../workflows/merged-pr-review-audit.yml', import.meta.url), 'utf8');
    assert.match(workflow, /pull_request_target:\n    types: \[closed\]/u);
    assert.doesNotMatch(workflow, /pull_request_review:/u);
    assert.match(workflow, /schedule:/u);
    assert.match(workflow, /workflow_dispatch:/u);
    assert.match(workflow,
        /pr_number:\n        description: Optional merged pull request number to audit/u);
    assert.match(workflow,
        /if: github\.event_name != 'pull_request_target' \|\| github\.event\.pull_request\.merged == true/u);
    assert.match(workflow,
        /AUDIT_PR_NUMBER: \$\{\{ github\.event_name == 'pull_request_target' && github\.event\.pull_request\.number \|\| inputs\.pr_number \|\| '' \}\}/u);
    assert.match(workflow,
        /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/u);
    assert.doesNotMatch(workflow, /ref: \$\{\{ github\.sha \}\}/u);
    assert.match(workflow, /fetch-depth: 1/u);
    assert.match(workflow, /pull-requests: read/u);
    assertAuditWorkflowPermissions(workflow);
    assert.match(workflow, /persist-credentials: false/u);
    assert.match(workflow, /AUDIT_FAIL_SEVERITY: 'high'/u);
    assert.doesNotMatch(workflow, /continue-on-error: true/u);
    assert.match(workflow, /AUDIT_COMMENT: 'true'/u);
    assert.match(workflow, /merged-pr-review-audit\.mjs/u);
});

function assertAuditWorkflowPermissions(workflow) {
    // Parse only the two deliberately explicit permission maps, not arbitrary YAML.
    const defaults = workflow.match(/^permissions:\n((?:  [\w-]+: \w+\n)+)/mu);
    const auditJob = workflow.match(/^  audit:\n(?:(?:    .*|)\n)*/mu)?.[0] ?? '';
    const job = auditJob.match(/^    permissions:\n((?:      [\w-]+: \w+\n)+)/mu);
    const entries = block => Object.fromEntries(block.trim().split('\n')
        .map(line => line.trim().split(': ')));
    assert.ok(defaults, 'workflow must explicitly default to read-only permissions');
    assert.ok(job, 'trusted audit job must explicitly grant PR comment access');
    assert.deepEqual(entries(defaults[1]), {
        contents: 'read', 'pull-requests': 'read'
    });
    assert.deepEqual(entries(job[1]), {
        contents: 'read', 'pull-requests': 'write'
    });
}

test('audit permissions reject the old PR-read-only scope and unrelated write grants', async () => {
    const workflow = await readFile(
        new URL('../workflows/merged-pr-review-audit.yml', import.meta.url), 'utf8');
    assertAuditWorkflowPermissions(workflow);
    for (const regression of [
        workflow.replace('      pull-requests: write', '      pull-requests: read'),
        workflow.replace('  pull-requests: read', '  pull-requests: write'),
        workflow.replace('      contents: read', '      contents: write'),
        workflow.replace('      pull-requests: write', '      issues: write'),
        workflow.replace('      pull-requests: write',
            '      pull-requests: write\n      issues: write')
    ]) {
        assert.throws(() => assertAuditWorkflowPermissions(regression));
    }
});

test('Maven-owned UI contract chain retains both review gates', async () => {
    const packageJson = JSON.parse(await readFile(
        new URL('../package.json', import.meta.url), 'utf8'));
    assert.equal(packageJson.scripts['test:exact-head-review-gate'],
        'node --test scripts/exact-head-review-gate.test.mjs');
    assert.equal(packageJson.scripts['test:merged-pr-review-audit'],
        'node --test scripts/merged-pr-review-audit.test.mjs');
    assert.match(packageJson.scripts['verify:ui-contracts'],
        /test:exact-head-review-gate && npm run test:merged-pr-review-audit/u);
});
