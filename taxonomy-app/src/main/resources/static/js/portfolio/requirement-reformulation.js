/* Interactive immutable proposal workspace. All external strings are rendered as text. */
(function () {
    'use strict';
    const route = location.pathname.match(/\/projects\/(\d+)\/requirements\/(\d+)\/?$/);
    const host = document.getElementById('reformulationList');
    if (!route || !host) return;
    const api = window.TaxonomyPortfolioApi, project = Number(route[1]), requirement = Number(route[2]);
    const lang = (new URLSearchParams(location.search).get('lang') || document.documentElement.lang || 'en').startsWith('de') ? 'de' : 'en';
    const words = {
        de: {usage:'Erfasste Anfrageversuche', usageUnrecorded:'Für diesen Lauf wurde noch keine Verbrauchserfassung gestartet.',
            httpAttempts:'Vorgemerkte HTTP-Versuche', replays:'Wiedergaben ohne HTTP', pendingAttempts:'Versuche ohne gespeicherten Ausgang', retries:'Transportwiederholungen', httpErrors:'HTTP-Fehlerantworten', transportErrors:'Transportfehler', invalidUsage:'Ungültige Verbrauchsangaben',
            inputTokens:'Gemeldete Eingabetokens', outputTokens:'Gemeldete Ausgabetokens', totalTokens:'Gemeldete Gesamttokens', cachedInputTokens:'Gemeldete Cache-Tokens', reasoningTokens:'Gemeldete Reasoning-Tokens',
            unknownUsage:'ohne Angabe', reports:'Meldungen', usageLate:'Die Erfassung begann erst bei einer Wiederaufnahme. Frühere Anfragen sind nicht enthalten.',usageWarning:'Beginn ist kein Beleg für Empfang oder Abrechnung. Offene Ausgänge können noch laufend oder durch Absturz oder Speicherfehler ungeklärt sein. Fehlende Tokenangaben sind nicht null Verbrauch; es werden keine Kosten errechnet.',
            progress:'Gespeicherte Teilergebnisse', storedRun:'In diesem Lauf neu gespeicherte Teilschritte', storedOffer:'Im gesamten Angebot gespeicherte Teilschritte',
            partialWarning:'Ungeprüftes Teilergebnis – kein vollständiger Vorschlag und nicht übernommen. Fragen und Aussagen können noch Abgleiche benötigen.',
            countWarning:'Diese Zähler sind weder Modellaufrufe noch ein Vollständigkeitsmaß. Wiederverwendung, Reparaturaufrufe und Tokenkosten werden hier nicht gezählt.',
            moreResults:'Weitere Teilergebnisse', noResults:'Noch kein Teilergebnis dauerhaft gespeichert.', inspect:'Teilergebnis ansehen', closeProgress:'Teilergebnisse schließen',
            createdAt:'Lauf angelegt', lastCheckpoint:'Letztes neu gespeichertes Ergebnis', retained:'Verweise auf erhaltene Aussagen / Fragen',
            heading:'Neuformulierungsangebote', create:'Neuformulierung vorschlagen', snapshot:'Vorhandener Analyse-Snapshot', source:'Quellversion', revision:'Entwurfsrevision',
            state:'Neuformulierungsangebot – nicht übernommen', original:'Original', proposal:'Vorschlag', questions:'Fragen', empty:'Noch kein Angebot vorhanden.',
            noSnapshot:'Zuerst einen vorhandenen Analyse-Snapshot auswählen.', save:'Entwurf speichern', saved:'Gespeichert. Die aktive Anforderung bleibt unverändert.',
            variant:'Variante speichern', copy:'Gespeicherte Revision kopieren', copied:'Gespeicherte Revision kopiert.', compare:'Mit Vorgänger vergleichen',
            synthesize:'Betroffene Abschnitte neu formulieren', generate:'Neuen Formulierungslauf starten', cancelRun:'Lauf abbrechen',
            retryHint:'Gespeicherte Teilergebnisse bleiben erhalten. Ein neuer Lauf verwendet passende Ergebnisse erneut; eine bereits laufende Modellanfrage kann noch enden, aber nichts mehr veröffentlichen.', rationale:'Begründung', defaultRationale:'Bewusste Bearbeitung des Angebots',
            answer:'Antwort speichern', defer:'Noch offen / zurückstellen', na:'Nicht anwendbar', other:'Andere Auswahl erläutern', history:'Entscheidungsverlauf',
            impact:'Betroffene Aussagen / Abschnitte / Schnittstellen', noImpact:'Noch keine Antwortänderungen.', review:'Alle Ergänzungen bleiben fachlich ungeprüft.',
            additions:'Aussagen, Ergänzungen und Herkunft', edit:'Absatz bearbeiten', reject:'Ergänzung ablehnen', rejected:'Abgelehnt – bleibt als Beleg erhalten',
            sourceAnswer:'Durch Original beantwortet', sourceConflict:'Widerspruch: Original und menschliche Entscheidung bleiben erhalten.',
            candidate:'Änderungsvorschlag – geschützte Bearbeitung bleibt erhalten', current:'Aktuell gespeicherter Text', candidateText:'Neuer Kandidat',
            older:'Dieses Angebot basiert auf einer älteren Quellversion.', noQuestions:'Keine Fragen erfasst. Dies ist keine fachliche Freigabe.',
            loading:'Wird geladen…', working:'Wird verarbeitet…', dirty:'Ungespeicherte Bearbeitung bleibt erhalten.', failed:'Aktion fehlgeschlagen: ',
            referenceUnavailable:'Der Bezug ist im eingefrorenen Snapshot nicht vorhanden.', architecture:'Architekturbezug', findings:'Konflikte und Quellabdeckung', true:'Ja', false:'Nein', refresh:'Status aktualisieren',
            OPEN:'Offen', ANSWERED:'Beantwortet', DEFERRED:'Zurückgestellt', NOT_APPLICABLE:'Nicht anwendbar', CONFLICT:'Konflikt',
            MODEL_ADDITION:'Modellergänzung', ORIGINAL:'Original', CATALOGUE_INSPIRATION:'Kataloganregung', ARCHITECTURE_HYPOTHESIS:'Architekturhypothese', HUMAN_DECISION:'Menschliche Entscheidung',
            changeDecision:'Abweichende menschliche Entscheidung erfassen', applicable:'Bedingte Folgefrage', unavailable:'Erst bei passender Antwort auf die vorausgesetzte Frage beantworten.'},
        en: {usage:'Recorded request attempts', usageUnrecorded:'Usage recording has not been started for this run.',
            httpAttempts:'Admitted HTTP attempts', replays:'Replays without HTTP', pendingAttempts:'Attempts without a recorded outcome', retries:'Transport retries', httpErrors:'HTTP error responses', transportErrors:'Transport errors', invalidUsage:'Invalid usage metadata',
            inputTokens:'Reported input tokens', outputTokens:'Reported output tokens', totalTokens:'Reported total tokens', cachedInputTokens:'Reported cached tokens', reasoningTokens:'Reported reasoning tokens',
            unknownUsage:'unreported', reports:'reports', usageLate:'Recording started on a later execution attempt. Earlier requests are not included.',usageWarning:'Admission is not proof of receipt or billing. Pending outcomes may be in flight or unresolved after a crash or recording error. Missing token counts do not mean zero usage; no monetary cost is inferred.',
            progress:'Saved partial results', storedRun:'Steps newly saved in this run', storedOffer:'Steps saved across this offer',
            partialWarning:'Unreviewed partial result – not a complete proposal and not adopted. Statements and questions may still need reconciliation.',
            countWarning:'These counts are neither model calls nor a completeness measure. Reuse, repair requests and token costs are not counted here.',
            moreResults:'More partial results', noResults:'No partial result has been durably saved yet.', inspect:'Inspect partial result', closeProgress:'Close partial results',
            createdAt:'Run created', lastCheckpoint:'Last newly saved result', retained:'References to preserved statements / questions',
            heading:'Reformulation offers', create:'Propose reformulation', snapshot:'Existing analysis snapshot', source:'Source version', revision:'Draft revision',
            state:'Reformulation offer – not adopted', original:'Original', proposal:'Proposal', questions:'Questions', empty:'No offer yet.',
            noSnapshot:'Select an existing analysis snapshot first.', save:'Save draft', saved:'Saved. The active requirement is unchanged.',
            variant:'Save variant', copy:'Copy saved revision', copied:'Saved revision copied.', compare:'Compare with predecessor',
            synthesize:'Reword affected sections', generate:'Start new wording run', cancelRun:'Cancel run',
            retryHint:'Saved step results remain available for an explicit retry. An in-flight model request may still finish, but cannot publish after cancellation.', rationale:'Rationale', defaultRationale:'Deliberate proposal edit',
            answer:'Save answer', defer:'Still open / defer', na:'Not applicable', other:'Describe other choice', history:'Decision history',
            impact:'Affected statements / sections / interfaces', noImpact:'No answer changes yet.', review:'All additions still require expert review.',
            additions:'Statements, additions and provenance', edit:'Edit paragraph', reject:'Reject addition', rejected:'Rejected – retained as evidence',
            sourceAnswer:'Answered by original', sourceConflict:'Conflict: source and human decision remain available.',
            candidate:'Candidate change – protected edit remains in place', current:'Currently saved text', candidateText:'New candidate',
            older:'This offer is based on an older source version.', noQuestions:'No questions recorded. This does not mean expert approval.',
            loading:'Loading…', working:'Working…', dirty:'Unsaved edit has been preserved.', failed:'Action failed: ',
            referenceUnavailable:'This reference is not present in the frozen snapshot.', architecture:'Architecture reference', findings:'Conflicts and source coverage', true:'Yes', false:'No', refresh:'Refresh status',
            OPEN:'Open', ANSWERED:'Answered', DEFERRED:'Deferred', NOT_APPLICABLE:'Not applicable', CONFLICT:'Conflict',
            MODEL_ADDITION:'Model addition', ORIGINAL:'Original', CATALOGUE_INSPIRATION:'Catalogue inspiration', ARCHITECTURE_HYPOTHESIS:'Architecture hypothesis', HUMAN_DECISION:'Human decision',
            changeDecision:'Record a different human decision', applicable:'Conditional follow-up', unavailable:'Answer only after the prerequisite decision selects the relevant variant.'}
    };
    const t = key => words[lang][key] || key;
    let offer, currentRequirement, offers = [], activeView = 'proposal', timer;
    let mutationInFlight = false, readGeneration = 0;
    // Each entry is one independently submitted form. Object identity is its edit generation.
    // A response acknowledges only that exact generation, never input entered while awaiting it.
    const drafts = new Map();
    const hasDrafts = () => drafts.size > 0;
    function trackDraft(key, controls, read, restore) {
        if (drafts.has(key)) restore(drafts.get(key));
        const remember = () => { drafts.set(key, read()); announce(t('dirty')); };
        controls.forEach(control => {
            control.addEventListener('input', remember);
            control.addEventListener('change', remember);
        });
    }
    function requireCleanTransition() {
        if (hasDrafts()) throw new Error(t('dirty'));
        if (mutationInFlight) throw new Error(t('working'));
    }
    async function transition(load) {
        requireCleanTransition();
        mutationInFlight = true; ++readGeneration;
        try {
            const next = await load();
            if (!offers.some(p => p.id === next.id)) offers.unshift({id:next.id,currentRevision:next.currentRevision.number});
            // The outgoing form remains editable while the request is in flight.
            if (hasDrafts()) { render(); throw new Error(t('dirty')); }
            offer = next; render();
        } finally { mutationInFlight = false; ++readGeneration; }
        await refreshRuns();
    }
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
        const baseline = offer.baseline;
        const a = el('a', t('architecture')+' '+id, 'me-2');
        a.href = '#reformulationArchitectureDetail';
        a.addEventListener('click', event => {
            event.preventDefault();
            perform(() => showArchitectureDetail(id, baseline));
        });
        return a;
    }
    function showArchitectureDetail(id, baseline) {
        // Never resolve a historical reference against the current catalogue or active snapshot.
        const context = baseline.frozenContext || {};
        const pending = [...JSON.parse(context.catalogue || '[]')];
        let node;
        while (pending.length) {
            const candidate = pending.pop();
            if (candidate.code === id) { node = candidate; break; }
            pending.push(...(candidate.children || []));
        }
        const edge = JSON.parse(context.relationMappings || '[]').find(e => 'edge-'+e.id === id);
        let detail = document.getElementById('reformulationArchitectureDetail');
        if (!detail) {
            detail = el('section', undefined, 'border rounded p-3 my-3 text-break');
            detail.id = 'reformulationArchitectureDetail';
            detail.dataset.reformulationArchitectureDetail = '';
            detail.setAttribute('aria-label', t('architecture')); host.append(detail);
        }
        detail.dataset.snapshotId = baseline.snapshotId;
        detail.replaceChildren(el('h3', t('architecture')+' '+id, 'h5'),
            el('p', t('snapshot')+': '+baseline.snapshotId, 'small'));
        if (node) {
            detail.append(el('h4', (lang==='de'?node.nameDe:node.nameEn) || node.name || node.code, 'h6'),
                pre((lang==='de'?node.descriptionDe:node.descriptionEn) || node.description || ''));
        } else if (edge) {
            detail.append(el('h4', edge.sourceCode+' → '+edge.targetCode, 'h6'),
                pre([edge.relationType, edge.presenceReason, edge.reviewStatus].filter(v => v != null).join('\n')));
        } else detail.append(el('p', t('referenceUnavailable'), 'alert alert-warning'));
        detail.tabIndex = -1; detail.focus(); announce(t('architecture')+' '+id);
    }
    async function update(operation, body, draftKey) {
        if (mutationInFlight) throw new Error(t('working'));
        const submitted = drafts.get(draftKey);
        mutationInFlight = true; ++readGeneration;
        try {
            const next = await api.updateReformulation(project, requirement, offer.id, operation, offer.currentRevision.number, body);
            if (next.currentRevision.number >= offer.currentRevision.number) offer = next;
            if (draftKey && drafts.get(draftKey) === submitted) drafts.delete(draftKey);
            render(); announce(t('saved')+(hasDrafts()?' '+t('dirty'):''));
            if (body.questionId) {
                const q = offer.currentRevision.questions.find(q => refs(q).includes(body.questionId));
                if (q) document.getElementById('question-'+q.id)?.focus();
            }
        } finally { mutationInFlight = false; ++readGeneration; }
    }
    function changeView(view,focus) {
        activeView=view;
        host.querySelectorAll('[data-panel]').forEach(p=>p.dataset.active=String(p.dataset.panel===view));
        host.querySelectorAll('[data-reformulation-view]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.reformulationView===view)));
        if(focus){ const panel=host.querySelector('[data-panel="'+view+'"]'); panel.tabIndex=-1; panel.focus(); }
    }
    function render() {
        host.replaceChildren(); if(progressView && progressView.offerId!==offer?.id){progressView=null;++progressGeneration;} if(!offer){host.append(el('p',t('empty')));return;}
        const revision=offer.currentRevision;
        const selection=el('select',undefined,'form-select');selection.setAttribute('aria-label',t('proposal'));
        offers.forEach(p=>{const o=el('option',p.id.slice(0,8)+' · '+t('revision')+' '+(p.id===offer.id ? revision.number : p.currentRevision));o.value=p.id;selection.append(o);});
        if(!offers.some(p=>p.id===offer.id)){const o=el('option',offer.id.slice(0,8));o.value=offer.id;selection.append(o);}selection.value=offer.id;
        selection.addEventListener('change',()=>perform(async()=>{
            const selected = selection.value; selection.value = offer.id;
            await transition(() => api.getReformulation(project,requirement,selected));
        }));
        host.append(selection,el('p',t('state'),'fw-semibold mt-3'),el('p',t('source')+' '+offer.baseline.sourceVersionId+' · '+t('revision')+' '+revision.number,'text-body-secondary'));
        if(currentRequirement && currentRequirement.currentVersionId!==offer.baseline.sourceVersionId)host.append(el('p',t('older'),'alert alert-warning'));
        if(revision.variantOrigin)host.append(el('p',t('variant')+': '+revision.variantOrigin.proposalId+' / '+revision.variantOrigin.revision,'text-break'));
        const tabs=el('div',undefined,'reformulation-tabs');tabs.setAttribute('aria-label',t('proposal'));
        ['original','proposal','questions'].forEach(view=>{const b=button(t(view),()=>changeView(view,true));b.dataset.reformulationView=view;tabs.append(b);});host.append(tabs);
        const grid=el('div',undefined,'reformulation-grid');
        const original=el('section',undefined,'reformulation-panel');original.dataset.panel='original';original.append(el('h3',t('original'),'h5'));const source=pre(offer.baseline.originalText);source.dataset.reformulationOriginal='';original.append(source);grid.append(original);
        const proposal=el('section',undefined,'reformulation-panel');proposal.dataset.panel='proposal';proposal.append(el('h3',t('proposal'),'h5'));
        const editor=el('textarea',undefined,'form-control');editor.dataset.reformulationEditor='';editor.setAttribute('aria-label',t('proposal'));editor.value=revision.text;
        proposal.append(editor,el('p',t('review'),'small text-body-secondary mt-2'));
        const reason=el('input',undefined,'form-control');reason.dataset.rationale='';reason.value=t('defaultRationale');proposal.append(field(t('rationale'),reason));
        trackDraft('proposal', [editor,reason], () => ({text:editor.value,rationale:reason.value}),
            draft => {editor.value=draft.text;reason.value=draft.rationale;});
        const controls=el('div',undefined,'reformulation-controls');
        const save=button(t('save'),async()=>{await update('revisions',{text:editor.value,rationale:reason.value},'proposal');host.querySelector('[data-reformulation-save]').focus();},'btn btn-primary');save.dataset.reformulationSave='';controls.append(save);
        controls.append(button(t('variant'),async()=>{await transition(() => api.updateReformulation(project,requirement,offer.id,'variants',revision.number,{rationale:reason.value}));const url=new URL(location.href);url.searchParams.set('proposal',offer.id);history.replaceState(null,'',url);}),
            button(t('copy'),async()=>{const exact=await api.getReformulationRevision(project,requirement,offer.id,revision.number);await navigator.clipboard.writeText(exact.text);announce(t('copied'));}),
            button(t('compare'),async()=>{const old=revision.predecessor?await api.getReformulationRevision(project,requirement,offer.id,revision.predecessor):{text:offer.baseline.originalText};showComparison(old.text,revision.text,t('compare'));announce(t('compare'));}));
        proposal.append(controls);grid.append(proposal);
        const questions=el('section',undefined,'reformulation-panel reformulation-questions');questions.dataset.panel='questions';questions.append(el('h3',t('questions'),'h5'));
        if(!revision.questions.length)questions.append(el('p',t('noQuestions')));revision.questions.forEach(q=>questions.append(question(q)));grid.append(questions);host.append(grid);
        const impact=el('details');impact.open=true;impact.append(el('summary',t('impact')));impact.append(pre(revision.impact?.sectionIds.length ? [revision.impact.statementIds.join(', '),revision.impact.sectionIds.join(', '),revision.impact.boundaryEdgeIds.join(', ')].filter(Boolean).join('\n') : t('noImpact')));host.append(impact);
        const actions=el('div',undefined,'reformulation-controls');const generate=button(revision.impact?.sectionIds.length?t('synthesize'):t('generate'),async()=>{
            if(mutationInFlight)throw new Error(t('working'));
            mutationInFlight=true;++readGeneration;
            try {await api.updateReformulation(project,requirement,offer.id,'synthesis-runs',revision.number,{});}
            finally {mutationInFlight=false;++readGeneration;}
            await refreshRuns();
        });generate.id='reformulationStartRun';generate.disabled=true;actions.append(generate,button(t('refresh'),refreshRuns));host.append(actions);
        const statements=el('details');statements.append(el('summary',t('additions')));revision.statements.forEach(s=>{
            const item=el('div',undefined,'border rounded p-3 my-2');item.id='statement-'+s.id;item.tabIndex=-1;
            item.append(el('strong',t(s.provenance)+(s.editingOrigin==='HUMAN'?' · '+t('HUMAN_DECISION'):'')),pre(s.wording));
            if(s.reviewState==='REJECTED')item.append(el('p',t('rejected'),'text-danger'));
            s.architectureLinks.forEach(id=>item.append(architectureLink(id)));
            s.questionDependencies.forEach(id=>{const a=el('a',t('questions')+' '+id,'me-2');a.href='#question-'+id;a.addEventListener('click',()=>changeView('questions',false));item.append(a);});
            const edit=el('textarea',undefined,'form-control my-2');edit.setAttribute('aria-label',t('edit')+' '+s.id);edit.value=s.wording;item.append(edit);
            const draftKey='statement:'+s.id;
            trackDraft(draftKey,[edit],()=>({text:edit.value}),draft=>{edit.value=draft.text;});
            if(drafts.has(draftKey))statements.open=true;
            item.append(button(t('edit'),()=>update('statements/'+s.id,{action:'EDIT',text:edit.value,rationale:rationale()},draftKey)));
            if(s.provenance!=='ORIGINAL' && s.reviewState!=='REJECTED')item.append(button(t('reject'),()=>update('statements/'+s.id,{action:'REJECT',rationale:rationale()}),'btn btn-sm btn-outline-danger ms-2'));
            statements.append(item);
        });host.append(statements);
        const findings=el('details');findings.append(el('summary',t('findings')));revision.validation.findings.forEach(f=>findings.append(el('p',f.code+': '+f.message,'text-break')));host.append(findings);
        const runs=el('div');runs.id='reformulationRuns';host.append(runs);const comparison=el('div');comparison.id='reformulationComparison';host.append(comparison);renderProgress();changeView(activeView,false);
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
        const draftKey='question:'+q.id;
        const readAnswer=()=>({values:inputs.filter(i=>!['radio','checkbox'].includes(i.type)||i.checked).map(i=>i.value),otherText:other.value,rationale:reason.value});
        trackDraft(draftKey,inputs.concat(other,reason),readAnswer,draft=>{
            inputs.forEach(input=>{if(['radio','checkbox'].includes(input.type))input.checked=draft.values.includes(input.value);else input.value=draft.values[0] ?? '';});
            other.value=draft.otherText;reason.value=draft.rationale;
        });
        if(drafts.has(draftKey) && answerHost!==box)answerHost.open=true;
        const answer=button(t('answer'),()=>update('answers',{questionId:q.id,action:'ANSWER',...readAnswer()},draftKey));answer.disabled=!applicable(q);controls.append(answer);
        ['DEFER','NOT_APPLICABLE'].forEach(action=>controls.append(button(t(action==='DEFER'?'defer':'na'),()=>update('answers',{questionId:q.id,action,values:[],otherText:'',rationale:reason.value}))));answerHost.append(controls);
        if(schema.applicability?.length)answerHost.append(el('p',t('applicable')+': '+schema.applicability.map(c=>c.questionId+' = '+c.anyOf.join(' / ')).join('; ')+(applicable(q)?'':' — '+t('unavailable')),'small'));
        const history=el('details');history.append(el('summary',t('history')));offer.currentRevision.answers.filter(a=>refs(q).includes(a.questionId)).forEach(a=>history.append(el('p',a.author+' · '+a.occurredAt+' · '+t(a.state)+' · '+a.values.join(', ')+' '+(a.otherText || '')+' — '+a.rationale)));box.append(history);return box;
    }
    function showComparison(before,after,title) {
        const target=document.getElementById('reformulationComparison');target.replaceChildren(el('h3',title,'h5 mt-3'));
        const grid=el('div',undefined,'reformulation-grid');[[t('current'),before],[t('candidateText'),after]].forEach(([label,text])=>{const col=el('div',undefined,'border rounded p-3');col.append(el('h4',label,'h6'),pre(text));grid.append(col);});target.append(grid);target.tabIndex=-1;target.focus();
    }
    // Read-only progress state is independent of editable proposal/question drafts.
    let progressView = null, progressGeneration = 0;
    const progressCurrent = (view, generation) => progressView===view && progressGeneration===generation && offer?.id===view.offerId;
    async function showProgress(runId) {
        progressView = {offerId:offer.id, runId, items:new Map(), data:null, detail:null, detailGeneration:0, loading:false};
        ++progressGeneration;const selectedView=progressView;renderProgress();
        await refreshProgress();
        if(progressView!==selectedView || offer?.id!==selectedView.offerId)return;
        announce(t('progress')+(hasDrafts()?' '+t('dirty'):''));
        const panel=document.getElementById('reformulationProgress');
        if(panel){panel.tabIndex=-1;panel.focus();}
    }
    async function refreshProgress(after) {
        const view=progressView, generation=progressGeneration;
        if(!view || view.offerId!==offer?.id || view.loading)return;
        view.loading=true;let changed=false;
        try {
            const data=await api.getReformulationProgress(project,requirement,view.offerId,view.runId,after);
            if(!progressCurrent(view,generation))return;
            if(data.runId!==view.runId)throw new Error('Unexpected progress run');
            changed=JSON.stringify(view.data)!==JSON.stringify(data) || !!view.error;
            view.data=data;
            data.items.forEach(item=>view.items.set(item.checkpointId,item));
            view.error=null;
            try {
                const usage=await api.getReformulationUsage(project,requirement,view.offerId,view.runId);
                if(!progressCurrent(view,generation))return;
                if(usage.runId!==view.runId)throw new Error('Unexpected usage run');
                changed=changed || JSON.stringify(view.usage)!==JSON.stringify(usage) || !!view.usageError;
                view.usage=usage;view.usageError=null;
            } catch(error) {
                if(progressCurrent(view,generation)){changed=true;view.usageError=error.message;}
            }
        } catch(error) {
            if(progressCurrent(view,generation)){changed=true;view.error=error.message;}
        } finally {
            view.loading=false;
            if(progressCurrent(view,generation) && changed)renderProgress();
        }
    }
    async function showPartial(checkpointId) {
        const view=progressView, generation=progressGeneration;
        if(!view)return;
        const detailGeneration=++view.detailGeneration;
        view.detail=null;view.detailLoading=true;view.error=null;renderProgress();
        try {
            const result=await api.getReformulationPartial(project,requirement,view.offerId,view.runId,checkpointId);
            if(!progressCurrent(view,generation) || view.detailGeneration!==detailGeneration)return;
            if(result.runId!==view.runId || result.checkpointId!==checkpointId)throw new Error('Unexpected partial result');
            view.detail=result;
        } catch(error) {
            if(progressCurrent(view,generation) && view.detailGeneration===detailGeneration)view.error=error.message;
        } finally {
            if(progressCurrent(view,generation) && view.detailGeneration===detailGeneration){view.detailLoading=false;renderProgress();announce(t('progress')+(hasDrafts()?' '+t('dirty'):''));}
        }
    }
    function renderUsage(view,panel) {
        const box=el('section',undefined,'border rounded p-2 my-2');box.dataset.reformulationUsage='';
        box.append(el('h4',t('usage'),'h6'));
        if(view.usageError)box.append(el('p',t('failed')+view.usageError,'text-danger'));
        const usage=view.usage;
        if(!usage || !usage.recorded)box.append(el('p',usage?t('usageUnrecorded'):t('loading')));
        else {
            if(!usage.fromFirstAttempt)box.append(el('p',t('usageLate'),'alert alert-warning'));
            for(const key of ['httpAttempts','replays','pendingAttempts','retries','httpErrors','transportErrors','invalidUsage'])box.append(el('p',t(key)+': '+usage[key],'small mb-1'));
            const list=el('dl',undefined,'small');
            for(const key of ['inputTokens','outputTokens','totalTokens','cachedInputTokens','reasoningTokens']) {
                const value=usage[key];
                list.append(el('dt',t(key)),el('dd',(value.reported===null?'—':value.reported)+' ('+value.reports+' '+t('reports')+', '+value.unknown+' '+t('unknownUsage')+')'));
            }
            box.append(list,el('p',t('usageWarning'),'small text-body-secondary'));
        }
        panel.append(box);
    }
    function renderProgress() {
        let panel=document.getElementById('reformulationProgress');
        if(!progressView || progressView.offerId!==offer?.id){if(panel)panel.remove();return;}
        if(!panel){panel=el('section',undefined,'border rounded p-3 my-3 text-break');panel.id='reformulationProgress';host.append(panel);}
        const view=progressView, data=view.data;
        panel.setAttribute('aria-label',t('progress'));
        panel.replaceChildren(el('h3',t('progress'),'h5'),el('p',t('partialWarning'),'alert alert-warning'));
        if(data){
            panel.append(el('p',t('source')+': '+data.sourceVersionId+' · '+t('revision')+': '+data.sourceRevision+' · '+data.status,'small'),
                el('p',t('storedRun')+': '+data.runCheckpointCount+' · '+t('storedOffer')+': '+data.proposalCheckpointCount),
                el('p',t('countWarning'),'small text-body-secondary'),
                el('p',t('createdAt')+': '+data.createdAt+(data.lastCheckpointAt?' · '+t('lastCheckpoint')+': '+data.lastCheckpointAt:''),'small text-break'));
        }
        renderUsage(view,panel);
        const controls=el('div',undefined,'reformulation-controls');
        controls.append(button(t('refresh'),async()=>{await refreshProgress();announce(t('progress')+(hasDrafts()?' '+t('dirty'):''));}),button(t('closeProgress'),()=>{progressView=null;++progressGeneration;renderProgress();announce(t('state')+(hasDrafts()?' '+t('dirty'):''));}));
        panel.append(controls);
        if(view.error){const error=el('p',t('failed')+view.error,'alert alert-danger');error.setAttribute('role','alert');panel.append(error);}
        const items=[...view.items.values()].sort((a,b)=>a.createdAt.localeCompare(b.createdAt) || a.checkpointId.localeCompare(b.checkpointId));
        const list=el('ol');
        for(const item of items){
            const row=el('li',undefined,'my-1');
            const open=button(t('inspect')+' · '+item.kind+' · '+item.createdAt,()=>showPartial(item.checkpointId));
            open.dataset.progressCheckpoint=item.checkpointId;row.append(open);list.append(row);
        }
        panel.append(list);
        if(!items.length && !view.error)panel.append(el('p',!data || view.loading?t('loading'):t('noResults')));
        if(data && items.length<data.runCheckpointCount){
            const more=button(t('moreResults'),()=>refreshProgress(items.at(-1)?.checkpointId));more.disabled=view.loading;panel.append(more);
        }
        if(view.detailLoading)panel.append(el('p',t('loading')));
        const detail=view.detail;
        if(!detail)return;
        const result=el('section',undefined,'border rounded p-3 mt-3');result.dataset.partialCheckpoint=detail.checkpointId;
        result.append(el('h4',t('inspect')+' · '+detail.kind,'h6'),el('p',t('partialWarning'),'small'));
        const wrapped=text=>{const p=pre(text);p.style.whiteSpace='pre-wrap';p.style.overflowWrap='anywhere';return p;};
        if(detail.node){
            const node=detail.node;
            result.append(el('p',node.nodeId,'fw-semibold'),wrapped(node.summary));
            node.statementProposals.forEach(s=>result.append(el('strong',t(s.provenance)),wrapped(s.wording)));
            node.questionProposals.forEach(q=>{
                const question=el('section',undefined,'border rounded p-2 my-2');question.append(el('h5',q.wording,'h6'),el('p',t(q.state)),
                    el('p',q.answerSchema.options.join(' / ')));
                const evidence=el('details');evidence.append(el('summary',t('architecture')),wrapped(JSON.stringify(q,null,2)));question.append(evidence);result.append(question);
            });
            const retained=el('details');retained.append(el('summary',t('retained')),wrapped(node.preservedStatementIds.concat(node.preservedQuestionIds).join('\n')));result.append(retained);
            node.conflictCandidates.forEach(f=>result.append(el('p',f.code+': '+f.message,'text-danger')));
            node.uncoveredSourceRefs.forEach(span=>result.append(wrapped(span.exactText)));
        } else if(detail.reconciliation){
            result.append(wrapped(JSON.stringify(detail.reconciliation,null,2)));
        }
        panel.append(result);
    }
    async function refreshRuns() {
        clearTimeout(timer);if(!offer)return;
        if(mutationInFlight){timer=setTimeout(()=>perform(refreshRuns),1500);return;}
        const selected=offer.id, generation=++readGeneration;
        const runs=await api.listReformulationRuns(project,requirement,selected);
        if(offer.id!==selected || generation!==readGeneration)return;
        const previousVersion=currentRequirement?.currentVersionId;
        const [latest, active]=await Promise.all([api.getReformulation(project,requirement,selected),api.getRequirement(project,requirement)]);
        if(offer.id!==selected || generation!==readGeneration)return;currentRequirement=active;
        const advanced=latest.currentRevision.number>offer.currentRevision.number;
        if(advanced)offer=latest;
        if(advanced || previousVersion!==active.currentVersionId)render();
        const target=document.getElementById('reformulationRuns');target.replaceChildren();
        const activeRun = run => ['QUEUED','RUNNING'].includes(run.status);
        const generate = document.getElementById('reformulationStartRun');
        if(generate)generate.disabled=runs.some(activeRun);
        // Older releases allowed concurrent runs. Keep all active runs reachable, not just the latest three.
        runs.filter((run,index)=>activeRun(run) || index>=runs.length-3).forEach(run=>{
            target.append(el('p',run.status+(run.failureCode?' · '+run.failureCode:''),'small text-break'));
            const inspect=button(t('progress'),()=>showProgress(run.id));inspect.dataset.reformulationProgressOpen=run.id;target.append(inspect);
            if(activeRun(run))target.append(button(t('cancelRun'),async()=>{
                if(mutationInFlight)throw new Error(t('working'));
                mutationInFlight=true;++readGeneration;
                try {await api.updateReformulation(project,requirement,selected,'synthesis-runs/'+encodeURIComponent(run.id)+'/cancel',offer.currentRevision.number,{});}
                finally {mutationInFlight=false;++readGeneration;}
                await refreshRuns();
            },'btn btn-sm btn-outline-danger'));

            if(run.status==='PARTIAL' && run.candidate){const candidate=el('section',undefined,'reformulation-candidate my-2');candidate.dataset.reformulationCandidate='';candidate.append(el('h3',t('candidate'),'h5'),button(t('compare'),()=>showComparison(offer.currentRevision.text,run.candidate.text,t('candidate'))));target.append(candidate);}
        });
        await refreshProgress();
        if(offer.id!==selected || generation!==readGeneration)return;
        if(runs.length)target.append(el('p',t('retryHint'),'small'));
        if(runs.some(activeRun)){announce(t('working'));timer=setTimeout(()=>perform(refreshRuns),1500);}
        else announce(t('state')+(hasDrafts()?' '+t('dirty'):''));
    }
    async function start() {
        document.getElementById('reformulationHeading').textContent=t('heading');announce(t('loading'));
        const snapshots=await api.listRequirementSnapshots(project,requirement);currentRequirement=await api.getRequirement(project,requirement);
        const start=document.getElementById('reformulationStart'),select=el('select',undefined,'form-select');select.id='reformulationSnapshot';
        snapshots.forEach(snapshot=>{const option=el('option','v'+snapshot.requirementVersionNumber+' · '+snapshot.id+' · '+snapshot.status);option.value=snapshot.id;select.append(option);});
        const requested=new URLSearchParams(location.search).get('snapshot');if(snapshots.some(s=>s.id===requested))select.value=requested;
        start.append(field(t('snapshot'),select),button(t('create'),()=>transition(()=>{
            const snapshot=snapshots.find(s=>s.id===select.value);if(!snapshot)throw new Error(t('noSnapshot'));
            return api.createReformulation(project,requirement,{sourceVersionId:snapshot.requirementVersionId,snapshotId:snapshot.id,language:lang});
        })));
        offers=await api.listReformulations(project,requirement);
        const selected=offers.find(p=>p.id===new URLSearchParams(location.search).get('proposal')) || offers[0];
        offer=selected ? await api.getReformulation(project,requirement,selected.id) : undefined;
        render();if(offer)await refreshRuns();else announce(t('empty'));
    }
    window.addEventListener('beforeunload',event=>{if(hasDrafts() || mutationInFlight){event.preventDefault();event.returnValue='';}});
    perform(start);
}());
