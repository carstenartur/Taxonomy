# Model commit signatures

Taxonomy can sign newly created model Git commits in personal workspaces and
central project/fork repositories. This is an operator-managed **service signature
on behalf of the authenticated user**. It does not mean that the user possesses a
personal private key. Git author metadata and a valid signature answer different
questions: an author field names an actor; signature verification checks the
stored object against a trusted signing key.

## Runtime configuration

Signing is opt-in. Provision an OpenPGP secret key through your deployment's
secret mechanism and mount its file so that only the application service account
can read it. Supply a passphrase through runtime secrets when the key is encrypted.
Taxonomy does not generate production keys or accept private keys through the
model history API.

| Spring property | Environment variable | Default / meaning |
|---|---|---|
| `taxonomy.git.signing.enabled` | `TAXONOMY_GIT_SIGNING_ENABLED` | `false`; enable signing of new commits |
| `taxonomy.git.signing.secret-key-file` | `TAXONOMY_GIT_SIGNING_SECRET_KEY_FILE` | Empty; path to the OpenPGP secret key file |
| `taxonomy.git.signing.passphrase` | `TAXONOMY_GIT_SIGNING_PASSPHRASE` | Empty; passphrase if required by the key |
| `taxonomy.git.signing.trusted-public-key-files` | `TAXONOMY_GIT_SIGNING_TRUSTED_PUBLIC_KEY_FILES` | Empty; comma-separated trusted public key file paths |
| `taxonomy.git.signing.trusted-public-key-directory` | `TAXONOMY_GIT_SIGNING_TRUSTED_PUBLIC_KEY_DIRECTORY` | Empty; directory of trusted public keys |
| `taxonomy.git.signing.require-signatures` | `TAXONOMY_GIT_SIGNING_REQUIRE_SIGNATURES` | `false`; reject unsigned incoming commits when `true` |

For example, mount a service key at `/run/secrets/taxonomy-model-signing.asc`, set
`TAXONOMY_GIT_SIGNING_ENABLED=true` and point
`TAXONOMY_GIT_SIGNING_SECRET_KEY_FILE` to that path. Set the passphrase separately
through the secret mechanism; do not put it in shell history or checked-in
configuration. Enabling signing with a missing, malformed, unusable or incorrectly
unlocked key fails closed rather than silently creating unsigned commits.

The configured signing key's public key is trusted automatically. Additional
public key files support external operators and rotation; verification also loads
these files when local signing is disabled. Trust is explicit configuration, not a
claim inferred from an email address, remote URL or Git author name.

Copies of the same certificate are reconciled by full fingerprint. The newest
authenticated, currently effective self-certification or subkey binding determines
expiry and signing permission for both signing and verification; an authenticated revocation remains
effective. An older public copy, including the one in the secret-key file, cannot
override these updates. Restart the application after changing key files to load
the updated certificates.

Keep private keys and passphrases outside all model and source repositories,
tracked provenance, HTTP responses, logs, test fixtures and application backup
exports. Back up secret material separately using the deployment's secret recovery
process. Restoring the application database does not restore a runtime key mount.

## Incoming history and transport

The default incoming policy accepts unsigned legacy commits but rejects invalid,
untrusted and unsupported signatures. With `require-signatures=true`, unsigned
incoming commits are rejected too. Verification covers newly introduced ancestry
before integration into the active branch. Check all participating operators'
public keys before enabling the strict policy, including keys that signed older
commits in incoming ancestry. Signing and acceptance policy are separate settings;
choose them together for the deployment.

For external fetch and synchronization, history already reachable from accepted
local branches before fetching remains accepted when the strict incoming policy
is enabled. Remote-tracking refs and temporary sync refs are not acceptance
boundaries. Fetch prunes deleted remote-tracking refs before
validation. An existing unsigned local synchronization base remains usable;
invalid or untrusted signatures on that base are still rejected.

Normal external Git fetch/push transports unchanged Git objects and therefore
preserves their signatures. Repository-aware external sync accepts an optional
`repositoryId` on fetch, push, full-sync, status and configure operations; omission
retains primary-repository behavior. Selected repositories keep their own remote
configuration and authorization. Credentials can be supplied through
`taxonomy.dsl.git.remotes.<repositoryId>.username` and `.token`. The legacy
`TAXONOMY_EXTERNAL_GIT_USERNAME` / `TAXONOMY_EXTERNAL_GIT_TOKEN` fallback applies
only to the primary repository. These transport credentials are independent of
OpenPGP signing keys.

Semantic publication between repositories remains a snapshot operation. Publish,
fork and other semantic transfers create a **new target commit** with source
evidence in `.taxonomy-provenance.json`; a target signature authenticates that new
object. It does not make a source signature a signature over the target object.
Source evidence carries bounded original commit and root-tree bytes. Verification
checks the original commit ID, its signature, its tree hash and the DSL blob ID
in that tree. Repository, branch and integration-base labels are attestations by
the target service; the original signature alone does not prove those locations
or their ancestry. This is evidence for one transfer, not a recursive copy of all
earlier evidence and not proof that merged content equals the source. A different
target commit ID is expected. Existing commits are never
automatically rewritten or retroactively signed.

When branches in the same Git history are merged, transfer evidence remains in
the original parent commits. The new signed merge references both parents and
contains no transfer evidence of its own. The reserved evidence file is excluded
from textual merging; conflicts in other files retain their normal behavior.

Cherry-picks and reverts also exclude the reserved file from content merging, but
their new single-parent commits carry fresh source evidence. Cherry-picks record
the selected source commit; reverts verify and record the parent snapshot used by
the reverse merge and identify the reverted commit as the base. Other files and genuine model
conflicts retain their normal merge behavior.

## Reading the history

The Versions history reports verification of each stored commit and expands to
show the signer, key fingerprint and verification details. The Git author remains
visible independently.

| Status | Interpretation |
|---|---|
| Unsigned | No signature; this includes legacy records without signature metadata |
| Signature verified | Cryptographically valid and accepted by the configured public key trust store |
| Signing key not trusted | A signature is present, but its key is not accepted by the current trust store |
| Invalid signature | Signature verification failed |
| Signature unsupported | The signature format or algorithm cannot be verified by this installation |

Only verified signatures receive the green status. A verified Taxonomy service
signature is labelled “by Taxonomy on behalf of the user”; external signatures use
the generic “External signature” label. Verification status depends on the current
trust configuration; it is not proof of a person's private key custody or approval
of the model's contents.

## Key rotation

1. Provision and validate the new key through the deployment's secret process.
2. Add its public key to each receiving installation before switching writers.
3. Switch the runtime secret key path/passphrase and restart the application to
   load the new configuration. Check a newly created commit's fingerprint in
   history and test incoming synchronization under the configured policy.
4. Retain the old public key while its signatures remain trusted and historical
   verification or incoming ancestry requires it. Remove or revoke compromised
   keys according to your incident procedure; their commits must no longer be
   treated as trusted. Keep historical private keys only under your separate
   secret retention policy.

Rotation does not rewrite history. Re-evaluate the incoming policy and trust set
after recovery or a compromise before resuming integration.
