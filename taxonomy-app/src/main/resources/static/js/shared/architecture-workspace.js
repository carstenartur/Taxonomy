/* One architecture workspace. Source adapters retain their own data, drafts and viewport;
 * activating an immutable source never changes the working architecture. */
(function () {
    'use strict';
    var kinds = ['analysis', 'snapshot', 'working'];
    var pinKeys = ['repositoryId', 'workspaceId', 'workspaceScopeKey', 'branch', 'projectId', 'requirementId',
        'requirementVersionId', 'snapshotId', 'commit', 'revision', 'element', 'viewId', 'layout', 'layoutRevision',
        'sourceRepositoryId', 'sourceWorkspaceId', 'sourceWorkspaceScopeKey', 'sourceBranch', 'sourceProjectId',
        'sourceRequirementId', 'sourceRequirementVersionId', 'sourceSnapshotId', 'adoption'];
    function parseSource(url) {
        var kind = url.searchParams.get('architectureSource');
        if (/\/architecture\/editor$/.test(url.pathname)) kind = 'working';
        if (/\/architecture\/workbench$/.test(url.pathname)) kind = 'snapshot';
        var value = {kind: kinds.includes(kind) ? kind : 'analysis'};
        pinKeys.forEach(function (key) { if (url.searchParams.has(key)) value[key] = url.searchParams.get(key); });
        if (!value.workspaceScopeKey && value.workspaceId !== undefined) value.workspaceScopeKey = value.workspaceId || '__shared__';
        return value;
    }
    function urlFor(url, source, path) {
        var target = new URL(url.href);
        if (path) target.pathname = path;
        pinKeys.forEach(function (key) {
            if (source[key] !== undefined && source[key] !== null) target.searchParams.set(key, source[key]);
            else target.searchParams.delete(key);
        });
        target.searchParams.set('architectureSource', source.kind);
        target.hash = 'architecture';
        return target.href;
    }
    function create(options) {
        var current = Object.assign({}, options.initial), retained = {}, activated = new Set(), active = false, generation = 0;
        retained[current.kind] = current;
        var adapters = options.adapters;
        function getSource() {
            return Object.assign({}, current, active ? adapters[current.kind]?.getSource?.() : null, {kind:current.kind});
        }
        async function open(kind, settings) {
            if (!kinds.includes(kind)) throw new Error('Unknown architecture source: ' + kind);
            settings = settings || {};
            var ticket = ++generation, prior = adapters[current.kind];
            var next = Object.assign({}, settings.source || retained[kind] || options.defaults?.[kind] || {}, {kind:kind});
            pinKeys.forEach(function (key) { if (settings[key] !== undefined) next[key] = settings[key]; });
            if (settings.workspaceId !== undefined && settings.workspaceScopeKey === undefined) next.workspaceScopeKey = settings.workspaceId || '__shared__';
            if (next.workspaceScopeKey !== undefined) next.workspaceId = next.workspaceScopeKey === '__shared__' ? '' : next.workspaceScopeKey;
            else if (next.workspaceId !== undefined) next.workspaceScopeKey = next.workspaceId || '__shared__';
            if (options.initialize && await options.initialize() === false) return false;
            if (ticket !== generation) return false;
            if (active && prior?.canLeave && !await prior.canLeave(next, {retain:kind !== current.kind})) return false;
            var target = adapters[kind];
            if (activated.has(kind) && (!active || target !== prior) && target?.canLeave && !await target.canLeave(next,{retain:false})) return false;
            if (ticket !== generation) return false;
            retained[current.kind] = getSource();
            if (active) prior?.suspend?.();
            current = next; retained[kind] = next; active = true;
            options.beforeChange?.(current, settings);
            activated.add(kind);
            await adapters[kind]?.activate?.(current);
            if (ticket !== generation || !active) return false;
            current = getSource(); retained[kind] = current;
            options.onChange?.(current, settings);
            return true;
        }
        return {
            open:open, getSource:getSource, isActive:function () { return active; },
            getData:function () { return adapters[current.kind]?.getData?.() ?? null; },
            getDiagram:function () { return adapters[current.kind]?.getDiagram?.(); },
            select:function (id) { current.element = id; adapters[current.kind]?.select?.(id); },
            rememberSelection:function (id) { current.element = id; },
            canLeave:function () { return adapters[current.kind]?.canLeave?.(current,{retain:true}) ?? true; },
            suspend:function () { retained[current.kind] = getSource(); current = retained[current.kind]; active = false; generation++; adapters[current.kind]?.suspend?.(); },
            refresh:function () { if (active) options.onChange?.(getSource(),{replace:true}); }
        };
    }
    window.TaxonomyArchitectureWorkspaceController = Object.freeze({create:create, parseSource:parseSource, urlFor:urlFor});
    var root = document.getElementById('architectureWorkspace');
    if (!root) return;
    var initial = parseSource(new URL(window.location.href)), editor, snapshot, lens, workspace;
    var initialScope = Object.assign({}, window.TaxonomyArchitectureWorkflow?.getScope?.());
    var analysisScope = initial.kind === 'analysis' ? Object.assign({},initialScope) : {};
    if (initial.kind === 'analysis') ['repositoryId','workspaceId','workspaceScopeKey','branch','projectId','requirementId','requirementVersionId'].forEach(function (key) {
        if (initial[key] !== undefined) analysisScope[key] = initial[key];
    });
    var contextKeys = ['repositoryId','workspaceId','workspaceScopeKey','branch'];
    var analysisUrlSource = initial.kind === 'analysis' ? explicitAnalysisUrlSource(initial) : null;
    var pendingAnalysisHistory = null;
    function explicitAnalysisUrlSource(source) {
        if (!contextKeys.some(function (key) { return source[key] !== undefined; })) return null;
        var value = {kind:'analysis'};
        pinKeys.forEach(function (key) { if (source[key] !== undefined) value[key] = source[key]; });
        if (value.workspaceScopeKey !== undefined) value.workspaceId = value.workspaceScopeKey === '__shared__' ? '' : value.workspaceScopeKey;
        else if (value.workspaceId !== undefined) value.workspaceScopeKey = value.workspaceId || '__shared__';
        return value;
    }
    function sourceForUrl(source) {
        if (source.kind !== 'analysis' || !analysisUrlSource) return source;
        // Shell links can explicitly select an import destination, including shared.
        // The retained analysis owns its separate evidence, header and workflow scope.
        var value = Object.assign({},analysisUrlSource);
        ['element','viewId','layout','layoutRevision'].forEach(function (key) {
            if (source[key] !== undefined) value[key] = source[key];
        });
        return value;
    }
    var analysisGeneration = 0, analysisMetadata = null, sourceInitialization = null, sourceActivation = null;
    var workingScope = Object.assign({kind:'working'}, initialScope);
    var snapshotScope = Object.assign({kind:'snapshot'}, initialScope);
    ['repositoryId','workspaceId','workspaceScopeKey','branch','projectId','requirementId','requirementVersionId','snapshotId'].forEach(function (key) {
        var sourceKey = 'source' + key.charAt(0).toUpperCase() + key.slice(1);
        if (initial[sourceKey] !== undefined) snapshotScope[key] = initial[sourceKey];
    });
    function el(id) { return document.getElementById(id); }
    function t(key) { return window.TaxonomyI18n.t(key); }
    function analysisDiagram() {
        return el('impactGraphView')?.__taxonomyImpactMapDiagram;
    }
    function sessionInitialization() {
        if (window.TaxonomyAnalysisSession?.whenInitialized) return window.TaxonomyAnalysisSession.whenInitialized();
        if (window.TaxonomyAnalysisSessionReady) return window.TaxonomyAnalysisSessionReady;
        var loader = document.querySelector('script[data-taxonomy-analysis-session]');
        if (!loader) return Promise.resolve(true);
        return new Promise(function (resolve) {
            function finish(value) {
                loader.removeEventListener('load', loaded); loader.removeEventListener('error', failed); resolve(value);
            }
            function loaded() { finish(window.TaxonomyAnalysisSessionReady || false); }
            function failed() { finish(false); }
            loader.addEventListener('load', loaded, {once:true}); loader.addEventListener('error', failed, {once:true});
        });
    }
    function initializeSource() {
        if (!sourceInitialization) {
            var current = window.TaxonomyApiClient.captureContext({contextSensitive:false});
            var pending = window.TaxonomyI18n.ready()
                .then(function () { return window.TaxonomyArchitectureLifecycle?.ready?.(); })
                .then(sessionInitialization)
                .then(function (initialized) {
                    if (!current()) return false;
                    if (initialized === false && !window.TaxonomyAnalysisSession) throw new Error(t('sourceLens.unavailable'));
                    var bar = window.TaxonomyContextBar;
                    if (!bar?.fetchAndRender) return true;
                    return bar.fetchAndRender('contextBar', {joinCurrent:true}).then(function (context) {
                        if (!current()) return false;
                        if (!context) throw new Error(t('sourceLens.unavailable'));
                        return true;
                    });
                });
            sourceInitialization = pending;
            pending.catch(function () {
                if (sourceInitialization === pending) sourceInitialization = null;
            });
        }
        return sourceInitialization;
    }
    async function resolveAnalysisScope() {
        var initialized = await sessionInitialization();
        var session = window.TaxonomyAnalysisSession;
        if (initialized === false && !session) throw new Error(t('sourceLens.unavailable'));
        if (session?.whenInitialized) await session.whenInitialized();
        var saved = session?.state?.();
        var workspaceId = saved?.workspaceId || '';
        var scopeKey = workspaceId || '__shared__';
        var retained = analysisScope.workspaceScopeKey === scopeKey || analysisScope.workspaceScopeKey === undefined ? analysisScope : {};
        var exact = Object.assign({},retained,{workspaceId:workspaceId,workspaceScopeKey:scopeKey});
        if (analysisMetadata && analysisMetadata.context.workspaceScopeKey === exact.workspaceScopeKey) {
            return Object.assign({},exact,{repositoryId:analysisMetadata.context.repositoryId,branch:analysisMetadata.context.branch});
        }
        // Metadata resolves this retained analysis session independently of the displayed editor/snapshot.
        // No working graph is used as analysis evidence.
        var value = await window.TaxonomyArchitectureLifecycleApi.overview(exact);
        if ((window.TaxonomyAnalysisSession?.state?.().workspaceId || '') !== workspaceId) return resolveAnalysisScope();
        analysisMetadata = value;
        return Object.assign({},exact,{repositoryId:value.context.repositoryId,branch:value.context.branch});
    }
    async function restoreAnalysisContext(page) {
        if (pendingAnalysisHistory) {
            var pending = pendingAnalysisHistory; pendingAnalysisHistory = null;
            if (pending.url.pathname === location.pathname && pending.url.search === location.search && pending.source.kind === 'analysis') {
                analysisUrlSource = explicitAnalysisUrlSource(pending.source);
            }
        }
        var ticket = ++analysisGeneration;
        if (await initializeSource() === false || ticket !== analysisGeneration) return;
        var resolved = await resolveAnalysisScope();
        if (ticket !== analysisGeneration || (workspace.isActive() && workspace.getSource().kind !== 'analysis')) return;
        analysisScope = resolved;
        window.TaxonomyArchitectureWorkflow?.replaceScope(analysisScope);
        window.TaxonomyAppChrome?.updateContext?.({architecture:{repositoryId:resolved.repositoryId,
            workspaceScopeKey:resolved.workspaceScopeKey,branch:resolved.branch,title:analysisMetadata?.architectureTitle,
            modelStand:null,commit:null,revision:null},editable:false},'analysis');
        window.TaxonomyAppChrome?.activateContext?.('analysis');
        if (page) {
            var target = new URL(urlFor(new URL(location.href),sourceForUrl(Object.assign({kind:'analysis'},analysisScope)),window.TaxonomyI18n.resolveUrl('/')));
            target.hash=page;history.replaceState(null,'',target);
        }
    }
    function dimensions() {
        if (!workspace.isActive()) return;
        var overview = document.querySelector('#architectureSubTabs .nav-link.active')?.dataset.subtab === 'arch-overview';
        var split = workspace.getSource().kind === 'analysis' && overview;
        if (el('leftPanel')) el('leftPanel').className = split ? 'col-lg-5' : 'd-none';
        if (el('rightPanel')) el('rightPanel').className = split ? 'col-lg-7' : 'col-12';
        scheduleSnapshotHeight();
    }
    var snapshotContextHomes = [], snapshotHeightPending = false;
    function positionSnapshotContext(enabled) {
        if (enabled && !snapshotContextHomes.length) {
            var inspector = el('architectureSnapshotSource')?.querySelector?.('.workbench-detail-panel');
            if (!inspector) return;
            ['architectureSourceDescription','architectureWorkflow','architectureStatus','architectureVisibleStatus'].forEach(function (id) {
                var node = el(id);
                if (!node?.parentNode) return;
                snapshotContextHomes.push({node:node,parent:node.parentNode,next:node.nextSibling});
                inspector.appendChild(node);
            });
        } else if (!enabled) {
            snapshotContextHomes.forEach(function (home) {
                home.parent.insertBefore(home.node, home.next?.parentNode === home.parent ? home.next : null);
            });
            snapshotContextHomes = [];
        }
    }
    function scheduleSnapshotHeight() {
        if (snapshotHeightPending || !window.requestAnimationFrame) return;
        snapshotHeightPending = true;
        window.requestAnimationFrame(function () {
            snapshotHeightPending = false;
            var grid = el('architectureSnapshotSource')?.querySelector?.('.workbench-grid');
            if (!grid || window.matchMedia?.('print').matches) return;
            if (!workspace.isActive() || workspace.getSource().kind !== 'snapshot' || window.innerWidth <= 900) {
                grid.style.removeProperty('--architecture-snapshot-height');
                return;
            }
            if (!grid.getClientRects().length) return;
            // Document coordinates keep ordinary page scrolling from resizing the diagram.
            var height = Math.max(0, Math.floor(window.innerHeight - grid.getBoundingClientRect().top - window.scrollY - 8)) + 'px';
            if (grid.style.getPropertyValue('--architecture-snapshot-height') !== height) {
                grid.style.setProperty('--architecture-snapshot-height', height);
            }
        });
    }
    function writeUrl(source, settings) {
        var target = urlFor(new URL(location.href), sourceForUrl(source), window.TaxonomyI18n.resolveUrl('/'));
        history[settings?.replace ? 'replaceState' : 'pushState'](null, '', target);
        window.TaxonomyAppChrome?.refreshNavigation();
    }
    function publish(source, settings) {
        writeUrl(source, {replace:true});
        window.TaxonomyAppChrome?.activateContext?.(source.kind);
        if (source.kind === 'working') lens?.suspend(); else lens?.activate(source);
        el('architectureSourceDescription').textContent = t('architecture.workspace.' + source.kind + '.help');
        dimensions();
        document.dispatchEvent(new CustomEvent('taxonomy:architecture-source-changed', {detail:{source:source,data:workspace.getData(),diagram:workspace.getDiagram(),active:true}}));
    }
    workspace = create({initial:initial, initialize:initializeSource, defaults:{analysis:Object.assign({kind:'analysis'},analysisScope),working:workingScope,snapshot:snapshotScope},
        adapters:{
            analysis:{
                activate:async function () {
                    await restoreAnalysisContext();
                    analysisDiagram()?.setActive?.(workspace.isActive() && workspace.getSource().kind === 'analysis');
                },
                suspend:function () { analysisGeneration++;analysisDiagram()?.setActive?.(false);lens?.suspend(); },
                getSource:function () { return analysisScope; },
                getData:function () { return window.TaxonomyState?.currentArchView; },
                getDiagram:analysisDiagram,
                select:function (id) { analysisDiagram()?.select(id); }
            },
            working:{
                activate:async function (source) { editor = editor || window.TaxonomyArchitectureEditor.mount(el('architectureWorkingSource')); await editor.activate(source); },
                suspend:function () { editor?.suspend(); },
                canLeave:function (next,settings) { return editor?.canLeave(next,settings) ?? true; },
                getSource:function () { return editor?.getSource(); }, getData:function () { return editor?.getData(); },
                getDiagram:function () { return editor?.getDiagram(); }, select:function (id) { editor?.select(id); }
            },
            snapshot:{
                activate:async function (source) { snapshot = snapshot || window.TaxonomyArchitectureSnapshot.mount(el('architectureSnapshotSource')); await snapshot.activate(source); },
                suspend:function () { snapshot?.suspend(); lens?.suspend(); },
                getSource:function () { return snapshot?.getSource(); }, getData:function () { return snapshot?.getData(); },
                getDiagram:function () { return snapshot?.getDiagram(); }, select:function (id) { snapshot?.select(id); }
            }
        },
        beforeChange:function (source,settings) {
            if (source.kind === 'analysis' && (settings.source || contextKeys.some(function (key) { return settings[key] !== undefined; }))) {
                pendingAnalysisHistory = null;
                analysisUrlSource = explicitAnalysisUrlSource(settings.source || settings);
            }
            lens?.suspend();
            el('architectureWorkspaceError').hidden=true;
            if(source.kind!=='working')window.TaxonomyArchitectureLifecycle?.suspend?.();
            kinds.forEach(function (kind) { el('architecture' + kind[0].toUpperCase() + kind.slice(1) + 'Source').hidden = kind !== source.kind; });
            positionSnapshotContext(source.kind === 'snapshot');
            el('architectureSource').value = source.kind;
            window.TaxonomyArchitectureWorkflow?.replaceScope(source);
            el('architectureWorkflow').hidden = true;
            el('architectureLifecycle').hidden = source.kind !== 'working';
            writeUrl(source,settings); dimensions();
        }, onChange:publish
    });
    var activateSource = workspace.open;
    workspace.open = function(kind,settings){
        var activation = activateSource(kind,settings).then(function (accepted) {
            if(accepted)window.navigateToPage?.('architecture');
            return accepted;
        });
        sourceActivation = activation;
        return activation;
    };
    function awaitSelectedSource(activation) {
        return activation.then(function (ready) {
            return sourceActivation !== activation ? awaitSelectedSource(sourceActivation) : ready;
        });
    }
    function restoreAnalysisPage(page) {
        sourceActivation = restoreAnalysisContext(page).then(function () { return true; })
            .catch(function (error) { report(error); return false; });
        return sourceActivation;
    }
    workspace.openLens = function () {
        if (workspace.getSource().kind === 'working') editor?.openLens(); else { lens?.select(workspace.getSource().element);lens?.open(); }
    };
    workspace.restoreUrl=function(){pendingAnalysisHistory=null;writeUrl(workspace.getSource(),{replace:true});};
    window.TaxonomyArchitectureWorkspace = workspace;
    if (window.TaxonomyArchitectureSourceLens) lens = window.TaxonomyArchitectureSourceLens.create({root:el('architectureSourceLens'),workspace:workspace,idPrefix:'architectureSourceLens'});
    function open(kind, settings) {
        return workspace.open(kind,settings).catch(function (error) {
            var target = el('architectureWorkspaceError'); target.textContent = error.message; target.hidden = false;
            return false;
        });
    }
    el('architectureSource').addEventListener('change',function () { open(this.value).then(function(ok){if(!ok)el('architectureSource').value=workspace.getSource().kind;}); });
    document.addEventListener('taxonomy:page-activated',function (event) {
        if (event.detail.page === 'architecture') { if (!workspace.isActive()) open(workspace.getSource().kind,{replace:true}); else dimensions(); }
        else { workspace.suspend(); positionSnapshotContext(false); lens?.suspend(); window.TaxonomyArchitectureLifecycle?.suspend?.(); restoreAnalysisPage(event.detail.page); }
    });
    document.addEventListener('taxonomy:architecture-diagram-updated',function () { if(workspace.isActive()) { lens?.refresh(); dimensions(); } });
    window.addEventListener('resize', scheduleSnapshotHeight);
    if (typeof ResizeObserver !== 'undefined') {
        var snapshotChromeObserver = new ResizeObserver(scheduleSnapshotHeight);
        [document.querySelector('.taxonomy-app-chrome'),root.querySelector?.('.architecture-source-toolbar')].forEach(function (node) {
            if (node) snapshotChromeObserver.observe(node);
        });
    }
    document.addEventListener('click',function (event) {
        if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        var link = event.target.closest?.('a[href]'); if (!link) return;
        var target = new URL(link.href,location.href);
        if (target.origin !== location.origin) return;
        var source = parseSource(target), legacy = /\/architecture\/(editor|workbench)$/.test(target.pathname);
        var local = target.pathname === new URL(window.TaxonomyI18n.resolveUrl('/'),location.href).pathname;
        if (!legacy && !(local && target.hash === '#architecture' && target.searchParams.has('architectureSource'))) return;
        event.preventDefault();
        open(source.kind,{source:source}).then(function (ok) { if(ok)window.navigateToPage?.('architecture'); });
    });
    window.addEventListener('popstate',function () {
        var target = new URL(location.href), source = parseSource(target);
        if (location.hash !== '#architecture') {
            // A visible working source may still refuse the page change. Commit
            // this destination only when its normal page activation is accepted.
            pendingAnalysisHistory = {url:target,source:source};
            if (!workspace.isActive()) restoreAnalysisPage(location.hash.slice(1) || 'analyze');
            return;
        }
        pendingAnalysisHistory = null;
        open(source.kind,{source:source,replace:true}).then(function(ok){if(!ok)workspace.restoreUrl();});
    });
    document.addEventListener('taxonomy:architecture-selection',function(event){
        if(!workspace.isActive() || event.detail?.kind!==workspace.getSource().kind)return;
        workspace.rememberSelection(event.detail.elementId);lens?.select(event.detail.elementId);
    });
    if(typeof MutationObserver!=='undefined' && el('architectureViewContent')) {
        new MutationObserver(function(){
            var analysisActive=workspace.isActive() && workspace.getSource().kind==='analysis';
            analysisDiagram()?.setActive?.(analysisActive);
            if(analysisActive)lens?.refresh();
        })
            .observe(el('architectureViewContent'),{childList:true});
    }
    window.addEventListener('beforeunload',function (event) {
        if (editor?.hasDraft?.()) { event.preventDefault(); event.returnValue = ''; }
    });
    var initialReady = null, initialFailed = false, initialSettled = false, initialWorkspace;
    workspace.ready = function (retry) {
        var workspaceChanged = initialSettled
            && initialWorkspace !== window.TaxonomyAnalysisSession?.state?.().workspaceId;
        var refreshRetained = workspaceChanged || retry === true && initialFailed;
        if (refreshRetained) { initialReady = null; sourceInitialization = null; }
        if (!initialReady) {
            initialFailed = false;
            initialSettled = false;
            initialReady = initializeSource().then(function (confirmed) {
                if (confirmed === false) return false;
                if (!refreshRetained && sourceActivation) return awaitSelectedSource(sourceActivation);
                // Workspace recovery confirms the independent retained analysis
                // scope without reopening or replacing a visible editor draft.
                if (!refreshRetained && (location.hash === '#architecture'
                        || /\/architecture\/(editor|workbench)$/.test(location.pathname))) {
                    return awaitSelectedSource(open(initial.kind,{replace:true}));
                }
                return awaitSelectedSource(restoreAnalysisPage(location.hash.slice(1) || 'analyze'));
            }).catch(function (error) { report(error); return false; })
            .then(function (ready) {
                // The existing server-check action can retry unavailable initial
                // metadata without creating a second background polling loop.
                initialFailed = ready === false;
                initialSettled = true;
                initialWorkspace = window.TaxonomyAnalysisSession?.state?.().workspaceId;
                return ready;
            });
        }
        return initialReady;
    };
    workspace.ready();
    function report(error) { var target=el('architectureWorkspaceError');target.textContent=error.message;target.hidden=false; }
}());
