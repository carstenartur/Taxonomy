window.TaxonomyArchitectureChangeSet = (function () {
    'use strict';

    function create(options) {
        function el(name) { return options.root.querySelector('#editorChangeSet' + name); }
        var panel = el('Panel'), form = el('Form'), fields = el('Fields');
        var review = el('Review'), accept = el('Accept'), errorNode = el('Error'), receipt = el('Receipt');
        var view = null, enabled = false, reviewed = null, busy = false;
        var t = options.t;

        function sameScope(left, right) {
            return left && right && ['repositoryId', 'workspaceId', 'workspaceScopeKey', 'branch', 'actor']
                .every(key => left[key] === right[key]);
        }
        function sameStand(left, right) {
            return sameScope(left, right) && left.commit === right.commit && left.revision === right.revision;
        }
        function clearReview() {
            reviewed = null;
            review.hidden = true;
            el('Changes').replaceChildren();
        }
        function setChoices(name, items) {
            var select = el(name), selected = select.value;
            function append(value, title) {
                var option = document.createElement('option');
                option.value = value;
                option.textContent = title;
                select.append(option);
            }
            select.replaceChildren();
            append('', t('editor.prep.choose'));
            items.forEach(item => append(item[0], item[1]));
            select.value = items.some(item => item[0] === selected) ? selected : '';
        }
        function update(next, writable) {
            panel.hidden = !next?.mayEdit || next.document.source !== 'WORKSPACE_REVISION'
                || next.document.projectionState !== 'READY';
            var checkpointed = Boolean(next?.document.context.commit)
                && next.document.checkpointRevision === next.document.context.revision;
            var hasSources = Boolean(next?.model.elements.length && next?.model.requirements.length
                && next?.schema.relationTypes.length);
            var availability = el('Availability');
            availability.textContent = panel.hidden || (checkpointed && hasSources) ? ''
                : t(checkpointed ? 'editor.changeSet.needsSources' : 'editor.changeSet.needsCheckpoint');
            if (next && next !== view) {
                if (!sameScope(next.document.context, view?.document.context)) {
                    form.reset();
                    receipt.textContent = '';
                    errorNode.textContent = '';
                }
                if (!sameStand(next.document.context, view?.document.context)) clearReview();
                view = next;
                if (!panel.hidden) {
                    setChoices('Type', next.schema.elementTypes.map(type => [type, t('editor.type.' + type)]));
                    setChoices('Relation', next.schema.relationTypes.map(type => [type, type]));
                    setChoices('Endpoint', next.model.elements.map(item => [
                        item.id, (next.displayTitles?.[item.id] || item.title || item.id) + ' · ' + item.id
                    ]));
                    setChoices('Requirement', next.model.requirements.map(item => [
                        item.id, (item.title || item.id) + ' · ' + item.id
                    ]));
                }
            }
            if (panel.hidden) clearReview();
            enabled = !panel.hidden && writable && checkpointed && hasSources;
            fields.disabled = !enabled || busy;
            accept.disabled = !enabled || busy || !reviewed;
        }
        function lock(value) {
            busy = value;
            options.lock(value);
            fields.disabled = !enabled || value;
            accept.disabled = !enabled || value || !reviewed;
        }
        function value(name) { return el(name).value.trim(); }
        function renderPreview(result, request) {
            el('Target').textContent = t('editor.changeSet.target', result.context.repositoryId,
                result.context.branch, result.context.commit, request.metadata.rationale);
            var changes = el('Changes');
            result.changes.forEach(change => {
                var heading = document.createElement('h3'), diff = document.createElement('div');
                heading.textContent = change.id;
                diff.className = 'editor-diff';
                [change.before, change.after].forEach((source, index) => {
                    var column = document.createElement('div'), label = document.createElement('p');
                    var content = document.createElement('pre');
                    label.textContent = t(index === 0 ? 'editor.before' : 'editor.after');
                    content.textContent = source ?? t('editor.absent');
                    column.append(label, content);
                    diff.append(column);
                });
                changes.append(heading, diff);
            });
            review.hidden = false;
        }
        form.addEventListener('input', function () {
            clearReview();
            errorNode.textContent = '';
        });
        form.addEventListener('submit', async function (event) {
            event.preventDefault();
            if (!enabled || busy) return;
            clearReview();
            errorNode.textContent = receipt.textContent = '';
            var context = view.document.context, id = value('Id'), endpoint = value('Endpoint');
            var outgoing = value('Direction') === 'out';
            var request = {
                context: context,
                metadata: options.newMetadata(value('Rationale')),
                element: {id: id, type: value('Type'), properties: {title: value('Title')}},
                relation: {sourceId: outgoing ? id : endpoint, relationType: value('Relation'),
                    targetId: outgoing ? endpoint : id, properties: {}},
                allocation: {requirementId: value('Requirement'), elementId: id}
            };
            lock(true);
            try {
                var result = await options.api.previewChangeSet(request);
                if (panel.hidden || !sameStand(context, view.document.context)) return;
                reviewed = Object.assign({previewFingerprint: result.fingerprint}, request);
                renderPreview(result, request);
                review.focus();
            } catch (error) {
                if (!panel.hidden && sameStand(context, view.document.context)) options.report(error, errorNode);
            } finally { lock(false); }
        });
        accept.addEventListener('click', async function () {
            if (!enabled || busy || !reviewed || !sameStand(reviewed.context, view.document.context)) return;
            var request = reviewed;
            errorNode.textContent = '';
            lock(true);
            try {
                var result = await options.api.acceptChangeSet(request);
                if (panel.hidden || !sameStand(request.context, view.document.context)) return;
                clearReview();
                form.reset();
                receipt.textContent = t('editor.changeSet.saved', result.commitId);
                receipt.focus();
                await options.onAccepted(result, request.context);
            } catch (error) {
                if (panel.hidden || !sameStand(request.context, view.document.context)) return;
                if (error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)) clearReview();
                options.report(error, errorNode);
                errorNode.focus();
            } finally { lock(false); }
        });
        return {update: update, hasDraft: () => ['Id', 'Title', 'Type', 'Endpoint', 'Requirement', 'Relation', 'Rationale']
            .some(name => value(name))};
    }
    return {create: create};
}());
