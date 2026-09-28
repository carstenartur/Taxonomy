/** Small profile-driven requirement panel. All writes use the existing scoped preview/accept workflow. */
(function () {
    'use strict';
    function create(options) {
        var view = null, writable = false, dirty = false, scope = '', draftId = null;
        var selectedRequirement = '', selectedEntry = '';
        function el(id) { return document.getElementById(id); }
        function text(key) {
            var full = 'editor.planning.' + key, translated = options.t(full);
            return translated === full ? key : translated;
        }
        function addOption(select, value, label) {
            var option = document.createElement('option'); option.value = value; option.textContent = label; select.append(option);
        }
        function entries() { return view && view.planningInformation && view.planningInformation[selectedRequirement] || []; }
        function invalid() { return entries().some(function (v) { return !v.entry; }); }
        function current() { return entries().find(function (v) { return v.entry && v.entry.id === selectedEntry; }); }
        function descriptor() {
            if (!view) return null;
            var entry = current();
            return (view.schema.planningProfiles || []).find(function (p) {
                return entry ? p.id === entry.entry.profile && p.version === entry.entry.version : p.id + '@' + p.version === el('editorPlanningProfile').value;
            });
        }
        function updatePermissions() {
            var item = current(), supported = !invalid() && (!item || item.supported);
            el('editorPlanningActions').disabled = !writable || !selectedRequirement || invalid();
            el('editorPlanningSave').disabled = !writable || !selectedRequirement || !supported || !descriptor();
            el('editorPlanningProfile').disabled = !writable || Boolean(item);
            el('editorPlanningDelete').disabled = !writable || !item;
            el('editorPlanningNew').disabled = !writable || !selectedRequirement || invalid();
        }
        function detail() {
            var item = current(), profile = descriptor();
            el('editorPlanningFields').replaceChildren(); el('editorPlanningValues').replaceChildren();
            if (invalid()) { el('editorPlanningStatus').textContent = text('invalid'); updatePermissions(); return; }
            if (item) {
                el('editorPlanningStatus').textContent = item.entry.profile + ' / ' + item.entry.version + ' · ' + item.entry.origin
                    + ' · ' + text(item.supported ? 'referenceOnly' : item.problem === 'INVALID_PROFILE_DATA' ? 'invalidProfile' : 'unknown');
            } else el('editorPlanningStatus').textContent = text(selectedRequirement ? 'referenceOnly' : 'noRequirements');
            if (item && !item.supported) {
                Object.keys(item.entry.values).sort().forEach(function (key) {
                    var term = document.createElement('dt'), value = document.createElement('dd');
                    term.textContent = key; value.textContent = item.entry.values[key]; el('editorPlanningValues').append(term, value);
                });
            } else if (profile) {
                profile.fields.forEach(function (field) {
                    var label = document.createElement('label'); label.htmlFor = 'editorPlanningValue-' + field.key;
                    label.textContent = text('field.' + field.key) + (field.required ? ' *' : '');
                    var input = document.createElement(field.choices.length ? 'select' : 'input');
                    input.id = label.htmlFor; input.dataset.planningField = field.key;
                    input.required = field.required; input.maxLength = 4096;
                    if (field.choices.length) field.choices.forEach(function (choice) { addOption(input, choice, text('choice.' + choice)); });
                    input.value = item ? item.entry.values[field.key] || '' : field.choices[0] || '';
                    input.addEventListener('input', function () { dirty = true; });
                    el('editorPlanningFields').append(label, input);
                });
            }
            updatePermissions();
        }
        function populateEntries() {
            var select = el('editorPlanningEntry'); select.replaceChildren(); addOption(select, '', text('newEntry'));
            entries().forEach(function (v) { if (v.entry) addOption(select, v.entry.id, text('profile.' + v.entry.profile) + ' · ' + v.entry.id); });
            if (!entries().some(function (v) { return v.entry && v.entry.id === selectedEntry; })) selectedEntry = '';
            select.value = selectedEntry;
            var profiles = el('editorPlanningProfile'); profiles.replaceChildren();
            (view.schema.planningProfiles || []).forEach(function (p) { addOption(profiles, p.id + '@' + p.version, text('profile.' + p.id) + ' / ' + p.version); });
            var item = current(); if (item && item.supported) profiles.value = item.entry.profile + '@' + item.entry.version;
            if (!draftId) draftId = 'plan-' + crypto.randomUUID();
            detail();
        }
        function discard() { return !dirty || window.confirm(text('discard')); }
        el('editorPlanningRequirement').addEventListener('change', function () {
            if (!discard()) { this.value = selectedRequirement; return; }
            selectedRequirement = this.value; selectedEntry = ''; draftId = null; dirty = false; populateEntries();
        });
        el('editorPlanningEntry').addEventListener('change', function () {
            if (!discard()) { this.value = selectedEntry; return; }
            selectedEntry = this.value; draftId = null; dirty = false; populateEntries();
        });
        el('editorPlanningProfile').addEventListener('change', function () { dirty = true; detail(); });
        el('editorPlanningNew').addEventListener('click', function () {
            if (!writable || invalid() || !discard()) return;
            selectedEntry = ''; draftId = null; dirty = false; populateEntries(); el('editorPlanningProfile').focus();
        });
        function reasonValid() {
            var reason = el('editorPlanningReason');
            reason.setCustomValidity(reason.value.trim() ? '' : text('reasonRequired'));
            return reason.reportValidity();
        }
        el('editorPlanningReason').addEventListener('input', function () { this.setCustomValidity(''); });
        el('editorPlanningForm').addEventListener('submit', function (event) {
            event.preventDefault();
            var item = current(), profile = descriptor();
            if (!writable || invalid() || !selectedRequirement || !profile || item && !item.supported || !reasonValid()) return;
            var fields = el('editorPlanningFields').querySelectorAll('[data-planning-field]'), values = {};
            for (var input of fields) {
                if (!input.reportValidity()) return;
                if (input.required || input.value !== '') values[input.dataset.planningField] = input.value;
            }
            options.stage({ kind: 'SET_PLANNING', id: selectedRequirement,
                planning: { entryId: selectedEntry || draftId, profile: profile.id, version: profile.version, values: values } }, el('editorPlanningReason'));
        });
        el('editorPlanningDelete').addEventListener('click', function () {
            if (!writable || !current() || !reasonValid()) return;
            options.stage({ kind: 'DELETE_PLANNING', id: selectedRequirement, planning: { entryId: selectedEntry } }, el('editorPlanningReason'));
        });
        return {
            render: function (next, preserveDraft) {
                var c = next.document && next.document.context || {}, nextScope = [c.repositoryId, c.workspaceScopeKey, c.branch].join('/');
                if (nextScope !== scope) { selectedRequirement = ''; selectedEntry = ''; draftId = null; dirty = false; }
                scope = nextScope; view = next;
                var select = el('editorPlanningRequirement'); select.replaceChildren();
                view.model.requirements.forEach(function (r) { addOption(select, r.id, (r.title || r.id) + ' · ' + r.id); });
                if (!view.model.requirements.some(function (r) { return r.id === selectedRequirement; })) selectedRequirement = view.model.requirements[0] && view.model.requirements[0].id || '';
                select.value = selectedRequirement;
                if (preserveDraft && dirty) { updatePermissions(); return; }
                if (draftId && entries().some(function (e) { return e.entry && e.entry.id === draftId; })) selectedEntry = draftId;
                dirty = false; populateEntries();
            },
            setEnabled: function (enabled) { writable = Boolean(enabled && view && view.mayEdit); updatePermissions(); }
        };
    }
    window.TaxonomyPlanningEditor = Object.freeze({ create: create });
}());
