/* Edit the organization catalogue through the same authorized revision commands as the model. */
(function () {
    'use strict';
    const get = id => document.getElementById(id);
    const root = get('catalogueResponsibilityEditor');
    if (!root) return;

    const api = window.ArchitectureEditorApi;
    const t = (...args) => window.TaxonomyI18n.t(...args);
    const editor = window.TaxonomyOrganizationDirectory.create({
        root: get('catalogueOrganizationDirectory'), prefix: 'catalogueDirectory', t, stage
    });
    const tree = window.TaxonomyResponsibilityTree.create({
        root: get('catalogueResponsibilityTree'), t, onSelect: select
    });
    const form = get('catalogueResponsibilityForm');
    const unit = get('catalogueResponsibilityUnit');
    const rationale = get('catalogueResponsibilityRationale');
    const preview = get('catalogueResponsibilityPreview');
    const status = get('catalogueResponsibilityStatus');
    const search = get('catalogueResponsibilitySearch');
    let view, pending, index, selected, direct, assignments, busy = false, generation = 0;

    function makeIndex(catalogue) {
        const elements = [], hierarchy = Object.create(null), titles = new Map();
        const german = document.documentElement.lang.toLowerCase().startsWith('de');
        for (const node of catalogue) {
            const id = node.code, title = (german && node.nameDe) || node.nameEn || id;
            elements.push({id, title, extensions: {'x-catalogue-order': node.sortOrder}});
            hierarchy[id] = {parentId: node.parentCode};
            titles.set(id, title);
        }
        return {elements, hierarchy, titles};
    }

    function select(id) {
        if (busy) return;
        if (selected !== id) {
            discardPreview();
        }
        selected = id;
        rationale.value = '';
        paint(true);
    }

    function paint(resetUnit = false) {
        if (!index || !view) return;
        editor.setEnabled(!busy);
        tree.render({...index, assignments, selected, query: search.value});
        form.hidden = !selected || !view.editable;
        if (resetUnit) unit.value = direct.get(selected) || '';
        unit.disabled = rationale.disabled = form.querySelector('button').disabled = busy;
        get('catalogueResponsibilityAccept').disabled = busy;
        if (selected) get('catalogueResponsibilitySelected').textContent = selected + ' · ' + index.titles.get(selected);
    }

    function bindDirectory(directory) {
        index = makeIndex(directory.catalogue);
        if (!index.titles.has(selected)) selected = null;
        view = directory;
        editor.render(view.organizations, view.editable);
        direct = new Map(view.assignments.map(assignment => [assignment.code, assignment.organizationId]));
        const organizations = new Map(view.organizations.map(organization => [organization.id, organization.title]));
        assignments = new Map();
        for (const {id} of index.elements) {
            const unitId = direct.get(id);
            const name = organizations.get(unitId);
            const owner = name ? window.TaxonomyResponsibilityDisplay.unit({unitId, name, sourceId: id})
                : assignments.get(index.hierarchy[id].parentId);
            if (owner) assignments.set(id, owner);
        }
        unit.replaceChildren(new Option(t('responsibility.catalogue.inherited'), ''));
        for (const row of editor.choices()) unit.add(new Option(row.label, row.id));
    }

    async function refresh(message, scope) {
        const ticket = ++generation;
        pending = null;
        view = null;
        busy = false;
        preview.hidden = form.hidden = true;
        editor.reset();
        tree.reset();
        if (!root.open) return;
        status.textContent = t('responsibility.loading');
        try {
            const exact = scope || await window.TaxonomyArchitectureWorkspace.analysisScope();
            if (ticket !== generation) return;
            const selection = window.TaxonomyArchitectureLifecycle?.state();
            const route = new URLSearchParams(window.location.search);
            const sameScope = selection?.context && ['repositoryId', 'workspaceScopeKey', 'branch']
                .every(key => selection.context[key] === exact[key]);
            const stand = route.get('commit') || route.get('revision') || (sameScope && selection.projectionState === 'HISTORICAL'
                ? selection.source === 'GIT_CHECKPOINT' ? selection.context.commit : selection.context.revision : null);
            const directory = await api.catalogueDirectory(exact, stand);
            if (ticket !== generation) return;
            bindDirectory(directory);
            status.textContent = message || (view.editable ? '' : t('responsibility.catalogue.readonly'));
            paint(true);
        } catch (error) {
            if (ticket === generation) status.textContent = t('responsibility.unavailable');
        }
    }

    async function stage(intent, reason) {
        if (!view?.editable || busy || !reason.reportValidity()) return;
        const ticket = generation;
        busy = true;
        pending = null;
        preview.hidden = true;
        status.textContent = '';
        paint();
        try {
            const command = {...intent, context: view.context, metadata: {commandId: crypto.randomUUID(), rationale: reason.value}};
            const result = await api.preview(command);
            if (ticket !== generation) return;
            if (!result.change.changes.length) {
                status.textContent = t('responsibility.catalogue.noChange');
                return;
            }
            pending = command;
            const label = intent.kind === 'SET_CATALOGUE_RESPONSIBILITY'
                ? index.titles.get(intent.id) + ' → ' + unit.selectedOptions[0].textContent
                : (intent.properties?.title || view.organizations.find(row => row.id === intent.id)?.title || intent.id);
            get('catalogueResponsibilityChanges').textContent = label + ' · ' + reason.value;
            preview.hidden = false;
        } catch (error) {
            if (ticket === generation) status.textContent = error.message || t('responsibility.unavailable');
        } finally {
            if (ticket === generation) {
                busy = false;
                paint();
                if (pending) get('catalogueResponsibilityAccept').focus();
            }
        }
    }

    form.addEventListener('submit', event => {
        event.preventDefault();
        stage({kind: 'SET_CATALOGUE_RESPONSIBILITY', id: selected, properties: {organization: unit.value}}, rationale);
    });
    get('catalogueResponsibilityAccept').addEventListener('click', async () => {
        if (!pending || busy) return;
        const ticket = generation;
        busy = true;
        paint();
        try {
            const result = await api.execute(pending);
            if (ticket !== generation) return;
            window.TaxonomyResponsibilityDisplay.invalidate();
            window.TaxonomyArchitectureLifecycle?.refresh(result.context, null);
            await refresh(t('editor.accepted', result.context.revision), result.context);
        } catch (error) {
            if (ticket === generation) {
                if (error.status === 412) await refresh(t('responsibility.catalogue.stale'));
                else status.textContent = error.message || t('responsibility.unavailable');
            }
        } finally {
            if (ticket === generation) { busy = false; paint(); }
        }
    });
    function discardPreview() {
        pending = null;
        preview.hidden = true;
    }
    get('catalogueResponsibilityCancel').addEventListener('click', discardPreview);
    root.addEventListener('input', event => { if (event.target !== search && pending) discardPreview(); });
    search.addEventListener('input', () => paint());
    root.addEventListener('toggle', () => refresh());
    window.TaxonomyArchitectureLifecycle?.onStandChange(() => { if (root.open) refresh(); });
    window.addEventListener('popstate', () => { if (root.open) refresh(); });
    document.addEventListener('taxonomy:repository-context-changed', event => {
        if (event.detail?.selectionChanged) refresh();
    });
}());
