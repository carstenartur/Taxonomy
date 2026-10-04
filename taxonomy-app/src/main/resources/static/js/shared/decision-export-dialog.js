/* One source-bound export dialog for current analyses and immutable snapshots. */
(function () {
    'use strict';
    const presets = {
        COMPACT: ['SUMMARY', 'TREE'],
        STANDARD: ['SUMMARY', 'TREE', 'CHAPTERS', 'ARCHITECTURE'],
        FULL: ['TITLE_PAGE', 'SUMMARY', 'TREE', 'CHAPTERS', 'ARCHITECTURE', 'EVIDENCE']
    };
    const types = {docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', html: 'text/html', json: 'application/json'};
    let active = null;
    function selection(input) {
        const roots = input.selectedRoots || [];
        const available = new Set((input.roots || []).filter(r => r.inAnalysisScope !== false).map(r => r.code));
        if (!roots.length || roots.some(code => !available.has(code))) throw new Error('Select at least one available taxonomy.');
        return {profile: input.profile || 'COMPACT', taxonomyRoots: [...new Set(roots)],
            contents: input.contents || 'SHORT', treeLayout: input.treeLayout || 'AUTO',
            sections: input.sections || presets[input.profile || 'COMPACT']};
    }
    function query(options) {
        const params = new URLSearchParams();
        Object.entries(options || {}).forEach(([key, value]) => {
            if (Array.isArray(value)) value.forEach(item => params.append(key, item));
            else if (value != null) params.set(key, value);
        });
        return params.toString();
    }
    async function download(response, format, snapshotId) {
        if (!response.ok) {
            const problem = await response.json().catch(() => null);
            throw new Error(problem?.detail || problem?.message || ('HTTP ' + response.status));
        }
        const type = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
        if (response.redirected || type !== types[format] || !response.headers.get('X-Taxonomy-Analysis-SHA256'))
            throw new Error('The response is not a decision report. Please check your session and retry.');
        if (snapshotId && response.headers.get('X-Taxonomy-Snapshot-Id') !== snapshotId)
            throw new Error('The report does not belong to the selected snapshot.');
        const blob = await response.blob();
        if (!blob.size) throw new Error('The report is empty.');
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = 'taxonomy-decision-report' + (snapshotId ? '-' + snapshotId.replace(/[^A-Za-z0-9_-]/g, '_') : '') + '.' + format;
        document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    function el(tag, text, attrs) {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        Object.entries(attrs || {}).forEach(([key, value]) => node.setAttribute(key, value));
        return node;
    }
    function open(config) {
        if (active) { active.focus(); return; }
        const opener = document.activeElement;
        const de = String(config.language || document.documentElement.lang).startsWith('de');
        const t = (en, german) => de ? german : en;
        const dialog = el('dialog', null, {'aria-labelledby':'decision-export-title', class:'decision-export-dialog'});
        active = dialog;
        if (!document.getElementById('decision-export-style')) {
            const style = el('style', `
.decision-export-dialog{box-sizing:border-box;width:min(640px,calc(100vw - 24px));max-height:calc(100dvh - 32px);overflow:auto;border:1px solid #bcc8d2;border-radius:12px;padding:24px;color:var(--bs-body-color,#20313f);background:var(--bs-body-bg,#fff);box-shadow:0 20px 70px #0004}
.decision-export-dialog::backdrop{background:#0b1f3380}.decision-export-dialog h2{font-size:1.4rem;margin:0 0 8px}.decision-export-dialog p{font-size:.9rem}.decision-export-dialog label{display:block;margin:8px 0}.decision-export-dialog select{display:block;width:100%;padding:8px;margin-top:4px}.decision-export-dialog input{margin-right:8px}.decision-export-dialog fieldset{border:1px solid #cbd3da;border-radius:6px;padding:10px 14px;margin:12px 0}.decision-export-dialog legend{font-size:1rem;float:none;width:auto;padding:0 4px}.decision-export-dialog summary{cursor:pointer;padding:10px 0}.decision-export-dialog .export-actions{display:flex;gap:10px;justify-content:flex-end;margin-top:20px}.decision-export-dialog button{padding:9px 14px;border-radius:6px;border:1px solid #8296a5;cursor:pointer}.decision-export-dialog button[type=submit]{background:#006d77;color:white;border-color:#006d77}.decision-export-dialog button:disabled{opacity:.5;cursor:wait}.decision-export-dialog [role=alert]{color:var(--bs-danger-text-emphasis,#a51d2d)}.decision-export-dialog :focus-visible{outline:3px solid #329aa3;outline-offset:2px}.decision-export-dialog [hidden]{display:none!important}`,
            {id:'decision-export-style'}); document.head.appendChild(style);
        }
        dialog.append(el('h2', t('Decision report', 'Entscheidungsbericht'), {id:'decision-export-title'}));
        dialog.append(el('p', config.source || t('Current analysis', 'Aktuelle Analyse')));
        const form = el('form'); dialog.append(form);
        const fieldset = el('fieldset'); form.append(fieldset);
        function select(label, name, values, initial, parent = fieldset) {
            const wrapper = el('label', label);
            const control = el('select', null, {name});
            values.forEach(([value, text]) => { const option = el('option', text, {value}); control.append(option); });
            control.value = initial; wrapper.append(control); parent.append(wrapper); return control;
        }
        const format = select(t('Format', 'Format'), 'format', [['docx','Word (.docx)'],['html','HTML'],['json','JSON']], config.format || 'docx');
        const presentation = el('div'); fieldset.append(presentation);
        const profile = select(t('Content', 'Umfang'), 'profile', [['COMPACT',t('Compact','Kompakt')],['STANDARD',t('Standard','Standard')],['FULL',t('Full','Vollständig')]], 'COMPACT', presentation);
        const profileNote = el('p'); presentation.append(profileNote);
        const rootGroup = el('fieldset'); rootGroup.append(el('legend', t('Taxonomies in this report', 'Taxonomien im Bericht'))); fieldset.append(rootGroup);
        const sourceScope = el('p'); fieldset.append(sourceScope);
        const jsonNote = el('p', t('JSON includes the complete evidence for your selection; page layout does not apply.', 'JSON enthält die vollständigen Nachweise Ihrer Auswahl; ein Seitenlayout entfällt.')); fieldset.append(jsonNote);
        const advanced = el('details'); advanced.append(el('summary', t('More options','Weitere Optionen'))); fieldset.append(advanced);
        const contents = select(t('Contents','Inhaltsverzeichnis'),'contents', [['NONE',t('None','Keines')],['SHORT',t('Short — main sections','Kurz – Hauptabschnitte')],['FULL',t('Detailed','Ausführlich')]], 'SHORT', advanced);
        const layout = select(t('Decision tree','Entscheidungsbaum'), 'treeLayout', [['AUTO',t('Automatic (landscape)','Automatisch (Querformat)')],['A4_LANDSCAPE',t('A4 landscape — one page','A4 quer – eine Seite')],['A3_LANDSCAPE',t('A3 landscape — one page','A3 quer – eine Seite')],['TABLE',t('Table','Tabelle')]], 'AUTO', advanced);
        advanced.append(el('p', t('Automatic uses A4, A3 or continuation pages. A fixed page size reports when the complete tree cannot fit legibly.', 'Automatisch nutzt A4, A3 oder Folgeseiten. Bei fester Seitengröße erhalten Sie einen Hinweis, falls der vollständige Baum nicht lesbar hineinpasst.')));
        const sectionGroup = el('fieldset'); sectionGroup.append(el('legend',t('Sections','Abschnitte'))); advanced.append(sectionGroup);
        const sectionNames = {TITLE_PAGE:t('Cover','Deckblatt'),SUMMARY:t('Summary','Zusammenfassung'),TREE:t('Complete tree','Vollständiger Baum'),CHAPTERS:t('Decision reasoning','Entscheidungsbegründungen'),ARCHITECTURE:t('Saved architecture and boundary context','Gespeicherte Architektur und Kontext'),EVIDENCE:t('Detailed evidence','Ausführliche Nachweise')};
        const sections = Object.entries(sectionNames).filter(([key]) => key !== 'ARCHITECTURE' || config.saved).map(([key,title]) => {
            const label = el('label'); const input = el('input',null,{type:'checkbox',value:key,name:'section'});label.append(input,document.createTextNode(title));sectionGroup.append(label);return input;
        });
        function preset() {
            profileNote.textContent = {COMPACT:t('Summary and complete decision tree.','Zusammenfassung und vollständiger Entscheidungsbaum.'),STANDARD:t('Includes decision reasoning.','Mit Entscheidungsbegründungen.'),FULL:t('Includes cover and detailed evidence.','Mit Deckblatt und ausführlichen Nachweisen.')}[profile.value];
            sections.forEach(input => {input.checked = !input.disabled && presets[profile.value].includes(input.value);});
            contents.value = profile.value === 'FULL' ? 'FULL' : 'SHORT';
            layout.value = profile.value === 'FULL' ? 'TABLE' : 'AUTO';
        }
        function updateFormat() { const json = format.value === 'json'; presentation.hidden = json; advanced.hidden = json; jsonNote.hidden = !json; }
        profile.addEventListener('change',preset);format.addEventListener('change',updateFormat);preset();updateFormat();
        const error = el('p','', {role:'alert'}); form.append(error);
        const retry = el('button', t('Retry loading','Erneut laden'), {type:'button'});retry.hidden = true;form.append(retry);
        const actions = el('div',null,{class:'export-actions'}); form.append(actions);
        const cancel = el('button', t('Cancel','Abbrechen'), {type:'button'});
        const submit = el('button', t('Download','Herunterladen'), {type:'submit'});actions.append(cancel,submit);
        let roots = [], checks = [], busy = false, ready = false;
        async function load() {
            ready=false; submit.disabled=true; retry.hidden=true;error.textContent='';
            sourceScope.textContent=t('Loading saved scope…','Gespeicherten Umfang laden …');
            try {
                const data = config.load ? await config.load() : config;
                if (!dialog.open) return;
                roots = data.roots || [];
                const scope = data.analysisScope;
                sections.filter(input => input.value === 'ARCHITECTURE').forEach(input => {
                    input.disabled = scope?.mode === 'TAXONOMIES_ONLY';
                    if (input.disabled) input.checked = false;
                });
                roots = roots.map(root => ({...root, inAnalysisScope:root.inAnalysisScope !== false && (!scope?.taxonomyRoots?.length || scope.taxonomyRoots.includes(root.code))}));
                rootGroup.querySelectorAll('label').forEach(label => label.remove());
                const available = roots.filter(root => root.inAnalysisScope).map(root=>root.code);
                const requested = (config.selectedRoots || []).filter(code => available.includes(code));
                const chosen = requested.length ? requested : available;
                checks = roots.map(root => {
                    const label = el('label');const input=el('input',null,{type:'checkbox',name:'root',value:root.code});
                    input.checked=chosen.includes(root.code);input.disabled=!root.inAnalysisScope;
                    label.append(input,document.createTextNode(root.code+' · '+(root.title||root.name||root.code)+(input.disabled?t(' (outside analysis scope)',' (außerhalb des Analyseumfangs)'):'')));
                    rootGroup.append(label);return input;
                });
                sourceScope.textContent = scope ? t('Recorded analysis: ','Aufgezeichnete Analyse: ') + (scope.taxonomyRoots?.length ? scope.taxonomyRoots.join(', ') : t('all taxonomies','alle Taxonomien')) + ' · ' + (scope.mode === 'TAXONOMIES_ONLY' ? t('taxonomies only','nur Taxonomien') : t('with relations','mit Beziehungen'))
                    : t('The original scope was not recorded. Export selection does not change the analysis.', 'Der ursprüngliche Umfang wurde nicht aufgezeichnet. Die Exportauswahl verändert die Analyse nicht.');
                ready=available.length>0;submit.disabled=!ready;
                if (!ready) throw new Error(t('No reportable taxonomy is available.','Keine exportierbare Taxonomie verfügbar.'));
            } catch (err) { error.textContent=err.message;retry.hidden=false; }
        }
        retry.addEventListener('click',load);
        cancel.addEventListener('click',()=>{if(!busy) dialog.close();});
        dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
        dialog.addEventListener('close',()=>{active=null;dialog.remove();opener?.focus();});
        form.addEventListener('submit',async event=>{
            event.preventDefault();if(busy||!ready)return;
            const selected = checks.filter(c=>c.checked&&!c.disabled).map(c=>c.value);
            if (!selected.length) {error.textContent=t('Select at least one taxonomy.','Wählen Sie mindestens eine Taxonomie.');return;}
            const included = sections.filter(c=>c.checked).map(c=>c.value);
            if(format.value!=='json'&&!included.length){error.textContent=t('Select at least one section.','Wählen Sie mindestens einen Abschnitt.');return;}
            const options = selection({roots,selectedRoots:selected,profile:profile.value,contents:contents.value,treeLayout:layout.value,sections:included});
            if(format.value==='json') {options.profile='FULL';delete options.sections;delete options.contents;delete options.treeLayout;}
            busy=true;fieldset.disabled=true;cancel.disabled=true;submit.disabled=true;error.textContent='';
            submit.textContent=t('Generating…','Wird erstellt …');dialog.setAttribute('aria-busy','true');
            try {await config.submit({format:format.value,options});dialog.close();}
            catch(err){error.textContent=t('Export failed: ','Export fehlgeschlagen: ')+err.message;error.scrollIntoView({block:'nearest'});}
            finally {busy=false;fieldset.disabled=false;checks.forEach((c,i)=>{c.disabled=!roots[i].inAnalysisScope;});cancel.disabled=false;submit.disabled=false;submit.textContent=t('Download','Herunterladen');dialog.removeAttribute('aria-busy');}
        });
        document.body.appendChild(dialog);dialog.showModal();format.focus();load();
        return dialog;
    }
    function openSaved(config) {
        const projectId=String(config.projectId), snapshotId=String(config.snapshotId);
        return open({...config,saved:true,source:config.source || ('Snapshot: '+snapshotId),
            load:()=>config.api.decisionReportOptions(projectId,snapshotId,config.language),
            submit:config.submit || (async ({format,options})=>download(await config.api.downloadDecisionReport(projectId,snapshotId,format,config.language,options),format,snapshotId))});
    }
    window.TaxonomyDecisionExport={open,openSaved,selection,query,download};
}());
