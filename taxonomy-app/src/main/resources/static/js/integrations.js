(function () {
    'use strict';
    var api = window.IntegrationApi, overview = null, operation = null, profiles = [], decisions = {}, page = 0, busy = false, generation = 0;
    var pendingUpload = null, pendingExport = null, pendingCreation = null, pendingRemote = null, mappings = {}, endpoints = {}, endpointOptions = {};
    var publication = null, resolutions = {}, pendingPublication = null, pendingReview = null;
    var mayWrite = document.body.dataset.mayWrite === 'true';
    function el(id) { return document.getElementById(id); }
    function t(key) { return window.TaxonomyI18n.t('integration.' + key); }
    function option(select, value, text) { var node = document.createElement('option'); node.value = value; node.textContent = text; select.append(node); }
    function connection() { return el('integrationConnection').value; }
    function prefix() { return '/' + encodeURIComponent(connection()); }
    function selectedProfile() { return overview && profiles.find(function (profile) { return profile.id === overview.connection.connectorId && profile.version === overview.connection.profileVersion; }); }
    function report(error) { var body = error.responseBody || {}; el('integrationError').textContent = (body.code ? body.code + ': ' : '') + (body.message || error.message); el('integrationError').hidden = false; }
    async function run(action) {
        if (busy) return; busy = true; el('integrationError').hidden = true; controls();
        try { await action(); } catch (error) { report(error); }
        finally { busy = false; controls(); }
    }
    function controls() {
        document.querySelectorAll('button, #integrationConnection').forEach(function (node) { node.disabled = busy; });
        document.querySelectorAll('#integrationCreate input, #integrationCreate select, #integrationCreate button, #integrationImport input, #integrationImport button, #integrationRemote input, #integrationRemote button').forEach(function (node) { node.disabled = busy || !mayWrite; });
        var editable = mayWrite && operation && (publication ? publication.allowedActions.includes('REVIEW') : operation.status === 'PREVIEWED');
        document.querySelectorAll('#integrationChanges select').forEach(function (node) { node.disabled = busy || !editable; });
        el('integrationAccept').textContent = t(publication ? 'mergeVisible' : 'acceptVisible');
        el('integrationReject').textContent = t(publication ? 'skipVisible' : 'rejectVisible');
        el('integrationApply').textContent = t(publication ? 'publishReviewed' : 'apply');
        ['integrationApply', 'integrationAccept', 'integrationReject'].forEach(function (id) { el(id).disabled = busy || !editable; });
        el('integrationCancel').disabled = busy || !mayWrite || !operation || !['PREVIEWED', 'FETCH_PENDING', 'FETCH_FAILED'].includes(operation.status);
        el('integrationRetry').disabled = busy || !mayWrite || !operation || !['CHECKPOINT_PENDING', 'FETCH_PENDING', 'FETCH_FAILED'].includes(operation.status);
        el('integrationRationale').disabled = busy || !mayWrite || !!(publication && !publication.allowedActions.includes('REVIEW') && !publication.allowedActions.includes('RECONCILE'));
        if (publication) {
            el('integrationApply').disabled = busy || !mayWrite || !publication.allowedActions.includes('PUBLISH');
            el('integrationCancel').disabled = busy || !mayWrite || !publication.allowedActions.includes('CANCEL');
            el('integrationRetry').disabled = busy || !mayWrite || !publication.allowedActions.includes('RETRY');
        }
        el('integrationReconcile').hidden = !publication;
        el('integrationReconcile').disabled = busy || !mayWrite || !publication || !publication.allowedActions.includes('RECONCILE');
        var availability = overview && overview.publicationAvailability, modes = availability && availability.available ? availability.modes : [];
        el('integrationPush').disabled = busy || !mayWrite || !modes.includes('PUSH');
        el('integrationSynchronize').disabled = busy || !mayWrite || !modes.includes('SYNCHRONIZE');
        el('integrationPublicationReason').textContent = availability && availability.reasonCode ? t('reason.' + availability.reasonCode) : t('publicationReady');
        var profile = selectedProfile(), capabilities = profile ? profile.capabilities : [];
        el('integrationRemote').hidden = !capabilities.includes('READ_LINK');
        el('integrationImport').hidden = overview && !capabilities.includes('FILE_IMPORT');
        el('integrationExport').disabled = busy || !mayWrite || !capabilities.includes('FILE_EXPORT');
        el('integrationSparxNotice').hidden = !overview || overview.connection.connectorId !== 'sparx-xmi-2.1';
        el('integrationPcsNotice').hidden = !overview || overview.connection.connectorId !== 'sparx-oslc-am-2.0';
        var creating = profiles.find(function (candidate) { return candidate.id + '@' + candidate.version === el('connectionProfile').value; });
        var readOnly = creating && creating.id === 'sparx-oslc-am-2.0';
        Array.from(el('connectionAuthority').options).forEach(function (choice) {
            choice.disabled = !!readOnly && ['BIDIRECTIONAL', 'PUBLISH_TARGET'].includes(choice.value);
        });
        if (el('connectionAuthority').selectedOptions[0].disabled) el('connectionAuthority').value = 'IMPORT_COPY';
        el('integrationPrevious').disabled = busy || page === 0;
        el('integrationNext').disabled = busy || (page + 1) * 40 >= filtered().length;
        el('integrationDownload').hidden = !(operation && operation.status === 'COMPLETED' && operation.direction === 'OUTBOUND');
        if (!el('integrationDownload').hidden) el('integrationDownload').href = api.downloadUrl(prefix() + '/operations/' + operation.id + '/file');
    }
    function filtered() {
        var query = el('integrationFilter').value.toLocaleLowerCase();
        return publication ? (publication.preview ? publication.preview.changes : []).filter(function (c) { return [c.externalId, c.local && c.local.title, c.remote && c.remote.title, c.conflicts.join(' ')].join(' ').toLocaleLowerCase().includes(query); }) : operation ? operation.changes.filter(function (c) { return [c.externalId, c.kind, c.after && c.after.title, c.conflicts.join(' ')].join(' ').toLocaleLowerCase().includes(query); }) : [];
    }
    function visible() { return filtered().slice(page * 40, (page + 1) * 40); }
    function renderChanges() {
        el('integrationChanges').replaceChildren();
        if (publication) { renderPublicationChanges(); return; }
        visible().forEach(function (change) {
            var row = document.createElement('tr'); row.dataset.changeId = change.id; if (change.kind === 'CONFLICT') row.className = 'conflict';
            var id = document.createElement('td'); id.textContent = change.externalId;
            var kind = document.createElement('td'); kind.textContent = t('change.' + change.kind) + (change.conflicts.length ? ': ' + change.conflicts.join(', ') : '');
            var value = document.createElement('td'), detail = document.createElement('details'), summary = document.createElement('summary'), text = document.createElement('pre');
            summary.textContent = ((change.before && change.before.title) || '—') + ' → ' + ((change.after && change.after.title) || '—');
            text.tabIndex = 0; text.setAttribute('aria-label', t('values') + ' ' + change.externalId);
            text.textContent = JSON.stringify({ fields: change.fields, before: change.before, after: change.after }, null, 2); detail.append(summary, text); value.append(detail);
            var decision = document.createElement('td'), select = document.createElement('select'); select.setAttribute('aria-label', t('decision') + ' ' + change.externalId);
            option(select, '', t('choose')); ['ACCEPT', 'REJECT', 'TAKE_EXTERNAL', 'KEEP_INTERNAL'].forEach(function (key) { option(select, key, t('decision.' + key)); });
            select.value = decisions[change.id] || ''; select.disabled = !mayWrite || operation.status !== 'PREVIEWED';
            select.addEventListener('change', function () { if (select.value) decisions[change.id] = select.value; else delete decisions[change.id]; });
            decision.append(select);
            if (mayWrite && change.after && operation.status === 'PREVIEWED') {
                var advanced = document.createElement('details'), heading = document.createElement('summary'); heading.textContent = t('remap'); advanced.append(heading);
                var artifact = change.after;
                var fields = artifact.kind === 'REQUIREMENT' && operation.context.profile === 'reqif-1.2' ? ['titleAttribute', 'textAttribute'] : ['ELEMENT', 'RELATION'].includes(artifact.kind) ? ['canonicalType'] : [];
                fields.forEach(function (field) {
                    var label = document.createElement('label'), mapping = document.createElement('select'); label.textContent = t('mapping.' + field); option(mapping, '', t('profileDefault'));
                    var values = field !== 'canonicalType' ? Object.keys(artifact.attributes) : artifact.kind === 'ELEMENT' ? ['Capability', 'Process', 'CoreService', 'COIService', 'CommunicationsService', 'UserApplication', 'InformationProduct', 'BusinessRole', 'System', 'Component'] : ['REALIZES', 'SUPPORTS', 'ASSIGNED_TO', 'COMMUNICATES_WITH', 'CONTAINS', 'RELATED_TO', 'CONSUMES', 'DEPENDS_ON'];
                    if (field === 'canonicalType' && artifact.kind === 'RELATION' && operation.context.profile.startsWith('sparx-')) {
                        values = ['REALIZES', 'COMMUNICATES_WITH', 'CONTAINS', 'RELATED_TO', 'CONSUMES', 'DEPENDS_ON'];
                    }
                    values.forEach(function (key) { option(mapping, key, artifact.extensions['definition:' + key] || key); });
                    mapping.value = mappings[change.id] && mappings[change.id][field] || '';
                    mapping.addEventListener('change', function () { if (!mappings[change.id]) mappings[change.id] = {}; mappings[change.id][field] = mapping.value || null; });
                    label.append(mapping); advanced.append(label);
                });
                if (operation.context.authority === 'LINK_ONLY' && ['REQUIREMENT', 'ELEMENT'].includes(artifact.kind)) {
                    var targetLabel = document.createElement('label'), target = document.createElement('input');
                    targetLabel.textContent = t('mapping.internalIdentity'); target.maxLength = 300;
                    target.value = mappings[change.id] && mappings[change.id].internalIdentity || '';
                    target.addEventListener('input', function () { if (!mappings[change.id]) mappings[change.id] = {}; mappings[change.id].internalIdentity = target.value.trim() || null; });
                    targetLabel.append(target); advanced.append(targetLabel); fields.push('internalIdentity');
                }
                if (artifact.kind === 'RELATION' && operation.context.profileVersion === '2' && operation.context.profile.startsWith('sparx-')) {
                    var endpointLabel = document.createElement('label'), projection = document.createElement('select');
                    endpointLabel.textContent = t('endpointProjection'); option(projection, '', t('choose'));
                    ['ARCHITECTURE_RELATION', 'REQUIREMENT_MAPPING', 'PRESERVE_ONLY'].forEach(function (value) { option(projection, value, t('projection.' + value)); });
                    projection.value = endpoints[change.id] && endpoints[change.id].projection || '';
                    projection.addEventListener('change', function () {
                        if (!projection.value) { delete endpoints[change.id]; return; }
                        if (!endpoints[change.id]) endpoints[change.id] = {};
                        endpoints[change.id].projection = projection.value;
                        ['source', 'target'].forEach(function (side) {
                            var externalSide = artifact.extensions.direction === 'Destination -> Source' ? (side === 'source' ? 'target' : 'source') : side;
                            var candidate = endpointOptions[artifact.extensions[externalSide]];
                            if (candidate && !endpoints[change.id][side + 'InternalIdentity']) endpoints[change.id][side + 'InternalIdentity'] = candidate.businessIdentity;
                        });
                        renderChanges();
                    });
                    endpointLabel.append(projection); advanced.append(endpointLabel);
                    ['sourceInternalIdentity', 'targetInternalIdentity'].forEach(function (field) {
                        var label = document.createElement('label'), select = document.createElement('select');
                        label.textContent = t('endpoint.' + field); option(select, '', t('choose'));
                        var side = field === 'sourceInternalIdentity' ? 'source' : 'target';
                        if (artifact.extensions.direction === 'Destination -> Source') side = side === 'source' ? 'target' : 'source';
                        var candidate = endpointOptions[artifact.extensions[side]];
                        if (candidate) option(select, candidate.businessIdentity, candidate.kind + ' · ' + candidate.businessIdentity);
                        select.value = endpoints[change.id] && endpoints[change.id][field] || '';
                        select.addEventListener('change', function () { if (!endpoints[change.id]) endpoints[change.id] = {}; endpoints[change.id][field] = select.value || null; });
                        label.append(select); advanced.append(label);
                    });
                    var sourceEndpoint = endpointOptions[artifact.extensions.source], targetEndpoint = endpointOptions[artifact.extensions.target];
                    var endpointCode = artifact.extensions.direction === 'Bi-Directional' ? 'SPARX_DIRECTION_UNMAPPED'
                        : !sourceEndpoint || !targetEndpoint || sourceEndpoint.kind === 'PACKAGE' || targetEndpoint.kind === 'PACKAGE'
                            || sourceEndpoint.kind === 'REQUIREMENT' && targetEndpoint.kind === 'REQUIREMENT' ? 'SPARX_ENDPOINT_KIND_UNMAPPED'
                        : sourceEndpoint.kind === 'REQUIREMENT' || targetEndpoint.kind === 'REQUIREMENT' ? 'SPARX_ENDPOINT_MAPPING_REQUIRED' : '';
                    var endpointHint = document.createElement('p'); endpointHint.textContent = (endpointCode ? endpointCode + ': ' : '') + t('endpointHint'); advanced.append(endpointHint);
                    fields.push('endpoints');
                }
                if (fields.length) decision.append(advanced);
            }
            row.append(id, kind, value, decision); el('integrationChanges').append(row);
        });
        el('integrationPage').textContent = (page + 1) + ' / ' + Math.max(1, Math.ceil(filtered().length / 40)) + ' · ' + filtered().length;
        controls();
    }
    function renderPublicationChanges() {
        visible().forEach(function (change) {
            var row = document.createElement('tr'); row.dataset.changeId = change.id; if (change.conflicts.length) row.className = 'conflict';
            var identity = document.createElement('td'); identity.textContent = change.externalId;
            var direction = document.createElement('td');
            direction.textContent = t('publicationMode.' + publication.mode) + ' · ' + t('localFields') + ': ' + change.localFields.join(', ') + ' · ' + t('remoteFields') + ': ' + change.remoteFields.join(', ');
            if (change.conflicts.length) direction.textContent += ' · ' + t('conflicts') + ': ' + change.conflicts.join(', ');
            if (change.dependencies.length) direction.textContent += ' · ' + t('dependencies') + ': ' + change.dependencies.join(', ');
            if ((!change.local && change.baseLocal) || (!change.remote && change.baseRemote)) direction.textContent += ' · ' + t('deletionReview');
            var values = document.createElement('td');
            ['baseLocal', 'baseRemote', 'local', 'remote', 'merged'].forEach(function (field) {
                var details = document.createElement('details'), summary = document.createElement('summary'), value = document.createElement('pre');
                summary.textContent = t('value.' + field) + ': ' + (change[field] ? change[field].title || change[field].id : t('absent'));
                value.textContent = change[field] ? JSON.stringify(change[field], null, 2) : t('absent'); value.tabIndex = 0;
                value.setAttribute('aria-label', t('value.' + field) + ' ' + change.externalId); details.append(summary, value); values.append(details);
            });
            var decision = document.createElement('td'), select = document.createElement('select'); select.setAttribute('aria-label', t('decision') + ' ' + change.externalId);
            option(select, '', t('choose')); ['MERGE', 'KEEP_LOCAL', 'TAKE_REMOTE', 'SKIP'].forEach(function (key) {
                if (key !== 'TAKE_REMOTE' || publication.mode === 'SYNCHRONIZE') option(select, key, t('resolution.' + key));
            });
            select.value = resolutions[change.id] || ''; select.disabled = busy || !mayWrite || !publication.allowedActions.includes('REVIEW');
            select.addEventListener('change', function () { if (select.value) resolutions[change.id] = select.value; else delete resolutions[change.id]; pendingReview = null; }); decision.append(select);
            publicationMappings(change, decision);
            row.append(identity, direction, values, decision); el('integrationChanges').append(row);
        });
        el('integrationPage').textContent = (page + 1) + ' / ' + Math.max(1, Math.ceil(filtered().length / 40)) + ' · ' + filtered().length;
        controls();
    }
    function publicationMappings(change, decision) {
        var artifact = change.remote || change.local; if (!artifact) return;
        var advanced = document.createElement('details'), heading = document.createElement('summary'); heading.textContent = t('remap'); advanced.append(heading);
        if (artifact.kind === 'REQUIREMENT') ['titleAttribute', 'textAttribute'].forEach(function (field) {
            var label = document.createElement('label'), select = document.createElement('select'); label.textContent = t('mapping.' + field); option(select, '', t('profileDefault'));
            Object.keys(artifact.attributes || {}).forEach(function (key) { option(select, key, key); });
            select.value = mappings[change.id] && mappings[change.id][field] || '';
            select.addEventListener('change', function () { if (!mappings[change.id]) mappings[change.id] = {}; mappings[change.id][field] = select.value || null; pendingReview = null; });
            label.append(select); advanced.append(label);
        });
        if (artifact.kind === 'RELATION') {
            var label = document.createElement('label'), projection = document.createElement('select'); label.textContent = t('endpointProjection'); option(projection, '', t('choose'));
            ['ARCHITECTURE_RELATION', 'REQUIREMENT_MAPPING', 'PRESERVE_ONLY'].forEach(function (key) { option(projection, key, t('projection.' + key)); });
            projection.value = endpoints[change.id] && endpoints[change.id].projection || '';
            projection.addEventListener('change', function () { if (!projection.value) delete endpoints[change.id]; else { if (!endpoints[change.id]) endpoints[change.id] = {}; endpoints[change.id].projection = projection.value; } pendingReview = null; });
            label.append(projection); advanced.append(label);
            ['sourceInternalIdentity', 'targetInternalIdentity'].forEach(function (field) {
                var label = document.createElement('label'), select = document.createElement('select'); label.textContent = t('endpoint.' + field); option(select, '', t('choose'));
                var side = field === 'sourceInternalIdentity' ? 'source' : 'target';
                if (artifact.extensions.direction === 'Destination -> Source') side = side === 'source' ? 'target' : 'source';
                var candidate = endpointOptions[artifact.extensions[side]]; if (candidate) option(select, candidate.businessIdentity, candidate.businessIdentity);
                select.value = endpoints[change.id] && endpoints[change.id][field] || '';
                select.addEventListener('change', function () { if (!endpoints[change.id]) endpoints[change.id] = {}; endpoints[change.id][field] = select.value || null; pendingReview = null; });
                label.append(select); advanced.append(label);
            });
            var typeLabel = document.createElement('label'), type = document.createElement('select'); typeLabel.textContent = t('mapping.canonicalType'); option(type, '', t('profileDefault'));
            ['REALIZES', 'SUPPORTS', 'ASSIGNED_TO', 'COMMUNICATES_WITH', 'CONTAINS', 'RELATED_TO', 'CONSUMES', 'DEPENDS_ON'].forEach(function (key) { option(type, key, key); });
            type.value = endpoints[change.id] && endpoints[change.id].canonicalType || '';
            type.addEventListener('change', function () { if (!endpoints[change.id]) endpoints[change.id] = {}; endpoints[change.id].canonicalType = type.value || null; pendingReview = null; }); typeLabel.append(type); advanced.append(typeLabel);
        }
        if (['REQUIREMENT', 'RELATION'].includes(artifact.kind)) decision.append(advanced);
    }
    function showPublication(value, generic) {
        publication = value; operation = generic || operation || {}; operation.id = value.operationId; operation.direction = value.mode; operation.status = value.status;
        resolutions = Object.assign({}, value.review ? value.review.resolutions : {});
        mappings = Object.assign({}, value.review ? value.review.review.mappings : {}); endpoints = Object.assign({}, value.review ? value.review.review.endpoints : {});
        if (value.scope) { el('integrationPublicationRoot').value = value.scope.rootResource; el('integrationPublicationSelector').value = value.scope.selectorFingerprint; }
        el('integrationPublicationRevision').value = value.expectedExternalRevision || '';
        el('integrationRationale').value = value.review ? value.review.review.rationale : '';
        el('integrationProvider').textContent = overview ? overview.connection.displayName : '';
        el('integrationOperation').textContent = value.operationId + ' · ' + t('publicationMode.' + value.mode) + ' · ' + t('phase.' + value.phase);
        el('integrationProvenance').textContent = t('requestFingerprint') + ': ' + (value.requestFingerprint || '—') + '\n' + t('observationCheckpoint') + ': ' + (value.observationCheckpointId || '—') + '\n' + t('commonCheckpoint') + ': ' + (value.commonCheckpointId || '—') + '\n' + t('localCheckpoint') + ': ' + (value.localCheckpoint ? value.localCheckpoint.commitId || t('pending') : '—');
        el('integrationPublicationOutcomes').hidden = false;
        el('integrationPublicationCounts').textContent = (overview ? overview.connection.displayName + ' · ' : '') + t('acknowledged') + ': ' + value.acknowledgedCount + ' · ' + t('unknown') + ': ' + value.unknownCount + ' · ' + t('remaining') + ': ' + value.remainingCount + ' · ' + t('itemState.REJECTED_STALE') + ': ' + value.items.filter(function (i) { return i.state === 'REJECTED_STALE'; }).length + ' · ' + t('itemState.READY') + ': ' + value.items.filter(function (i) { return i.state === 'READY'; }).length + ' · ' + t('itemState.RETRYABLE_NO_EFFECT') + ': ' + value.items.filter(function (i) { return i.state === 'RETRYABLE_NO_EFFECT'; }).length + (value.failureCode ? ' · ' + value.failureCode : '');
        el('integrationPublicationItems').replaceChildren(); value.items.forEach(function (item) { var li = document.createElement('li'); li.textContent = item.resourceId + ' · ' + t('mutation.' + item.mutation) + ' · ' + t('itemState.' + item.state) + (item.failureCode ? ' · ' + item.failureCode : ''); el('integrationPublicationItems').append(li); });
        el('integrationLosses').replaceChildren(); (value.preview ? value.preview.losses : []).forEach(function (loss) { var li = document.createElement('li'); li.textContent = loss.code + ': ' + (loss.detail || ''); el('integrationLosses').append(li); });
        endpointOptions = {}; link(value.operationId); page = 0; renderChanges();
        if (value.preview && value.allowedActions.includes('REVIEW') && value.preview.changes.some(function (c) { return (c.remote || c.local || {}).kind === 'RELATION'; })) {
            api.read(prefix() + '/operations/' + value.operationId + '/endpoint-options').then(function (result) { if (publication && publication.operationId === value.operationId) { endpointOptions = result.external; renderChanges(); } }).catch(report);
        }
    }
    function publicationRequest(mode) {
        if (!overview || !el('integrationPublicationRevision').value.trim()) throw new Error(t('exactRevisionRequired'));
        return { operationId: crypto.randomUUID(), expected: overview.current, mode: mode,
            scope: { externalScope: overview.connection.externalScope, rootResource: el('integrationPublicationRoot').value.trim(), selectorFingerprint: el('integrationPublicationSelector').value.trim() },
            expectedExternalRevision: el('integrationPublicationRevision').value.trim() };
    }
    async function previewPublication(mode) {
        if (!pendingPublication) pendingPublication = publicationRequest(mode);
        var id = pendingPublication.operationId; link(id);
        try { showPublication(await api.write(prefix() + '/publication-previews', pendingPublication)); pendingPublication = null; }
        catch (error) { try { await loadOperation(id); } catch (missing) { /* The original failure remains authoritative if no operation was stored. */ } throw error; }
        finally { await refresh(); }
        el('integrationReview').focus();
    }
    function link(operationId) {
        var url = new URL(location.href); url.searchParams.set('connection', connection());
        if (operationId) url.searchParams.set('operation', operationId); else url.searchParams.delete('operation'); history.replaceState(null, '', url);
    }
    function show(value) {
        if (value.operationId && value.phase) { showPublication(value); return; }
        publication = null; el('integrationPublicationOutcomes').hidden = true;
        operation = value; endpoints = Object.assign({}, value.review ? value.review.endpoints : {}); endpointOptions = {};
        if (value.status === 'PREVIEWED' && value.context.profileVersion === '2' && value.context.profile.startsWith('sparx-')) {
            api.read(prefix() + '/operations/' + value.id + '/endpoint-options').then(function (result) { if (operation && operation.id === value.id) { endpointOptions = result.external; renderChanges(); } }).catch(report);
        }
        decisions = Object.assign({}, value.review ? value.review.decisions : {}); mappings = Object.assign({}, value.review ? value.review.mappings : {}); page = 0;
        el('integrationRationale').value = value.review ? value.review.rationale : '';
        el('integrationOperation').textContent = value.id + ' · ' + t('status.' + value.status) + (value.failureCode ? ' · ' + value.failureCode : '');
        el('integrationProvenance').textContent = JSON.stringify(value.context, null, 2);
        el('integrationLosses').replaceChildren(); value.document.losses.forEach(function (loss) {
            var item = document.createElement('li'); item.textContent = (loss.artifactId || '') + ' · ' + loss.disposition + ' · ' + loss.code + ': ' + loss.detail; el('integrationLosses').append(item);
        });
        el('integrationDiscovery').replaceChildren();
        if (value.document.metadata.discovery) JSON.parse(value.document.metadata.discovery).resources.forEach(function (resource) {
            var item = document.createElement('li'), button = document.createElement('button'); button.type = 'button'; button.textContent = resource.type + ' · ' + resource.uri;
            button.addEventListener('click', function () { el('integrationRemoteUri').value = resource.uri; el('integrationRemoteVersion').value = ''; pendingRemote = null; el('integrationRemoteUri').focus(); }); item.append(button); el('integrationDiscovery').append(item);
        });
        link(value.id); renderChanges();
    }
    async function loadOperation(id) {
        var version = generation, path = prefix() + '/operations/' + encodeURIComponent(id);
        var values = await Promise.all([api.read(path), api.read(path + '/events')]); if (version !== generation) return;
        if (['PUSH', 'SYNCHRONIZE'].includes(values[0].direction)) { var pub = await api.read(path + '/publication'); if (version !== generation) return; showPublication(pub, values[0]); } else show(values[0]); el('integrationEvents').replaceChildren();
        values[1].forEach(function (event) { var li = document.createElement('li'); li.textContent = event.occurredAt + ' · ' + event.actor + ' · ' + event.type + (event.rationale ? ' · ' + event.rationale : '') + (event.failureCode ? ' · ' + event.failureCode : ''); el('integrationEvents').append(li); });
    }
    async function refresh() {
        if (!connection()) { overview = null; controls(); return; }
        var version = ++generation; var scopeQuery = el('integrationPublicationRoot').value.trim() && el('integrationPublicationSelector').value.trim() ? '?rootResource=' + encodeURIComponent(el('integrationPublicationRoot').value.trim()) + '&selectorFingerprint=' + encodeURIComponent(el('integrationPublicationSelector').value.trim()) : ''; var value = await api.read(prefix() + scopeQuery); if (version !== generation) return;
        overview = value; el('integrationContext').textContent = JSON.stringify({ current: value.current, authority: value.connection.authority, external: value.connection.externalScope, observationCheckpoint: value.checkpoint, commonCheckpointId: value.connection.commonCheckpointId }, null, 2);
        el('integrationOslcCatalog').href = window.TaxonomyI18n.resolveUrl(value.oslcCatalogPath); el('integrationOslcCatalog').hidden = false;
        el('integrationHistory').replaceChildren(); value.history.forEach(function (entry) {
            var item = document.createElement('li'), button = document.createElement('button'); button.type = 'button';
            button.textContent = entry.createdAt + ' · ' + t('status.' + entry.status) + ' · ' + entry.id; button.addEventListener('click', function () { run(function () { return loadOperation(entry.id); }); }); item.append(button); el('integrationHistory').append(item);
        });
        el('integrationStatus').textContent = t('ready'); controls();
    }
    async function connections(selected) {
        var values = await api.read(''); el('integrationConnection').replaceChildren(); option(el('integrationConnection'), '', t('choose'));
        values.forEach(function (value) { option(el('integrationConnection'), value.id, value.displayName + ' · ' + value.connectorId + '@' + value.profileVersion); });
        if (selected) el('integrationConnection').value = selected; else if (values.length) el('integrationConnection').value = values[0].id;
        await refresh();
    }
    el('integrationConnection').addEventListener('change', function () {
        operation = null; publication = null; resolutions = {}; pendingPublication = null; pendingReview = null; decisions = {}; mappings = {}; pendingUpload = null; pendingExport = null; pendingRemote = null;
        ['integrationOperation', 'integrationProvenance', 'integrationEvents', 'integrationLosses', 'integrationDiscovery'].forEach(function (id) { el(id).replaceChildren(); });
        el('integrationRationale').value = ''; link(null); run(refresh); renderChanges();
    });
    el('integrationRefresh').addEventListener('click', function () { run(async function () { await refresh(); if (operation) await loadOperation(operation.id); }); });
    el('integrationCreate').addEventListener('submit', function (event) { event.preventDefault(); run(async function () {
        if (!pendingCreation) pendingCreation = { id: crypto.randomUUID(), name: el('connectionName').value, connectorId: el('connectionProfile').value.split('@')[0], profileVersion: el('connectionProfile').value.split('@')[1], authority: el('connectionAuthority').value,
            projectId: el('connectionProject').value ? Number(el('connectionProject').value) : null, remoteProfile: el('connectionRemote').value || null, externalScope: { systemType: el('externalSystem').value, repository: el('externalRepository').value, configuration: el('externalConfiguration').value || null } };
        var created = await api.write('', pendingCreation); pendingCreation = null; await connections(created.id); link(null);
    }); });
    el('integrationCreate').addEventListener('input', function () { pendingCreation = null; });
    el('integrationRemote').addEventListener('input', function () { pendingRemote = null; });
    el('integrationRemote').addEventListener('submit', function (event) { event.preventDefault(); run(async function () {
        if (!overview) throw new Error(t('choose'));
        if (!pendingRemote) pendingRemote = { operationId: crypto.randomUUID(), expected: overview.current, resource: el('integrationRemoteUri').value, expectedExternalVersion: el('integrationRemoteVersion').value || null };
        try { show(await api.write(prefix() + '/remote-previews', pendingRemote)); pendingRemote = null; }
        finally { await refresh(); }
    }); });
    el('integrationFile').addEventListener('change', function () { pendingUpload = null; }); el('integrationComplete').addEventListener('change', function () { pendingUpload = null; });
    el('connectionProfile').addEventListener('change', controls);
    el('integrationImport').addEventListener('submit', function (event) { event.preventDefault(); run(async function () {
        if (!overview) throw new Error(t('choose')); var file = el('integrationFile').files[0]; if (!file) return;
        var profile = selectedProfile();
        if (!profile || !Array.isArray(profile.mediaTypes) || !profile.mediaTypes.length) throw new Error(t('profileUnavailable'));
        if (!pendingUpload) pendingUpload = { operationId: crypto.randomUUID(), expected: overview.current, mediaType: profile.mediaTypes.includes('application/xml') ? 'application/xml' : profile.mediaTypes[0], completeScope: el('integrationComplete').checked };
        show(await api.upload(prefix() + '/previews', pendingUpload, file)); pendingUpload = null; await refresh(); el('integrationReview').focus();
    }); });
    el('integrationExport').addEventListener('click', function () { run(async function () {
        if (!pendingExport) pendingExport = { operationId: crypto.randomUUID(), expected: overview.current, expectedExternalVersion: overview.checkpoint ? overview.checkpoint.externalVersion : null };
        show(await api.write(prefix() + '/export-previews', pendingExport)); pendingExport = null; await refresh(); el('integrationReview').focus();
    }); });
    el('integrationFilter').addEventListener('input', function () { page = 0; renderChanges(); });
    el('integrationPrevious').addEventListener('click', function () { page = Math.max(0, page - 1); renderChanges(); }); el('integrationNext').addEventListener('click', function () { page++; renderChanges(); });
    el('integrationAccept').addEventListener('click', function () { if (publication) { visible().forEach(function (c) { if (!c.conflicts.length && c.local && c.remote) resolutions[c.id] = 'MERGE'; }); renderChanges(); return; } visible().forEach(function (c) { if (c.kind !== 'CONFLICT' && c.kind !== 'REMOVE_CANDIDATE') decisions[c.id] = 'ACCEPT'; }); renderChanges(); });
    el('integrationReject').addEventListener('click', function () { if (publication) { visible().forEach(function (c) { resolutions[c.id] = 'SKIP'; }); renderChanges(); return; } visible().forEach(function (c) { decisions[c.id] = 'REJECT'; }); renderChanges(); });
    el('integrationApply').addEventListener('click', function () { run(async function () {
        if (publication) {
            if (!publication.preview || publication.preview.changes.some(function (c) { return !resolutions[c.id]; }) || !el('integrationRationale').value.trim()) throw new Error(t('reviewRequired'));
            if (!pendingReview) pendingReview = { review: { operationId: publication.operationId, previewFingerprint: publication.preview.fingerprint, decisions: {}, rationale: el('integrationRationale').value.trim(), mappings: Object.assign({}, mappings), endpoints: Object.assign({}, endpoints) }, resolutions: Object.assign({}, resolutions) };
            var frozenReview = pendingReview, publicationId = publication.operationId;
            try { showPublication(await api.write(prefix() + '/publish', frozenReview)); pendingReview = null; }
            finally { await refresh(); await loadOperation(publicationId); if (publication && publication.allowedActions.includes('REVIEW')) { resolutions = Object.assign({}, frozenReview.resolutions); el('integrationRationale').value = frozenReview.review.rationale; mappings = Object.assign({}, frozenReview.review.mappings); endpoints = Object.assign({}, frozenReview.review.endpoints); renderChanges(); } }
            return;
        }
        var review = operation.review || { operationId: operation.id, previewFingerprint: operation.fingerprint, decisions: Object.assign({}, decisions), rationale: el('integrationRationale').value.trim(), mappings: Object.assign({}, mappings), endpoints: Object.assign({}, endpoints) };
        var id = operation.id;
        try { show(await api.write(prefix() + (operation.direction === 'OUTBOUND' ? '/files' : '/apply'), review)); }
        finally {
            await refresh(); await loadOperation(id);
            if (operation.status === 'PREVIEWED' && operation.fingerprint === review.previewFingerprint) {
                decisions = Object.assign({}, review.decisions); mappings = Object.assign({}, review.mappings); endpoints = Object.assign({}, review.endpoints);
                el('integrationRationale').value = review.rationale; renderChanges();
            }
        }
    }); });
    el('integrationCancel').addEventListener('click', function () { run(async function () { show(await api.write(prefix() + '/operations/' + operation.id + '/cancel', { rationale: el('integrationRationale').value.trim() })); await refresh(); }); });
    el('integrationRetry').addEventListener('click', function () { run(async function () { show(await api.write(prefix() + '/operations/' + operation.id + '/retry', {})); await refresh(); await loadOperation(operation.id); }); });
    el('integrationPush').addEventListener('click', function () { run(function () { return previewPublication('PUSH'); }); });
    el('integrationSynchronize').addEventListener('click', function () { run(function () { return previewPublication('SYNCHRONIZE'); }); });
    el('integrationPublicationCheck').addEventListener('click', function () { run(refresh); });
    ['integrationPublicationRoot', 'integrationPublicationSelector', 'integrationPublicationRevision'].forEach(function (id) { el(id).addEventListener('input', function () { pendingPublication = null; if (overview) overview.publicationAvailability = { available: false, modes: [], reasonCode: overview.publicationAvailability && overview.publicationAvailability.reasonCode === 'PUBLICATION_GUARANTEES_UNVERIFIED' ? 'PUBLICATION_GUARANTEES_UNVERIFIED' : 'PUBLICATION_SCOPE_REQUIRED' }; controls(); }); });
    el('integrationReconcile').addEventListener('click', function () { run(async function () {
        var predecessor = publication.operationId, request = publicationRequest(publication.mode);
        var value = await api.write(prefix() + '/operations/' + predecessor + '/reconciliation-previews', { predecessorOperationId: predecessor, request: request, rationale: el('integrationRationale').value.trim() || t('reconciliationRationale') });
        showPublication(value); await refresh();
    }); });
    window.TaxonomyI18n.ready().then(function () { return run(async function () {
        document.querySelectorAll('[data-i18n]').forEach(function (node) { node.textContent = window.TaxonomyI18n.t(node.getAttribute('data-i18n')); });
        profiles = await api.read('/profiles'); profiles.forEach(function (profile) { option(el('connectionProfile'), profile.id + '@' + profile.version, profile.title + ' · ' + profile.id + '@' + profile.version); });
        var params = new URLSearchParams(location.search); await connections(params.get('connection')); if (params.get('operation') && connection()) await loadOperation(params.get('operation'));
    }); }).catch(report);
}());
