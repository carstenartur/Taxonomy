# Guided Installation Implementation Plan

> Execute using superpowers:executing-plans. The user requested implementation.

**Goal:** Guided deployment without a second configuration system.
**Architecture:** Existing Helm config/Secret projection and Spring ConfigData;
explicit pre-context Java setup commands; jpackage around the same application.
**Tech Stack:** Java 21, Spring Boot, Helm, jpackage, GitHub Actions.
**Spec:** ../specs/2026-09-28-guided-installation.md

## Global constraints

No public setup server, no implicit network/AI checks, no new production dependency,
no main-branch writes, no overwriting existing configuration or deleting user data.

## Review focus

Existing explicit Helm deployments must remain unchanged; native paths may contain
spaces; credentials must not appear in error strings; checks must not migrate a
DB; reinstall must not overwrite or remove configuration.

## Task 1: Shared checks and local setup

- [x] Write executable regression cases and observe the missing implementation.
- [x] Implement SetupChecks, SetupProbe, LocalSetup and a ConfigData-based CLI adapter.
- [x] Wire only explicit setup commands into TaxonomyApplication before Spring startup.
- [x] Run Java contract checks and add Spring/JUnit adapter tests for CI.

## Task 2: Rancher

- [x] Add guided authentication/database fields while preserving existing mode.
- [x] Render one effective config/Secret environment and reject conflicts.
- [x] Add JSON schema and executable positive/negative Helm tests.

## Task 3: Native packages and verification

- [x] Package the existing Boot JAR with jpackage for Linux and Windows.
- [x] Add explicit Configure/Check launchers and external per-user configuration.
- [x] Add package/CLI smoke verification, unattended operation and upgrade guidance.
- [ ] Review the exact diff, push one branch/PR, inspect available CI results.

## Environment

The GitHub connector reads the repository at 6b6394b3013843f1186166b3313ffe54d538663c.
Direct container network/DNS and a full Maven checkout are unavailable. The local
workspace is a partial export, not a full clone. Java 21/javac/jpackage are present.
Use exact-source standalone contract tests locally and full project CI remotely.
A downloaded older CI executable artifact additionally supplied real Boot dependencies
and a diagnostic native launcher/start/restart test; see the explicit provenance in
`docs/qa/guided-installation-validation.md`. It is not an exact-head Maven build.
