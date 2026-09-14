from pathlib import Path
import shutil
ROOT=Path('taxonomy-app/src/main')
TOOLS=Path(__file__).resolve().parents[2]
def change(path,old,new):
    p=Path(path);s=p.read_text();assert s.count(old)==1,(str(path),old[:80],s.count(old))
    p.write_text(s.replace(old,new))
relative=Path('taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js')
shutil.copyfile(TOOLS/relative,relative)
loader=ROOT/'resources/static/js/core/taxonomy-analysis-session.js'
change(loader,"        '/js/core/taxonomy-analysis-session-transport.js',", "        '/js/core/taxonomy-analysis-session-transport.js',\n        '/js/core/taxonomy-analysis-progress.js',")
browse=ROOT/'resources/static/js/core/taxonomy-browse.js'
change(browse,"""                if (S.currentView === 'list' || S.currentView === 'tabs') {
                    SC().runStreamingAnalysis();
                } else {
                    SC().runAnalysis();
                }""", """                // Visualization never selects a different analysis algorithm or loses architecture options.
                SC().runAnalysis();""")
p=ROOT/'resources/static/js/core/taxonomy-scoring.js'
s=p.read_text();start=s.index('    function runAnalysis()');end=s.index('    // ── Interactive analysis',start)
part=s[start:end]
needle='        const analysisStart = new Date();'
assert needle in part
part=part.replace(needle,needle+'''
        var operationId = typeof crypto.randomUUID === 'function' ? crypto.randomUUID()
            : '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, function (c) {
                return (Number(c) ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> Number(c) / 4).toString(16);
            });
        S.currentReasons = {};
        S.lastAnalysisStatus = 'IN_PROGRESS';
        applyLocalRawScores({}, true);
        var progress = window.TaxonomyAnalysisProgress
            ? window.TaxonomyAnalysisProgress.start(operationId, function (snapshot) {
                var previous = S.currentEffectiveScores || {};
                var previousRaw = S.currentRawScores || {};
                applyLocalRawScores(snapshot.rawScores || {}, true);
                var changed = Object.keys(S.currentScores || {}).filter(function (code) {
                    return previous[code] !== S.currentScores[code] || previousRaw[code] !== S.currentRawScores[code];
                });
                if (!changed.length) return;
                if (S.currentView === 'list' || S.currentView === 'tabs') {
                    changed.forEach(function (code) {
                        applyScoreToNode(code, S.currentScores[code], null,
                            S.currentScoreDetails[code], S.currentRawScores[code]);
                    });
                } else {
                    B().renderView(S.taxonomyData, S.currentScores);
                }
            }) : null;
''')
old="headers: { 'Content-Type': 'application/json' },"
assert part.count(old)==1
part=part.replace(old,"headers: { 'Content-Type': 'application/json', 'X-Analysis-Operation-Id': operationId },")
part=part.replace('            .then(result => {\n                setAnalyzing(false);',
                  '            .then(result => {\n                if (progress) progress.stop();\n                setAnalyzing(false);')
part=part.replace('                S.taxonomyData = result.tree;',
                  '                if (Array.isArray(result.tree) && result.tree.length) S.taxonomyData = result.tree;')
part=part.replace('            .catch(err => {\n                setAnalyzing(false);',
                  '            .catch(err => {\n                if (progress) { progress.cancel(); progress.stop(); }\n                setAnalyzing(false);')
s=s[:start]+part+s[end:]
p.write_text(s)
# Preserve the existing SSE compatibility test while changing its deliberate timeout contract.
change('taxonomy-app/src/test/java/com/taxonomy/analysis/controller/AnalysisApiControllerTest.java',
       'assertThat(emitter.getTimeout()).isEqualTo(120_000L);','assertThat(emitter.getTimeout()).isEqualTo(1_800_000L);')
# If a mixed category/product sibling group is stopped, category evidence must survive too.
p=ROOT/'java/com/taxonomy/analysis/service/LlmService.java'
change(p,'''        LlmCallDetail categoryDetail =
                callLlmPropagatingDetailed(businessText, categories, parentScore);
        LlmCallDetail productDetail = callProductBatchesDetailed(businessText, products);''',
'''        LlmCallDetail categoryDetail =
                callLlmPropagatingDetailed(businessText, categories, parentScore);
        LlmCallDetail productDetail;
        try {
            productDetail = callProductBatchesDetailed(businessText, products);
        } catch (AnalysisStoppedException stopped) {
            throw stopped.withPartial(categoryDetail);
        }''')
print('Integrated full browser analysis, live score updates and preserved partial evidence')
