# Frontend API boundary

Taxonomy browser modules must use the transport clients below
`taxonomy-app/src/main/resources/static/js/api`. Feature, workspace, relation,
and versioning modules must not introduce their own direct `fetch()` calls.

## JUnit policy

`FrontendApiBoundaryPolicyIT` runs in the final `taxonomy-build` module during
the canonical Maven verification. It combines the two historic checks into one
authority:

1. Literal `fetch('/api/...')` calls outside `static/js/api` must belong to the
   explicit migration inventory. New files cannot be added silently.
2. Every direct `fetch()` call outside the API clients and the base-path-aware
   `taxonomy-i18n.js` bootstrap is compared with the Git baseline. Per-file and
   aggregate legacy debt may stay equal or decrease, but never increase.

For pull requests, CI exposes the pull request base SHA as
`FRONTEND_API_BASE_REF`. Push and local verification fall back to `HEAD^`. The
Git revision is passed directly to `git show` without shell evaluation.

The canonical command remains:

```bash
./mvnw -B verify -Pci
```

The policy writes review evidence to:

```text
target/frontend-api-boundary-report.txt
```

The report records the baseline, current and baseline debt totals, the remaining
literal `/api` inventory, allowlist entries that can now be removed, every
violation, and the final decision.

## Allowed transport owners

- JavaScript and module files below `static/js/api/`;
- `taxonomy-i18n.js`, because it installs the application-base-path-aware fetch
  wrapper used by all clients.

Moving a call between feature modules does not reduce debt. New API interactions
must be added to a client under `static/js/api` and consumed from there.

## Migration workflow

1. Move one feature's request construction and response/error handling to the
   appropriate API client.
2. Replace direct feature-module calls with the client function.
3. Remove the migrated file from the fixed legacy inventory when its literal
   `/api` count reaches zero.
4. Run the canonical Maven verification and inspect the generated report.

Positive and negative JUnit fixtures cover API owners, the bootstrap exception,
legacy reductions, per-file increases, new modules, shifted aggregate debt,
line-number diagnostics, templates, `.js`/`.mjs` scanning, invalid UTF-8, and
stable evidence writing.
