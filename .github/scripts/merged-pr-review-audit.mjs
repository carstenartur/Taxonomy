#!/usr/bin/env node

import { appendFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const API_VERSION = '2022-11-28';
const DEFAULT_REVIEWERS = [
    'copilot-pull-request-reviewer[bot]',
    'copilot-pull-request-reviewer',
    'Copilot'
];
const COMMENT_MARKER = '<!-- taxonomy-merged-review-audit:';
const SEVERITY_RANK = { low: 1, medium: 2, high: 3 };

export function normalizeLogin(login) {
    return String(login ?? '')
        .trim()
        .toLowerCase()
        .replace(/\[bot\]$/u, '');
}

export function parseReviewerLogins(value = '') {
    const supplied = String(value)
        .split(',')
        .map(normalizeLogin)
        .filter(Boolean);
    return new Set((supplied.length ? supplied : DEFAULT_REVIEWERS)
        .map(normalizeLogin));
}

export function parseReviewCoverage(body) {
    const match = String(body ?? '').match(
        /Files reviewed:\*{0,2}\s*(\d+)\s*\/\s*(\d+)/iu);
    return match ? {
        reviewed: Number.parseInt(match[1], 10),
        total: Number.parseInt(match[2], 10)
    } : null;
}

export function parseReviewCommentCount(body) {
    const match = String(body ?? '').match(
        /Comments generated:\*{0,2}\s*(\d+)/iu);
    return match ? Number.parseInt(match[1], 10) : null;
}

export function classifyReview(review) {
    const body = String(review?.body ?? '');
    const state = String(review?.state ?? '').toUpperCase();
    if (/Changes recommended/iu.test(body) || state === 'CHANGES_REQUESTED') {
        return 'changes-recommended';
    }
    if (/Needs a closer look/iu.test(body)) {
        return 'needs-closer-look';
    }
    if (/Approval recommended/iu.test(body) || state === 'APPROVED') {
        return 'approval-recommended';
    }
    return 'unclassified';
}

function reviewLogin(review) {
    return normalizeLogin(review?.user?.login ?? review?.author?.login);
}

function reviewCommit(review) {
    return String(review?.commit_id ?? review?.commitId ?? '');
}

function reviewSubmittedAt(review) {
    return String(review?.submitted_at ?? review?.submittedAt ?? '');
}

function trustedReviews(reviews, reviewerLogins) {
    const trusted = reviewerLogins instanceof Set
        ? reviewerLogins : parseReviewerLogins(reviewerLogins);
    return (reviews ?? [])
        .filter(review => trusted.has(reviewLogin(review)))
        .filter(review => String(review?.state ?? '').toUpperCase() !== 'DISMISSED')
        .filter(review => reviewSubmittedAt(review))
        .toSorted((left, right) =>
            reviewSubmittedAt(left).localeCompare(reviewSubmittedAt(right)));
}

function unresolvedCurrentThreads(threads) {
    return (threads ?? []).filter(thread => {
        const resolved = Boolean(thread?.isResolved ?? thread?.is_resolved);
        const outdated = Boolean(thread?.isOutdated ?? thread?.is_outdated);
        return !resolved && !outdated;
    });
}

function reviewEvidence(review, changedFiles) {
    const coverage = parseReviewCoverage(review?.body);
    const commentCount = parseReviewCommentCount(review?.body);
    const classification = classifyReview(review);
    const completeCoverage = coverage
        && coverage.reviewed === coverage.total
        && coverage.total === changedFiles;
    return {
        classification,
        coverage,
        commentCount,
        completeCoverage: Boolean(completeCoverage),
        submittedAt: reviewSubmittedAt(review),
        commit: reviewCommit(review),
        reviewer: reviewLogin(review)
    };
}

function finding(severity, code, message, details = {}) {
    return { severity, code, message, ...details };
}

export function auditMergedPullRequest({
    pullRequest,
    reviews,
    threads,
    reviewerLogins
}) {
    const number = Number(pullRequest?.number);
    const mergedAt = String(pullRequest?.merged_at ?? pullRequest?.mergedAt ?? '');
    const headSha = String(pullRequest?.head?.sha ?? '');
    const changedFiles = Number(pullRequest?.changed_files);
    const findings = [];

    if (!number || !mergedAt || !headSha
            || !Number.isInteger(changedFiles) || changedFiles < 1) {
        findings.push(finding('high', 'MERGED_PR_METADATA_INCOMPLETE',
            'Merged pull-request number, merge time, head SHA, or changed-file count is missing.'));
        return auditResult(pullRequest, number, headSha, mergedAt, changedFiles, findings);
    }

    const trusted = trustedReviews(reviews, reviewerLogins);
    const exactHeadReviews = trusted.filter(review => reviewCommit(review) === headSha);
    const exactBeforeMerge = exactHeadReviews.filter(review =>
        reviewSubmittedAt(review) <= mergedAt);
    const lateReviews = trusted.filter(review =>
        reviewSubmittedAt(review) > mergedAt);
    const latestBeforeMerge = exactBeforeMerge.at(-1);

    if (!latestBeforeMerge) {
        findings.push(finding('medium', 'NO_EXACT_HEAD_REVIEW_BEFORE_MERGE',
            `No trusted review of exact head ${headSha} completed before merge.`));
    } else {
        const evidence = reviewEvidence(latestBeforeMerge, changedFiles);
        if (evidence.classification !== 'approval-recommended') {
            findings.push(finding('high', 'NON_APPROVING_EXACT_HEAD_REVIEW',
                `The latest exact-head pre-merge review outcome was ${evidence.classification}.`,
                evidence));
        }
        if (!evidence.completeCoverage) {
            findings.push(finding('high', 'INCOMPLETE_EXACT_HEAD_REVIEW',
                evidence.coverage
                    ? `The pre-merge review covered ${evidence.coverage.reviewed}/${evidence.coverage.total} files while the PR changed ${changedFiles}.`
                    : 'The pre-merge review published no changed-file coverage count.',
                evidence));
        }
        if (evidence.commentCount === null) {
            findings.push(finding('medium', 'PRE_MERGE_REVIEW_COMMENT_COUNT_MISSING',
                'The pre-merge review did not publish its generated-comment count.',
                evidence));
        } else if (evidence.commentCount > 0) {
            findings.push(finding('high', 'PRE_MERGE_REVIEW_FINDINGS_NOT_RECHECKED',
                `The latest exact-head pre-merge review generated ${evidence.commentCount} comment(s) and no later comment-free exact-head review exists.`,
                evidence));
        }
    }

    for (const review of lateReviews) {
        const evidence = reviewEvidence(review, changedFiles);
        const actionable = evidence.classification !== 'approval-recommended'
            || !evidence.completeCoverage
            || evidence.commentCount === null
            || evidence.commentCount > 0;
        findings.push(finding(actionable ? 'high' : 'medium',
            actionable
                ? 'ACTIONABLE_REVIEW_SUBMITTED_AFTER_MERGE'
                : 'APPROVING_REVIEW_SUBMITTED_AFTER_MERGE',
            `A trusted ${evidence.classification} review was submitted at ${evidence.submittedAt}, after merge at ${mergedAt}.`,
            evidence));
    }

    const unresolved = unresolvedCurrentThreads(threads);
    if (unresolved.length) {
        findings.push(finding('high', 'UNRESOLVED_CURRENT_THREAD_AFTER_MERGE',
            `${unresolved.length} non-outdated review thread(s) remain unresolved after merge.`, {
                paths: [...new Set(unresolved.map(thread => thread.path).filter(Boolean))]
                    .toSorted()
            }));
    }

    return auditResult(pullRequest, number, headSha, mergedAt, changedFiles, findings);
}

function auditResult(pullRequest, number, headSha, mergedAt, changedFiles, findings) {
    return {
        number,
        title: String(pullRequest?.title ?? ''),
        url: String(pullRequest?.html_url ?? pullRequest?.url ?? ''),
        headSha,
        mergedAt,
        changedFiles,
        findings
    };
}

export function findingsAtOrAbove(findings, threshold = 'high') {
    const required = SEVERITY_RANK[String(threshold).toLowerCase()]
        ?? SEVERITY_RANK.high;
    return (findings ?? []).filter(item =>
        (SEVERITY_RANK[item.severity] ?? SEVERITY_RANK.high) >= required);
}

class GitHubClient {
    constructor(token, repository) {
        if (!token) {
            throw new Error('GITHUB_TOKEN or GH_TOKEN is required.');
        }
        const [owner, name] = String(repository ?? '').split('/');
        if (!owner || !name) {
            throw new Error('GITHUB_REPOSITORY must use owner/name form.');
        }
        this.token = token;
        this.owner = owner;
        this.name = name;
        this.repository = `${owner}/${name}`;
        this.apiUrl = String(process.env.GITHUB_API_URL
            || 'https://api.github.com').replace(/\/$/u, '');
        this.graphqlUrl = String(process.env.GITHUB_GRAPHQL_URL
            || 'https://api.github.com/graphql');
    }

    async request(path, options = {}) {
        const response = await fetch(`${this.apiUrl}${path}`, {
            method: options.method ?? 'GET',
            headers: {
                Accept: 'application/vnd.github+json',
                Authorization: `Bearer ${this.token}`,
                'Content-Type': 'application/json',
                'X-GitHub-Api-Version': API_VERSION
            },
            body: options.body === undefined
                ? undefined : JSON.stringify(options.body)
        });
        const payload = response.status === 204 ? null : await response.json();
        if (!response.ok) {
            throw new Error(`GitHub API ${path} failed: ${payload?.message || response.status}.`);
        }
        return payload;
    }

    async paginate(path, maximumPages = 10) {
        const separator = path.includes('?') ? '&' : '?';
        const result = [];
        for (let page = 1; page <= maximumPages; page++) {
            const batch = await this.request(
                `${path}${separator}per_page=100&page=${page}`);
            if (!Array.isArray(batch)) {
                throw new Error(`Paginated API ${path} returned a non-array response.`);
            }
            result.push(...batch);
            if (batch.length < 100) {
                return result;
            }
        }
        throw new Error(`Pagination limit exceeded for ${path}.`);
    }

    pullRequest(number) {
        return this.request(`/repos/${this.repository}/pulls/${number}`);
    }

    reviews(number) {
        return this.paginate(`/repos/${this.repository}/pulls/${number}/reviews`);
    }

    issueComments(number) {
        return this.paginate(`/repos/${this.repository}/issues/${number}/comments`);
    }

    addIssueComment(number, body) {
        return this.request(`/repos/${this.repository}/issues/${number}/comments`, {
            method: 'POST',
            body: { body }
        });
    }

    async reviewThreads(number) {
        const query = `
          query AuditReviewThreads($owner: String!, $name: String!, $number: Int!, $after: String) {
            repository(owner: $owner, name: $name) {
              pullRequest(number: $number) {
                reviewThreads(first: 100, after: $after) {
                  pageInfo { hasNextPage endCursor }
                  nodes { isResolved isOutdated path line }
                }
              }
            }
          }`;
        const result = [];
        let after = null;
        for (let page = 1; page <= 10; page++) {
            const response = await fetch(this.graphqlUrl, {
                method: 'POST',
                headers: {
                    Accept: 'application/vnd.github+json',
                    Authorization: `Bearer ${this.token}`,
                    'Content-Type': 'application/json',
                    'X-GitHub-Api-Version': API_VERSION
                },
                body: JSON.stringify({
                    query,
                    variables: {
                        owner: this.owner,
                        name: this.name,
                        number,
                        after
                    }
                })
            });
            const payload = await response.json();
            if (!response.ok || payload.errors?.length) {
                const message = payload.errors?.map(error => error.message).join('; ')
                    || response.status;
                throw new Error(`Review-thread query failed: ${message}.`);
            }
            const connection = payload.data?.repository?.pullRequest?.reviewThreads;
            if (!connection) {
                throw new Error(`Review threads are unavailable for PR #${number}.`);
            }
            result.push(...(connection.nodes ?? []));
            if (!connection.pageInfo?.hasNextPage) {
                return result;
            }
            after = connection.pageInfo.endCursor;
        }
        throw new Error(`Review-thread pagination exceeded 1,000 entries for PR #${number}.`);
    }

    async searchMergedPullRequests(sinceDate) {
        const query = [
            `repo:${this.repository}`,
            'is:pr',
            'is:merged',
            'base:main',
            `merged:>=${sinceDate}`
        ].join(' ');
        const result = [];
        for (let page = 1; page <= 10; page++) {
            const payload = await this.request(
                `/search/issues?q=${encodeURIComponent(query)}&sort=updated&order=desc&per_page=100&page=${page}`);
            if (!Array.isArray(payload?.items)) {
                throw new Error('Merged-PR search returned no item array.');
            }
            result.push(...payload.items);
            if (payload.items.length < 100) {
                return result;
            }
        }
        throw new Error('Merged-PR search exceeded the 1,000-result limit.');
    }
}

function boundedInteger(value, fallback, minimum, maximum) {
    const parsed = Number.parseInt(String(value ?? ''), 10);
    return Number.isInteger(parsed)
        ? Math.min(maximum, Math.max(minimum, parsed))
        : fallback;
}

function dateDaysAgo(days) {
    const date = new Date();
    date.setUTCDate(date.getUTCDate() - days);
    return date.toISOString().slice(0, 10);
}

function renderMarkdown(results, scope) {
    const allFindings = results.flatMap(result => result.findings);
    const highCount = allFindings.filter(item => item.severity === 'high').length;
    const lines = [
        '# Merged pull-request review audit',
        '',
        `Scope: ${scope}`,
        '',
        `Audited pull requests: ${results.length}`,
        `Findings: ${allFindings.length} (${highCount} high)`,
        ''
    ];
    for (const result of results.filter(item => item.findings.length)) {
        lines.push(`## PR #${result.number} — ${result.title}`);
        lines.push('');
        lines.push(`- Head: \`${result.headSha}\``);
        lines.push(`- Merged: ${result.mergedAt}`);
        lines.push(`- Changed files: ${result.changedFiles}`);
        lines.push('');
        for (const item of result.findings) {
            lines.push(`- **${item.severity.toUpperCase()}** \`${item.code}\`: ${item.message}`);
        }
        lines.push('');
    }
    return `${lines.join('\n')}\n`;
}

function auditComment(result) {
    const marker = `${COMMENT_MARKER}${result.headSha} -->`;
    const items = findingsAtOrAbove(result.findings, 'high')
        .map(item => `- \`${item.code}\`: ${item.message}`)
        .join('\n');
    return `${marker}
## Retrospective review audit

The repository-owned audit found review evidence for merged head \`${result.headSha}\` that arrived too late, remained unresolved, or was not followed by a clean exact-head re-review:

${items}

Closing or merging the original PR does not dispose of these findings. Each item needs a technically explicit disposition and, when the current tree remains affected, a bounded follow-up PR reviewed on its exact final head.
`;
}

async function commentOnce(client, result) {
    const marker = `${COMMENT_MARKER}${result.headSha} -->`;
    const comments = await client.issueComments(result.number);
    if (comments.some(comment => String(comment?.body ?? '').includes(marker))) {
        return false;
    }
    await client.addIssueComment(result.number, auditComment(result));
    return true;
}

async function auditOne(client, number, reviewerLogins) {
    const [pullRequest, reviews, threads] = await Promise.all([
        client.pullRequest(number),
        client.reviews(number),
        client.reviewThreads(number)
    ]);
    if (!pullRequest.merged_at || pullRequest.base?.ref !== 'main') {
        return null;
    }
    return auditMergedPullRequest({
        pullRequest,
        reviews,
        threads,
        reviewerLogins
    });
}

async function appendSummary(markdown) {
    if (process.env.GITHUB_STEP_SUMMARY) {
        await appendFile(process.env.GITHUB_STEP_SUMMARY, markdown, 'utf8');
    }
}

async function writeJson(path, value) {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

async function run() {
    const client = new GitHubClient(
        process.env.GITHUB_TOKEN || process.env.GH_TOKEN,
        process.env.GITHUB_REPOSITORY);
    const reviewerLogins = parseReviewerLogins(process.env.AUDIT_REVIEWERS);
    const requestedNumber = boundedInteger(
        process.env.AUDIT_PR_NUMBER, 0, 0, Number.MAX_SAFE_INTEGER);
    const days = boundedInteger(process.env.AUDIT_SINCE_DAYS, 35, 1, 365);
    const sinceDate = String(process.env.AUDIT_SINCE_DATE ?? '').trim()
        || dateDaysAgo(days);
    const numbers = requestedNumber
        ? [requestedNumber]
        : (await client.searchMergedPullRequests(sinceDate))
            .map(item => Number(item.number))
            .filter(Boolean);
    const results = [];
    for (const number of numbers) {
        const item = await auditOne(client, number, reviewerLogins);
        if (item) {
            results.push(item);
        }
    }
    results.sort((left, right) => right.number - left.number);

    const scope = requestedNumber
        ? `merged PR #${requestedNumber}`
        : `merged PRs into main since ${sinceDate}`;
    const report = {
        schemaVersion: 1,
        repository: client.repository,
        scope,
        generatedAt: new Date().toISOString(),
        pullRequests: results
    };
    const outputRoot = String(process.env.AUDIT_OUTPUT_ROOT
        || 'target/merged-pr-review-audit');
    await writeJson(`${outputRoot}/audit.json`, report);
    const markdown = renderMarkdown(results, scope);
    await writeFile(`${outputRoot}/audit.md`, markdown, 'utf8');
    await appendSummary(markdown);

    const threshold = String(process.env.AUDIT_FAIL_SEVERITY || 'high');
    const actionable = results.filter(result =>
        findingsAtOrAbove(result.findings, threshold).length);
    if (String(process.env.AUDIT_COMMENT ?? '').toLowerCase() === 'true') {
        for (const result of actionable) {
            await commentOnce(client, result);
        }
    }
    if (actionable.length) {
        throw new Error(
            `${actionable.length} merged pull request(s) have review findings at or above ${threshold}.`);
    }
    console.log(`Merged-PR review audit passed for ${results.length} pull request(s).`);
}

const invokedDirectly = process.argv[1]
    && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
    run().catch(error => {
        console.error(`::error::${error.message}`);
        process.exitCode = 1;
    });
}
