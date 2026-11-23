# Bounded recovery for signed Ubuntu release probes

## Evidence and scope

PR1596 full source tests passed on its earlier integrated tree, but refreshed Kubernetes and production image builds have repeatedly failed before deployment while obtaining signed Ubuntu release indexes. Two configured origins were tried; the fallback and signature validation are functioning. The exact failed suites differ across attempts, and an earlier uncached build downloaded the same suites successfully. The logs show variable transport availability, not proof of a source defect or ownership of the network outage. Evidence is retained in the packed-snapshot ledger's hosted/b134-repeat-build-diagnosis.md and decoded logs.

The current selector makes one pass over the two origins. A full image retry starts again and can lose progress on a suite that previously succeeded. Add at most one further bounded selector round for the unresolved suite. This is resilience hardening for an observed failure class, not a claim to repair the upstream network. Actual hosted builds still gate delivery.

## Contract

Use the same ordered origins and the existing per-probe12s timeout,2s kill grace,6s HTTPS timeout and APT internal Retries=0. At most two rounds over the finite candidate list; with currenttwoorigins atmostfourprobes, each individuallybounded. Healthy primary/fallback selection still exits afterone/twoprobes. No additional mirror, dependency, configuration, backoff framework, cache acceptance or whole-build retry is introduced. The maximum retry exposure is explicit; the enclosing job's limit is unchanged.

Emit one clear notice on stderr when entering the second round, without changing URI-only selector stdout; healthy exits emit no retry notice. Each attempt has a fresh isolated list directory. APT must succeed, there must be exactly one physical InRelease, and its Origin and exact Suite must match before selecting any URI. Invalid signature/exit, stale or malformed metadata, wrong suite and exhausted origins must still fail closed. Preserve Signed-By, validity checks, suite enumeration/deduplication, source options, architecture, third-party and disabled sources, hosted-list mode, and actual package update/install behavior.

## Verification and release

Extend the existing .github/scripts/docker-apt-sources.test.mjs fixture to exercise stateful availability. Baseline must fail a new first-round-unavailable/second-round-verified recovery test before editing product. Test both primary and fallback recovery in the second round, early healthy exits, finite all-unavailable failure, failed-APT metadata rejection and wrong-suite rejection. Assert actual invoked candidate sequence and distinct fresh probe directories. Keep meaningful source-rewriting cases and the script's existing Maven-owned inventory. Check Bash syntax and focused Node suite; no local Docker/Maven/app needed. Fresh independent review precedes publication; unchanged application and packed Java source identities are verified. Hosted production-image and Kubernetes gates must pass; no passing claim follows merely from a fake transport fixture.
