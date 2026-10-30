/* Read-only intake evidence and explicit user choices shared by requirement entry surfaces. */
(function () {
    'use strict';
    var forms = new WeakMap();
    var de = String(document.documentElement.lang || 'en').startsWith('de');
    function label(en, german) { return de ? german : en; }
    function scope() { return window.TaxonomyArchitectureWorkflow?.getScope() || {}; }
    function scopeKey(value) { return JSON.stringify([value.repositoryId || '', value.workspaceScopeKey ?? (value.workspaceId || '__shared__'), value.branch || '']); }
    function project(state) { var value = typeof state.options.projectId === 'function' ? state.options.projectId() : state.options.projectId; return value ? Number(value) : null; }
    function fingerprint(state, text, projectId) {
        var controls=Array.from(state.form.querySelectorAll?.('input,textarea,select')||[])
            .map(control=>[control.type,control.type==='checkbox'?control.checked:control.value]);
        return JSON.stringify([text, projectId, scopeKey(scope()),controls,state.options.versionTarget||null]);
    }
    function node(tag, text) { var value = document.createElement(tag); if (text !== undefined) value.textContent = text; return value; }
    function cancel(state) { state.selected=null; if (state.choose) { state.choose(null); state.choose = null; } }
    function invalidate(state) {
        state.generation++; state.response = null; state.selected = null; state.pending = null; cancel(state);
        state.section.replaceChildren(node('p', label('Review updates when the text is ready.', 'Die Prüfung wird nach der Texteingabe aktualisiert.')));
        window.clearTimeout(state.timer);
        state.timer = window.setTimeout(function () { review(state); }, 650);
    }
    function attach(form, options) {
        if (forms.has(form)) {
            var existing=forms.get(form);
            if(options?.versionTarget && JSON.stringify(existing.options.versionTarget)!==JSON.stringify(options.versionTarget)) {
                existing.options.versionTarget=options.versionTarget;invalidate(existing);review(existing);
            }
            return existing;
        }
        if (!options?.textElement) throw new Error('Requirement intake needs a text field');
        var section = node('section'); section.className = 'requirement-intake-review border rounded p-3 my-3';
        section.setAttribute('aria-label', label('Requirement review', 'Anforderungsprüfung')); section.setAttribute('aria-live', 'polite');
        var state = {form:form,options:options,section:section,generation:0,response:null,pending:null,selected:null,choose:null};
        forms.set(form,state); form.append(section);
        options.textElement.addEventListener('input',function () { invalidate(state); });
        form.addEventListener('input',function (event) { if(event.target!==options.textElement) invalidate(state); });
        form.addEventListener('change',function () { invalidate(state); });
        var dialog = form.closest?.('dialog, .modal');
        if (dialog) { ['close','cancel','hidden.bs.modal'].forEach(function(event){dialog.addEventListener(event,function(){cancel(state);});}); }
        review(state); return state;
    }
    function matching(state, capture) { return capture === fingerprint(state,state.options.textElement.value,project(state)); }
    function review(state) {
        var text = state.options.textElement.value;
        if (!text || !text.trim()) return Promise.resolve(null);
        var projectId = project(state), capture = fingerprint(state,text,projectId);
        if (state.pending?.capture === capture) return state.pending.promise;
        if (state.response?.capture === capture) return Promise.resolve(state.response.value);
        var generation = ++state.generation;
        state.section.replaceChildren(node('p',label('Reviewing possible duplicates, changes, additions and conflicts…','Mögliche Duplikate, Änderungen, Ergänzungen und Konflikte werden geprüft…')));
        var api = window.TaxonomyArchitectureLifecycleApi;
        var operation;
        try { operation = api?.reviewRequirementIntake({projectId:projectId,text:text,analysisLanguage:de?'de':'en'}); }
        catch (error) { operation = Promise.reject(error); }
        var promise = Promise.resolve(operation).then(async function (response) {
            if (!response) throw new Error('Review unavailable');
            if (generation !== state.generation || !matching(state,capture)) return null;
            if (!response.scope || scopeKey(response.scope) !== scopeKey(scope())) throw new Error('Review scope changed');
            if (response.inputHash !== await inputHash(text)) throw new Error('Review input changed');
            if (generation !== state.generation || !matching(state,capture)) return null;
            state.response = {capture:capture,value:response}; render(state,response,capture); return response;
        }).catch(async function () {
            if (generation !== state.generation || !matching(state,capture)) return null;
            var response = {status:'UNAVAILABLE',code:'REVIEW_UNAVAILABLE',inputHash:await inputHash(text),scope:scope(),candidates:[]};
            if (generation !== state.generation || !matching(state,capture)) return null;
            state.response = {capture:capture,value:response}; render(state,response,capture); return response;
        });
        state.pending = {capture:capture,promise:promise};
        promise.finally(function () { if (state.pending?.promise === promise) state.pending = null; });
        return promise;
    }
    function choose(state,choice,candidate,capture,response) {
        if (!matching(state,capture)) { invalidate(state); return; }
        state.selected = {choice:choice,projectId:candidate?.projectId ?? project(state),
            requirementId:candidate?.requirementId ?? null,expectedVersionId:candidate?.requirementVersionId ?? null,
            expectedContentHash:candidate?.contentHash ?? null,inputHash:response.inputHash};
        if (state.choose) { var finish=state.choose;state.choose=null;finish(state.selected); }
    }
    function button(state,text,choice,candidate,capture,response) {
        var value=node('button',text);value.type='button';value.className='btn btn-outline-primary btn-sm me-2 mt-2';value.dataset.intakeChoice=choice;
        value.addEventListener('click',function(){choose(state,choice,candidate,capture,response);});return value;
    }
    function render(state,response,capture) {
        state.section.replaceChildren(node('h3',label('Review before saving','Prüfung vor dem Speichern')));
        var complete=response.status==='COMPLETE';
        state.section.append(node('p',complete
            ? label('Choose how to use this text. Suggestions do not change existing requirements.','Wählen Sie, wie der Text verwendet werden soll. Vorschläge ändern keine bestehenden Anforderungen.')
            : label('The review is incomplete or unavailable. This is not evidence that no match exists. Review the available candidates and choose explicitly.','Die Prüfung ist unvollständig oder nicht verfügbar. Das bedeutet nicht, dass kein Treffer existiert. Prüfen Sie die verfügbaren Kandidaten und wählen Sie ausdrücklich.')));
        if(state.options.versionTarget) state.section.append(button(state,
            label('Create a new version of this requirement','Neue Version dieser Anforderung erstellen'),
            'CHANGE',state.options.versionTarget,capture,response));
        (response.candidates||[]).forEach(function(candidate){
            if(candidate.classification==='NONE') return;
            var article=node('article');article.className='border-top pt-2 mt-2';
            article.append(node('strong',[candidate.requirementKey||candidate.projectKey||'',candidate.title||''].filter(Boolean).join(' · ')));
            if(candidate.sourceCommit) article.append(node('p',[
                candidate.sourceVersion?label('Source version ','Quellversion ')+candidate.sourceVersion:null,
                label('Commit ','Commit ')+candidate.sourceCommit.slice(0,12)
            ].filter(Boolean).join(' · ')));
            if(candidate.classification) article.append(node('p',candidate.classification+(candidate.rationale?' — '+candidate.rationale:'')));
            (candidate.quotes||[]).forEach(function(quote){article.append(node('blockquote',quote));});
            var local=candidate.repositoryId===response.scope?.repositoryId
                && (candidate.workspaceId||'')===(response.scope?.workspaceId||'') && candidate.branch===response.scope?.branch;
            if(!state.options.versionTarget&&local&&candidate.requirementId&&candidate.requirementVersionId&&candidate.contentHash){
                article.append(button(state,label('New version','Neue Version'),'CHANGE',candidate,capture,response),
                    button(state,label('Link existing requirement','Bestehende Anforderung verknüpfen'),'LINK',candidate,capture,response));
            } else if(local&&candidate.projectId) article.append(button(state,label('Add to this project','Diesem Projekt hinzufügen'),'NEW',candidate,capture,response));
            var link=node('a',label('View existing','Bestehenden Stand ansehen'));link.target='_blank';link.rel='noopener';link.className='btn btn-link btn-sm';
            var path=candidate.projectId&&candidate.requirementId?'/projects/'+candidate.projectId+'/requirements/'+candidate.requirementId:'/architecture/editor';
            var url=new URL(window.TaxonomyI18n?.resolveUrl?.(path)||path,window.location.href);
            url.searchParams.set('repositoryId',candidate.repositoryId);url.searchParams.set('workspaceId',candidate.workspaceId||'');url.searchParams.set('branch',candidate.branch);
            if(candidate.sourceCommit) url.searchParams.set('commit',candidate.sourceCommit);
            link.href=url.href;
            article.append(link);state.section.append(article);
        });
        state.section.append(button(state,label('Create a separate new requirement','Separate neue Anforderung erstellen'),'NEW',null,capture,response));
        var stop=node('button',label('Cancel','Abbrechen'));stop.type='button';stop.className='btn btn-outline-secondary btn-sm mt-2';stop.addEventListener('click',function(){cancel(state);});state.section.append(stop);
    }
    async function prepare(form,payload,projectId) {
        var state=forms.get(form);
        if(!state) throw new Error(label('Requirement review is not ready.','Die Anforderungsprüfung ist nicht bereit.'));
        if(projectId!==undefined && typeof state.options.projectId!=='function') state.options.projectId=projectId;
        if(payload.text!==state.options.textElement.value) return null;
        var capture=fingerprint(state,payload.text,project(state));
        var response=await review(state);
        if(!response||!matching(state,capture)) return null;
        var choice=state.selected;
        if(!choice){state.section.focus();choice=await new Promise(function(resolve){state.choose=resolve;});}
        if(!choice||!matching(state,capture)) return null;
        state.selected=null;
        state.commandCapture=capture;
        return Object.assign({},choice,{requirement:payload});
    }
    function isCurrent(form) { var state=forms.get(form);return Boolean(state?.commandCapture&&matching(state,state.commandCapture)); }
    async function prepareVersion(form,payload,projectId,requirementId,versionId,contentHash) {
        if(!projectId||!requirementId||!versionId||!/^[0-9a-f]{64}$/i.test(contentHash||''))
            throw new Error(label('The original requirement version is required. Reload its details before changing it.','Die ursprüngliche Anforderungsversion ist erforderlich. Laden Sie vor einer Änderung ihre Details neu.'));
        var state=forms.get(form);
        if(!state) throw new Error('Requirement review is not ready');
        attach(form,{versionTarget:{projectId:Number(projectId),requirementId:Number(requirementId),requirementVersionId:Number(versionId),contentHash:contentHash}});
        var command=await prepare(form,payload,projectId);
        if(command?.choice==='CHANGE'&&(command.requirementId!==Number(requirementId)||command.expectedVersionId!==Number(versionId)||command.expectedContentHash!==contentHash))
            throw new Error(label('The reviewed original version changed.','Die geprüfte ursprüngliche Version wurde geändert.'));
        return command;
    }
    // The fallback hashes exact UTF-8 input on deployments without secure-context WebCrypto.
    async function inputHash(text) {
        var bytes=new TextEncoder().encode(text);
        if(window.crypto?.subtle) return Array.from(new Uint8Array(await window.crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
        var k=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
        var h=[0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
        var padded=new Uint8Array(Math.ceil((bytes.length+9)/64)*64);padded.set(bytes);padded[bytes.length]=128;
        var view=new DataView(padded.buffer);view.setUint32(padded.length-8,Math.floor(bytes.length/0x20000000));view.setUint32(padded.length-4,bytes.length*8);
        function r(value,bits){return (value>>>bits)|(value<<(32-bits));}
        for(var offset=0;offset<padded.length;offset+=64){var w=new Uint32Array(64);for(var i=0;i<16;i++)w[i]=view.getUint32(offset+i*4);
            for(i=16;i<64;i++){var s0=r(w[i-15],7)^r(w[i-15],18)^(w[i-15]>>>3),s1=r(w[i-2],17)^r(w[i-2],19)^(w[i-2]>>>10);w[i]=(w[i-16]+s0+w[i-7]+s1)>>>0;}
            var a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];
            for(i=0;i<64;i++){var t1=(hh+(r(e,6)^r(e,11)^r(e,25))+((e&f)^(~e&g))+k[i]+w[i])>>>0;var t2=((r(a,2)^r(a,13)^r(a,22))+((a&b)^(a&c)^(b&c)))>>>0;hh=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0;}
            [a,b,c,d,e,f,g,hh].forEach(function(value,index){h[index]=(h[index]+value)>>>0;});}
        return h.map(value=>value.toString(16).padStart(8,'0')).join('');
    }
    window.TaxonomyRequirementIntake={attach:attach,prepare:prepare,prepareVersion:prepareVersion,inputHash:inputHash,isCurrent:isCurrent};
}());
