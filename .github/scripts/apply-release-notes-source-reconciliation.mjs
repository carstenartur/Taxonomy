#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';

const path = 'release_notes.md';
let notes = await readFile(path, 'utf8');

function replaceOnce(search, replacement, description) {
    const first = notes.indexOf(search);
    if (first < 0) {
        throw new Error(`Missing expected release-notes text for ${description}.`);
    }
    if (notes.indexOf(search, first + search.length) >= 0) {
        throw new Error(`Expected unique release-notes text for ${description}.`);
    }
    notes = notes.replace(search, replacement);
}

replaceOnce(
    'Taxonomy 1.4.0 is the first published release after 1.3.0. It combines the stabilization prepared for the unpublished 1.3.1 line with a substantially stronger requirements and architecture workbench, an authoritative recoverable Copilot session, a versioned Information Product catalogue overlay, bounded concrete-product analysis, versioned Word-template administration, deterministic architecture exports, local semantic-search readiness, constrained-cluster deployment profiles, bounded authentication controls, and a fail-closed release pipeline.',
    'Taxonomy 1.4.0 is the first published release after 1.3.0. It combines previously prepared stabilisation work from the unpublished 1.3.1 line with a substantially stronger requirements and architecture workbench, an authoritative recoverable Copilot session, a versioned Information Product catalogue overlay, bounded concrete-product analysis, versioned Word-template administration, deterministic architecture exports, local semantic-search readiness, constrained-cluster deployment profiles, bounded authentication controls, and a fail-closed release pipeline.',
    'opening grammar and spelling');

replaceOnce(
    'The produced DOCX records the full template ID, full 40-character Git revision, and canonical package SHA-256 in custom document properties independently of any visible template fields.',
    'The produced DOCX records the full template ID, full 40-character Git revision, canonical package SHA-256, and positive template schema version in the custom document properties `Taxonomy.Template.Id`, `Taxonomy.Template.Commit`, `Taxonomy.Template.PackageSha256`, and `Taxonomy.Template.SchemaVersion`, independently of any visible template fields. Both ad-hoc and immutable-snapshot report responses expose the same validated identity through the allow-listed headers `X-Taxonomy-Template-Id`, `X-Taxonomy-Template-Commit`, `X-Taxonomy-Template-SHA256`, and `X-Taxonomy-Template-Schema-Version`.',
    'complete template provenance');

replaceOnce(
    'Interactive login and machine monitoring credentials are deliberately separated. `TAXONOMY_ADMIN_PASSWORD` bootstraps the local form-login administrator. The historic application variable `ADMIN_PASSWORD` remains the optional Actuator/admin token. In the supplied Helm chart, the existing Secret key `ADMIN_PASSWORD` continues to supply the login credential for upgrade compatibility, while a distinct optional `ADMIN_TOKEN` key supplies the machine token and protected ServiceMonitor. The chart rejects reuse of one Secret key for both purposes and rejects an application/ServiceMonitor token-key mismatch. Token-authenticated Actuator reads work behind a context path, missing or incorrect tokens are rejected, and CSRF protection remains enabled.',
    'Interactive login and machine monitoring credentials are deliberately separated. `TAXONOMY_ADMIN_PASSWORD` bootstraps the local form-login administrator. The historic application variable `ADMIN_PASSWORD` remains the optional Actuator/admin token. In the supplied Helm chart, the existing Secret key `ADMIN_PASSWORD` continues to supply the login credential for upgrade compatibility, while a distinct optional `ADMIN_TOKEN` key supplies the machine token and protected ServiceMonitor. The chart rejects reuse of one Secret key for both purposes and rejects an application/ServiceMonitor token-key mismatch. Token-authenticated Actuator reads work behind a context path, missing or incorrect tokens are rejected, and CSRF protection remains enabled.\n\nWhen `TAXONOMY_ADMIN_PASSWORD` is intentionally empty outside production, Taxonomy writes the generated one-time password to a deterministic owner-only bootstrap credential file, logs only its non-secret path, and removes the file after the committed administrator password change. Production still requires an explicitly supplied credential.',
    'bootstrap credential delivery');

replaceOnce(
    'The 1.4.0 release train removes six previously baselined findings: the WebDAV write-scope authorization dataflow, two unbounded semantic-search arithmetic paths, and three predictable temporary-evidence paths in JavaScript tooling.\n\nEight pre-existing findings remain in a schema-validated migration baseline. Every entry is bound to its exact rule, artifact path, CodeQL primary-location fingerprint, rationale, and tracking issue. No complete rule class, severity, or path is excluded, and a new occurrence of an otherwise baselined rule remains release-blocking. The remaining entries cover the typed-request migration for the proposal bulk compatibility endpoint, a consistent non-disclosing repository/context logging contract, and replacement of startup-log delivery for a generated local bootstrap password. They remain tracked in issue #857 and must not be described as remediated in 1.4.0.',
    'The 1.4.0 release train removes seven previously baselined findings: the WebDAV write-scope authorization dataflow, two unbounded semantic-search arithmetic paths, three predictable temporary-evidence paths in JavaScript tooling, and generated local bootstrap-password delivery through the application log.\n\nThe schema-validated migration baseline contains exactly 7 pre-existing findings. Every entry is bound to its exact rule, artifact path, CodeQL primary-location fingerprint, rationale, and tracking issue. No complete rule class, severity, or path is excluded, and a new occurrence of an otherwise baselined rule remains release-blocking. The remaining entries cover three typed-request dataflows in the proposal bulk compatibility endpoint and four repository/context diagnostic logging dataflows. They remain tracked in issue #857 and must not be described as remediated in 1.4.0.',
    'CodeQL migration baseline');

await writeFile(path, notes, 'utf8');
