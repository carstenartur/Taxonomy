# Combined-source documentation screenshots — 7 November 2026

The 77 refreshed PNGs and both generated manifests come unchanged from the successful [combined-source PR 1404 screenshot run](https://github.com/carstenartur/Taxonomy/actions/runs/42322441904), [capture job](https://github.com/carstenartur/Taxonomy/actions/runs/42322441904/job/128249920789). This source combines the REST permissions and CSRF changes, shared-editing/recovery work, compact application toolbar, visible score legend and standalone installation-snapshot tooling. Earlier screenshot artifacts remain historical evidence for their own source and bytes; none supplies the files in this refresh.

## Exact capture provenance

| Field | Recorded value |
|---|---|
| Artifact | `generated-documentation-screenshots`, ID `13554715149`, 9,701,878 bytes |
| Artifact ZIP SHA-256 | `eaa335e6386c286ab122709dde89dc98a4dab093ec5f3615953cd72a96ce68b5` |
| Actual capture commit | `131e8e29b4ce2e89275f8eb3bc6cf6e8686f03dd` |
| Capture tree | `e5171822b9272dba57dbc01606ab83bd07aee03c` |
| Capture merge parents | `b1375bf8b47ae1f4f393a2e00aef66f4bb56990c`, `fad4db84db08a936764ef05e5498932f2b870747` |
| Published PR source | `fad4db84db08a936764ef05e5498932f2b870747` |
| Matching local product checkpoint | `de98f75ee86b56bdfd074ff5004fe12066e9ab5b` |
| Working-tree state | `sourceDirty=false`, `sourceChanges=[]` |
| Maven run start | `2026-11-07T01:10:56Z` |
| Common manifest recorded at | `2026-11-07T01:21:04.466160513Z` |
| Entry point | `./mvnw -B verify -Pscreenshots` |

The synthetic capture merge, published PR source and local product checkpoint have the same tree. The final addition combines prose and captured assets with three test-only CI fixture adjustments. Runtime code, templates, styles and capture owners match the capture baseline. The generated metadata retains the actual capture commit after the documentation commit advances HEAD.

The post-capture test delta was independently reviewed as `b3ecb2058084a29132c0ea8145e3d06b8d36ddb4`: ArchiMate export positives use the execution-capable USER role; source-focus tests retain authentication/CSRF checks and require denial for ADMIN/READER without execution permission; the isolated account/editor context supplies its required collaboration-service mock. Both Bearer-scope editability assertions remain. All 152 focused cases across eight classes passed, including all 65 cases in the three affected owners. These fixture changes alter no captured runtime or capture-owner bytes; the 79 generated files remain byte-identical to the artifact.

The original [common manifest](../images/documentation-screenshots.json) records dimensions and hashes for all 77 PNGs. The original [decision-export component manifest](../images/decision-export-screenshots.json), recorded at `2026-11-07T01:21:02.046Z`, records six labelled examples, their production-source hash and Chrome `149.0.7827.55`. Both manifests identify the same capture commit. All 79 retained files match the downloaded ZIP byte for byte. The component source hash also matches the committed production JavaScript. Four older capture manifests remain unchanged historical records; their old hashes do not describe these replacement bytes.

## Capture checks and visual review

| Existing capture owner | Passed integration cases |
|---|---:|
| `ArchitectureWorkbenchUiIT` | 1 |
| `CompleteCopilotSessionIT` | 1 |
| `ScreenshotGeneratorIT` | 55 |
| `DocumentTemplateReportDownloadIT` | 1 |
| `ScreenshotDocumentationIT` | 2 |
| Total | 60 |

All 60 integration cases passed with zero failures, errors or skips. Five Mermaid plugin unit cases also passed. The screenshot Maven run completed successfully in 10 minutes 8 seconds. These results describe this workflow, not every PR gate or native database qualification.

Every PNG decodes and matches its recorded dimensions and SHA-256. All **66 unique PNG references** in the DE/EN guides are covered. All 77 images were reviewed through 13 contact sheets. Additional native-size inspection covered navigation 01/14, legends 10/17, exports 23/33, Swagger 70, both editor overviews, both saved-snapshot workbenches and complete Copilot result 72. Review-only crops confirm complete localized labels where the multi-image viewer downsampled them. Raw artifacts, contact sheets, crops and execution logs remain outside committed documentation.

- Swagger 70 now includes all four application-role rows, the explicit ARCHITECT requirement for business editing by an administrator, and the complete role-group, scope, resource and CSRF guidance. The server selector and Authorize control also fit. This is the local Swagger overview, not a demonstration of every authenticated API operation or Keycloak login.
- Navigation 01/14 shows command menus and work-area links on one compact toolbar, with System status and selected context below. Legends 10/17 show all five swatches continuously without a disclosure control.
- Both editor overviews show current projection status, editor actions, the actual private sharing state and enabled sharing action, both created example nodes within the canvas, and selected-element properties with preview controls. The existing owner checks readiness, bounds and hit-testing before and after capture. The canvas remains a pannable model viewport. These images illustrate a private working copy, not a completed contributor-sharing operation.
- Result 72 includes the selected immutable snapshot, `MOCK` metadata, shared report action and all six result indicators. The owner requires those controls to fit and remain unobscured.
- Exports 23/33 retain genuinely locked score-dependent controls. Queries 21/22 retain the actual empty BP1327 catalogue result. The scored tree is the UA branch. Graph panel 11 has an edited input above its last completed query; it is not evidence of a completed new query. Long-page and history images retain their intended viewport/element crops; navigation evidence comes from 01/14.
- Localized workbench, lens, database-entry, print-control and root-clarification captures retain their documented states. Six decision-export dialogs use authored example input. The report image renders a page from the actually downloaded DOCX.

Application captures use deterministic `MOCK` or declared demo data without external LLM calls. Their relevance values do not establish live-provider quality. Database connection-entry images do not prove a successful external connection or migration. **Native Enterprise Architect and Microsoft Visio application acceptance was NOT EXECUTED by this capture workflow**; listed export formats do not establish native round-trip fidelity. Native Microsoft Word/WebDAV acceptance is separate from the rendered DOCX illustration. This review makes no native Oracle Data Pump success claim; those results require separate qualification evidence.

## Documentation checks

After staging the original artifact files and paired prose in the fresh worktree, the existing `DocumentationLinkPolicyTest` (6), `HelpHeadingTest` (5) and `ContextualHelpTest` (8) passed: 19 cases, zero failures, errors or skips. The selected 20-module reactor completed successfully in 1 minute 41 seconds. This check started no Spring application or browser and skipped frontend contracts; the hosted native capture results are recorded separately above.

```bash
./mvnw -B -ntp -pl taxonomy-app -am test -Ptest-local \
  -Dtest=DocumentationLinkPolicyTest,HelpHeadingTest,ContextualHelpTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.build.cache.enabled=false -Dtaxonomy.frontend.skip=true
```
