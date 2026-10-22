package com.taxonomy.workspace.signing;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SignatureSubpacketTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.GpgSignature;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Provider;
import java.time.Instant;
import java.time.Clock;
import java.util.*;

/**
 * Standard Git OpenPGP signing, with an explicit operator-controlled trust store.
 * The service signs on behalf of an actor: it never represents a personal user key.
 * Key loading is startup-only, bounded, and fails closed when configured incorrectly.
 */
public final class GitCommitSignatureService {
    private static final Provider BC = new BouncyCastleProvider();
    private static final int MAX_COMMIT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 64 * 1024;
    private static final int MAX_KEY_FILE_BYTES = 1024 * 1024;
    private static final int MAX_TRUST_FILES = 100;
    private static final String SERVICE_NOTATION = "service@taxonomy.local";
    private final Clock clock;
    private final boolean enabled;
    private final boolean requireSignatures;
    private final PGPPrivateKey privateKey;
    private final PGPPublicKey signingKey;
    private final PGPPublicKey signingPrimaryKey;
    // Full fingerprints prevent stale duplicate ring copies from bypassing revocation.
    private final Set<String> revokedFingerprints = new HashSet<>();
    private final Map<Long, List<TrustedKey>> trustedKeys = new HashMap<>();

    private record TrustedKey(PGPPublicKey key, PGPPublicKey primary, String signer) { }
    private record ParsedCommit(byte[] payload, byte[] signature, SignatureStatus error) { }

    /** Construct an isolated verifier/signer. No process-global provider or GPG home changes. */
    public GitCommitSignatureService(GitSigningProperties properties) throws IOException {
        this(properties, Clock.systemUTC());
    }

    GitCommitSignatureService(GitSigningProperties properties, Clock clock) throws IOException {
        this.clock = Objects.requireNonNull(clock, "clock");
        enabled = properties.isEnabled();
        requireSignatures = properties.isRequireSignatures();
        PGPPrivateKey loadedPrivate = null;
        PGPPublicKey loadedPublic = null;
        PGPPublicKey loadedPrimary = null;
        try {
            if (enabled) {
                if (properties.getSecretKeyFile() == null || properties.getSecretKeyFile().isBlank()) {
                    throw new IOException("Git commit signing is enabled but no secret key file is configured");
                }
                var collection = new PGPSecretKeyRingCollection(
                        PGPUtil.getDecoderStream(new ByteArrayInputStream(readKeyFile(Path.of(properties.getSecretKeyFile())))),
                        new JcaKeyFingerprintCalculator());
                for (var rings = collection.getKeyRings(); rings.hasNext();) {
                    var ring = rings.next();
                    var primary = ring.getPublicKey();
                    for (var keys = ring.getSecretKeys(); keys.hasNext();) {
                        var candidate = keys.next();
                        if (candidate.isPrivateKeyEmpty() || !candidate.isSigningKey()
                                || !permittedSigningKey(candidate.getPublicKey(), primary)
                                || !usable(candidate.getPublicKey()) || !usable(primary)) continue;
                        if (loadedPrivate != null) continue; // deterministic first usable signing key
                        char[] passphrase = Objects.toString(properties.getPassphrase(), "").toCharArray();
                        try {
                            loadedPrivate = candidate.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder()
                                    .setProvider(BC).build(passphrase));
                            loadedPublic = candidate.getPublicKey();
                            loadedPrimary = primary;
                        } finally { Arrays.fill(passphrase, '\0'); }
                    }
                    var publicKeys = new ArrayList<PGPPublicKey>();
                    ring.getPublicKeys().forEachRemaining(publicKeys::add);
                    addTrustedRing(new PGPPublicKeyRing(publicKeys));
                }
                if (loadedPrivate == null) throw new IOException("Git commit signing requires a usable signing key");
            }
            var publicFiles = new ArrayList<Path>();
            if (properties.getTrustedPublicKeyFiles() != null) {
                for (var file : properties.getTrustedPublicKeyFiles()) {
                    if (file != null && !file.isBlank()) publicFiles.add(Path.of(file));
                }
            }
            if (properties.getTrustedPublicKeyDirectory() != null && !properties.getTrustedPublicKeyDirectory().isBlank()) {
                try (var files = Files.list(Path.of(properties.getTrustedPublicKeyDirectory()))) {
                    publicFiles.addAll(files.filter(Files::isRegularFile).sorted().limit(MAX_TRUST_FILES + 1L).toList());
                }
            }
            if (publicFiles.size() > MAX_TRUST_FILES) throw new IOException("Too many Git trusted public key files");
            for (var file : publicFiles) {
                var rings = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(
                        new ByteArrayInputStream(readKeyFile(file))), new JcaKeyFingerprintCalculator());
                if (rings.size() == 0) throw new IOException("Git trust file contains no public keys");
                for (var iterator = rings.getKeyRings(); iterator.hasNext();) addTrustedRing(iterator.next());
            }
            // Public revocations also override the earlier auto-trusted secret-key ring.
            if (enabled && (!usable(loadedPublic) || !usable(loadedPrimary))) {
                throw new IOException("Git signing identity is expired or revoked");
            }
        } catch (PGPException | IOException | RuntimeException e) {
            // Do not include parser/decryptor messages or causes: these may expose secret input.
            throw new IOException("Cannot initialize Git commit signing or public key trust configuration");
        }
        privateKey = loadedPrivate;
        signingKey = loadedPublic;
        signingPrimaryKey = loadedPrimary;
    }

    /** Legacy repositories still inspect signatures and reject signed objects without a trusted key. */
    public static GitCommitSignatureService disabled() {
        try { return new GitCommitSignatureService(new GitSigningProperties()); }
        catch (IOException impossible) { throw new IllegalStateException("Cannot create unsigned verifier"); }
    }

    public boolean isSigningEnabled() { return enabled; }

    /** Sign the exact object bytes before insertion. An IOException must prevent the ref update. */
    public void sign(CommitBuilder commit, String repositoryName, String branch) throws IOException {
        if (!enabled) return;
        if (!usable(signingKey) || !usable(signingPrimaryKey)) {
            throw new IOException("Git signing key or primary key is expired or revoked");
        }
        if (commit.getGpgSignature() != null) throw new IOException("Commit already has a signature");
        try {
            var generator = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(
                    signingKey.getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(BC), signingKey);
            generator.init(PGPSignature.BINARY_DOCUMENT, privateKey);
            var packets = new PGPSignatureSubpacketGenerator();
            packets.setIssuerFingerprint(false, signingKey);
            // Preserve the durable commit timestamp on retries; algorithm-dependent signature
            // randomness still means callers must not rely on identical signed object IDs.
            // A rotated key cannot sign before it existed. This timestamp is not trusted
            // wall-clock evidence; it is commit metadata covered by the signature.
            var commitTime = commit.getCommitter().getWhenAsInstant();
            var keyTime = signingKey.getCreationTime().toInstant();
            packets.setSignatureCreationTime(false, Date.from(commitTime.isBefore(keyTime) ? keyTime : commitTime));
            packets.addNotationData(false, true, SERVICE_NOTATION, "v1");
            packets.addNotationData(false, true, "actor@taxonomy.local", actor());
            packets.addNotationData(false, true, "repository@taxonomy.local", boundedMetadata(repositoryName));
            packets.addNotationData(false, true, "branch@taxonomy.local", boundedMetadata(branch));
            generator.setHashedSubpackets(packets.generate());
            var payload = commit.build();
            if (payload.length > MAX_COMMIT_BYTES) throw new IOException("Git commit exceeds signing size limit");
            generator.update(payload);
            var output = new ByteArrayOutputStream();
            try (var armor = new ArmoredOutputStream(output)) { generator.generate().encode(armor); }
            commit.setGpgSignature(new GpgSignature(output.toByteArray()));
        } catch (PGPException | RuntimeException e) {
            throw new IOException("Cannot sign Git commit");
        }
    }

    public CommitSignature verify(byte[] rawCommit) throws IOException {
        var parsed = parse(rawCommit);
        if (parsed.error() != null) return status(parsed.error(), "Malformed or unsupported Git commit signature");
        if (parsed.signature() == null) return CommitSignature.unsigned();
        var armorText = new String(parsed.signature(), StandardCharsets.US_ASCII);
        if (!armorText.startsWith("-----BEGIN PGP SIGNATURE-----")) {
            return status(SignatureStatus.UNSUPPORTED, "Only OpenPGP commit signatures are supported");
        }
        try {
            var factory = new PGPObjectFactory(PGPUtil.getDecoderStream(new ByteArrayInputStream(parsed.signature())),
                    new JcaKeyFingerprintCalculator());
            var object = factory.nextObject();
            if (!(object instanceof PGPSignatureList signatures) || signatures.size() != 1 || factory.nextObject() != null) {
                return status(SignatureStatus.INVALID, "Expected one detached OpenPGP signature");
            }
            var signature = signatures.get(0);
            if (signature.getSignatureType() != PGPSignature.BINARY_DOCUMENT
                    || !supportedHash(signature.getHashAlgorithm())) {
                return status(SignatureStatus.UNSUPPORTED, "Unsupported OpenPGP signature type or digest");
            }
            if (hasUnsupportedCriticalPackets(signature)) {
                return status(SignatureStatus.UNSUPPORTED, "Unsupported critical OpenPGP signature extension");
            }
            var packets = signature.getHashedSubPackets();
            long expiration = packets == null ? 0 : packets.getSignatureExpirationTime();
            if (expiration > 0 && !signature.getCreationTime().toInstant().plusSeconds(expiration).isAfter(clock.instant())) {
                return status(SignatureStatus.INVALID, "OpenPGP signature has expired");
            }
            var candidates = trustedKeys.get(signature.getKeyID());
            if (candidates == null || candidates.isEmpty()) return status(SignatureStatus.UNTRUSTED, "Signing key is not trusted");
            for (var trusted : candidates) {
                if (!usable(trusted.key()) || !usable(trusted.primary())) continue;
                if (!permittedSigningKey(trusted.key(), trusted.primary())) {
                    return status(SignatureStatus.UNSUPPORTED, "OpenPGP key is not permitted to sign commits");
                }
                signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(BC), trusted.key());
                signature.update(parsed.payload());
                if (signature.verify()) {
                    boolean service = signingKey != null
                            && Arrays.equals(signingKey.getFingerprint(), trusted.key().getFingerprint())
                            && signature.getHashedSubPackets() != null
                            && Arrays.stream(signature.getHashedSubPackets().getNotationDataOccurrences(SERVICE_NOTATION))
                            .anyMatch(notation -> notation.isHumanReadable() && "v1".equals(notation.getNotationValue()));
                    return new CommitSignature(SignatureStatus.VERIFIED, trusted.signer(),
                            HexFormat.of().withUpperCase().formatHex(trusted.key().getFingerprint()), service,
                            service ? "Verified service signature on behalf of the recorded actor" : "Verified trusted OpenPGP signature");
                }
            }
            return status(SignatureStatus.INVALID, "Signature verification failed or signing key is expired/revoked");
        } catch (PGPException | IOException | RuntimeException e) {
            return status(SignatureStatus.INVALID, "Malformed or invalid OpenPGP signature");
        }
    }

    /** Import policy: only unsigned legacy history (when allowed) or verified trusted signatures. */
    public void requireAcceptable(byte[] rawCommit) throws IOException {
        var result = verify(rawCommit);
        if (result.status() == SignatureStatus.VERIFIED
                || (result.status() == SignatureStatus.UNSIGNED && !requireSignatures)) return;
        throw new IOException("Incoming Git commit rejected by signature policy: " + result.status());
    }

    private void addTrustedRing(PGPPublicKeyRing ring) {
        var primary = ring.getPublicKey();
        String signer = primary.getUserIDs().hasNext() ? primary.getUserIDs().next() : "OpenPGP signing key";
        signer = boundedMetadata(signer);
        for (var keys = ring.getPublicKeys(); keys.hasNext();) {
            var key = keys.next();
            if (key.hasRevocation()) revokedFingerprints.add(fingerprint(key));
            trustedKeys.computeIfAbsent(key.getKeyID(), ignored -> new ArrayList<>()).add(new TrustedKey(key, primary, signer));
        }
    }

    private static byte[] readKeyFile(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var bytes = input.readNBytes(MAX_KEY_FILE_BYTES + 1);
            if (bytes.length > MAX_KEY_FILE_BYTES) throw new IOException("Git key file exceeds size limit");
            return bytes;
        }
    }

    private boolean usable(PGPPublicKey key) {
        if (key == null || key.hasRevocation() || revokedFingerprints.contains(fingerprint(key))) return false;
        var now = clock.instant();
        var created = key.getCreationTime().toInstant();
        return !created.isAfter(now) && (key.getValidSeconds() == 0
                || created.plusSeconds(key.getValidSeconds()).isAfter(now));
    }

    private static String fingerprint(PGPPublicKey key) {
        return HexFormat.of().withUpperCase().formatHex(key.getFingerprint());
    }

    private static boolean supportedKey(PGPPublicKey key) {
        return switch (key.getAlgorithm()) {
            case PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_SIGN,
                 PublicKeyAlgorithmTags.DSA, PublicKeyAlgorithmTags.ECDSA, PublicKeyAlgorithmTags.EDDSA_LEGACY,
                 PublicKeyAlgorithmTags.Ed25519, PublicKeyAlgorithmTags.Ed448 -> true;
            default -> false;
        };
    }

    private static boolean hasUnsupportedCriticalPackets(PGPSignature signature) {
        return hasUnsupportedCriticalPackets(signature.getHashedSubPackets())
                || hasUnsupportedCriticalPackets(signature.getUnhashedSubPackets());
    }

    private static boolean hasUnsupportedCriticalPackets(PGPSignatureSubpacketVector packets) {
        if (packets == null) return false;
        for (int tag : packets.getCriticalTags()) {
            if (tag != SignatureSubpacketTags.CREATION_TIME && tag != SignatureSubpacketTags.EXPIRE_TIME
                    && tag != SignatureSubpacketTags.ISSUER_KEY_ID && tag != SignatureSubpacketTags.ISSUER_FINGERPRINT
                    && tag != SignatureSubpacketTags.NOTATION_DATA) return true;
        }
        var understood = Set.of(SERVICE_NOTATION, "actor@taxonomy.local", "repository@taxonomy.local", "branch@taxonomy.local");
        for (var notation : packets.getNotationDataOccurrences()) {
            if (notation.isCritical() && !understood.contains(notation.getNotationName())) return true;
        }
        return false;
    }

    /** Respect explicit operator-key usage flags; legacy keys without flags retain algorithm permissions. */
    private static boolean permittedSigningKey(PGPPublicKey key, PGPPublicKey primary) {
        if (!supportedKey(key)) return false;
        PGPSignature newest = null;
        for (var signatures = key.getSignatures(); signatures.hasNext();) {
            var signature = signatures.next();
            var packets = signature.getHashedSubPackets();
            if (signature.getKeyID() != primary.getKeyID() || packets == null
                    || !packets.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)) continue;
            int type = signature.getSignatureType();
            if (type != PGPSignature.SUBKEY_BINDING && type != PGPSignature.DIRECT_KEY
                    && type != PGPSignature.DEFAULT_CERTIFICATION && type != PGPSignature.NO_CERTIFICATION
                    && type != PGPSignature.CASUAL_CERTIFICATION && type != PGPSignature.POSITIVE_CERTIFICATION) continue;
            if (newest == null || signature.getCreationTime().after(newest.getCreationTime())) newest = signature;
        }
        return newest == null || (newest.getHashedSubPackets().getKeyFlags() & KeyFlags.SIGN_DATA) != 0;
    }

    private static boolean supportedHash(int algorithm) {
        return algorithm == HashAlgorithmTags.SHA256 || algorithm == HashAlgorithmTags.SHA384
                || algorithm == HashAlgorithmTags.SHA512 || algorithm == HashAlgorithmTags.SHA224;
    }

    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                ? boundedMetadata(authentication.getName()) : "system";
    }

    private static String boundedMetadata(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(value.length(), 512));
    }

    private static CommitSignature status(SignatureStatus status, String reason) {
        return new CommitSignature(status, null, null, false, reason);
    }

    /** Remove exactly the gpgsig header (and its single-space continuations), preserving all other bytes. */
    private static ParsedCommit parse(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_COMMIT_BYTES) return invalid();
        var payload = new ByteArrayOutputStream(raw.length);
        var signature = new ByteArrayOutputStream();
        boolean inSignature = false, found = false, tree = false, author = false, committer = false;
        int cursor = 0;
        while (cursor < raw.length) {
            int start = cursor;
            while (cursor < raw.length && raw[cursor] != '\n') cursor++;
            if (cursor == raw.length || cursor > 1024 * 1024) return invalid();
            int length = cursor - start;
            cursor++;
            if (length == 0) {
                if (!tree || !author || !committer) return invalid();
                payload.write(raw, start, raw.length - start);
                return new ParsedCommit(payload.toByteArray(), found ? signature.toByteArray() : null, null);
            }
            if (raw[start] == ' ' && inSignature) {
                signature.write(raw, start + 1, length - 1); signature.write('\n');
            } else {
                inSignature = false;
                var header = new String(raw, start, length, StandardCharsets.US_ASCII);
                if (header.equals("gpgsig") || header.startsWith("gpgsig\t")) return invalid();
                if (header.startsWith("gpgsig-sha256 ")) return new ParsedCommit(null, null, SignatureStatus.UNSUPPORTED);
                if (header.startsWith("gpgsig ")) {
                    if (found) return invalid();
                    found = true; inSignature = true;
                    signature.write(raw, start + 7, length - 7); signature.write('\n');
                } else {
                    tree |= header.startsWith("tree "); author |= header.startsWith("author "); committer |= header.startsWith("committer ");
                    payload.write(raw, start, length + 1);
                }
            }
            if (signature.size() > MAX_SIGNATURE_BYTES) return invalid();
        }
        return invalid();
    }
    private static ParsedCommit invalid() { return new ParsedCommit(null, null, SignatureStatus.INVALID); }
}
