from pathlib import Path
import re, shutil
ROOT=Path('taxonomy-app/src/main')
TOOLS=Path(__file__).resolve().parents[2]
def change(path,old,new,count=1):
    p=Path(path);s=p.read_text()
    assert s.count(old)==count,(str(path),old[:100],s.count(old),count)
    p.write_text(s.replace(old,new))
for name in ['AnalysisStoppedException','AnalysisMemoryGuard','AnalysisRunControl','AnalysisProgressRegistry']:
    relative=Path('taxonomy-app/src/main/java/com/taxonomy/analysis/service')/(name+'.java')
    assert not relative.exists(),relative
    shutil.copyfile(TOOLS/relative,relative)

llm=ROOT/'java/com/taxonomy/analysis/service/LlmService.java'
s=llm.read_text()
# Observe every real/mock/local batch before execution, not only after an answer.
s=s.replace('private LlmCallDetail callProductBatchDetailed(', 'private LlmCallDetail performProductBatchDetailed(')
s=s.replace('private LlmCallDetail callLlmPropagatingDetailed(', 'private LlmCallDetail performLlmPropagatingDetailed(')
marker='    private LlmCallDetail performProductBatchDetailed('
assert marker in s
wrappers='''    private LlmCallDetail callProductBatchDetailed(String businessText, List<TaxonomyNode> products) {
        return AnalysisRunControl.call(getActiveProviderName(), siblingScope(products),
                () -> performProductBatchDetailed(businessText, products));
    }

    private LlmCallDetail callLlmPropagatingDetailed(String businessText, List<TaxonomyNode> nodes, int parentScore) {
        return AnalysisRunControl.call(getActiveProviderName(), siblingScope(nodes),
                () -> performLlmPropagatingDetailed(businessText, nodes, parentScore));
    }

'''
s=s.replace(marker,wrappers+marker)
# Keep already evaluated product batches if the next batch is stopped.
old='            accumulator.add(callProductBatchDetailed(businessText, batch));'
assert old in s
s=s.replace(old,'''            try {
                accumulator.add(callProductBatchDetailed(businessText, batch));
            } catch (AnalysisStoppedException stopped) {
                throw stopped.withPartial(accumulator.result());
            }''')
# Root traversal owns the accumulated result and returns it on cooperative stop.
start=s.index('    public AnalysisResult analyzeWithBudget(')
end=s.index('    private void analyzeNodesPropagating(',start)
part=s[start:end]
part=part.replace('        boolean rateLimitHit = false;', '        boolean rateLimitHit = false;\n        AnalysisStoppedException stop = null;')
old='            } catch (LlmRateLimitException e) {'
assert old in part
part=part.replace(old,'''            } catch (AnalysisStoppedException stopped) {
                allScores.putAll(stopped.partialScores());
                allReasons.putAll(stopped.partialReasons());
                warnings.add(stopped.getMessage());
                stop = stopped;
                break;
            } catch (LlmRateLimitException e) {''')
part=part.replace('List<TaxonomyNodeDto> rawTree = taxonomyService.getFullTree();',
                  'List<TaxonomyNodeDto> rawTree = stop == null ? taxonomyService.getFullTree() : List.of();')
part=part.replace('        if (rateLimitHit) {\n            String msg', '        if (stop != null) {\n            result.setStatus("PARTIAL");\n            result.setErrorMessage(stop.getMessage());\n        } else if (rateLimitHit) {\n            String msg')
s=s[:start]+part+s[end:]
# Do not let generic provider/interactive exception handling turn a stop into another call.
start=s.index('    public LlmCallDetail analyzeSingleBatchDetailed(')
part=s[start:]
part=re.sub(r'} catch \(Exception (\w+)\) \{',r'} catch (AnalysisStoppedException stopped) {\n            throw stopped;\n        } catch (Exception \1) {',part)
s=s[:start]+part
# Preserve streaming partial scores too (legacy clients).
needle='''        } catch (Exception e) {
            log.error("Streaming analysis failed", e);'''
assert needle in s
s=s.replace(needle,'''        } catch (AnalysisStoppedException stopped) {
            allScores.putAll(stopped.partialScores());
            warnings.add(stopped.getMessage());
            callback.onError("PARTIAL", stopped.getMessage(),
                    allScores, warnings, allDiscrepancies, productCoverageGaps);
        } catch (Exception e) {
            log.error("Streaming analysis failed", e);''')
llm.write_text(s)

for name in ['OpenAiCompatibleGateway','GeminiGateway']:
    p=ROOT/'java/com/taxonomy/analysis/service'/(name+'.java')
    s=p.read_text()
    s,n=re.subn(r'(public String sendHttpRequest\([^)]*\)\s*\{)',r'\1\n        AnalysisRunControl.checkpoint();',s)
    assert n==1,(name,'entry',n)
    sleep=r'try\s*\{\s*Thread\.sleep\((backoffMs|sleepMs)\);\s*\}\s*catch\s*\(InterruptedException\s+\w+\)\s*\{\s*Thread\.currentThread\(\)\.interrupt\(\);\s*\}'
    s,n=re.subn(sleep,lambda m:'AnalysisRunControl.pause("'+('RETRY_WAIT' if m[1]=='backoffMs' else 'WAITING_RATE_LIMIT')+'", '+m[1]+');',s)
    assert n>=1,(name,'waits',n)
    s,n=re.subn(r'(\s+)(response = restTemplate.exchange\()',r'\1AnalysisRunControl.phase("LLM_REQUEST", null);\1\2',s)
    assert n==1,(name,'request',n)
    s=re.sub(r'} catch \(Exception (\w+)\) \{',r'} catch (AnalysisStoppedException stopped) {\n            throw stopped;\n        } catch (Exception \1) {',s)
    p.write_text(s)

use=ROOT/'java/com/taxonomy/analysis/usecase/AnalyzeRequirementUseCase.java'
change(use,'import org.springframework.stereotype.Service;','''import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.analysis.service.AnalysisStoppedException;''')
change(use,'    private final LlmService llmService;','''    @Autowired
    private AnalysisProgressRegistry analysisProgressRegistry;

    private final LlmService llmService;''')
change(use,'''        return analyze(command, command.provenance() == null);''','''        if (analysisProgressRegistry == null || AnalysisRunControl.active()) {
            return analyze(command, command.provenance() == null);
        }
        // Portfolio/Copilot callers retain their durable job and claim boundaries.
        try (var run = analysisProgressRegistry.open(null, command.username(),
                command.workspaceContext(), command.provenance())) {
            AnalyzeRequirementResult result = analyze(command, command.provenance() == null);
            run.finish(result.analysisResult().getStatus());
            return result;
        }''')
change(use,'''            enrichWithRelationHypotheses(command, result, persistHypotheses);
            enrichWithArchitectureView(command, result);''','''            if (result.getErrorMessage() == null || !isCooperativeStop(result.getErrorMessage())) {
                try {
                    AnalysisRunControl.phase("RELATIONS", null);
                    enrichWithRelationHypotheses(command, result, persistHypotheses);
                    AnalysisRunControl.phase("ARCHITECTURE", null);
                    enrichWithArchitectureView(command, result);
                } catch (AnalysisStoppedException stopped) {
                    result.setStatus("PARTIAL");
                    result.setErrorMessage(stopped.getMessage());
                    var warnings = new java.util.ArrayList<>(result.getWarnings() == null
                            ? java.util.List.<String>of() : result.getWarnings());
                    warnings.add(stopped.getMessage());
                    result.setWarnings(warnings);
                }
            }''')
change(use,'    private void applyProviderOverride(String provider) {','''    private static boolean isCooperativeStop(String message) {
        return message.startsWith("MEMORY_PRESSURE:") || message.startsWith("CANCELLED:")
                || message.startsWith("TIME_LIMIT:");
    }

    private void applyProviderOverride(String provider) {''')

controller=ROOT/'java/com/taxonomy/analysis/controller/AnalysisApiController.java'
change(controller,'import java.io.IOException;','''import java.io.IOException;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;''')
change(controller,'    private final TaxonomyService taxonomyService;','''    @Autowired
    private AnalysisProgressRegistry analysisProgressRegistry;

    private final TaxonomyService taxonomyService;''')
change(controller,'''            AnalyzeRequirementResult result = analyzeRequirementUseCase.analyze(''','''            WorkspaceContext context = resolveWorkspaceContext(username);
            try (var run = analysisProgressRegistry == null ? null
                    : analysisProgressRegistry.open(operationId, username, context, null)) {
            AnalyzeRequirementResult result = analyzeRequirementUseCase.analyze(''')
change(controller,'''                            resolveWorkspaceContext(username)));
            return ResponseEntity.ok()''','''                            context));
            if (run != null) run.finish(result.analysisResult().getStatus());
            return ResponseEntity.ok()''')
change(controller,'''                    .body(result.analysisResult());
        } catch (UnknownAnalysisProviderException e) {''','''                    .body(result.analysisResult());
            }
        } catch (UnknownAnalysisProviderException e) {''')
change(controller,'''    private String newOperationId() {
        return UUID.randomUUID().toString();
    }''','''    private String newOperationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String requested = attributes.getRequest().getHeader(ANALYSIS_OPERATION_ID_HEADER);
            if (requested != null && !requested.isBlank()) return requested;
        }
        return UUID.randomUUID().toString();
    }''')
# The legacy SSE endpoint remains available, but full browser analysis no longer loses architecture data through it.
change(controller,'new SseEmitter(120_000L)','new SseEmitter(1_800_000L)')

properties={
 'WARNING_PERCENT':('warning-percent','80'),
 'STOP_PERCENT':('stop-percent','92'),
 'MINIMUM_HEADROOM_MB':('minimum-headroom-mb','16'),
 'PRESSURE_SECONDS':('pressure-seconds','5'),
 'MAXIMUM_DURATION_SECONDS':('maximum-duration-seconds','1800')}
with (ROOT/'resources/application.properties').open('a') as f:
    f.write('\n# Bounded live analysis and cooperative memory/time limits (not an OOM guarantee).\n')
    for env,(key,value) in properties.items():
        f.write('taxonomy.analysis.runtime.'+key+'=${TAXONOMY_ANALYSIS_RUNTIME_'+env+':'+value+'}\n')
for lang in ['de','en']:
    with (Path('docs')/lang/'CONFIGURATION_REFERENCE.md').open('a') as f:
        f.write('\n## Live analysis runtime / Laufzeit der Live-Analyse\n\n| Environment variable | Spring property | Default |\n|---|---|---|\n')
        for env,(key,value) in properties.items():
            f.write('| `TAXONOMY_ANALYSIS_RUNTIME_'+env+'` | `taxonomy.analysis.runtime.'+key+'` | `'+value+'` |\n')
        f.write('\nWarning < stop <= 98 percent; at least 1 MiB reserve; nonnegative pressure grace; positive deadline. Limits are checked cooperatively before calls and during rate-limit/retry waits. In-flight HTTP calls retain their configured timeout. Neither native memory nor one large allocation can be guaranteed safe by a heap sample. Completed scores are retained when the next step is stopped. Live telemetry is process-local, owner/workspace/repository/branch-scoped, limited to 4 active and 16 retained runs (10-minute terminal retention), 32 call previews and 8192 score entries. Preview text is limited to 8192 characters per prompt/response and loaded separately; omitted entries are counted. Durable portfolio jobs and semantic history remain separate and unchanged.\n')
print('Integrated live runtime into existing full analysis and provider boundaries')
