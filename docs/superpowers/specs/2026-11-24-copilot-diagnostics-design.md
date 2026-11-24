# Copilot diagnostics and workspace polling

## Goal

Address the weaknesses identified in the 409/MVC diagnostics and Copilot prompt investigation. Production log samples identify status 409, but do not identify a particular request; distinguish the proven code defects from an unverified production root cause.

## Design

- Workspace readiness conflicts expose a stable `WORKSPACE_NOT_READY` code and the existing lifecycle enum as `workspaceStatus`. Initial source startup exposes `INITIAL_SOURCE_PENDING`. Ownership, explicit source selection and mutation readiness remain enforced.
- The synchronization status endpoint is a metadata operation: its existing read-only observation can report provisioning without opening an unavailable Git working copy.
- Passive status polling backs off on failure, avoids overlapping requests, resets on recovery or context changes, and stops on page departure. HTTP conflicts retain their server explanation and do not classify a reachable server as disconnected. Use existing client/runtime infrastructure where practical.
- MVC diagnostics include registered controller/method identifiers, safe standard HTTP methods, exception types and allowlisted error codes. Identifiers come from the resolved `HandlerMethod`, not a request path or pattern. Never log raw URLs, headers, request identifiers supplied by clients, messages, payloads or exception causes. Known readiness conflicts are expected lifecycle diagnostics; other conflicts remain warnings.
- The prompt display renders complete structured JSON input values as safe text with real line breaks. Keep instructions, original prompt, exact copying and truncation evidence. Never blindly replace backslash escapes or repair partial JSON. Use one renderer in the progress log, legacy log and recovery details.

## Validation

Meaningful regressions cover unready owned versus foreign workspace access, safe diagnostic metadata and redaction, failure/backoff/recovery/abort behavior, and structured prompt rendering with exact copies, literal escapes, hostile markup and truncated input. Review a real Chromium rendering at desktop and narrow viewport. Model requests and budgets are unchanged.
