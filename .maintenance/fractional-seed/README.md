# Existing fractional-percentage implementation: exact source transfer

This branch is a temporary delivery carrier, NOT a product PR and NOT intended for main. The six files contain compressed JSON data, not an executable installer. The visible Python decoder validates the complete input digest, every old/new UTF-8 file digest, and the complete resulting Git tree before writing a standard reviewable Git patch. It never changes the working tree, normal index or branch refs. It creates only Git blob/tree objects and a temporary index.

The code was developed in the preceding user-authorized sessions and locally compiled/tested with real runtime dependencies. Do not reimplement it from a summary. The missing full Maven/JUnit/PostgreSQL verification must be completed in the normal development environment before merge. Do not confuse targeted direct Java checks with a full JUnit run.

## Identities

Original upstream base commit: `406ee89aa7183c4e50c7b91c0006bdf9718911bd`.
Base source tree: `0e8194419722ab30f9a673110468625fbe3fe6e6`.
Fractional-only target tree: `7bf9090c040ddf9cdd5e6a6f2d909c701795e531`.
266 text paths; no workflow or Maven POM changes.
Decompressed JSON SHA-256: `3eb8c6c451873a3b364d1733d9e3119db3a30be8ec8e0c0c0e1b401fd8f7444f`.
Standard reconstructed full-index patch SHA-256: `9288647f39a3d5b1f7df9d9a5d287f782228e12ff31bbf2d0491889e5b619eff`.

The locally archived synthetic commit IDs are deliberately NOT published as upstream history. Only the source-bound change is transferred. The final old #1238 integration commit is deliberately excluded: preserve newer export changes from #1241 when applying to current main.

## Usage in a clean branch based on current main

Fetch this delivery ref and the original base, extract only `.maintenance/fractional-seed` to a temporary directory outside the working tree, then run:

```sh
python3 /tmp/fractional-seed/restore_patch.py --repo "$PWD" --output /tmp/fractional.patch
sha256sum /tmp/fractional.patch
git apply --3way --index /tmp/fractional.patch
```

The decoder reads the original base objects even when the checkout is newer. Review three-way conflicts and preserve intervening fixes, notably the real PDF pagination and Visio improvements of #1241. Do not commit the delivery files or script into the product PR.

## Scope and non-negotiable semantics

`0.5` means 0.5 percent, not 50 percent. Category children share their parent's absolute percentage; independent product suitability keeps separate semantics and effective relevance is their product divided by 100. The existing implementation uses decimal validation/arithmetic with finite Double DTO/JSON boundaries. Do not claim unlimited precision. Preserve positive sub-percent values; explicit zero, unknown, failed assessment and unresolved work are distinct. Legacy 85 remains 85 percent. No 1-percent floor, invented endpoints, new paid-provider calls, generalized taxonomy rewrites, or whole-tree rescaling.

The seed contains prompt/parser changes, typed DTOs, legacy-compatible snapshots/checkpoints/worker message versions, HSQL/PostgreSQL migrations, cache/reference scoring, restore/backup, manual UI editing and export formatting. Business counts, levels, priorities and call budgets remain integers. The initial tests are normal Maven-owned JUnit tests; small JS scripts have their existing JUnit ownership.

## Remaining acceptance

Run normal Maven tests with real JUnit, Mockito, AssertJ and Spring Test; fix any remaining source/behavior expectations without deleting tests, weakening coverage or adding exclusions. Run `./mvnw -B verify -Pci -DrunOnnxTests=true`, the existing database compatibility profile including PostgreSQL, and relevant recovery/worker tests. Test 1 -> two 0.5 -> four 0.125 children, long chains, legacy integral storage, failed-response not-zero, and immutable checkpoint policy. Update stale local-only wording in `docs/dev/fractional-percentages.md` based on fresh evidence. The final protected PR must contain ordinary Java/JS/SQL/source/test changes only, with honest exact-head evidence.
