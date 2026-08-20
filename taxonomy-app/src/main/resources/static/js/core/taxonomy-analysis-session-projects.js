/* taxonomy-analysis-session-projects.js – project promotion, workspace pinning and startup */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session modules must load before startup');
    var S = C.S;
    var runtime = C.runtime;
    var CHANGE_POLL_MS = C.CHANGE_POLL_MS;
    var language = C.language;
    var text = C.text;
    var businessTextElement = C.businessTextElement;
    var jsonRequest = C.jsonRequest;
    var rememberedWorkspaceId = C.rememberedWorkspaceId;
    var rememberWorkspaceId = C.rememberWorkspaceId;
    var installWorkspaceFetchRouting = C.installWorkspaceFetchRouting;
    var installWorkspaceEventSourceRouting = C.installWorkspaceEventSourceRouting;
    var currentPayload = C.currentPayload;
    var comparable = C.comparable;
    var isStale = C.isStale;
    var statusArea = C.statusArea;
    var showActionAlert = C.showActionAlert;
    var invalidate = C.invalidate;
    var queueStaleActions = C.queueStaleActions;
    var queueSave = C.queueSave;
    var deleteDraft = C.deleteDraft;
    var saveDraft = C.saveDraft;
    var loadDraft = C.loadDraft;

    function generateProjectKey() {
        var now = new Date();
        var date = String(now.getFullYear())
            + String(now.getMonth() + 1).padStart(2, '0')
            + String(now.getDate()).padStart(2, '0');
        return 'P-' + date + '-' + String(now.getTime()).slice(-4);
    }

    function deriveTitle(requirementText) {
        var clean = String(requirementText || '').replace(/\s+/g, ' ').trim();
        if (!clean) return language() === 'de' ? 'Neue Architektur' : 'New architecture';
        var firstSentence = clean.split(/(?<=[.!?])\s+/)[0];
        return firstSentence.substring(0, 120);
    }

    function nextRequirementKey(requirements) {
        var maximum = 0;
        (requirements || []).forEach(function (requirement) {
            var match = /^REQ-(\d+)$/.exec(requirement.requirementKey || '');
            if (match) maximum = Math.max(maximum, Number(match[1]));
        });
        return 'REQ-' + String(maximum + 1).padStart(3, '0');
    }

    function createDialog(title) {
        var dialog = document.createElement('dialog');
        dialog.className = 'taxonomy-accessible-dialog';
        dialog.setAttribute('aria-modal', 'true');

        var form = document.createElement('form');
        form.noValidate = true;

        var heading = document.createElement('h2');
        heading.className = 'h5';
        heading.textContent = title;

        var body = document.createElement('div');
        body.className = 'mb-3';

        var error = document.createElement('div');
        error.className = 'alert alert-danger d-none';
        error.setAttribute('role', 'alert');
        error.setAttribute('tabindex', '-1');

        var footer = document.createElement('div');
        footer.className = 'd-flex justify-content-end gap-2';

        var cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.className = 'btn btn-outline-secondary';
        cancel.textContent = text('cancel');
        cancel.addEventListener('click', function () { dialog.close(); });

        var submit = document.createElement('button');
        submit.type = 'submit';
        submit.className = 'btn btn-primary';
        submit.textContent = text('create');

        footer.append(cancel, submit);
        form.append(heading, body, error, footer);
        dialog.appendChild(form);
        document.body.appendChild(dialog);
        dialog.addEventListener('close', function () { dialog.remove(); }, { once: true });
        dialog.showModal();
        return { dialog: dialog, form: form, body: body, error: error, submit: submit };
    }

    function field(labelText, input) {
        var group = document.createElement('div');
        group.className = 'mb-3';
        var label = document.createElement('label');
        label.className = 'form-label';
        if (!input.id) input.id = 'analysis-session-' + Math.random().toString(36).slice(2);
        label.htmlFor = input.id;
        label.textContent = labelText;
        group.append(label, input);
        return group;
    }

    function setDialogBusy(parts, busy) {
        parts.submit.disabled = busy;
        parts.submit.textContent = busy ? text('saving') : text('create');
    }

    function showDialogError(parts, error) {
        parts.error.textContent = error && error.message ? error.message : text('createFailed');
        parts.error.classList.remove('d-none');
        parts.error.focus();
    }

    function createRequirement(projectId, values) {
        return jsonRequest('/api/projects/' + encodeURIComponent(projectId) + '/requirements', {
            method: 'POST',
            body: JSON.stringify({
                requirementKey: values.requirementKey,
                title: values.title,
                text: values.text,
                status: 'DRAFT',
                priority: 50,
                criticality: 'MEDIUM',
                requirementType: 'FUNCTIONAL',
                reviewStatus: 'PROPOSED',
                ownerUsername: null,
                changeReason: text('requirementReason'),
                source: null
            })
        });
    }

    function applicationUrl(value) {
        return window.TaxonomyI18n
                && typeof window.TaxonomyI18n.resolveUrl === 'function'
            ? window.TaxonomyI18n.resolveUrl(value)
            : value;
    }

    function discardDraftAndNavigate(url) {
        invalidate({ keepText: false, silent: true, reason: 'promoted-to-project' });
        return deleteDraft().finally(function () {
            window.location.assign(applicationUrl(url));
        });
    }

    function openRequirementDialog(requirementText) {
        showActionAlert('info', text('loadingProjects'), '', [], 'loading-projects');
        jsonRequest('/api/projects', { method: 'GET' }).then(function (projects) {
            if (!projects || projects.length === 0) {
                openNewProjectDialog({ seedRequirementText: requirementText });
                return;
            }

            var parts = createDialog(text('requirementDialogTitle'));
            var projectSelect = document.createElement('select');
            projectSelect.className = 'form-select';
            projectSelect.required = true;
            projects.forEach(function (project) {
                var option = document.createElement('option');
                option.value = project.id;
                option.textContent = project.projectKey + ' — ' + project.title;
                projectSelect.appendChild(option);
            });

            var keyInput = document.createElement('input');
            keyInput.className = 'form-control';
            keyInput.required = true;
            keyInput.maxLength = 64;
            keyInput.value = 'REQ-001';

            var titleInput = document.createElement('input');
            titleInput.className = 'form-control';
            titleInput.required = true;
            titleInput.maxLength = 240;
            titleInput.value = deriveTitle(requirementText);

            var textInput = document.createElement('textarea');
            textInput.className = 'form-control';
            textInput.rows = 10;
            textInput.required = true;
            textInput.value = requirementText || '';

            parts.body.append(
                field(text('project'), projectSelect),
                field(text('requirementKey'), keyInput),
                field(text('requirementTitle'), titleInput),
                field(text('requirementText'), textInput)
            );

            function refreshKey() {
                jsonRequest('/api/projects/' + encodeURIComponent(projectSelect.value) + '/requirements', {
                    method: 'GET'
                }).then(function (requirements) {
                    keyInput.value = nextRequirementKey(requirements);
                }).catch(function () { keyInput.value = 'REQ-001'; });
            }
            projectSelect.addEventListener('change', refreshKey);
            refreshKey();

            parts.form.addEventListener('submit', function (event) {
                event.preventDefault();
                if (!parts.form.reportValidity()) return;
                setDialogBusy(parts, true);
                createRequirement(projectSelect.value, {
                    requirementKey: keyInput.value.trim(),
                    title: titleInput.value.trim(),
                    text: textInput.value
                }).then(function (requirement) {
                    parts.dialog.close();
                    return discardDraftAndNavigate('/projects/' + encodeURIComponent(projectSelect.value)
                        + '/requirements/' + encodeURIComponent(requirement.id));
                }).catch(function (error) {
                    setDialogBusy(parts, false);
                    showDialogError(parts, error);
                });
            });
            projectSelect.focus();
        }).catch(function (error) {
            showActionAlert('danger', text('createFailed'), error.message, [], 'project-load-error');
        });
    }

    function openNewProjectDialog(options) {
        options = options || {};
        var seed = options.seedRequirementText;
        var parts = createDialog(text('projectDialogTitle'));

        var keyInput = document.createElement('input');
        keyInput.className = 'form-control';
        keyInput.required = true;
        keyInput.maxLength = 64;
        keyInput.value = generateProjectKey();

        var titleInput = document.createElement('input');
        titleInput.className = 'form-control';
        titleInput.required = true;
        titleInput.maxLength = 240;
        titleInput.value = deriveTitle(seed || '');

        var descriptionInput = document.createElement('textarea');
        descriptionInput.className = 'form-control';
        descriptionInput.rows = 3;
        descriptionInput.maxLength = 4000;

        parts.body.append(
            field(text('projectKey'), keyInput),
            field(text('projectTitle'), titleInput),
            field(text('projectDescription'), descriptionInput)
        );

        var createRequirementCheck = null;
        if (seed) {
            var checkGroup = document.createElement('div');
            checkGroup.className = 'form-check mb-3';
            createRequirementCheck = document.createElement('input');
            createRequirementCheck.type = 'checkbox';
            createRequirementCheck.className = 'form-check-input';
            createRequirementCheck.id = 'analysis-session-create-first-requirement';
            createRequirementCheck.checked = true;
            var checkLabel = document.createElement('label');
            checkLabel.className = 'form-check-label';
            checkLabel.htmlFor = createRequirementCheck.id;
            checkLabel.textContent = text('createFirstRequirement');
            checkGroup.append(createRequirementCheck, checkLabel);
            parts.body.appendChild(checkGroup);
        }

        parts.form.addEventListener('submit', function (event) {
            event.preventDefault();
            if (!parts.form.reportValidity()) return;
            setDialogBusy(parts, true);
            jsonRequest('/api/projects', {
                method: 'POST',
                body: JSON.stringify({
                    projectKey: keyInput.value.trim(),
                    title: titleInput.value.trim(),
                    description: descriptionInput.value.trim(),
                    status: 'DRAFT',
                    targetArchitecture: null,
                    targetDate: null,
                    budgetAmount: null,
                    budgetCurrency: null
                })
            }).then(function (project) {
                if (seed && createRequirementCheck && createRequirementCheck.checked) {
                    return createRequirement(project.id, {
                        requirementKey: 'REQ-001',
                        title: deriveTitle(seed),
                        text: seed
                    }).then(function (requirement) {
                        return '/projects/' + encodeURIComponent(project.id)
                            + '/requirements/' + encodeURIComponent(requirement.id);
                    });
                }
                return '/projects';
            }).then(function (url) {
                parts.dialog.close();
                return discardDraftAndNavigate(url);
            }).catch(function (error) {
                setDialogBusy(parts, false);
                showDialogError(parts, error);
            });
        });
        keyInput.focus();
    }

    function saveAsRequirementVersion(context, requirementText) {
        jsonRequest('/api/projects/' + encodeURIComponent(context.projectId)
                + '/requirements/' + encodeURIComponent(context.requirementId) + '/versions', {
            method: 'POST',
            body: JSON.stringify({
                text: requirementText,
                changeReason: text('versionReason'),
                source: null
            })
        }).then(function () {
            invalidate({ keepText: true, silent: true, reason: 'promoted-to-version' });
            showActionAlert('success', text('versionSaved'), '', [], 'version-saved');
        }).catch(function (error) {
            showActionAlert('danger', text('createFailed'), error.message, [], 'version-error');
        });
    }

    function resolveWorkspaceAndLoad() {
        var remembered = rememberedWorkspaceId();
        installWorkspaceFetchRouting();
        installWorkspaceEventSourceRouting();

        if (remembered) {
            runtime.workspaceId = remembered;
            return loadDraft({ probe: true }).then(function (view) {
                rememberWorkspaceId(remembered);
                return view;
            }).catch(function (error) {
                runtime.workspaceId = null;
                runtime.version = null;
                rememberWorkspaceId(null);
                if (window.console) {
                    window.console.warn('[Taxonomy] Remembered workspace is no longer available', error);
                }
                return resolveActiveWorkspaceAndLoad();
            });
        }
        return resolveActiveWorkspaceAndLoad();
    }

    function resolveActiveWorkspaceAndLoad() {
        return jsonRequest('/api/workspace/current', { method: 'GET' })
            .then(function (workspace) {
                if (!workspace || !workspace.workspaceId) return null;
                runtime.workspaceId = workspace.workspaceId;
                rememberWorkspaceId(runtime.workspaceId);
                return loadDraft();
            })
            .catch(function (error) {
                if (window.console) window.console.warn('[Taxonomy] Workspace draft unavailable', error);
                return null;
            });
    }

    function installObservers() {
        var input = businessTextElement();
        if (input) {
            input.addEventListener('input', function () {
                queueStaleActions();
                queueSave();
            });
            new MutationObserver(queueStaleActions).observe(input, {
                attributes: true,
                attributeFilter: ['class']
            });
        }

        var area = statusArea();
        if (area) {
            new MutationObserver(function () {
                if (isStale() && (area.dataset.analysisSessionMessage !== 'stale'
                        || !area.querySelector('[data-analysis-session-action="discard-analysis"]'))) {
                    queueStaleActions();
                }
            }).observe(area, { childList: true, subtree: true });
        }

        // Intercept the legacy one-size-fits-all reset button before its target
        // listener can leave architecture panels and other derived state behind.
        document.addEventListener('click', function (event) {
            var button = event.target.closest && event.target.closest('#statusArea .btn-warning');
            if (!button || button.dataset.analysisSessionAction || !isStale()) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            invalidate({ keepText: true, reason: 'legacy-stale-reset' });
        }, true);

        document.addEventListener('taxonomy:view-rendered', function () {
            queueSave();
            queueStaleActions();
        });
        document.addEventListener('taxonomy:analysis-invalidated', function () {
            queueSave(0);
        });

        window.setInterval(function () {
            if (runtime.restoring || runtime.invalidating || runtime.conflict) return;
            var serialized = comparable(currentPayload());
            if (serialized !== runtime.lastObservedComparable) {
                runtime.lastObservedComparable = serialized;
                queueSave();
            }
        }, CHANGE_POLL_MS);

        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'hidden') saveDraft();
        });
        window.addEventListener('pagehide', function () { saveDraft(); });
    }

    function initialize() {
        if (runtime.initialized) return;
        runtime.initialized = true;
        installObservers();
        resolveWorkspaceAndLoad();
    }

    window.TaxonomyAnalysisSession = Object.freeze({
        invalidate: invalidate,
        saveNow: saveDraft,
        reload: function () { runtime.conflict = false; return loadDraft({ force: true }); },
        addAsRequirement: function () {
            var input = businessTextElement();
            openRequirementDialog(input ? input.value : '');
        },
        startNewProject: function () { openNewProjectDialog({ seedRequirementText: null }); },
        state: function () {
            return Object.freeze({
                workspaceId: runtime.workspaceId,
                version: runtime.version,
                conflict: runtime.conflict,
                restoring: runtime.restoring
            });
        }
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize, { once: true });
    } else {
        initialize();
    }

    Object.assign(C, {
        generateProjectKey: generateProjectKey,
        deriveTitle: deriveTitle,
        nextRequirementKey: nextRequirementKey,
        createDialog: createDialog,
        field: field,
        setDialogBusy: setDialogBusy,
        showDialogError: showDialogError,
        createRequirement: createRequirement,
        discardDraftAndNavigate: discardDraftAndNavigate,
        openRequirementDialog: openRequirementDialog,
        openNewProjectDialog: openNewProjectDialog,
        saveAsRequirementVersion: saveAsRequirementVersion,
        resolveWorkspaceAndLoad: resolveWorkspaceAndLoad,
        resolveActiveWorkspaceAndLoad: resolveActiveWorkspaceAndLoad,
        installObservers: installObservers,
        initialize: initialize
    });
}());
