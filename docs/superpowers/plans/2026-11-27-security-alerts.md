# GitHub Security Alerts Implementation Plan

> **For agentic workers:** Use systematic debugging and verification-before-completion. Work is authorized by the user's request to read and fix Taxonomy's GitHub Security findings. Independent investigation and implementation use isolated file ownership.

**Goal:** Resolve the actual causes of the current Dependabot and Trivy findings and make security scan coverage trustworthy.

**Architecture:** Keep version alignment in the existing Maven parent and deployment policy in the existing manifests. Preserve the current scanner and Maven security gates. Resolve proven scanner false positives individually with evidence rather than weakening protections or changing image users without validating their requirements.

**Tech Stack:** Java 25, Maven Wrapper, Kubernetes/Helm, Trivy, GitHub Actions.

**Spec:** User request of 2026-11-27; current alerts in `https://github.com/carstenartur/Taxonomy/security`, inspected at main `724f4111be87feb93682a928496bc8fda37d4bbf`.

## Global Constraints

- Preserve unrelated work and other working directories. PR #1612 was integrated
  independently; the security branch now starts at its main commit `487c264`.
- Keep Maven as the entry point for owned build and verification logic.
- Retain vulnerability, secret and misconfiguration scanning and the existing HIGH/CRITICAL failure gate.
- Do not dismiss actual unresolved vulnerabilities, broadly disable rules, weaken deployment controls or invent a live runtime test.
- Keep immutable image references and verify compatibility before altering an image's UID/GID.
- The initial inventory is one Dependabot alert, 43 Trivy alerts, zero secret-scanning alerts. Capture final counts from the live UI.

## Review Focus

- All OpenTelemetry components must stay aligned, including test-scoped Selenium dependencies.
- A scanner rendering failure must not be reported as complete scan coverage.
- A scanner rule must be evaluated against the actual Kubernetes resource kind; declarations in a different document are not missing policies.
- Parameterized smoke manifests must still render in both supported namespaces and keep their local-only image policy.
- Artemis/PostgreSQL persistence and non-root permissions must continue to match the pinned images.

## Task 1: OpenTelemetry dependency remediation

**Files:** `pom.xml`; existing dependency tests if a real regression requires them.

- [x] Record the resolved vulnerable dependency chain and parent BOM property.
- [x] Override the existing family property to the patched 1.67.0 release or newer compatible patch.
- [x] Resolve all affected components, compare their versions and test scopes, and run the relevant existing Maven validation/dependency resolution. Full CI remains the integration gate.

## Task 2: Deployment findings

**Files:** `deploy/helm/taxonomy/artemis-smoke-fixtures.yaml`, `small-provider.yaml`, its renderer and related existing contracts; `deploy/artemis/kubernetes/broker.yaml` and `deploy/rancher/persistent-volume-claim.yaml` only where actual fixes are required.

- [x] Inspect each affected resource and trace its actual installation/rendering path.
- [x] Reproduce actual policy/renderer gaps with existing tools or a focused behavior regression.
- [x] Apply minimal compatible fixes, preserving pinning, namespace semantics, non-root execution and volume permissions.
- [ ] Run the affected existing contracts through Maven and syntax/render validation; identify any runtime boundary that cannot be exercised locally.
  - Original Helm contract sources pass 20/20 under isolated JUnit with pinned Helm.
    Local reactor attempts were killed; full Maven execution and live PVC reuse
    remain required CI gates.

## Task 3: Scanner coverage and accurate findings

**Files:** `.github/workflows/security-scan.yml`, minimal Trivy configuration and documented verification/QA evidence.

- [x] Reproduce KSV039/KSV040 object-selection behavior with the actual supported Trivy version.
- [x] Select explicit scanner version and approved registry data; supply valid scan-only Helm values without changing chart production defaults.
- [x] Preserve all reported severities and enforce scan/render failures plus the existing Maven HIGH/CRITICAL gate.
- [x] Compare before/after scanner results and retain exact reasons for any per-alert false-positive or accepted image-user resolution.

## Task 4: Integration and live verification

- [x] Independently review the complete change and address important findings.
  - Reject partial SARIF uploads, require exact registry hosts, and verify SARIF
    finding integrity against raw scan JSON. Regression tests pass for all three.
- [ ] Publish one focused PR, inspect checks for the exact reviewed revision, and integrate after required checks pass.
- [ ] Recheck Dependabot, Code Scanning and Secret Scanning on updated main; distinguish fixed, false-positive and mitigated results in the user report.

## Evidence checkpoint

Baseline Security Scan run `45966770020`, job `140397205236`, used Trivy 0.70.0. Its log reported a Helm render error because the SNAPSHOT chart requires `image.tag` or `image.digest`, but the run passed. This is an observed coverage defect, independent of the lower-severity configuration findings.
