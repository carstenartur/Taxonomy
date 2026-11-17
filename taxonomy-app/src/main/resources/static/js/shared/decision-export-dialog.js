/* One source-bound export dialog for current analyses and immutable snapshots. */
(function () {
    'use strict';
    const presets = {
        COMPACT: ['SUMMARY', 'TREE'],
        STANDARD: ['SUMMARY', 'TREE', 'CHAPTERS', 'ARCHITECTURE'],
        FULL: ['TITLE_PAGE', 'SUMMARY', 'TREE', 'CHAPTERS', 'ARCHITECTURE', 'EVIDENCE']
    };
    const types = {docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', html: 'text/html', json: 'application/json', visio: 'application/vnd.ms-visio.drawing', 'visio-pdf': 'application/pdf'};
    let active = null;
    function reportText(en, de) {return String(document.documentElement.lang).startsWith('de') ? de : en;}
    function selection(input) {
        const roots = input.selectedRoots || [];
        const available = new Set((input.roots || []).filter(r => r.inAnalysisScope !== false).map(r => r.code));
        if (!roots.length || roots.some(code => !available.has(code))) throw new Error(reportText('Select at least one available taxonomy.', 'Wählen Sie mindestens eine verfügbare Taxonomie.'));
        return {profile: input.profile || 'STANDARD', taxonomyRoots: [...new Set(roots)],
            contents: input.contents || 'SHORT', treeLayout: input.treeLayout || 'AUTO',
            sections: input.sections || presets[input.profile || 'STANDARD'],
            ...(input.showResponsibilities ? {showResponsibilities:true} : {})};
    }
    function query(options) {
        const params = new URLSearchParams();
        Object.entries(options || {}).forEach(([key, value]) => {
            if (key === 'visio' && value) {
                Object.entries(value).forEach(([field, setting]) => params.set('visio.' + field, setting));
            } else if (Array.isArray(value)) value.forEach(item => params.append(key, item));
            else if (value != null) params.set(key, value);
        });
        return params.toString();
    }
    async function reportBlob(response, format, snapshotId, signal, preparedBlob, descriptor) {
        if (signal?.aborted) throw new DOMException('Export cancelled', 'AbortError');
        if (!response.ok) {
            const problem = await response.json().catch(() => null);
            throw new Error(problem?.detail || problem?.message || ('HTTP ' + response.status));
        }
        const type = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
        if (response.redirected || type !== (descriptor?.contentType || types[format] || '').split(';')[0].trim().toLowerCase() || !response.headers.get('X-Taxonomy-Analysis-SHA256'))
            throw new Error(reportText('The response is not a decision report. Please check your session and retry.', 'Die Antwort ist kein Entscheidungsbericht. Prüfen Sie Ihre Sitzung und versuchen Sie es erneut.'));
        if (snapshotId && response.headers.get('X-Taxonomy-Snapshot-Id') !== snapshotId)
            throw new Error(reportText('The report does not belong to the selected snapshot.', 'Der Bericht gehört nicht zum ausgewählten Snapshot.'));
        const blob = preparedBlob || await response.blob();
        if (signal?.aborted) throw new DOMException('Export cancelled', 'AbortError');
        if (!blob.size) throw new Error(reportText('The report is empty.', 'Der Bericht ist leer.'));
        return blob;
    }
    async function download(response, format, snapshotId, signal, preparedBlob, descriptor) {
        const blob = await reportBlob(response, format, snapshotId, signal, preparedBlob, descriptor);
        if (signal?.aborted) throw new DOMException('Export cancelled', 'AbortError');
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        const demo = snapshotId && /^attachment;\s*filename="DEMO-/i.test(response.headers.get('Content-Disposition') || '');
        link.download = (demo ? 'DEMO-' : '') + 'taxonomy-decision-report'
            + (snapshotId ? '-' + snapshotId.replace(/[^A-Za-z0-9_-]/g, '_') : '') + '.' + (descriptor?.fileExtension || format);
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
        if (config.sourceSignal?.aborted) return;
        if (active?.open) { active.focus(); return; }
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
        const format = select(t('Format', 'Format'), 'format', [], '');
        let analysisLanguage = ['de', 'en'].includes(config.analysisLanguage) ? config.analysisLanguage : null;
        const language = select(t('Document language', 'Dokumentsprache'), 'language', [
            ['analysis', t('Follow analysis', 'Wie Analyse')], ['ui', t('Follow interface', 'Wie Oberfläche')],
            ['de', 'Deutsch'], ['en', 'English']
        ], window.TaxonomyContentLanguage?.preference('document') || 'analysis');
        language.setAttribute('data-content-language', 'document');
        const languageNote = el('p'); fieldset.append(languageNote);
        function selectedLanguage() {
            if (window.TaxonomyContentLanguage) return window.TaxonomyContentLanguage.documentLanguage(analysisLanguage, language.value);
            return language.value === 'analysis' ? analysisLanguage : language.value === 'ui' ? (de ? 'de' : 'en') : language.value;
        }
        function updateLanguageNote() {
            languageNote.textContent = language.value === 'analysis' && !analysisLanguage
                ? t('The original analysis language was not recorded; document headings follow the interface. Saved explanations retain their original wording.',
                    'Die ursprüngliche Analysesprache wurde nicht aufgezeichnet; Dokumentüberschriften folgen der Oberfläche. Gespeicherte Begründungen behalten ihren Originaltext.')
                : t('This selects the language of document headings and labels. Saved explanations retain their original wording; choose the AI language before starting a new analysis.',
                    'Diese Auswahl bestimmt die Sprache der Dokumentüberschriften und Beschriftungen. Gespeicherte Begründungen behalten ihren Originaltext; wählen Sie die KI-Sprache vor einer neuen Analyse.');
        }
        language.addEventListener('change', () => {
            window.TaxonomyContentLanguage?.setPreference('document', language.value);
            updateLanguageNote();
        });
        updateLanguageNote();
        const presentation = el('div'); fieldset.append(presentation);
        const profile = select(t('Content', 'Umfang'), 'profile', [['COMPACT',t('Compact','Kompakt')],['STANDARD',t('Standard','Standard')],['FULL',t('Full','Vollständig')]], 'STANDARD', presentation);
        const profileNote = el('p'); presentation.append(profileNote);
        const rootGroup = el('fieldset'); rootGroup.append(el('legend', t('Taxonomies in this report', 'Taxonomien im Bericht'))); fieldset.append(rootGroup);
        const sourceScope = el('p'); fieldset.append(sourceScope);
        const jsonNote = el('p', t('JSON includes the complete evidence for your selection; page layout does not apply.', 'JSON enthält die vollständigen Nachweise Ihrer Auswahl; ein Seitenlayout entfällt.')); fieldset.append(jsonNote);
        const drawing = el('fieldset');
        drawing.append(el('legend', t('Drawing layout', 'Zeichnungslayout')));
        fieldset.append(drawing);
        function choice(parent, type, name, value, title) {
            const label = el('label');
            const input = el('input', null, {type, name, value});
            label.append(input, document.createTextNode(title));
            parent.append(label);
            return input;
        }
        const screen = choice(drawing, 'radio', 'visioMode', 'SCREEN', t('Explore on screen', 'Am Bildschirm erkunden'));
        const wall = choice(drawing, 'radio', 'visioMode', 'WALL', t('Print as a wall chart', 'Als Wandplan drucken'));
        screen.checked = true;
        const drawingNote = el('p'); drawing.append(drawingNote);
        const wallOptions = el('div'); drawing.append(wallOptions);
        const paperSizes = {A4:'297 × 210', A3:'420 × 297', A2:'594 × 420', A1:'841 × 594', A0:'1189 × 841'};
        const paper = select(t('Paper size (landscape)', 'Papierformat (Querformat)'), 'visioPaper',
            Object.keys(paperSizes).map(size => [size, size]), 'A1', wallOptions);
        const shortReasons = choice(wallOptions, 'checkbox', 'visioShortReasons', 'true',
            t('Show short reasons on the drawing', 'Kurzbegründungen auf der Zeichnung anzeigen'));
        const drawingResponsibilities = choice(drawing, 'checkbox', 'visioResponsibilities', 'true',
            t('Show responsibilities', 'Zuständigkeiten anzeigen'));
        drawingResponsibilities.checked = true;
        const appendix = choice(drawing, 'checkbox', 'visioAppendix', 'true',
            t('Include report appendix', 'Berichtsanhang hinzufügen'));
        const previewArea = el('div'); drawing.append(previewArea);
        const paperSummary = el('p'); previewArea.append(paperSummary);
        const preview = el('button', t('Generate print preview', 'Druckvorschau erstellen'), {type:'button'});
        const previewLink = el('a', t('Open print preview (new tab)', 'Druckvorschau öffnen (neuer Tab)'), {target:'_blank',rel:'noopener'});
        previewLink.hidden = true;
        const previewStatus = el('p', '', {role:'status'});
        previewArea.append(preview, previewStatus, previewLink);
        let previewController = null, previewUrl = null;
        function clearPreview() {
            previewController?.abort();
            previewController = null;
            if (previewUrl) URL.revokeObjectURL(previewUrl);
            previewUrl = null;
            previewLink.removeAttribute('href');
            previewLink.hidden = true;
            previewStatus.textContent = '';
            preview.disabled = false;
        }
        const advanced = el('details'); advanced.append(el('summary', t('More options','Weitere Optionen'))); fieldset.append(advanced);
        const contents = select(t('Contents','Inhaltsverzeichnis'),'contents', [['NONE',t('None','Keines')],['SHORT',t('Short — main sections','Kurz – Hauptabschnitte')],['FULL',t('Detailed','Ausführlich')]], 'SHORT', advanced);
        const layout = select(t('Decision tree','Entscheidungsbaum'), 'treeLayout', [['AUTO',t('Automatic (landscape)','Automatisch (Querformat)')],['A4_LANDSCAPE',t('A4 landscape — one page','A4 quer – eine Seite')],['A3_LANDSCAPE',t('A3 landscape — one page','A3 quer – eine Seite')],['TABLE',t('Table','Tabelle')]], 'AUTO', advanced);
        advanced.append(el('p', t('Automatic uses A4, A3 or continuation pages. A fixed page size reports when the complete tree cannot fit legibly.', 'Automatisch nutzt A4, A3 oder Folgeseiten. Bei fester Seitengröße erhalten Sie einen Hinweis, falls der vollständige Baum nicht lesbar hineinpasst.')));
        const responsibilityLabel = el('label');
        const responsibility = el('input', null, {type:'checkbox',name:'showResponsibilities'});
        responsibility.checked = !!window.TaxonomyResponsibilityDisplay?.enabled();
        responsibilityLabel.append(responsibility, document.createTextNode(t('Show organizational responsibilities', 'Organisatorische Zuständigkeiten anzeigen')));
        advanced.append(responsibilityLabel);
        const sectionGroup = el('fieldset'); sectionGroup.append(el('legend',t('Sections','Abschnitte'))); advanced.append(sectionGroup);
        const sectionNames = {TITLE_PAGE:t('Cover','Deckblatt'),SUMMARY:t('Summary','Zusammenfassung'),TREE:t('Complete tree','Vollständiger Baum'),CHAPTERS:t('Decision reasoning','Entscheidungsbegründungen'),ARCHITECTURE:t('Saved architecture and boundary context','Gespeicherte Architektur und Kontext'),EVIDENCE:t('Detailed evidence','Ausführliche Nachweise')};
        const sections = Object.entries(sectionNames).filter(([key]) => key !== 'ARCHITECTURE' || config.saved).map(([key,title]) => {
            const label = el('label'); const input = el('input',null,{type:'checkbox',value:key,name:'section'});label.append(input,document.createTextNode(title));sectionGroup.append(label);return input;
        });
        function preset() {
            profileNote.textContent = {COMPACT:t('Summary and complete decision tree.','Zusammenfassung und vollständiger Entscheidungsbaum.'),STANDARD:t('Includes decision reasoning.','Mit Entscheidungsbegründungen.'),FULL:t('Includes cover and detailed evidence.','Mit Deckblatt und ausführlichen Nachweisen.')}[profile.value];
            sections.forEach(input => {input.checked = !input.disabled && presets[profile.value].includes(input.value);});
            // Content depth selects sections. The reader's layout and navigation
            // choices remain independent, including for a complete report.
        }
        function drawingFormat() { return format.value === 'visio' || format.value === 'visio-pdf'; }
        function updateFormat() {
            const isDrawing = drawingFormat();
            presentation.hidden = format.value === 'json' || isDrawing;
            advanced.hidden = presentation.hidden;
            jsonNote.hidden = format.value !== 'json';
            drawing.hidden = !isDrawing;
            wallOptions.hidden = !wall.checked;
            previewArea.hidden = !wall.checked;
            if (!isDrawing) return;
            const description = wall.checked
                ? t('The drawing uses the selected landscape paper size.', 'Die Zeichnung verwendet das gewählte Papierformat im Querformat.')
                : t('Follow the decision trees and explore the saved architecture when available.', 'Verfolgen Sie die Entscheidungsbäume und erkunden Sie die gespeicherte Architektur, sofern sie vorliegt.');
            drawingNote.textContent = description + ' ' + (format.value === 'visio'
                ? t('In Visio, select and edit shapes and inspect their recorded data.', 'In Visio können Sie Shapes auswählen, bearbeiten und ihre gespeicherten Daten einsehen.')
                : t('The PDF shows the drawings as fixed pages for reading or printing.', 'Das PDF zeigt die Zeichnungen als feste Seiten zum Lesen oder Drucken.'));
            const hasPreview = !!config.requestReport && availableFormats.some(item => item.id === 'visio-pdf');
            preview.hidden = !hasPreview;
            const selection = checks.filter(c => c.checked && !c.disabled).map(c => c.value).join(', ');
            paperSummary.textContent = paper.value + ' · ' + paperSizes[paper.value] + ' mm'
                + (selection ? ' · ' + selection : '');
            if (paper.value === 'A1' || paper.value === 'A0') {
                paperSummary.textContent += t('. Select ' + paper.value + ' in the printer driver too.',
                    '. Wählen Sie auch im Druckertreiber ' + paper.value + '.');
            }
            if (!hasPreview) previewStatus.textContent = t('PDF preview is unavailable here. Open the downloaded drawing to check its print layout.',
                'Die PDF-Vorschau ist hier nicht verfügbar. Prüfen Sie das Drucklayout in der heruntergeladenen Zeichnung.');
        }
        profile.addEventListener('change',preset);
        form.addEventListener('change', () => {clearPreview(); updateFormat();});
        preset();
        const error = el('p','', {role:'alert'}); form.append(error);
        const retry = el('button', t('Retry loading','Erneut laden'), {type:'button'});retry.hidden = true;form.append(retry);
        const actions = el('div',null,{class:'export-actions'}); form.append(actions);
        const cancel = el('button', t('Cancel','Abbrechen'), {type:'button'});
        const submit = el('button', t('Download','Herunterladen'), {type:'submit'});actions.append(cancel,submit);
        let roots = [], checks = [], busy = false, ready = false, exportController = null, availableFormats = [];
        async function load() {
            ready=false; submit.disabled=true; retry.hidden=true;error.textContent='';
            sourceScope.textContent=t('Loading saved scope…','Gespeicherten Umfang laden …');
            try {
                const [data, capabilities] = await Promise.all([config.load ? config.load() : config, window.TaxonomyCapabilities.load()]);
                if (!dialog.open) return;
                availableFormats = window.TaxonomyCapabilities.formats(capabilities.reports, 'decision-rationale');
                format.replaceChildren();
                availableFormats.forEach(item => format.append(el('option', item.displayName + ' (.' + item.fileExtension + ')', {value:item.id})));
                format.value = availableFormats.some(item => item.id === config.format) ? config.format
                    : availableFormats.some(item => item.id === 'docx') ? 'docx' : (availableFormats[0]?.id || '');
                if (!availableFormats.length) throw new Error(t('No decision-report format is installed.', 'Kein Format für Entscheidungsberichte ist installiert.'));
                analysisLanguage = ['de', 'en'].includes(data.analysisLanguage) ? data.analysisLanguage : null;
                updateLanguageNote();
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
                updateFormat();
                ready=available.length>0;submit.disabled=!ready;
                if (!ready) throw new Error(t('No reportable taxonomy is available.','Keine exportierbare Taxonomie verfügbar.'));
            } catch (err) { if(dialog.open){error.textContent=err.message;retry.hidden=false;} }
        }
        retry.addEventListener('click',load);
        function stopOperations() {
            exportController?.abort();
            clearPreview();
        }
        function closeDialog() {
            stopOperations();
            if (dialog.open) dialog.close();
        }
        cancel.addEventListener('click', closeDialog);
        dialog.addEventListener('cancel', stopOperations);
        config.sourceSignal?.addEventListener('abort', closeDialog, {once:true});
        const lifecycle = new AbortController();
        const sameSource = window.TaxonomyApiClient?.captureContext?.();
        function checkDrawingSource() {
            if (drawingFormat() && sameSource && !sameSource()) {
                closeDialog();
                return false;
            }
            return true;
        }
        for (const event of ['taxonomy:entity-context-changed', 'taxonomy:repository-context-changed',
            'taxonomy:architecture-source-changed', 'taxonomy:analysis-invalidated', 'taxonomy-api-auth-failure']) {
            document.addEventListener(event, checkDrawingSource, {signal:lifecycle.signal});
        }
        window.addEventListener?.('pagehide', () => {if (drawingFormat()) closeDialog();}, {signal:lifecycle.signal});
        previewLink.addEventListener('click', event => {if (!checkDrawingSource()) event.preventDefault();});
        dialog.addEventListener('close',()=>{
            lifecycle.abort();
            config.sourceSignal?.removeEventListener('abort', closeDialog);
            stopOperations();
            if(active===dialog)active=null;
            dialog.remove();
            if (!active && !config.sourceSignal?.aborted) opener?.focus();
        });
        function reportOptions() {
            const selected = checks.filter(c => c.checked && !c.disabled).map(c => c.value);
            if (!selected.length) throw new Error(t('Select at least one taxonomy.', 'Wählen Sie mindestens eine Taxonomie.'));
            const included = sections.filter(section => !section.disabled && (drawingFormat()
                ? section.value === 'TREE' || section.value === 'ARCHITECTURE'
                : section.checked)).map(section => section.value);
            if (format.value !== 'json' && !included.length) throw new Error(t('Select at least one section.', 'Wählen Sie mindestens einen Abschnitt.'));
            const options = selection({roots,selectedRoots:selected,profile:profile.value,contents:contents.value,treeLayout:layout.value,sections:included,showResponsibilities:responsibility.checked});
            if (format.value === 'json') {options.profile='FULL';delete options.sections;delete options.contents;delete options.treeLayout;}
            if (drawingFormat()) {
                delete options.contents;
                delete options.treeLayout;
                options.showResponsibilities = drawingResponsibilities.checked;
                options.visio = {mode:wall.checked ? 'WALL' : 'SCREEN', paper:paper.value,
                    appendix:appendix.checked, shortReasons:wall.checked && shortReasons.checked};
            }
            return options;
        }
        preview.addEventListener('click', async () => {
            if (busy || !ready || !dialog.open || !drawingFormat() || !wall.checked || !checkDrawingSource()) return;
            clearPreview();
            const descriptor = availableFormats.find(item => item.id === 'visio-pdf');
            if (!descriptor || !config.requestReport) return;
            const controller = new AbortController();
            previewController = controller;
            preview.disabled = true;
            error.textContent = '';
            previewStatus.textContent = t('Generating print preview…', 'Druckvorschau wird erstellt …');
            try {
                const result = await config.requestReport({format:'visio-pdf', descriptor, options:reportOptions(),
                    language:selectedLanguage(), signal:controller.signal, timeoutMillis:120000});
                const blob = await reportBlob(result.response, 'visio-pdf', config.snapshotId, controller.signal, result.blob);
                if (controller.signal.aborted || !dialog.open || !checkDrawingSource()) return;
                previewUrl = URL.createObjectURL(blob);
                previewLink.href = previewUrl;
                previewLink.hidden = false;
                previewStatus.textContent = t('The print preview is ready.', 'Die Druckvorschau ist bereit.');
                previewLink.focus();
            } catch (err) {
                if (!controller.signal.aborted && dialog.open) {
                    previewStatus.textContent = '';
                    error.textContent = t('Print preview failed: ', 'Druckvorschau fehlgeschlagen: ') + err.message;
                }
            } finally {
                if (previewController === controller) {previewController = null; preview.disabled = false;}
            }
        });
        form.addEventListener('submit',async event=>{
            event.preventDefault();if(busy||!ready||!dialog.open||config.sourceSignal?.aborted||!checkDrawingSource())return;
            let options;
            try {options = reportOptions();}
            catch (err) {error.textContent = err.message; return;}
            clearPreview();
            const controller=new AbortController();
            exportController=controller;
            busy=true;fieldset.disabled=true;submit.disabled=true;error.textContent='';
            submit.textContent=t('Generating…','Wird erstellt …');dialog.setAttribute('aria-busy','true');
            try {
                window.TaxonomyContentLanguage?.setPreference('document', language.value);
                await config.submit({format:format.value,timeoutMillis:format.value === 'visio-pdf' ? 120000 : undefined,descriptor:availableFormats.find(item => item.id === format.value),options,language:selectedLanguage(),signal:controller.signal});
                if(dialog.open&&!controller.signal.aborted)dialog.close();
            }
            catch(err){if(dialog.open&&!controller.signal.aborted){error.textContent=t('Export failed: ','Export fehlgeschlagen: ')+err.message;error.scrollIntoView({block:'nearest'});}}
            finally {
                busy=false;
                if(exportController===controller)exportController=null;
                if(dialog.open){updateFormat();fieldset.disabled=false;checks.forEach((c,i)=>{c.disabled=!roots[i].inAnalysisScope;});submit.disabled=false;submit.textContent=t('Download','Herunterladen');dialog.removeAttribute('aria-busy');}
            }
        });
        updateFormat();
        document.body.appendChild(dialog);dialog.showModal();format.focus();load();
        return dialog;
    }
    function openSaved(config) {
        const projectId=String(config.projectId), snapshotId=String(config.snapshotId);
        const openingScope = config.scope || window.TaxonomyArchitectureWorkflow?.getScope?.();
        const scope = openingScope ? Object.freeze(Object.assign({}, openingScope)) : undefined;
        const requestReport = async ({format, options, language, signal, timeoutMillis}) => ({
            response:await config.api.downloadDecisionReport(projectId, snapshotId, format, language, options, {signal, scope, timeoutMillis})
        });
        return open({...config,saved:true,snapshotId,source:config.source || ('Snapshot: '+snapshotId),requestReport,
            load:()=>config.api.decisionReportOptions(projectId,snapshotId,window.TaxonomyContentLanguage?.documentLanguage(null) || null, {scope,signal:config.sourceSignal}),
            submit:config.submit ? selection => config.submit({...selection, scope}) : (async selection => {
                const {response} = await requestReport(selection);
                return download(response, selection.format, snapshotId, selection.signal, undefined, selection.descriptor);
            })});
    }
    window.TaxonomyDecisionExport={open,openSaved,selection,query,download};
}());
