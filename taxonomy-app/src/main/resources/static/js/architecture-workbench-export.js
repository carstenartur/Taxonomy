/* Download adapters for immutable architecture snapshot formats. */
(function () {
    'use strict';

    const body = document.body;
    const projectId = body.dataset.projectId;
    const snapshotId = body.dataset.snapshotId;

    function bind(buttonId, urlFactory) {
        const button = document.getElementById(buttonId);
        if (!button) return;
        button.addEventListener('click', function () {
            window.location.assign(urlFactory(projectId, snapshotId));
        });
    }

    bind(
        'downloadArchitectureArchiMate',
        ArchitectureWorkbenchApi.archiMateUrl);
    bind(
        'downloadArchitectureVisio',
        ArchitectureWorkbenchApi.visioUrl);
}());
