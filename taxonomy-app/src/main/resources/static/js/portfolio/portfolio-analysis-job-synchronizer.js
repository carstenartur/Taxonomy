/*
 * Server-backed portfolio analysis job synchronization.
 *
 * The job centre persists browser history for convenience, but the server job
 * resource remains authoritative. Synchronize after analysis actions and on
 * page startup so navigation, a cleared browser store or adapter ordering can
 * never hide an accepted persisted job.
 */
(function () {
    'use strict';

    const retryDelaysMs = [250, 1500, 4000];
    const maximumJobs = 20;

    document.addEventListener('click', function (event) {
        if (!event.target.closest('#analyzeAllBtn, .requirement-analyze')) return;
        scheduleSynchronization();
    });
    document.addEventListener('DOMContentLoaded', function () {
        scheduleSynchronization();
    });

    function scheduleSynchronization() {
        retryDelaysMs.forEach(function (delay) {
            window.setTimeout(synchronizeCurrentProjectJobs, delay);
        });
    }

    async function synchronizeCurrentProjectJobs() {
        if (typeof window.taxonomyPortfolioRegisterJob !== 'function') return;
        const projectId = Number(
            window.localStorage.getItem('taxonomy.portfolio.projectId')) || null;
        if (!projectId) return;

        try {
            const response = await window.fetch(
                '/api/projects/' + encodeURIComponent(projectId) + '/analysis-jobs', {
                    method: 'GET',
                    headers: { Accept: 'application/json' },
                    credentials: 'same-origin',
                    cache: 'no-store'
                });
            if (!response.ok) return;
            const jobs = await response.json();
            if (!Array.isArray(jobs)) return;
            jobs.slice(0, maximumJobs).forEach(function (job) {
                if (!job || !job.id) return;
                const location = '/api/projects/' + encodeURIComponent(projectId)
                    + '/analysis-jobs/' + encodeURIComponent(job.id);
                window.taxonomyPortfolioRegisterJob(location, job);
            });
        } catch (error) {
            // The existing job centre exposes poll failures. Initial discovery
            // is best-effort and will be retried by the bounded schedule above.
        }
    }
}());
