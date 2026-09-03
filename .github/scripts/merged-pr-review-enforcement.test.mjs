import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

import {
    DEFAULT_ENFORCE_MERGED_SINCE,
    enforceAuditReport,
    renderEnforcementMarkdown
} from './merged-pr-review-enforcement.mjs';

function finding(severity = 'high', code = 'EXAMPLE') {
    return { severity, code, message: code };
}

function pullRequest(number, mergedAt, findings = [finding()]) {
    return {
        number,
        title: `PR ${number}`,
        url: `https://github.example/pull/${number}`,
        headSha: String(number).padStart(40, '0'),
        baseRef: 'main',
        mergedAt,
        changedFiles: 1,
        findings
    };
}

function report(pullRequests) {
    return {
        schemaVersion: 2,
        repository: 'carstenartur/Taxonomy',
        scope: 'test window',
        pullRequests
    };
}

test('window enforcement retains pre-rollout findings but blocks later findings', () => {
    const result = enforceAuditReport(report([
        pullRequest(970, '2026-09-01T23:59:59Z'),
        pullRequest(974, DEFAULT_ENFORCE_MERGED_SINCE),
        pullRequest(975, '2026-09-02T06:00:00Z', [finding('medium')])
    ]));

    assert.deepEqual(result.actionablePullRequests.map(item => item.number), [974]);
    assert.deepEqual(result.historicalPullRequests.map(item => item.number), [970]);
    assert.deepEqual(result.counts, {
        auditedPullRequests: 3,
        actionablePullRequests: 1,
        historicalPullRequests: 1
    });
});

test('targeted review events remain enforceable regardless of merge age', () => {
    const result = enforceAuditReport(
        report([pullRequest(700, '2026-08-01T00:00:00Z')]),
        { requestedNumber: '700' });

    assert.equal(result.mode, 'targeted');
    assert.equal(result.actionablePullRequests[0].enforcementClass,
        'targeted-review-event');
    assert.equal(result.historicalPullRequests.length, 0);
});

test('invalid merge timestamps fail closed instead of becoming historical', () => {
    const result = enforceAuditReport(report([
        pullRequest(980, 'not-a-timestamp')
    ]));

    assert.equal(result.actionablePullRequests[0].enforcementClass,
        'invalid-merge-time-fail-closed');
});

test('rejects malformed targeted scope, severity, and report contracts', () => {
    assert.throws(() => enforceAuditReport(report([]), { requestedNumber: '12' }),
        /exactly that one pull request/u);
    assert.throws(() => enforceAuditReport(report([]), { requestedNumber: '012' }),
        /positive integer/u);
    assert.throws(() => enforceAuditReport(report([
        pullRequest(1, '2026-09-03T00:00:00Z', [finding('critical')])
    ])), /unsupported severity/u);
    assert.throws(() => enforceAuditReport({}), /pullRequests array/u);
});

test('renders both the blocking result and retained historical evidence', () => {
    const result = enforceAuditReport(report([
        pullRequest(970, '2026-09-01T23:59:59Z'),
        pullRequest(974, '2026-09-02T05:00:00Z')
    ]));
    const markdown = renderEnforcementMarkdown(result);

    assert.match(markdown, /Actionable findings/u);
    assert.match(markdown, /Historical evidence retained/u);
    assert.match(markdown, /PR #974/u);
    assert.match(markdown, /PR #970/u);
});

test('workflow separates targeted blocking from window collection and enforcement', async () => {
    const workflow = await readFile(
        new URL('../workflows/merged-pr-review-audit.yml', import.meta.url), 'utf8');

    assert.match(workflow,
        /name: Audit one merged pull request after a review event[\s\S]*if: github\.event_name == 'pull_request_review'[\s\S]*AUDIT_COMMENT: 'true'/u);
    assert.match(workflow,
        /name: Collect the retrospective audit window[\s\S]*if: github\.event_name != 'pull_request_review'[\s\S]*continue-on-error: \$\{\{ github\.event_name == 'schedule' \|\| github\.event_name == 'workflow_dispatch' \}\}[\s\S]*AUDIT_COMMENT: 'false'/u);
    assert.match(workflow,
        /name: Enforce review evidence from the audit rollout onward[\s\S]*if: always\(\) && github\.event_name != 'pull_request_review'/u);
    assert.ok(workflow.includes(
        `AUDIT_ENFORCE_MERGED_SINCE: '${DEFAULT_ENFORCE_MERGED_SINCE}'`));
    assert.match(workflow, /merged-pr-review-enforcement\.mjs/u);
});

test('Maven-owned UI contract chains execute the enforcement tests', async () => {
    const packageJson = JSON.parse(await readFile(
        new URL('../package.json', import.meta.url), 'utf8'));

    assert.equal(packageJson.scripts['test:merged-pr-review-enforcement'],
        'node --test scripts/merged-pr-review-enforcement.test.mjs');
    assert.match(packageJson.scripts['verify:ui'],
        /test:merged-pr-review-audit && npm run test:merged-pr-review-enforcement/u);
    assert.match(packageJson.scripts['verify:ui-contracts'],
        /test:merged-pr-review-audit && npm run test:merged-pr-review-enforcement/u);
});
