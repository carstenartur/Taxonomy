/* Download an exact saved revision/receipt. Does not save, refresh, adopt or navigate. */
window.TaxonomyReformulationReports = (function () {
    'use strict';
    function controls(options) {
        const captured = Object.freeze(Object.assign({}, options));
        const de = captured.language === 'de';
        const group = document.createElement('div');
        group.className = 'reformulation-controls';
        const explanation = document.createElement('p');
        explanation.className = 'small text-body-secondary';
        explanation.textContent = captured.commandId
            ? (de ? 'Genau diesen gespeicherten Übernahmebeleg exportieren.' : 'Export exactly this saved adoption receipt.')
            : (de ? 'Gespeicherte Revision exportieren. Ungespeicherte Bearbeitungen sind nicht enthalten.'
                  : 'Export the saved revision. Unsaved edits are not included.');
        const status = document.createElement('p');
        status.setAttribute('role', 'status');
        group.append(explanation);
        for (const [format, name] of [['json', 'JSON'], ['md', 'Markdown'], ['html', 'HTML']]) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'btn btn-outline-secondary';
            button.textContent = (de ? 'Exportieren: ' : 'Export: ') + name;
            button.dataset.reformulationReport = format;
            let pending = false;
            button.addEventListener('click', async () => {
                if (pending) return;
                pending = true; button.disabled = true; status.textContent = '';
                try {
                    const api = window.TaxonomyPortfolioApi;
                    const response = captured.commandId
                        ? await api.downloadReformulationAdoptionReport(captured.projectId, captured.requirementId, captured.proposalId, captured.commandId, format)
                        : await api.downloadReformulationReport(captured.projectId, captured.requirementId, captured.proposalId, captured.revision, format);
                    const expected = {json:'application/json',md:'text/markdown',html:'text/html'}[format];
                    if ((response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase() !== expected
                            || !(response.headers.get('Content-Disposition') || '').startsWith('attachment;')
                            || !/^[a-f0-9]{64}$/.test(response.headers.get('X-Content-SHA256') || ''))
                        throw new Error(de ? 'Keine gültige Berichtsausgabe erhalten.' : 'No valid report response received.');
                    const blob = await response.blob();
                    if (button.isConnected === false) return; // A different selection replaced this inspector.
                    const url = URL.createObjectURL(blob);
                    const link = document.createElement('a');
                    const identity = captured.commandId ? 'adoption-' + captured.commandId : 'revision-' + captured.revision;
                    link.href = url;
                    link.download = 'reformulation-' + captured.proposalId + '-' + identity + '.' + format;
                    try { document.body.append(link); link.click(); }
                    finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 30000); }
                    status.textContent = de ? 'Gespeicherter Stand exportiert.' : 'Saved record exported.';
                } catch (error) {
                    if (button.isConnected !== false)
                        status.textContent = (de ? 'Export fehlgeschlagen: ' : 'Export failed: ') + error.message;
                } finally { pending = false; button.disabled = false; }
            });
            group.append(button);
        }
        group.append(status);
        return group;
    }
    return {controls};
}());
