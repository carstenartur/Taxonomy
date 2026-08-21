cat > /tmp/expected-decision-report-files <<'LIST'
docs/de/DECISION_RATIONALE_REPORT.md
docs/dev/07-extension-points.md
docs/dev/tasks/add-report-family.md
docs/en/DECISION_RATIONALE_REPORT.md
taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmService.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionChapterDiagramRenderer.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleDocxRenderer.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleHtmlRenderer.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleJsonRenderer.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReport.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReportPlugin.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReportService.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionReportBuildMetadataService.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionReportLabels.java
taxonomy-app/src/main/java/com/taxonomy/architecture/decision/TaxonomyCatalogueMetadataService.java
taxonomy-app/src/main/java/com/taxonomy/architecture/report/ReportRendererRegistry.java
taxonomy-app/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java
taxonomy-app/src/main/java/com/taxonomy/portfolio/report/DecisionRationaleSnapshotReportController.java
taxonomy-app/src/main/java/com/taxonomy/portfolio/report/DecisionRationaleSnapshotReportService.java
taxonomy-app/src/main/java/com/taxonomy/security/config/AuthorizationRulesConfigurer.java
taxonomy-app/src/main/java/com/taxonomy/shared/controller/HelpController.java
taxonomy-app/src/main/java/com/taxonomy/versioning/controller/DecisionRationaleReportController.java
taxonomy-app/src/main/resources/application.properties
taxonomy-app/src/main/resources/i18n/messages.properties
taxonomy-app/src/main/resources/i18n/messages_de.properties
taxonomy-app/src/main/resources/static/js/api/portfolio-api.js
taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js
taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js
taxonomy-app/src/main/resources/static/js/core/taxonomy-state.js
taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js
taxonomy-app/src/main/resources/templates/index.html
taxonomy-app/src/test/java/com/taxonomy/DecisionRationaleReportTests.java
taxonomy-domain/src/main/java/com/taxonomy/dto/AnalysisResult.java
taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/report/ReportRenderContext.java
taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/report/ReportRendererExtension.java
LIST
sort -o /tmp/expected-decision-report-files /tmp/expected-decision-report-files

cat > /tmp/expected-transient-files <<'LIST'
.github/tmp/decision-report-package/part-00.b64
.github/tmp/decision-report-package/part-01.b64
.github/tmp/decision-report-package/part-02.b64
.github/tmp/decision-report-package/part-03a.b64
.github/tmp/decision-report-package/part-03b.b64
.github/tmp/decision-report-package/part-04.b64
.github/tmp/decision-report-package/part-05.b64
.github/tmp/decision-report-package/part-06a.b64
.github/tmp/decision-report-package/part-06b.b64
.github/tmp/decision-report-package/part-07a.b64
.github/tmp/decision-report-package/part-07b.b64
.github/tmp/decision-report-package/part-08a.b64
.github/tmp/decision-report-package/part-08b.b64
.github/tmp/materialize-decision-report.sh
.github/tmp/materialize-decision-report-parts/part-00.sh
.github/tmp/materialize-decision-report-parts/part-01.sh
.github/tmp/materialize-decision-report-parts/part-02.sh
.github/tmp/materialize-decision-report-parts/part-03.sh
.github/tmp/materialize-decision-report-parts/part-04.sh
LIST
sort -o /tmp/expected-transient-files /tmp/expected-transient-files

rm -rf .github/tmp/decision-report-package
rm -rf .github/tmp/materialize-decision-report-parts
rm -f .github/tmp/materialize-decision-report.sh

{
  cat /tmp/expected-decision-report-files
  cat /tmp/expected-transient-files
} | sort -u > /tmp/expected-all-files
{
  git diff --name-only "$INTEGRATION_BASE"
  git ls-files --others --exclude-standard
} | sort -u > /tmp/actual-all-files
diff -u /tmp/expected-all-files /tmp/actual-all-files

test ! -e .github/tmp/decision-report-package
test ! -e .github/tmp/materialize-decision-report-parts
test ! -e .github/tmp/materialize-decision-report.sh

mapfile -t report_files < /tmp/expected-decision-report-files
mapfile -t transient_files < /tmp/expected-transient-files
git add -- "${report_files[@]}" "${transient_files[@]}"
git diff --cached --check
git diff --cached --name-only | sort > /tmp/staged-files
diff -u /tmp/expected-all-files /tmp/staged-files
git commit -m 'Add hierarchical decision rationale report extension'
test -z "$(git status --porcelain)"

mkdir -p target/decision-report-diagnostics
set -o pipefail
./mvnw -B -pl taxonomy-app -am \
  -Dtest=DecisionRationaleReportTests \
  -Dsurefire.failIfNoSpecifiedTests=false test \
  2>&1 | tee target/decision-report-diagnostics/focused-tests.log

git push origin "HEAD:${TARGET_BRANCH}"
