# Active AI model transparency

## Intent

Carsten authorized the recommendation to show the actual embedded AI, its origin,
licence, revision and state in About, distinguish the separate analysis provider,
and compare European embedding candidates fairly before changing the default.
This increment implements the About part. The comparison and interactive analysis
have separate tasks because neither should delay a useful, independently testable
transparency feature.

## Design

Keep the existing Java 25/Maven modular application, DJL/ONNX inference and About
dialog. Extend the existing About response with a small typed AI information
snapshot assembled in the application composition layer. The knowledge service
owns the read-only embedding runtime snapshot and model provenance. Reading the
snapshot must never load weights, perform inference, download files, count indexes
or invoke a model endpoint. Use only state and identities already retained in
memory. About remains available when AI is disabled or its configuration is invalid.

Report the configured profile separately from the loaded artifact identity.
Known default or BGE metadata can describe the configured profile before load;
only a loaded artifact whose complete expected bundle matches may be presented
as a verified upstream model. A custom local directory or URL is not evidence of
publisher, licence or revision. Display custom/unknown provenance honestly.
Provide model/tokenizer hashes and the inference identity once loaded. Do not
expose credentials, raw exception messages, URLs with userinfo/query secrets or
operator filesystem paths.

The embedding states distinguish disabled, not yet loaded, loading, ready,
failed and stopped. The separate analysis provider summary identifies the
configured provider/model, its configuration availability and execution mode:
embedded ONNX, provider HTTP, operator-configured endpoint, plugin or mock/replay.
Do not infer that an arbitrary operator HTTP endpoint is local. Do not imply that
an embedding encoder is a generative relationship model.

Render a visible, accessible DE/EN "AI and models" section inside the main About
pane, with long identifiers wrapping on mobile. Show detail/provenance without a
hover requirement. Refresh when About opens; handle missing optional data and
failed requests explicitly without retaining stale runtime information. Keep
existing build/legal links and focus behavior.

## Approaches considered

* Static model text only: simple, but cannot explain disabled, overridden or
  failed deployments. This is already available in the legal notices.
* Reuse the full search status API: gives readiness but also performs index work
  and has insufficient actual artifact provenance.
* A side-effect-free runtime snapshot in About: selected because the model
  service already owns the state and content identity, and the UI needs no extra
  endpoint or new infrastructure.

## Verification

Behavior tests cover disabled/unloaded/custom/known loaded states, failed loading,
no inference/download side effects, unsafe configured values, and About with an
invalid provider configuration. Browser verification covers DE/EN, 390px layout,
context paths, reopened About, failure and safe rendering of supplied model text.
Use Maven-owned verification; retain raw test output only under target/.
