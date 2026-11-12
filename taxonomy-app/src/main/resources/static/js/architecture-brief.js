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
        let relationTypes = [];
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
            render(view, preserveDraft) {
                const ctx = view.document.context;
                const nextWorkspace = JSON.stringify([ctx.repositoryId, ctx.workspaceScopeKey, ctx.branch]);
                const next = JSON.stringify([nextWorkspace, ctx.commit, ctx.revision]);
                const changedScope = source !== next;
                if (changedScope) {
                    selectedId = '';
                    if (workspace !== nextWorkspace) draftId = null;
                    el('Reason').value = el('ReferenceReason').value = '';
                }
                source = next;
                workspace = nextWorkspace;
                snapshot = view.architectureBrief;
                relationTypes = Object.keys(view.schema.relationDomains || {}).sort();
                offerSources(view);
                el('Status').textContent = t(snapshot?.problem === 'INVALID_ARCHITECTURE_BRIEF' ? 'invalid' : snapshot?.present ? snapshot.version === '1' && !snapshot.problem ? 'saved' : 'unsupported' : 'absent');
                if (changedScope || !preserveDraft || !briefDirty) {
                    renderFields('Fields', fields, snapshot?.fields || {});
                    briefDirty = false;
                }
                if (changedScope || !preserveDraft || !refDirty) {
                    updateReferences();
                    refDirty = false;
                }
                permissions();
            },
            hasDraft() { return briefDirty || refDirty; },
            setEnabled(enabled) { writable = Boolean(enabled); permissions(); }
        };
    }
    window.TaxonomyArchitectureBrief = Object.freeze({create});
}());
