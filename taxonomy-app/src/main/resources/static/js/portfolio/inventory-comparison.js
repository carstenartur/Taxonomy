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
        hint:'Aktivstatus, Supportende und Prüfdatum beweisen keine organisatorische Verfügbarkeit. Produktnamen sind keine Identität.', all:'Nicht ausgewählte Bereiche werden nicht geprüft.',
        optional:'Optionale Einstellungen und Lösungsvarianten', selectRetained:'Zuerst einen gespeicherten Abgleich auswählen.'
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
        hint:'Active status, support end and verification date do not establish organizational availability. Names are not identity.', all:'Areas outside this selection are unexamined.',
        optional:'Optional settings and solution variants', selectRetained:'Select a retained comparison first.'
    };
    Object.assign(words,de ? {
        addRelationship:'Gerichtete Anforderung ergänzen', relationSource:'Ausgangskandidat (exakte ID)', relationTarget:'Zielkandidat (exakte ID)', relationKind:'Beziehungsart', qualifier:'Vertrag / Version / Bedingung', required:'Für die Variante erforderlich',
        addVariant:'Bestehende Lösungsvariante ergänzen', variant:'Bestehende Lösungsvariante', action:'Fachliche Maßnahme', candidateEvidence:'Zusätzliche Kandidatennachweise (IDs, durch Komma getrennt)',
        membership:'Zusätzliche Nachweise ändern keine Produktmitgliedschaft. Befunde und Ausschlüsse der vorhandenen Lösung bleiben erhalten.',
        prerequisite:'Voraussetzung ergänzen', risk:'Risiko ergänzen', cost:'Kostenannahme ergänzen', note:'Begründete Aussage', assessmentStatus:'Nachweisstatus', amount:'Exakter Dezimalbetrag', currency:'Währung', basis:'Kostenbasis', remove:'Entfernen',
        variantFindings:'Gespeicherte Lösungsvarianten', variantDecision:'Variantenentscheidung', saveVariant:'Variantenentscheidung speichern', acknowledge:'Verbleibende ungeklärte Nachweise bewusst anerkennen', exclusions:'Harte Ausschlüsse', questions:'Offene Klärungen', directed:'Gerichtete Beziehungsbefunde'
    } : {
        addRelationship:'Add directed requirement', relationSource:'Source candidate (exact ID)', relationTarget:'Target candidate (exact ID)', relationKind:'Relationship kind', qualifier:'Contract / version / constraint', required:'Required for the variant',
        addVariant:'Add existing solution variant', variant:'Existing solution variant', action:'Domain action', candidateEvidence:'Additional candidate evidence (comma-separated IDs)',
        membership:'Additional evidence does not change product membership. Findings and exclusions of the existing solution remain binding.',
        prerequisite:'Add prerequisite', risk:'Add risk', cost:'Add cost assumption', note:'Reasoned statement', assessmentStatus:'Evidence status', amount:'Exact decimal amount', currency:'Currency', basis:'Cost basis', remove:'Remove',
        variantFindings:'Retained solution variants', variantDecision:'Variant decision', saveVariant:'Save variant decision', acknowledge:'Explicitly acknowledge remaining unknown evidence', exclusions:'Hard exclusions', questions:'Open questions', directed:'Directed relationship findings'
    });
    const label = key => words[key] || key;
    const displayLabels = {
        SUPPORTED:['Belegt','Supported'], CONTRADICTED:['Widerlegt','Contradicted'], UNKNOWN:['Ungeklärt','Unknown'],
        CONFLICT:['Widerspruch','Conflict'], UNEXAMINED:['Nicht geprüft','Unexamined'],
        CAPABILITY:['Fähigkeit','Capability'], AVAILABILITY:['Verfügbarkeit','Availability'], SUPPORT:['Unterstützung','Support'],
        ORGANIZATION:['Organisation','Organization'], ENVIRONMENT:['Umgebung','Environment'], RELATIONSHIPS:['Beziehungen','Relationships'],
        EXACT_DATE:['Stichtag','Exact date'], START_WINDOW:['Startfenster','Start window'], CONTINUOUS_PERIOD:['Durchgehender Nutzungszeitraum','Continuous use period'],
        INTERFACE:['Schnittstelle','Interface'], DEPENDENCY:['Abhängigkeit','Dependency'], ALTERNATIVE:['Alternative','Alternative'], HARD_EXCLUSION:['Harter Ausschluss','Hard exclusion'],
        SATISFIED_AS_IS:['Unverändert erfüllt','Satisfied as is'], REUSE:['Wiederverwenden','Reuse'], CHANGE:['Ändern','Change'], CREATE:['Neu erstellen','Create'], PROCURE:['Beschaffen','Procure'],
        ORGANIZATIONAL:['Organisatorische Maßnahme','Organizational action'], RETIRE_OR_REPLACE:['Stilllegen oder ersetzen','Retire or replace'], UNDECIDED:['Unentschieden','Undecided'],
        OPEN:['Offen','Open'], SATISFIED:['Erfüllt','Satisfied'], BLOCKING:['Blockierend','Blocking'], EVALUATED:['Bewertet','Evaluated'], SELECTED:['Ausgewählt','Selected'], REJECTED:['Abgelehnt','Rejected'],
        DECLARED:['Erklärt','Declared'], PLANNED:['Geplant','Planned'], OBSERVED:['Beobachtet','Observed'], TECHNOLOGY_PRODUCT:['Technologieprodukt','Technology product'],
        APPLICATION:['Anwendung','Application'], SERVICE:['Dienst','Service'], DEPLOYMENT:['Bereitstellung','Deployment'], OTHER:['Sonstiges','Other'],
        NOT_EXAMINED:['Nicht geprüft','Not examined'], DIRECTED_REQUIREMENT_REQUIRED:['Gerichtete Anforderung erforderlich','Directed requirement required'],
        PLANNED_NOT_ACTUAL:['Geplant, noch nicht tatsächlich beobachtet','Planned, not yet observed'],
        SEMANTIC_MAPPING_NOT_AVAILABILITY:['Semantische Zuordnung belegt keine Verfügbarkeit','Semantic mapping does not establish availability'],
        MISSING_BOUNDARY:['Zeitgrenze fehlt','Missing time boundary'], PARTIAL_OR_IMPRECISE_COVERAGE:['Teilweise oder ungenaue Abdeckung','Partial or imprecise coverage'],
        ALL_STARTS_EXPLICITLY_EXCLUDED:['Alle Startzeitpunkte ausdrücklich ausgeschlossen','All start dates explicitly excluded'],
        OVERLAPPING_POSITIVE_AND_EXPLICIT_EXCLUSION:['Positive Aussage und ausdrücklicher Ausschluss überlappen','Positive assertion overlaps an explicit exclusion'],
        NO_ADMISSIBLE_ASSERTION:['Kein zulässiger Nachweis','No admissible assertion'],
        MISSING_CONTRACT_QUALIFICATION:['Vertragsbedingungen fehlen','Missing contract qualification'],
        PARTIAL_EXPIRED_OR_IMPRECISE_EDGE:['Beziehung teilweise, abgelaufen oder ungenau belegt','Partial, expired or imprecise relationship evidence'],
        NO_DATED_SOLUTION_AVAILABILITY:['Kein datierter Verfügbarkeitsnachweis der Lösung','No dated solution availability evidence']
    };
    function display(value) { return Object.hasOwn(displayLabels,value)?displayLabels[value][de?0:1]:String(value??''); }
    function clarification(value) {
        if(!de)return value;
        const known={
            'Obtain explicit dated evidence for this criterion, organization and environment':'Explizite datierte Nachweise für dieses Kriterium, diese Organisation und diese Umgebung einholen.',
            'Review the cited assertion and its planned/actual status':'Zitierte Aussage und ihren geplanten oder tatsächlichen Stand prüfen.',
            'Examine the required relationship evidence':'Erforderliche Beziehungsnachweise prüfen.',
            'Specify exact directed relationship requirements':'Exakte gerichtete Beziehungsanforderungen festlegen.'
        };
        return Object.hasOwn(known,value)?known[value]:value;
    }
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
        const nodeList=node('div',undefined,'inventory-node-options');nodeList.dataset.inventoryNodes='';setup.append(nodeList);
        const checks=[];
        const recorded=new Map((options.snapshot.elementMappings||[]).map(m=>[m.nodeCode,m]));
        const scores=options.snapshot.analysis?.rawScores||options.snapshot.analysis?.scores||{};
        function visit(nodes) {for(const n of nodes||[]){if(Object.prototype.hasOwnProperty.call(scores,n.code)&&scores[n.code]!==null&&!recorded.has(n.code))recorded.set(n.code,{nodeCode:n.code,nodeTitle:de?(n.nameDe||n.nameEn):(n.nameEn||n.nameDe)});visit(n.children);}}
        visit(options.snapshot.analysis?.tree);
        for(const mapping of recorded.values()) {const check=input('checkbox');check.value=mapping.nodeCode;field(nodeList,mapping.nodeCode+' · '+(mapping.nodeTitle||''),check);checks.push(check);}
        const organization=field(setup,label('organization'),input());organization.required=true;organization.dataset.inventoryOrganization="";
        const optional=node('details',undefined,'inventory-optional-settings');optional.dataset.inventoryOptions='';optional.append(node('summary',label('optional')));
        const environment=field(optional,label('environment'),input());
        const mode=field(setup,label('mode'),select([['EXACT_DATE',label('exact')],['START_WINDOW',label('window')],['CONTINUOUS_PERIOD',label('period')]]));
        const start=field(setup,label('start'),input('date'));start.required=true;start.dataset.inventoryStart='';
        const end=field(setup,label('end'),input('date'));end.disabled=true;mode.addEventListener('change',()=>{end.disabled=mode.value==='EXACT_DATE';end.required=!end.disabled;});
        setup.append(optional);
        const profiles=field(optional,label('sources'),input());
        const relationshipRows=[],variantRows=[];
        const editor=node('div');optional.append(editor);
        function removeRow(rows,row,element) {element.append(button(label('remove'),()=>{rows.splice(rows.indexOf(row),1);element.remove();}));}
        editor.append(button(label('addRelationship'),()=>{
            const box=node('fieldset',undefined,'border p-2 my-2');box.append(node('legend',label('addRelationship'),'h6'));editor.append(box);
            const row={id:crypto.randomUUID(),source:field(box,label('relationSource'),input()),target:field(box,label('relationTarget'),input()),kind:field(box,label('relationKind'),select(['INTERFACE','DEPENDENCY','ALTERNATIVE','HARD_EXCLUSION'].map(v=>[v,display(v)]))),qualifier:field(box,label('qualifier'),input()),required:field(box,label('required'),input('checkbox'))};
            row.source.dataset.relationshipSource='';row.target.dataset.relationshipTarget='';row.kind.dataset.relationshipKind='';row.required.checked=true;
            relationshipRows.push(row);removeRow(relationshipRows,row,box);
        }));
        editor.append(node('p',label('membership'),'small'),button(label('addVariant'),()=>{
            const box=node('fieldset',undefined,'border p-2 my-2');box.append(node('legend',label('variant'),'h6'));editor.append(box);
            const row={solution:field(box,label('variant'),select([['',label('choose')],...(options.solutions||[]).map(v=>[String(v.id),v.title||v.solution?.title||String(v.id)])])),action:field(box,label('action'),select(['REUSE','SATISFIED_AS_IS','CHANGE','CREATE','PROCURE','ORGANIZATIONAL','RETIRE_OR_REPLACE','UNDECIDED'].map(v=>[v,display(v)]))),candidates:field(box,label('candidateEvidence'),input()),prerequisites:[],risks:[],costs:[]};
            row.solution.dataset.variantSolution='';row.candidates.dataset.variantCandidates='';
            function note(kind) {const area=node('fieldset',undefined,'border p-2 my-2');box.append(area);const n={text:field(area,label('note'),node('textarea')),status:field(area,label('assessmentStatus'),select(['OPEN','SATISFIED','BLOCKING'].map(v=>[v,display(v)]))),citation:field(area,label('citation'),input())};n.text.className='form-control';n.text.maxLength=1800;n.citation.maxLength=1800;row[kind].push(n);removeRow(row[kind],n,area);}
            box.append(button(label('prerequisite'),()=>note('prerequisites')),button(label('risk'),()=>note('risks')),button(label('cost'),()=>{const area=node('fieldset',undefined,'border p-2 my-2');box.append(area);const c={amount:field(area,label('amount'),input('text')),currency:field(area,label('currency'),input()),basis:field(area,label('basis'),input()),citation:field(area,label('citation'),input())};c.amount.inputMode='decimal';c.amount.placeholder='1234.5678';c.currency.maxLength=3;row.costs.push(c);removeRow(row.costs,c,area);}));
            variantRows.push(row);removeRow(variantRows,row,box);
        }));
        const output=node('div');output.dataset.inventoryResult='';
        const previous=field(panel,label('retained'),select([['',label('choose')]]));previous.dataset.inventoryRetained='';
        const reopen=button(label('reopen'),()=>run(()=>open(previous.value)));reopen.dataset.inventoryReopen='';
        let retainedIds=new Set();
        function updateReopen() {reopen.disabled=!retainedIds.has(previous.value);reopen.setAttribute('title',reopen.disabled?label('selectRetained'):'');}
        previous.addEventListener('change',updateReopen);updateReopen();
        async function run(action) {const current=window.TaxonomyApiClient.captureContext();status.textContent='';try {await action();}catch(error){if(current()&&error.code!=='ABORTED')status.textContent=error.detail||error.message||label('error');}}
        function request(nodes,question,sourceProfiles) {if(!nodes.length)throw new Error(label('selectNodes'));return {selectedNodes:nodes,question,sourceProfiles,
            relationships:relationshipRows.map(r=>({id:r.id,sourceCandidateId:r.source.value.trim(),targetCandidateId:r.target.value.trim(),kind:r.kind.value,qualifier:r.qualifier.value.trim()||null,required:r.required.checked})),
            variants:variantRows.map(v=>({projectSolutionId:Number(v.solution.value),action:v.action.value,candidateIds:v.candidates.value.split(',').map(x=>x.trim()).filter(Boolean),prerequisites:v.prerequisites.map(n=>({text:n.text.value,status:n.status.value,citation:n.citation.value})),risks:v.risks.map(n=>({text:n.text.value,status:n.status.value,citation:n.citation.value})),costs:v.costs.map(c=>({amount:c.amount.value.trim(),currency:c.currency.value.trim().toUpperCase(),basis:c.basis.value,citation:c.citation.value}))}))};}
        async function refreshList(preferred) {const values=await window.TaxonomyPortfolioApi.listInventoryComparisons(options.projectId, options.snapshot.summary.id);const selected=preferred||previous.value;retainedIds=new Set(values.map(value=>value.id));previous.replaceChildren();for(const value of [{id:'',createdAt:label('choose')},...values]){const o=node('option',value.createdAt+' '+(value.demand?.question?.organization||''));o.value=value.id;previous.append(o);}previous.value=retainedIds.has(selected)?selected:'';updateReopen();}
        async function compare() {
            const question={mode:mode.value,start:start.value,endExclusive:mode.value==='EXACT_DATE'?null:end.value,organization:organization.value.trim(),environment:environment.value.trim()||null};
            if(!question.start||!question.organization||(question.mode!=='EXACT_DATE'&&!question.endExclusive))throw new Error(de?'Organisation und Zeitfrage vervollständigen':'Complete the organization and time question');
            const comparison=await window.TaxonomyPortfolioApi.createInventoryComparison(options.projectId, options.snapshot.summary.id,request(checks.filter(c=>c.checked).map(c=>c.value),question,profiles.value.split(',').map(v=>v.trim()).filter(Boolean)));
            render(comparison);await refreshList(comparison.id);return comparison;
        }
        setup.append(button(label('compare'),()=>run(compare)));
        panel.append(reopen,output);
        async function open(id) {if(!id)return;render(await window.TaxonomyPortfolioApi.getInventoryComparison(id));}
        function render(comparison) {
            output.replaceChildren();const demand=comparison.demand;
            output.append(node('h4',label('frozen'),'h6'),node('p',`${demand.snapshotId} · #${demand.requirementVersionId} · ${demand.taxonomyFingerprint}`,'small text-break'),node('p',demand.selectedNodes.join(', ')),node('p',label('unknown'),'small'));
            const question=node('dl',undefined,'portfolio-card-meta');
            for(const [name,value]of [['mode',display(demand.question.mode)],['start',demand.question.start],['end',demand.question.endExclusive],['organization',demand.question.organization],['environment',demand.question.environment]])if(value)question.append(node('dt',label(name)),node('dd',value));
            output.append(question);
            if(demand.requirementText)output.append(node('blockquote',demand.requirementText));
            const source=node('details');source.append(node('summary',label('source')),node('pre',JSON.stringify(comparison.sources,null,2),'text-break'));output.append(source);
            output.append(button(label('export'),()=>run(async()=>{const retained=await window.TaxonomyPortfolioApi.getInventoryComparison(comparison.id);const a=node('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(retained,null,2)],{type:'application/json'}));a.download='inventory-comparison-'+comparison.id+'.json';a.click();URL.revokeObjectURL(a.href);})));
            const table=node('table',undefined,'table table-sm mt-3');const head=node('tr');for(const text of [label('candidate'),label('finding'),label('next')])head.append(node('th',text));const thead=node('thead');thead.append(head);table.append(thead);const body=node('tbody');table.append(body);const tableScroll=node('div',undefined,'table-responsive');tableScroll.append(table);output.append(tableScroll);
            for(const candidate of comparison.candidates) {
                const identity=node('td');identity.className='align-top';identity.append(node('strong',candidate.name),node('p',display(candidate.kind)+' · '+candidate.id,'small text-break'));
                const meta=node('details');meta.append(node('summary',label('evidence')),node('pre',JSON.stringify({metadata:candidate.metadata,assertions:candidate.assertions},null,2),'small text-break'));identity.append(meta);
                const findings=comparison.findings.filter(f=>f.candidateId===candidate.id);identity.rowSpan=Math.max(1,findings.length);
                for(const [index,finding] of findings.entries()) {
                    const row=node('tr');if(index===0)row.append(identity);
                    const values=node('td'),next=node('td');values.className='align-top';next.className='align-top';
                    values.append(node('strong',display(finding.outcome)),node('p',display(finding.criterion)+(finding.nodeCode?' · '+finding.nodeCode:''),'small mb-1'));
                    const limits=node('details');limits.append(node('summary',label('limitations')),node('p',(finding.limitations||[]).map(display).join('; '),'small'),node('p',(finding.evidenceIds||[]).join(', '),'small text-break'));values.append(limits);
                    next.append(node('p',clarification(finding.nextClarification),'small'));row.append(values,next);body.append(row);
                }
                const details=node('details');details.append(node('summary',label('review')));identity.append(details);
                const rationale=field(details,label('rationale'),node('textarea'));rationale.className='form-control';rationale.required=true;
                const solution=field(details,label('solution'),select([['',label('noSolution')],...(options.solutions||[]).map(s=>[String(s.id),s.title||s.solution?.title||String(s.id)])]));
                const coverage=field(details,label('coverage'),input('number'));coverage.min='0';coverage.max='100';
                const choose=field(details,label('select'),input('checkbox'));choose.dataset.inventorySelect='';choose.disabled=!candidate.productId||findings.some(f=>f.hardExclusion||f.outcome==='CONTRADICTED'||f.outcome==='CONFLICT');
                const reviewId=crypto.randomUUID();details.append(button(label('review'),()=>run(async()=>{if(!rationale.value.trim())throw new Error(label('rationale'));await window.TaxonomyPortfolioApi.reviewInventoryComparison(comparison.id,{id:reviewId,comparisonFingerprint:comparison.fingerprint,candidateId:candidate.id,rationale:rationale.value,projectSolutionId:solution.value?Number(solution.value):null,selectProduct:choose.checked,coveragePercent:coverage.value===''?null:Number(coverage.value)});status.textContent=label('saved');})));
                if(candidate.productId) assertionForm(details,candidate,demand);
                if(candidate.metadata?.sourceProfile) {
                    const mapping=node('details');mapping.append(node('summary',label('mapped')));details.append(mapping);const product=field(mapping,label('product'),select([['',label('choose')]]));
                    run(async()=>{for(const p of await window.TaxonomyPortfolioApi.listProducts()){const o=node('option',p.productName+' · '+p.productKey);o.value=String(p.id);product.append(o);}});
                    const mappingId=crypto.randomUUID();mapping.append(button(label('map'),()=>run(async()=>{if(!product.value||!rationale.value.trim())throw new Error(label('rationale'));await window.TaxonomyPortfolioApi.mapInventoryCandidate(comparison.id, candidate.id,{id:mappingId,productId:Number(product.value),comparisonFingerprint:comparison.fingerprint,rationale:rationale.value});status.textContent=label('saved');})));
                }
            }
            if(comparison.relationshipFindings?.length) {
                output.append(node('h4',label('directed'),'h6'));
                for(const relation of comparison.relationshipFindings)output.append(node('p',`${relation.sourceCandidateId} → ${relation.targetCandidateId} · ${display(relation.kind)} · ${relation.qualifier||'?'} · ${display(relation.finding.outcome)}`));
            }
            if(comparison.variants?.length)output.append(node('h4',label('variantFindings'),'h6'));
            const variants=node('div',undefined,'row g-3');output.append(variants);
            for(const variant of comparison.variants||[]) {
                const card=node('section',undefined,'col border rounded p-3');variants.append(card);card.append(node('h5',variant.title),node('p',variant.solutionKey+' · '+display(variant.input.action)));
                const frozen=node('details');frozen.append(node('summary',label('evidence')),node('pre',JSON.stringify({solution:JSON.parse(variant.frozenSolutionJson),assessment:variant.input,findings:variant.findings},null,2),'small text-break'));card.append(frozen);
                card.append(node('h6',label('exclusions')));for(const reason of variant.hardExclusions)card.append(node('p',reason,'small text-break'));
                card.append(node('h6',label('questions')));for(const question of variant.openQuestions)card.append(node('p',question,'small text-break'));
                const form=node('div');form.dataset.variantReview='';card.append(form);
                const decision=field(form,label('variantDecision'),select(['EVALUATED','SELECTED','REJECTED'].map(v=>[v,display(v)])));if(variant.hardExclusions.length)decision.querySelector('option[value=SELECTED]').disabled=true;
                const rationale=field(form,label('rationale'),node('textarea'));rationale.className='form-control';rationale.maxLength=1800;
                const coverage=field(form,label('coverage'),input('number'));coverage.min='0';coverage.max='100';
                const acknowledge=field(form,label('acknowledge'),input('checkbox'));const reviewId=crypto.randomUUID();
                form.append(button(label('saveVariant'),()=>run(async()=>{
                    if(!rationale.value.trim()||coverage.value==='')throw new Error(label('rationale')+' / '+label('coverage'));
                    await window.TaxonomyPortfolioApi.reviewInventoryComparison(comparison.id,{id:reviewId,comparisonFingerprint:comparison.fingerprint,candidateId:null,rationale:rationale.value,projectSolutionId:variant.projectSolutionId,selectProduct:false,coveragePercent:Number(coverage.value),variantDecision:{projectSolutionId:variant.projectSolutionId,outcome:decision.value,acknowledgeUnknowns:acknowledge.checked}});status.textContent=label('saved');
                })));
            }
            const history=node('details');history.append(node('summary',label('history')));output.append(history);
            run(async()=>{for(const item of await window.TaxonomyPortfolioApi.listInventoryReviews(comparison.id))history.append(node('p',item.actor+' · '+item.reviewedAt+' · '+item.review.rationale));});
            output.focus();
        }
        function assertionForm(parent,candidate,demand) {
            const form=node('details');form.append(node('summary',label('evidence')));parent.append(form);
            const criterion=field(form,label('criterion'),select(['AVAILABILITY','CAPABILITY','SUPPORT','ORGANIZATION','ENVIRONMENT','RELATIONSHIPS'].map(v=>[v,display(v)])));
            const edgeTarget=field(form,label('relationTarget'),input());const edgeKind=field(form,label('relationKind'),select(['INTERFACE','DEPENDENCY','ALTERNATIVE','HARD_EXCLUSION'].map(v=>[v,display(v)])));const edgeQualifier=field(form,label('qualifier'),input());
            const polarity=field(form,label('evidence'),select([['true',label('positive')],['false',label('negative')]]));
            const kind=field(form,label('kind'),select(['DECLARED','PLANNED','OBSERVED'].map(v=>[v,display(v)])));
            const from=field(form,label('start')+' (YYYY / YYYY-MM-DD)',input());const until=field(form,label('end')+' (YYYY / YYYY-MM-DD)',input());
            const org=field(form,label('organization'),input());org.value=demand.question.organization;const env=field(form,label('environment'),input());env.value=demand.question.environment||'';
            const nodeCode=field(form,label('assertionNode'),select([['',label('choose')],...demand.selectedNodes.map(v=>[v,display(v)])]));const citation=field(form,label('citation'),node('textarea'));
            const assertionId=crypto.randomUUID();form.append(button(label('saveAssertion'),()=>run(async()=>{if(!citation.value?.trim())throw new Error(label('citation'));await window.TaxonomyPortfolioApi.addInventoryAssertion(candidate.productId,{id:assertionId,candidateId:'product:'+candidate.productId,criterion:criterion.value,nodeCode:criterion.value==='CAPABILITY'?nodeCode.value:null,positive:polarity.value==='true',start:boundary(from.value),endExclusive:boundary(until.value),organization:org.value,environment:env.value||null,kind:kind.value,citation:citation.value,recordedAt:new Date().toISOString(),relationship:criterion.value==='RELATIONSHIPS'?{targetCandidateId:edgeTarget.value.trim(),kind:edgeKind.value,qualifier:edgeQualifier.value.trim()||null}:null});status.textContent=label('saved');})));
        }
        run(refreshList);return {open,compare,request,element:panel};
    }
    return {mount};
})();
