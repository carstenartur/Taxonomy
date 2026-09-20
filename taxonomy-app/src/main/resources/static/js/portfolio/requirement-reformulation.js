/* Interactive immutable proposal workspace. All external strings are rendered as text. */
(function () {
    'use strict';
    const route = location.pathname.match(/^\/projects\/(\d+)\/requirements\/(\d+)\/?$/);
    const host = document.getElementById('reformulationList');
    if (!route || !host) return;
    const api = window.TaxonomyPortfolioApi, project = Number(route[1]), requirement = Number(route[2]);
    const lang = (new URLSearchParams(location.search).get('lang') || document.documentElement.lang || 'en').startsWith('de') ? 'de' : 'en';
    const words = {
        de: {heading:'Neuformulierungsangebote', create:'Neuformulierung vorschlagen', snapshot:'Vorhandener Analyse-Snapshot', source:'Quellversion', revision:'Entwurfsrevision',
            state:'Neuformulierungsangebot – nicht übernommen', original:'Original', proposal:'Vorschlag', questions:'Fragen', empty:'Noch kein Angebot vorhanden.',
            noSnapshot:'Zuerst einen vorhandenen Analyse-Snapshot auswählen.', save:'Entwurf speichern', saved:'Gespeichert. Die aktive Anforderung bleibt unverändert.',
            variant:'Variante speichern', copy:'Gespeicherte Revision kopieren', copied:'Gespeicherte Revision kopiert.', compare:'Mit Vorgänger vergleichen',
            synthesize:'Betroffene Abschnitte neu formulieren', generate:'Neuen Formulierungslauf starten', rationale:'Begründung', defaultRationale:'Bewusste Bearbeitung des Angebots',
            answer:'Antwort speichern', defer:'Noch offen / zurückstellen', na:'Nicht anwendbar', other:'Andere Auswahl erläutern', history:'Entscheidungsverlauf',
            impact:'Betroffene Aussagen / Abschnitte / Schnittstellen', noImpact:'Noch keine Antwortänderungen.', review:'Alle Ergänzungen bleiben fachlich ungeprüft.',
            additions:'Aussagen, Ergänzungen und Herkunft', edit:'Absatz bearbeiten', reject:'Ergänzung ablehnen', rejected:'Abgelehnt – bleibt als Beleg erhalten',
            sourceAnswer:'Durch Original beantwortet', sourceConflict:'Widerspruch: Original und menschliche Entscheidung bleiben erhalten.',
            candidate:'Änderungsvorschlag – geschützte Bearbeitung bleibt erhalten', current:'Aktuell gespeicherter Text', candidateText:'Neuer Kandidat',
            older:'Dieses Angebot basiert auf einer älteren Quellversion.', noQuestions:'Keine Fragen erfasst. Dies ist keine fachliche Freigabe.',
            loading:'Wird geladen…', working:'Wird verarbeitet…', dirty:'Ungespeicherte Bearbeitung bleibt erhalten.', failed:'Aktion fehlgeschlagen: ',
            architecture:'Architekturbezug', findings:'Konflikte und Quellabdeckung', true:'Ja', false:'Nein', refresh:'Status aktualisieren',
            OPEN:'Offen', ANSWERED:'Beantwortet', DEFERRED:'Zurückgestellt', NOT_APPLICABLE:'Nicht anwendbar', CONFLICT:'Konflikt',
            MODEL_ADDITION:'Modellergänzung', ORIGINAL:'Original', CATALOGUE_INSPIRATION:'Kataloganregung', ARCHITECTURE_HYPOTHESIS:'Architekturhypothese', HUMAN_DECISION:'Menschliche Entscheidung',
            changeDecision:'Abweichende menschliche Entscheidung erfassen', applicable:'Bedingte Folgefrage', unavailable:'Erst bei passender Antwort auf die vorausgesetzte Frage beantworten.'},
        en: {heading:'Reformulation offers', create:'Propose reformulation', snapshot:'Existing analysis snapshot', source:'Source version', revision:'Draft revision',
            state:'Reformulation offer – not adopted', original:'Original', proposal:'Proposal', questions:'Questions', empty:'No offer yet.',
            noSnapshot:'Select an existing analysis snapshot first.', save:'Save draft', saved:'Saved. The active requirement is unchanged.',
            variant:'Save variant', copy:'Copy saved revision', copied:'Saved revision copied.', compare:'Compare with predecessor',
            synthesize:'Reword affected sections', generate:'Start new wording run', rationale:'Rationale', defaultRationale:'Deliberate proposal edit',
            answer:'Save answer', defer:'Still open / defer', na:'Not applicable', other:'Describe other choice', history:'Decision history',
            impact:'Affected statements / sections / interfaces', noImpact:'No answer changes yet.', review:'All additions still require expert review.',
            additions:'Statements, additions and provenance', edit:'Edit paragraph', reject:'Reject addition', rejected:'Rejected – retained as evidence',
            sourceAnswer:'Answered by original', sourceConflict:'Conflict: source and human decision remain available.',
            candidate:'Candidate change – protected edit remains in place', current:'Currently saved text', candidateText:'New candidate',
            older:'This offer is based on an older source version.', noQuestions:'No questions recorded. This does not mean expert approval.',
            loading:'Loading…', working:'Working…', dirty:'Unsaved edit has been preserved.', failed:'Action failed: ',
            architecture:'Architecture reference', findings:'Conflicts and source coverage', true:'Yes', false:'No', refresh:'Refresh status',
            OPEN:'Open', ANSWERED:'Answered', DEFERRED:'Deferred', NOT_APPLICABLE:'Not applicable', CONFLICT:'Conflict',
            MODEL_ADDITION:'Model addition', ORIGINAL:'Original', CATALOGUE_INSPIRATION:'Catalogue inspiration', ARCHITECTURE_HYPOTHESIS:'Architecture hypothesis', HUMAN_DECISION:'Human decision',
            changeDecision:'Record a different human decision', applicable:'Conditional follow-up', unavailable:'Answer only after the prerequisite decision selects the relevant variant.'}
    };
    const t = key => words[lang][key] || key;
    let offer, currentRequirement, offers = [], dirtyText = null, activeView = 'proposal', timer;
    const status = document.getElementById('reformulationStatus');
    const el = (tag, text, cls) => { const node=document.createElement(tag); if(text!==undefined)node.textContent=text; if(cls)node.className=cls; return node; };
    const button = (label, action, cls) => { const node=el('button',label,cls || 'btn btn-sm btn-outline-primary'); node.type='button'; node.addEventListener('click',()=>perform(action)); return node; };
    function announce(message) { status.textContent=message; }
    async function perform(action) { try { announce(t('working')); await action(); } catch(error) { announce(t('failed')+error.message); status.tabIndex=-1; status.focus(); } }
    function field(label, control) { const wrapper=el('label',label,'d-block mb-2'); wrapper.append(control); return wrapper; }
    function pre(text) { return el('pre',text,'text-break'); }
    function rationale() { return host.querySelector('[data-rationale]')?.value || t('defaultRationale'); }
    const refs = q => [q.id].concat(q.aliases || []);
    function activeAnswers() { const all=offer.currentRevision.answers; const superseded=new Set(all.flatMap(a=>a.supersedes || [])); return all.filter(a=>!superseded.has(a.id)); }
    function applicable(q) {
        const conditions=q.answerSchema.applicability || [];
        return conditions.every(c=>{ const parent=offer.currentRevision.questions.find(p=>refs(p).includes(c.questionId));
            return parent && parent.state==='ANSWERED' && (activeAnswers().filter(a=>refs(parent).includes(a.questionId) && a.state==='ANSWERED').some(a=>a.values.some(v=>c.anyOf.includes(v)))
                || (parent.sourceResolutions || []).some(r=>r.values.some(v=>c.anyOf.includes(v)))); });
    }
    function architectureLink(id) {
        const url=new URL('/architecture/workbench',location.origin);
        url.searchParams.set('projectId',project); url.searchParams.set('snapshotId',offer.baseline.snapshotId); url.searchParams.set('lang',lang); url.searchParams.set('nodeId',id);
        const a=el('a',t('architecture')+' '+id,'me-2');a.href=url.pathname+url.search;return a;
    }
    async function update(operation,body) {
        offer=await api.updateReformulation(project,requirement,offer.id,operation,offer.currentRevision.number,body);
        render(); announce(t('saved')+(dirtyText!==null?' '+t('dirty'):''));
        if(body.questionId){const q=offer.currentRevision.questions.find(q=>refs(q).includes(body.questionId));document.getElementById('question-'+q.id)?.focus();}
    }
    function changeView(view,focus) {
        activeView=view;
        host.querySelectorAll('[data-panel]').forEach(p=>p.dataset.active=String(p.dataset.panel===view));
        host.querySelectorAll('[data-reformulation-view]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.reformulationView===view)));
        if(focus){ const panel=host.querySelector('[data-panel="'+view+'"]'); panel.tabIndex=-1; panel.focus(); }
    }
    function render() {
        host.replaceChildren(); if(!offer){host.append(el('p',t('empty')));return;}
        const revision=offer.currentRevision;
        const selection=el('select',undefined,'form-select');selection.setAttribute('aria-label',t('proposal'));
        offers.forEach(p=>{const o=el('option',p.id.slice(0,8)+' · '+t('revision')+' '+p.currentRevision.number);o.value=p.id;selection.append(o);});
        if(!offers.some(p=>p.id===offer.id)){const o=el('option',offer.id.slice(0,8));o.value=offer.id;selection.append(o);}selection.value=offer.id;
        selection.addEventListener('change',()=>perform(async()=>{if(dirtyText!==null){selection.value=offer.id;throw new Error(t('dirty'));}offer=await api.getReformulation(project,requirement,selection.value);render();await refreshRuns();announce(t('state'));}));
        host.append(selection,el('p',t('state'),'fw-semibold mt-3'),el('p',t('source')+' '+offer.baseline.sourceVersionId+' · '+t('revision')+' '+revision.number,'text-body-secondary'));
        if(currentRequirement && currentRequirement.currentVersionId!==offer.baseline.sourceVersionId)host.append(el('p',t('older'),'alert alert-warning'));
        if(revision.variantOrigin)host.append(el('p',t('variant')+': '+revision.variantOrigin.proposalId+' / '+revision.variantOrigin.revision,'text-break'));
        const tabs=el('div',undefined,'reformulation-tabs');tabs.setAttribute('aria-label',t('proposal'));
        ['original','proposal','questions'].forEach(view=>{const b=button(t(view),()=>changeView(view,true));b.dataset.reformulationView=view;tabs.append(b);});host.append(tabs);
        const grid=el('div',undefined,'reformulation-grid');
        const original=el('section',undefined,'reformulation-panel');original.dataset.panel='original';original.append(el('h3',t('original'),'h5'));const source=pre(offer.baseline.originalText);source.dataset.reformulationOriginal='';original.append(source);grid.append(original);
        const proposal=el('section',undefined,'reformulation-panel');proposal.dataset.panel='proposal';proposal.append(el('h3',t('proposal'),'h5'));
        const editor=el('textarea',undefined,'form-control');editor.dataset.reformulationEditor='';editor.setAttribute('aria-label',t('proposal'));editor.value=dirtyText===null?revision.text:dirtyText;
        editor.addEventListener('input',()=>{dirtyText=editor.value;announce(t('dirty'));});proposal.append(editor,el('p',t('review'),'small text-body-secondary mt-2'));
        const reason=el('input',undefined,'form-control');reason.dataset.rationale='';reason.value=t('defaultRationale');proposal.append(field(t('rationale'),reason));
        const controls=el('div',undefined,'reformulation-controls');
        const save=button(t('save'),async()=>{const text=editor.value;await update('revisions',{text,rationale:reason.value});dirtyText=null;render();announce(t('saved'));host.querySelector('[data-reformulation-save]').focus();},'btn btn-primary');save.dataset.reformulationSave='';controls.append(save);
        controls.append(button(t('variant'),async()=>{if(dirtyText!==null)throw new Error(t('dirty'));await update('variants',{rationale:reason.value});const url=new URL(location.href);url.searchParams.set('proposal',offer.id);history.replaceState(null,'',url);}),
            button(t('copy'),async()=>{const exact=await api.getReformulationRevision(project,requirement,offer.id,revision.number);await navigator.clipboard.writeText(exact.text);announce(t('copied'));}),
            button(t('compare'),async()=>{const old=revision.predecessor?await api.getReformulationRevision(project,requirement,offer.id,revision.predecessor):{text:offer.baseline.originalText};showComparison(old.text,revision.text,t('compare'));announce(t('compare'));}));
        proposal.append(controls);grid.append(proposal);
        const questions=el('section',undefined,'reformulation-panel reformulation-questions');questions.dataset.panel='questions';questions.append(el('h3',t('questions'),'h5'));
        if(!revision.questions.length)questions.append(el('p',t('noQuestions')));revision.questions.forEach(q=>questions.append(question(q)));grid.append(questions);host.append(grid);
        const impact=el('details');impact.open=true;impact.append(el('summary',t('impact')));impact.append(pre(revision.impact?.sectionIds.length ? [revision.impact.statementIds.join(', '),revision.impact.sectionIds.join(', '),revision.impact.boundaryEdgeIds.join(', ')].filter(Boolean).join('\n') : t('noImpact')));host.append(impact);
        const actions=el('div',undefined,'reformulation-controls');actions.append(button(revision.impact?.sectionIds.length?t('synthesize'):t('generate'),async()=>{await api.updateReformulation(project,requirement,offer.id,'synthesis-runs',revision.number,{});announce(t('working'));await refreshRuns();}),button(t('refresh'),refreshRuns));host.append(actions);
        const statements=el('details');statements.append(el('summary',t('additions')));revision.statements.forEach(s=>{
            const item=el('div',undefined,'border rounded p-3 my-2');item.id='statement-'+s.id;item.tabIndex=-1;
            item.append(el('strong',t(s.provenance)+(s.editingOrigin==='HUMAN'?' · '+t('HUMAN_DECISION'):'')),pre(s.wording));
            if(s.reviewState==='REJECTED')item.append(el('p',t('rejected'),'text-danger'));
            s.architectureLinks.forEach(id=>item.append(architectureLink(id)));
            s.questionDependencies.forEach(id=>{const a=el('a',t('questions')+' '+id,'me-2');a.href='#question-'+id;a.addEventListener('click',()=>changeView('questions',false));item.append(a);});
            const edit=el('textarea',undefined,'form-control my-2');edit.setAttribute('aria-label',t('edit')+' '+s.id);edit.value=s.wording;item.append(edit);
            item.append(button(t('edit'),()=>update('statements/'+s.id,{action:'EDIT',text:edit.value,rationale:rationale()})));
            if(s.provenance!=='ORIGINAL' && s.reviewState!=='REJECTED')item.append(button(t('reject'),()=>update('statements/'+s.id,{action:'REJECT',rationale:rationale()}),'btn btn-sm btn-outline-danger ms-2'));
            statements.append(item);
        });host.append(statements);
        const findings=el('details');findings.append(el('summary',t('findings')));revision.validation.findings.forEach(f=>findings.append(el('p',f.code+': '+f.message,'text-break')));host.append(findings);
        const runs=el('div');runs.id='reformulationRuns';host.append(runs);const comparison=el('div');comparison.id='reformulationComparison';host.append(comparison);changeView(activeView,false);
    }
    function question(q) {
        const box=el('section',undefined,'reformulation-question');box.id='question-'+q.id;box.tabIndex=-1;
        box.append(el('h4',q.wording,'h6'),el('p',t(q.state),'fw-semibold'));
        if(q.state==='CONFLICT')box.append(el('p',t('sourceConflict'),'text-danger'));
        (q.sourceResolutions || []).forEach(r=>box.append(el('p',t('sourceAnswer')+': '+r.values.join(', ')+' — '+r.rationale,'reformulation-evidence')));
        const evidence=el('details');evidence.append(el('summary',t('architecture')));
        q.discoveries.forEach(d=>{evidence.append(el('p',d.rationale),el('p',d.context,'small text-break'));d.nodeIds.concat(d.edgeIds).forEach(id=>evidence.append(architectureLink(id)));d.sourceSpans.forEach(span=>evidence.append(pre(span.exactText)));});
        q.affectedStatementIds.forEach(id=>{const a=el('a',id,'me-2');a.href='#statement-'+id;a.addEventListener('click',()=>{const item=document.getElementById('statement-'+id);if(item)item.parentElement.open=true;});evidence.append(a);});box.append(evidence,el('p',q.consequences));
        let answerHost=box;
        if((q.sourceResolutions || []).length){answerHost=el('details');answerHost.append(el('summary',t('changeDecision')));box.append(answerHost);}
        const schema=q.answerSchema, inputs=[];
        const last=activeAnswers().filter(a=>refs(q).includes(a.questionId)).at(-1);
        if(['SINGLE_CHOICE','MULTIPLE_CHOICE'].includes(schema.kind))schema.options.forEach(option=>{
            const input=el('input');input.type=schema.kind==='MULTIPLE_CHOICE'?'checkbox':'radio';input.name='question-'+q.id;input.value=option;input.className='form-check-input me-2';input.checked=!!last?.values.includes(option);inputs.push(input);const label=el('label',undefined,'d-block my-1');label.append(input,document.createTextNode(option));answerHost.append(label);
        });
        else if(schema.kind==='BOOLEAN'){const input=el('select',undefined,'form-select');input.setAttribute('aria-label',q.wording);['','true','false'].forEach(value=>{const option=el('option',value?t(value):'—');option.value=value;input.append(option);});input.value=last?.values[0] || '';inputs.push(input);answerHost.append(input);}
        else {const input=el(schema.kind==='TEXT'?'textarea':'input',undefined,'form-control');input.setAttribute('aria-label',q.wording);if(schema.kind==='NUMBER'){input.type='number';input.step='any';if(schema.minimum!==null)input.min=schema.minimum;if(schema.maximum!==null)input.max=schema.maximum;}input.value=last?.values[0] || '';inputs.push(input);answerHost.append(input);if(schema.unit)answerHost.append(el('small',schema.unit));}
        const other=el('input',undefined,'form-control');other.dataset.otherAnswer='';other.value=last?.otherText || '';answerHost.append(field(t('other'),other));
        const reason=el('input',undefined,'form-control');reason.value=last?.rationale || t('defaultRationale');answerHost.append(field(t('rationale'),reason));
        const controls=el('div',undefined,'reformulation-controls');
        const answer=button(t('answer'),()=>update('answers',{questionId:q.id,action:'ANSWER',values:inputs.filter(i=>!['radio','checkbox'].includes(i.type)||i.checked).map(i=>i.value),otherText:other.value,rationale:reason.value}));answer.disabled=!applicable(q);controls.append(answer);
        ['DEFER','NOT_APPLICABLE'].forEach(action=>controls.append(button(t(action==='DEFER'?'defer':'na'),()=>update('answers',{questionId:q.id,action,values:[],otherText:'',rationale:reason.value}))));answerHost.append(controls);
        if(schema.applicability?.length)answerHost.append(el('p',t('applicable')+': '+schema.applicability.map(c=>c.questionId+' = '+c.anyOf.join(' / ')).join('; ')+(applicable(q)?'':' — '+t('unavailable')),'small'));
        const history=el('details');history.append(el('summary',t('history')));offer.currentRevision.answers.filter(a=>refs(q).includes(a.questionId)).forEach(a=>history.append(el('p',a.author+' · '+a.occurredAt+' · '+t(a.state)+' · '+a.values.join(', ')+' '+(a.otherText || '')+' — '+a.rationale)));box.append(history);return box;
    }
    function showComparison(before,after,title) {
        const target=document.getElementById('reformulationComparison');target.replaceChildren(el('h3',title,'h5 mt-3'));
        const grid=el('div',undefined,'reformulation-grid');[[t('current'),before],[t('candidateText'),after]].forEach(([label,text])=>{const col=el('div',undefined,'border rounded p-3');col.append(el('h4',label,'h6'),pre(text));grid.append(col);});target.append(grid);target.tabIndex=-1;target.focus();
    }
    async function refreshRuns() {
        clearTimeout(timer);if(!offer)return;const selected=offer.id;
        const runs=await api.listReformulationRuns(project,requirement,selected);if(offer.id!==selected)return;
        const previousVersion=currentRequirement?.currentVersionId;
        const [latest, active]=await Promise.all([api.getReformulation(project,requirement,selected),api.getRequirement(project,requirement)]);
        if(offer.id!==selected)return;currentRequirement=active;
        if(latest.currentRevision.number!==offer.currentRevision.number || previousVersion!==active.currentVersionId){offer=latest;render();}
        const target=document.getElementById('reformulationRuns');target.replaceChildren();
        runs.slice(-3).forEach(run=>{target.append(el('p',run.status+(run.failureCode?' · '+run.failureCode:''),'small text-break'));
            if(run.status==='PARTIAL' && run.candidate){const candidate=el('section',undefined,'reformulation-candidate my-2');candidate.dataset.reformulationCandidate='';candidate.append(el('h3',t('candidate'),'h5'),button(t('compare'),()=>showComparison(offer.currentRevision.text,run.candidate.text,t('candidate'))));target.append(candidate);}
        });
        if(runs.some(r=>['QUEUED','RUNNING'].includes(r.status))){announce(t('working'));timer=setTimeout(()=>perform(refreshRuns),1500);}
        else announce(t('state')+(dirtyText!==null?' '+t('dirty'):''));
    }
    async function start() {
        document.getElementById('reformulationHeading').textContent=t('heading');announce(t('loading'));
        const snapshots=await api.listRequirementSnapshots(project,requirement);currentRequirement=await api.getRequirement(project,requirement);
        const start=document.getElementById('reformulationStart'),select=el('select',undefined,'form-select');select.id='reformulationSnapshot';
        snapshots.forEach(snapshot=>{const option=el('option','v'+snapshot.requirementVersionNumber+' · '+snapshot.id+' · '+snapshot.status);option.value=snapshot.id;select.append(option);});
        const requested=new URLSearchParams(location.search).get('snapshot');if(snapshots.some(s=>s.id===requested))select.value=requested;
        start.append(field(t('snapshot'),select),button(t('create'),async()=>{const snapshot=snapshots.find(s=>s.id===select.value);if(!snapshot)throw new Error(t('noSnapshot'));offer=await api.createReformulation(project,requirement,{sourceVersionId:snapshot.requirementVersionId,snapshotId:snapshot.id,language:lang});offers.unshift(offer);dirtyText=null;render();await refreshRuns();}));
        offers=await api.listReformulations(project,requirement);offer=offers.find(p=>p.id===new URLSearchParams(location.search).get('proposal')) || offers[0];render();if(offer)await refreshRuns();else announce(t('empty'));
    }
    window.addEventListener('beforeunload',event=>{if(dirtyText!==null){event.preventDefault();event.returnValue='';}});
    perform(start);
}());
