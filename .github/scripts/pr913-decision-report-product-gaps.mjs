const fs = require('node:fs');

function replaceOnce(text, before, after, label) {
  const first = text.indexOf(before);
  const last = text.lastIndexOf(before);
  if (first < 0 || first !== last) {
    throw new Error(`${label}: expected exactly one match`);
  }
  return text.slice(0, first) + after + text.slice(first + before.length);
}

function replaceRegexOnce(text, pattern, replacement, label) {
  const flags = pattern.flags.includes('g') ? pattern.flags : pattern.flags + 'g';
  const matches = [...text.matchAll(new RegExp(pattern.source, flags))];
  if (matches.length !== 1) {
    throw new Error(`${label}: expected exactly one match, found ${matches.length}`);
  }
  return text.replace(pattern, replacement);
}

function write(path, text) {
  fs.writeFileSync(path, text);
}

// Format-neutral report model.
const modelPath = 'taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReport.java';
let model = fs.readFileSync(modelPath, 'utf8');
model = replaceOnce(model,
  'import com.taxonomy.dto.TaxonomyDiscrepancy;',
  'import com.taxonomy.dto.ProductCoverageGap;\nimport com.taxonomy.dto.TaxonomyDiscrepancy;',
  'report product gap import');
model = replaceOnce(model,
  '        List<String> warnings,\n        List<TaxonomyDiscrepancy> discrepancies,',
  '        List<String> warnings,\n        List<ProductCoverageGap> productCoverageGaps,\n        List<TaxonomyDiscrepancy> discrepancies,',
  'report product gap field');
model = replaceOnce(model,
  '        warnings = immutable(warnings);\n        discrepancies = immutable(discrepancies);',
  '        warnings = immutable(warnings);\n        productCoverageGaps = immutable(productCoverageGaps);\n        discrepancies = immutable(discrepancies);',
  'report product gap immutability');
write(modelPath, model);

// Report service: validated evidence, completeness, warnings and fingerprinting.
const servicePath = 'taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReportService.java';
let service = fs.readFileSync(servicePath, 'utf8');
service = replaceOnce(service,
  'import com.taxonomy.dto.TaxonomyDiscrepancy;',
  'import com.taxonomy.dto.ProductCoverageGap;\nimport com.taxonomy.dto.TaxonomyDiscrepancy;',
  'service product gap import');

service = replaceRegexOnce(service,
  /    public record DecisionAnalysisInput\([\s\S]*?\n    \}\n\n    \/\*\* Immutable evidence copied from a persisted requirement-analysis snapshot\. \*\//,
`    public record DecisionAnalysisInput(
  String businessText,
  Map<String, Integer> scores,
  Map<String, String> reasons,
  String provider,
  String analysisStatus,
  List<TaxonomyDiscrepancy> discrepancies,
  List<ProductCoverageGap> productCoverageGaps,
  List<TaxonomyNodeDto> taxonomyTree,
  AnalysisSnapshotProvenance snapshotProvenance) {

        public DecisionAnalysisInput {
  scores = scores == null ? Map.of() : Map.copyOf(scores);
  reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
  discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
  productCoverageGaps = productCoverageGaps == null
          ? List.of() : List.copyOf(productCoverageGaps);
  taxonomyTree = taxonomyTree == null ? List.of() : List.copyOf(taxonomyTree);
        }

        /** Backward-compatible ad-hoc analysis input using the currently loaded hierarchy. */
        public DecisionAnalysisInput(
      String businessText,
      Map<String, Integer> scores,
      Map<String, String> reasons,
      String provider,
      String analysisStatus,
      List<TaxonomyDiscrepancy> discrepancies) {
  this(businessText, scores, reasons, provider, analysisStatus, discrepancies,
          List.of(), List.of(), null);
        }

        /** Ad-hoc analysis input including explicit concrete-product coverage evidence. */
        public DecisionAnalysisInput(
      String businessText,
      Map<String, Integer> scores,
      Map<String, String> reasons,
      String provider,
      String analysisStatus,
      List<TaxonomyDiscrepancy> discrepancies,
      List<ProductCoverageGap> productCoverageGaps) {
  this(businessText, scores, reasons, provider, analysisStatus, discrepancies,
          productCoverageGaps, List.of(), null);
        }

        /** Backward-compatible immutable-snapshot input without product-gap evidence. */
        public DecisionAnalysisInput(
      String businessText,
      Map<String, Integer> scores,
      Map<String, String> reasons,
      String provider,
      String analysisStatus,
      List<TaxonomyDiscrepancy> discrepancies,
      List<TaxonomyNodeDto> taxonomyTree,
      AnalysisSnapshotProvenance snapshotProvenance) {
  this(businessText, scores, reasons, provider, analysisStatus, discrepancies,
          List.of(), taxonomyTree, snapshotProvenance);
        }
    }

    /** Immutable evidence copied from a persisted requirement-analysis snapshot. */`,
  'decision analysis input');

service = replaceOnce(service,
  '        List<TaxonomyNode> hierarchyOrder = preOrder(roots, childrenMap);\n\n        Completeness completeness = assessCompleteness(roots, hierarchyOrder, childrenMap, scores);',
  '        List<TaxonomyNode> hierarchyOrder = preOrder(roots, childrenMap);\n        List<ProductCoverageGap> productCoverageGaps = validateProductCoverageGaps(\n                input.productCoverageGaps(), nodesByCode, childrenMap, scores);\n\n        Completeness completeness = assessCompleteness(\n                roots, hierarchyOrder, childrenMap, scores, productCoverageGaps);',
  'validated gaps and completeness');
service = replaceOnce(service,
  '                nodesByCode.keySet(), childrenMap, german));',
  '                nodesByCode.keySet(), childrenMap, productCoverageGaps, german));',
  'warning gap input');
service = replaceOnce(service,
  '        String analysisSnapshotFingerprint = fingerprintAnalysis(input, scores, reasons);',
  '        String analysisSnapshotFingerprint = fingerprintAnalysis(\n                input, scores, reasons, productCoverageGaps);',
  'gap fingerprint call');
service = replaceOnce(service,
  '                warnings,\n                input.discrepancies(),\n                viewContext);',
  '                warnings,\n                productCoverageGaps,\n                input.discrepancies(),\n                viewContext);',
  'report gap output');

service = replaceRegexOnce(service,
  /(private Completeness assessCompleteness\(\s*List<TaxonomyNode> roots,\s*List<TaxonomyNode> hierarchyOrder,\s*Map<String, List<TaxonomyNode>> childrenMap,\s*Map<String, Integer> scores)\) \{/,
  '$1,\n            List<ProductCoverageGap> productCoverageGaps) {',
  'completeness signature');
service = replaceOnce(service,
  '            } else if (!anyPositiveChild) {\n                unresolvedParents.add(node.getCode());\n            }',
  '            } else if (!anyPositiveChild\n                    && !hasResolvedProductCoverageGap(\n                            node.getCode(), score, childCodes, scores, productCoverageGaps)) {\n                unresolvedParents.add(node.getCode());\n            }',
  'coverage gap completeness');

const gapCompletenessHelper = `
    private boolean hasResolvedProductCoverageGap(
  String familyCode,
  Integer familyScore,
  List<String> directChildCodes,
  Map<String, Integer> scores,
  List<ProductCoverageGap> productCoverageGaps) {
        return productCoverageGaps.stream().anyMatch(gap ->
      familyCode.equals(gap.productFamilyCode())
              && familyScore != null
              && familyScore == gap.familyScore()
              && !gap.candidateCodes().isEmpty()
              && directChildCodes.containsAll(gap.candidateCodes())
              && gap.candidateCodes().stream().allMatch(code ->
                      scores.containsKey(code) && scores.get(code) == 0));
    }

`;
service = replaceOnce(service,
  '    private List<DecisionChapter> buildChapters(',
  gapCompletenessHelper + '    private List<DecisionChapter> buildChapters(',
  'coverage gap completeness helper');

service = replaceRegexOnce(service,
  /(private List<String> buildWarnings\(\s*DecisionAnalysisInput input,\s*ViewContext viewContext,\s*Completeness completeness,\s*Map<String, Integer> scores,\s*Map<String, String> reasons,\s*List<LeafCandidate> leaves,\s*Set<String> knownNodeCodes,\s*Map<String, List<TaxonomyNode>> childrenMap,)(\s*boolean german\) \{)/,
  '$1\n            List<ProductCoverageGap> productCoverageGaps,$2',
  'warning signature');

const gapWarnings = `        for (ProductCoverageGap gap : productCoverageGaps) {
  String candidates = String.join(", ", gap.candidateCodes());
  warnings.add(german
          ? "Produktabdeckungslücke für " + gap.productFamilyCode() + " ("
              + gap.productFamilyName() + ", " + gap.familyScore()
              + " %): Keines der " + gap.candidateCodes().size()
              + " vollständig bewerteten Katalogprodukte erreichte den Eignungsschwellenwert. Kandidaten: "
              + candidates + "."
          : "Product coverage gap for " + gap.productFamilyCode() + " ("
              + gap.productFamilyName() + ", " + gap.familyScore()
              + "%): None of the " + gap.candidateCodes().size()
              + " fully evaluated catalogue products reached the suitability threshold. Candidates: "
              + candidates + ".");
        }
`;
service = replaceOnce(service,
  '        if (input.analysisStatus() != null\n                && !"SUCCESS".equalsIgnoreCase(input.analysisStatus())) {',
  gapWarnings + '        if (input.analysisStatus() != null\n                && !"SUCCESS".equalsIgnoreCase(input.analysisStatus())) {',
  'coverage gap warnings');

const validationHelper = `    private List<ProductCoverageGap> validateProductCoverageGaps(
  List<ProductCoverageGap> source,
  Map<String, TaxonomyNode> nodesByCode,
  Map<String, List<TaxonomyNode>> childrenMap,
  Map<String, Integer> scores) {
        if (source == null || source.isEmpty()) {
  return List.of();
        }
        List<ProductCoverageGap> ordered = new ArrayList<>(source);
        if (ordered.stream().anyMatch(Objects::isNull)) {
  throw new IllegalArgumentException("Product coverage gaps must not contain null entries");
        }
        ordered.sort(Comparator.comparing(
      ProductCoverageGap::productFamilyCode,
      Comparator.nullsLast(String::compareTo)));

        Map<String, ProductCoverageGap> validated = new LinkedHashMap<>();
        for (ProductCoverageGap gap : ordered) {
  if (gap.productFamilyCode() == null || gap.productFamilyCode().isBlank()) {
      throw new IllegalArgumentException("Product coverage gap has no product family code");
  }
  String familyCode = gap.productFamilyCode().strip();
  TaxonomyNode family = nodesByCode.get(familyCode);
  if (family == null) {
      throw new IllegalArgumentException(
              "Product coverage gap references unknown family " + familyCode);
  }
  if (gap.familyScore() < 0 || gap.familyScore() > 100) {
      throw new IllegalArgumentException(
              "Product coverage gap family score must be between 0 and 100 for "
                      + familyCode);
  }
  Integer storedFamilyScore = scores.get(familyCode);
  if (storedFamilyScore == null || storedFamilyScore != gap.familyScore()) {
      throw new IllegalArgumentException(
              "Product coverage gap family score does not match analysis score for "
                      + familyCode);
  }
  if (gap.reason() == null || gap.reason().isBlank()) {
      throw new IllegalArgumentException(
              "Product coverage gap has no reason for " + familyCode);
  }
  if (gap.candidateCodes() == null || gap.candidateCodes().isEmpty()) {
      throw new IllegalArgumentException(
              "Product coverage gap has no evaluated product candidates for "
                      + familyCode);
  }

  Set<String> candidateSet = new LinkedHashSet<>();
  for (String rawCode : gap.candidateCodes()) {
      if (rawCode == null || rawCode.isBlank()) {
          throw new IllegalArgumentException(
                  "Product coverage gap contains a blank candidate for " + familyCode);
      }
      String candidateCode = rawCode.strip();
      if (!candidateSet.add(candidateCode)) {
          throw new IllegalArgumentException(
                  "Product coverage gap contains duplicate candidate "
                          + candidateCode + " for " + familyCode);
      }
  }
  List<String> candidateCodes = candidateSet.stream().sorted().toList();
  Set<String> directChildCodes = childrenMap
          .getOrDefault(familyCode, List.of()).stream()
          .map(TaxonomyNode::getCode)
          .collect(Collectors.toCollection(LinkedHashSet::new));
  if (!directChildCodes.containsAll(candidateCodes)) {
      throw new IllegalArgumentException(
              "Product coverage gap candidates are not direct children of "
                      + familyCode);
  }
  if (candidateCodes.stream().anyMatch(code ->
          !scores.containsKey(code) || scores.get(code) != 0)) {
      throw new IllegalArgumentException(
              "Product coverage gap candidates must be explicitly evaluated at 0 for "
                      + familyCode);
  }

  ProductCoverageGap normalizedGap = new ProductCoverageGap(
          familyCode,
          firstNonBlank(gap.productFamilyName(), family.getName(), familyCode),
          gap.familyScore(),
          candidateCodes,
          gap.reason().strip());
  if (validated.putIfAbsent(familyCode, normalizedGap) != null) {
      throw new IllegalArgumentException(
              "Duplicate product coverage gap for family " + familyCode);
  }
        }
        return List.copyOf(validated.values());
    }

`;
service = replaceOnce(service,
  '    private Map<String, List<TaxonomyNode>> copyAndSort(',
  validationHelper + '    private Map<String, List<TaxonomyNode>> copyAndSort(',
  'coverage gap validation helper');

service = replaceOnce(service,
  '    private String fingerprintAnalysis(\n            DecisionAnalysisInput input,\n            Map<String, Integer> scores,\n            Map<String, String> reasons) {',
  '    private String fingerprintAnalysis(\n            DecisionAnalysisInput input,\n            Map<String, Integer> scores,\n            Map<String, String> reasons,\n            List<ProductCoverageGap> productCoverageGaps) {',
  'fingerprint gap signature');
service = replaceOnce(service,
  '            for (int index = 0; index < input.discrepancies().size(); index++) {\n                updateDigest(digest, "discrepancy:" + index,\n                        String.valueOf(input.discrepancies().get(index)));\n            }\n            return HexFormat.of().formatHex(digest.digest());',
  '            for (int index = 0; index < input.discrepancies().size(); index++) {\n                updateDigest(digest, "discrepancy:" + index,\n                        String.valueOf(input.discrepancies().get(index)));\n            }\n            for (ProductCoverageGap gap : productCoverageGaps) {\n                updateDigest(digest, "product-gap:" + gap.productFamilyCode(),\n                        gap.familyScore() + "|" + String.join(",", gap.candidateCodes())\n                                + "|" + gap.reason());\n            }\n            return HexFormat.of().formatHex(digest.digest());',
  'fingerprint product gaps');
write(servicePath, service);

// Ad-hoc HTTP request boundary.
const controllerPath = 'taxonomy-app/src/main/java/com/taxonomy/versioning/controller/DecisionRationaleReportController.java';
let controller = fs.readFileSync(controllerPath, 'utf8');
controller = replaceOnce(controller,
  'import com.taxonomy.dto.TaxonomyDiscrepancy;',
  'import com.taxonomy.dto.ProductCoverageGap;\nimport com.taxonomy.dto.TaxonomyDiscrepancy;',
  'controller product gap import');
controller = replaceOnce(controller,
  '    private static final int MAX_DISCREPANCIES = 10_000;\n    private static final int MAX_METADATA_LENGTH = 256;',
  '    private static final int MAX_DISCREPANCIES = 10_000;\n    private static final int MAX_PRODUCT_COVERAGE_GAPS = 10_000;\n    private static final int MAX_PRODUCT_GAP_CANDIDATES = 25_000;\n    private static final int MAX_METADATA_LENGTH = 256;',
  'controller product gap limits');
controller = replaceOnce(controller,
  '            String analysisStatus,\n            List<TaxonomyDiscrepancy> discrepancies,\n            String language) {',
  '            String analysisStatus,\n            List<TaxonomyDiscrepancy> discrepancies,\n            List<ProductCoverageGap> productCoverageGaps,\n            String language) {',
  'controller request field');
controller = replaceOnce(controller,
  '                request.analysisStatus(),\n                request.discrepancies());',
  '                request.analysisStatus(),\n                request.discrepancies(),\n                request.productCoverageGaps());',
  'controller report input');
controller = replaceOnce(controller,
  '                || (request.discrepancies() != null\n                        && request.discrepancies().size() > MAX_DISCREPANCIES)) {',
  '                || (request.discrepancies() != null\n                        && request.discrepancies().size() > MAX_DISCREPANCIES)\n                || (request.productCoverageGaps() != null\n                        && request.productCoverageGaps().size() > MAX_PRODUCT_COVERAGE_GAPS)) {',
  'controller gap request bound');
controller = replaceOnce(controller,
  '        return validScores\n                && validReasons(request.reasons())\n                && validDiscrepancies(request.discrepancies());',
  '        return validScores\n                && validReasons(request.reasons())\n                && validDiscrepancies(request.discrepancies())\n                && validProductCoverageGaps(request.productCoverageGaps());',
  'controller gap validation call');

const controllerValidation = `    private boolean validProductCoverageGaps(
  List<ProductCoverageGap> productCoverageGaps) {
        if (productCoverageGaps == null) {
  return true;
        }
        int totalCandidates = 0;
        java.util.Set<String> familyCodes = new java.util.HashSet<>();
        for (ProductCoverageGap gap : productCoverageGaps) {
  if (gap == null
          || !boundedText(gap.productFamilyCode(),
                  MAX_NODE_CODE_LENGTH, false)
          || !boundedText(gap.productFamilyName(), MAX_REASON_LENGTH, true)
          || gap.familyScore() < 0
          || gap.familyScore() > 100
          || !boundedText(gap.reason(), MAX_REASON_LENGTH, false)
          || gap.candidateCodes() == null
          || gap.candidateCodes().isEmpty()
          || !familyCodes.add(gap.productFamilyCode().strip())) {
      return false;
  }
  java.util.Set<String> candidateCodes = new java.util.HashSet<>();
  for (String candidateCode : gap.candidateCodes()) {
      totalCandidates++;
      if (totalCandidates > MAX_PRODUCT_GAP_CANDIDATES
              || !boundedText(candidateCode, MAX_NODE_CODE_LENGTH, false)
              || !candidateCodes.add(candidateCode.strip())) {
          return false;
      }
  }
        }
        return true;
    }

`;
controller = replaceOnce(controller,
  '    private boolean validDiscrepancies(',
  controllerValidation + '    private boolean validDiscrepancies(',
  'controller gap validation helper');
write(controllerPath, controller);

// Immutable snapshot propagation.
const snapshotServicePath = 'taxonomy-app/src/main/java/com/taxonomy/portfolio/report/DecisionRationaleSnapshotReportService.java';
let snapshotService = fs.readFileSync(snapshotServicePath, 'utf8');
snapshotService = replaceOnce(snapshotService,
  '                analysis.getDiscrepancies(),\n                analysis.getTree(),',
  '                analysis.getDiscrepancies(),\n                analysis.getProductCoverageGaps(),\n                analysis.getTree(),',
  'snapshot product gap propagation');
write(snapshotServicePath, snapshotService);

// Browser ad-hoc exports.
const browsePath = 'taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js';
let browse = fs.readFileSync(browsePath, 'utf8');
browse = replaceOnce(browse,
  '                discrepancies: S.currentDiscrepancies || [],\n                language: window.TaxonomyI18n',
  '                discrepancies: S.currentDiscrepancies || [],\n                productCoverageGaps: S.currentProductCoverageGaps || [],\n                language: window.TaxonomyI18n',
  'browser product gap request');
write(browsePath, browse);

// Browser-to-report E2E evidence and contract.
const e2ePath = '.github/scripts/document-template-report-download.mjs';
let e2e = fs.readFileSync(e2ePath, 'utf8');
e2e = replaceOnce(e2e,
  '      warningCount: reportModel.warnings.length\n    },',
  '      warningCount: reportModel.warnings.length,\n      productCoverageGapCount: reportModel.productCoverageGaps.length\n    },',
  'E2E report gap evidence');
e2e = replaceOnce(e2e,
  '    positiveScoreCount: positive.length,\n    suppliedReasonCount: Object.keys(reasons).length,',
  '    positiveScoreCount: positive.length,\n    productCoverageGapCount: (result.productCoverageGaps || []).length,\n    suppliedReasonCount: Object.keys(reasons).length,',
  'E2E analysis gap evidence');
e2e = replaceOnce(e2e,
  '      discrepancies: state?.currentDiscrepancies || [],\n      language: window.TaxonomyI18n?.getLocale?.()',
  '      discrepancies: state?.currentDiscrepancies || [],\n      productCoverageGaps: state?.currentProductCoverageGaps || [],\n      language: window.TaxonomyI18n?.getLocale?.()',
  'E2E report gap request');
e2e = replaceOnce(e2e,
  '  if (report.status === \'DRAFT_INCOMPLETE\' || report.status === \'NO_RESULT\') {\n    throw new Error(`Unexpected decision report status: ${report.status}`);\n  }\n  return report;',
  '  if (!Array.isArray(report.productCoverageGaps)) {\n    throw new Error(\'Decision report omitted structured product coverage gaps\');\n  }\n  const requestedGaps = Array.isArray(request.productCoverageGaps)\n    ? request.productCoverageGaps : [];\n  if (report.productCoverageGaps.length !== requestedGaps.length) {\n    throw new Error(\n      `Decision report preserved ${report.productCoverageGaps.length} of `\n        + `${requestedGaps.length} product coverage gaps`);\n  }\n  for (const gap of report.productCoverageGaps) {\n    if (!report.warnings.some(warning => warning.includes(gap.productFamilyCode))) {\n      throw new Error(\n        `Decision report did not explain product coverage gap ${gap.productFamilyCode}`);\n    }\n  }\n  if (requestedGaps.length > 0 && report.status !== \'FINAL_WITH_WARNINGS\') {\n    throw new Error(\n      `Completed product coverage gaps require FINAL_WITH_WARNINGS, got ${report.status}`);\n  }\n  if (report.status === \'DRAFT_INCOMPLETE\' || report.status === \'NO_RESULT\') {\n    throw new Error(`Unexpected decision report status: ${report.status}`);\n  }\n  return report;',
  'E2E product gap verification');
write(e2ePath, e2e);

// Explicit constructors used by deterministic template tests and previews.
for (const path of [
  'taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleTemplatePreviewService.java',
  'taxonomy-app/src/test/java/com/taxonomy/architecture/decision/DecisionRationaleTemplateRendererTest.java',
  'taxonomy-app/src/test/java/com/taxonomy/portfolio/report/DecisionRationaleSnapshotReportTest.java'
]) {
  let text = fs.readFileSync(path, 'utf8');
  text = replaceRegexOnce(text,
    /(new DecisionRationaleReport\([\s\S]*?\n\s*List\.of\([^\n]*\),\n\s*List\.of\(\),)(\n\s*null\);)/,
    '$1\n                List.of(),$2',
    `report constructor product gaps in ${path}`);
  write(path, text);
}

// Service and HTTP regression tests.
const reportTestPath = 'taxonomy-app/src/test/java/com/taxonomy/DecisionRationaleReportTests.java';
let reportTests = fs.readFileSync(reportTestPath, 'utf8');
reportTests = replaceOnce(reportTests,
  'import com.taxonomy.dto.TaxonomyNodeDto;',
  'import com.taxonomy.dto.ProductCoverageGap;\nimport com.taxonomy.dto.TaxonomyNodeDto;',
  'report test gap import');
const reportRegression = `    @Test
    void evaluatedProductCoverageGapIsCompleteAndFinalWithWarnings() {
        Fixture fixture = completeFixture();
        Map<String, List<TaxonomyNode>> childrenMap = taxonomyService.getChildrenMap();
        Map<String, Integer> scores = new LinkedHashMap<>(fixture.scores());
        TaxonomyNode productFamily = taxonomyService.getRootNodes().stream()
      .filter(root -> scores.getOrDefault(root.getCode(), 0) == 0)
      .filter(root -> !childrenMap.getOrDefault(root.getCode(), List.of()).isEmpty())
      .findFirst()
      .orElseThrow();
        List<String> candidateCodes = childrenMap.get(productFamily.getCode()).stream()
      .map(TaxonomyNode::getCode)
      .toList();
        scores.put(productFamily.getCode(), 40);
        candidateCodes.forEach(code -> scores.put(code, 0));
        ProductCoverageGap gap = new ProductCoverageGap(
      productFamily.getCode(),
      productFamily.getName(),
      40,
      candidateCodes,
      "Every concrete candidate remained below the suitability threshold.");

        DecisionRationaleReport report = reportService.generate(
      new DecisionRationaleReportService.DecisionAnalysisInput(
              fixture.requirement(), scores, fixture.reasons(),
              "MOCK", "SUCCESS", List.of(), List.of(gap)),
      new WorkspaceContext(
              "decision-auditor", "test-workspace", "main", "test-repository"),
      viewContext(),
      Locale.GERMAN);

        assertThat(report.status()).isEqualTo(
      DecisionRationaleReport.ReportStatus.FINAL_WITH_WARNINGS);
        assertThat(report.metadata().completenessPercent()).isEqualTo(100.0);
        assertThat(report.productCoverageGaps()).containsExactly(gap);
        assertThat(report.warnings())
      .anyMatch(warning -> warning.contains(productFamily.getCode())
              && warning.contains("Produktabdeckungslücke"));
    }

`;
reportTests = replaceOnce(reportTests,
  '    @Test\n    void missingChildScoreProducesDraftAndDoesNotTreatMissingAsZero() {',
  reportRegression + '    @Test\n    void missingChildScoreProducesDraftAndDoesNotTreatMissingAsZero() {',
  'report gap regression');
const invalidGapTest = `
        Map<String, Object> invalidGap = new LinkedHashMap<>();
        invalidGap.put("scores", Map.of("CP", 100));
        invalidGap.put("businessText", "bounded requirement");
        invalidGap.put("productCoverageGaps", List.of(Map.of(
      "productFamilyCode", "CP",
      "productFamilyName", "Capability",
      "familyScore", 100,
      "candidateCodes", List.of(),
      "reason", "No suitable product")));
        mockMvc.perform(post("/api/decision-report/json")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(invalidGap)))
      .andExpect(status().isBadRequest());
`;
reportTests = replaceOnce(reportTests,
  '        Map<String, Object> oversizedNodeCode = new LinkedHashMap<>();',
  invalidGapTest + '\n        Map<String, Object> oversizedNodeCode = new LinkedHashMap<>();',
  'invalid gap HTTP test');
write(reportTestPath, reportTests);

const snapshotTestPath = 'taxonomy-app/src/test/java/com/taxonomy/portfolio/report/DecisionRationaleSnapshotReportTest.java';
let snapshotTests = fs.readFileSync(snapshotTestPath, 'utf8');
snapshotTests = replaceOnce(snapshotTests,
  'import com.taxonomy.dto.RelationHypothesisDto;',
  'import com.taxonomy.dto.ProductCoverageGap;\nimport com.taxonomy.dto.RelationHypothesisDto;',
  'snapshot test gap import');
snapshotTests = replaceOnce(snapshotTests,
  '        assertThat(input.scores()).containsEntry("CP", 100);\n        assertThat(input.taxonomyTree()).hasSize(1);',
  '        assertThat(input.scores()).containsEntry("CP", 100);\n        assertThat(input.productCoverageGaps())\n                .extracting(ProductCoverageGap::productFamilyCode)\n                .containsExactly("CP");\n        assertThat(input.taxonomyTree()).hasSize(1);',
  'snapshot gap assertion');
snapshotTests = replaceOnce(snapshotTests,
  '        analysis.setReasons(Map.of("CP", "The requirement directly needs this capability."));\n        analysis.setTree(List.of(root));',
  '        analysis.setReasons(Map.of("CP", "The requirement directly needs this capability."));\n        analysis.setProductCoverageGaps(List.of(new ProductCoverageGap(\n                "CP", "Capability", 100, List.of("CP-P1"),\n                "No suitable product reached the threshold.")));\n        analysis.setTree(List.of(root));',
  'snapshot gap fixture');
write(snapshotTestPath, snapshotTests);

// Documentation.
const enPath = 'docs/en/DECISION_RATIONALE_REPORT.md';
let en = fs.readFileSync(enPath, 'utf8');
en = replaceOnce(en,
  'A report is final only when all root areas were evaluated and every positive inner node has a score for each of its direct children. A positive parent whose children are missing, or whose analysis ended before reaching a real leaf, produces a clearly marked draft report. Positive parents whose evaluated children all scored zero are reported as unresolved classifications.',
  'A report is final only when all root areas were evaluated and every positive inner node has a score for each of its direct children. A positive parent whose children are missing, or whose analysis ended before reaching a real leaf, produces a clearly marked draft report. Positive parents whose evaluated children all scored zero are unresolved unless the analysis carries a validated structured `productCoverageGap`: in that case, the absence of a suitable concrete product is a completed finding and the report becomes `FINAL_WITH_WARNINGS` rather than an incomplete draft.',
  'English completeness documentation');
en = replaceOnce(en,
  'The analysis fingerprint covers the requirement, provider, status, sorted scores, sorted reasons, and discrepancies.',
  'The analysis fingerprint covers the requirement, provider, status, sorted scores, sorted reasons, discrepancies, and validated product coverage gaps.',
  'English fingerprint documentation');
en = replaceOnce(en,
  '  "discrepancies": [],\n  "language": "en"',
  '  "discrepancies": [],\n  "productCoverageGaps": [],\n  "language": "en"',
  'English API example');
write(enPath, en);

const dePath = 'docs/de/DECISION_RATIONALE_REPORT.md';
let de = fs.readFileSync(dePath, 'utf8');
de = replaceOnce(de,
  'Ein Bericht ist erst dann abschließend, wenn alle Wurzelbereiche bewertet wurden und für jeden positiven inneren Knoten sämtliche direkten Kinder einen Wert besitzen. Fehlen bei einem positiven Vaterknoten Kinderbewertungen oder endet die Analyse vor einem tatsächlichen Blatt, wird der Bericht deutlich als Entwurf gekennzeichnet. Positive Vaterknoten, deren vollständig bewertete Kinder sämtlich 0% erhalten, werden als ungelöste Zuordnung ausgewiesen.',
  'Ein Bericht ist erst dann abschließend, wenn alle Wurzelbereiche bewertet wurden und für jeden positiven inneren Knoten sämtliche direkten Kinder einen Wert besitzen. Fehlen bei einem positiven Vaterknoten Kinderbewertungen oder endet die Analyse vor einem tatsächlichen Blatt, wird der Bericht deutlich als Entwurf gekennzeichnet. Positive Vaterknoten, deren vollständig bewertete Kinder sämtlich 0% erhalten, bleiben nur dann ungelöst, wenn kein validierter strukturierter `productCoverageGap` vorliegt. Belegt dieser Nachweis die vollständige erfolglose Bewertung der konkreten Produkte, ist die Produktabdeckungslücke eine abgeschlossene Feststellung und der Bericht erhält `FINAL_WITH_WARNINGS` statt `DRAFT_INCOMPLETE`.',
  'German completeness documentation');
de = replaceOnce(de,
  'Der Analyse-Fingerabdruck umfasst Anforderung, Anbieter, Status, sortierte Bewertungen, sortierte Begründungen und dokumentierte Abweichungen.',
  'Der Analyse-Fingerabdruck umfasst Anforderung, Anbieter, Status, sortierte Bewertungen, sortierte Begründungen, dokumentierte Abweichungen und validierte Produktabdeckungslücken.',
  'German fingerprint documentation');
de = replaceOnce(de,
  '  "discrepancies": [],\n  "language": "de"',
  '  "discrepancies": [],\n  "productCoverageGaps": [],\n  "language": "de"',
  'German API example');
write(dePath, de);

const overlayDocsPath = 'docs/dev/INFORMATION_PRODUCT_OVERLAY.md';
let overlayDocs = fs.readFileSync(overlayDocsPath, 'utf8');
overlayDocs = replaceOnce(overlayDocs,
  'does not discard completed product evidence or suppress a product gap established by a\nsuccessfully completed concrete-product batch.',
  'does not discard completed product evidence or suppress a product gap established by a\nsuccessfully completed concrete-product batch. Ad-hoc and immutable-snapshot decision reports\ncarry the validated structured gap, include it in the analysis fingerprint, and classify the\notherwise complete document as `FINAL_WITH_WARNINGS` rather than `DRAFT_INCOMPLETE`.',
  'overlay decision report documentation');
write(overlayDocsPath, overlayDocs);
