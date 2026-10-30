import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.bundling.Jar
import java.util.zip.ZipFile
import java.net.URI
import java.util.Properties
import javax.imageio.ImageIO

// Keep BF656 resource packaging separate from HMCL's version generation.
val bf656ConfigFile = rootProject.file("config/bf656.properties")
val bf656Config = Properties()
bf656ConfigFile.reader(Charsets.UTF_8).use { bf656Config.load(it) }
listOf("name", "fullName", "version").forEach { key ->
    require(!bf656Config.getProperty(key).isNullOrBlank()) {
        "Missing or blank '$key' in config/bf656.properties"
    }
}

// Catch release configuration mistakes before they can prevent a packaged launcher from starting.
require(bf656Config.getProperty("update.channel", "stable").trim() in listOf("stable", "dev", "nightly")) {
    "update.channel must be stable, dev, or nightly in config/bf656.properties"
}
listOf("update.url", "update.downloadUrl", "update.changelogUrl").forEach { key ->
    val url = bf656Config.getProperty(key, "").trim()
    if (url.isNotEmpty()) {
        val uri = URI(url)
        val loopback = uri.host?.lowercase() in listOf("localhost", "127.0.0.1", "[::1]")
        require(uri.host != null && uri.userInfo == null && uri.fragment == null
                && (uri.scheme.equals("https", ignoreCase = true)
                || (loopback && uri.scheme.equals("http", ignoreCase = true)))) {
            "$key must be HTTPS (or loopback HTTP) in config/bf656.properties"
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    from(bf656ConfigFile) {
        into("assets")
    }
}

// Validate independent resources without touching any original HMCL image.
val bf656IconDirectory = file("src/main/resources/assets/img")
val bf656IconSizes = mapOf(
    "bf656-icon.png" to 32,
    "bf656-icon@2x.png" to 64,
    "bf656-icon@4x.png" to 128,
    "bf656-icon@8x.png" to 256,
    "bf656-icon-title.png" to 24,
    "bf656-icon-title@2x.png" to 48,
    "bf656-icon-mac.png" to 512,
)
val bf656Ico = bf656IconDirectory.resolve("bf656-icon.ico")
val validateBF656Icons = tasks.register("validateBF656Icons") {
    inputs.files(bf656IconSizes.keys.map { bf656IconDirectory.resolve(it) }, bf656Ico)
    doLast {
        bf656IconSizes.forEach { (name, size) ->
            val image = ImageIO.read(bf656IconDirectory.resolve(name))
            require(image != null && image.width == size && image.height == size) {
                "$name must be a readable ${size}x$size image"
            }
        }
        val bytes = bf656Ico.readBytes()
        require(bytes.size >= 22 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte()
                && bytes[2] == 1.toByte() && bytes[3] == 0.toByte()) {
            "bf656-icon.ico must be a Windows ICO file"
        }
    }
}

// Exclude the dependency's original EXE resource and package a generated copy instead.
// The dedicated configuration still follows the version in HMCL's version catalog.
val bf656Launcher = configurations.create("bf656Launcher")
val catalogs = extensions.getByType<VersionCatalogsExtension>()
dependencies.add(bf656Launcher.name, catalogs.named("libs").findLibrary("hmclauncher").get())
configurations.named("runtimeClasspath") {
    exclude(group = "org.glavo.hmcl", module = "HMCLauncher")
}
val launcherOverride = providers.environmentVariable("HMCL_LAUNCHER_EXE")
val iconScript = rootProject.file("gradle/bf656-icons.ps1")
val nativeDirectory = layout.buildDirectory.dir("generated/bf656-native")
val prepareBF656Launcher = tasks.register("prepareBF656Launcher") {
    dependsOn(validateBF656Icons)
    inputs.property("launcherOverride", launcherOverride.orElse(""))
    inputs.property("platform", System.getProperty("os.name"))
    if (launcherOverride.orNull.isNullOrBlank()) {
        inputs.files(bf656Launcher)
    } else {
        inputs.file(launcherOverride)
    }
    inputs.file(bf656Ico)
    inputs.file(iconScript)
    outputs.dir(nativeDirectory)
    doLast {
        val directory = nativeDirectory.get().asFile
        val original = directory.resolve("source/HMCLauncher.exe")
        val generated = directory.resolve("assets/HMCLauncher.exe")
        original.parentFile.mkdirs()
        val override = launcherOverride.orNull
        if (override.isNullOrBlank()) {
            ZipFile(bf656Launcher.singleFile).use { archive ->
                val entry = requireNotNull(archive.getEntry("assets/HMCLauncher.exe")) {
                    "HMCLauncher dependency does not contain its native EXE"
                }
                archive.getInputStream(entry).use { original.writeBytes(it.readBytes()) }
            }
        } else {
            file(override).copyTo(original, overwrite = true)
        }
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            providers.exec {
                commandLine("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", iconScript, "-SourceLauncher", original, "-IconPath", bf656Ico,
                    "-OutputLauncher", generated)
            }.result.get().assertNormalExitValue()
        } else {
            generated.parentFile.mkdirs()
            original.copyTo(generated, overwrite = true)
            logger.warn("BF656 EXE icon customization requires a Windows build; retaining the original native icon.")
        }
    }
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(validateBF656Icons)
    from(prepareBF656Launcher.map { nativeDirectory.get().dir("assets") }) {
        into("assets")
    }
}
// HMCL's optional native override is copied and customized above, preserving the supplied file.
// Suppress only its original shadowJar source, so it cannot replace our generated native resource.
tasks.named<Jar>("shadowJar") {
    eachFile {
        val override = launcherOverride.orNull
        if (!override.isNullOrBlank() && path == "assets/HMCLauncher.exe"
            && file.canonicalFile == project.file(override).canonicalFile) {
            exclude()
        }
    }
}
