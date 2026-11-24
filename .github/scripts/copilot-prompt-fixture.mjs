// Produce an offline native-browser fixture using the production diagnostic UI.
// API results are authored test data; this is not a live provider/server acceptance test.
import {mkdir, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root = new URL('../../', import.meta.url);
const output = new URL('target/copilot-prompt-browser/', root);
await mkdir(output, {recursive: true});
const resources = new URL('taxonomy-app/src/main/resources/static/', root);
const prompt = 'Evaluate the offered taxonomy nodes.\nRetain the original requirement wording.\nINPUT\n' + JSON.stringify({
    original: 'Die Lösung soll Dokumente zentral verwalten.\n\nDer zweite Absatz bleibt als eigener Absatz sichtbar. Die absichtliche Zeichenfolge \\n bleibt unverändert. <script>window.__promptExecuted=true</script>',
    nodes: [{id: 'IP', name: 'Information Platforms', description: 'Dokumente erfassen und strukturieren.\nInhalte sicher wiederfinden.'}],
    errors: []
});
const fixture = {
    prompt, response: '{"selected":["IP"]}', promptLength: prompt.length,
    responseLength: 19, truncated: false
};
const scripts = ['shared/taxonomy-utils.js', 'core/taxonomy-prompt-display.js', 'core/taxonomy-analysis-progress.js'];
const serialize = value => JSON.stringify(value).replaceAll('<', '\\u003c');
const html = `<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Copilot-Prompt – Browserprüfung</title>
<link rel="stylesheet" href="${new URL('css/taxonomy.css', resources)}">
<style>
body{font:16px/1.5 system-ui,sans-serif;margin:0;background:#eef2f6;color:#212529}
main{box-sizing:border-box;max-width:1000px;margin:24px auto;padding:24px;background:white;border:1px solid #dee2e6;border-radius:12px}
h1{font-size:1.4rem;margin:0 0 8px}summary,button{cursor:pointer}button{padding:6px 10px;border:1px solid #6c757d;border-radius:5px;background:white;color:inherit}
.d-flex{display:flex}.flex-wrap{flex-wrap:wrap}.align-items-center{align-items:center}.justify-content-between{justify-content:space-between}.gap-2{gap:8px}.mb-2{margin-bottom:8px}.small{font-size:.9rem}.text-muted{color:#626b74}.p-2{padding:8px}
#analysisLiveProgress{margin:12px 0;padding:12px;background:#f8f9fa}#llmCommLogContent{border:1px solid #dee2e6;border-radius:6px}#checkResult{padding:8px;border:1px solid #adb5bd;margin:12px 0}textarea{box-sizing:border-box;width:100%;min-height:90px}
@media(max-width:500px){main{margin:0;padding:12px;border-radius:0}h1{font-size:1.2rem}}
</style></head><body><main>
<h1>Copilot · LLM-Kommunikationsprotokoll</h1>
<p class="text-muted">Browserprüfung mit Testdaten und den originalen UI-Modulen.</p>
<div id="statusArea"></div><div id="llmCommLogContent"></div>
<p><button id="checkCopy" type="button">Kopierten Rohprompt prüfen</button>
<button id="plain" type="button">Unformatierte Vorschau prüfen</button></p>
<div id="checkResult" role="status">Prüfung wird geladen.</div>
<label for="clipboardPaste">Kopierten Text hier einfügen:</label><textarea id="clipboardPaste"></textarea>
<div id="plainPreview"></div>
<script>
window.__fixture=${serialize(fixture)};
window.__pageErrors=[];
window.addEventListener('error',e=>window.__pageErrors.push(e.message));
window.addEventListener('unhandledrejection',e=>window.__pageErrors.push(String(e.reason)));
window.__TaxonomyAnalysisSessionContext={runtime:{workspaceId:'fixture-workspace',analysisGeneration:1}};
window.TaxonomyAnalysisSessionApi={
 getRunStatus:async()=>({json:async()=>({operationId:'fixture-run',sequence:1,phase:'FINISHED',status:'COMPLETED',
 startedAt:1795518000000,serverTime:1795518002500,lastActivityAt:1795518002500,evaluatedNodes:1,
 calls:[{id:1,provider:'MOCK',node:'IP',status:'COMPLETED',startedAt:1795518000000,durationMillis:2500}],memory:{}})}),
 getRunCallDetail:async()=>({json:async()=>window.__fixture}),cancelRun:async()=>{}
};
</script>
${scripts.map(name => `<script src="${new URL('js/' + name, resources)}"></script>`).join('\n')}
<script>
TaxonomyAnalysisProgress.start('fixture-run');
function verify(){
 const readable=document.querySelector('.llm-prompt-readable');
 const raw=document.querySelector('.llm-prompt-field details pre');
 const values=readable ? Array.from(readable.querySelectorAll('pre')).map(e=>e.textContent) : [];
 const expected=JSON.parse(window.__fixture.prompt.split('INPUT\\n')[1]).original;
 const valid=values.includes(expected)&&raw?.textContent===window.__fixture.prompt&&!window.__promptExecuted&&window.__pageErrors.length===0;
 document.getElementById('checkResult').textContent=valid ? 'BESTANDEN: Absätze, wörtliche Escapes, unverändertes Original und sichere Textdarstellung.' : 'FEHLER: Darstellung oder Original weichen ab. '+window.__pageErrors.join('; ');
}
const observer=new MutationObserver(()=>{
 const entry=document.querySelector('.llm-log-entry');
 if(entry&&!entry.open)entry.open=true;
 if(document.querySelector('.llm-prompt-readable')){observer.disconnect();verify();}
});
observer.observe(document.getElementById('llmCommLogContent'),{childList:true,subtree:true});
document.getElementById('clipboardPaste').addEventListener('input',e=>{
 document.getElementById('checkResult').textContent=e.target.value===window.__fixture.prompt ? 'BESTANDEN: Kopierter Rohprompt stimmt exakt überein.' : 'FEHLER: Kopierter Text weicht ab.';
});
document.getElementById('checkCopy').onclick=async()=>{
 try{document.getElementById('clipboardPaste').value=await navigator.clipboard.readText();document.getElementById('clipboardPaste').dispatchEvent(new Event('input'));}
 catch(_){document.getElementById('checkResult').textContent='Bitte den kopierten Rohprompt in das Textfeld einfügen.';}
};
document.getElementById('plain').onclick=()=>{
 const host=document.getElementById('plainPreview');host.replaceChildren();
 host.append(TaxonomyPromptDisplay.render('Erste Zeile\\nZweite Zeile mit wörtlichem \\\\n.',{title:'Einfacher Text'}));
 host.append(TaxonomyPromptDisplay.render('INPUT\\n{"original":"abgeschnitten',{title:'Gekürzte Vorschau',originalLength:500}));
};
</script></main></body></html>`;
await writeFile(new URL('index.html', output), html);
const comparison = `<!doctype html><html lang="de"><head><meta charset="utf-8"><title>Copilot – Schmale Ansicht</title>
<style>body{font:16px system-ui;background:#e9ecef;margin:20px}iframe{width:390px;height:1500px;border:1px solid #adb5bd;background:white}</style>
</head><body><h1>390-Pixel-Ansicht</h1><p>Originale Komponenten in einem schmalen Browser-Viewport.</p><iframe title="Copilot auf schmalem Bildschirm" src="index.html"></iframe></body></html>`;
await writeFile(new URL('narrow.html', output), comparison);
console.log(fileURLToPath(new URL('index.html', output)));
console.log(fileURLToPath(new URL('narrow.html', output)));
