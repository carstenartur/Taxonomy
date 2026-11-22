(function () {
    'use strict';
    const card = document.getElementById('mailAnalysisStatus');
    if (!card) return;
    const labels = new Map(Array.from(card.querySelector('[data-state-labels]').content.querySelectorAll('[data-state]'),
        node => [node.dataset.state, node.textContent]));
    const state = card.querySelector('[data-job-state]');
    const position = card.querySelector('[data-job-position]');
    const queued = card.querySelector('[data-job-queued]');
    const capacity = card.querySelector('[data-job-capacity]');
    const running = card.querySelector('[data-job-running]');
    const delivering = card.querySelector('[data-job-delivering]');
    const expired = card.querySelector('[data-job-expired]');
    const error = card.querySelector('[data-job-error]');
    let delay = 3000;
    function show(node, value) {
        if (node.textContent !== value) node.textContent = value;
    }
    async function refresh() {
        if (!document.hidden) {
            try {
                const response = await fetch(card.dataset.url, {cache:'no-store', redirect:'error'});
                if (!response.ok) {
                    if (response.status === 404) {
                        error.hidden = true;
                        expired.hidden = false;
                        return;
                    }
                    throw new Error('Status unavailable');
                }
                const job = await response.json();
                show(state, labels.get(job.state) || job.state);
                show(position, String(job.position ?? '—'));
                show(queued, String(job.queuedJobs));
                show(capacity, String(job.capacity));
                show(running, String(job.runningJobs));
                show(delivering, String(job.deliveringJobs));
                error.hidden = true;
                delay = job.finished ? 15000 : 3000;
            } catch {
                error.hidden = false;
                delay = Math.min(delay * 2, 30000);
            }
        }
        window.setTimeout(refresh, delay);
    }
    window.setTimeout(refresh, delay);
}());
