# ADR: streaming protection for portable backup archives

Status: accepted for the P04 archive implementation in issue #1146. Public jobs,
key-provider composition and restore activation are separate parts of the same
implementation; this ADR does not declare the complete product released.

## Decision

Use Apache Commons Compress 1.28.0 for the ZIP64 container and Google Tink Java
1.23.0 Streaming AEAD for encrypted artifacts. The initial key template exercised
by the implementation is `AES256_GCM_HKDF_1MB`. Tink owns nonce generation, key
derivation, authenticated segment boundaries and ciphertext encoding. No custom
cipher, password derivation or hand-built AEAD chunking is introduced.

Tink's seekable decrypting channel permits ZIP directory access directly over the
encrypted file. Verification never writes a decrypted whole archive to disk.
The channel must authenticate its first read before reporting plaintext size.
Both the container and each captured file, including its manifest and capture
proof, are encrypted when a protection provider is selected. An unencrypted
provider cannot capture, write or verify a secret-bearing package. A protected
payload is also rejected unless the request explicitly selected secrets.

Associated data separates archive encryption (`taxonomy.taxbackup.archive.v1`)
from spool encryption and binds each spool file to its backup UUID and canonical
entry path. A protected entry copied to another capture/path does not decrypt.
This contextual binding uses Tink's standard associated-data API.

## Keys and authority

The adapter receives a `KeysetHandle` from trusted application/operator
composition. No key, password, provider credential or keyset is taken from an
archive, included in its manifest, written into its spool or returned by an API.
Deployment key management must keep the keyset outside backup/download storage,
with separate retention and recovery access. An unavailable key means encrypted
archives cannot be restored; an archive cannot supply its own replacement key.

Production composition should use a secret store/KMS or an independently
protected deployment secret. If an operator explicitly provisions a keyset file,
it must be separately access-controlled and backed up outside the archive, never
passed as a URL/query value or printed. Rotation retains the old decryption keys
until all dependent archives expire; new encryption uses the primary key. The
adapter does not generate an ephemeral production key at startup.

SHA-256 detects content mismatch and binds a restore plan to its input; it does
not establish a trusted sender. Possession of an encryption key likewise does
not grant target ownership, account roles or permission to activate a restore.
The stable-principal, mapping and current authorization checks remain separate.
Optional sender signatures are not claimed by this adapter.

## Resource and parser boundary

The writer streams from captured files through a 64 KiB application buffer and
Tink's bounded segment buffers. ZIP64 entries are stored without compression;
this keeps output size and CPU usage predictable, including highly compressible
inputs. Length and CRC are computed before writing an entry; SHA-256 remains the
manifest integrity value. Source Git history is never rewritten by packaging.

The reader bounds the ZIP/ZIP64 central-directory count and field lengths before
Commons Compress allocates its index. Defaults are 12 GiB for the container,
10 GiB expanded data, 2 GiB per payload, 10,000 payload entries, a compression
ratio of 100, a 4 MiB manifest and a 30 minute processing deadline. Stored and
DEFLATE entries are supported; split archives, comments, undeclared extra
metadata, overlapping/hidden payload regions, directory entries, symlinks,
unportable paths and normalized collisions are rejected. All declared entries
must exist, with the exact size and SHA-256; unknown component versions fail.

Digest calculation and ZIP reads use one open file descriptor, so replacing an
upload pathname cannot bind one file's digest to another file's contents. A
second digest detects changes during verification. Consumers receive verified
entry streams, not an extraction directory or a supposedly verified mutable
pathname. Uploads and job artifacts must still remain in private storage owned
by the application, with no external writer.

Publication occurs only after the complete artifact has been closed, flushed
and verified. The worker's callback checks cancellation/current ownership before
publication; durable READY state is the job layer's additional boundary. Failed
or cancelled writes remove partial output. Cancellation preserves interruption
and does not drain the remainder of a captured multi-gigabyte entry on close.

## Alternatives and evidence

A single in-memory AEAD operation or a whole-file decrypt-to-temp step violates
the bounded-memory or no-plaintext-secret-spool requirement. Custom per-chunk
cryptography would add nonce, ordering and truncation risks. Password ZIP
extensions do not provide the selected standard streaming protection contract.

`BackupArchiveValidationTest` includes encrypted spool/archive roundtrips,
wrong-key/corruption/truncation failures, parser and digest checks, path
replacement, cancellation and an isolated JVM probe exporting and reading
192 MiB with a 96 MiB maximum heap. This is a bounded-memory regression, not the
P12 workload/throughput benchmark or a claim of completed Windows acceptance.
The implementation uses Java channels and argument files rather than native
cryptographic executables or platform-specific shell decryption.

References:

- [Tink Streaming AEAD](https://developers.google.com/tink/streaming-aead)
- [Tink large-file APIs](https://developers.google.com/tink/encrypt-large-files-or-data-streams)
- [Tink Java 1.23.0](https://github.com/tink-crypto/tink-java/releases/tag/v1.23.0)
- [Commons Compress ZipFile](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipFile.html)
