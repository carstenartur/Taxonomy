/* Explicit preparation of one reviewed delivery view and its R7 derivation. */
(function () {
    'use strict';
    function create(options) {
        const {root, api, t} = options;
        const el = id => root.querySelector('#' + id);
        const panel = el('editorAdmbwPreparation');
        let view, scopeKey, standKey, available = false, basis, preview, read, download, repair, findingsKey;
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
        const receiptKey = scope => 'taxonomy.admbw.delivery.' + scope;
        function remembered(scope) {
            try { return sessionStorage.getItem(receiptKey(scope)); }
            catch (error) { options.report(error, el('prepStatus')); return null; }
        }
        function remember(scope, id) {
            try {
                if (id) sessionStorage.setItem(receiptKey(scope), id);
                else sessionStorage.removeItem(receiptKey(scope));
            } catch (error) { options.report(error, el('prepStatus')); }
        }
        async function recover(operation, resume) {
            const scope = scopeOf(operation.request.context);
            operation.state = 'RECOVERING';
            operations.set(scope, operation); controls();
            try {
                const result = await api.recoverAdmbw(operation.request.context, operation.id, resume);
                const {delivery, publication} = result;
                if (!delivery || !['CHECKPOINTED','CHECKPOINT_PENDING','RECONCILIATION_REQUIRED'].includes(publication?.state)
                        || publication.state === 'CHECKPOINTED' && !/^[a-f0-9]{40}$/i.test(publication.checkpoint?.commitId))
                    throw new Error(text('unexpected'));
                operation.publication = publication;
                operation.request.selection = delivery;
                operation.request.basisSha256 = delivery.basisSha256;
                operation.preview = {viewId:delivery.viewId, assessment:result.assessment, qeaIssues:result.qeaIssues};
                operation.state = publication.state;
            } catch (error) {
                operation.state = error.status === 404 ? 'NO_RECEIPT' : 'UNKNOWN';
                if (scope === scopeKey) options.report(error, el('prepStatus'));
            } finally { controls(); }
        }
        function checkpointContext(operation) {
            const checkpoint = Object.create(operation.request.context);
            Object.defineProperty(checkpoint, 'commit', {value:operation.publication.checkpoint.commitId});
            return checkpoint;
        }
        function controls() {
            const operation = operations.get(scopeKey), state = operation?.state;
            const pending = ['SENDING','RECOVERING','UNKNOWN','CHECKPOINT_PENDING'].includes(state);
            const retry = ['UNKNOWN','NO_RECEIPT','CHECKPOINT_PENDING'].includes(state);
            panel.hidden = !working() || view.document.projectionState === 'HISTORICAL' || !view.mayEdit && !operation;
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
            el('prepReceipt').textContent = operation ? text('state.' + state) + (operation.request.selection?.projectCode ? ' · ' + operation.request.selection.projectCode : '')
                + (operation.publication?.checkpoint?.commitId ? ' · ' + operation.publication.checkpoint.commitId.slice(0, 12) : '')
                + (operation.publication?.detail ? ' · ' + operation.publication.detail : '') : '';
            const assessment = el('prepAssessment');
            assessment.hidden = !available || state !== 'CHECKPOINTED';
            if (assessment.hidden) assessment.removeAttribute('href');
            else assessment.href = api.admbwUrl(checkpointContext(operation), operation.request.selection.projectCode, operation.preview.viewId);
            el('prepDownload').hidden = !(state === 'CHECKPOINTED' && exportable(operation.preview));
            const findingKey = JSON.stringify([scopeKey, standKey, state, operation?.id || operation?.request.metadata?.commandId, available]);
            if (findingKey !== findingsKey) {
                findingsKey = findingKey;
                const list = el('prepAcceptedFindings');
                list.replaceChildren();
                if (state === 'CHECKPOINTED' && operation.preview?.assessment) {
                    const assessment = operation.preview.assessment;
                    const current = working() && operation.publication.context.revision === view.document.context.revision
                        && operation.publication.checkpoint.commitId === view.document.context.commit;
                    for (const issue of [...assessment.capabilityToService.issues, ...assessment.requirementDerivation.issues, ...operation.preview.qeaIssues]) {
                        list.append(finding(issue, available && current, false));
                    }
                }
                list.hidden = !list.children.length;
            }
            el('prepDeliveryFile').hidden = el('prepDownload').hidden || !operation?.restored;
            el('prepBasisDigest').textContent = !el('prepDeliveryFile').hidden ? 'SHA-256 ' + operation.request.basisSha256 : '';
            el('prepPreflight').hidden = el('prepDownload').hidden;
            el('prepPreflight').disabled = !available || !!download || !operation?.file;
            el('prepDownload').disabled = !available || !!download || !operation?.checkedFile || operation.file !== operation.checkedFile;
        }
        function exportable(result) {
            const report = result?.assessment;
            return result?.qeaIssues?.length === 0 && report?.coverage && [report.capabilityToService, report.requirementDerivation].every(v =>
                v.status === 'REVIEW_REQUIRED' && v.issues?.length === 0);
        }
        function update(next, enabled) {
            const context = next?.document.context, nextScope = context ? scopeOf(context) : null;
            const nextStand = context ? JSON.stringify([nextScope, context.revision, context.commit, next.document.source, next.document.projectionState]) : null;
            view = next;
            available = Boolean(enabled);
            if (scopeKey !== nextScope) {
                scopeKey = nextScope; repair = null;
                basis = null;
                download?.abort(); download = null;
                el('prepForm').reset(); el('prepDeliveryBasis').value = '';
                select('prepRoot', []);
                selectReferences();
            }
            if (standKey !== nextStand || !available) { download?.abort(); download = null; }
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
            if (available && working() && next.document.projectionState !== 'HISTORICAL' && !operations.has(nextScope)) {
                const id = remembered(nextScope);
                if (id) recover({id, restored:true, request:{context}}, false);
            }
            controls();
            if (repair && repair.scope === nextScope && available && next.mayEdit && context.revision > repair.revision) {
                repair = null;
                if (basis && file() && el('prepForm').checkValidity()) el('prepForm').requestSubmit();
            }
        }
        function node(tag, value) {
            const result = document.createElement(tag); result.textContent = value; return result;
        }
        function finding(issue, editable, recompute) {
            const row = node('li', [issue.code, issue.object, issue.detail].filter(Boolean).join(' · '));
            const target = editable && options.openFinding && window.TaxonomyAdmbwCoverage.findingTarget(view, issue);
            if (target) {
                const button = node('button', text('editFinding'));
                button.type = 'button';
                button.addEventListener('click', () => {
                    if (!available || !options.openFinding(target)) return;
                    if (recompute) repair = {scope:scopeKey, revision:ctx().revision};
                    resetReview(); controls();
                });
                row.append(button);
            }
            return row;
        }
        function showPreview(result, selection) {
            const area = el('prepResult');
            const heading = node('h3', text('assessment'));
            heading.id = 'prepResultTitle';
            heading.tabIndex = -1;
            area.replaceChildren(heading);
            for (const [profile, viewpoint] of [[result.assessment.capabilityToService, 'C1–S1'],
                    [result.assessment.requirementDerivation, 'R7']]) {
                const section = document.createElement('section');
                section.className = 'editor-admbw-profile';
                section.append(node('h4', profile.viewpoint || profile.name || viewpoint),
                    node('p', t('editor.admbw.status.' + profile.status)));
                if (profile.issues.length || profile.requiredReviews?.length) {
                    const list = document.createElement('ul');
                    for (const issue of profile.issues) list.append(finding(issue, true, true));
                    for (const item of profile.requiredReviews || []) list.append(node('li', window.TaxonomyAdmbwCoverage.message(item, t)));
                    section.append(list);
                }
                area.append(section);
            }
            for (const note of result.notes) area.append(node('p', window.TaxonomyAdmbwCoverage.message(note, t)));
            if (result.qeaIssues?.length) {
                const list = document.createElement('ul');
                for (const issue of result.qeaIssues) list.append(finding(issue, true, true));
                area.append(list);
            }
            area.append(window.TaxonomyAdmbwCoverage.render(result.assessment.coverage, t));
            const technicalDetails = document.createElement('details');
            technicalDetails.append(node('summary', text('technicalDetails')),
                node('p', el('prepService').selectedOptions[0].textContent + ' → ' + el('prepCapability').selectedOptions[0].textContent),
                node('p', result.requirementId + ' · ' + text('version', result.requirementVersion) + ' · SHA-256 ' + result.requirementContentHash),
                node('p', t('editor.planning.choice.' + selection.requirementType) + ' · ' + el('prepSubject').selectedOptions[0].textContent),
                node('p', 'QEA SHA-256 ' + result.basisSha256));
            for (const change of result.changes) {
                const details = document.createElement('details');
                details.append(node('summary', change.id));
                if (change.before != null) details.append(node('p', text('before')), node('pre', change.before));
                if (change.after != null) details.append(node('p', text('after')), node('pre', change.after));
                technicalDetails.append(details);
            }
            area.append(technicalDetails);

            const service = services().find(item => item.id === selection.serviceId);
            const capability = basis.references.find(item => item.guid === selection.capabilityGuid);
            const requirement = requirements().find(item => item.id === selection.requirementId);
            const subject = basis.references.find(item => item.guid === selection.subjectGuid);
            el('prepRelationshipSummary').textContent = text('relationshipSummary', service?.title || selection.serviceId,
                capability?.name || selection.capabilityGuid);
            el('prepDerivationSummary').textContent = text('derivationSummary', requirement?.title || selection.requirementId,
                text('version', result.requirementVersion), t('editor.planning.choice.' + selection.requirementType),
                subject?.name || selection.subjectGuid);
            el('prepConstraintSummary').textContent = selection.constraintText;
            el('prepEvidenceQuote').textContent = selection.evidenceQuote;
            area.hidden = false;
            heading.focus();
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
            const id = event.target.id; repair = null;
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
            const originalScope = scopeOf(operation.request.context), originalStand = standKey;
            const generation = options.generation?.();
            operation.state = 'SENDING';
            remember(originalScope, operation.request.metadata.commandId);
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
                    operations.delete(originalScope); remember(originalScope, null);
                    if (scopeKey === originalScope) resetReview();
                } else operation.state = 'UNKNOWN';
                if (scopeKey === originalScope) options.report(error, el('prepStatus'));
                controls(); return;
            }
            controls();
            if (scopeKey === originalScope && originalStand === standKey && available && working()
                    && view.document.projectionState !== 'HISTORICAL' && operation.state === 'CHECKPOINTED'
                    && generation === options.generation?.()) {
                await options.onAccepted(operation.publication, operation.request.context, operation.preview.viewId, generation);
            }
        }
        el('prepAccept').addEventListener('click', () => {
            if (!available || !view?.mayEdit || !preview || el('prepAccept').disabled || el('prepReview').hidden) return;
            const request = Object.assign({}, preview.request, {previewFingerprint:preview.fingerprint, relationshipReviewed:true, derivationReviewed:true});
            publish({request, file:preview.file, preview});
        });
        el('prepRetry').addEventListener('click', () => {
            const operation = operations.get(scopeKey);
            if (!available || !operation) return;
            if (operation.restored) recover(operation, operation.state === 'CHECKPOINT_PENDING');
            else if (['UNKNOWN','CHECKPOINT_PENDING'].includes(operation.state)) publish(operation);
        });
        el('prepDeliveryBasis').addEventListener('change', () => {
            const operation = operations.get(scopeKey);
            if (!operation?.restored) return;
            download?.abort(); download = null;
            operation.file = el('prepDeliveryBasis').files[0]; operation.checkedFile = null;
            status(''); controls();
        });
        el('prepPreflight').addEventListener('click', async () => {
            const operation = operations.get(scopeKey);
            if (el('prepPreflight').disabled || !operation?.file) return;
            const ticket = download = new AbortController(), source = operation.file, selected = operation.request.selection;
            operation.checkedFile = null; controls();
            try {
                const check = await api.preflightAdmbw(checkpointContext(operation), selected.projectCode, operation.preview.viewId,
                    selected.rootPackageGuid, source, ticket.signal);
                if (ticket !== download || operation !== operations.get(scopeKey) || source !== operation.file) return;
                operation.checkedFile = source;
                status(text('preflightReady') + ' · ' + (check.coverage.status || check.coverage));
            } catch (error) { if (ticket === download) options.report(error, el('prepStatus')); }
            finally { if (ticket === download) { download = null; controls(); } }
        });
        el('prepDownload').addEventListener('click', async () => {
            const operation = operations.get(scopeKey);
            if (el('prepDownload').disabled || operation?.state !== 'CHECKPOINTED' || !exportable(operation.preview)) return;
            const ticket = download = new AbortController();
            controls();
            try {
                const selected = operation.request.selection;
                const blob = await api.admbwQea(checkpointContext(operation), selected.projectCode, operation.preview.viewId,
                    selected.rootPackageGuid, operation.file, ticket.signal);
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
