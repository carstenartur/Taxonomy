/* Download adapters for immutable architecture snapshot formats. */
(function () {
    'use strict';

    function source() {
        const workspaceSource = window.TaxonomyArchitectureWorkspace?.getSource?.();
        if (workspaceSource) return workspaceSource.kind === 'snapshot' ? workspaceSource : null;
        return document.body.__architectureSnapshot?.getSource?.()
            || Object.assign({}, window.TaxonomyArchitectureWorkflow?.getScope?.(), document.body.dataset);
    }

    function attachmentName(response) {
        const header = response.headers.get('Content-Disposition') || '';
        if (!/^attachment(?:;|$)/i.test(header.trim())) return null;
        const encoded = /;\s*filename\*\s*=\s*UTF-8'[^']*'([^;]+)/i.exec(header);
        if (encoded) {
            try { return decodeURIComponent(encoded[1].trim()); } catch (error) { /* Try the plain filename. */ }
        }
        const plain = /;\s*filename\s*=\s*(?:"([^"]+)"|([^;]+))/i.exec(header);
        return plain && (plain[1] || plain[2].trim());
    }

    function exportSignal() {
        const snapshot = document.getElementById('architectureSnapshotSource')?.__architectureSnapshot
            || document.body.__architectureSnapshot;
        return snapshot?.getExportSignal();
    }

    let previousDownloadError;
    function bind(buttonId, urlFactory) {
        const button = document.getElementById(buttonId);
        if (!button) return;
        button.addEventListener('click', async function () {
            const selected = source();
            const signal = exportSignal();
            if (button.disabled || !selected || !signal || signal.aborted) return;
            const alert = document.getElementById('architectureAlert');
            if (previousDownloadError && alert.textContent === previousDownloadError) {
                alert.textContent = '';
                alert.classList.add('d-none');
                previousDownloadError = null;
            }
            try {
                const url = urlFactory(selected.projectId, selected.snapshotId, selected);
                const result = await ArchitectureWorkbenchApi.download(url, selected, signal);
                if (signal.aborted) return;
                const name = attachmentName(result.response);
                if (!name) throw new Error(window.TaxonomyI18n?.t?.('workbench.ui.missingAttachment') || 'The server did not return a named architecture attachment.');
                const link = document.createElement('a');
                link.href = URL.createObjectURL(result.blob);
                link.download = name;
                document.body.appendChild(link);
                try { link.click(); } finally {
                    link.remove();
                    window.setTimeout(() => URL.revokeObjectURL(link.href), 1000);
                }
            } catch (error) {
                if (signal.aborted || error.code === 'ABORTED') return;
                previousDownloadError = error.status
                    ? window.TaxonomyI18n?.t?.('export.failed.http', error.status) || error.message : error.message;
                alert.textContent = previousDownloadError;
                alert.classList.remove('d-none');
            }
        });
    }

    bind('downloadArchitectureSvg', ArchitectureWorkbenchApi.svgUrl);
    bind('downloadArchitecturePdf', ArchitectureWorkbenchApi.pdfUrl);
    bind('downloadArchitectureWord', ArchitectureWorkbenchApi.architectureWordUrl);
    const decision = document.getElementById('downloadDecisionWord');
    if (decision) decision.addEventListener('click', function () {
        const selected=source();
        if (decision.disabled || !selected) return;
        window.TaxonomyDecisionExport.openSaved({projectId:selected.projectId, snapshotId:selected.snapshotId, language: document.documentElement.lang,
            scope: Object.assign({}, selected), sourceSignal: exportSignal(), api: window.TaxonomyPortfolioApi});
    });

    bind(
        'downloadArchitectureArchiMate',
        ArchitectureWorkbenchApi.archiMateBundleUrl);
    bind(
        'downloadArchitectureVisio',
        ArchitectureWorkbenchApi.visioBundleUrl);
}());
