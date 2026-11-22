/* Explicit preparation of one reviewed delivery view and its R7 derivation. */
(function () {
    'use strict';
    function create(options) {
        const {root, api, t} = options;
        const el = id => root.querySelector('#' + id);
        const panel = el('editorAdmbwPreparation');
        let view, scopeKey, standKey, available = false, basis, preview, read, download;
        // A transport failure may arrive after the server has saved the operation.
        // Keep its original File, context, operation ID and fingerprint across model refreshes.
        const operations = new Map();
        const text = (key, ...args) => t('editor.prep.' + key, ...args);
        const file = () => el('prepBasis').files?.[0];
        const status = value => { el('prepStatus').textContent = value; };
        const ctx = () => view.document.context;
        const working = () => view?.document.source === 'WORKSPACE_REVISION';
        const services = () => (view?.model?.elements || []).filter(e => ['CoreService','COIService'].includes(e.type));
        const requirements = () => view?.model?.requirements || [];
        function scopeOf(context) {
            return JSON.stringify([context.repositoryId, context.workspaceScopeKey,
                context.workspaceId !== undefined ? (context.workspaceId ?? '') : context.workspaceScopeKey === '__shared__' ? '' : context.workspaceScopeKey,
                context.branch, context.actor]);
        }
        function select(id, choices, value) {
            const field = el(id);
            field.replaceChildren(new Option(text('choose'), ''));
            for (const [key, label] of choices) field.add(new Option(label, key));
            field.value = choices.some(([key]) => key === value) ? value : '';
        }
        function selectModelObject(id, rows) {
            const value = rows.length === 1 ? rows[0].id : el(id).value;
            select(id, rows.map(row => [row.id, (row.title || row.id) + ' · ' + row.id]), value);
        }
        function selectReferences() {
            const items = (basis?.references || []).filter(r => r.rootGuid === el('prepRoot').value);
            const choices = rows => rows.map(r => [r.guid, r.name + ' · ' + r.packagePath.join(' / ') + ' · ' + r.guid]);
            select('prepCapability', choices(items.filter(r => r.stereotype === 'Capability')));
            select('prepSubject', choices(items.filter(r => ['OperationalRole','OperationalArchitecture'].includes(r.stereotype))));
        }
        function requirementText() {
            const req = requirements().find(r => r.id === el('prepRequirement').value);
            el('prepSource').value = req?.text || '';
            el('prepQuote').setCustomValidity('');
            el('prepUseSelection').disabled = !el('prepSource').value;
        }
        function resetReview() {
            read?.abort();
            read = null;
            preview = null;
            el('prepResult').replaceChildren();
            el('prepResult').hidden = true;
            el('prepRelationshipReviewed').checked = el('prepDerivationReviewed').checked = false;
            status('');
        }
        function controls() {
            const operation = operations.get(scopeKey), state = operation?.state;
            const pending = ['SENDING','UNKNOWN','CHECKPOINT_PENDING'].includes(state);
            const retry = state === 'UNKNOWN' || state === 'CHECKPOINT_PENDING';
            panel.hidden = !working() || !view.mayEdit && !operation;
            const missing = !requirements().length ? 'missingRequirement' : !services().length ? 'missingService' : null;
            const prerequisites = el('prepPrerequisites');
            prerequisites.hidden = !missing;
            if (missing) prerequisites.textContent = text(missing);
            const evidence = el('prepServiceTypeEvidence');
            const detail = window.TaxonomyAdmbwCoverage.serviceTypeEvidence(basis,
                view?.document.context.revision, el('prepRoot').value, el('prepService').value, t);
            if (evidence.textContent !== detail) evidence.textContent = detail;
            evidence.hidden = !detail;
            el('prepInputs').disabled = !available || !view?.mayEdit || pending || !!missing;
            el('prepInspect').disabled = !file() || !!read;
            el('prepPreview').disabled = !basis || !!read;
            el('prepReview').hidden = !preview || pending || operation?.preview === preview;
            el('prepReview').disabled = !available || !view?.mayEdit || pending;
            el('prepAccept').disabled = !preview || !el('prepRelationshipReviewed').checked
                || !el('prepDerivationReviewed').checked || !el('prepRationale').value.trim();
            el('prepRetry').hidden = !retry;
            el('prepRetry').disabled = !available;
            el('prepReceipt').textContent = operation ? text('state.' + state) + ' · ' + operation.request.selection.projectCode + ' · ' + operation.preview.viewId
                + (operation.publication?.checkpoint ? ' · ' + operation.publication.checkpoint.commitId : '')
                + (operation.publication?.detail ? ' · ' + operation.publication.detail : '') : '';
            el('prepDownload').hidden = !(state === 'CHECKPOINTED' && exportable(operation.preview));
            el('prepDownload').disabled = !available || !!download;
        }
        function exportable(result) {
            const report = result?.assessment;
            return result?.qeaIssues?.length === 0 && report?.coverage && [report.capabilityToService, report.requirementDerivation].every(v =>
                v.status === 'REVIEW_REQUIRED' && v.issues?.length === 0);
        }
        function update(next, enabled) {
            const context = next?.document.context, nextScope = context ? scopeOf(context) : null;
            const nextStand = context ? JSON.stringify([nextScope, context.revision, context.commit, next.document.source]) : null;
            view = next;
            available = Boolean(enabled);
            if (scopeKey !== nextScope) {
                scopeKey = nextScope;
                basis = null;
                download?.abort(); download = null;
                el('prepForm').reset();
                select('prepRoot', []);
                selectReferences();
            }
            if (standKey !== nextStand) {
                standKey = nextStand;
                resetReview();
                const priorRequirement = el('prepRequirement').value;
                const priorText = el('prepSource').value;
                selectModelObject('prepService', services());
                selectModelObject('prepRequirement', requirements());
                requirementText();
                if (el('prepRequirement').value !== priorRequirement || el('prepSource').value !== priorText) el('prepQuote').value = '';
            }
            controls();
        }
        function node(tag, value) {
            const result = document.createElement(tag); result.textContent = value; return result;
        }
        function showPreview(result, selection) {
            const area = el('prepResult');
            const heading = node('h3', text('review')); heading.id = 'prepResultTitle';
            area.replaceChildren(heading);
            for (const [profile, viewpoint] of [[result.assessment.capabilityToService, 'C1–S1'],
                    [result.assessment.requirementDerivation, 'R7']]) {
                const section = document.createElement('section');
                section.className = 'editor-admbw-profile';
                section.append(node('h4', profile.viewpoint || profile.name || viewpoint),
                    node('p', t('editor.admbw.status.' + profile.status)));
                const findings = [
                    ...profile.issues.map(issue => issue.object + ' · ' + issue.detail),
                    ...(profile.requiredReviews || []).map(item => window.TaxonomyAdmbwCoverage.message(item, t))
                ];
                if (findings.length) {
                    const list = document.createElement('ul');
                    list.append(...findings.map(item => node('li', item)));
                    section.append(list);
                }
                area.append(section);
            }
            area.append(
                node('p', el('prepService').selectedOptions[0].textContent + ' → ' + el('prepCapability').selectedOptions[0].textContent),
                node('p', result.requirementId + ' · ' + text('version', result.requirementVersion) + ' · SHA-256 ' + result.requirementContentHash),
                node('p', t('editor.planning.choice.' + selection.requirementType) + ' · ' + el('prepSubject').selectedOptions[0].textContent),
                node('p', 'QEA SHA-256 ' + result.basisSha256));
            for (const note of result.notes) area.append(node('p', window.TaxonomyAdmbwCoverage.message(note, t)));
            for (const issue of result.qeaIssues || []) area.append(node('p', issue.object + ' · ' + issue.detail));
            area.append(window.TaxonomyAdmbwCoverage.render(result.assessment.coverage, t));
            for (const change of result.changes) {
                const details = document.createElement('details');
                details.append(node('summary', change.id));
                if (change.before != null) details.append(node('p', text('before')), node('pre', change.before));
                if (change.after != null) details.append(node('p', text('after')), node('pre', change.after));
                area.append(details);
            }
            area.hidden = false;
        }
        function exactQuote(quote) {
            const source = requirements().find(r => r.id === el('prepRequirement').value)?.text || '';
            if (source.includes(quote)) return quote;
            // HTML textareas normalize all line endings to LF. Recover the original
            // source slice so the server can verify a verbatim quote, including CRLF.
            const offset = source.replace(/\r\n?/g, '\n').indexOf(quote);
            if (offset < 0) return null;
            let raw = 0, start = 0;
            for (let normalized = 0; normalized < offset + quote.length; normalized++) {
                if (normalized === offset) start = raw;
                if (source[raw] === '\r' && source[raw + 1] === '\n') raw++;
                raw++;
            }
            return source.slice(start, raw);
        }
        function selection() {
            return {projectCode:el('prepProject').value.trim(), rootPackageGuid:el('prepRoot').value,
                serviceId:el('prepService').value, capabilityGuid:el('prepCapability').value,
                requirementId:el('prepRequirement').value, requirementType:el('prepType').value,
                subjectGuid:el('prepSubject').value, constraintText:el('prepConstraint').value, evidenceQuote:exactQuote(el('prepQuote').value)};
        }
        function quoteProblem(quote, maxLength) {
            if (quote == null) return text('quoteMismatch');
            if (quote.length > maxLength) return text('selectionTooLong');
            return '';
        }
        function inputChanged(event) {
            const id = event.target.id;
            if (id === 'prepBasis') {
                basis = null; select('prepRoot', []); selectReferences();
            }
            if (id === 'prepRoot') selectReferences();
            if (id === 'prepRequirement') { requirementText(); el('prepQuote').value = ''; }
            if (id === 'prepQuote' || id === 'prepRationale') el(id).setCustomValidity('');
            resetReview(); controls();
        }
        el('prepInputs').addEventListener('input', inputChanged);
        el('prepInputs').addEventListener('change', inputChanged);
        el('prepReview').addEventListener('change', controls);
        el('prepUseSelection').addEventListener('click', () => {
            const source = el('prepSource'), quote = el('prepQuote');
            const selected = source.value.slice(source.selectionStart, source.selectionEnd);
            const problem = selected.trim() ? quoteProblem(exactQuote(selected), quote.maxLength) : text('selectQuote');
            if (problem) {
                status(problem);
                source.focus();
                return;
            }
            quote.value = selected;
            inputChanged({target: quote});
            quote.focus();
        });
        el('prepInspect').addEventListener('click', async () => {
            const sourceFile = file();
            if (!available || !view?.mayEdit || !sourceFile) return;
            resetReview(); basis = null; select('prepRoot', []); selectReferences();
            if (!sourceFile.size || sourceFile.size > 32 * 1024 * 1024) { status(text('fileSize')); controls(); return; }
            const ticket = read = new AbortController();
            status(text('inspecting')); controls();
            try {
                const result = await api.inspectAdmbwBasis(ctx(), sourceFile, ticket.signal);
                if (ticket !== read) return;
                basis = result;
                select('prepRoot', result.roots.map(r => [r.guid, r.name + ' · ' + r.guid]),
                    result.roots.length === 1 ? result.roots[0].guid : null);
                selectReferences();
                status(text('inspected', result.references.length) + ' · SHA-256 ' + result.sha256);
            } catch (error) { if (ticket === read) options.report(error, el('prepStatus')); }
            finally { if (ticket === read) { read = null; controls(); } }
        });
        el('prepForm').addEventListener('submit', async event => {
            event.preventDefault();
            if (!basis || !available || !view?.mayEdit || read) return;
            const choice = selection();
            const quote = el('prepQuote');
            quote.setCustomValidity(quoteProblem(choice.evidenceQuote, quote.maxLength));
            el('prepRationale').setCustomValidity(el('prepRationale').value.trim() ? '' : text('rationaleRequired'));
            if (!el('prepForm').reportValidity()) return;
            resetReview();
            const ticket = read = new AbortController(), id = crypto.randomUUID();
            const request = {context:ctx(), metadata:{commandId:id, correlationId:id, causationId:id, rationale:el('prepRationale').value.trim()},
                selection:choice, basisSha256:basis.sha256, previewFingerprint:null, relationshipReviewed:false, derivationReviewed:false};
            const sourceFile = file();
            status(text('previewing')); controls();
            try {
                const result = await api.previewAdmbwPreparation(request, sourceFile, ticket.signal);
                if (ticket !== read) return;
                if (result.basisSha256 !== basis.sha256 || result.requirementId !== choice.requirementId) throw new Error(text('unexpected'));
                showPreview(result, choice);
                preview = Object.assign({}, result, {request, file:sourceFile});
                status(text('ready'));
            } catch (error) { if (ticket === read) options.report(error, el('prepStatus')); }
            finally { if (ticket === read) { read = null; controls(); } }
        });
        async function publish(operation) {
            const originalScope = scopeOf(operation.request.context);
            operation.state = 'SENDING';
            operations.set(originalScope, operation); controls();
            try {
                const result = await api.acceptAdmbwPreparation(operation.request, operation.file);
                operation.publication = result;
                operation.state = result.state;
                if (!['CHECKPOINTED','CHECKPOINT_PENDING','RECONCILIATION_REQUIRED'].includes(result.state)
                        || result.state === 'CHECKPOINTED' && !/^[a-f0-9]{40}$/i.test(result.checkpoint?.commitId)) {
                    throw new Error(text('unexpected'));
                }
            } catch (error) {
                if (error.status >= 400 && error.status < 500 && ![408,429].includes(error.status)) {
                    operations.delete(originalScope);
                    if (scopeKey === originalScope) resetReview();
                } else operation.state = 'UNKNOWN';
                if (scopeKey === originalScope) options.report(error, el('prepStatus'));
                controls(); return;
            }
            controls();
            if (scopeKey === originalScope && available && working() && operation.state === 'CHECKPOINTED') {
                await options.onAccepted(operation.publication, operation.request.context, operation.preview.viewId);
            }
        }
        el('prepAccept').addEventListener('click', () => {
            if (!available || !view?.mayEdit || !preview || el('prepAccept').disabled || el('prepReview').hidden) return;
            const request = Object.assign({}, preview.request, {previewFingerprint:preview.fingerprint, relationshipReviewed:true, derivationReviewed:true});
            publish({request, file:preview.file, preview});
        });
        el('prepRetry').addEventListener('click', () => {
            const operation = operations.get(scopeKey);
            if (available && ['UNKNOWN','CHECKPOINT_PENDING'].includes(operation?.state)) publish(operation);
        });
        el('prepDownload').addEventListener('click', async () => {
            const operation = operations.get(scopeKey);
            if (!available || download || operation?.state !== 'CHECKPOINTED' || !exportable(operation.preview)) return;
            const ticket = download = new AbortController();
            controls();
            try {
                const {request, publication} = operation, selected = request.selection;
                const checkpoint = Object.create(request.context);
                Object.defineProperty(checkpoint, 'commit', {value:publication.checkpoint.commitId});
                const blob = await api.admbwQea(checkpoint, selected.projectCode, operation.preview.viewId, selected.rootPackageGuid, operation.file, ticket.signal);
                if (ticket !== download || operation !== operations.get(scopeKey) || !available) return;
                const link = document.createElement('a'), url = URL.createObjectURL(blob);
                link.href = url; link.download = selected.projectCode + '-admbw.qea';
                document.body.append(link); link.click(); link.remove();
                window.setTimeout(() => URL.revokeObjectURL(url), 1000);
            } catch (error) { if (ticket === download) options.report(error, el('prepStatus')); }
            finally { if (ticket === download) { download = null; controls(); } }
        });
        return {update};
    }
    window.TaxonomyAdmbwPreparation = {create};
}());
