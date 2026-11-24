# Workspace conflicts and passive status polling

## Lifecycle responses

An explicitly pinned working copy must still belong to the caller, and repository
operations still require a ready working copy. A repository read against an owned
copy in `NOT_PROVISIONED`, `PROVISIONING`, or `FAILED` now includes the stable
`WORKSPACE_NOT_READY` code and its `workspaceStatus` in the existing HTTP 409 error
response. Waiting for a source's first checkpoint uses `INITIAL_SOURCE_PENDING`.

`GET /api/workspace/sync-state` is a metadata operation. Its existing synchronization
observation reports the selected working copy's lifecycle with HTTP 200 without
opening or provisioning a Git working copy. The explicit pin's ownership check
and the source repository's current read permission remain mandatory. The other
repository and mutation endpoints retain their readiness checks. The special
HTTP 202 response from `/api/workspace/current` while a default source initializes
is unchanged.

## Browser behavior

Git state, current context, synchronization and startup observations schedule their
next request after the current request completes. A slow response no longer causes
an interval to abort and restart it repeatedly. Failures double the delay, up to
60 seconds. Successful observations restore the ordinary interval; synchronization
also backs off when its successful response reports an unready lifecycle.

| Observation | Normal interval | First failed retry | Maximum delay |
| --- | ---: | ---: | ---: |
| Git state and current context | 10 seconds | 20 seconds | 60 seconds |
| Synchronization and system status | 15 seconds | 30 seconds | 60 seconds |
| Catalogue startup | 5 seconds | 10 seconds | 60 seconds |

HTTP 401/403 stops automatic retries for that poll. Context selection and deliberate
refresh retain their existing recovery paths. Page departure cancels timers and
owned requests; restored pages restart eligible observations without duplicate
timers. Responses from a previous workspace or document generation cannot repaint
the current state. The system status bar starts once, instead of issuing both an
immediate request and another on `DOMContentLoaded`.

The Git bar retains the server's conflict explanation, including older HTTP 409
responses without a typed code. A conflict indicates a responding server and no
longer announces a lost connection. Known workspace lifecycle states use the
existing translated explanations. The synchronization view exposes a conflict
instead of silently retaining a misleading synchronization state.

Automatic session recovery observes the context poll's current outcome rather
than starting another context request after every Git observation. This preserves
the backoff and authentication stop through the actual session consumer. A Git
conflict pauses draft actions with its explanation without initiating another
draft-status read. Selecting a different source clears the previous source's
indicators and restarts the eligible polls before the first successful context
publication. Canceling a navigation attempt resumes Git observation.

A newer unavailable/conflict observation invalidates older draft checks and
restoration results. An already committed write receipt still updates the local
revision bookkeeping, but cannot reopen saving after that newer observation.
A reset queued behind a save is canceled before sending if the observation
changed while it waited. A fresh successful check restores admission normally.
Git results waiting for translations also recheck authentication before rendering
or announcing successful contact.

## Server diagnostics

Unexpected MVC client errors remain warnings. Known `WORKSPACE_NOT_READY` and
`INITIAL_SOURCE_PENDING` conflicts are expected lifecycle events and use DEBUG
with `type=MVC_STATE_CONFLICT`. This does not downgrade arbitrary HTTP 409 errors.
Server errors remain ERROR.

Diagnostic fields identify the standard HTTP method, registered controller method,
exception class, allowlisted code and allowlisted workspace lifecycle. For example:

```text
Request failure: status=409, type=MVC_STATE_CONFLICT, method=GET, handler=GitStateController.getState, exception=WorkspaceNotReadyException, code=WORKSPACE_NOT_READY, workspaceStatus=PROVISIONING
```

The controller identity comes from Spring's resolved `HandlerMethod`. An unmatched
request uses `handler=UNMAPPED`; a nonstandard request method uses `method=OTHER`.
Raw request paths and even request path-pattern attributes are excluded. Headers,
client-supplied request IDs, exception messages, payloads and causes are never
passed to these log calls, including at TRACE. Existing client response paths and
actionable 4xx messages are preserved.

The reported production log sample supplied only status 409. These changes fix a
reproducible lifecycle/polling defect and improve diagnosis of other conflicts;
they do not establish which endpoint produced every historical production entry.

## Regression evidence

The workspace readiness and synchronization tests exercise the real resolver,
interceptor and synchronization service, including foreign-workspace ownership
and revoked source access. Global handler tests capture actual log events and
verify both redaction and client response contracts. The JavaScript lifecycle
tests execute the production scripts with controlled HTTP, clock and DOM boundaries
to cover backoff, recovery, slow requests, authentication, source switches and
page departure. See [the QA record](../qa/2026-11-24-copilot-diagnostics.md).
