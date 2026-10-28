# Artemis Kubernetes setup documentation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this documentation task and obtain one independent review before delivery.

**Goal:** Provide the end-to-end German setup guide requested on 2026-10-28, including the previously missing external broker installation.

**Architecture:** One persistent external Artemis broker in `messaging`; one Taxonomy coordinator and one all-root worker in `taxonomy`; an existing shared PostgreSQL database and explicitly configured model provider. Use the existing broker XML and Helm role/Secret contracts. Keep HA as a separate operational extension.

**Tech Stack:** Markdown, Kubernetes YAML, Helm 3, Apache Artemis 2.53.0, Bash, OpenSSL, keytool, Python 3.

**Spec:** The user's request to create the setup documentation proposed in the preceding conversation, plus `docs/de/OPERATIONS_GUIDE.md#betrieb-mit-externem-artemis-broker` and the current chart/configuration contracts.

## Global constraints

- Preserve the eight existing roots: BP, BR, CP, CI, CO, CR, IP, UA.
- Do not provision infrastructure or invoke paid providers while writing the guide.
- Keep application and broker administrator credentials separate and out of values, arguments, logs and committed files.
- Persist the broker journal on a PVC; do not reuse the disposable smoke fixture as a production example.
- Require TLS with certificate and hostname verification; expose only the broker protocol internally.
- Do not imply HA, throughput acceptance, or a successful live-cluster exercise from static checks.

## Review focus

- Existing PVC after Secret/configuration rotation: new configuration must be copied before every broker start.
- Namespace/service/certificate/Secret names and application/worker network rules must agree.
- The coordinator's readiness does not prove broker, worker or provider availability.
- A Helm values list replaces its predecessor: preserve all required egress and worker entries.
- A transport switch does not migrate HSQLDB data; incompatible upgrades require stopping all application roles.

## Task 1: Write and verify the operator entry point

- [x] Add `docs/de/ARTEMIS_SETUP.md` with prerequisites, broker TLS/JAAS/Secrets, persistent deployment, application values, installation, verification, troubleshooting, rotation and upgrade boundaries.
- [x] Add the referenced broker manifest and a small all-root worker values example, without changing application code or existing defaults.
- [x] Link the guide from the README, German deployment/operations guides and Helm README; also link from the English deployment entry point.
- [x] Check Markdown links/rendering, fenced shell/Python syntax, generated TLS/Secret material in a disposable local directory, Kubernetes schemas, and actual Helm rendering.
- [x] Run the existing Helm verification and report the full Maven/cluster gates separately if their prerequisites are unavailable.
- [x] Obtain independent review and resolve actionable findings. Publish this completed change as a documentation PR without merging.

## Verification evidence and boundaries

- `bash deploy/helm/taxonomy/verify.sh` with the repository's checksum-verified Helm 3.21.0: passed, including 34 existing Node tests.
- The new guide renders as Markdown; all 11 fenced blocks parse, 20 local guide links/anchors resolve, and all five edited entry points render with eight valid new links.
- Real Helm rendering preserves the existing provider and both sample site egress rules, clears a prior image tag when selecting a digest, and creates exactly one coordinator and one all-root worker.
- Four broker resources and six rendered application resources validate against the official Kubernetes 1.27.16 OpenAPI schema, including its IntOrString semantics.
- The official Artemis 2.53.0 distribution was SHA-512 verified and run locally as UID/GID 1001. Fresh instance creation, TLS/CA/hostname checks, application/operator authentication, rejected invalid/bootstrap credentials, denied application access to an admin-only queue, durable delivery across restart, and changed JAAS/configuration copying on restart all passed. Actual passwords were absent from process arguments and nine inspected logs.
- The native check first exposed an unresolved create-time template token in the hand-authored bootstrap XML. Replacing it with `file:/var/lib/artemis-instance/etc/broker.xml` fixed startup; both fresh and existing-instance paths then passed.
- Independent source review found no Critical, Important, or Minor issues. The bootstrap URI correction is backed by the failing and passing native runs.
- These checks do not execute the pinned Alpine image, read-only container root filesystem, Kubernetes volumes/CNI, or Taxonomy against a real database/model. No live cluster or paid provider was used.
- The full `./mvnw -B verify -Pci -DrunOnnxTests=true` gate was not run: this documentation workspace has Java 17 rather than the required Java 25 and no Docker. Focused checks are not represented as full CI acceptance.
