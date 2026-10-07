(function () {
    'use strict';
    const basePath = window.TaxonomyI18n?.getBasePath?.() || '';
    const applicationUrl = window.TaxonomyI18n?.resolveUrl || (path => path);
    const pathname = location.pathname;
    if (basePath && !pathname.startsWith(basePath + '/')) return;
    const match = pathname.slice(basePath.length).match(/^\/projects\/(\d+)\/reports$/); if (!match) return;
    const projectId = Number(match[1]);
    const locale = (new URLSearchParams(location.search).get('lang') || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const state = { project: null, requirements: [], previewUrl: null, previewGeneration: 0,
        previewController: null, previewSelection: null, previewLoaded: false };
    const labels = {
        en: { title: 'Portfolio reports', portfolio: 'Portfolio', versioning: 'Versioning', heading: 'Project and requirement reports', options: 'Report scope', scope: 'Scope', project: 'Entire project', requirement: 'Single requirement', requirementLabel: 'Requirement', matrix: 'CSV matrix', preview: 'Preview HTML report', previewHeading: 'Report preview', empty: 'Generate a preview to inspect the exact report baseline before export.', rendering: 'Rendering report…', baseline: 'Preview generated from the current project and snapshot baseline.', failed: 'The report could not be generated.' },
        de: { title: 'Portfolio-Berichte', portfolio: 'Portfolio', versioning: 'Versionierung', heading: 'Projekt- und Anforderungsberichte', options: 'Berichtsumfang', scope: 'Umfang', project: 'Gesamtes Projekt', requirement: 'Einzelne Anforderung', requirementLabel: 'Anforderung', matrix: 'CSV-Matrix', preview: 'HTML-Bericht prüfen', previewHeading: 'Berichtsvorschau', empty: 'Erzeugen Sie eine Vorschau, um die exakte Berichtsbaseline vor dem Export zu prüfen.', rendering: 'Bericht wird erzeugt…', baseline: 'Die Vorschau wurde aus dem aktuellen Projekt- und Snapshotstand erzeugt.', failed: 'Der Bericht konnte nicht erzeugt werden.' }
    };
    Object.assign(labels.en, {print: 'Print report', stale: 'The report selection changed. Generate a new preview before printing.',
        empty: 'Generate a preview for the selected report scope.',
        baseline: 'Preview for the selected scope. Downloads generate a fresh report from the current project state.',
        exportStarted: 'export started', previewTitle: 'Portfolio report preview',
        matrixTaxonomy: 'Requirements × taxonomy', matrixSolutions: 'Requirements × solutions', matrixProducts: 'Solutions × products'});
    Object.assign(labels.de, {print: 'Bericht drucken', stale: 'Die Berichtsauswahl wurde geändert. Erzeugen Sie vor dem Drucken eine neue Vorschau.',
        empty: 'Erzeugen Sie eine Vorschau für den gewählten Berichtsumfang.',
        baseline: 'Vorschau für den gewählten Umfang. Downloads erzeugen einen neuen Bericht aus dem aktuellen Projektstand.',
        exportStarted: 'Export gestartet', previewTitle: 'Vorschau des Portfolio-Berichts',
        matrixTaxonomy: 'Anforderungen × Taxonomie', matrixSolutions: 'Anforderungen × Lösungen', matrixProducts: 'Lösungen × Produkte'});
    document.addEventListener('DOMContentLoaded',initialize);
    function l(k){return labels[locale][k]||labels.en[k]||k;}
    async function initialize(){translate();wire();busy(true);try{[state.project,state.requirements]=await Promise.all([api().getProject(projectId),api().listRequirements(projectId)]);render();}catch(e){showError(e);}finally{busy(false);}}
    function translate(){document.documentElement.lang=locale;document.title=`${l('title')} — Taxonomy`;document.querySelector('.skip-link').textContent=locale==='de'?'Zu den Berichten springen':'Skip to reports';document.getElementById('reportsPageTitle').textContent=l('title');document.getElementById('reportsHeading').textContent=l('heading');document.getElementById('portfolioBack').textContent=l('portfolio');document.getElementById('portfolioBack').href=applicationUrl(`/projects?lang=${locale}`);document.getElementById('versioningLink').textContent=l('versioning');document.getElementById('versioningLink').href=applicationUrl(`/projects/${projectId}/versioning?lang=${locale}`);document.getElementById('reportOptionsHeading').textContent=l('options');document.querySelector('label[for="reportScope"]').textContent=l('scope');document.getElementById('reportScope').options[0].textContent=l('project');document.getElementById('reportScope').options[1].textContent=l('requirement');document.querySelector('label[for="reportRequirement"]').textContent=l('requirementLabel');document.querySelector('label[for="matrixType"]').textContent=l('matrix');document.getElementById('previewReport').textContent=l('preview');document.querySelector('#reportPreviewFrame').parentElement.previousElementSibling.querySelector('h2').textContent=l('previewHeading');document.getElementById('emptyPreview').textContent=l('empty');document.querySelector('#reportsBusy span').textContent=l('rendering');}
    function wire(){
        const frame = document.getElementById('reportPreviewFrame');
        const print = document.getElementById('printReport');
        print.textContent = l('print');
        frame.setAttribute('title', l('previewTitle'));
        ['matrixTaxonomy','matrixSolutions','matrixProducts'].forEach((key,index)=>{
            document.getElementById('matrixType').options[index].textContent=l(key);
        });
        invalidatePreview(l('empty'));
        document.getElementById('reportScope').addEventListener('change',()=>{
            document.getElementById('reportRequirement').disabled=document.getElementById('reportScope').value!=='requirement';
            invalidatePreview(l('stale'));
        });
        document.getElementById('reportRequirement').addEventListener('change',()=>invalidatePreview(l('stale')));
        document.getElementById('previewReport').addEventListener('click',preview);
        print.addEventListener('click',()=>{
            if (!state.previewLoaded || !state.previewUrl || state.previewSelection !== selectionKey()) return;
            frame.contentWindow.focus();
            frame.contentWindow.print();
        });
        frame.addEventListener('load',()=>{
            if (!state.previewUrl || state.previewSelection !== selectionKey()) return;
            // A late load from about:blank or an older blob must not authorize printing.
            if (frame.contentWindow.location.href !== state.previewUrl) return;
            state.previewLoaded = true;
            print.disabled = false;
            document.getElementById('previewBaseline').textContent = l('baseline');
            document.getElementById('reportsLive').textContent = l('baseline');
        });
        document.querySelectorAll('.report-download').forEach(button=>button.addEventListener('click',()=>download(button.dataset.format)));
    }
    function render(){document.getElementById('reportsProject').textContent=`${state.project.projectKey} — ${state.project.title}`;const select=document.getElementById('reportRequirement');select.textContent='';state.requirements.forEach(requirement=>{const option=document.createElement('option');option.value=requirement.id;option.textContent=`${requirement.requirementKey} — ${requirement.title}`;select.appendChild(option);});}
    function reportParameters(format){const parameters={};if(document.getElementById('reportScope').value==='requirement')parameters.requirementId=document.getElementById('reportRequirement').value;if(format==='csv')parameters.matrix=document.getElementById('matrixType').value;return parameters;}
    function endpoint(format){return api().reportUrl(projectId,format,reportParameters(format));}
    function selectionKey(){return JSON.stringify(reportParameters('html'));}
    function invalidatePreview(message){
        state.previewGeneration++;
        if (state.previewController) state.previewController.abort();
        state.previewController=null;
        state.previewLoaded=false;
        state.previewSelection=null;
        const frame=document.getElementById('reportPreviewFrame');
        frame.classList.add('d-none');
        frame.src='about:blank';
        if(state.previewUrl) URL.revokeObjectURL(state.previewUrl);
        state.previewUrl=null;
        document.getElementById('printReport').disabled=true;
        document.getElementById('previewBaseline').textContent='';
        document.getElementById('reportsError').classList.add('d-none');
        document.getElementById('reportsError').textContent='';
        const empty=document.getElementById('emptyPreview');
        empty.textContent=message;
        empty.classList.remove('d-none');
        document.getElementById('reportsLive').textContent=message;
        busy(false);
    }
    async function preview(){
        invalidatePreview(l('rendering'));
        const generation=state.previewGeneration;
        const parameters=reportParameters('html');
        const key=JSON.stringify(parameters);
        const controller=new AbortController();
        state.previewController=controller;
        busy(true);
        try{
            const response=await api().fetchReport(projectId,'html',parameters,'text/html',{signal:controller.signal});
            const blob=await response.blob();
            if(generation!==state.previewGeneration || controller.signal.aborted || key!==selectionKey()) return;
            state.previewUrl=URL.createObjectURL(blob);
            state.previewSelection=key;
            const frame=document.getElementById('reportPreviewFrame');
            frame.src=state.previewUrl;
            frame.classList.remove('d-none');
            document.getElementById('emptyPreview').classList.add('d-none');
        }catch(e){
            if(generation!==state.previewGeneration || controller.signal.aborted) return;
            document.getElementById('emptyPreview').textContent=l('failed');
            showError(e);
        }finally{
            if(generation===state.previewGeneration){state.previewController=null;busy(false);}
        }
    }
    function download(format){const link=document.createElement('a');link.href=endpoint(format);link.hidden=true;document.body.appendChild(link);link.click();link.remove();document.getElementById('reportsLive').textContent=`${format.toUpperCase()} ${l('exportStarted')}`;}
    function api(){if(!window.TaxonomyPortfolioApi)throw new Error('Portfolio API boundary is not available');return window.TaxonomyPortfolioApi;}
    function busy(active){document.getElementById('reportsBusy').classList.toggle('d-none',!active);}
    function showError(e){const t=document.getElementById('reportsError');t.textContent=e?.message||l('failed');t.classList.remove('d-none');t.focus();document.getElementById('reportsLive').textContent=t.textContent;}
    window.addEventListener('beforeunload',()=>{if(state.previewController)state.previewController.abort();if(state.previewUrl)URL.revokeObjectURL(state.previewUrl);});
})();
