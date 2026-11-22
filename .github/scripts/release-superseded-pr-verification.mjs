import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {setTimeout as delay} from 'node:timers/promises';

const ciPath = '.github/workflows/ci-cd.yml';
const positiveId = value => Number.isSafeInteger(value) && value > 0;
const active = run => ['requested', 'pending', 'queued', 'in_progress'].includes(run.status) && run.conclusion === null;

function queuedFinalizer(jobs, runId) {
    const unfinished = jobs.filter(job => job.status !== 'completed');
    const [last] = unfinished;
    return jobs.every(job => job.run_id === runId) && unfinished.length === 1 &&
        last.name === 'Maven verification' && last.status === 'queued' && last.conclusion === null &&
        last.runner_id === 0 && last.runner_name === '' && Array.isArray(last.steps) && !last.steps.length &&
        jobs.some(job => job.status === 'completed' && job.conclusion === 'cancelled');
}

export async function releaseSuperseded(event, api, wait = delay) {
    const request = event.workflow_run;
    const repository = event.repository;
    const [pull] = request?.pull_requests ?? [];
    if (event.action !== 'requested' || request?.event !== 'pull_request' || request.path !== ciPath ||
        request.pull_requests?.length !== 1 || !positiveId(pull.number) || !positiveId(request.workflow_id) ||
        !positiveId(request.id) || !positiveId(request.run_number) || !positiveId(request.head_repository?.id) ||
        !positiveId(repository?.id) || request.repository?.id !== repository.id ||
        !/^[0-9a-f]{40}$/.test(request.head_sha)) return;

    function samePull(run) {
        return run.path === ciPath && run.workflow_id === request.workflow_id && run.event === 'pull_request' &&
            run.repository?.id === repository.id && run.head_repository?.id === request.head_repository.id &&
            run.head_branch === request.head_branch && run.pull_requests?.length === 1 &&
            run.pull_requests[0].number === pull.number && run.pull_requests[0].base?.repo?.id === repository.id;
    }
    function older(run) {
        return samePull(run) && active(run) && positiveId(run.id) && positiveId(run.run_number) &&
            run.run_number < request.run_number && /^[0-9a-f]{40}$/.test(run.head_sha) && run.head_sha !== request.head_sha;
    }
    const readRun = id => api.get('actions/runs/' + id);
    const jobs = run => api.pages('actions/runs/' + run.id + '/attempts/' + run.run_attempt + '/jobs', 'jobs');
    const branch = encodeURIComponent(request.head_branch);
    // Queued finalizers and cancellations still propagating can both appear here.
    const candidates = (await Promise.all(['queued', 'in_progress'].map(status => api.pages(
        'actions/workflows/' + request.workflow_id + '/runs?event=pull_request&branch=' + branch + '&status=' + status,
        'workflow_runs')))).flat().filter(older);
    for (const candidate of new Map(candidates.map(run => [run.id, run])).values()) {
        for (let check = 0; check < 4; check++) {
            const current = await readRun(request.id);
            if (!samePull(current) || current.head_sha !== request.head_sha || !active(current) ||
                current.status === 'in_progress') return;
            const old = await readRun(candidate.id);
            if (!older(old) || old.id !== candidate.id || !positiveId(old.run_attempt) || old.run_attempt !== candidate.run_attempt) break;
            if (current.status === 'pending' && queuedFinalizer(await jobs(old), old.id)) {
                const open = await api.pages('pulls?state=open');
                const protectedSha = open.some(pr => pr.head?.sha === old.head_sha);
                if (protectedSha) break;
                // A stacked PR may retain its former base SHA. Protect the actual default ref.
                if (!repository.default_branch) return;
                const main = await api.get('git/ref/heads/' + encodeURIComponent(repository.default_branch));
                if (!main.object?.sha || main.object.sha === old.head_sha) break;
                // Re-read the attempt, jobs and live PR after discovery. Never use branch names alone for a mutation.
                const fresh = await readRun(old.id);
                if (!older(fresh) || fresh.head_sha !== old.head_sha || fresh.run_attempt !== old.run_attempt ||
                    !queuedFinalizer(await jobs(fresh), old.id)) break;
                const replacement = await readRun(request.id);
                if (!samePull(replacement) || replacement.head_sha !== request.head_sha || replacement.status !== 'pending') return;
                const pr = await api.get('pulls/' + pull.number);
                if (pr.state !== 'open' || pr.number !== pull.number || pr.base?.repo?.id !== repository.id ||
                    pr.head?.repo?.id !== request.head_repository.id || pr.head?.ref !== request.head_branch ||
                    pr.head?.sha !== request.head_sha) return;
                await api.post('actions/runs/' + old.id + '/force-cancel');
                console.log('Requested cancellation of superseded run %d for PR #%d', old.id, pull.number);
                break;
            }
            if (check < 3) await wait(10_000);
        }
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const repository = process.env.GITHUB_REPOSITORY;
    if (!/^[\w.-]+\/[\w.-]+$/.test(repository ?? '')) throw new Error('Expected a GitHub repository');
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    if (event.repository?.full_name !== repository) throw new Error('Workflow event repository mismatch');
    const gh = (path, flags = []) => execFileSync('gh', ['api', ...flags, 'repos/' + repository + '/' + path],
        {encoding:'utf8', maxBuffer:16 * 1024 * 1024, timeout:30_000});
    await releaseSuperseded(event, {
        get: path => JSON.parse(gh(path)),
        pages: (path, key) => JSON.parse(gh(path + (path.includes('?') ? '&' : '?') + 'per_page=100',
            ['--paginate', '--slurp'])).flatMap(page => key ? page[key] : page),
        post: path => gh(path, ['--method', 'POST'])
    });
}
