#!/usr/bin/env node

import { appendFile, readFile } from 'node:fs/promises';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import {
    GitHubClient, HUMAN_CONFIRMATION_POLICY_VERSION, evaluateLiveReview,
    parseReviewerLogins, parseReviewConfirmation
} from './exact-head-review-gate.mjs';

const GATE_STEP = 'Require complete review of the exact pull-request head';
const TECHNICAL_STEPS = [
    'Require every authoritative lane',
    'Verify complete digest-bound UI evidence'
];

export function latestPullRequestRun(runs, pullRequest) {
    return runs.filter(run => run.event === 'pull_request'
        && run.path === '.github/workflows/ci-cd.yml'
        && run.head_sha === pullRequest.head.sha
        && run.pull_requests?.some(pr => pr.number === pullRequest.number
            && pr.head.sha === pullRequest.head.sha
            && pr.base.repo.id === pullRequest.base.repo.id
            && pr.base.ref === pullRequest.base.ref))
        .toSorted((a, b) => b.id - a.id)[0];
}

export function verificationJobToRefresh(run, jobs, gate) {
    if (!run || run.status !== 'completed') return null;
    const job = jobs.filter(item => item.name === 'Maven verification'
        && item.run_id === run.id && item.status === 'completed')
        .toSorted((a, b) => b.id - a.id)[0];
    if (!job || !TECHNICAL_STEPS.every(name =>
        job.steps?.some(step => step.name === name && step.conclusion === 'success'))) {
        return null;
    }
    const reviewStep = job.steps.find(step => step.name === GATE_STEP);
    const nowPassed = gate.status === 'passed';
    // Never retry infrastructure, test, evidence or publication failures here.
    if (nowPassed && job.conclusion === 'failure' && reviewStep?.conclusion === 'failure') {
        return job;
    }
    if (!nowPassed && job.conclusion === 'success' && reviewStep?.conclusion === 'success') {
        return job;
    }
    return null;
}

export async function refreshPullRequest(client, number, reviewerLogins) {
    const prefix = `/repos/${client.repository}`;
    const pr = await client.request(`${prefix}/pulls/${number}`);
    if (pr.state !== 'open' || pr.draft) return `#${number}: closed or draft; no refresh.`;
    const runs = await client.request(
        `${prefix}/actions/workflows/ci-cd.yml/runs?event=pull_request&head_sha=${pr.head.sha}&per_page=100`);
    if (!Array.isArray(runs.workflow_runs) || runs.total_count > 100) {
        throw new Error('Unable to enumerate the current head CI runs completely.');
    }
    const run = latestPullRequestRun(runs.workflow_runs, pr);
    if (!run || run.status !== 'completed') return `#${number}: CI absent or still running.`;
    if (run.run_attempt >= 10) return `#${number}: automatic refresh limit reached; inspect CI manually.`;

    // A rerun uses its original base SHA. Do not retry old policy indefinitely
    // or load code from the PR head into this Actions-write job.
    const baseSha = run.pull_requests.find(item => item.number === number).base.sha;
    if (!/^[a-f0-9]{40}$/u.test(baseSha)) throw new Error('CI base SHA is unavailable.');
    const file = await client.request(
        `${prefix}/contents/.github/scripts/exact-head-review-gate.mjs?ref=${baseSha}`);
    if (file.encoding !== 'base64') throw new Error('Trusted gate source is unavailable.');
    const source = Buffer.from(file.content, 'base64').toString('utf8');
    const policyVersion = Number(source.match(
        /^\s*export\s+const\s+HUMAN_CONFIRMATION_POLICY_VERSION\s*=\s*([1-9][0-9]*)\s*(?:;|$)/mu)?.[1]);
    if (!Number.isSafeInteger(policyVersion)) {
        return `#${number}: trusted base review policy version is unreadable; update the PR from main or inspect the trusted gate declaration.`;
    }
    if (policyVersion !== HUMAN_CONFIRMATION_POLICY_VERSION) {
        return `#${number}: update the PR from main and run CI with the confirmation policy first.`;
    }

    const gate = await evaluateLiveReview(client, number, pr.head.sha, reviewerLogins);
    const response = await client.request(`${prefix}/actions/runs/${run.id}/jobs?filter=latest&per_page=100`);
    if (!Array.isArray(response.jobs) || response.total_count > 100) {
        throw new Error('Unable to enumerate the latest CI attempt completely.');
    }
    const job = verificationJobToRefresh(run, response.jobs, gate);
    if (!job) return `#${number}: ${gate.code}; no final-job retry needed.`;

    // Recheck mutable state immediately before requesting a rerun. GitHub also
    // rejects a rerun if another attempt has started in the meantime.
    const [currentPr, currentRun, latestRuns] = await Promise.all([
        client.request(`${prefix}/pulls/${number}`),
        client.request(`${prefix}/actions/runs/${run.id}`),
        client.request(`${prefix}/actions/workflows/ci-cd.yml/runs?event=pull_request&head_sha=${pr.head.sha}&per_page=100`)
    ]);
    if (!Array.isArray(latestRuns.workflow_runs) || latestRuns.total_count > 100) {
        throw new Error('Unable to recheck the latest CI run completely.');
    }
    if (currentPr.state !== 'open' || currentPr.draft || currentPr.head.sha !== pr.head.sha
        || currentRun.status !== 'completed' || currentRun.run_attempt !== run.run_attempt
        || latestPullRequestRun(latestRuns.workflow_runs, currentPr)?.id !== run.id) {
        return `#${number}: state changed; no refresh.`;
    }
    await client.request(`${prefix}/actions/jobs/${job.id}/rerun`, { method: 'POST' });
    return `#${number}: ${gate.code}; rerunning Maven verification job ${job.id}.`;
}

export async function eventPullRequests(client, eventName, event) {
    if (eventName === 'issue_comment') {
        const confirmationEvent = ['created', 'edited', 'deleted'].includes(event.action)
            && (parseReviewConfirmation(event.comment?.body)
                || (event.action === 'edited' && parseReviewConfirmation(event.changes?.body?.from)));
        return confirmationEvent && event.issue?.pull_request && Number.isSafeInteger(event.issue.number)
            ? [event.issue.number] : [];
    }
    if (eventName === 'workflow_run') {
        return event.workflow_run?.event === 'pull_request'
            && event.workflow_run?.path === '.github/workflows/ci-cd.yml'
            ? [...new Set((event.workflow_run.pull_requests ?? []).map(pr => pr.number))]
            : [];
    }
    if (eventName !== 'schedule' && eventName !== 'workflow_dispatch') return [];
    const numbers = [];
    for (let page = 1; page <= 10; page++) {
        const prs = await client.request(
            `/repos/${client.repository}/pulls?state=open&per_page=100&page=${page}`);
        if (!Array.isArray(prs)) throw new Error('Pull request API returned a non-array response.');
        numbers.push(...prs.filter(pr => !pr.draft).map(pr => pr.number));
        if (prs.length < 100) return numbers;
    }
    throw new Error('Open pull-request pagination exceeded 1,000 entries.');
}

async function run() {
    const client = new GitHubClient(process.env.GITHUB_TOKEN, process.env.GITHUB_REPOSITORY);
    const event = JSON.parse(await readFile(process.env.GITHUB_EVENT_PATH, 'utf8'));
    const reviewerLogins = parseReviewerLogins(process.env.REVIEW_GATE_REVIEWERS);
    const numbers = await eventPullRequests(client, process.env.GITHUB_EVENT_NAME, event);
    const messages = [];
    for (const number of numbers) {
        try {
            messages.push(await refreshPullRequest(client, number, reviewerLogins));
        } catch (error) {
            messages.push(`#${number}: refresh failed: ${error.message}`);
            process.exitCode = 1;
        }
    }
    const summary = messages.join('\n') || 'No pull-request review refresh requested.';
    console.log(summary);
    if (process.env.GITHUB_STEP_SUMMARY) {
        await appendFile(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`, 'utf8');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    run().catch(error => { console.error(error.message); process.exitCode = 1; });
}
