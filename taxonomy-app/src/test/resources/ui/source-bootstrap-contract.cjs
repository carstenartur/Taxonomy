'use strict';

// JUnit invokes one case per process; the VM runs the real feature controllers,
// named clients, source navigation, context bar and canonical response guards.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const [scenario, resources] = process.argv.slice(2);
const source = name => fs.readFileSync(path.join(resources, 'static/js', name), 'utf8');
const deferred = () => {
    let resolve;
    const promise = new Promise(yes => { resolve = yes; });
    return {promise, resolve};
};
const settle = () => new Promise(resolve => setImmediate(resolve));

function events(target = {}) {
    const listeners = new Map();
    return Object.assign(target, {
        addEventListener(type, listener) {
            if (!listeners.has(type)) listeners.set(type, []);
            listeners.get(type).push(listener);
        },
        removeEventListener(type, listener) {
            listeners.set(type, (listeners.get(type) || []).filter(value => value !== listener));
        },
        dispatchEvent(event) {
            event.currentTarget = this;
            event.target ||= this;
            event.preventDefault ||= function () { this.defaultPrevented = true; };
            event.results = (listeners.get(event.type) || []).slice().map(listener => listener.call(this, event));
            return !event.defaultPrevented;
        }
    });
}

function element(tag = 'div') {
    const classes = new Set(), attributes = new Map();
    return events({tagName: tag.toUpperCase(), dataset: {}, children: [], style: {},
        value: '', textContent: '', innerHTML: '', hidden: false, disabled: false,
        classList: {add: (...values) => values.forEach(value => classes.add(value)),
            remove: (...values) => values.forEach(value => classes.delete(value)),
            contains: value => classes.has(value),
            toggle(value, force) {const enabled = force === undefined ? !classes.has(value) : force;
                if (enabled) classes.add(value); else classes.delete(value); return enabled;}},
        setAttribute(name, value) {attributes.set(name, String(value));},
        getAttribute: name => attributes.get(name) ?? null,
        removeAttribute: name => attributes.delete(name),
        append(...values) {for (const value of values) {this.children.push(value); value.parentNode = this;}},
        appendChild(value) {this.append(value); return value;},
        replaceChildren(...values) {this.children = []; this.append(...values);},
        querySelector() {return null;}, querySelectorAll() {return [];},
        getClientRects() {return [];}, scrollIntoView() {}, focus() {},
        get childElementCount() {return this.children.length;},
        get firstChild() {return this.children[0] || null;},
        get lastChild() {return this.children.at(-1) || null;}
    });
}

function fixture(page, fetcher, {lifecycle = false} = {}) {
    const nodes = new Map(), timers = new Map(), requests = [], stored = new Map();
    let timerId = 0;
    const templates = page === 'portfolio' ? ['projects.html', ...(lifecycle ? ['fragments/application-chrome.html'] : [])] : ['index.html', 'architecture-workbench.html',
        'fragments/architecture-workflow.html', 'fragments/application-chrome.html'];
    for (const template of templates) {
        const text = fs.readFileSync(path.join(resources, 'templates', template), 'utf8');
        for (const match of text.matchAll(/<([a-z][\w-]*)\b[^>]*\bid="([^"]+)"/gi)) {
            nodes.set(match[2], element(match[1]));
        }
    }
    // Chrome still publishes its real context; navigation presentation is not part of this contract.
    for (const id of ['applicationContext', 'applicationNavigation', 'applicationNavigationMenu']) nodes.delete(id);
    const submitButtons = [...nodes.values()].filter(node => node.tagName === 'FORM').map(form => {
        const button = element('button'); button.type = 'submit'; form.append(button); return button;
    });
    // The server-rendered busy indicator starts hidden; production owns every later transition.
    nodes.get('portfolioBusy')?.classList.add('d-none');
    const requirementsBody = element('tbody');
    const document = events({documentElement: {lang: 'en'}, body: element('body'), cookie: '',
        getElementById: id => nodes.get(id) || null,
        querySelector(selector) {
            if (selector === '#requirementsTable tbody') return requirementsBody;
            if (selector.startsWith('meta[')) return null;
            return /^#[\w-]+$/.test(selector) ? nodes.get(selector.slice(1)) || null : null;
        },
        querySelectorAll(selector) {
            return selector.split(',').flatMap(value => {
                value = value.trim();
                if (value === 'button[type="submit"]') return submitButtons;
                return /^#[\w-]+$/.test(value) && nodes.has(value.slice(1)) ? [nodes.get(value.slice(1))] : [];
            });
        },
        createElement: element, createTextNode: text => ({textContent: String(text)})
    });
    const href = page === 'portfolio' ? 'https://example.test/taxonomy/projects?lang=en'
        : 'https://example.test/taxonomy/?architectureSource=snapshot&projectId=1&snapshotId=snapshot-A'
          + '&repositoryId=repository-A&workspaceId=workspace-A&workspaceScopeKey=workspace-A&branch=draft#architecture';
    const context = events({document, location: new URL(href), console, URL, URLSearchParams,
        Headers, Request, Response, AbortController, DOMException, Blob, Error, Promise,
        CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options?.detail;}},
        crypto: require('node:crypto').webcrypto,
        FormData: class {constructor(form) {this.values = Object.entries(form.fields);}
            forEach(callback) {this.values.forEach(([key, value]) => callback(value, key));}},
        setTimeout(callback, delay) {timers.set(++timerId, {callback, delay}); return timerId;},
        clearTimeout(id) {timers.delete(id);}, setInterval() {return 0;}, clearInterval() {},
        localStorage: {getItem: key => stored.get(key) ?? null,
            setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key)},
        sessionStorage: {getItem() {return null;}, setItem() {}, removeItem() {}},
        TaxonomyI18n: {ready: () => Promise.resolve(), getLocale: () => 'en', getBasePath: () => '/taxonomy',
            resolveUrl: value => value.startsWith('/taxonomy/') ? value : '/taxonomy' + value,
            formatBranch: value => value, formatEnum: value => value,
            t: (key, ...args) => key === 'workbench.ui.loaded' ? `Loaded ${args[0]} elements, ${args[1]} relations`
                : key + (args.length ? ':' + args.join(',') : '')},
        TaxonomyUtils: {hideModal(modal) {modal.hidden = true;}, escapeHtml: String},
        async fetch(url, options) {
            const request = {url: new URL(url, context.location), options};
            requests.push(request);
            return fetcher(request);
        }
    });
    context.window = context;
    context.history = {replaceState(_state, _title, url) {context.location = new URL(url, context.location);},
        pushState(_state, _title, url) {context.location = new URL(url, context.location);}};
    vm.createContext(context);
    const load = name => vm.runInContext(source(name), context, {filename: name});
    load('shared/architecture-workflow.js');
    const canonical = source('api/taxonomy-api-client.js');
    vm.runInContext(canonical.slice(0, canonical.indexOf('(function loadAuthenticatedUiSurfaces')), context,
        {filename: 'taxonomy-api-client.js'});
    load('shared/application-chrome.js');
    return {context, document, nodes, requests, timers, load};
}

async function portfolio(early, leavePage = false, selectionPending = false, inspectStartup = false) {
    const hydration = deferred(), initialList = deferred(), creation = deferred(), translations = deferred();
    const project = {id: 7, projectKey: 'P-CREATED', title: 'Created from the visible form',
        description: 'A project created while metadata is loading', status: 'PLANNING'};
    const existing = {id: 5, projectKey: 'P-EXISTING', title: 'Previously saved project', status: 'PLANNING'};
    let created = false, firstList = true;
    function portfolioData(selected) {
        return {project: selected,
            metrics: {totalRequirements: 0, analyzedRequirements: 0, requirementsWithoutConfirmedSolution: 0,
                totalSolutions: 0, selectedProducts: 0, openConflicts: 0}, requirements: [], taxonomyNodes: [],
            solutions: [], conflicts: [], requirementTaxonomyMatrix: null, requirementSolutionMatrix: null,
            solutionProductMatrix: null};
    }
    const app = fixture('portfolio', request => {
        const route = request.url.pathname;
        if (route === '/taxonomy/api/projects' && request.options.method === 'POST') {
            created = true; return early ? creation.promise : Response.json(project, {status: 201});
        }
        if (route === '/taxonomy/api/projects') {
            if (early && firstList) {firstList = false; return initialList.promise;}
            return Response.json(created ? [existing, project] : [existing]);
        }
        if (route === '/taxonomy/api/projects/7/portfolio') return Response.json(portfolioData(project));
        if (route === '/taxonomy/api/projects/5/portfolio') return Response.json(portfolioData(existing));
        if (route === '/taxonomy/api/products') return Response.json([]);
        throw new Error('Unexpected portfolio request ' + route);
    });
    if (inspectStartup) app.context.TaxonomyI18n.ready = () => translations.promise;
    app.context.TaxonomyArchitectureLifecycle = {ready: () => inspectStartup
        ? translations.promise.then(() => hydration.promise) : hydration.promise};
    app.load('api/portfolio-api.js');
    app.load('portfolio/taxonomy-portfolio.js');
    const started = {type: 'DOMContentLoaded'};
    app.document.dispatchEvent(started);
    function hydrate() {
        const scope = {repositoryId: 'resolved-repository', workspaceId: 'resolved-workspace',
            workspaceScopeKey: 'resolved-workspace', branch: 'draft'};
        app.context.TaxonomyArchitectureWorkflow.setScope(scope);
        app.context.TaxonomyAppChrome.updateContext({architecture: scope}, 'working');
        hydration.resolve({context: scope});
    }
    if (inspectStartup) {
        app.nodes.get('projectKey').value = 'P-UNSAVED';
        app.nodes.get('projectTitle').value = 'Keep this form draft';
        const submits = app.document.querySelectorAll('button[type="submit"]');
        assert.equal(submits.length, 4, 'All four portfolio forms are represented');
        const busy = () => ({visible: !app.nodes.get('portfolioBusy').classList.contains('d-none'),
            allSubmitsDisabled: submits.every(button => button.disabled)});
        const whileTranslationsPending = busy();
        assert.equal(app.requests.length, 0, 'Source reads wait for the held initial translations');
        translations.resolve(); await settle();
        const whileLifecyclePending = busy();
        hydrate(); await settle();
        const whileSelectionPending = busy();
        initialList.resolve(Response.json([existing]));
        await Promise.all(started.results);
        assert.deepEqual(whileTranslationsPending, {visible: true, allSubmitsDisabled: true},
            'Startup must show busy feedback and disable every form submit while translations are pending');
        assert.deepEqual(whileLifecyclePending, {visible: true, allSubmitsDisabled: true});
        assert.deepEqual(whileSelectionPending, {visible: true, allSubmitsDisabled: true});
        assert.deepEqual(busy(), {visible: false, allSubmitsDisabled: false},
            'Initialization releases its busy state after the selected project is rendered');
        assert.equal(submits.every(button => button.disabled === false), true);
        assert.equal(app.nodes.get('selectedProjectKey').textContent, 'P-EXISTING');
        assert.equal(app.nodes.get('projectKey').value, 'P-UNSAVED');
        assert.equal(app.nodes.get('projectTitle').value, 'Keep this form draft');
        return;
    }
    if (!early) {hydrate(); await Promise.all(started.results);}
    if (selectionPending) {
        hydrate();
        await settle();
        assert.equal(app.requests.filter(request => request.url.pathname === '/taxonomy/api/projects').length, 1,
            'The initial list has started after lifecycle metadata resolved');
        assert.equal(app.nodes.get('selectedProjectKey').textContent, '', 'Its initial selection is still pending');
    }
    const form = app.nodes.get('projectForm');
    form.fields = {projectKey: project.projectKey, title: project.title, description: project.description};
    form.reset = () => {form.fields = {};};
    const submit = {type: 'submit', defaultPrevented: false};
    form.dispatchEvent(submit);
    const handledImmediately = submit.defaultPrevented;
    if (early) form.fields.title = 'Edited after submitting';
    await settle();
    const earlyWrites = app.requests.filter(request => request.options.method === 'POST').length;
    if (leavePage) app.context.dispatchEvent({type: 'pagehide'});
    if (early && !selectionPending) hydrate();
    await settle();
    const writesBeforeInitialSelection = app.requests.filter(request => request.options.method === 'POST').length;
    initialList.resolve(Response.json([existing]));
    await settle();
    creation.resolve(Response.json(project, {status: 201}));
    await Promise.all([...started.results, ...submit.results]);
    await settle();
    assert.equal(handledImmediately, true,
        'Visible project form submission must prevent default navigation before metadata hydration');
    if (early) assert.equal(earlyWrites, 0, 'A project write must wait for the resolved source');
    if (early) assert.equal(writesBeforeInitialSelection, 0,
        'The automatic initial project selection must finish before the queued creation is admitted');
    const writes = app.requests.filter(request => request.options.method === 'POST');
    if (leavePage) {
        assert.equal(writes.length, 0, 'A queued creation must retain the page that accepted the submit');
        assert.equal(app.nodes.get('selectedProjectKey').textContent, '');
        return;
    }
    assert.equal(writes.length, 1, 'One form submission creates exactly one project');
    assert.equal(writes[0].url.searchParams.get('workspaceId'), 'resolved-workspace');
    assert.equal(writes[0].url.searchParams.get('repositoryId'), 'resolved-repository');
    assert.equal(new Headers(writes[0].options.headers).get('X-Taxonomy-Workspace-Id'), 'resolved-workspace');
    assert.equal(JSON.parse(writes[0].options.body).projectKey, 'P-CREATED');
    assert.equal(JSON.parse(writes[0].options.body).title, 'Created from the visible form',
        'A queued action retains the values accepted by its submit event');
    assert.equal(app.nodes.get('selectedProjectKey').textContent, 'P-CREATED',
        'The created project must become the visible selected project');
    assert.equal(app.nodes.get('projectWorkspace').classList.contains('d-none'), false);
    assert.equal(app.nodes.get('projectModal').hidden, true);
    assert.equal(app.nodes.get('portfolioError').textContent, '');
}

async function portfolioRequirements(transition) {
    const project = {id:5,projectKey:'P-REQUIREMENTS',title:'Requirement publication',status:'PLANNING'};
    const base = {repositoryId:'resolved-repository',workspaceScopeKey:'resolved-workspace',branch:'draft',
        revision:1,commit:'1'.repeat(40),actor:'owner',writeMode:'PRIVATE_WORKSPACE'};
    let revision=1, requirements=[], mutationReady=deferred(), publicationReady=deferred(), choiceReady=deferred();
    let heldMutation, heldPublication, holdPublication=false;
    let publicationState='CHECKPOINTED';
    const commands=new Map();
    const currentContext = () => ({...base,revision,commit:String(revision).repeat(40)});
    const overview = (context=currentContext(), historical=false) => ({context,source:'WORKSPACE_REVISION',
        projectionState:historical?'HISTORICAL':publicationState==='CHECKPOINTED'?'READY':publicationState,
        publicationState,mayEdit:!historical&&publicationState==='CHECKPOINTED',
        projects:[project],requirements:historical?[]:requirements.map(requirement=>({projectId:5,projectKey:project.projectKey,
            requirementId:requirement.id,requirementKey:requirement.requirementKey,title:requirement.title,
            version:1,versionId:requirement.id*10,realization:{state:'OPEN'}})),dependencies:[]});
    const portfolioData = () => ({project,metrics:{totalRequirements:requirements.length,analyzedRequirements:0,
        requirementsWithoutConfirmedSolution:requirements.length,totalSolutions:0,selectedProducts:0,openConflicts:0},
        requirements,taxonomyNodes:[],solutions:[],conflicts:[],requirementTaxonomyMatrix:null,
        requirementSolutionMatrix:null,solutionProductMatrix:null});
    const app=fixture('portfolio',request=>{
        const route=request.url.pathname;
        if(route==='/taxonomy/api/account/me')return Response.json({error:'Authentication required'},{status:403});
        if(route==='/taxonomy/api/projects')return Response.json([project]);
        if(route==='/taxonomy/api/projects/5/portfolio')return Response.json(portfolioData());
        if(route==='/taxonomy/api/products')return Response.json([]);
        if(route==='/taxonomy/api/architecture/lifecycle/intake-review') {
            const body=JSON.parse(request.options.body);
            return Response.json({status:'UNAVAILABLE',scope:currentContext(),candidates:[],
                inputHash:require('node:crypto').createHash('sha256').update(body.text).digest('hex')});
        }
        if(route==='/taxonomy/api/architecture/editor/requirements') {
            const body=JSON.parse(request.options.body);
            const previous=commands.get(body.metadata.commandId);
            let requirement=previous?.requirement;
            if(previous) {
                assert.ok(['pending','reconciliation'].includes(transition),'A known checkpointed write is never sent twice');
                assert.equal(request.options.body,previous.body,'Recovery retains the exact body, context and command identity');
                publicationState='CHECKPOINTED';
            } else {
                publicationState=transition==='pending'?'CHECKPOINT_PENDING':transition==='reconciliation'?'RECONCILIATION_REQUIRED':'CHECKPOINTED';
                requirement={id:requirements.length+1,projectId:5,...body.requirement,
                    currentVersion:{versionNumber:1,text:body.requirement.text}};
                requirements.push(requirement);commands.set(body.metadata.commandId,{body:request.options.body,requirement});
            }
            revision++;holdPublication=true;
            const result={projectId:5,requirementId:requirement.id,requirement,
                publication:{state:publicationState,context:currentContext()}};
            heldMutation=deferred();mutationReady.resolve(request);
            return heldMutation.promise.then(()=>Response.json(result));
        }
        if(route==='/taxonomy/api/architecture/lifecycle') {
            const historical=request.url.searchParams.has('revision');
            const context=historical?{...base,revision:Number(request.url.searchParams.get('revision'))}:currentContext();
            if(holdPublication&&!historical) {
                holdPublication=false;heldPublication=deferred();publicationReady.resolve(request);
                return heldPublication.promise.then(response=>response||Response.json(overview(context)));
            }
            return Response.json(overview(context,historical));
        }
        throw new Error('Unexpected requirement publication request '+route);
    }, {lifecycle:true});
    app.context.TextEncoder=TextEncoder;
    app.context.TaxonomyArchitectureWorkflow.setScope(base);
    const form=app.nodes.get('requirementForm'),modal=app.nodes.get('requirementModal');
    const controls={requirementKey:app.nodes.get('requirementKey'),title:app.nodes.get('requirementTitle'),
        requirementType:app.nodes.get('requirementType'),text:app.nodes.get('requirementText')};
    Object.entries(controls).forEach(([name,control])=>{control.name=name;control.type='text';});
    form.querySelector=selector=>controls[selector.match(/^\[name="(.+)"\]$/)?.[1]]||null;
    form.querySelectorAll=()=>Object.values(controls);
    let resets=0;
    form.reset=()=>{resets++;form.fields={};Object.values(controls).forEach(control=>control.value='');};
    const query=app.document.querySelector.bind(app.document);
    app.document.querySelector=selector=>selector==='#requirementForm [name="text"]'?controls.text:query(selector);
    const append=form.append;
    form.append=function(...children) {
        for(const section of children)if(section.className?.includes('requirement-intake-review')) {
            const add=section.append;
            section.append=function(...values) {
                add.apply(this,values);
                for(const value of values)if(value.dataset?.intakeChoice==='NEW')choiceReady.resolve(value);
            };
        }
        append.apply(this,children);
    };
    app.load('api/architecture-lifecycle-api.js');app.load('shared/architecture-lifecycle.js');
    app.load('shared/requirement-intake.js');app.load('api/portfolio-api.js');app.load('portfolio/taxonomy-portfolio.js');
    const started={type:'DOMContentLoaded'};app.document.dispatchEvent(started);await Promise.all(started.results);
    assert.ok(app.context.TaxonomyArchitectureLifecycle.state()?.context,'The actual composed lifecycle root has hydrated');
    function descendants(node) {return (node.children||[]).flatMap(child=>[child,...descendants(child)]);}
    async function choose(number) {
        modal.hidden=false;
        form.fields={requirementKey:'REQ-'+number,title:'Requirement '+number,requirementType:'SECURITY',
            text:'The architecture shall retain auditable evidence for requirement '+number+'.'};
        Object.entries(controls).forEach(([name,control])=>{control.value=form.fields[name];});
        const reusable=app.context.TaxonomyRequirementIntake.isCurrent(form)
            ? descendants(form).find(node=>node.dataset?.intakeChoice==='NEW'):null;
        choiceReady=deferred();mutationReady=deferred();publicationReady=deferred();
        const event={type:'submit'};form.dispatchEvent(event);
        assert.equal(event.defaultPrevented,true);
        const choice=reusable||await choiceReady.promise;choice.dispatchEvent({type:'click'});
        return event;
    }
    async function submit(number) {
        const event=await choose(number);
        const outcome=await Promise.race([mutationReady.promise.then(request=>({request})),
            Promise.all(event.results).then(()=>({error:app.nodes.get('portfolioError').textContent}))]);
        assert.ok(outcome.request,'The real handler must admit its explicit choice: '+outcome.error);
        const request=outcome.request;
        assert.equal(new Headers(request.options.headers).get('If-Match'),'"workspace-revision-'+JSON.parse(request.options.body).context.revision+'"');
        assert.equal(request.url.searchParams.get('workspaceId'),base.workspaceScopeKey);
        return {event,request};
    }
    if(['pending','reconciliation','failed-confirmation','superseded-confirmation'].includes(transition)) {
        const first=await submit(1);heldMutation.resolve();await publicationReady.promise;
        if(transition==='failed-confirmation')heldPublication.resolve(Response.json({detail:'Confirmation temporarily unavailable'},{status:503}));
        else if(transition==='superseded-confirmation') {
            revision=9;heldPublication.resolve(Response.json(overview(currentContext())));
        } else heldPublication.resolve();
        await Promise.all(first.event.results);await settle();
        assert.equal(resets,0);assert.equal(modal.hidden,false);
        assert.equal(controls.text.value,form.fields.text);
        const originalBody=first.request.options.body;
        if(['pending','reconciliation'].includes(transition)) {
            assert.ok(form.children.some(node=>node.dataset.lifecyclePublicationRecovery!==undefined),'The accepted pending command exposes its existing recovery controls');
            const retry=await submit(1);
            assert.equal(retry.request.options.body,originalBody);
            heldMutation.resolve();await publicationReady.promise;heldPublication.resolve();
            await Promise.all(retry.event.results);await settle();
            assert.equal(requirements.length,1);
            assert.equal(resets,1);assert.equal(modal.hidden,true);
        } else {
            const retry=await choose(1);await Promise.all(retry.results);await settle();
            assert.equal(app.requests.filter(request=>request.url.pathname.endsWith('/editor/requirements')).length,1,
                'An accepted checkpoint awaiting confirmation retries only the read, never its write');
            assert.equal(first.request.options.body,originalBody);
            assert.equal(resets,transition==='failed-confirmation'?1:0);
            assert.equal(modal.hidden,transition==='failed-confirmation');
        }
        return;
    }
    if(transition==='own-publication') {
        for(let number=1;number<=2;number++) {
            const {event}=await submit(number);heldMutation.resolve();
            const read=await publicationReady.promise;await settle();
            const stayedBusy=!app.nodes.get('portfolioBusy').classList.contains('d-none');
            heldPublication.resolve();await Promise.all(event.results);await settle();
            assert.equal(stayedBusy,true,'The accepted command must retain its busy share through its own source publication');
            assert.equal(read.options.signal.aborted,false,'Selecting the saved requirement must not cancel its own lifecycle refresh');
            assert.equal(modal.hidden,true,'An accepted own save must close its still-current modal');
            assert.equal(resets,number);
            assert.equal(app.context.TaxonomyArchitectureLifecycle.state()?.context.revision,number+1);
            assert.ok(app.context.TaxonomyArchitectureLifecycle.state().requirements.some(value=>value.requirementKey==='REQ-'+number));
            assert.ok(app.document.querySelector('#requirementsTable tbody').children.some(row=>row.innerHTML.includes('REQ-'+number)));
        }
        assert.equal(app.requests.filter(request=>request.url.pathname.endsWith('/editor/requirements')).length,2);
        assert.equal(app.nodes.get('portfolioError').textContent,'');
        return;
    }
    const {event,request}=await submit(1);
    if(transition==='branch') {
        const other={...base,branch:'other'};app.context.TaxonomyArchitectureWorkflow.replaceScope(other);
        app.context.TaxonomyArchitectureLifecycle.beginSelection(other,null);
    } else if(transition==='history') {
        const url=new URL(app.context.location);url.searchParams.set('revision','1');app.context.history.pushState(null,'',url);
        await app.context.TaxonomyArchitectureLifecycle.refresh(base,1);
    } else if(transition==='pagehide')app.context.dispatchEvent({type:'pagehide'});
    else if(transition==='authentication')await assert.rejects(app.context.TaxonomyApiClient.getJson('/api/account/me'),error=>error.status===403);
    else if(transition==='foreign-publication') {
        holdPublication=false;revision=9;await app.context.TaxonomyArchitectureLifecycle.refresh(currentContext(),null);
    }
    const selectedUrl=app.context.location.href;
    const readsBefore=app.requests.filter(value=>value.url.pathname.endsWith('/portfolio')).length;
    heldMutation.resolve();await Promise.all(event.results);await settle();
    assert.equal(modal.hidden,false,'An old command receipt must not close a different source or departed form');
    assert.equal(resets,0);
    assert.equal(controls.text.value,form.fields.text,'The unsaved form remains owned by the current editor');
    assert.equal(app.context.location.href,selectedUrl);
    assert.equal(app.requests.filter(value=>value.url.pathname.endsWith('/portfolio')).length,readsBefore);
    assert.equal(JSON.parse(request.options.body).context.revision,1,'The admitted server context is never replaced on receipt');
}

async function snapshot(mode) {
    const changeSource = mode === 'source-change';
    const leavePage = mode === 'page-exit' || mode === 'loader-page-exit';
    const loaderFailure = mode === 'loader-error', standalone = mode === 'standalone';
    const delayedLoader = ['delayed-loader', 'loader-error', 'loader-page-exit'].includes(mode);
    const delayed = mode === 'startup' || delayedLoader || leavePage;
    const initialization = deferred(), body = deferred(), contextBody = deferred();
    const contextRef = {contextId: 'context-A', branch: 'draft', commitId: 'commit-A', mode: 'EDITABLE'};
    const projection = {projectId: 1, projectKey: 'P-1', requirementId: 2, requirementKey: 'R-2',
        requirementTitle: 'Saved requirement', requirementText: 'Retained business evidence',
        snapshotId: 'snapshot-A', workspaceId: 'workspace-A', branchName: 'draft', snapshotStatus: 'SUCCESS',
        provider: 'DEMO', exportProvenance: {repositoryId: 'repository-A', requirementVersionId: 3},
        scene: {title: 'Saved architecture', width: 800, height: 400,
            nodes: [{id: 'CP-1', label: 'Capability', type: 'Capabilities', layer: 0, x: 30, y: 70,
                width: 200, height: 80, relevance: 0.85, anchor: true}], edges: []},
        elements: {}, relations: {}, warnings: []};
    const app = fixture('snapshot', request => {
        if (request.url.pathname === '/taxonomy/api/context/current') return delayed
            ? contextBody.promise.then(value => Response.json(value)) : Response.json(contextRef);
        if (request.url.pathname === '/taxonomy/api/projects/1/architecture-workbench/snapshot-A') return body.promise;
        throw new Error('Unexpected snapshot request ' + request.url.pathname);
    });
    const runtime = {workspaceId: undefined, analysisGeneration: 0};
    const session = {whenInitialized: () => initialization.promise,
        state: () => ({workspaceId: runtime.workspaceId, ready: false})};
    let loader;
    if (delayedLoader) {
        app.document.head = element('head');
        const query = app.document.querySelector;
        app.document.querySelector = selector => selector === 'script[data-taxonomy-analysis-session]'
            ? app.document.head.children.find(script => script.dataset.taxonomyAnalysisSession) || null : query(selector);
        app.load('core/taxonomy-state.js');
        loader = app.document.querySelector('script[data-taxonomy-analysis-session]');
        assert.ok(loader, 'The real state bootstrap injected the asynchronous session loader');
        assert.equal(app.context.TaxonomyAnalysisSession, undefined);
        assert.equal(app.context.TaxonomyAnalysisSessionReady, undefined);
    } else if (!standalone) {
        app.context.__TaxonomyAnalysisSessionContext = {runtime};
        app.context.TaxonomyAnalysisSession = session;
        app.context.TaxonomyAnalysisSessionReady = initialization.promise;
    }
    app.context.TaxonomyArchitectureLifecycle = {ready: () => Promise.resolve(), suspend() {}};
    app.context.TaxonomyArchitectureEditor = {mount() {throw new Error('Snapshot must not load the working editor');}};
    app.load('api/workspace-api.js');
    app.load('versioning/taxonomy-context-bar.js');
    app.load('api/architecture-workbench-api.js');
    app.load('shared/architecture-diagram.js');
    const realDiagram = app.context.TaxonomyArchitectureDiagram;
    app.context.TaxonomyArchitectureDiagram = Object.assign({}, realDiagram, {
        create() {
            let state = {};
            return {render(_scene, value) {state = value;}, getState: () => state,
                fit() {}, setActive() {}, destroy() {}, captureSession: () => ({state})};
        }
    });
    app.nodes.get('architectureSnapshotSource').querySelector = selector => app.nodes.get(selector.slice(1)) || null;
    app.load('architecture-workbench.js');
    function hydrate() {
        app.context.__TaxonomyAnalysisSessionContext ||= {runtime};
        app.context.TaxonomyAnalysisSession ||= session;
        runtime.workspaceId = 'workspace-A';
        initialization.resolve(mode !== 'draft-unavailable');
    }
    if (!delayed) {
        if (!standalone) hydrate();
        await app.context.TaxonomyContextBar.fetchAndRender('contextBar');
    }
    app.load('shared/architecture-workspace.js');
    const ready = app.context.TaxonomyArchitectureWorkspace.ready();
    await settle();
    const prematureReads = app.requests.filter(request => request.url.pathname.includes('/architecture-workbench/')).length;
    const readsBeforeLoader = app.requests.length;
    if (leavePage) app.context.dispatchEvent({type: 'pagehide'});
    if (delayedLoader) {
        // The external loader publishes Ready before its load event. Its child
        // scripts and draft initialization remain pending after that event.
        if (loaderFailure) loader.dispatchEvent({type: 'error'});
        else {
            app.context.TaxonomyAnalysisSessionReady = initialization.promise;
            loader.dispatchEvent({type: 'load'});
        }
        await settle();
    }
    const readsBeforeDraft = app.requests.length;
    if (delayed && !loaderFailure) {hydrate(); await settle();}
    const readsBeforeContext = app.requests.filter(request => request.url.pathname.includes('/architecture-workbench/')).length;
    contextBody.resolve(contextRef);
    await settle();
    if (changeSource) {
        app.context.TaxonomyArchitectureWorkflow.replaceScope({repositoryId: 'repository-A',
            workspaceId: 'workspace-A', workspaceScopeKey: 'workspace-A', branch: 'other-branch'});
        app.document.dispatchEvent(new app.context.CustomEvent('taxonomy:entity-context-changed'));
    }
    body.resolve(Response.json(projection));
    const sourceReady = await ready;
    const reads = app.requests.filter(request => request.url.pathname.includes('/architecture-workbench/'));
    if (delayedLoader) {
        assert.equal(readsBeforeLoader, 0, 'Snapshot metadata must wait for the injected session loader');
        assert.equal(readsBeforeDraft, 0, 'The load event must adopt the loader-owned draft initialization promise');
    }
    if (loaderFailure) {
        assert.equal(sourceReady, false);
        assert.equal(app.requests.length, 0, 'A failed required session loader cannot admit source reads');
        assert.equal(app.context.TaxonomyArchitectureWorkspace.getData(), null);
        assert.equal(app.nodes.get('architectureWorkspaceError').textContent, 'sourceLens.unavailable');
        assert.equal(app.nodes.get('architectureWorkspaceError').hidden, false);
        return;
    }
    if (leavePage) {
        assert.equal(sourceReady, false);
        assert.equal(reads.length, 0, 'Snapshot preparation cannot outlive its page');
        assert.equal(app.context.TaxonomyArchitectureWorkspace.getData(), null);
        return;
    }
    assert.equal(reads.length, 1, 'Opening one saved source reads its exact snapshot once; workspace: '
        + app.nodes.get('architectureWorkspaceError').textContent + '; adapter: '
        + app.nodes.get('architectureAlert').textContent);
    assert.equal(reads[0].url.searchParams.get('workspaceId'), 'workspace-A');
    assert.equal(reads[0].url.searchParams.get('repositoryId'), 'repository-A');
    assert.equal(reads[0].url.searchParams.get('branch'), 'draft');
    if (changeSource) {
        assert.equal(app.context.TaxonomyArchitectureWorkspace.getData(), null,
            'An actual subsequent branch change must discard the obsolete snapshot');
        assert.equal(reads[0].options.signal.aborted, true);
        assert.equal(app.nodes.get('architectureStatus').textContent.startsWith('Loaded '), false);
    } else {
        assert.equal(app.nodes.get('architectureStatus').textContent, 'Loaded 1 elements, 0 relations',
            'Initial metadata hydration must not strand the saved snapshot in a load failure');
        if (delayed) {
            assert.equal(prematureReads, 0, 'The protected snapshot read must follow draft hydration');
            assert.equal(readsBeforeContext, 0, 'The protected snapshot read must follow fresh context metadata');
        }
        assert.equal(app.context.TaxonomyContextBar.getCurrentContext().contextId, 'context-A');
        assert.equal(app.context.TaxonomyArchitectureWorkspace.getData().requirementText, 'Retained business evidence');
        assert.equal(app.nodes.get('architectureAlert').classList.contains('d-none'), true);
        assert.equal(app.nodes.get('downloadArchitectureSvg').disabled, false);
    }
}

(async () => {
    if (scenario === 'portfolio-early-submit') await portfolio(true);
    else if (scenario === 'portfolio-ready-submit') await portfolio(false);
    else if (scenario === 'portfolio-selection-pending-submit') await portfolio(true, false, true);
    else if (scenario === 'portfolio-startup-busy') await portfolio(true, false, false, true);
    else if (scenario === 'portfolio-page-exit') await portfolio(true, true);
    else if (scenario.startsWith('portfolio-requirement-')) await portfolioRequirements(scenario.slice('portfolio-requirement-'.length));
    else if (scenario === 'snapshot-startup') await snapshot('startup');
    else if (scenario === 'snapshot-delayed-loader') await snapshot('delayed-loader');
    else if (scenario === 'snapshot-loader-error') await snapshot('loader-error');
    else if (scenario === 'snapshot-loader-page-exit') await snapshot('loader-page-exit');
    else if (scenario === 'snapshot-draft-unavailable') await snapshot('draft-unavailable');
    else if (scenario === 'snapshot-standalone') await snapshot('standalone');
    else if (scenario === 'snapshot-source-change') await snapshot('source-change');
    else if (scenario === 'snapshot-ready') await snapshot('ready');
    else if (scenario === 'snapshot-page-exit') await snapshot('page-exit');
    else throw new Error('Unknown JUnit scenario ' + scenario);
})().then(() => console.log('SOURCE_BOOTSTRAP_COMPLETE '+scenario))
    .catch(error => {console.error(error); process.exitCode = 1;});
