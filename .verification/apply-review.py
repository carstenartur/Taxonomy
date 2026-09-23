from pathlib import Path
import shutil
HERE=Path(__file__).resolve().parent

def replace(path, old, new):
    p=Path(path); text=p.read_text()
    assert text.count(old)==1, (path,old[:100],text.count(old))
    p.write_text(text.replace(old,new))

shutil.copyfile(HERE/'ReviewBoundaryContinuationTest.java','taxonomy-analysis/src/test/java/com/taxonomy/analysis/relations/ReviewBoundaryContinuationTest.java')
p='taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java'
replace(p, 'import tools.jackson.databind.ObjectMapper;', 'import tools.jackson.databind.ObjectMapper;\nimport tools.jackson.databind.DeserializationFeature;\nimport java.math.BigDecimal;')
replace(p, '''            if (!(scoreValue instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0 || number.doubleValue() > 100
                    || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new IllegalArgumentException("Category response for " + code
                        + " requires an explicit integer score between 0 and 100");
            }
            scores.put(code, number.intValue());''', '''            if (!(scoreValue instanceof Number number)) {
                throw new IllegalArgumentException("Category response for " + code
                        + " requires an explicit integer score between 0 and 100");
            }
            final int score;
            try {
                // The reader preserves decimal tokens; conversion through double would
                // turn precision-boundary fractions into apparently integral scores.
                score = new BigDecimal(number.toString()).intValueExact();
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException("Category response for " + code
                        + " requires an exact integer score between 0 and 100", invalid);
            }
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("Category response for " + code
                        + " requires an explicit integer score between 0 and 100");
            }
            scores.put(code, score);''')
replace(p, '''                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .readValue(jsonText);''', '''                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(jsonText);''')

p='taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RequirementRelationSearch.java'
replace(p,'import com.taxonomy.dto.RelationSearchReport;', 'import com.taxonomy.dto.RelationSearchReport;\nimport com.taxonomy.analysis.assessment.ChildAssessmentContract;')
replace(p, '''            checkpoint.run();
            List<Node> nodes = sourceNodes(scores, options.maxSources(), warnings);''', '''            checkpoint.run();
            // Catalogue validity is a precondition for extraction, not a late
            // engine concern after the caller has already paid for model calls.
            List<Node> offeredRoots = List.copyOf(catalogue.roots());
            ChildAssessmentContract.validateCandidates(offeredRoots.stream().map(Node::id).toList());
            List<Node> roots = offeredRoots.stream().sorted(Comparator.comparing(Node::id)).toList();
            List<Node> nodes = sourceNodes(scores, options.maxSources(), warnings);''')
replace(p, '            List<Node> roots = catalogue.roots().stream().sorted(Comparator.comparing(Node::id)).toList();\n', '')

p=Path('docs/dev/shared-child-assessment.md')
p.write_text(p.read_text()+'''\n\n## Projection preserves score provenance and choice evidence\n\nThe active evidence projection receives `AnalysisResult.getScoreDetails()` alongside\neffective relevance. A product with raw suitability 80 and parent relevance 40\ntherefore keeps direct score 80 and effective relevance 32; it is not reclassified\nas a directly assessed 32. `RequirementElementView.scoreDetail` reuses the existing\nimmutable `AnalysisScoreDetail`. Null means no assessment was supplied, whereas a\nnon-null detail with score zero means an explicit negative assessment. Legacy\nnumeric layout/index defaults remain for compatibility and are not score evidence.\nThe untyped projection overload cannot establish raw-score provenance.\n\nThe snapshot mapping table reads raw and effective scores from the authoritative\nanalysis details and shows an em dash for unassessed scoped nodes, never an index\nplaceholder zero. Analysis and architecture-view JSON round trips retain these\ndetails. Other exchange formats still follow their documented support boundaries.\n\nAll verified evidence is grouped by the actual oriented source/target/type\nsignature before deciding which required graph edges to display. A group containing\na required claim retains its distinct optional and alternative evidence, including\nconditions and choice groups, irrespective of their arrival order. A pure-choice\ngroup creates no required edge or endpoint. The full report is unchanged by\ngrouping, node limits or neutral diagram projection; no choice is automatically\nadopted into the active architecture.\n\nExact JSON decimals are checked before integer conversion: precision-boundary\nfractions such as `0.999999999999999999` are invalid, while exactly integral `1.0`\nor `1e0` remain accepted. The supplied catalogue root set is copied and validated\nbefore contribution extraction, avoiding paid calls for malformed roots. These\nchecks use the existing parser, child contract and report/stop paths, with no extra\nprovider call, service, global cache or transaction spanning a model request.\n\nRegression entry points: `EvidenceProjectionContinuationTest` exercises the real\nuse case, score derivation, facade, projection, snapshot JSON and neutral diagram;\n`ReviewBoundaryContinuationTest` exercises exact decimals and catalogue preflight.\nThe existing relationship confidence UI suite also covers authoritative raw vs.\neffective scores, explicit zero and absent assessment.\n''')
