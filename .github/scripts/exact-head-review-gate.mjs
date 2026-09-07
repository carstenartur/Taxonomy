#!/usr/bin/env node

import { appendFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const DEFAULT_REVIEWERS = [
    'copilot-pull-request-reviewer[bot]',
    'copilot-pull-request-reviewer',
    'Copilot'
];
const API_VERSION = '2022-11-28';
export const HUMAN_CONFIRMATION_POLICY_VERSION = 1;

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

function unresolvedCurrentThreads(threads) {
    return (threads ?? []).filter(thread => {
        const resolved = Boolean(thread?.isResolved ?? thread?.is_resolved);
        const outdated = Boolean(thread?.isOutdated ?? thread?.is_outdated);
        return !resolved && !outdated;
    });
}

export function parseReviewConfirmation(body) {
    const match = String(body ?? '').trim().match(
        /^\/confirm-review ([a-fA-F0-9]{40}) ([1-9][0-9]*)$/u);
    return match ? { headSha: match[1].toLowerCase(), reviewId: match[2] } : null;
}

function isHuman(user, reviewerLogins) {
    const automated = reviewerLogins instanceof Set
        ? reviewerLogins : parseReviewerLogins(reviewerLogins);
    return user?.type === 'User' && /^[a-z0-9-]+$/iu.test(user.login ?? '')
        && !automated.has(normalizeLogin(user.login));
}

function canConfirm(user, permissions, reviewerLogins) {
    return isHuman(user, reviewerLogins)
        && ['admin', 'maintain', 'write'].includes(
            permissions.get(normalizeLogin(user.login)));
}

// Permission is read from GitHub's collaborator API, never author_association.
export async function loadHumanPermissions(client, { reviews, comments, headSha, reviewerLogins }) {
    const candidates = [
        ...reviews.filter(review => reviewCommit(review) === headSha
            && ['APPROVED', 'CHANGES_REQUESTED', 'DISMISSED'].includes(review.state)),
        ...comments.filter(comment =>
            parseReviewConfirmation(comment.body)?.headSha === headSha)
    ].filter(item => isHuman(item.user, reviewerLogins) && !item.performed_via_github_app);
    const logins = [...new Set(candidates.map(item => normalizeLogin(item.user.login)))];
    if (logins.length > 50) {
        throw new Error('Too many human-review principals to verify safely.');
    }
    const permissions = new Map();
    for (const login of logins) {
        try {
            const response = await client.request(
                `/repos/${client.repository}/collaborators/${encodeURIComponent(login)}/permission`);
            permissions.set(login, response.permission);
        } catch (error) {
            if (error.status !== 404) throw error;
            permissions.set(login, 'none');
        }
    }
    return permissions;
}

export async function loadHumanEvidence(client, input) {
    const humanPermissions = await loadHumanPermissions(client, input);
    const candidates = input.comments.filter(item =>
        parseReviewConfirmation(item.body)?.headSha === input.headSha
        && canConfirm(item.user, humanPermissions, input.reviewerLogins) && !item.performed_via_github_app);
    const editTimes = new Map();
    // REST timestamps only have second precision. lastEditedAt distinguishes
    // even an edit made during the same second as creation from an original comment.
    for (let offset = 0; offset < candidates.length; offset += 100) {
        const batch = candidates.slice(offset, offset + 100);
        if (batch.some(item => !item.node_id)) throw new Error('Confirmation node ID is unavailable.');
        const response = await fetch(client.graphqlUrl, {
            method: 'POST',
            headers: { Authorization: `Bearer ${client.token}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({
                query: 'query ConfirmationEdits($ids: [ID!]!) { nodes(ids: $ids) { ... on IssueComment { id lastEditedAt } } }',
                variables: { ids: batch.map(item => item.node_id) }
            })
        });
        const payload = await response.json();
        if (!response.ok || payload.errors?.length || !Array.isArray(payload.data?.nodes)) {
            throw new Error('Unable to verify confirmation edit history.');
        }
        for (const node of payload.data.nodes) {
            if (node && Object.hasOwn(node, 'lastEditedAt')) editTimes.set(node.id, node.lastEditedAt);
        }
    }
    return {
        humanPermissions,
        comments: input.comments.map(item => ({ ...item, last_edited_at: editTimes.get(item.node_id) }))
    };
}

export function humanReviewDecision({
    pullRequest, review, reviews = [], comments = [], humanPermissions = new Map(),
    reviewerLogins, asOf = Infinity
}) {
    const headSha = pullRequest?.head?.sha;
    const reviewedAt = Date.parse(reviewSubmittedAt(review));
    const reviewId = String(review?.id ?? '');
    const latestOpinions = new Map();
    for (const candidate of reviews
        .filter(item => reviewCommit(item) === headSha
            && canConfirm(item.user, humanPermissions, reviewerLogins) && !item.performed_via_github_app
            && ['APPROVED', 'CHANGES_REQUESTED', 'DISMISSED'].includes(item.state)
            && Date.parse(reviewSubmittedAt(item)) <= asOf)
        .toSorted((a, b) => Date.parse(reviewSubmittedAt(a)) - Date.parse(reviewSubmittedAt(b))
            || Number(a.id) - Number(b.id))) {
        latestOpinions.set(normalizeLogin(candidate.user.login), candidate);
    }
    const objection = [...latestOpinions.values()]
        .find(item => item.state === 'CHANGES_REQUESTED');
    if (objection) return { objection };
    if (!Number.isFinite(reviewedAt) || !/^[1-9][0-9]*$/u.test(reviewId)) return {};

    const nativeApproval = [...latestOpinions.values()].find(item =>
        item.state === 'APPROVED' && Date.parse(reviewSubmittedAt(item)) > reviewedAt
        && normalizeLogin(item.user.login) !== normalizeLogin(pullRequest?.user?.login));
    if (nativeApproval) {
        return { confirmation: {
            source: 'pull_request_review', id: nativeApproval.id,
            login: nativeApproval.user.login, url: nativeApproval.html_url,
            submittedAt: reviewSubmittedAt(nativeApproval), headSha, reviewId
        } };
    }
    const comment = comments.filter(item => {
        const command = parseReviewConfirmation(item.body);
        const createdAt = Date.parse(item.created_at);
        return command?.headSha === headSha && command.reviewId === reviewId
            && Number.isSafeInteger(item.id) && item.id > 0
            && canConfirm(item.user, humanPermissions, reviewerLogins) && !item.performed_via_github_app
            && createdAt > reviewedAt && createdAt <= asOf
            && item.created_at === item.updated_at && item.last_edited_at === null;
    }).toSorted((a, b) => b.id - a.id)[0];
    return comment ? { confirmation: {
        source: 'issue_comment', id: comment.id, login: comment.user.login,
        url: comment.html_url, submittedAt: comment.created_at, headSha, reviewId
    } } : {};
}

export function evaluateExactHeadReview({
    pullRequest,
    reviews,
    threads,
    expectedHeadSha,
    reviewerLogins,
    comments = [],
    humanPermissions = new Map()
}) {
    const currentHead = String(pullRequest?.head?.sha ?? '');
    if (currentHead !== expectedHeadSha) {
        return result('blocked', 'STALE_REVIEW_GATE_RUN',
            `Current head ${currentHead || 'unknown'} differs from ${expectedHeadSha}.`);
    }
    if (pullRequest?.draft) {
        return result('blocked', 'PULL_REQUEST_IS_DRAFT',
            'The pull request returned to draft while the review gate was running.');
    }
    if (String(pullRequest?.state ?? '').toLowerCase() !== 'open') {
        return result('blocked', 'PULL_REQUEST_NOT_OPEN',
            'The pull request is no longer open.');
    }

    const trusted = reviewerLogins instanceof Set
        ? reviewerLogins : parseReviewerLogins(reviewerLogins);
    const exactReviews = (reviews ?? [])
        .filter(review => trusted.has(reviewLogin(review)))
        .filter(review => String(review?.state ?? '').toUpperCase() !== 'DISMISSED')
        .filter(review => reviewCommit(review) === expectedHeadSha)
        .filter(review => reviewSubmittedAt(review))
        .toSorted((left, right) =>
            reviewSubmittedAt(left).localeCompare(reviewSubmittedAt(right)));
    if (!exactReviews.length) {
        return result('pending', 'EXACT_HEAD_REVIEW_MISSING',
            `No completed trusted review exists for exact head ${expectedHeadSha}.`);
    }

    const review = exactReviews.at(-1);
    const classification = classifyReview(review);
    if (classification === 'changes-recommended') {
        return result('blocked', 'CHANGES_RECOMMENDED',
            'The latest exact-head review recommends changes.', { review });
    }
    if (!['approval-recommended', 'needs-closer-look'].includes(classification)) {
        return result('blocked', 'REVIEW_OUTCOME_UNCLASSIFIED',
            'The latest exact-head review has no explicit approval outcome.', { review });
    }

    const coverage = parseReviewCoverage(review.body);
    const changedFiles = Number(pullRequest?.changed_files);
    if (!coverage
            || coverage.total < 1
            || coverage.reviewed !== coverage.total
            || !Number.isInteger(changedFiles)
            || changedFiles < 1
            || coverage.total !== changedFiles) {
        const message = coverage
            ? `The review covered ${coverage.reviewed}/${coverage.total} files while the pull request contains ${Number.isInteger(changedFiles) ? changedFiles : 'an unknown number of'} changed files.`
            : 'The review did not publish a changed-file coverage count.';
        return result('blocked', 'REVIEW_FILE_COVERAGE_INCOMPLETE', message, {
            review,
            coverage,
            changedFiles
        });
    }

    const reviewCommentCount = parseReviewCommentCount(review.body);
    if (reviewCommentCount === null) {
        return result('blocked', 'REVIEW_COMMENT_COUNT_MISSING',
            'The review did not publish its generated-comment count.', {
                review,
                coverage,
                changedFiles,
                reviewCommentCount
            });
    }
    if (reviewCommentCount !== 0) {
        return result('blocked', 'REVIEW_FOLLOW_UP_REQUIRED',
            `The latest exact-head review generated ${reviewCommentCount} comment(s); a fresh comment-free review is required after disposition.`, {
                review,
                coverage,
                changedFiles,
                reviewCommentCount
            });
    }

    const unresolvedThreads = unresolvedCurrentThreads(threads);
    if (unresolvedThreads.length) {
        return result('blocked', 'UNRESOLVED_REVIEW_THREADS',
            `${unresolvedThreads.length} current review thread(s) remain unresolved.`, {
                review,
                coverage,
                changedFiles,
                reviewCommentCount,
                unresolvedThreads
            });
    }

    const decision = humanReviewDecision({
        pullRequest, review, reviews, comments, humanPermissions, reviewerLogins: trusted
    });
    if (decision.objection) {
        return result('blocked', 'HUMAN_CHANGES_REQUESTED',
            'A repository writer has requested changes on the current head.', { review });
    }
    if (classification === 'needs-closer-look' && !decision.confirmation) {
        return result('blocked', 'CLOSER_REVIEW_REQUIRED',
            'The complete, comment-free Copilot review requires human confirmation. '
            + 'After reviewing this head and the linked review, a repository writer '
            + `(including the PR author) can post this exact PR conversation comment: /confirm-review ${expectedHeadSha} ${review.id}`, {
                review, coverage, changedFiles, reviewCommentCount, unresolvedThreads: []
            });
    }
    return result('passed', 'EXACT_HEAD_REVIEW_COMPLETE',
        decision.confirmation && classification === 'needs-closer-look'
            ? `Exact head ${expectedHeadSha} has a complete Copilot review confirmed by ${decision.confirmation.login}.`
            : `Exact head ${expectedHeadSha} has a complete approval-recommended review.`, {
            review,
            coverage,
            changedFiles,
            reviewCommentCount,
            unresolvedThreads: [],
            humanConfirmation: classification === 'needs-closer-look'
                ? decision.confirmation : null
        });
}

function result(status, code, message, details = {}) {
    return { status, code, message, ...details };
}

export class GitHubClient {
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
                'X-GitHub-Api-Version': API_VERSION,
                'Content-Type': 'application/json'
            },
            body: options.body === undefined ? undefined : JSON.stringify(options.body)
        });
        const text = await response.text();
        const payload = text ? JSON.parse(text) : null;
        if (!response.ok) {
            const error = new Error(`GitHub API ${path} failed: ${payload?.message || response.status}.`);
            error.status = response.status;
            throw error;
        }
        return payload;
    }

    async issueComments(number) {
        const comments = [];
        for (let page = 1; page <= 10; page++) {
            const batch = await this.request(
                `/repos/${this.repository}/issues/${number}/comments?per_page=100&page=${page}`);
            if (!Array.isArray(batch)) throw new Error('Comment API returned a non-array response.');
            comments.push(...batch);
            if (batch.length < 100) return comments;
        }
        throw new Error('Comment pagination exceeded 1,000 entries.');
    }

    async reviews(number) {
        const reviews = [];
        for (let page = 1; page <= 10; page++) {
            const batch = await this.request(
                `/repos/${this.repository}/pulls/${number}/reviews?per_page=100&page=${page}`);
            if (!Array.isArray(batch)) {
                throw new Error('Review API returned a non-array response.');
            }
            reviews.push(...batch);
            if (batch.length < 100) {
                return reviews;
            }
        }
        throw new Error('Review pagination exceeded 1,000 entries.');
    }

    async threads(number) {
        const query = `
          query ReviewThreads($owner: String!, $name: String!, $number: Int!, $after: String) {
            repository(owner: $owner, name: $name) {
              pullRequest(number: $number) {
                reviewThreads(first: 100, after: $after) {
                  pageInfo { hasNextPage endCursor }
                  nodes { isResolved isOutdated path line }
                }
              }
            }
          }`;
        const threads = [];
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
                const detail = payload.errors?.map(error => error.message).join('; ')
                    || response.status;
                throw new Error(`Review-thread query failed: ${detail}.`);
            }
            const connection = payload.data?.repository?.pullRequest?.reviewThreads;
            if (!connection) {
                throw new Error(`Review threads are unavailable for PR #${number}.`);
            }
            threads.push(...(connection.nodes ?? []));
            if (!connection.pageInfo?.hasNextPage) {
                return threads;
            }
            after = connection.pageInfo.endCursor;
        }
        throw new Error('Review-thread pagination exceeded 1,000 entries.');
    }
}

export async function evaluateLiveReview(client, number, expectedHeadSha, reviewerLogins) {
    const [pullRequest, reviews, threads, comments] = await Promise.all([
        client.request(`/repos/${client.repository}/pulls/${number}`),
        client.reviews(number), client.threads(number), client.issueComments(number)
    ]);
    const humanEvidence = await loadHumanEvidence(client, {
        reviews, comments, headSha: expectedHeadSha, reviewerLogins
    });
    return evaluateExactHeadReview({
        pullRequest, reviews, threads, ...humanEvidence,
        expectedHeadSha, reviewerLogins
    });
}

function boundedInteger(value, fallback, minimum, maximum) {
    const parsed = Number.parseInt(String(value ?? ''), 10);
    return Number.isInteger(parsed)
        ? Math.min(maximum, Math.max(minimum, parsed))
        : fallback;
}

function sleep(milliseconds) {
    return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function evidence(resultValue, number, headSha) {
    return {
        schemaVersion: 1,
        pullRequestNumber: number,
        expectedHeadSha: headSha,
        status: resultValue.status,
        code: resultValue.code,
        message: resultValue.message,
        reviewer: reviewLogin(resultValue.review),
        reviewCommit: reviewCommit(resultValue.review),
        reviewSubmittedAt: reviewSubmittedAt(resultValue.review),
        reviewId: resultValue.review?.id ?? null,
        humanConfirmation: resultValue.humanConfirmation ?? null,
        filesReviewed: resultValue.coverage?.reviewed ?? null,
        changedFilesReportedByReview: resultValue.coverage?.total ?? null,
        changedFilesInPullRequest: resultValue.changedFiles ?? null,
        reviewCommentsGenerated: resultValue.reviewCommentCount ?? null,
        unresolvedCurrentThreads: resultValue.unresolvedThreads?.length ?? 0,
        recordedAt: new Date().toISOString()
    };
}

async function writeEvidence(value) {
    const path = process.env.REVIEW_GATE_EVIDENCE_PATH;
    if (!path) {
        return;
    }
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

async function writeSummary(resultValue, number, headSha) {
    const path = process.env.GITHUB_STEP_SUMMARY;
    if (!path) {
        return;
    }
    const coverage = resultValue.coverage
        ? `${resultValue.coverage.reviewed}/${resultValue.coverage.total}`
        : 'not available';
    await appendFile(path, `
## Exact-head review gate

- Pull request: #${number}
- Head: \`${headSha}\`
- Result: **${resultValue.status}** (\`${resultValue.code}\`)
- Changed-file coverage: ${coverage}
- Review comments generated: ${resultValue.reviewCommentCount ?? 'not available'}
- Current unresolved threads: ${resultValue.unresolvedThreads?.length ?? 0}

${resultValue.message}
`, 'utf8');
}

async function run() {
    const number = boundedInteger(process.env.PR_NUMBER, 0, 0, Number.MAX_SAFE_INTEGER);
    const expectedHeadSha = String(process.env.PR_HEAD_SHA ?? '').trim();
    if (!number || !expectedHeadSha) {
        throw new Error('PR_NUMBER and PR_HEAD_SHA are required.');
    }
    const waitSeconds = boundedInteger(
        process.env.REVIEW_GATE_WAIT_SECONDS, 900, 1, 1800);
    const pollSeconds = boundedInteger(
        process.env.REVIEW_GATE_POLL_SECONDS, 20, 1, 120);
    const reviewerLogins = parseReviewerLogins(
        process.env.REVIEW_GATE_REVIEWERS);
    const client = new GitHubClient(
        process.env.GITHUB_TOKEN || process.env.GH_TOKEN,
        process.env.GITHUB_REPOSITORY);
    const deadline = Date.now() + waitSeconds * 1000;

    while (true) {
        const gate = await evaluateLiveReview(client, number, expectedHeadSha, reviewerLogins);
        if (gate.status === 'passed') {
            await writeEvidence(evidence(gate, number, expectedHeadSha));
            await writeSummary(gate, number, expectedHeadSha);
            console.log(gate.message);
            return;
        }
        if (gate.status === 'blocked') {
            await writeEvidence(evidence(gate, number, expectedHeadSha));
            await writeSummary(gate, number, expectedHeadSha);
            throw new Error(`${gate.code}: ${gate.message}`);
        }
        if (Date.now() >= deadline) {
            const timeout = result('blocked', 'EXACT_HEAD_REVIEW_TIMEOUT',
                `${gate.message} The ${waitSeconds}-second review window expired.`);
            await writeEvidence(evidence(timeout, number, expectedHeadSha));
            await writeSummary(timeout, number, expectedHeadSha);
            throw new Error(`${timeout.code}: ${timeout.message}`);
        }
        console.log(`${gate.message} Rechecking in ${pollSeconds} seconds.`);
        await sleep(pollSeconds * 1000);
    }
}

const invokedDirectly = process.argv[1]
    && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
    run().catch(error => {
        console.error(`::error::${error.message}`);
        process.exitCode = 1;
    });
}
