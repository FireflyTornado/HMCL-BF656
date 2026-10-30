import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Properties

// Private key bytes are never registered as Gradle inputs or written to a build cache.
val signingKeyLocation = System.getenv("HMCL_SIGNATURE_KEY")?.trim()?.takeIf { it.isNotEmpty() }
val signingPublicKey = file("src/main/resources/assets/hmcl_signature_publickey.der")
val signingConfig = Properties().also { config ->
    rootProject.file("config/bf656.properties").reader(Charsets.UTF_8).use { config.load(it) }
}
val updateRequiresSignature = !signingConfig.getProperty("update.url", "").isBlank()

fun validateSigningKey(required: Boolean) {
    val location = signingKeyLocation
    if (location == null) {
        check(!required) { "BF656 signed release requires HMCL_SIGNATURE_KEY pointing to an external PKCS#8 DER private key" }
        return
    }
    val privateFile = rootProject.file(location).canonicalFile
    check(!privateFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        "BF656 update private keys must be stored outside the source repository"
    }
    check(privateFile.isFile) { "HMCL_SIGNATURE_KEY does not point to an existing private key file" }
    val factory = KeyFactory.getInstance("RSA")
    val keyBytes = privateFile.readBytes()
    val privateKey = try {
        factory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    } finally {
        keyBytes.fill(0)
    }
    val publicKey = factory.generatePublic(X509EncodedKeySpec(signingPublicKey.readBytes()))
    val challenge = "BF656 update signing key validation".toByteArray(Charsets.UTF_8)
    val signer = Signature.getInstance("SHA512withRSA")
    signer.initSign(privateKey)
    signer.update(challenge)
    val proof = signer.sign()
    val verifier = Signature.getInstance("SHA512withRSA")
    verifier.initVerify(publicKey)
    verifier.update(challenge)
    check(verifier.verify(proof)) { "HMCL_SIGNATURE_KEY does not match the bundled BF656 public key" }
}

val validateBF656SigningKey = tasks.register("validateBF656SigningKey") {
    group = "verification"
    description = "Validates any supplied BF656 signing key; enabled update sources require a key."
    doNotTrackState("Signing credentials must be checked each time without storing private key inputs")
    doLast { validateSigningKey(updateRequiresSignature) }
}

val requireBF656ReleaseSigningKey = tasks.register("requireBF656ReleaseSigningKey") {
    group = "verification"
    description = "Requires a private key matching the bundled BF656 public key before a release."
    doNotTrackState("Every signed release must validate the available key, including incremental builds")
    doLast { validateSigningKey(true) }
}

val signedLauncherJar = tasks.named<Jar>("shadowJar") {
    dependsOn(validateBF656SigningKey)
    mustRunAfter(requireBF656ReleaseSigningKey)
    inputs.property("bf656.signing.enabled", signingKeyLocation != null)
    // A new or changed local key must never silently reuse an artifact from an earlier build.
    outputs.upToDateWhen { signingKeyLocation == null }
    outputs.doNotCacheIf("Signed artifacts depend on a local private key") { signingKeyLocation != null }
}.flatMap { it.archiveFile }

val signingSourceSets = extensions.getByType<SourceSetContainer>()
val verifyBF656SignedArtifacts = tasks.register<JavaExec>("verifyBF656SignedArtifacts") {
    group = "verification"
    description = "Verifies actual JAR/EXE/SH signatures and rejects tampered, unsigned, and foreign-key packages."
    dependsOn(requireBF656ReleaseSigningKey, tasks.named("makeExecutables"), tasks.named("testClasses"))
    // Production classes and the trust root must come from the actual signed JAR, not build/classes.
    classpath = files(signingSourceSets.named("test").map { it.output }, signedLauncherJar)
    mainClass.set("org.jackhuang.hmcl.upgrade.BF656SignatureVerification")
    val reportDirectory = layout.buildDirectory.dir("reports/bf656-signing")
    workingDir(reportDirectory)
    doFirst {
        reportDirectory.get().asFile.mkdirs()
        val jar = signedLauncherJar.get().asFile
        setArgs(listOf(jar.absolutePath,
            jar.resolveSibling(jar.nameWithoutExtension + ".exe").absolutePath,
            jar.resolveSibling(jar.nameWithoutExtension + ".sh").absolutePath,
            signingPublicKey.absolutePath, reportDirectory.get().asFile.absolutePath))
    }
}

tasks.register("bf656SignedRelease") {
    group = "build"
    description = "Builds a BF656 release only when the key matches and every signature check passes."
    dependsOn(verifyBF656SignedArtifacts)
}
