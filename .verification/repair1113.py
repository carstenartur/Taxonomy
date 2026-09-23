from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parent
base=Path('taxonomy-analysis/src')
mode=sys.argv[1]
def replace_once(text,old,new):
    assert text.count(old)==1, ('unexpected source anchor',old)
    return text.replace(old,new,1)
if mode=='tests':
    p=base/'test/java/com/taxonomy/analysis/relations/RelationSearchProtocolContract.java'
    p.write_text(replace_once(p.read_text(),'    private static void rejects(String raw) {',
        (root/'shared/protocol-tests.txt').read_text()+'    private static void rejects(String raw) {'))
elif mode=='implementation':
    p=base/'main/java/com/taxonomy/analysis/assessment/ChildAssessmentContract.java'
    p.write_text((root/'shared/ChildAssessmentContract.java').read_text())
    p=base/'main/java/com/taxonomy/analysis/relations/RelationSearchProtocol.java'
    s=p.read_text()
    s=replace_once(s,'import tools.jackson.core.StreamReadFeature;',
        'import com.taxonomy.analysis.assessment.ChildAssessmentContract;\nimport tools.jackson.core.StreamReadFeature;')
    s=replace_once(s,'    public List<SourceAssessment> contributions(String original, List<Node> nodes) {',
        '    public List<SourceAssessment> contributions(String original, List<Node> nodes) {\n        nodes = List.copyOf(nodes);\n        List<String> candidateIds = ChildAssessmentContract.validateCandidates(nodes.stream().map(Node::id).toList());')
    s=replace_once(s,'        if (selections.size() != nodes.size()) invalid("Every offered source requires exactly one selection");\n','')
    s=replace_once(s,'        Set<String> seen = new HashSet<>();\n        List<SourceAssessment> result = new ArrayList<>();\n        for (JsonNode selected : selections) {',
        '        return decodeChildren(candidateIds, selections, "nodeId", (id, selected) -> {')
    s=replace_once(s,'            String id = text(selected, "nodeId", false);\n            if (!offered.containsKey(id) || !seen.add(id)) invalid("Unknown or duplicate source ID");\n','')
    s=replace_once(s,'            result.add(new SourceAssessment(offered.get(id), contributions, rationale, question));\n        }\n        return List.copyOf(result);',
        '            contributions.sort(Comparator.comparing(Contribution::text)\n                    .thenComparing(Contribution::quote).thenComparing(Contribution::condition));\n            return new SourceAssessment(offered.get(id), contributions, rationale, question);\n        });')
    s=replace_once(s,'    public List<Decision> evaluate(Query query) {',
        '    public List<Decision> evaluate(Query query) {\n        List<String> candidateIds = ChildAssessmentContract.validateCandidates(\n                query.candidates().stream().map(Node::id).toList());')
    s=replace_once(s,'        List<Decision> decisions = new ArrayList<>();\n        for (JsonNode item : array(response, "decisions")) {',
        '        return decodeChildren(candidateIds, array(response, "decisions"), "targetId", (id, item) -> {')
    s=replace_once(s,'                decisions.add(new Decision(text(item, "targetId", false), Outcome.valueOf(text(item, "outcome", false)),',
        '                return new Decision(id, Outcome.valueOf(text(item, "outcome", false)),')
    s=replace_once(s,'                        text(item, "alternativeGroup", true), text(item, "rationale", false), text(item, "question", true)));\n            } catch (IllegalArgumentException invalidEnum) { invalid("Unknown relation decision or necessity"); }\n        }\n        return List.copyOf(decisions);',
        '                        text(item, "alternativeGroup", true), text(item, "rationale", false), text(item, "question", true));\n            } catch (IllegalArgumentException invalidEnum) {\n                throw new RelationSearchEngine.InvalidResponseException("Unknown relation decision or necessity");\n            }\n        });')
    helper='''    private static <T> List<T> decodeChildren(List<String> candidateIds, JsonNode array, String identityField,
                                                java.util.function.BiFunction<String, JsonNode, T> policy) {
        List<JsonNode> answers = new ArrayList<>();
        array.forEach(answers::add);
        try {
            return List.copyOf(ChildAssessmentContract.decodeEntries(candidateIds, answers,
                    item -> text(item, identityField, false), policy).values());
        } catch (IllegalArgumentException invalidSet) {
            throw new RelationSearchEngine.InvalidResponseException(invalidSet.getMessage());
        }
    }

'''
    s=replace_once(s,'    private static JsonNode read(String raw) {',helper+'    private static JsonNode read(String raw) {')
    p.write_text(s)
    for suffix in ['main/java/com/taxonomy/analysis/assessment/RelationAssessment.java',
        'main/java/com/taxonomy/analysis/service/RelationChildAssessmentService.java',
        'test/java/com/taxonomy/analysis/assessment/RelationAssessmentChecks.java',
        'test/java/com/taxonomy/analysis/assessment/RelationAssessmentTest.java',
        'test/java/com/taxonomy/analysis/service/RelationChildAssessmentServiceTest.java']:
        (base/suffix).unlink()
    Path('docs/dev/shared-child-assessment.md').write_text((root/'shared/shared-child-assessment.md').read_text())
else: raise ValueError(mode)
subprocess.run(['git','diff','--check'],check=True)
