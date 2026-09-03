#!/usr/bin/env node

import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const SEVERITY_RANK = Object.freeze({ low: 1, medium: 2, high: 3 });
export const DEFAULT_ENFORCE_MERGED_SINCE = '2026-09-02T04:59:06Z';

function thresholdRank(value) {
    const threshold = String(value ?? 'high').trim().toLowerCase();
    if (!Object.hasOwn(SEVERITY_RANK, threshold)) {
        throw new Error(`Unsupported audit enforcement severity: ${threshold || '<empty>'}.`);
    }
    return { threshold, rank: SEVERITY_RANK[threshold] };
}

function parseRequestedNumber(value) {
    const text = String(value ?? '').trim();
    if (!text) {
        return 0;
    }
    const number = Number.parseInt(text, 10);
    if (!Number.isInteger(number) || number < 1 || String(number) !== text) {
        throw new Error(`AUDIT_PR_NUMBER must be a positive integer, got ${text}.`);
    }
    return number;
}

function parseTimestamp(value, label) {
    const text = String(value ?? '').trim();
    const milliseconds = Date.parse(text);
    if (!text || Number.isNaN(milliseconds)) {
        throw new Error(`${label} must be an ISO-8601 timestamp, got ${text || '<empty>'}.`);
    }
    return { text, milliseconds };
}

function findingsAtOrAbove(result, requiredRank) {
    if (!Array.isArray(result?.findings)) {
        throw new Error(`Audit result for PR #${result?.number ?? 'unknown'} has no findings array.`);
    }
    return result.findings.filter(item => {
        const severity = String(item?.severity ?? '').toLowerCase();
        if (!Object.hasOwn(SEVERITY_RANK, severity)) {
            throw new Error(
                `Audit result for PR #${result?.number ?? 'unknown'} has unsupported severity ${severity || '<empty>'}.`);
        }
        return SEVERITY_RANK[severity] >= requiredRank;
    });
}

function resultSummary(result, findings, enforcementClass) {
    return {
        number: Number(result.number),
        title: String(result.title ?? ''),
        url: String(result.url ?? ''),
        headSha: String(result.headSha ?? ''),
        baseRef: String(result.baseRef ?? ''),
        mergedAt: String(result.mergedAt ?? ''),
        enforcementClass,
        findings
    };
}

export function enforceAuditReport(report, options = {}) {
    if (!report || typeof report !== 'object' || !Array.isArray(report.pullRequests)) {
        throw new Error('Merged-PR audit report must contain a pullRequests array.');
    }

    const { threshold, rank } = thresholdRank(options.threshold);
    const requestedNumber = parseRequestedNumber(options.requestedNumber);
    const boundary = parseTimestamp(
        options.enforceMergedSince ?? DEFAULT_ENFORCE_MERGED_SINCE,
        'AUDIT_ENFORCE_MERGED_SINCE');

    if (requestedNumber) {
        const matching = report.pullRequests.filter(item =>
            Number(item?.number) === requestedNumber);
        if (report.pullRequests.length !== 1 || matching.length !== 1) {
            throw new Error(
                `Targeted audit for PR #${requestedNumber} must contain exactly that one pull request.`);
        }
    }

    const actionablePullRequests = [];
    const historicalPullRequests = [];
    for (const result of report.pullRequests) {
        const findings = findingsAtOrAbove(result, rank);
        if (!findings.length) {
            continue;
        }

        if (requestedNumber) {
            actionablePullRequests.push(
                resultSummary(result, findings, 'targeted-review-event'));
            continue;
        }

        const mergedAt = Date.parse(String(result?.mergedAt ?? ''));
        if (Number.isNaN(mergedAt)) {
            actionablePullRequests.push(
                resultSummary(result, findings, 'invalid-merge-time-fail-closed'));
        } else if (mergedAt >= boundary.milliseconds) {
            actionablePullRequests.push(
                resultSummary(result, findings, 'post-rollout'));
        } else {
            historicalPullRequests.push(
                resultSummary(result, findings, 'pre-rollout-evidence'));
        }
    }

    return {
        schemaVersion: 1,
        sourceSchemaVersion: report.schemaVersion ?? null,
        repository: String(report.repository ?? ''),
        sourceScope: String(report.scope ?? ''),
        threshold,
        mode: requestedNumber ? 'targeted' : 'window',
        requestedNumber: requestedNumber || null,
        enforceMergedSince: boundary.text,
        counts: {
            auditedPullRequests: report.pullRequests.length,
            actionablePullRequests: actionablePullRequests.length,
            historicalPullRequests: historicalPullRequests.length
        },
        actionablePullRequests,
        historicalPullRequests
    };
}

export function renderEnforcementMarkdown(result) {
    const lines = [
        '# Merged pull-request review enforcement',
        '',
        `Mode: ${result.mode}`,
        `Failure threshold: ${result.threshold}`,
        `Enforce merges since: ${result.enforceMergedSince}`,
        '',
        `Audited pull requests: ${result.counts.auditedPullRequests}`,
        `Actionable pull requests: ${result.counts.actionablePullRequests}`,
        `Historical pre-rollout pull requests: ${result.counts.historicalPullRequests}`,
        ''
    ];

    if (result.actionablePullRequests.length) {
        lines.push('## Actionable findings');
        lines.push('');
        for (const item of result.actionablePullRequests) {
            lines.push(`- PR #${item.number} (${item.enforcementClass}): ${item.findings.length} finding(s)`);
        }
        lines.push('');
    }

    if (result.historicalPullRequests.length) {
        lines.push('## Historical evidence retained without retroactive branch failure');
        lines.push('');
        lines.push('These findings remain in the complete audit artifact and QA backlog.');
        for (const item of result.historicalPullRequests) {
            lines.push(`- PR #${item.number}: ${item.findings.length} finding(s)`);
        }
        lines.push('');
    }

    return `${lines.join('\n')}\n`;
}

async function writeJson(path, value) {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

async function appendSummary(markdown) {
    if (process.env.GITHUB_STEP_SUMMARY) {
        await appendFile(process.env.GITHUB_STEP_SUMMARY, markdown, 'utf8');
    }
}

async function run() {
    const reportPath = String(process.env.AUDIT_REPORT
        || 'target/merged-pr-review-audit/audit.json');
    const outputRoot = String(process.env.AUDIT_ENFORCEMENT_OUTPUT_ROOT
        || 'target/merged-pr-review-audit/enforcement');
    const report = JSON.parse(await readFile(reportPath, 'utf8'));
    const result = enforceAuditReport(report, {
        threshold: process.env.AUDIT_FAIL_SEVERITY || 'high',
        requestedNumber: process.env.AUDIT_PR_NUMBER || '',
        enforceMergedSince: process.env.AUDIT_ENFORCE_MERGED_SINCE
            || DEFAULT_ENFORCE_MERGED_SINCE
    });
    const markdown = renderEnforcementMarkdown(result);
    await writeJson(`${outputRoot}/enforcement.json`, result);
    await mkdir(outputRoot, { recursive: true });
    await writeFile(`${outputRoot}/enforcement.md`, markdown, 'utf8');
    await appendSummary(markdown);

    if (result.actionablePullRequests.length) {
        throw new Error(
            `${result.actionablePullRequests.length} merged pull request(s) have enforceable review findings at or above ${result.threshold}.`);
    }
    console.log(
        `Review enforcement passed; retained ${result.historicalPullRequests.length} historical pre-rollout finding set(s).`);
}

const invokedDirectly = process.argv[1]
    && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
    run().catch(error => {
        console.error(`::error::${error.message}`);
        process.exitCode = 1;
    });
}
