import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

function surface(submitImpl, historyAvailable = false) {
    const nodes = new Map();
    function node(id) {
        const value = { id, value: '', hidden: false, disabled: false, textContent: '', dataset: {}, handlers: {},
            addEventListener(type, fn) { this.handlers[type] = fn; }, replaceChildren() {}, append() {}, setAttribute() {}, removeAttribute() {} };
        nodes.set(id, value); return value;
    }
    for (const id of ['backupJobList','backupNotice','backupRefresh','backupPrevious','backupNext','backupPage',
        'backupExportForm','backupExportScope','backupExportProfile','backupExportCommit','backupExportSubmit','backupExportFeedback','backupCommitGroup','backupExportGit',
        'backupExportHistoryOption','backupExportCurrentHelp','backupExportHistoryHelp']) node(id);
    nodes.get('backupExportHistoryOption').value = 'REPOSITORY_HISTORY';
    nodes.get('backupExportProfile').options = [{value:'CURRENT_STATE'}, {value:'SELECTED_VERSION'}, nodes.get('backupExportHistoryOption')];
    nodes.get('backupExportGit').options = ['NONE','BUNDLE','BARE','WORKTREE'].map(value=>({value,hidden:false,disabled:false}));
    nodes.get('backupExportProfile').value = 'CURRENT_STATE';
    nodes.get('backupExportGit').value = 'NONE';
    nodes.get('backupExportScope').selectedOptions = [{dataset:{repositoryId:'repo-a',workspaceId:'private-a',installation:'false',historyAvailable:String(historyAvailable)}}];
    let ready, submitted = [];
    const api = {list:async()=>[],submit:async request=>{submitted.push(request);return submitImpl ? submitImpl(request) : {id:'job'};}};
    const document = {hidden:false,getElementById:id=>nodes.get(id),addEventListener(){},createElement:()=>node('generated')};
    const window = {BackupApi:api, TaxonomyI18n:{t:key=>key,getLocale:()=> 'en',ready:()=>({then:fn=>{ready=fn;}})},setInterval(){}};
    vm.runInNewContext(fs.readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/backup/backup-jobs.js',import.meta.url),'utf8'),{window,document,Intl,Date,Map});
    return {nodes,submitted,async submit(){ const handler=nodes.get('backupExportForm').handlers.submit; if(handler) await handler({preventDefault(){}}); },ready:()=>ready()};
}

test('normal export submits exact selected workspace through the existing API',async()=>{
    const s=surface();await s.submit();
    assert.equal(s.submitted.length,1);
    assert.deepEqual(JSON.parse(JSON.stringify(s.submitted[0])),{profile:'CURRENT_STATE',scope:{kind:'WORKSPACE',repositoryId:'repo-a',workspaceId:'private-a',workspacesByRepository:{}},time:{kind:'CURRENT',commitsByRepository:[]},gitRepresentation:'NONE',secrets:'EXCLUDE'});
});
test('selected version requires an exact commit before submitting',async()=>{
    const s=surface();s.nodes.get('backupExportProfile').value='SELECTED_VERSION';await s.submit();
    assert.equal(s.submitted.length,0);assert.ok(s.nodes.get('backupExportFeedback').textContent);
    s.nodes.get('backupExportCommit').value='a'.repeat(40);await s.submit();
    assert.equal(s.submitted.length,1);assert.equal(s.submitted[0].time.commitsByRepository[0].commit,'a'.repeat(40));
});


test('central repository and installation use their exact independent wire scopes',async()=>{
    const central=surface();central.nodes.get('backupExportScope').selectedOptions=[{dataset:{repositoryId:'repo-a',installation:'false'}}];
    central.nodes.get('backupExportGit').value='BUNDLE';await central.submit();
    assert.deepEqual(JSON.parse(JSON.stringify(central.submitted[0].scope)),{kind:'REPOSITORIES',repositoryId:null,workspaceId:null,workspacesByRepository:{'repo-a':[]}});
    assert.equal(central.submitted[0].gitRepresentation,'BUNDLE');
    const installation=surface();installation.nodes.get('backupExportScope').selectedOptions=[{dataset:{installation:'true'}}];
    installation.nodes.get('backupExportProfile').value='SELECTED_VERSION';installation.nodes.get('backupExportScope').handlers.change();await installation.submit();
    assert.equal(installation.nodes.get('backupExportProfile').disabled,true);
    assert.equal(installation.nodes.get('backupExportProfile').value,'CURRENT_STATE');
    assert.equal(installation.nodes.get('backupCommitGroup').hidden,true);
    assert.equal(installation.submitted[0].profile,'INSTALLATION_CURRENT');assert.equal(installation.submitted[0].scope.kind,'INSTALLATION');
});
test('pending submission prevents duplicate jobs and a permission failure restores controls',async()=>{
    let reject;const s=surface(()=>new Promise((resolve,fail)=>{reject=fail;}));
    const pending=s.submit();await s.submit();assert.equal(s.submitted.length,1);assert.equal(s.nodes.get('backupExportSubmit').disabled,true);
    reject({status:403});await pending;assert.equal(s.nodes.get('backupExportSubmit').disabled,false);
    assert.equal(s.nodes.get('backupExportFeedback').textContent,'backup.export.denied');
});
test('missing scope and non-exact version strings cannot submit',async()=>{
    const s=surface();s.nodes.get('backupExportScope').selectedOptions=[];await s.submit();assert.equal(s.submitted.length,0);
    s.nodes.get('backupExportScope').selectedOptions=[{dataset:{repositoryId:'repo-a',workspaceId:'private-a',installation:'false'}}];
    s.nodes.get('backupExportProfile').value='SELECTED_VERSION';
    for(const invalid of ['HEAD','a'.repeat(39),'A'.repeat(40),'a'.repeat(41)]){s.nodes.get('backupExportCommit').value=invalid;await s.submit();}
    assert.equal(s.submitted.length,0);
});

test('hidden commit field cannot block native validation of a current export',()=>{
    const s=surface();assert.equal(s.nodes.get('backupExportCommit').disabled,true);
    s.nodes.get('backupExportProfile').value='SELECTED_VERSION';s.nodes.get('backupExportProfile').handlers.change();
    assert.equal(s.nodes.get('backupExportCommit').disabled,false);assert.equal(s.nodes.get('backupExportCommit').required,true);
    s.nodes.get('backupExportProfile').value='CURRENT_STATE';s.nodes.get('backupExportProfile').handlers.change();
    assert.equal(s.nodes.get('backupExportCommit').disabled,true);assert.equal(s.nodes.get('backupExportCommit').required,false);
});

test('a current-only selection exposes neither history nor historical Git layouts',()=>{
    const s=surface();
    assert.equal(s.nodes.get('backupExportHistoryOption').hidden,true);
    assert.equal(s.nodes.get('backupExportHistoryOption').disabled,true);
    assert.equal(s.nodes.get('backupExportCurrentHelp').hidden,false);
    assert.equal(s.nodes.get('backupExportHistoryHelp').hidden,true);
    for(const option of s.nodes.get('backupExportGit').options) {
        assert.equal(option.hidden,['BARE','WORKTREE'].includes(option.value));
        assert.equal(option.disabled,['BARE','WORKTREE'].includes(option.value));
    }
});

for(const representation of ['BUNDLE','BARE','WORKTREE']) {
    test(`authorized history sends an exact history request with ${representation}`,async()=>{
        const s=surface(undefined,true);
        assert.equal(s.nodes.get('backupExportProfile').value,'CURRENT_STATE','Current export remains the initial selection');
        assert.equal(s.nodes.get('backupExportHistoryOption').hidden,false);
        s.nodes.get('backupExportProfile').value='REPOSITORY_HISTORY';
        s.nodes.get('backupExportProfile').handlers.change();
        assert.equal(s.nodes.get('backupExportGit').value,'BUNDLE','History must start with a supported complete Git layout');
        assert.equal(s.nodes.get('backupCommitGroup').hidden,true);
        assert.equal(s.nodes.get('backupExportCommit').disabled,true);
        assert.equal(s.nodes.get('backupExportCurrentHelp').hidden,true);
        assert.equal(s.nodes.get('backupExportHistoryHelp').hidden,false);
        for(const option of s.nodes.get('backupExportGit').options) {
            assert.equal(option.hidden,option.value==='NONE');
            assert.equal(option.disabled,option.value==='NONE');
        }
        s.nodes.get('backupExportGit').value=representation;await s.submit();
        assert.deepEqual(JSON.parse(JSON.stringify(s.submitted)),[{profile:'REPOSITORY_HISTORY',
            scope:{kind:'WORKSPACE',repositoryId:'repo-a',workspaceId:'private-a',workspacesByRepository:{}},
            time:{kind:'HISTORY',commitsByRepository:[]},gitRepresentation:representation,secrets:'EXCLUDE'}]);
    });
}

test('history stays on the explicitly chosen central repository',async()=>{
    const s=surface(undefined,true);
    s.nodes.get('backupExportScope').selectedOptions=[{dataset:{repositoryId:'repo-b',installation:'false',historyAvailable:'true'}}];
    s.nodes.get('backupExportScope').handlers.change();
    s.nodes.get('backupExportProfile').value='REPOSITORY_HISTORY';s.nodes.get('backupExportProfile').handlers.change();
    await s.submit();
    assert.equal(s.submitted[0].time.kind,'HISTORY');
    assert.deepEqual(JSON.parse(JSON.stringify(s.submitted[0].scope)),{kind:'REPOSITORIES',repositoryId:null,workspaceId:null,workspacesByRepository:{'repo-b':[]}});
});

test('changing to a current-only workspace resets history and its incompatible representation',async()=>{
    const s=surface(undefined,true);
    s.nodes.get('backupExportProfile').value='REPOSITORY_HISTORY';s.nodes.get('backupExportProfile').handlers.change();
    s.nodes.get('backupExportGit').value='WORKTREE';
    s.nodes.get('backupExportScope').selectedOptions=[{dataset:{repositoryId:'repo-b',workspaceId:'private-b',installation:'false',historyAvailable:'false'}}];
    s.nodes.get('backupExportScope').handlers.change();
    assert.equal(s.nodes.get('backupExportProfile').value,'CURRENT_STATE');
    assert.equal(s.nodes.get('backupExportGit').value,'NONE');
    assert.equal(s.nodes.get('backupExportHistoryOption').hidden,true);
    assert.equal(s.nodes.get('backupExportHistoryHelp').hidden,true);
    await s.submit();assert.equal(s.submitted[0].profile,'CURRENT_STATE');assert.equal(s.submitted[0].scope.workspaceId,'private-b');
});

test('changing history to a selected version restores exact commit input and current Git formats',async()=>{
    const s=surface(undefined,true);
    s.nodes.get('backupExportProfile').value='REPOSITORY_HISTORY';s.nodes.get('backupExportProfile').handlers.change();
    s.nodes.get('backupExportGit').value='BARE';
    s.nodes.get('backupExportProfile').value='SELECTED_VERSION';s.nodes.get('backupExportProfile').handlers.change();
    assert.equal(s.nodes.get('backupExportGit').value,'NONE');
    assert.equal(s.nodes.get('backupExportCommit').disabled,false);assert.equal(s.nodes.get('backupExportCommit').required,true);
    await s.submit();assert.equal(s.submitted.length,0);
    s.nodes.get('backupExportCommit').value='b'.repeat(40);await s.submit();
    assert.equal(s.submitted[0].profile,'SELECTED_VERSION');assert.equal(s.submitted[0].time.kind,'SELECTED_VERSION');
});

test('installation switch offers the supported current installation without inheriting history',async()=>{
    const s=surface(undefined,true);
    s.nodes.get('backupExportProfile').value='REPOSITORY_HISTORY';s.nodes.get('backupExportProfile').handlers.change();
    s.nodes.get('backupExportGit').value='WORKTREE';
    s.nodes.get('backupExportScope').selectedOptions=[{dataset:{installation:'true',historyAvailable:'false'}}];
    s.nodes.get('backupExportScope').handlers.change();
    assert.equal(s.nodes.get('backupExportProfile').disabled,true);assert.equal(s.nodes.get('backupExportHistoryOption').hidden,true);
    assert.equal(s.nodes.get('backupExportGit').value,'NONE');
    await s.submit();assert.equal(s.submitted[0].profile,'INSTALLATION_CURRENT');assert.equal(s.submitted[0].time.kind,'CURRENT');
});

test('unavailable history and unsupported representations cannot silently create another export',async()=>{
    const s=surface();
    s.nodes.get('backupExportProfile').value='REPOSITORY_HISTORY';await s.submit();
    assert.equal(s.submitted.length,0);
    s.nodes.get('backupExportProfile').value='CURRENT_STATE';
    for(const invalid of ['BARE','WORKTREE','INVALID']) {s.nodes.get('backupExportGit').value=invalid;await s.submit();}
    assert.equal(s.submitted.length,0);
    const history=surface(undefined,true);
    history.nodes.get('backupExportProfile').value='REPOSITORY_HISTORY';history.nodes.get('backupExportProfile').handlers.change();
    history.nodes.get('backupExportGit').value='NONE';await history.submit();assert.equal(history.submitted.length,0);
});
