/* Startup presentation shared by the initial catalogue read and its status polling. */
window.TaxonomyStartupProgress = (function () {
    'use strict';
    var blockedAnalysis = false;
    var phases = new Set(['STARTING', 'LOADING_CATALOGUE', 'CHECKING_NODE_INDEX', 'INDEXING_NODES',
        'CHECKING_RELATION_INDEX', 'INDEXING_RELATIONS', 'WAITING_FOR_TAXONOMY', 'LOADING_MODEL',
        'CHECKING_HISTORY_INDEX', 'INDEXING_HISTORY', 'READY']);
    function text(id, value) {
        var node = document.getElementById(id);
        if (node) node.textContent = value;
    }
    function render(snapshot) {
        var t = TaxonomyI18n.t;
        var ready = snapshot.initialized === true;
        var failed = snapshot.phase === 'FAILED';
        var embedding = snapshot.embedding || {state: 'DISABLED'};
        var semanticPending = ready && ['WAITING_FOR_TAXONOMY', 'LOADING_MODEL',
            'INDEXING_NODES', 'INDEXING_RELATIONS'].includes(embedding.state);
        var semanticFailed = ready && ['FAILED', 'PARTIAL'].includes(embedding.state);
        var history = snapshot.history?.state || 'DISABLED';
        var historyPending = ready && ['WAITING', 'CHECKING', 'REBUILDING'].includes(history);
        var historyFailed = ready && ['FAILED', 'STOPPED'].includes(history);
        var show = !ready || semanticPending || semanticFailed || historyPending || historyFailed;
        var banner = document.getElementById('startupBanner');
        if (banner) {
            banner.classList.toggle('d-none', !show);
            banner.classList.toggle('alert-danger', failed);
            banner.classList.toggle('alert-warning', semanticFailed || historyFailed);
            banner.classList.toggle('alert-info', !failed && !semanticFailed && !historyFailed);
        }
        var button = document.getElementById('analyzeBtn');
        if (button && !ready) {
            button.disabled = true;
            button.title = t('browse.loading.wait');
            blockedAnalysis = true;
        } else if (button && blockedAnalysis) {
            button.disabled = false;
            button.title = t('browse.analyze.btn.title');
            blockedAnalysis = false;
        }
        // Later embedding updates must not re-enable a button disabled by a running analysis.
        text('startupBannerTitle', t(failed ? 'startup.ui.failedTitle'
            : historyFailed ? 'startup.ui.historyFailedTitle'
            : semanticFailed ? 'startup.ui.semanticFailedTitle'
            : historyPending ? 'startup.ui.searchTitle'
            : ready ? 'startup.ui.semanticTitle' : 'startup.ui.title'));
        text('startupBannerHelp', t(failed ? 'startup.ui.failedHelp'
            : historyFailed ? 'startup.ui.historyFailedHelp'
            : semanticFailed ? 'startup.ui.semanticFailedHelp'
            : ready ? 'startup.ui.semanticHelp' : 'startup.ui.help'));
        text('startupHistoryStatus', historyPending || historyFailed ? t('startup.history.' + history) : '');

        var progress = ready ? semanticPending ? embedding : historyPending
            ? {phase: history === 'REBUILDING' ? 'INDEXING_HISTORY' : 'CHECKING_HISTORY_INDEX'} : embedding
            : snapshot.progress || {};
        var phase = progress.phase || (ready ? embedding.state : snapshot.phase);
        var detail = failed ? t('startup.ui.failureCode', snapshot.errorCode || 'INITIALIZATION_FAILED')
            : historyFailed && !semanticPending ? t('startup.ui.failureCode', 'COMMIT_SEARCH_INIT_FAILED')
            : semanticFailed ? t('startup.ui.semanticFailure')
            : phases.has(phase) ? t('startup.phase.' + phase)
            : snapshot.phaseMessage || t('startup.ui.statusLoading');
        text('startupBannerStatus', detail);

        var bar = document.getElementById('startupProgress');
        var determinate = Number.isSafeInteger(progress.processed) && Number.isSafeInteger(progress.total)
            && progress.total > 0 && progress.processed >= 0 && progress.processed <= progress.total;
        if (bar) {
            bar.hidden = failed || (ready && !semanticPending && !historyPending) || !show;
            if (determinate) {
                bar.setAttribute('max', progress.total);
                bar.setAttribute('value', progress.processed);
            } else {
                bar.removeAttribute('value');
                bar.setAttribute('max', '1');
            }
        }
        text('startupProgressCount', determinate && !failed && !semanticFailed
            ? t('startup.ui.count', progress.processed, progress.total,
                Math.floor(progress.processed / progress.total * 100)) : '');
        return {ready: ready, failed: failed, pending: !failed && (!ready || semanticPending || historyPending)};
    }
    return {render: render};
}());
