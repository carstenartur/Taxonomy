/** Maintain the workspace's organizational units through the editor command preview. */
(function () {
    'use strict';

    function create({ root, t, stage }) {
        const control = key => root.querySelector('#editorDirectory' + key);
        const list = control('Units');
        const form = control('Form');
        const name = control('Name');
        const parent = control('Parent');
        const reason = control('Reason');
        let units = new Map();
        let rows = [];
        let selected = '';
        let newId = '';
        let mayEdit = false;
        let enabled = false;

        function option(select, id, label) {
            const item = root.ownerDocument.createElement('option');
            item.value = id;
            item.textContent = label;
            select.append(item);
        }

        function pathFor(unit) {
            const ids = new Set();
            const names = [];
            while (unit && !ids.has(unit.id)) {
                ids.add(unit.id);
                names.push(unit.title);
                unit = units.get(unit.parentId);
            }
            return { ids, label: names.reverse().join(' / ') };
        }

        function setEnabled(writable) {
            enabled = Boolean(mayEdit && writable);
            control('Fields').disabled = !enabled;
            control('New').disabled = !enabled;
            control('Delete').disabled = !enabled || !selected;
        }

        function select(id) {
            selected = id;
            newId = '';
            list.value = id;
            const unit = units.get(id);
            name.value = unit?.title || '';
            reason.value = '';
            parent.replaceChildren();
            option(parent, '', t('editor.directory.noParent'));
            for (const row of rows) {
                if (!row.ids.has(id)) option(parent, row.id, row.label);
            }
            parent.value = unit?.parentId || '';
            control('Delete').disabled = !enabled || !unit;
        }

        function render(organizations, writable, preserveDraft) {
            const draft = preserveDraft && hasDraft() && writable
                ? {id: selected, newId, title: name.value, parent: parent.value, rationale: reason.value} : null;
            units = new Map((organizations || []).map(unit => [unit.id, unit]));
            rows = Array.from(units.values(), unit => ({ id: unit.id, ...pathFor(unit) }));
            rows.sort((a, b) => a.label.localeCompare(b.label) || a.id.localeCompare(b.id));
            list.replaceChildren();
            for (const row of rows) option(list, row.id, row.label);
            control('Empty').hidden = rows.length > 0;
            list.hidden = control('UnitsLabel').hidden = !rows.length;
            mayEdit = Boolean(writable);
            form.hidden = control('New').hidden = !mayEdit;
            setEnabled(writable);
            const previous = units.has(newId) ? newId : selected;
            if (draft) {
                select(draft.id);
                newId = draft.newId;
                name.value = draft.title;
                parent.value = draft.parent;
                reason.value = draft.rationale;
            } else select(units.has(previous) ? previous : (rows[0]?.id || ''));
        }

        function hasDraft() {
            const unit = units.get(selected);
            return mayEdit && (name.value !== (unit?.title || '') ||
                parent.value !== (unit?.parentId || '') || Boolean(reason.value));
        }

        list.addEventListener('change', () => {
            if (hasDraft() && !window.confirm(t('architecture.workspace.discardDraft'))) list.value = selected;
            else select(list.value);
        });
        control('New').addEventListener('click', () => {
            if (!enabled || (hasDraft() && !window.confirm(t('architecture.workspace.discardDraft')))) return;
            select('');
            name.focus();
        });
        form.addEventListener('submit', event => {
            event.preventDefault();
            if (!enabled) return;
            name.value = name.value.trim();
            if (!name.reportValidity() || !reason.reportValidity()) return;
            if (!selected && !newId) newId = window.crypto.randomUUID();
            stage({ kind: 'UPSERT_ORGANIZATION', id: selected || newId,
                properties: { title: name.value, parentId: parent.value } }, reason);
        });
        control('Delete').addEventListener('click', () => {
            if (enabled && selected && reason.reportValidity()) {
                stage({ kind: 'DELETE_ORGANIZATION', id: selected }, reason);
            }
        });
        return { render, setEnabled, hasDraft, choices: () => rows, reset() { render([], false); } };
    }

    window.TaxonomyOrganizationDirectory = { create };
})();
