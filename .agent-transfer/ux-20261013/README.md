# Temporary transfer of the owner-approved Taxonomy UX patch

This branch is a transfer-only staging area, not a product PR. NEVER merge the encoded transport files or a build-time patch application into main. The final implementation must be an ordinary, reviewable source diff, with no `.agent-transfer` files.

The four `part-*.b64` files, concatenated in numeric order and base64-decoded, contain the gzip-compressed exact patch previously supplied to the owner in ChatGPT. The input main commit is `9e518bed083db86c4098825b6cd3cbe16e3c00bb`, tree `cfb5795e03b0270c7bc6139b3441b86ed0899cf6`.

SHA-256 of decoded gzip: `6a457486dba048eb3535cfd110716c9a0d8a99ea56c3b41d6c9e011359dc3dd6`.
SHA-256 of uncompressed patch: `a59f01f5c543cd7f82dd6fe93dc27393a24b0a035bcb84840f7442a5d20481f9`.

Apply the decoded patch only after both hashes and `git apply --check` pass. It changes 19 production resources and adds two native browser component tests plus one scope/verification document. The tests were rerun against the exact production resources with the original Bootstrap 5.3.8 stylesheet: 45 passed, zero failures/skips. This is not full server, Maven, distributed broker, or independent-review acceptance.

Finish integration in a full source checkout: execute the existing suites, wire the new tests into the existing browser verification (no additional Actions workflows), resolve legitimate regression changes without weakening gates, and obtain review. Retain role restrictions, recovery semantics, cancellation and existing analyses. The separate server-side relations-only supplementary operation and detailed Artemis telemetry are NOT implemented by this patch; do not describe this frontend subset as those features.

The author explicitly authorized publication and protected-branch merge after successful verification. Do not bypass repository rules, auto-approve, or merge this transfer branch.
