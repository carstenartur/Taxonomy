# WebJar Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the existing application WebJar closure without remote version-list metadata.

**Architecture:** Manage the exact successfully tested transitive versions in the root POM. Preserve the complete selected closure and validate it with real Maven plus an existing-owner regression.

**Tech Stack:** Java 25, Maven 3.9.16, JUnit 5, existing GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-20-webjar-resolution-design.md`

## Global Constraints

- Preserve all 52 selected NPM WebJar artifact/version pairs.
- Retain the two existing managed pins; manage the remaining 46 active targets.
- Preserve optional dependency semantics and every existing security/CI gate.
- Do not assert an unproved network/cache root cause.

## Review Focus

- Deeper transitive ranges must be covered, not only the first 35 failures.
- Optional ranges must not become new runtime dependencies.
- Existing prerelease selection must not silently become a dependency upgrade.
- A fresh metadata-denied resolver must use fixed artifacts rather than warmed version lists.
- The resulting actual dependency set must equal the proven baseline.

## Task 1: Fix and verify the selected WebJar closure

**Files:** root `pom.xml`; focused test under the existing application/build test owners; QA evidence in `target/webjar-resolution-qa/` and a concise committed QA note.

**Interfaces:** consumes the exact artifact/version map from verified CI and native dependency POMs; produces an unchanged resolved closure with explicit managed versions.

- [x] Record the tested and packaged baseline and prepare an isolated repository fixture without WebJar version metadata.
- [x] Run the real Maven fixture and focused regression before changing dependency management; confirm the relevant failures.
- [x] Add the 46 exact managed versions to `pom.xml`, retaining existing pins and optional behavior.
- [x] Repeat the resolver with a fresh consumer repository, compare the full selected set, and run the focused regression plus normal packaging.
- [ ] Obtain independent review, publish a small follow-up PR, verify all current-head checks, merge, and verify main plus automatic Delivery.
