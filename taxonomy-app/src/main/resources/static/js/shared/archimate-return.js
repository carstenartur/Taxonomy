/* Explicit, content-preserving Archi return review. Ordinary XML import remains separate. */
(function () {
    'use strict';
    function initialize() {
        var original = document.getElementById('archiReturnOriginal');
        if (!original) return;
        var returned = document.getElementById('archiReturnFile');
        var preview = document.getElementById('archiReturnPreview');
        var apply = document.getElementById('archiReturnApply');
        var accept = document.getElementById('archiReturnAccept');
        var status = document.getElementById('archiReturnStatus');
        var losses = document.getElementById('archiReturnLosses');
        var fingerprint = null, generation = 0, busy = false, writableContext = false;
        function t() { return window.TaxonomyI18n.t.apply(window.TaxonomyI18n, arguments); }
        function canApply() { return writableContext && window.TaxonomyRoleSurface?.canMutateArchitecture() === true; }
        function controls() {
            original.disabled = busy; returned.disabled = busy;
            preview.disabled = busy || !original.files[0] || !returned.files[0];
            accept.disabled = busy || !fingerprint || !canApply();
            apply.disabled = busy || !fingerprint || !accept.checked || !canApply();
        }
        function invalidate() {
            generation++; fingerprint = null; writableContext = false; accept.checked = false; busy = false;
            losses.replaceChildren(); status.textContent = ''; controls();
        }
        function form() {
            var data = new FormData();
            data.append('original', original.files[0]); data.append('file', returned.files[0]);
            return data;
        }
        original.addEventListener('change', invalidate);
        returned.addEventListener('change', invalidate);
        accept.addEventListener('change', controls);
        preview.addEventListener('click', async function () {
            if (preview.disabled) return;
            invalidate(); busy = true; controls();
            var current = generation;
            status.textContent = t('archi.return.loading');
            try {
                var result = await window.ImportApi.previewArchiReturn(form());
                if (current !== generation) return;
                fingerprint = result.reviewFingerprint;
                writableContext = result.writableContext === true;
                status.textContent = t('archi.return.previewSummary', result.elementsMatched, result.relationsParsed,
                    result.relationsSkipped, result.relationsRejected) + '\n' + (result.notes || []).join('\n');
                if (!writableContext) status.textContent += '\n' + t('archi.return.workspaceRequired');
                (result.losses || []).forEach(function (loss) {
                    var row = document.createElement('li');
                    row.textContent = [loss.scope, loss.id, loss.field, loss.kind, loss.rationale].join(' — ');
                    losses.appendChild(row);
                });
            } catch (error) {
                if (current === generation) status.textContent = error.message;
            } finally { if (current === generation) { busy = false; controls(); } }
        });
        apply.addEventListener('click', async function () {
            if (busy || !fingerprint || !accept.checked || !canApply()) return;
            var data = form(); data.append('reviewedFingerprint', fingerprint);
            var current = generation;
            busy = true; controls();
            try {
                var result = await window.ImportApi.applyArchiReturn(data);
                if (current !== generation) return;
                status.textContent = t('archi.return.applied', result.relationsImported, result.relationsSkipped, result.relationsRejected);
            } catch (error) {
                if (current === generation) status.textContent = error.message;
            } finally {
                if (current === generation) { fingerprint = null; accept.checked = false; busy = false; controls(); }
            }
        });
        document.addEventListener?.('taxonomy:roles-ready', controls);
        controls();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
    else initialize();
}());
