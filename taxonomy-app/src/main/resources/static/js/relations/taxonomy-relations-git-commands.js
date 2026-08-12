/**
 * Git-authoritative command adapter for the existing relation browser.
 *
 * The legacy browser remains responsible for rendering and impact analysis.
 * This adapter captures create/delete actions before the retired DB-first
 * handlers, refreshes one authoritative snapshot immediately before each
 * command, and addresses relations by the source/type/target identity visible
 * in the same table row.
 */
(function () {
    'use strict';

    var headEtag = null;
    var branchMissing = false;
    var commandSequence = 0;
    var initialized = false;

    function init() {
        if (initialized) return;
        initialized = true;

        document.addEventListener('click', captureRelationCommand, true);

        var panel = document.getElementById('relationsBrowser');
        if (panel) {
            panel.addEventListener('toggle', function () {
                if (panel.open) refreshSnapshot();
            });
            if (panel.open) refreshSnapshot();
        }

        var typeFilter = document.getElementById('relationsTypeFilter');
        if (typeFilter) {
            typeFilter.addEventListener('change', refreshSnapshot);
        }
    }

    function captureRelationCommand(event) {
        var createButton = event.target.closest('#createRelationSubmit');
        if (createButton) {
            event.preventDefault();
            event.stopImmediatePropagation();
            createRelation();
            return;
        }

        var deleteButton = event.target.closest('.relation-delete-btn');
        if (deleteButton) {
            event.preventDefault();
            event.stopImmediatePropagation();
            deleteRelation(deleteButton);
        }
    }

    function refreshSnapshot() {
        var typeFilter = document.getElementById('relationsTypeFilter');
        var type = typeFilter ? typeFilter.value : '';
        var url = type
            ? '/api/relations?type=' + encodeURIComponent(type)
            : '/api/relations';

        return fetch(url, { cache: 'no-store' })
            .then(function (response) {
                branchMissing = response.status === 404
                    && response.headers.get(
                        'X-Taxonomy-Relation-Projection-State') === 'BRANCH_MISSING';
                if (branchMissing) {
                    headEtag = null;
                    return [];
                }
                if (!response.ok) {
                    headEtag = null;
                    throw new Error(projectionError(response));
                }
                headEtag = response.headers.get('ETag');
                branchMissing = false;
                return response.json();
            });
    }

    function createRelation() {
        var sourceCode = valueOf('newRelSource');
        var targetCode = valueOf('newRelTarget');
        var relationType = valueOf('newRelType');
        var description = valueOf('newRelDescription');
        var errorElement = document.getElementById('createRelationError');
        clearError(errorElement);

        if (!sourceCode || !targetCode || !relationType) {
            showError(errorElement, message(
                'relations.create.required',
                'Source, target and relation type are required.'));
            return;
        }

        setSpinner(true);
        refreshSnapshot()
            .then(function () {
                var extensions = {};
                if (description) extensions['x-description'] = description;
                return fetch(commandUrl(
                    sourceCode, relationType, targetCode), {
                    method: 'PUT',
                    headers: commandHeaders('relation-create', true),
                    body: JSON.stringify({
                        provenance: 'manual',
                        extensions: extensions,
                        rationale: description || null
                    })
                });
            })
            .then(handleCommandResponse)
            .then(function () {
                setSpinner(false);
                var modalElement = document.getElementById(
                    'createRelationModal');
                var modal = modalElement && window.bootstrap
                    ? window.bootstrap.Modal.getInstance(modalElement)
                    : null;
                if (modal) modal.hide();
                refreshBrowser();
            })
            .catch(function (error) {
                setSpinner(false);
                showError(errorElement, message(
                    'relations.create.error',
                    'Could not create relation') + ': ' + error.message);
            });
    }

    function deleteRelation(button) {
        var relation = relationIdentity(button);
        if (!relation) {
            showBrowserError(message(
                'relations.load.failed',
                'The displayed relation identity is incomplete.'));
            return;
        }
        if (!window.confirm(message(
            'relations.delete.confirm',
            'Delete this relation?'))) return;

        refreshSnapshot()
            .then(function (relations) {
                if (!headEtag) {
                    throw new Error('The selected branch has no relation head.');
                }
                if (!(relations || []).some(function (candidate) {
                    return sameIdentity(candidate, relation);
                })) {
                    throw new Error(
                        'The displayed relation no longer exists in the current branch snapshot.');
                }
                return fetch(commandUrl(
                    relation.sourceCode,
                    relation.relationType,
                    relation.targetCode), {
                    method: 'DELETE',
                    headers: commandHeaders('relation-delete', false)
                });
            })
            .then(handleCommandResponse)
            .then(refreshBrowser)
            .catch(function (error) {
                refreshBrowser();
                showBrowserError(message(
                    'relations.delete.failed',
                    'Could not delete relation') + ': ' + error.message);
            });
    }

    function relationIdentity(button) {
        var row = button.closest('tr');
        if (!row || !row.cells || row.cells.length < 3) return null;
        var sourceCode = row.cells[0].textContent.trim();
        var targetCode = row.cells[1].textContent.trim();
        var relationType = row.cells[2].textContent.trim();
        if (!sourceCode || !targetCode || !relationType) return null;
        return {
            sourceCode: sourceCode,
            relationType: relationType,
            targetCode: targetCode
        };
    }

    function sameIdentity(left, right) {
        return left
            && left.sourceCode === right.sourceCode
            && left.relationType === right.relationType
            && left.targetCode === right.targetCode;
    }

    function handleCommandResponse(response) {
        var nextEtag = response.headers.get('ETag');
        if (nextEtag) {
            headEtag = nextEtag;
            branchMissing = false;
        }
        if (response.status === 202) {
            throw new Error(
                'Git accepted the change, but projection recovery is pending.');
        }
        if (response.status === 412) {
            headEtag = null;
            refreshSnapshot().catch(function () {});
            throw new Error(
                'The selected branch changed. Relations are being reloaded.');
        }
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        if (response.status === 204) return null;
        return response.json();
    }

    function commandHeaders(prefix, allowCreation) {
        var headers = {
            'Content-Type': 'application/json',
            'Idempotency-Key': prefix + ':' + Date.now()
                + ':' + (++commandSequence)
        };
        if (allowCreation && branchMissing) {
            headers['If-None-Match'] = '*';
        } else if (headEtag) {
            headers['If-Match'] = headEtag;
        } else {
            throw new Error('No authoritative relation ETag is available.');
        }
        return headers;
    }

    function commandUrl(sourceCode, relationType, targetCode) {
        return '/api/architecture/relations/'
            + encodeURIComponent(sourceCode) + '/'
            + encodeURIComponent(relationType) + '/'
            + encodeURIComponent(targetCode);
    }

    function refreshBrowser() {
        return refreshSnapshot()
            .catch(function (error) {
                showBrowserError(error.message);
            })
            .then(function () {
                if (window.TaxonomyRelations) {
                    window.TaxonomyRelations.loadRelations();
                }
                if (window.TaxonomyQuality) {
                    window.TaxonomyQuality.loadQualityDashboard();
                }
            });
    }

    function projectionError(response) {
        var state = response.headers.get(
            'X-Taxonomy-Relation-Projection-State');
        var pending = response.headers.get(
            'X-Taxonomy-Relation-Pending-Recovery');
        if (!state) return 'HTTP ' + response.status;
        return 'Projection ' + state
            + (pending ? ' (pending recovery: ' + pending + ')' : '');
    }

    function valueOf(id) {
        var element = document.getElementById(id);
        return element && element.value ? element.value.trim() : '';
    }

    function setSpinner(visible) {
        var spinner = document.getElementById('createRelationSpinner');
        if (spinner) spinner.classList.toggle('d-none', !visible);
        var button = document.getElementById('createRelationSubmit');
        if (button) button.disabled = visible;
    }

    function clearError(element) {
        if (!element) return;
        element.textContent = '';
        element.classList.add('d-none');
    }

    function showError(element, text) {
        if (!element) return;
        element.textContent = text;
        element.classList.remove('d-none');
    }

    function showBrowserError(text) {
        var container = document.getElementById(
            'relationsTableContainer');
        if (!container) return;
        var error = document.createElement('div');
        error.className = 'text-danger small p-1';
        error.textContent = text;
        container.prepend(error);
        window.setTimeout(function () { error.remove(); }, 5000);
    }

    function message(key, fallback) {
        if (!window.TaxonomyI18n || !window.TaxonomyI18n.t) {
            return fallback;
        }
        var translated = window.TaxonomyI18n.t(key);
        return translated && translated !== key ? translated : fallback;
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
