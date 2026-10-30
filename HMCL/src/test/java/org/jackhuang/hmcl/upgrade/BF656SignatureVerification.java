/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.upgrade;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Verifies signed release files using production classes and the trust root loaded from the actual release JAR.
/// Private production keys are never available to this process; foreign test keys remain only in memory.
@NotNullByDefault
public final class BF656SignatureVerification {
    /// Archive entry containing the launcher signature.
    private static final String SIGNATURE_ENTRY = "META-INF/hmcl_signature";
    /// Archive entry containing the launcher's embedded trust root.
    private static final String PUBLIC_KEY_ENTRY = "assets/hmcl_signature_publickey.der";

    /// Prevents construction of this standalone verification command.
    private BF656SignatureVerification() {
    }

    /// Checks JAR/EXE/SH files, self-verification, source-key consistency, and realistic rejected update packages.
    public static void main(String @Unmodifiable [] args) throws Exception {
        require(args.length == 5, "Expected JAR, EXE, SH, source public key, and report directory");
        Path jar = Path.of(args[0]).toRealPath();
        Path exe = Path.of(args[1]).toRealPath();
        Path shell = Path.of(args[2]).toRealPath();
        Path publicSource = Path.of(args[3]).toRealPath();
        Path reportDirectory = Files.createDirectories(Path.of(args[4])).toRealPath();
        Path runtimeJar = Objects.requireNonNull(JarUtils.thisJarPath(), "Verifier must load production classes from the signed JAR");
        require(runtimeJar.toRealPath().equals(jar), "Production classes were not loaded from the signed release JAR");
        require(!IntegrityChecker.DISABLE_SELF_INTEGRITY_CHECK, "Signature verification must not be disabled");

        byte @Unmodifiable [] embeddedPublicKey;
        try (InputStream input = Objects.requireNonNull(IntegrityChecker.class.getResourceAsStream("/" + PUBLIC_KEY_ENTRY))) {
            embeddedPublicKey = input.readAllBytes();
        }
        require(Arrays.equals(embeddedPublicKey, Files.readAllBytes(publicSource)), "Embedded public key does not match the source resource");
        require(IntegrityChecker.isSelfVerified(), "Signed launcher failed its own production self-verification");

        @Unmodifiable List<Path> artifacts = List.of(jar, exe, shell);
        JsonObject report = new JsonObject();
        report.addProperty("verifiedUtc", Instant.now().toString());
        report.addProperty("bf656Version", BF656Metadata.VERSION);
        report.addProperty("publicKeySha256", sha256(embeddedPublicKey));
        report.addProperty("selfVerification", "passed");
        for (Path artifact : artifacts) {
            IntegrityChecker.verifyJar(artifact);
            report.addProperty(artifact.getFileName().toString() + ".sha256", sha256(Files.readAllBytes(artifact)));
        }

        verifyRejectedCandidates(jar, reportDirectory);
        report.addProperty("jarExeShVerification", "passed");
        report.addProperty("tamperedPackageRejected", true);
        report.addProperty("unsignedPackageRejected", true);
        report.addProperty("foreignKeyPackageRejected", true);
        Files.writeString(reportDirectory.resolve("verification.json"), report + "\n", UTF_8);
        System.out.println("BF656 signatures verified: self, source public key, JAR, EXE, SH; tampered, unsigned, and foreign-key updates rejected.");
        System.out.println("Public key SHA-256: " + sha256(embeddedPublicKey));
    }

    /// Creates invalid update candidates only in a generated local directory, then removes the known files.
    private static void verifyRejectedCandidates(Path jar, Path reportDirectory) throws Exception {
        Path samples = Files.createTempDirectory(reportDirectory, "signature-cases-").toRealPath();
        require(samples.getParent().equals(reportDirectory), "Sample directory must remain within the verification report directory");
        Path tampered = samples.resolve("tampered.jar");
        Path unsigned = samples.resolve("unsigned.jar");
        Path foreign = samples.resolve("foreign-key.jar");
        try {
            Files.copy(jar, tampered);
            try (var archive = FileSystems.newFileSystem(tampered)) {
                Files.writeString(archive.getPath("assets/bf656.properties"), "\n# altered update candidate\n", UTF_8,
                        StandardOpenOption.APPEND);
            }
            expectRejected(tampered, "Tampered update was accepted");

            Files.copy(jar, unsigned);
            try (var archive = FileSystems.newFileSystem(unsigned)) {
                Files.delete(archive.getPath(SIGNATURE_ENTRY));
            }
            expectRejected(unsigned, "Unsigned update was accepted");

            // This candidate is correctly signed by its own key, but must fail against the running launcher's key.
            Files.copy(jar, foreign);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            KeyPair foreignKeys = generator.generateKeyPair();
            try (var archive = FileSystems.newFileSystem(foreign)) {
                Files.write(archive.getPath(PUBLIC_KEY_ENTRY), foreignKeys.getPublic().getEncoded());
                Files.delete(archive.getPath(SIGNATURE_ENTRY));
            }
            signForeignCandidate(foreign, foreignKeys);
            expectRejected(foreign, "Foreign-key update was accepted using its embedded key instead of the trusted key");
        } finally {
            // Delete only these generated files; never recursively delete a computed Windows path.
            Files.deleteIfExists(tampered);
            Files.deleteIfExists(unsigned);
            Files.deleteIfExists(foreign);
            Files.delete(samples);
        }
    }

    /// Confirms an invalid candidate is rejected by the unchanged production verifier.
    private static void expectRejected(Path candidate, String failureMessage) throws IOException {
        try {
            IntegrityChecker.verifyJar(candidate);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(failureMessage);
    }

    /// Builds a valid foreign-signed fixture so rejection tests exercise the trust boundary, not broken ZIP data.
    private static void signForeignCandidate(Path candidate, KeyPair keys) throws Exception {
        byte @Unmodifiable [] payload = signaturePayload(candidate);
        Signature signer = Signature.getInstance("SHA512withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(payload);
        byte @Unmodifiable [] signature = signer.sign();
        Signature fixtureVerifier = Signature.getInstance("SHA512withRSA");
        fixtureVerifier.initVerify(keys.getPublic());
        fixtureVerifier.update(payload);
        require(fixtureVerifier.verify(signature), "Foreign-key fixture must be valid under its own public key");
        try (var archive = FileSystems.newFileSystem(candidate)) {
            Files.write(archive.getPath(SIGNATURE_ENTRY), signature);
        }
    }

    /// Encodes the existing HMCL signature format solely to construct a correctly signed adversarial fixture.
    private static byte @Unmodifiable [] signaturePayload(Path jar) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            @Unmodifiable List<? extends ZipEntry> entries = archive.stream().filter(entry -> !SIGNATURE_ENTRY.equals(entry.getName()))
                    .sorted(Comparator.comparing(entry -> entry.getName())).toList();
            for (var entry : entries) {
                payload.write(digest.digest(entry.getName().getBytes(UTF_8)));
                try (InputStream input = Objects.requireNonNull(archive.getInputStream(entry))) {
                    payload.write(digest.digest(input.readAllBytes()));
                }
            }
        }
        return payload.toByteArray();
    }

    /// Returns a public fingerprint or artifact checksum without exposing private signing material.
    private static String sha256(byte @Unmodifiable [] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /// Terminates verification with a nonzero process exit when a release invariant is violated.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
