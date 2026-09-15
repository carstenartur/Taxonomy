"""Disposable review verification helper. Never included in the product tree."""
from pathlib import Path
import sys

ROOT = Path('.')
CONTROL = 'taxonomy-app/src/main/java/com/taxonomy/analysis/service/AnalysisRunControl.java'
REGISTRY = 'taxonomy-app/src/main/java/com/taxonomy/analysis/service/AnalysisProgressRegistry.java'
AUTH = 'taxonomy-app/src/main/java/com/taxonomy/security/config/AuthorizationRulesConfigurer.java'
PROGRESS = 'taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js'
SCORING = 'taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js'
JS_TEST = '.github/scripts/analysis-live-progress.test.mjs'
BASE_TEST = '.github/scripts/test-taxonomy-base-path.mjs'
UI_TEST = '.github/scripts/ui-role-state-flow.mjs'
JAVA_TESTS = [
 'taxonomy-app/src/test/java/com/taxonomy/analysis/service/AnalysisRunControlBoundaryTest.java',
 'taxonomy-app/src/test/java/com/taxonomy/security/config/AnalysisRunAuthorizationTest.java']
FILES = [CONTROL, REGISTRY, AUTH, PROGRESS, SCORING, JS_TEST, BASE_TEST, UI_TEST, *JAVA_TESTS]

def replace(path, old, new):
    p = ROOT / path
    text = p.read_text()
    assert text.count(old) == 1, (path, old[:100], text.count(old))
    p.write_text(text.replace(old, new))

if sys.argv[1] == 'tests':
    with (ROOT / JS_TEST).open('a') as out:
        out.write('''

test('terminal observation never sends a later cancellation write', async () => {
    const f = fixture(async () => response(snapshot(2, 'COMPLETED')));
    await f.step(0);
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 1);
    assert.ok(f.calls.every(call => !call.options.method));
    assert.equal(f.timers.size, 0);
});

test('explicitly stopped monitoring cannot cancel a superseded run', async () => {
    const f = fixture(async () => response(snapshot()));
    f.monitor.stop();
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 0);
    assert.equal(f.timers.size, 0);
});
''')
    replace(UI_TEST,
        "  passed('analysis provider error retains hierarchy and retry action');",
        """  await page.waitForFunction(() => {
    const panel = document.getElementById('analysisLiveProgress');
    return panel?.querySelector('button')?.disabled
      && /Analysis ended|Analyse beendet/.test(panel.textContent || '');
  });
  passed('rejected analysis finalizes live progress and disables cancellation');
  passed('analysis provider error retains hierarchy and retry action');""")

elif sys.argv[1] == 'fix':
    replace(AUTH,
        '        auth.requestMatchers(HttpMethod.POST, "/api/analyze").authenticated();',
        '        auth.requestMatchers(HttpMethod.POST, "/api/analyze").authenticated();\n'
        '        auth.requestMatchers(HttpMethod.POST, "/api/analysis-runs/*/cancel").authenticated();')
    p = ROOT / CONTROL
    text = p.read_text()
    start = text.index('    public static LlmCallDetail call(')
    end = text.index('    public static void pause(', start)
    text = text[:start] + '''    public static LlmCallDetail call(String provider, String node, Supplier<LlmCallDetail> operation) {
        checkpoint();
        var current = CURRENT.get();
        long id = current == null ? 0 : current.observer.started(provider, node);
        long started = System.nanoTime();
        LlmCallDetail detail;
        try {
            detail = operation.get();
        } catch (AnalysisStoppedException stopped) {
            // A cooperative stop is not a provider failure, including stops inside retries.
            if (current != null) current.observer.stopped(stopped.reason());
            throw stopped;
        } catch (RuntimeException failure) {
            if (current != null) current.observer.failed(id, failure.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            throw failure;
        }
        try {
            // The final provider call may outlive cancellation, the deadline or heap reserves.
            checkpoint();
        } catch (AnalysisStoppedException stopped) {
            throw stopped.withPartial(detail);
        }
        if (current != null) current.observer.completed(id, detail, (System.nanoTime() - started) / 1_000_000);
        return detail;
    }

''' + text[end:]
    p.write_text(text)
    replace(REGISTRY, '        final long id, startedAt;',
            '        final long id, startedAt;\n        final long startedNanos = System.nanoTime();')
    replace(REGISTRY,
        '            stopReason = reason.name();\n            phase("STOPPING", null);',
        '''            stopReason = reason.name();
            long now = System.nanoTime();
            calls.stream().filter(call -> "STARTED".equals(call.status)).forEach(call -> {
                call.status = "STOPPED";
                call.duration = TimeUnit.NANOSECONDS.toMillis(Math.max(0, now - call.startedNanos));
            });
            phase("STOPPING", null);''')
    replace(PROGRESS, 'if (cancelling) return false;', 'if (stopped || cancelling) return false;')
    replace(PROGRESS, "'btn btn-sm btn-outline-danger mt-2'", "'btn btn-sm btn-danger mt-2'")
    p = ROOT / SCORING
    text = p.read_text()
    start = text.index('    function runAnalysis()')
    end = text.index('    function runInteractiveAnalysis()', start)
    section = text[start:end]
    old = "if (!r.ok) throw new Error('HTTP ' + r.status);"
    assert section.count(old) == 1
    section = section.replace(old, """if (!r.ok) {
                    const error = new Error('HTTP ' + r.status);
                    error.httpStatus = r.status;
                    throw error;
                }""")
    old = 'if (progress) { progress.cancel(); progress.stop(); }'
    assert section.count(old) == 1
    section = section.replace(old, '''if (progress) {
                    // A rejected HTTP request is not an active job. A lost connection may be.
                    if (!err.httpStatus) progress.cancel();
                    progress.finish('ERROR');
                }''')
    p.write_text(text[:start] + section + text[end:])
    replace(BASE_TEST,
        "    '/taxonomy/js/core/taxonomy-analysis-session-transport.js',",
        "    '/taxonomy/js/core/taxonomy-analysis-session-transport.js',\n"
        "    '/taxonomy/js/core/taxonomy-analysis-progress.js',")

elif sys.argv[1] == 'publish-blobs':
    import base64, hashlib, json, os, subprocess, tempfile, urllib.request, zipfile
    destination = Path('target/review-evidence')
    destination.mkdir(parents=True, exist_ok=True)
    base = 'f1152fab618379e8e9afd9b9852b4cfabf323814'
    index = destination / 'candidate-index'
    env = dict(os.environ, GIT_INDEX_FILE=str(index.resolve()))
    subprocess.run(['git', 'read-tree', base], env=env, check=True)
    files = []
    with zipfile.ZipFile(destination / 'tested-sources.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in FILES:
            content = Path(name).read_bytes()
            sha = subprocess.check_output(['git', 'hash-object', '-w', name], text=True).strip()
            request = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/git/blobs',
                data=json.dumps({'content': base64.b64encode(content).decode(), 'encoding': 'base64'}).encode(),
                headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                         'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'}, method='POST')
            with urllib.request.urlopen(request) as response:
                assert json.load(response)['sha'] == sha
            subprocess.run(['git', 'update-index', '--add', '--cacheinfo', '100644', sha, name], env=env, check=True)
            files.append({'path': name, 'mode': '100644', 'type': 'blob', 'sha': sha})
            archive.writestr(name, content)
    tree = subprocess.check_output(['git', 'write-tree'], env=env, text=True).strip()
    patch = subprocess.check_output(['git', 'diff', '--binary', base, '--', *FILES])
    (destination / 'review-fix.patch').write_bytes(patch)
    manifest = {'base': base, 'testedTree': tree, 'files': files,
                'patchSha256': hashlib.sha256(patch).hexdigest(),
                'verificationCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()}
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))
else:
    raise SystemExit('Unknown operation')
