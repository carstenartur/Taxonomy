# Source and artifact evidence manifest, version 1

The manifest joins existing evidence by immutable source and SHA-256. It is an
inventory and verification boundary, not another publisher or a replacement for
Maven, database, UI, scan or deployment acceptance.

The machine-readable shape is in
[evidence-manifest-v1.schema.json](evidence-manifest-v1.schema.json).
`TaxonomyTooling` implements the semantic checks, including original XML recounts,
coverage metrics, UI source/outcomes, gate outcomes and release ancestry.

## Identities and profiles

`sourceCommit` and `sourceTree` are canonical full Git object IDs for the artifact
source. With one source, `verificationCommit` and `verificationTree` may be omitted.
A release can record a distinct final main source in those two fields. The caller
must independently supply that expected verification commit and the repository
used by `ReleaseHistoryVerifier`; an untrusted manifest cannot select a newer main
or an unrelated source. Release-to-main ancestry or the existing exact-tree rebase
mapping must verify. The repository's actual source and verification trees must
match their recorded identities.

Each artifact records `kind`, a path relative to the archive root, `sourceCommit`
and lowercase hexadecimal `sha256`. Quality and UI rows must themselves name the
verification commit, matching their payloads. Paths cannot escape through traversal,
absolute paths or symlinks. Paths cannot be duplicated. Every file must exist
and match its digest. Each source must be one of the explicit artifact or
verification sources; quality and UI contents must name the verification source
and tree themselves.

The `quality` profile requires `quality`, `junit`, `coverage` and `ui` kinds.
The existing finalizer emits it using:

```bash
bash .github/scripts/taxonomy-tooling.sh write-quality-manifest \
  --root target/quality-reports \
  --expected-commit "$GITHUB_SHA" --expected-tree "$source_tree"
```

The `release` profile additionally requires `sbom`, `vex`, `image`, `image-onnx`,
`security`, `database`, `distribution` and `helm` kinds. It requires exactly these
named successful gates: `ci`, `database-postgresql`, `database-sqlserver`,
`database-oracle`, `codeql`, `security`, `ui`, `packaged-plugins`.
Each gate records the explicit verification `sourceCommit` and `outcome: success`.
Missing, duplicate, foreign, skipped, cancelled and unsuccessful gates fail.
An artifact inventory does not itself prove that a producer ran; retain the
original producer receipts and the existing source-bound workflow evidence.

## Rechecking an archive

For one exact source:

```bash
bash .github/scripts/taxonomy-tooling.sh check-evidence-manifest \
  --root target/quality-reports \
  --file target/quality-reports/evidence-manifest.json \
  --expected-commit "$SOURCE_SHA" --expected-tree "$SOURCE_TREE"
```

For a release artifact source with separately verified final main:

```bash
java -jar "$TOOLING_JAR" check-evidence-manifest \
  --root "$ARCHIVE_ROOT" --file "$ARCHIVE_ROOT/evidence-manifest.json" \
  --expected-commit "$RELEASE_SOURCE_SHA" --expected-tree "$RELEASE_SOURCE_TREE" \
  --verification-commit "$EXPECTED_MAIN_SHA" --repository-root "$CHECKOUT" \
  --expected-image-digest "$IMAGE_DIGEST" \
  --expected-onnx-image-digest "$ONNX_IMAGE_DIGEST"
```

The verifier reopens the quality summary's original JUnit files, validates every
suite/testcase counter and rejects flaky/rerun or DTD/entity evidence. It compares
summary arithmetic, badges and raw report inventory, then recomputes aggregate
coverage from the recorded JaCoCo XML. Recomputing an artifact's digest after
changing raw outcomes or coverage does not hide metric drift. Registered and
skipped invocations can coexist when the aggregate retains positive execution;
negative counts, zero aggregate execution, failures and errors do not constitute
a successful quality manifest. Individual gate skips are never successful gates.
The UI summary must retain its source/tree, successful shards and consistent
scenario totals.

Standard and ONNX image receipts are checked against the independently supplied
expected image digests, artifact-source commit, correct variant, successful Trivy
severity gate and required provenance/SBOM attestation flags. The receipt writer
and manifest verifier share that authority. The existing SBOM companion authority
checks that VEX names the exact SBOM digest, serial number and version. Archived
security SARIF is evaluated by the same Trivy HIGH/CRITICAL gate as the workflow.
These checks verify recorded receipts; they do not replace the existing actual
image scan or external OCI attestation execution.

Binary distribution and Helm files cannot reveal a Git commit merely by being
hashed. Their source attribution is trusted input from the existing source-bound
producer/archive receipt and exact release checkout; callers must retain those
producer identities and checksum evidence when assembling an archive. A supplied
manifest is not an independent proof that an external producer ran. Render/live
installation acceptance remains a separate receipt; this release profile does not
claim an actual deployment.

`publication` is a required boolean declaration. `false` records a non-publishing
build or fixture; setting it to `true` does not authorize or execute publication.
All commands are local file readers/writers. Existing remote release, deployment
and DOI evidence remains separate, including explicitly unexecuted acceptance.
