/* Shared saved-demand inventory comparison. All evidence is text; retained reads never refresh sources. */
window.TaxonomyInventoryComparison = (function () {
    'use strict';
    const de = document.documentElement.lang.toLowerCase().startsWith('de');
    const words = de ? {
        title:'Mit Bestand und Planung abgleichen', nodes:'Aufgezeichnete Taxonomieknoten auswählen', organization:'Nutzende Organisation', environment:'Zielumgebung (optional)',
        mode:'Zeitfrage', start:'Datum / Anfang', end:'Ende (ausschließlich)', sources:'Freigegebene Quellprofile (optional, durch Komma getrennt)',
        exact:'Stichtag', window:'Startfenster', period:'Durchgehender Nutzungszeitraum', compare:'Abgleich starten', retained:'Gespeicherte Abgleiche', reopen:'Gespeicherten Abgleich öffnen',
        history:'Gespeicherte Prüfungen', review:'Begründete Prüfung speichern', rationale:'Begründung der Prüfung / Zuordnung', solution:'Bestehende Projektlösung (optional)', noSolution:'Nur Nachweis prüfen',
        coverage:'Geprüfte Abdeckung (0–100, getrennt von der Analyse)', select:'Produkt ausdrücklich auswählen', mapped:'Externes Objekt einem bestehenden Produkt zuordnen', product:'Bestehendes Katalogprodukt', map:'Begründete Identitätszuordnung speichern',
        evidence:'Expliziten Produktnachweis ergänzen', criterion:'Kriterium', positive:'Positive Aussage', negative:'Expliziter Ausschluss', kind:'Aussageart', citation:'Quellenangabe',
        saveAssertion:'Unveränderlichen Nachweis speichern', saved:'Gespeichert. Für neue Nachweise einen neuen Abgleich starten.', unknown:'Fehlender Nachweis bleibt ungeklärt. Nicht geprüfte Beziehungen bleiben gesondert sichtbar.',
        frozen:'Eingefrorener Bedarf', source:'Quellen / Abrufalter', export:'Gespeicherten Nachweis als JSON herunterladen', candidate:'Kandidat', finding:'Kriterienbefund', next:'Nächste Klärung',
        selectNodes:'Mindestens einen aufgezeichneten Knoten auswählen', choose:'Bitte auswählen', error:'Die Aktion ist fehlgeschlagen', limitations:'Aussagegrenzen', assertionNode:'Knoten (nur Fähigkeitsnachweis)',
        hint:'Aktivstatus, Supportende und Prüfdatum beweisen keine organisatorische Verfügbarkeit. Produktnamen sind keine Identität.', all:'Nicht ausgewählte Bereiche werden nicht geprüft.'
    } : {
        title:'Compare inventory and planning', nodes:'Select recorded taxonomy nodes', organization:'Using organization', environment:'Target environment (optional)',
        mode:'Time question', start:'Date / start', end:'End (exclusive)', sources:'Approved source profiles (optional, comma separated)',
        exact:'Exact date', window:'Start window', period:'Continuous use period', compare:'Start comparison', retained:'Retained comparisons', reopen:'Open retained comparison',
        history:'Retained reviews', review:'Save reasoned review', rationale:'Review / mapping rationale', solution:'Existing project solution (optional)', noSolution:'Review evidence only',
        coverage:'Reviewed coverage (0–100, separate from analysis)', select:'Explicitly select this product', mapped:'Map external object to an existing product', product:'Existing catalogue product', map:'Save reasoned identity mapping',
        evidence:'Add explicit product evidence', criterion:'Criterion', positive:'Positive assertion', negative:'Explicit exclusion', kind:'Assertion kind', citation:'Source citation',
        saveAssertion:'Save immutable assertion', saved:'Saved. Start a new comparison to use new evidence.', unknown:'Missing evidence remains unknown. Unexamined relationships remain separate.',
        frozen:'Frozen demand', source:'Sources / observation age', export:'Download retained evidence as JSON', candidate:'Candidate', finding:'Criterion finding', next:'Next clarification',
        selectNodes:'Select at least one recorded node', choose:'Choose', error:'The action failed', limitations:'Limitations', assertionNode:'Node (capability assertions only)',
        hint:'Active status, support end and verification date do not establish organizational availability. Names are not identity.', all:'Areas outside this selection are unexamined.'
    };
    const label = key => words[key] || key;
    const url = path => window.TaxonomyI18n?.resolveUrl?.(path) || path;
    const read = path => window.TaxonomyApiClient.getJson(url(path));
    const write = (path, body) => window.TaxonomyApiClient.sendJson(url(path), body, 'POST', {timeoutMillis:125000});
    function node(tag, text, className) { const n=document.createElement(tag); if(text!==undefined)n.textContent=String(text);if(className)n.className=className;return n; }
    function button(text, action) {const n=node('button',text,'btn btn-outline-primary btn-sm');n.type='button';n.addEventListener('click',action);return n;}
    function input(type='text') {const n=node('input');n.type=type;n.className=type==='checkbox'?'form-check-input ms-2':'form-control form-control-sm';return n;}
    function field(parent,title,control) {const l=node('label',title,'d-block my-2');l.append(control);parent.append(l);return control;}
    function select(options) {const s=node('select');s.className='form-select form-select-sm';for(const [value,title]of options){const o=node('option',title);o.value=value;s.append(o);}s.value=options[0]?.[0]||'';return s;}
    function boundary(value) {return value?{value,precision:value.length===4?'YEAR':'DATE'}:null;}
    function mount(target, options) {
        target.replaceChildren();const panel=node('section',undefined,'border rounded p-3 mt-3');panel.dataset.inventoryComparison='';target.append(panel);
        panel.append(node('h3',label('title'),'h5'),node('p',label('hint'),'small'),node('p',label('all'),'small'));
        const status=node('p');status.setAttribute('role','status');status.setAttribute('aria-live','polite');panel.append(status);
        const setup=node('details');setup.open=true;setup.append(node('summary',label('nodes')));panel.append(setup);
        const checks=[];
        const recorded=new Map((options.snapshot.elementMappings||[]).map(m=>[m.nodeCode,m]));
        const scores=options.snapshot.analysis?.rawScores||options.snapshot.analysis?.scores||{};
        function visit(nodes) {for(const n of nodes||[]){if(Object.prototype.hasOwnProperty.call(scores,n.code)&&scores[n.code]!==null&&!recorded.has(n.code))recorded.set(n.code,{nodeCode:n.code,nodeTitle:de?(n.nameDe||n.nameEn):(n.nameEn||n.nameDe)});visit(n.children);}}
        visit(options.snapshot.analysis?.tree);
        for(const mapping of recorded.values()) {const check=input('checkbox');check.value=mapping.nodeCode;field(setup,mapping.nodeCode+' · '+(mapping.nodeTitle||''),check);checks.push(check);}
        const organization=field(setup,label('organization'),input());organization.required=true;organization.dataset.inventoryOrganization="";
        const environment=field(setup,label('environment'),input());
        const mode=field(setup,label('mode'),select([['EXACT_DATE',label('exact')],['START_WINDOW',label('window')],['CONTINUOUS_PERIOD',label('period')]]));
        const start=field(setup,label('start'),input('date'));start.required=true;start.dataset.inventoryStart='';
        const end=field(setup,label('end'),input('date'));end.disabled=true;mode.addEventListener('change',()=>{end.disabled=mode.value==='EXACT_DATE';end.required=!end.disabled;});
        const profiles=field(setup,label('sources'),input());
        const output=node('div');output.dataset.inventoryResult='';
        const previous=field(panel,label('retained'),select([['',label('choose')]]));
        async function run(action) {status.textContent='';try {await action();}catch(error){status.textContent=error.detail||error.message||label('error');}}
        function request(nodes,question,sourceProfiles) {if(!nodes.length)throw new Error(label('selectNodes'));return {selectedNodes:nodes,question,sourceProfiles};}
        async function refreshList() {const values=await read(`/api/projects/${options.projectId}/snapshots/${options.snapshot.summary.id}/inventory-comparisons`);previous.replaceChildren();for(const value of [{id:'',createdAt:label('choose')},...values]){const o=node('option',value.createdAt+' '+(value.demand?.question?.organization||''));o.value=value.id;previous.append(o);}}
        async function compare() {
            const question={mode:mode.value,start:start.value,endExclusive:mode.value==='EXACT_DATE'?null:end.value,organization:organization.value.trim(),environment:environment.value.trim()||null};
            if(!question.start||!question.organization||(question.mode!=='EXACT_DATE'&&!question.endExclusive))throw new Error(de?'Organisation und Zeitfrage vervollständigen':'Complete the organization and time question');
            const comparison=await write(`/api/projects/${options.projectId}/snapshots/${options.snapshot.summary.id}/inventory-comparisons`,request(checks.filter(c=>c.checked).map(c=>c.value),question,profiles.value.split(',').map(v=>v.trim()).filter(Boolean)));
            render(comparison);await refreshList();return comparison;
        }
        setup.append(button(label('compare'),()=>run(compare)));
        panel.append(button(label('reopen'),()=>run(()=>open(previous.value))),output);
        async function open(id) {if(!id)return;render(await read('/api/inventory-comparisons/'+encodeURIComponent(id)));}
        function render(comparison) {
            output.replaceChildren();const demand=comparison.demand;
            output.append(node('h4',label('frozen'),'h6'),node('p',`${demand.snapshotId} · v${demand.requirementVersionId} · ${demand.taxonomyFingerprint}`,'small text-break'),node('p',JSON.stringify(demand.question)),node('p',demand.selectedNodes.join(', ')),node('p',label('unknown'),'small'));
            if(demand.requirementText)output.append(node('blockquote',demand.requirementText));
            const source=node('details');source.append(node('summary',label('source')),node('pre',JSON.stringify(comparison.sources,null,2),'text-break'));output.append(source);
            output.append(button(label('export'),()=>run(async()=>{const retained=await read('/api/inventory-comparisons/'+encodeURIComponent(comparison.id));const a=node('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(retained,null,2)],{type:'application/json'}));a.download='inventory-comparison-'+comparison.id+'.json';a.click();URL.revokeObjectURL(a.href);})));
            const table=node('table',undefined,'table table-sm mt-3');const head=node('tr');for(const text of [label('candidate'),label('finding'),label('next')])head.append(node('th',text));const thead=node('thead');thead.append(head);table.append(thead);const body=node('tbody');table.append(body);const tableScroll=node('div',undefined,'table-responsive');tableScroll.append(table);output.append(tableScroll);
            for(const candidate of comparison.candidates) {
                const identity=node('td');identity.className='align-top';identity.append(node('strong',candidate.name),node('p',candidate.kind+' · '+candidate.id,'small text-break'));
                const meta=node('details');meta.append(node('summary',label('evidence')),node('pre',JSON.stringify({metadata:candidate.metadata,assertions:candidate.assertions},null,2),'small text-break'));identity.append(meta);
                const findings=comparison.findings.filter(f=>f.candidateId===candidate.id);identity.rowSpan=Math.max(1,findings.length);
                for(const [index,finding] of findings.entries()) {
                    const row=node('tr');if(index===0)row.append(identity);
                    const values=node('td'),next=node('td');values.className='align-top';next.className='align-top';
                    values.append(node('strong',finding.outcome),node('p',finding.criterion+(finding.nodeCode?' · '+finding.nodeCode:''),'small mb-1'));
                    const limits=node('details');limits.append(node('summary',label('limitations')),node('p',(finding.limitations||[]).map(v=>v.toLowerCase().replaceAll('_',' ')).join('; '),'small'),node('p',(finding.evidenceIds||[]).join(', '),'small text-break'));values.append(limits);
                    next.append(node('p',finding.nextClarification,'small'));row.append(values,next);body.append(row);
                }
                const details=node('details');details.append(node('summary',label('review')));identity.append(details);
                const rationale=field(details,label('rationale'),node('textarea'));rationale.className='form-control';rationale.required=true;
                const solution=field(details,label('solution'),select([['',label('noSolution')],...(options.solutions||[]).map(s=>[String(s.id),s.title||s.solution?.title||String(s.id)])]));
                const coverage=field(details,label('coverage'),input('number'));coverage.min='0';coverage.max='100';
                const choose=field(details,label('select'),input('checkbox'));choose.dataset.inventorySelect='';choose.disabled=!candidate.productId||findings.some(f=>f.hardExclusion||f.outcome==='CONTRADICTED'||f.outcome==='CONFLICT');
                const reviewId=crypto.randomUUID();details.append(button(label('review'),()=>run(async()=>{if(!rationale.value.trim())throw new Error(label('rationale'));await write('/api/inventory-comparisons/'+comparison.id+'/reviews',{id:reviewId,comparisonFingerprint:comparison.fingerprint,candidateId:candidate.id,rationale:rationale.value,projectSolutionId:solution.value?Number(solution.value):null,selectProduct:choose.checked,coveragePercent:coverage.value===''?null:Number(coverage.value)});status.textContent=label('saved');})));
                if(candidate.productId) assertionForm(details,candidate,demand);
                if(candidate.metadata?.sourceProfile) {
                    const mapping=node('details');mapping.append(node('summary',label('mapped')));details.append(mapping);const product=field(mapping,label('product'),select([['',label('choose')]]));
                    run(async()=>{for(const p of await read('/api/products')){const o=node('option',p.productName+' · '+p.productKey);o.value=String(p.id);product.append(o);}});
                    const mappingId=crypto.randomUUID();mapping.append(button(label('map'),()=>run(async()=>{if(!product.value||!rationale.value.trim())throw new Error(label('rationale'));await write('/api/inventory-comparisons/'+comparison.id+'/candidates/'+encodeURIComponent(candidate.id)+'/mappings',{id:mappingId,productId:Number(product.value),comparisonFingerprint:comparison.fingerprint,rationale:rationale.value});status.textContent=label('saved');})));
                }
            }
            const history=node('details');history.append(node('summary',label('history')));output.append(history);
            run(async()=>{for(const item of await read('/api/inventory-comparisons/'+comparison.id+'/reviews'))history.append(node('p',item.actor+' · '+item.reviewedAt+' · '+item.review.rationale));});
            output.focus();
        }
        function assertionForm(parent,candidate,demand) {
            const form=node('details');form.append(node('summary',label('evidence')));parent.append(form);
            const criterion=field(form,label('criterion'),select(['AVAILABILITY','CAPABILITY','SUPPORT','ORGANIZATION','ENVIRONMENT'].map(v=>[v,v])));
            const polarity=field(form,label('evidence'),select([['true',label('positive')],['false',label('negative')]]));
            const kind=field(form,label('kind'),select(['DECLARED','PLANNED','OBSERVED'].map(v=>[v,v])));
            const from=field(form,label('start')+' (YYYY / YYYY-MM-DD)',input());const until=field(form,label('end')+' (YYYY / YYYY-MM-DD)',input());
            const org=field(form,label('organization'),input());org.value=demand.question.organization;const env=field(form,label('environment'),input());env.value=demand.question.environment||'';
            const nodeCode=field(form,label('assertionNode'),select([['',label('choose')],...demand.selectedNodes.map(v=>[v,v])]));const citation=field(form,label('citation'),node('textarea'));
            const assertionId=crypto.randomUUID();form.append(button(label('saveAssertion'),()=>run(async()=>{if(!citation.value?.trim())throw new Error(label('citation'));await write('/api/products/'+candidate.productId+'/inventory-assertions',{id:assertionId,candidateId:'product:'+candidate.productId,criterion:criterion.value,nodeCode:criterion.value==='CAPABILITY'?nodeCode.value:null,positive:polarity.value==='true',start:boundary(from.value),endExclusive:boundary(until.value),organization:org.value,environment:env.value||null,kind:kind.value,citation:citation.value,recordedAt:new Date().toISOString()});status.textContent=label('saved');})));
        }
        run(refreshList);return {open,compare,request,element:panel};
    }
    return {mount};
})();
