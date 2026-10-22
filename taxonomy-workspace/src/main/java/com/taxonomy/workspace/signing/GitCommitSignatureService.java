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
    private final TrustedKey signingIdentity;
    private final Set<String> revokedFingerprints = new HashSet<>();
    private final Map<Long, List<TrustedKey>> trustedKeys = new HashMap<>();

    private record SignatureLifetime(Instant created, Instant expires) {
        boolean contains(Instant now) {
            return !created.isAfter(now) && (expires == null || expires.isAfter(now));
        }
    }
    private record CertificateMetadata(SignatureLifetime lifetime, long validSeconds, boolean canSign,
                                       List<SignatureLifetime> backsignatures) { }
    private record CertificatePolicy(boolean primaryKey, long legacyValidity, boolean supportsSigning,
                                     List<CertificateMetadata> metadata, boolean revoked) { }
    private record EffectivePolicy(long validSeconds, boolean canSign, boolean valid) { }
    private record TrustedKey(PGPPublicKey key, PGPPublicKey primary, String signer,
                              CertificatePolicy keyPolicy, CertificatePolicy primaryPolicy) { }
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
        TrustedKey loadedIdentity = null;
        try {
            var certificates = new LinkedHashMap<String, Map<String, PGPPublicKey>>();
            PGPSecretKeyRingCollection secretRings = null;
            if (enabled) {
                if (properties.getSecretKeyFile() == null || properties.getSecretKeyFile().isBlank()) {
                    throw new IOException("Git commit signing is enabled but no secret key file is configured");
                }
                secretRings = new PGPSecretKeyRingCollection(
                        PGPUtil.getDecoderStream(new ByteArrayInputStream(readKeyFile(Path.of(properties.getSecretKeyFile())))),
                        new JcaKeyFingerprintCalculator());
                for (var rings = secretRings.getKeyRings(); rings.hasNext();) {
                    var ring = rings.next();
                    var publicKeys = new ArrayList<PGPPublicKey>();
                    ring.getPublicKeys().forEachRemaining(publicKeys::add);
                    addTrustedRing(certificates, new PGPPublicKeyRing(publicKeys));
                }
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
                for (var iterator = rings.getKeyRings(); iterator.hasNext();) addTrustedRing(certificates, iterator.next());
            }
            loadTrustedCertificates(certificates);
            // Select private material only after public updates and the auto-trusted
            // secret certificates have been reconciled into the same identity policy.
            if (enabled) {
                var now = clock.instant();
                for (var rings = secretRings.getKeyRings(); rings.hasNext();) {
                    var ring = rings.next();
                    for (var keys = ring.getSecretKeys(); keys.hasNext();) {
                        var candidate = keys.next();
                        if (candidate.isPrivateKeyEmpty() || !candidate.isSigningKey() || loadedPrivate != null) continue;
                        var identity = trustedKeys.getOrDefault(candidate.getKeyID(), List.of()).stream()
                                .filter(trusted -> Arrays.equals(trusted.key().getFingerprint(), candidate.getPublicKey().getFingerprint())
                                        && Arrays.equals(trusted.primary().getFingerprint(), ring.getPublicKey().getFingerprint()))
                                .findFirst().orElse(null);
                        if (identity == null) continue;
                        var policy = effectivePolicy(identity.keyPolicy(), now);
                        if (!usable(identity, policy, now) || !policy.canSign()) continue;
                        char[] passphrase = Objects.toString(properties.getPassphrase(), "").toCharArray();
                        try {
                            loadedPrivate = candidate.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder()
                                    .setProvider(BC).build(passphrase));
                            loadedIdentity = identity;
                        } finally { Arrays.fill(passphrase, '\0'); }
                    }
                }
                if (loadedPrivate == null) throw new IOException("Git commit signing requires a usable signing key");
            }
        } catch (PGPException | IOException | RuntimeException e) {
            // Do not include parser/decryptor messages or causes: these may expose secret input.
            throw new IOException("Cannot initialize Git commit signing or public key trust configuration");
        }
        privateKey = loadedPrivate;
        signingIdentity = loadedIdentity;
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
        var now = clock.instant();
        var policy = effectivePolicy(signingIdentity.keyPolicy(), now);
        if (!usable(signingIdentity, policy, now) || !policy.canSign()) {
            throw new IOException("Git signing key or primary key is expired or revoked");
        }
        if (commit.getGpgSignature() != null) throw new IOException("Commit already has a signature");
        try {
            var signingKey = signingIdentity.key();
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
        var now = clock.instant();
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
            if (expiration > 0 && !signature.getCreationTime().toInstant().plusSeconds(expiration).isAfter(now)) {
                return status(SignatureStatus.INVALID, "OpenPGP signature has expired");
            }
            var candidates = trustedKeys.get(signature.getKeyID());
            if (candidates == null || candidates.isEmpty()) return status(SignatureStatus.UNTRUSTED, "Signing key is not trusted");
            for (var trusted : candidates) {
                var policy = effectivePolicy(trusted.keyPolicy(), now);
                if (!usable(trusted, policy, now)) continue;
                signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(BC), trusted.key());
                signature.update(parsed.payload());
                if (signature.verify()) {
                    if (!policy.canSign()) {
                        return status(SignatureStatus.UNSUPPORTED, "OpenPGP key is not permitted to sign commits");
                    }
                    boolean service = signingIdentity != null
                            && Arrays.equals(signingIdentity.key().getFingerprint(), trusted.key().getFingerprint())
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

    private static void addTrustedRing(Map<String, Map<String, PGPPublicKey>> certificates,
                                       PGPPublicKeyRing ring) throws PGPException {
        var certificate = certificates.computeIfAbsent(fingerprint(ring.getPublicKey()), ignored -> new LinkedHashMap<>());
        for (var keys = ring.getPublicKeys(); keys.hasNext();) {
            var key = keys.next();
            var identity = fingerprint(key);
            var previous = certificate.get(identity);
            certificate.put(identity, previous == null ? key : PGPPublicKey.join(previous, key, false, false));
        }
    }

    private void loadTrustedCertificates(Map<String, Map<String, PGPPublicKey>> certificates) {
        for (var entry : certificates.entrySet()) {
            var primary = entry.getValue().get(entry.getKey());
            var primaryPolicy = certificatePolicy(primary, primary);
            if (primaryPolicy.revoked()) revokedFingerprints.add(fingerprint(primary));
            String signer = primary.getUserIDs().hasNext() ? primary.getUserIDs().next() : "OpenPGP signing key";
            for (var key : entry.getValue().values()) {
                var policy = key == primary ? primaryPolicy : certificatePolicy(key, primary);
                if (policy.revoked()) revokedFingerprints.add(fingerprint(key));
                trustedKeys.computeIfAbsent(key.getKeyID(), ignored -> new ArrayList<>())
                        .add(new TrustedKey(key, primary, boundedMetadata(signer), policy, primaryPolicy));
            }
        }
    }

    private static byte[] readKeyFile(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var bytes = input.readNBytes(MAX_KEY_FILE_BYTES + 1);
            if (bytes.length > MAX_KEY_FILE_BYTES) throw new IOException("Git key file exceeds size limit");
            return bytes;
        }
    }

    private boolean usable(TrustedKey identity, EffectivePolicy policy, Instant now) {
        return usable(identity.key(), identity.keyPolicy(), policy, now)
                && usable(identity.primary(), identity.primaryPolicy(), effectivePolicy(identity.primaryPolicy(), now), now);
    }

    private boolean usable(PGPPublicKey key, CertificatePolicy certificate, EffectivePolicy policy, Instant now) {
        if (!policy.valid() || certificate.revoked() || revokedFingerprints.contains(fingerprint(key))) return false;
        var created = key.getCreationTime().toInstant();
        return !created.isAfter(now) && (policy.validSeconds() == 0
                || created.plusSeconds(policy.validSeconds()).isAfter(now));
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

    /** Metadata accessors alone do not authenticate self-certifications or subkey bindings. */
    private static CertificatePolicy certificatePolicy(PGPPublicKey key, PGPPublicKey primary) {
        boolean primaryKey = Arrays.equals(key.getFingerprint(), primary.getFingerprint());
        int metadataType = primaryKey ? PGPSignature.DIRECT_KEY : PGPSignature.SUBKEY_BINDING;
        int revocationType = primaryKey ? PGPSignature.KEY_REVOCATION : PGPSignature.SUBKEY_REVOCATION;
        boolean revoked = false;
        var metadata = new ArrayList<CertificateMetadata>();
        for (var signatures = key.getKeySignatures(); signatures.hasNext();) {
            var signature = signatures.next();
            int type = signature.getSignatureType();
            if (type != metadataType && type != revocationType) continue;
            if (!authenticated(signature, primary, sig -> primaryKey
                    ? sig.verifyCertification(key) : sig.verifyCertification(primary, key))) continue;
            if (type == revocationType) revoked = true;
            else metadata.add(certificateMetadata(signature, key, primary, primaryKey));
        }
        if (primaryKey) {
            for (var ids = key.getRawUserIDs(); ids.hasNext();) {
                var id = ids.next();
                for (var signatures = key.getSignaturesForID(id); signatures != null && signatures.hasNext();) {
                    var signature = signatures.next();
                    if (signature.isCertification() && authenticated(signature, primary,
                            sig -> sig.verifyCertification(id, key))) {
                        metadata.add(certificateMetadata(signature, key, primary, true));
                    }
                }
            }
            for (var attributes = key.getUserAttributes(); attributes.hasNext();) {
                var attribute = attributes.next();
                for (var signatures = key.getSignaturesForUserAttribute(attribute); signatures != null && signatures.hasNext();) {
                    var signature = signatures.next();
                    if (signature.isCertification() && authenticated(signature, primary,
                            sig -> sig.verifyCertification(attribute, key))) {
                        metadata.add(certificateMetadata(signature, key, primary, true));
                    }
                }
            }
        }
        return new CertificatePolicy(primaryKey, key.getVersion() <= 3 ? key.getValidSeconds() : 0,
                supportedKey(key), List.copyOf(metadata), revoked);
    }

    private static CertificateMetadata certificateMetadata(PGPSignature signature, PGPPublicKey key,
                                                           PGPPublicKey primary, boolean primaryKey) {
        var packets = signature.getHashedSubPackets();
        long validity = key.getVersion() <= 3 ? key.getValidSeconds()
                : packets == null ? 0 : packets.getKeyExpirationTime();
        boolean canSign = supportedKey(key) && (packets == null
                || !packets.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)
                || (packets.getKeyFlags() & KeyFlags.SIGN_DATA) != 0);
        return new CertificateMetadata(signatureLifetime(signature), validity, canSign,
                primaryKey ? List.of() : authenticatedBacksignatures(signature, primary, key));
    }

    private static List<SignatureLifetime> authenticatedBacksignatures(PGPSignature binding,
                                                                      PGPPublicKey primary, PGPPublicKey subkey) {
        var lifetimes = new ArrayList<SignatureLifetime>();
        for (var packets : new PGPSignatureSubpacketVector[]{binding.getHashedSubPackets(), binding.getUnhashedSubPackets()}) {
            if (packets == null) continue;
            try {
                var signatures = packets.getEmbeddedSignatures();
                for (int i = 0; i < signatures.size(); i++) {
                    var signature = signatures.get(i);
                    if (signature.getSignatureType() == PGPSignature.PRIMARYKEY_BINDING
                            && authenticated(signature, subkey, sig -> sig.verifyCertification(primary, subkey))) {
                        lifetimes.add(signatureLifetime(signature));
                    }
                }
            } catch (PGPException | RuntimeException invalidBacksignature) {
                // An invalid embedded proof cannot discard the primary's authenticated
                // restrictions or restore an older, more permissive binding.
            }
        }
        return List.copyOf(lifetimes);
    }

    private static SignatureLifetime signatureLifetime(PGPSignature signature) {
        var created = signature.getCreationTime().toInstant();
        var packets = signature.getHashedSubPackets();
        long expiration = packets == null ? 0 : packets.getSignatureExpirationTime();
        return new SignatureLifetime(created, expiration == 0 ? null : created.plusSeconds(expiration));
    }

    /** Evaluate immutable, previously authenticated metadata without sharing mutable signature verifiers. */
    private static EffectivePolicy effectivePolicy(CertificatePolicy certificate, Instant now) {
        if (certificate.metadata().isEmpty()) {
            // Explicitly trusted bare primary keys retain their legacy algorithm policy;
            // a subkey must always carry an authenticated binding from its primary.
            return new EffectivePolicy(certificate.legacyValidity(), certificate.supportsSigning(), certificate.primaryKey());
        }
        Instant newest = null;
        long validity = certificate.legacyValidity();
        boolean canSign = certificate.supportsSigning();
        boolean backsignatureValid = false;
        for (var metadata : certificate.metadata()) {
            // A signature's own lifetime controls whether it participates in selection.
            // Its key-expiration restriction is enforced only after selecting the newest
            // live metadata, so an expired key never falls back to stale permissions.
            if (!metadata.lifetime().contains(now)) continue;
            var created = metadata.lifetime().created();
            if (newest != null && created.isBefore(newest)) continue;
            long candidateValidity = metadata.validSeconds();
            boolean candidateBacksignature = metadata.backsignatures().stream().anyMatch(lifetime -> lifetime.contains(now));
            if (newest != null && created.equals(newest)) {
                // Equal-time conflicting certificates have no authenticated order.
                // Choose their restrictive intersection rather than trust-file order.
                validity = validity == 0 ? candidateValidity : candidateValidity == 0 ? validity
                        : Math.min(validity, candidateValidity);
                canSign &= metadata.canSign();
                backsignatureValid &= candidateBacksignature;
            } else {
                newest = created;
                validity = candidateValidity;
                canSign = metadata.canSign();
                backsignatureValid = candidateBacksignature;
            }
        }
        // Select primary-authenticated policy before checking the subkey's proof.
        // Removing signing permission is effective even without a backsignature.
        boolean valid = newest != null && (certificate.primaryKey() || !canSign || backsignatureValid);
        return new EffectivePolicy(validity, canSign, valid);
    }

    @FunctionalInterface
    private interface CertificationVerifier {
        boolean verify(PGPSignature signature) throws PGPException;
    }

    private static boolean authenticated(PGPSignature signature, PGPPublicKey issuer, CertificationVerifier verifier) {
        try {
            // Issuer IDs are metadata hints; verification must use the actual key
            // authorized to certify this policy or prove the subkey binding.
            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(BC), issuer);
            return verifier.verify(signature);
        } catch (PGPException | RuntimeException invalidCertificate) {
            return false;
        }
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
