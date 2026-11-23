/** Model-scoped commission and source assignments use the normal preview and audited acceptance. */
(function () {
    'use strict';
    function create(options) {
        const el = id => (options.root || document).querySelector('#editorBrief' + id);
        const t = key => options.t('editor.brief.' + key);
        const fields = ['objective', 'decision', 'stakeholders', 'boundary', 'stage', 'context', 'referenceArchitecture', 'views', 'deliverables'];
        const referenceFields = ['identifier', 'edition', 'section', 'purpose', 'role', 'scope', 'appliesTo', 'derivation', 'rule', 'relation'];
        const choices = {
            stage: ['', 'current', 'transition', 'target'],
            context: ['', 'public-services', 'federal', 'european', 'admbw', 'custom'],
            role: ['', 'binding', 'guidance', 'open'],
            scope: ['', 'architecture', 'deliverable', 'background'],
            rule: ['', 'exclude-relation-v1']
        };
        let snapshot, source, workspace, writable = false, selectedId = '', draftId, briefDirty = false, refDirty = false;
        let relationTypes = [], context, pinReview, pinDirty = false, pinEpoch = 0, pinBusy = false;
        function listPins(id, bindings) {
            const list = el(id);
            list.replaceChildren();
            for (const {link, evidenceQuote} of bindings || []) {
                const item = document.createElement('li');
                item.textContent = link.projectKey + ' / ' + link.requirementKey + ' · v' + link.requirementVersion
                    + ' · SHA-256 ' + link.contentHash + ' · ' + evidenceQuote;
                list.append(item);
            }
        }
        function invalidatePins() {
            pinEpoch++;
            pinReview = null;
            el('PinReview').replaceChildren();
            el('PinAccept').hidden = true;
            el('PinStatus').textContent = '';
        }
        function pinFailure(error) { el('PinStatus').textContent = error.detail || error.message || String(error); }
        el('PinForm').addEventListener('input', () => { invalidatePins(); pinDirty = true; });
        el('PinEdit').addEventListener('click', async () => {
            if (!writable || pinBusy || pinDirty && !window.confirm(t('discard'))) return;
            invalidatePins();
            const ticket = pinEpoch;
            try {
                const overview = await window.TaxonomyArchitectureLifecycleApi.overview(context);
                if (ticket !== pinEpoch || !writable) return;
                if (!overview.mayEdit || overview.context.revision !== context.revision || overview.context.commit !== context.commit)
                    throw new Error(t('refreshPins'));
                const choices = el('PinChoices');
                choices.replaceChildren();
                for (const req of overview.requirements.filter(row => row.versionId != null)) {
                    const binding = snapshot.bindings?.find(item => item.link.projectKey === req.projectKey
                        && item.link.requirementKey === req.requirementKey && item.link.contentHash === req.contentHash
                        && item.link.requirementVersion === req.version);
                    const row = document.createElement('div'), label = document.createElement('label');
                    const check = document.createElement('input');
                    check.type = 'checkbox'; check.checked = !!binding;
                    label.append(check, ' ' + req.projectKey + ' / ' + req.requirementKey + ' · v' + req.version + ' · ' + req.title);
                    const source = document.createElement('p'); source.textContent = req.text;
                    const quote = document.createElement('textarea'); quote.maxLength = 4096;
                    quote.setAttribute('aria-label', t('excerpt') + ' · ' + req.requirementKey);
                    quote.value = binding?.evidenceQuote || ''; quote.placeholder = t('excerpt');
                    quote.required = check.checked; quote.disabled = !check.checked;
                    check.addEventListener('change', () => { quote.required = check.checked; quote.disabled = !check.checked; });
                    row.append(label, source, quote);
                    row.pin = () => {
                        if (!check.checked) return null;
                        // Textarea line endings are normalized by the browser. Return the corresponding original slice.
                        const index = req.text.replace(/\r\n?/g, '\n').indexOf(quote.value);
                        if (index < 0 || !quote.value.trim()) throw new Error(t('excerpt'));
                        const chars = req.text.match(/\r\n|[\s\S]/g) || [];
                        return {projectId:req.projectId, requirementId:req.requirementId, expectedVersionId:req.versionId,
                            expectedContentHash:req.contentHash, evidenceQuote:chars.slice(index, index + quote.value.length).join('')};
                    };
                    choices.append(row);
                }
                pinDirty = false;
                el('PinForm').hidden = false;
            } catch (error) { if (ticket === pinEpoch) pinFailure(error); }
        });
        el('PinForm').addEventListener('submit', async event => {
            event.preventDefault();
            if (!writable || pinBusy || !el('PinForm').reportValidity()) return;
            invalidatePins();
            const ticket = pinEpoch;
            try {
                const pins = Array.from(el('PinChoices').children).map(row => row.pin()).filter(Boolean);
                const id = crypto.randomUUID(), rationale = el('PinReason').value.trim();
                if (!rationale) throw new Error(t('reasonRequired'));
                const request = {context, pins, metadata:{commandId:id, causationId:id, correlationId:id, rationale}};
                const result = await window.TaxonomyArchitectureLifecycleApi.previewBriefScope(request);
                if (ticket !== pinEpoch || !writable) return;
                pinReview = {...request, previewFingerprint:result.fingerprint};
                listPins('PinReview', result.bindings);
                el('PinStatus').textContent = t('replacePins');
                el('PinAccept').hidden = false;
            } catch (error) { if (ticket === pinEpoch) pinFailure(error); }
        });
        el('PinAccept').addEventListener('click', async () => {
            if (!writable || !pinReview || pinBusy) return;
            const ticket = pinEpoch, original = pinReview, generation = options.generation?.();
            pinBusy = true; permissions();
            try {
                const publication = await window.TaxonomyArchitectureLifecycleApi.acceptBriefScope(original);
                if (ticket !== pinEpoch) return;
                if (publication.state !== 'CHECKPOINTED') { el('PinStatus').textContent = publication.detail || publication.state; return; }
                pinDirty = false;
                await options.onPinsAccepted(publication, original.context, generation);
            } catch (error) { if (ticket === pinEpoch) pinFailure(error); }
            finally { pinBusy = false; permissions(); }
        });
        function option(select, value, label) {
            const item = document.createElement('option');
            item.value = value;
            item.textContent = label;
            select.append(item);
        }
        function renderFields(target, keys, values) {
            const fieldset = el(target);
            fieldset.replaceChildren();
            for (const key of keys) {
                const variants = key === 'relation' ? ['', ...relationTypes] : choices[key];
                const label = document.createElement('label');
                const input = document.createElement(variants ? 'select' : 'input');
                input.name = key;
                input.id = fieldset.id + '-' + key;
                label.htmlFor = input.id;
                label.textContent = t('field.' + key);
                if (variants) {
                    for (const value of variants) {
                        option(input, value, !value ? t('choose') : key === 'relation' ? value : t('choice.' + value));
                    }
                } else input.maxLength = 4096;
                if (key === 'identifier') input.setAttribute('list', 'editorBriefSources');
                input.required = ['objective', 'identifier', 'role', 'scope'].includes(key);
                input.value = values[key] || '';
                fieldset.append(label, input);
            }
        }
        function values(id) {
            return Object.fromEntries(Array.from(el(id).elements).filter(control => control.name && control.value.trim())
                .map(control => [control.name, control.value.trim()]));
        }
        function permissions() {
            const mayEdit = writable && !snapshot?.problem && (!snapshot?.present || snapshot.version === '1');
            el('PinEdit').disabled = !mayEdit || !snapshot?.present || pinBusy;
            el('PinChoices').disabled = el('PinReason').disabled = el('PinAccept').disabled = !mayEdit || pinBusy;
            el('Fields').disabled = el('Save').disabled = !mayEdit;
            el('ReferenceFields').disabled = el('ReferenceSave').disabled = !mayEdit || !snapshot?.present;
            el('ReferenceRemove').disabled = !mayEdit || !selectedId;
        }
        function reference() {
            const found = snapshot?.references?.find(ref => ref.id === selectedId);
            renderFields('ReferenceFields', referenceFields, found?.values || {});
            permissions();
        }
        function offerSources(view) {
            const list = el('Sources');
            list.replaceChildren();
            const examples = {
                federal: ['Föderale IT-Architekturrichtlinie'],
                european: ['European Interoperability Reference Architecture (EIRA)'],
                'public-services': ['Föderale IT-Architekturrichtlinie', 'European Interoperability Reference Architecture (EIRA)'],
                admbw: ['ADMBw R7 / FFFmLV']
            };
            const cited = (view.model?.sources || []).map(s => s.canonicalIdentifier).filter(Boolean);
            for (const name of new Set([...(examples[snapshot?.fields?.context] || []), ...cited])) option(list, name, name);
        }
        function updateReferences() {
            const select = el('Reference');
            select.replaceChildren();
            option(select, '', t('newReference'));
            for (const ref of snapshot?.references || []) {
                option(select, ref.id, [ref.values.identifier, ref.values.edition, ref.values.section].filter(Boolean).join(' · '));
            }
            if (snapshot?.references?.some(r => r.id === draftId)) selectedId = draftId;
            if (!snapshot?.references?.some(r => r.id === selectedId)) selectedId = '';
            select.value = selectedId;
            draftId = selectedId || null;
            reference();
        }
        el('Form').addEventListener('input', () => { briefDirty = true; });
        el('ReferenceForm').addEventListener('input', () => { refDirty = true; });
        el('Reference').addEventListener('change', event => {
            if (refDirty && !window.confirm(t('discard'))) { event.target.value = selectedId; return; }
            selectedId = event.target.value;
            draftId = selectedId || null;
            refDirty = false;
            reference();
        });
        el('Form').addEventListener('submit', event => {
            event.preventDefault();
            if (el('Save').disabled || !el('Form').reportValidity()) return;
            options.stage({kind: 'SET_ARCHITECTURE_BRIEF', properties: values('Fields')}, el('Reason'));
        });
        el('ReferenceForm').addEventListener('submit', event => {
            event.preventDefault();
            if (el('ReferenceSave').disabled || !el('ReferenceForm').reportValidity()) return;
            if (!draftId) draftId = 'ref-' + crypto.randomUUID();
            options.stage({kind: 'SET_ARCHITECTURE_REFERENCE', id: selectedId || draftId,
                properties: values('ReferenceFields')}, el('ReferenceReason'));
        });
        el('ReferenceRemove').addEventListener('click', () => {
            if (el('ReferenceRemove').disabled || !el('ReferenceReason').reportValidity()) return;
            options.stage({kind: 'DELETE_ARCHITECTURE_REFERENCE', id: selectedId}, el('ReferenceReason'));
        });
        return {
            render(view, preserveDraft, preserveBriefDrafts) {
                const ctx = context = view.document.context;
                const nextWorkspace = JSON.stringify([ctx.repositoryId, ctx.workspaceScopeKey, ctx.workspaceId, ctx.branch, ctx.actor]);
                const next = JSON.stringify([nextWorkspace, ctx.commit, ctx.revision, view.document.source, view.document.projectionState]);
                const changedScope = source !== next;
                const keepBrief = preserveDraft && preserveBriefDrafts && workspace === nextWorkspace
                    && view.document.source === 'WORKSPACE_REVISION' && view.document.projectionState === 'READY';
                if (changedScope) {
                    pinDirty = false; invalidatePins(); el('PinForm').hidden = true; el('PinChoices').replaceChildren();
                    el('PinReason').value = '';
                    if (!keepBrief || !refDirty) { selectedId = ''; el('ReferenceReason').value = ''; }
                    if (workspace !== nextWorkspace) draftId = null;
                    if (!keepBrief || !briefDirty) el('Reason').value = '';
                }
                source = next;
                workspace = nextWorkspace;
                snapshot = view.architectureBrief;
                listPins('Pins', snapshot?.bindings);
                relationTypes = Object.keys(view.schema.relationDomains || {}).sort();
                offerSources(view);
                el('Status').textContent = t(snapshot?.problem === 'INVALID_ARCHITECTURE_BRIEF' ? 'invalid' : snapshot?.present ? snapshot.version === '1' && !snapshot.problem ? 'saved' : 'unsupported' : 'absent');
                if (changedScope && !keepBrief || !preserveDraft || !briefDirty) {
                    renderFields('Fields', fields, snapshot?.fields || {});
                    briefDirty = false;
                }
                if (changedScope && !keepBrief || !preserveDraft || !refDirty) {
                    updateReferences();
                    refDirty = false;
                }
                permissions();
            },
            hasDraft() { return briefDirty || refDirty || pinDirty; },
            setEnabled(enabled) { writable = Boolean(enabled); permissions(); }
        };
    }
    window.TaxonomyArchitectureBrief = Object.freeze({create});
}());
