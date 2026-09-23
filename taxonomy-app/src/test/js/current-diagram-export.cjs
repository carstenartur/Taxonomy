const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync, existsSync} = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = readFileSync(path.resolve(__dirname, '../../main/resources/static/js/shared/taxonomy-export.js'), 'utf8');
function fixture(view = {viewTitle: 'Existing result', includedElements: [{nodeCode: 'A'}], includedRelationships: []}) {
    const elements = new Map();
    function element(tag) {
        const e = {tagName: tag, children: [], attributes: {}, disabled: false,
            classList: {add() {}, remove() {}}, textContent: '',
            appendChild(child) { this.children.push(child); if (child.id) elements.set(child.id, child); return child; },
            setAttribute(k,v) { this.attributes[k]=String(v); }, removeAttribute(k) { delete this.attributes[k]; },
            getAttribute(k) { return this.attributes[k] || null; },
            remove() {}, click() {}, querySelector() {return null;}, querySelectorAll() {return [];} };
        return e;
    }
    for (const id of ['exportGroup','exportSvg','exportPng','exportVisio','exportArchiMate','exportMermaid','exportStructurizr']) {
        const el=element('button');el.id=id;elements.set(id,el);
    }
    const businessText=element('textarea');businessText.id='businessText';businessText.value='original';elements.set('businessText',businessText);
    const requests=[]; const alerts=[]; const downloads=[]; let resolve; let reject;
    const pending=new Promise((yes,no)=>{resolve=yes;reject=no;});
    const document={documentElement:{lang:'en'},body:element('body'),
        getElementById:id=>elements.get(id)||null,createElement:element,
        querySelector:()=>null,addEventListener(){}};
    const state={currentArchView:view,lastAnalyzedText:'original',currentScores:{A:90}};
    const window={TaxonomyState:state,setTimeout() {},confirm:()=>true};
    const context={window,document,TaxonomyI18n:{t:key=>key},Blob,URL:{createObjectURL:blob=> {downloads.push(blob);return 'blob:test';},revokeObjectURL(){}},
        fetch:(url,options)=>{requests.push({url,options});return pending;},alert(message){alerts.push(message);},Element:class {}};
    vm.runInNewContext(source,context);
    return {elements,requests,alerts,state,resolve,reject,downloads,api:window.TaxonomyExport,run:()=>window.TaxonomyExport.exportVisio('original')};
}
test('exports a frozen copy of the current architecture with visible busy state',async()=>{
    const f=fixture();const original=JSON.stringify(f.state);const pending=f.run();await Promise.resolve();
    assert.equal(f.requests[0].url,'/api/diagram/current/visio');
    assert.deepEqual(JSON.parse(f.requests[0].options.body),f.state.currentArchView);
    assert.equal(f.elements.get('exportVisio').disabled,true);
    assert.equal(f.elements.get('diagramExportStatus').attributes['aria-busy'],'true');
    assert.match(f.elements.get('diagramExportStatusText').textContent,/Creating/);
    f.resolve(binaryResponse());await pending;
    assert.equal(f.elements.get('exportVisio').disabled,false);
    assert.equal(f.elements.get('diagramExportStatus').attributes['aria-busy'],'false');
    assert.match(f.elements.get('diagramExportStatusText').textContent,/ready/i);
    assert.equal(JSON.stringify(f.state),original);
});
test('main SVG export posts the frozen architecture model instead of serializing the live viewport',async()=>{
    const f=fixture();const work=f.api.exportSvg();await Promise.resolve();
    assert.equal(f.requests[0].url,'/api/diagram/current/svg');
    assert.deepEqual(JSON.parse(f.requests[0].options.body),f.state.currentArchView);
    f.resolve({ok:true,redirected:false,headers:{get:()=> 'image/svg+xml'},
        text:async()=>'<svg xmlns="http://www.w3.org/2000/svg"><text>A</text></svg>'});
    assert.equal(await work,true);
    assert.equal(f.downloads.length,1);
});

test('missing architecture never triggers a new analysis',async()=>{
    const f=fixture(null);f.resolve({ok:true,blob:async()=>new Blob(['unexpected'])});await f.run();assert.equal(f.requests.length,0);
    assert.match(f.elements.get('diagramExportStatusText').textContent,/architecture/i);
});
test('changed requirement never exports a mismatched architecture',async()=>{
    const f=fixture();f.state.lastAnalyzedText='previous';f.resolve({ok:true,blob:async()=>new Blob(['unexpected'])});await f.run();assert.equal(f.requests.length,0);
    assert.match(f.elements.get('diagramExportStatusText').textContent,/changed/i);
});
test('failed export keeps the architecture and restores previously disabled controls',async()=>{
    const f=fixture();f.elements.get('exportArchiMate').disabled=true;
    const view=f.state.currentArchView;const pending=f.run();f.reject(new Error('network down'));await pending;
    assert.equal(f.state.currentArchView,view);assert.equal(f.elements.get('exportVisio').disabled,false);
    assert.equal(f.elements.get('exportArchiMate').disabled,true);
    assert.match(f.elements.get('diagramExportStatusText').textContent,/network down/);
});
test('duplicate clicks do not create duplicate export requests',async()=>{
    const f=fixture();const pending=f.run();f.run();await Promise.resolve();assert.equal(f.requests.length,1);
    f.resolve(binaryResponse());await pending;
});

test('backend errors also reach global feedback while the export group is hidden',async()=>{
    const f=fixture();f.elements.get('exportGroup').hidden=true;
    const before=JSON.stringify(f.state);const pending=f.run();
    f.resolve({ok:false,status:503,json:async()=>({error:'EXPORT_SERVICE_UNAVAILABLE'})});
    assert.equal(await pending,false);
    assert.equal(f.alerts.length,1);
    assert.match(f.alerts[0],/EXPORT_SERVICE_UNAVAILABLE/);
    assert.match(f.elements.get('diagramExportStatusText').textContent,/EXPORT_SERVICE_UNAVAILABLE/);
    assert.equal(f.elements.get('diagramExportStatus').attributes['aria-busy'],'false');
    assert.equal(f.elements.get('exportVisio').disabled,false);
    assert.equal(JSON.stringify(f.state),before);
});
test('missing or stale architectures produce global feedback without any request',async()=>{
    for (const missing of [true,false]) {
        const f=missing?fixture(null):fixture();
        if(!missing)f.state.lastAnalyzedText='older requirement';
        assert.equal(await f.run(),false);
        assert.equal(f.requests.length,0);
        assert.equal(f.alerts.length,1);
        assert.match(f.alerts[0],/architecture|changed/i);
    }
});
test('serialization failure produces global feedback without changing the view',async()=>{
    const f=fixture();f.state.currentArchView.self=f.state.currentArchView;
    assert.equal(await f.run(),false);
    assert.equal(f.requests.length,0);
    assert.equal(f.alerts.length,1);
    assert.match(f.alerts[0],/serialized/i);
    assert.equal(f.state.currentArchView.self,f.state.currentArchView);
});

test('an absent or blank analysed-text baseline must not permit an export',async()=>{
    for (const baseline of [null,undefined,'','   ']) {
        const f=fixture();f.state.lastAnalyzedText=baseline;
        f.resolve({ok:true,blob:async()=>new Blob(['unexpected'])});
        assert.equal(await f.run(),false);
        assert.equal(f.requests.length,0);
        assert.equal(f.alerts.length,1);
    }
});

// Structural ZIP fixtures contain real stored/deflated bytes and matching CRCs.
// Their payloads deliberately do not claim to be complete Visio documents.
function crc32(data) {
    let crc = 0xffffffff;
    for (const byte of data) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
    return (crc ^ 0xffffffff) >>> 0;
}
function packageBytes(names=['[Content_Types].xml','visio/document.xml','visio/pages/pages.xml'], options={}) {
    const locals=[], directories=[];let offset=0;
    for(const name of names){
        const n=Buffer.from(name);
        const data=Buffer.from(options.empty === name ? '' : 'content of ' + name);
        const method=options.descriptor ? 8 : 0;
        const compressed=method===8 ? require('node:zlib').deflateRawSync(data) : data;
        const flags=options.descriptor ? 8 : 0;
        const crc=crc32(data);
        const local=Buffer.alloc(30+n.length);local.writeUInt32LE(0x04034b50);
        local.writeUInt16LE(20,4);local.writeUInt16LE(flags,6);local.writeUInt16LE(method,8);
        local.writeUInt16LE(n.length,26);n.copy(local,30);
        if(!flags) {local.writeUInt32LE(crc,14);local.writeUInt32LE(compressed.length,18);local.writeUInt32LE(data.length,22);}
        const descriptor=flags ? Buffer.alloc(options.descriptor==='unsigned' ? 12 : 16) : Buffer.alloc(0);
        if(flags) {const shift=descriptor.length===16 ? 4 : 0;
            if(shift)descriptor.writeUInt32LE(0x08074b50);
            descriptor.writeUInt32LE(crc,shift);descriptor.writeUInt32LE(compressed.length,shift+4);descriptor.writeUInt32LE(data.length,shift+8);}
        locals.push(local,compressed,descriptor);
        const central=Buffer.alloc(46+n.length);central.writeUInt32LE(0x02014b50);central.writeUInt16LE(20,4);central.writeUInt16LE(20,6);
        central.writeUInt16LE(flags,8);central.writeUInt16LE(method,10);central.writeUInt32LE(crc,16);
        central.writeUInt32LE(compressed.length,20);central.writeUInt32LE(data.length,24);
        central.writeUInt16LE(n.length,28);central.writeUInt32LE(offset,42);n.copy(central,46);directories.push(central);
        offset+=local.length+compressed.length+descriptor.length;
    }
    const directory=Buffer.concat(directories);const end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);
    end.writeUInt16LE(names.length,8);end.writeUInt16LE(names.length,10);end.writeUInt32LE(directory.length,12);end.writeUInt32LE(offset,16);
    return Buffer.concat([...locals,directory,end]);
}
function zipEntries(bytes) {
    let cursor=bytes.readUInt32LE(bytes.length-6);
    const result=[];
    for(let i=0;i<bytes.readUInt16LE(bytes.length-12);i++) {
        result.push({central:cursor,local:bytes.readUInt32LE(cursor+42)});
        cursor+=46+bytes.readUInt16LE(cursor+28)+bytes.readUInt16LE(cursor+30)+bytes.readUInt16LE(cursor+32);
    }
    return result;
}
function binaryResponse(overrides={}) {
    return {ok:true, redirected:false, headers:{get:()=> 'application/vnd.ms-visio.drawing'},
        blob:async()=>new Blob([packageBytes()],{type:'application/vnd.ms-visio.drawing'}), ...overrides};
}
for (const [name, response] of Object.entries({
    'HTML success': binaryResponse({headers:{get:()=> 'text/html'},blob:async()=>new Blob(['<html>login</html>'])}),
    'JSON success': binaryResponse({headers:{get:()=> 'application/json'},blob:async()=>new Blob(['{"error":"failed"}'])}),
    'missing content type': binaryResponse({headers:{get:()=> null}}),
    'redirected login': binaryResponse({redirected:true}),
    'empty binary': binaryResponse({blob:async()=>new Blob([])}),
    'non-ZIP binary': binaryResponse({blob:async()=>new Blob(['a forged VSDX response'])}),
    'ZIP without Visio parts': binaryResponse({blob:async()=>new Blob([packageBytes(['unrelated.txt'])])}),
    'truncated ZIP': binaryResponse({blob:async()=>new Blob([new Uint8Array([0x50,0x4b,3,4])])})
})) test(`reject ${name} before creating a download`,async()=>{
    const f=fixture(); const original=JSON.stringify(f.state); const work=f.run(); f.resolve(response);
    assert.equal(await work,false);
    assert.equal(f.downloads.length,0);
    assert.equal(f.alerts.length,1);
    assert.equal(f.elements.get('exportVisio').disabled,false);
    assert.equal(JSON.stringify(f.state),original);
});
test('valid Visio binary creates exactly one download, without further requests',async()=>{
    const f=fixture();const work=f.run();f.resolve(binaryResponse());assert.equal(await work,true);
    assert.equal(f.downloads.length,1);assert.equal(f.requests.length,1);
});
test('Sparx handoff freezes the same existing view and does not call analysis',async()=>{
    const f=fixture();assert.equal(typeof f.api.exportSparx,'function');
    const initial=JSON.stringify(f.state.currentArchView);
    const work=f.api.exportSparx('original');await Promise.resolve();
    assert.equal(f.requests[0].url,'/api/diagram/current/sparx');
    assert.equal(f.requests[0].options.body,initial);
    f.resolve(binaryResponse({headers:{get:()=> 'application/zip'},blob:async()=>new Blob([packageBytes(['architecture.xmi','manifest.json','README.txt'])])}));
    assert.equal(await work,true);assert.equal(f.downloads.length,1);
    assert.equal(JSON.stringify(f.state.currentArchView),initial);
});


for (const [name, mutate] of Object.entries({
    'local header outside data area': (b,e) => b.writeUInt32LE(b.length-22,e[1].central+42),
    'missing local header signature': (b,e) => b.writeUInt32LE(0,e[1].local),
    'local name differs from directory': (b,e) => b[e[1].local+30]^=1,
    'local flags disagree': (b,e) => b.writeUInt16LE(8,e[1].local+6),
    'local compression disagrees': (b,e) => b.writeUInt16LE(8,e[1].local+8),
    'local size disagrees': (b,e) => b.writeUInt32LE(1,e[1].local+18),
    'local CRC disagrees': (b,e) => b.writeUInt32LE(1,e[1].local+14),
    'data extends into central directory': (b,e) => {
        b.writeUInt32LE(b.length,e[2].central+20);b.writeUInt32LE(b.length,e[2].central+24);
        b.writeUInt32LE(b.length,e[2].local+18);b.writeUInt32LE(b.length,e[2].local+22);
    },
    'member overlaps next local header': (b,e) => {
        const size=b.readUInt32LE(e[0].central+20)+1;
        b.writeUInt32LE(size,e[0].central+20);b.writeUInt32LE(size,e[0].central+24);
        b.writeUInt32LE(size,e[0].local+18);b.writeUInt32LE(size,e[0].local+22);
    },
    'extra field points beyond data area': (b,e) => b.writeUInt16LE(65535,e[1].local+28),
    'central entry points to another disk': (b,e) => b.writeUInt16LE(1,e[1].central+34),
    'unsupported compression method': (b,e) => {b.writeUInt16LE(99,e[1].central+10);b.writeUInt16LE(99,e[1].local+8);}
})) test(`reject ${name} despite intact directory and EOCD`,async()=>{
    const bytes=packageBytes();mutate(bytes,zipEntries(bytes));
    const f=fixture();const work=f.run();f.resolve(binaryResponse({blob:async()=>new Blob([bytes])}));
    assert.equal(await work,false);assert.equal(f.downloads.length,0);assert.equal(f.alerts.length,1);
    assert.equal(f.elements.get('exportVisio').disabled,false);
});
for (const descriptor of ['signed','unsigned']) test(`accept nonempty streaming ZIP with ${descriptor} descriptor`,async()=>{
    const bytes=packageBytes(undefined,{descriptor});const f=fixture();const work=f.run();
    f.resolve(binaryResponse({blob:async()=>new Blob([bytes])}));assert.equal(await work,true);assert.equal(f.downloads.length,1);
});
test('reject inconsistent streaming descriptor without inflating data',async()=>{
    const bytes=packageBytes(undefined,{descriptor:'signed'});const e=zipEntries(bytes)[1];
    const dataEnd=e.local+30+bytes.readUInt16LE(e.local+26)+bytes.readUInt32LE(e.central+20);
    bytes.writeUInt32LE(0,dataEnd+8);
    const f=fixture();const work=f.run();f.resolve(binaryResponse({blob:async()=>new Blob([bytes])}));
    assert.equal(await work,false);assert.equal(f.downloads.length,0);
});
for (const format of ['visio','sparx']) test(`reject empty required ${format} member`,async()=>{
    const names=format==='visio'?undefined:['architecture.xmi','manifest.json','README.txt'];
    const bytes=packageBytes(names,{empty:format==='visio'?'visio/document.xml':'architecture.xmi'});
    const f=fixture();const work=format==='visio'?f.run():f.api.exportSparx('original');
    f.resolve(binaryResponse({headers:{get:()=>format==='visio'?'application/vnd.ms-visio.drawing':'application/zip'},blob:async()=>new Blob([bytes])}));
    assert.equal(await work,false);assert.equal(f.downloads.length,0);
});

test('Sparx export help is registered rather than orphaned repository documentation',()=>{
    const root=path.resolve(__dirname, '../../../..');
    const controller=readFileSync(path.join(root,'taxonomy-app/src/main/java/com/taxonomy/shared/controller/HelpController.java'),'utf8');
    assert.match(controller,/new String\[\]\{"SPARX_CURRENT_VIEW_EXPORT"/);
});
test('Sparx export help has paired documents and translated table-of-contents entries',()=>{
    const root=path.resolve(__dirname, '../../../..');
    const config=readFileSync(path.join(root,'taxonomy-app/src/main/java/com/taxonomy/shared/config/I18nConfig.java'),'utf8');
    assert.match(config,/"messages_sparx_export"/);
    for(const language of ['en','de']) {
        const document=path.join(root,'docs',language,'SPARX_CURRENT_VIEW_EXPORT.md');
        assert.ok(existsSync(document), 'Missing guide: ' + language);
        const text=readFileSync(document,'utf8');
        assert.match(text,/architecture\.xmi/);assert.match(text,/manifest\.json/);
        const bundle=readFileSync(path.join(root,'taxonomy-app/src/main/resources/i18n',language==='en'?'messages_sparx_export.properties':'messages_sparx_export_de.properties'),'utf8');
        assert.match(bundle,/^help\.toc\.SPARX_CURRENT_VIEW_EXPORT=.+$/m);
    }
});
