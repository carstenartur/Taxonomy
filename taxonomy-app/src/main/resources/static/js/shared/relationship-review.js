/* The active architecture source owns the review surface and its available queues. */
(function () {
    'use strict';

    const panel = document.getElementById('proposalsPanel');
    if (!panel) return;
    const byId = id => document.getElementById(id);
    const queues = Array.from(panel.querySelectorAll('[data-review-queue]'));
    const sections = Array.from(panel.querySelectorAll('[data-review-section]'));
    const available = {
        analysis: ['analysis', 'saved', 'focus'],
        working: ['saved', 'focus'],
        snapshot: ['focus']
    };
    const selected = { analysis: 'analysis', working: 'saved', snapshot: 'focus' };
    let source = queues.length ? 'analysis' : 'working';
    let subTab = 'arch-overview';

    function showQueue(queue) {
        if (!available[source].includes(queue)) return false;
        selected[source] = queue;
        queues.forEach(button => {
            button.hidden = !available[source].includes(button.dataset.reviewQueue);
            button.setAttribute('aria-pressed', String(button.dataset.reviewQueue === queue));
        });
        sections.forEach(section => { section.hidden = section.dataset.reviewSection !== queue; });
        const badge = byId('proposalPendingBadge');
        if (badge) badge.hidden = queue !== 'saved';
        return true;
    }

    function place(tab) {
        if (tab && tab !== subTab && source === 'analysis' && tab === 'arch-relations' && selected.analysis === 'analysis') {
            showQueue('saved');
        }
        if (tab) subTab = tab;
        if (!queues.length) return;
        const home = byId(source === 'working' ? 'workingProposalInbox' : source === 'snapshot' ? 'snapshotProposalInbox'
            : subTab === 'arch-relations' ? 'analysisProposalInbox' : 'analysisProposalOverview');
        if (home && panel.parentNode !== home) home.appendChild(panel);
    }

    function setSource(kind) {
        source = kind;
        byId('relationshipReviewWorkingFocus').hidden = kind !== 'working';
        byId('relationshipReviewSwitch').hidden = available[kind].length < 2;
        showQueue(selected[kind]);
        place();
        panel.hidden = false;
    }

    function open(queue) {
        if (!showQueue(queue) || panel.hidden) return false;
        if (source === 'analysis' && subTab === 'arch-export') {
            document.dispatchEvent(new Event('taxonomy:relationship-review-overview'));
        }
        const heading = queue === 'focus' ? byId(source === 'working' ? 'editorLensTitle' : 'architectureSourceLensTitle')
            : byId('relationshipReviewTitle');
        heading?.scrollIntoView({block: 'nearest'});
        heading?.focus({preventScroll: true});
        return true;
    }

    queues.forEach(button => button.addEventListener('click', () => showQueue(button.dataset.reviewQueue)));
    if (queues.length) place();
    window.TaxonomyRelationshipReview = Object.freeze({ open, place, setSource });
}());
