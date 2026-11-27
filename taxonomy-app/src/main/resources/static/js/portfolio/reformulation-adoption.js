/* Two-stage adoption. No input is copied into the active requirement until a confirmed server command. */
window.TaxonomyReformulationAdoption = (function () {
    'use strict';
    let active;
    const messages = {
        de: {
            title:'Neuformulierungsangebot übernehmen', loading:'Vorschau wird gespeichert und geladen…', close:'Schließen',
            original:'Ursprüngliche Quellversion', current:'Aktuell aktive Anforderung', proposed:'Genau dieser Text wird übernommen',
            questions:'Fragen und Entscheidungsbedarf', evidence:'Aussagen, Antworten und Herkunft', warnings:'Vor Übernahme prüfen',
            confirm:'Als Anforderungsentwurf übernehmen', confirmed:'Ich bestätige diesen Text ausdrücklich für den aktiven Anforderungsstand. Geänderter Text wird als ungeprüfter Entwurf übernommen.',
            acknowledge:'Ich habe die offenen Fragen, Ergänzungen und Hinweise geprüft. Sie bleiben als Übernahmeevidenz erhalten.',
            rationale:'Begründung der Übernahme', notice:'Erst die ausdrückliche Bestätigung ändert den aktiven Textstand. Frühere Versionen und Analysen bleiben erhalten. Die Architektur wird nicht automatisch geändert oder neu analysiert. Ein bereits identischer aktiver Text und sein Status bleiben unverändert.',
            invalid:'Der Vorschlag enthält strukturelle Fehler. Übernahme ist gesperrt.', stale:'Der Text- oder Entscheidungsstand hat sich geändert. Neue Vorschau erforderlich.',
            adopted:'Übernahme dokumentiert. Zielversion: ', analysis:'Für den geänderten Text ist eine neue Analyse erforderlich.',
            history:'Bisherige Übernahmen dieses Angebots', none:'Keine Übernahmebelege vorhanden.', failed:'Aktion fehlgeschlagen: ',
            uncertain:'Die Antwort ist unklar. Ein erneuter Klick wiederholt denselben Befehl, nicht den Versionswechsel. Nach dem Schließen sind Belege unter „Bisherige Übernahmen“ einsehbar.',
            pending:'Übernahme wird verarbeitet. Schließen macht einen bereits gesendeten Befehl nicht rückgängig.',
            UNRESOLVED_QUESTIONS:'Entscheidungsfragen sind noch offen.', REVIEW_PENDING_EDITS_AND_DECISIONS:'Textbearbeitungen oder Entscheidungen benötigen noch Abgleich.',
            SOURCE_DIFFERS_FROM_CURRENT:'Die Quelle dieses Angebots unterscheidet sich von der aktuell aktiven Version.', EXTERIOR_WHITESPACE_NORMALIZED:'Äußerer Leerraum wird wie bei der vorhandenen Versionierung entfernt; die Vorschau zeigt den endgültigen Text.',
            ADDITIONS_REQUIRE_REVIEW:'Der Vorschlag enthält zusätzliche oder bearbeitete Aussagen.',
            OPEN:'Offen', ANSWERED:'Beantwortet', DEFERRED:'Zurückgestellt', NOT_APPLICABLE:'Nicht anwendbar', CONFLICT:'Konflikt',
            NOT_SYNTHESIZED:'Die Quellabdeckung wurde noch nicht ausgewertet.',
            SOURCE_ANSWER_CONFLICT:'Original und menschliche Antwort widersprechen sich.',
            STALE_FOLLOW_UP_CONTEXT:'Der Entscheidungskontext einer Folgefrage hat sich geändert.',
            STATEMENT_TEXT_CONFLICT:'Die Bearbeitung widerspricht einer erhaltenen Aussage.',
            SOURCE_FORMULATION_REVIEW:'Die Formulierung aus der Quelle benötigt fachliche Prüfung.',
            GROUPED_SYNTHESIS:'Zusammengefasste Teilergebnisse benötigen fachliche Prüfung.',
            STATEMENT_CHANGED_OR_LOST:'Eine zuvor eingefrorene Aussage wurde geändert oder fehlt.',
            INVALID_SOURCE_SPAN:'Ein Quellenverweis passt nicht zum eingefrorenen Original.',
            UNCONFIRMED_ADDITION:'Eine zusätzliche Aussage ist noch nicht bestätigt.',
            ORIGINAL_NOT_VISIBLE:'Teile des Originals sind im Vorschlag nicht mehr sichtbar.',
            SEMANTIC_REVIEW_REQUIRED:'Eine fachliche Bedeutungsprüfung ist erforderlich.',
            MODEL_UNCOVERED:'Ein Quellabschnitt bleibt für die Prüfung unzugeordnet.',
            NO_TAXONOMY_MATCH:'Für die Quelle wurde kein Taxonomiebezug festgestellt.',
            UNREVIEWED_GENERATION:'Das erzeugte Angebot wurde noch nicht fachlich geprüft.',
            DUPLICATE_STATEMENT:'Eine Aussage ist mehrfach vorhanden.',
            DUPLICATE_QUESTION_REFERENCE:'Ein Fragenverweis ist mehrfach vorhanden.',
            DUPLICATE_SECTION:'Ein Abschnitt ist mehrfach vorhanden.',
            UNKNOWN_QUESTION_REFERENCE:'Eine Aussage verweist auf eine unbekannte Frage.',
            UNKNOWN_DECISION_REFERENCE:'Eine Entscheidung verweist auf unbekannte Inhalte.',
            UNKNOWN_SECTION_REFERENCE:'Ein Abschnitt verweist auf unbekannte Inhalte.',
            UNKNOWN_ANSWER_REFERENCE:'Eine Antwort verweist auf eine unbekannte Frage.',
            REJECTED_ADDITION_REINTRODUCED:'Eine zuvor abgelehnte Ergänzung erscheint erneut.',
            RECONCILED_UNCOVERED:'Ein Originalabschnitt bleibt nach dem Abgleich für die Prüfung unzugeordnet.',
            RECONCILIATION_LIMIT:'Zwei Abgleichrunden sind ausgeschöpft. Verbleibende Unterschiede benötigen fachliche Prüfung.',
            INCOMPATIBLE_ANSWER_CONTRACTS:'Fragen mit gleicher Bedeutung haben unterschiedliche Antwortvorgaben und bleiben getrennt.',
            CONFLICTING_ANSWERS:'Widersprüchliche Antworten oder Quellen bleiben zur Klärung erhalten.',
            ORIGINAL_REMAINDER:'Originalbedingungen bleiben unabhängig von Architekturwertungen sichtbar erhalten.',
            BOUNDED_RECONCILIATION:'Der Abgleich wurde in begrenzten Gruppen ausgeführt. Nicht gemeinsam geprüfte Bezüge benötigen fachliche Prüfung.'
        },
        en: {
            title:'Adopt reformulation offer', loading:'Saving and loading the exact preview…', close:'Close',
            original:'Original source version', current:'Currently active requirement', proposed:'Exactly this text will be adopted',
            questions:'Questions and decisions', evidence:'Statements, answers and provenance', warnings:'Review before adoption',
            confirm:'Adopt as requirement draft', confirmed:'I explicitly confirm this text for the active requirement. Changed text is adopted as an unreviewed draft.',
            acknowledge:'I reviewed the open questions, additions and warnings. They remain part of the adoption evidence.',
            rationale:'Adoption rationale', notice:'Only explicit confirmation changes the active text version. Earlier versions and analyses remain available. Architecture is not changed or reanalysed automatically. Identical active text and its status remain unchanged.',
            invalid:'The proposal has structural errors. Adoption is blocked.', stale:'Text or decisions changed. Create a new preview.',
            adopted:'Adoption recorded. Target version: ', analysis:'The changed text needs a new analysis.', history:'Previous adoptions of this offer', none:'No adoption receipts yet.',
            failed:'Action failed: ', uncertain:'The response is uncertain. Clicking again retries the same command, not the version transition. After closing, inspect Previous adoptions for receipts.',
            pending:'Adoption is being processed. Closing does not undo an already submitted command.',
            UNRESOLVED_QUESTIONS:'Decision questions remain open.', REVIEW_PENDING_EDITS_AND_DECISIONS:'Text edits or decisions still need reconciliation.',
            SOURCE_DIFFERS_FROM_CURRENT:'The offer source differs from the currently active requirement version.', EXTERIOR_WHITESPACE_NORMALIZED:'Exterior whitespace is removed by existing versioning; this preview shows the exact final text.',
            ADDITIONS_REQUIRE_REVIEW:'This proposal contains added or edited statements.',
            OPEN:'Open', ANSWERED:'Answered', DEFERRED:'Deferred', NOT_APPLICABLE:'Not applicable', CONFLICT:'Conflict',
            NOT_SYNTHESIZED:'Source coverage has not been evaluated.',
            SOURCE_ANSWER_CONFLICT:'The original and the human answer conflict.',
            STALE_FOLLOW_UP_CONTEXT:'The decision context of a follow-up question has changed.',
            STATEMENT_TEXT_CONFLICT:'The edit conflicts with a retained statement.',
            SOURCE_FORMULATION_REVIEW:'Source wording needs expert review.',
            GROUPED_SYNTHESIS:'Combined partial results need expert review.',
            STATEMENT_CHANGED_OR_LOST:'A previously frozen statement changed or is missing.',
            INVALID_SOURCE_SPAN:'A source reference does not match the frozen original.',
            UNCONFIRMED_ADDITION:'An added statement has not been confirmed.',
            ORIGINAL_NOT_VISIBLE:'Parts of the original are no longer visible in the proposal.',
            SEMANTIC_REVIEW_REQUIRED:'Expert semantic review is required.',
            MODEL_UNCOVERED:'A source passage remains unmapped for review.',
            NO_TAXONOMY_MATCH:'No taxonomy reference was found for the source.',
            UNREVIEWED_GENERATION:'The generated offer has not received expert review.',
            DUPLICATE_STATEMENT:'A statement occurs more than once.',
            DUPLICATE_QUESTION_REFERENCE:'A question reference occurs more than once.',
            DUPLICATE_SECTION:'A section occurs more than once.',
            UNKNOWN_QUESTION_REFERENCE:'A statement refers to an unknown question.',
            UNKNOWN_DECISION_REFERENCE:'A decision refers to unknown content.',
            UNKNOWN_SECTION_REFERENCE:'A section refers to unknown content.',
            UNKNOWN_ANSWER_REFERENCE:'An answer refers to an unknown question.',
            REJECTED_ADDITION_REINTRODUCED:'A previously rejected addition has reappeared.',
            RECONCILED_UNCOVERED:'An original passage remains unmapped for review after reconciliation.',
            RECONCILIATION_LIMIT:'Two reconciliation rounds are exhausted. Remaining differences need expert review.',
            INCOMPATIBLE_ANSWER_CONTRACTS:'Questions with the same meaning have different answer requirements and remain separate.',
            CONFLICTING_ANSWERS:'Conflicting answers or source evidence remain available for clarification.',
            ORIGINAL_REMAINDER:'Original conditions remain visible independently of architecture scores.',
            BOUNDED_RECONCILIATION:'Reconciliation ran in bounded groups. References not examined together need expert review.'
        }
    };
    const element=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
    const displayCode=(words,code)=>Object.hasOwn(words,code)?words[code]:code;
    function open(options) {
        if(!options.isCurrent())throw new Error(messages[options.language==='de'?'de':'en'].stale);
        if(active)active.dialog.close();
        const words=messages[options.language==='de'?'de':'en'];
        const dialog=element('dialog');dialog.className='reformulation-adoption-dialog';dialog.setAttribute('aria-label',words.title);
        dialog.setAttribute('aria-modal','true');
        const header=element('header');header.className='reformulation-adoption-header';
        const body=element('div');body.className='reformulation-adoption-body';
        const state={dialog,body,options,words,pending:false,request:null,done:false};active=state;
        dialog.addEventListener('close',()=>{if(active===state)active=null;dialog.remove();});
        const status=element('p',words.loading);status.setAttribute('role','status');state.status=status;
        const close=element('button',words.close);close.type='button';close.className='btn btn-outline-secondary btn-sm';close.dataset.adoptionAction='close';close.addEventListener('click',()=>dialog.close());
        const heading=element('h2',words.title);heading.className='h4 mb-0';header.append(heading,close);
        body.append(element('p',words.notice),status);dialog.append(header,body);document.body.append(dialog);dialog.showModal();
        load(state); // Loading only stores a preview; no requirement write.
    }
    function visible(state){return active===state && state.dialog.open;}
    async function load(state) {
        const {options,words,body:dialog}=state,api=window.TaxonomyPortfolioApi;
        try {
            const preview=await api.createReformulationAdoptionPreview(options.projectId,options.requirementId,options.proposalId,options.revision);
            if(!visible(state))return;
            if(!options.isCurrent()){state.status.textContent=words.stale;return;}
            const data=preview.content;
            if(data.proposalId!==options.proposalId || data.revision.number!==options.revision)throw new Error(words.stale);
            state.preview=preview;state.status.textContent='';
            const grid=element('div');grid.className='reformulation-adoption-comparison';
            for(const [title,text] of [[words.original+' #'+data.sourceVersionId,data.originalText],
                [words.current+' #'+data.currentRequirement.currentVersionId,data.currentRequirement.currentVersion.text],
                [words.proposed,data.finalText]]) {
                const section=element('section');section.append(element('h3',title),element('pre',text));grid.append(section);
            }
            dialog.append(grid,element('h3',words.questions));
            for(const q of data.revision.questions) {
                const box=element('details');box.open=true;box.append(element('summary',q.wording+' ['+displayCode(words,q.state)+']'));
                if(q.answerSchema.options?.length)box.append(element('p',q.answerSchema.options.join(' / ')));
                for(const d of q.discoveries||[])box.append(element('p',d.rationale));
                dialog.append(box);
            }
            const evidence=element('details');evidence.append(element('summary',words.evidence),
                element('pre',JSON.stringify({sections:data.revision.sections,statements:data.revision.statements,answers:data.revision.answers,
                    questions:data.revision.questions,findings:data.revision.validation},null,2)));dialog.append(evidence);
            if(data.warnings.length){dialog.append(element('h3',words.warnings));data.warnings.forEach(w=>dialog.append(element('p',displayCode(words,w))));}
            if(data.blockingReasons.length){dialog.append(element('p',words.invalid),element('pre',data.blockingReasons.map(code=>displayCode(words,code)).join('\n')));}
            const acknowledged=checkBox(dialog,words.acknowledge,'warnings');acknowledged.checked=false;
            const confirmed=checkBox(dialog,words.confirmed,'confirmed');confirmed.checked=false;
            const reason=element('input');reason.className='form-control';reason.dataset.adoptionAction='rationale';reason.maxLength=1000;
            const label=element('label',words.rationale);label.append(reason);dialog.append(label);
            const submit=element('button',words.confirm);submit.type='button';submit.className='btn btn-primary';submit.dataset.adoptionAction='confirm';dialog.append(submit);
            Object.assign(state,{acknowledged,confirmed,reason,submit});
            const gate=()=>{submit.disabled=state.pending || state.done || data.blockingReasons.length>0 || !options.isCurrent()
                || !confirmed.checked || (data.warnings.length>0 && !acknowledged.checked) || !reason.value.trim();};
            [confirmed,acknowledged,reason].forEach(n=>{n.addEventListener('change',gate);n.addEventListener('input',gate);});
            submit.addEventListener('click',()=>confirm(state));gate();
            const history=element('details');history.append(element('summary',words.history));dialog.append(history);
            try {
                const receipts=await api.listReformulationAdoptions(options.projectId,options.requirementId,options.proposalId);
                if(!visible(state))return;
                history.append(element('p',receipts.length?'':words.none));
                receipts.forEach(r=>{
                    history.append(element('p','#'+r.targetVersionId+' · '+r.actor+' · '+r.adoptedAt+' — '+r.rationale));
                    if(window.TaxonomyReformulationReports) history.append(window.TaxonomyReformulationReports.controls({
                        projectId:options.projectId,requirementId:options.requirementId,proposalId:options.proposalId,
                        commandId:r.commandId,language:options.language
                    }));
                });
            } catch(error){if(visible(state))history.append(element('p',words.failed+error.message));}
        } catch(error){if(visible(state))state.status.textContent=words.failed+error.message;}
    }
    function checkBox(host,text,action) {
        const input=element('input');input.type='checkbox';input.className='form-check-input';input.dataset.adoptionAction=action;
        const label=element('label',text);label.append(input);host.append(label);return input;
    }
    async function confirm(state) {
        const {options,preview,words}=state;
        if(state.pending || state.done || !visible(state))return;
        if(!options.isCurrent()){state.submit.disabled=true;state.status.textContent=words.stale;return;}
        if(preview.content.blockingReasons.length || !state.confirmed.checked ||
                (preview.content.warnings.length && !state.acknowledged.checked) || !state.reason.value.trim())return;
        // Keep this immutable command for an uncertain transport retry; never create another UUID then.
        state.request ||= {commandId:crypto.randomUUID(),previewId:preview.content.id,previewHash:preview.hash,
            confirmed:true,acknowledgeWarnings:state.acknowledged.checked,rationale:state.reason.value};
        state.pending=true;state.submit.disabled=true;state.status.textContent=words.pending;
        state.reason.disabled=true;state.confirmed.disabled=true;state.acknowledged.disabled=true;
        try {
            const result=await window.TaxonomyPortfolioApi.confirmReformulationAdoption(options.projectId,options.requirementId,options.proposalId,options.revision,state.request);
            state.done=true;
            if(visible(state))state.status.textContent=words.adopted+result.targetVersionId+(result.analysisNeedsRefresh?' '+words.analysis:'');
            // This notification must never reload the page or discard another currently edited offer.
            try {
                if(options.onAdopted)await options.onAdopted(result);
            } finally {
                // A failed offer refresh cannot suppress the independent active-detail refresh.
                if(window.TaxonomyRequirementDetail)await window.TaxonomyRequirementDetail.refreshAfterAdoption();
            }
        } catch(error) {
            if(visible(state)){
                if(state.done){state.status.textContent+=' '+words.failed+error.message;return;}
                const stale=[409,412,422].includes(error.status);
                state.status.textContent=words.failed+error.message+' '+(stale?words.stale:words.uncertain);
                state.submit.disabled=stale;
            }
        } finally {state.pending=false;}
    }
    return {open};
}());
