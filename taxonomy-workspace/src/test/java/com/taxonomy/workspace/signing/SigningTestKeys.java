package com.taxonomy.workspace.signing;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.operator.jcajce.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Test-runtime keys only: never checks a private key fixture into the source repository. */
public final class SigningTestKeys {
    private static final String PASSPHRASE = "test-only";
    private SigningTestKeys() { }

    private static final class DefaultKeys {
        static final PGPKeyRingGenerator RING = generate();
        private static PGPKeyRingGenerator generate() {
            try { return generateKeys("Test service <signing-service@example.test>"); }
            catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
        }
    }

    /** A ready-to-use local service signer, sharing one generated test key per JVM. */
    public static GitCommitSignatureService createService(Path directory) throws Exception {
        return new GitCommitSignatureService(signingProperties(directory));
    }

    /** A separate runtime identity for cross-operator trust and rejection tests. */
    public static GitCommitSignatureService createIndependentService(Path directory, String userId) throws Exception {
        return new GitCommitSignatureService(properties(directory, generateKeys(userId), true));
    }

    public static GitSigningProperties signingProperties(Path directory) throws Exception {
        return properties(directory, DefaultKeys.RING, true);
    }

    /** Trusts the same runtime key as createService, without enabling signing. */
    public static GitSigningProperties verifierProperties(Path directory) throws Exception {
        Files.createDirectories(directory);
        var file = directory.resolve("trusted-test-service.asc");
        Files.write(file, armor(DefaultKeys.RING.generatePublicKeyRing().getEncoded()));
        var properties = new GitSigningProperties();
        properties.setTrustedPublicKeyFiles(List.of(file.toString()));
        return properties;
    }

    static GitSigningProperties properties(Path directory, PGPKeyRingGenerator generator, boolean enabled) throws Exception {
        Files.createDirectories(directory);
        var properties = new GitSigningProperties(); properties.setEnabled(enabled);
        var secret = directory.resolve("secret-" + generator.generatePublicKeyRing().getPublicKey().getKeyID() + ".asc");
        Files.write(secret, armor(generator.generateSecretKeyRing().getEncoded()));
        properties.setSecretKeyFile(secret.toString()); properties.setPassphrase(PASSPHRASE);
        return properties;
    }

    static PGPKeyRingGenerator generateKeys(String userId) throws Exception { return generateKeys(userId, null); }

    static PGPKeyRingGenerator generateKeys(String userId, PGPSignatureSubpacketVector packets) throws Exception {
        var provider = new BouncyCastleProvider();
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, generator.generateKeyPair(),
                Date.from(Instant.now().minusSeconds(60)));
        var digests = new JcaPGPDigestCalculatorProviderBuilder().setProvider(provider).build();
        return new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, pair, userId,
                digests.get(HashAlgorithmTags.SHA1), packets, null,
                new JcaPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(provider),
                new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256,
                        digests.get(HashAlgorithmTags.SHA256)).setProvider(provider).build(PASSPHRASE.toCharArray()));
    }

    static byte[] armor(byte[] bytes) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var output = new ArmoredOutputStream(buffer)) { output.write(bytes); }
        return buffer.toByteArray();
    }
}
