/*
 * Server-backed portfolio analysis job synchronization.
 *
 * The job centre persists browser history for convenience, but the server job
 * resource remains authoritative. Synchronize after analysis actions and each
 * successful project selection so navigation, a cleared browser store or adapter ordering can
 * never hide an accepted persisted job.
 */
(function () {
    'use strict';

    const retryDelaysMs = [250, 1500, 4000];
    const timers = new Set();
    const requests = new Set();
    let pageActive = true;
    let generation = 0;

    document.addEventListener('click', function (event) {
        if (!event.target.closest('#analyzeAllBtn, .requirement-analyze')) return;
        scheduleSynchronization();
    });
    document.addEventListener('taxonomy:portfolio-project-selected', function () {
        scheduleSynchronization();
    });
    document.addEventListener('taxonomy:portfolio-project-selecting', cancelSynchronization);
    window.addEventListener('pagehide', function () {
        pageActive = false;
        cancelSynchronization();
    });
    window.addEventListener('pageshow', function (event) {
        if (!event.persisted || pageActive) return;
        pageActive = true;
        scheduleSynchronization();
    });

    function cancelSynchronization() {
        generation += 1;
        timers.forEach(function (timer) { window.clearTimeout(timer); });
        timers.clear();
        requests.forEach(function (controller) { controller.abort(); });
        requests.clear();
    }

    function selectedProjectId() {
        return Number(window.localStorage.getItem('taxonomy.portfolio.projectId')) || null;
    }

    function stillCurrent(projectId, scheduledGeneration) {
        return pageActive && generation === scheduledGeneration && selectedProjectId() === projectId;
    }

    function scheduleSynchronization() {
        if (!pageActive) return;
        cancelSynchronization();
        const projectId = selectedProjectId();
        if (!projectId) return;
        const scheduledGeneration = generation;
        retryDelaysMs.forEach(function (delay) {
            const timer = window.setTimeout(function () {
                timers.delete(timer);
                return synchronizeCurrentProjectJobs(projectId, scheduledGeneration);
            }, delay);
            timers.add(timer);
        });
    }

    async function synchronizeCurrentProjectJobs(projectId, scheduledGeneration) {
        if (!stillCurrent(projectId, scheduledGeneration)) return;
        if (typeof window.taxonomyPortfolioRegisterJob !== 'function') return;
        const api = window.TaxonomyPortfolioApi;
        if (!api || typeof api.listAnalysisJobs !== 'function') return;

        const controller = new AbortController();
        requests.add(controller);
        try {
            const jobs = await api.listAnalysisJobs(projectId, { signal: controller.signal });
            if (!stillCurrent(projectId, scheduledGeneration) || !Array.isArray(jobs)) return;
            api.retainedAnalysisJobs(jobs, job => job).forEach(function (job) {
                if (!stillCurrent(projectId, scheduledGeneration) || !job || !job.id) return;
                const location = '/api/projects/' + encodeURIComponent(projectId)
                    + '/analysis-jobs/' + encodeURIComponent(job.id);
                window.taxonomyPortfolioRegisterJob(location, job);
            });
        } catch (error) {
            // The existing job centre exposes poll failures. Initial discovery
            // is best-effort and will be retried by the bounded schedule above.
        } finally {
            requests.delete(controller);
        }
    }
}());
