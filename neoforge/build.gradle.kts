import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

plugins {
    id("idea")
    id("maven-publish")
    id("net.minecraftforge.gradle") version "[6.0,6.2)"
    id("java-library")
    id("org.spongepowered.mixin") version "0.7-SNAPSHOT"
}

base {
    archivesName = "Pain_au_Choc_Ultimate_de_Ouf"
}

val MINECRAFT_VERSION: String by rootProject.extra
val NEOFORGE_VERSION: String by rootProject.extra
val MOD_VERSION: String by rootProject.extra
val embeddedLodRuntimeJar = rootDir.resolve("vendor").resolve("pauc-lod-runtime").resolve("PaucUltimateLOD-3.0.3-b-1.20.1-forge.jar")
val privateAntlrRuntime = configurations.create("privateAntlrRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

jarJar.enable()

mixin {
    add(sourceSets.main.get(), "paucultimate.refmap.json")
    //add(project(":common").sourceSets.getByName("paucorCompatibility"), "paucultimate.refmap.json")
    setIgnoreConstraints(true)
    config("paucultimate.mixins.json")
    config("paucultimate-compat-dh.mixins.json")
    config("paucultimate-vertexformat.mixins.json")
    config("paucultimate-batched-entity-rendering.mixins.json")
    config("paucultimate-fantastic.mixins.json")
    config("paucultimate-forge.mixins.json")
}

sourceSets {
    main.get().apply {
        compileClasspath += project(":common").sourceSets.getByName("headers").output
    }

    test.get().apply {
        compileClasspath += main.get().compileClasspath
        compileClasspath += project(":common").sourceSets.getByName("headers").output
    }
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        forRepositories(fg.repository) // Only add this if you're using ForgeGradle, otherwise remove this line
        filter {
            includeGroup("maven.modrinth")
        }
    }

    maven { url = uri("https://maven.architectury.dev/") }
    maven { url = uri("https://files.minecraftforge.net/maven/") }
    maven { url = uri("https://maven.neoforged.net/releases/") }
    maven { url = uri("https://maven.su5ed.dev/releases") }
    mavenLocal()
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge Snapshots" }

}

minecraft {
    mappings("official", "1.20.1")
    copyIdeResources = true //Calls processResources when in dev

    val transformerFile = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (transformerFile.exists()) {
        accessTransformer(transformerFile)
    }

    runs {
        create("client") {
            environment("LD_PRELOAD", "/usr/lib/librenderdoc.so")
            workingDirectory(project.file("run"))
            ideaModule("${rootProject.name}.${project.name}.main")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", "${projectDir}/build/createSrgToMcp/output.srg")
            mods {
                create("modRun") {
                    source(sourceSets.main.get())
                    source(project(":common").sourceSets.main.get())
                }
            }
        }
    }
}

dependencies {
    minecraft("net.minecraftforge:forge:${MINECRAFT_VERSION}-${NEOFORGE_VERSION}")
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT:processor")

    compileOnly("io.github.llamalad7:mixinextras-common:0.3.5")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.3.5")
    implementation(jarJar("io.github.llamalad7:mixinextras-forge:0.3.5")) {
        jarJar.ranged(this, "[0.3.5,)")
    }
    compileOnly("net.java.dev.jna:jna:5.12.1")

    minecraftLibrary("io.github.douira:glsl-transformer:2.0.0-pre6") {
        isTransitive = false
    }
    jarJar("io.github.douira:glsl-transformer:2.0.0-pre6") {
        jarJar.ranged(this, "[2.0.0-pre6,2.0.0-pre7)")
        isTransitive = false
    }
    add("privateAntlrRuntime", "org.antlr:antlr4-runtime:4.10.1")
    minecraftLibrary("org.anarres:jcpp:1.4.14") {
        isTransitive = false
    }
    jarJar("org.anarres:jcpp:[1.4.14,1.4.15)") {
        isTransitive = false
    }
    compileOnly(files(embeddedLodRuntimeJar))
    compileOnly(files(rootDir.resolve("vendor").resolve("baseline").resolve("paucultimate-base.jar")))
}

fun CopySpec.excludeEmbeddedDistantHorizonsLoader() {
    exclude("META-INF/MANIFEST.MF")
    exclude("META-INF/mods.toml")
    exclude("META-INF/neoforge.mods.toml")
    exclude("META-INF/accesstransformer.cfg")
    exclude("META-INF/forgix/**")
    exclude("META-INF/jarjar/**")
    exclude("META-INF/jars/**")
    exclude("META-INF/maven/**")
    exclude("META-INF/native-image/**")
    exclude("META-INF/services/**")
    exclude("META-INF/*.SF")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.DSA")
    exclude("fabric.mod.json")
    exclude("not.fabric.mod.json")
    exclude("quilt.mod.json")
    exclude("pack.mcmeta")
    exclude("*.mixins.json")
    exclude("*mixins-refmap.json")
    exclude("*.accesswidener")
    exclude("org/antlr/**")
    exclude("com/seibel/distanthorizons/forge/ForgeMain.class")
    exclude("com/seibel/distanthorizons/forge/mixins/**")
    exclude("com/seibel/distanthorizons/fabric/**")
    exclude("com/seibel/distanthorizons/cleanroom/**")
    exclude("com/seibel/distanthorizons/neoforge/**")
    exclude("com/seibel/distanthorizons/common/**/*_fabric.class")
    exclude("com/seibel/distanthorizons/core/jar/installer/GitlabGetter.class")
    exclude("com/seibel/distanthorizons/core/jar/installer/ModrinthGetter.class")
    exclude("com/seibel/distanthorizons/core/jar/installer/WebDownloader.class")
    exclude("com/seibel/distanthorizons/core/jar/updater/SelfUpdater.class")
    exclude("dh_sqlite/util/ProcessRunner.class")
}


tasks.jarJar {
    dependsOn("reobfJar")
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(zipTree(embeddedLodRuntimeJar)) {
        excludeEmbeddedDistantHorizonsLoader()
    }
}

val forbiddenJarNameFragments = listOf(
    "fabric",
    "iris",
    "oculus",
    "sodium",
    "indium",
    "dh_sqlite",
    "distanthorizons",
    "distant horizons",
    "com/seibel/distanthorizons",
    "com.seibel.distanthorizons"
)
val sameLengthJarReplacements = listOf(
    "SODIUM" to "PAUCOR",
    "Sodium" to "PauCor",
    "sodium" to "paucor",
    "INDIUM" to "PAUCIN",
    "Indium" to "PauCin",
    "indium" to "paucin",
    "OCULUS" to "PAUCUS",
    "Oculus" to "PauCus",
    "oculus" to "paucus",
    "FABRIC" to "FORGEX",
    "Fabric" to "ForgeX",
    "fabric" to "forgex",
    "com/seibel/distanthorizons" to "fr/hoyatla/pauc/lodruntime",
    "com.seibel.distanthorizons" to "fr.hoyatla.pauc.lodruntime",
    "assets/distanthorizons" to "assets/paucultimatelod",
    "DistantHorizons.toml" to "PaucUltimateLOD.toml",
    "distanthorizons.toml" to "paucultimatelod.toml",
    "DistantHorizons" to "PaucUltimateLOD",
    "distantHorizons" to "paucUltimateLOD",
    "distanthorizons" to "paucultimatelod",
    "Distant Horizons" to "PauC UltimateLOD",
    "DH-" to "PL-",
    "DH_" to "PL_",
    "DH " to "PL ",
    "dh_" to "pl_",
    "DhApi" to "PlApi",
    "IRIS" to "PAUC",
    "Iris" to "PauC",
    "iris" to "pauc"
)

val sanitizableJarTextExtensions = setOf(
    "json",
    "toml",
    "mcmeta",
    "cfg",
    "properties",
    "txt",
    "md",
    "lang",
    "glsl",
    "vsh",
    "fsh",
    "geom",
    "tesc",
    "tese",
    "comp",
    "vert",
    "frag",
    "xml",
    "yml",
    "yaml"
)

val sanitizableNativeSqliteExtensions = setOf(
    "dll",
    "so",
    "dylib",
    "jnilib"
)

val nativeSqliteJarReplacements = listOf(
    "dh_" to "pl_"
)

val releaseSafetyStrippedEntries = setOf(
    "fr/hoyatla/pauc/lodruntime/core/jar/installer/GitlabGetter.class",
    "fr/hoyatla/pauc/lodruntime/core/jar/installer/ModrinthGetter.class",
    "fr/hoyatla/pauc/lodruntime/core/jar/installer/WebDownloader.class"
)
val releaseSafetyEntryChecks = mapOf(
    "fr/hoyatla/pauc/lodruntime/core/jar/updater/SelfUpdater.class" to listOf(
        "webdownloader",
        "downloadasfile",
        "execcommand",
        "http://",
        "https://"
    ),
    "pl_sqlite/util/ProcessRunner.class" to listOf(
        "java/lang/processbuilder",
        "java/lang/runtime",
        "exec"
    )
)
val releaseSafetyReplacementEntries = releaseSafetyEntryChecks.keys

val privateAntlrJarReplacements = listOf(
    "org/antlr/v4/runtime" to "fr/pauc/antlr/v4/rtx",
    "org.antlr.v4.runtime" to "fr.pauc.antlr.v4.rtx"
)

val sanitizableJarClassPrefixes = listOf(
    "net/irisshaders/",
    "net/paucshaders/",
    "fr/hoyatla/pauc/",
    "com/seibel/distanthorizons/",
    "distanthorizons/libraries/",
    "paucultimatelod/libraries/",
    "dh_sqlite/",
    "pl_sqlite/",
    "kroppeb/stareval/"
)

fun replaceLegacyFragments(value: String): String {
    return sameLengthJarReplacements.fold(value) { current, replacement ->
        current.replace(replacement.first, replacement.second)
    }
}

fun replaceLegacyFragments(
    bytes: ByteArray,
    replacements: List<Pair<String, String>> = sameLengthJarReplacements
): ByteArray {
    val output = bytes.copyOf()
    for ((legacy, replacement) in replacements) {
        val legacyBytes = legacy.toByteArray(Charsets.UTF_8)
        val replacementBytes = replacement.toByteArray(Charsets.UTF_8)
        require(legacyBytes.size == replacementBytes.size) {
            "Jar sanitization replacement must preserve byte length: $legacy -> $replacement"
        }

        var index = 0
        while (index <= output.size - legacyBytes.size) {
            var matches = true
            for (offset in legacyBytes.indices) {
                if (output[index + offset] != legacyBytes[offset]) {
                    matches = false
                    break
                }
            }

            if (matches) {
                for (offset in replacementBytes.indices) {
                    output[index + offset] = replacementBytes[offset]
                }
                index += legacyBytes.size
            } else {
                index++
            }
        }
    }
    return output
}

fun replacePrivateAntlrFragments(value: String): String {
    return privateAntlrJarReplacements.fold(value) { current, replacement ->
        current.replace(replacement.first, replacement.second)
    }
}

fun replacePrivateAntlrFragments(bytes: ByteArray): ByteArray {
    return replaceLegacyFragments(bytes, privateAntlrJarReplacements)
}

fun shouldSanitizeNativeSqliteEntryContent(entryName: String): Boolean {
    val lowerName = entryName.lowercase(Locale.ROOT)
    val extension = lowerName.substringAfterLast('.', missingDelimiterValue = "")
    return extension in sanitizableNativeSqliteExtensions
        && (lowerName.startsWith("dh_sqlite/native/") || lowerName.startsWith("pl_sqlite/native/"))
}

fun loadReleaseSafetyReplacementBytes(entryName: String): ByteArray? {
    if (entryName !in releaseSafetyReplacementEntries) {
        return null
    }

    val replacementFile = layout.buildDirectory.dir("sourceSets/main").get().asFile.resolve(entryName)
    return if (replacementFile.isFile) {
        replacementFile.readBytes()
    } else {
        null
    }
}

fun shouldPatchGlslTransformerJar(entryName: String): Boolean {
    val lowerName = entryName.lowercase(Locale.ROOT)
    return lowerName.startsWith("meta-inf/jarjar/glsl-transformer-") && lowerName.endsWith(".jar")
}

fun shouldRelocatePrivateAntlrEntryContent(entryName: String): Boolean {
    val lowerName = entryName.lowercase(Locale.ROOT)
    return lowerName.endsWith(".class") && (
        lowerName.startsWith("net/irisshaders/")
            || lowerName.startsWith("net/paucshaders/")
            || lowerName.startsWith("io/github/douira/glsl_transformer/")
        )
}

fun shouldSanitizeEntryContent(entryName: String): Boolean {
    val lowerName = entryName.lowercase(Locale.ROOT)
    if (lowerName == "meta-inf/manifest.mf") {
        return true
    }

    if (lowerName.startsWith("meta-inf/services/")) {
        return true
    }

    val extension = lowerName.substringAfterLast('.', missingDelimiterValue = "")
    if (extension in sanitizableJarTextExtensions) {
        return true
    }

    return lowerName.endsWith(".class")
        && sanitizableJarClassPrefixes.any { lowerName.startsWith(it) }
}

fun putJarEntry(
    jarOutput: JarOutputStream,
    inputEntry: JarEntry,
    outputName: String,
    bytes: ByteArray? = null
) {
    val outputEntry = JarEntry(outputName)
    outputEntry.time = inputEntry.time
    outputEntry.comment = inputEntry.comment?.let(::replaceLegacyFragments)?.let(::replacePrivateAntlrFragments)
    if (inputEntry.method == JarEntry.STORED && inputEntry.isDirectory) {
        outputEntry.method = JarEntry.STORED
        outputEntry.size = 0
        outputEntry.compressedSize = 0
        outputEntry.crc = 0
    }

    jarOutput.putNextEntry(outputEntry)
    if (!inputEntry.isDirectory && bytes != null) {
        jarOutput.write(bytes)
    }
    jarOutput.closeEntry()
}

fun sanitizeAndRelocateManifest(manifest: Manifest?): Manifest? {
    if (manifest == null) {
        return null
    }

    val manifestBytes = ByteArrayOutputStream()
    manifest.write(manifestBytes)
    return Manifest(ByteArrayInputStream(replacePrivateAntlrFragments(manifestBytes.toByteArray())))
}

fun addRelocatedPrivateAntlrRuntime(jarOutput: JarOutputStream, seenEntries: MutableSet<String>) {
    val antlrRuntimeJar = privateAntlrRuntime.singleFile
    JarInputStream(ByteArrayInputStream(antlrRuntimeJar.readBytes())).use { antlrInput ->
        generateSequence { antlrInput.nextJarEntry }.forEach { antlrEntry ->
            val originalName = antlrEntry.name
            if (!originalName.startsWith("org/antlr/v4/runtime/")) {
                antlrInput.closeEntry()
                return@forEach
            }

            val relocatedName = replacePrivateAntlrFragments(originalName)
            if (!seenEntries.add(relocatedName)) {
                antlrInput.closeEntry()
                return@forEach
            }

            val relocatedBytes = if (antlrEntry.isDirectory) {
                null
            } else {
                replacePrivateAntlrFragments(antlrInput.readBytes())
            }
            putJarEntry(jarOutput, antlrEntry, relocatedName, relocatedBytes)
            antlrInput.closeEntry()
        }
    }
}

fun sanitizeGlslTransformerJarBytes(inputBytes: ByteArray): ByteArray {
    val outputBytes = ByteArrayOutputStream()
    JarInputStream(ByteArrayInputStream(inputBytes)).use { jarInput ->
        val sanitizedManifest = sanitizeAndRelocateManifest(jarInput.manifest)
        val jarOutputStream = if (sanitizedManifest != null) {
            JarOutputStream(outputBytes, sanitizedManifest)
        } else {
            JarOutputStream(outputBytes)
        }

        jarOutputStream.use { jarOutput ->
            val seenEntries = mutableSetOf<String>()
            generateSequence { jarInput.nextJarEntry }.forEach { inputEntry ->
                val sanitizedName = replacePrivateAntlrFragments(inputEntry.name)
                if (!seenEntries.add(sanitizedName)) {
                    if (inputEntry.isDirectory) {
                        jarInput.closeEntry()
                        return@forEach
                    }
                    throw GradleException("glsl-transformer relocation created a duplicate entry: $sanitizedName")
                }

                val sanitizedBytes = if (inputEntry.isDirectory) {
                    null
                } else {
                    replacePrivateAntlrFragments(jarInput.readBytes())
                }
                putJarEntry(jarOutput, inputEntry, sanitizedName, sanitizedBytes)
                jarInput.closeEntry()
            }

            addRelocatedPrivateAntlrRuntime(jarOutput, seenEntries)
        }
    }
    return outputBytes.toByteArray()
}

fun sanitizeJarBytes(inputBytes: ByteArray): ByteArray {
    val outputBytes = ByteArrayOutputStream()
    JarInputStream(ByteArrayInputStream(inputBytes)).use { jarInput ->
        val sanitizedManifest = sanitizeManifest(jarInput.manifest)
        val jarOutputStream = if (sanitizedManifest != null) {
            JarOutputStream(outputBytes, sanitizedManifest)
        } else {
            JarOutputStream(outputBytes)
        }

        jarOutputStream.use { jarOutput ->
            val seenEntries = mutableSetOf<String>()
            generateSequence { jarInput.nextJarEntry }.forEach { inputEntry ->
                val sanitizedName = replaceLegacyFragments(inputEntry.name)
                if (sanitizedName in releaseSafetyStrippedEntries) {
                    jarInput.closeEntry()
                    return@forEach
                }
                if (!seenEntries.add(sanitizedName)) {
                    if (inputEntry.isDirectory) {
                        jarInput.closeEntry()
                        return@forEach
                    }
                    throw GradleException("Jar sanitization created a duplicate entry: $sanitizedName")
                }

                val outputEntry = JarEntry(sanitizedName)
                outputEntry.time = inputEntry.time
                outputEntry.comment = inputEntry.comment?.let(::replaceLegacyFragments)
                if (inputEntry.method == JarEntry.STORED && inputEntry.isDirectory) {
                    outputEntry.method = JarEntry.STORED
                    outputEntry.size = 0
                    outputEntry.compressedSize = 0
                    outputEntry.crc = 0
                }

                jarOutput.putNextEntry(outputEntry)
                if (!inputEntry.isDirectory) {
                    val entryBytes = jarInput.readBytes()
                    val sanitizedBytes = loadReleaseSafetyReplacementBytes(sanitizedName) ?: if (sanitizedName.endsWith(".jar")) {
                        val sanitizedNestedJarBytes = sanitizeJarBytes(entryBytes)
                        if (shouldPatchGlslTransformerJar(sanitizedName)) {
                            sanitizeGlslTransformerJarBytes(sanitizedNestedJarBytes)
                        } else {
                            sanitizedNestedJarBytes
                        }
                    } else if (shouldSanitizeNativeSqliteEntryContent(sanitizedName)) {
                        replaceLegacyFragments(entryBytes, nativeSqliteJarReplacements)
                    } else if (shouldRelocatePrivateAntlrEntryContent(sanitizedName)) {
                        replacePrivateAntlrFragments(replaceLegacyFragments(entryBytes))
                    } else if (shouldSanitizeEntryContent(sanitizedName)) {
                        replaceLegacyFragments(entryBytes)
                    } else {
                        entryBytes
                    }
                    jarOutput.write(sanitizedBytes)
                }
                jarOutput.closeEntry()
                jarInput.closeEntry()
            }
        }
    }
    return outputBytes.toByteArray()
}

fun sanitizeManifest(manifest: Manifest?): Manifest? {
    if (manifest == null) {
        return null
    }

    val manifestBytes = ByteArrayOutputStream()
    manifest.write(manifestBytes)
    return Manifest(ByteArrayInputStream(replaceLegacyFragments(manifestBytes.toByteArray())))
}

fun collectForbiddenJarFragments(jarBytes: ByteArray, jarLabel: String): List<String> {
    val findings = mutableListOf<String>()
    JarInputStream(ByteArrayInputStream(jarBytes)).use { jarInput ->
        val manifest = jarInput.manifest
        if (manifest != null) {
            val manifestBytes = ByteArrayOutputStream()
            manifest.write(manifestBytes)
            val lowerManifest = manifestBytes.toByteArray().toString(Charsets.ISO_8859_1).lowercase(Locale.ROOT)
            for (fragment in forbiddenJarNameFragments) {
                if (lowerManifest.contains(fragment)) {
                    findings += "$jarLabel!/META-INF/MANIFEST.MF"
                    break
                }
            }
        }

        generateSequence { jarInput.nextJarEntry }.forEach { entry ->
            val lowerName = entry.name.lowercase(Locale.ROOT)
            for (fragment in forbiddenJarNameFragments) {
                if (lowerName.contains(fragment)) {
                    findings += "$jarLabel!/${entry.name}"
                }
            }

            if (!entry.isDirectory) {
                val entryBytes = jarInput.readBytes()
                if (entry.name.endsWith(".jar")) {
                    findings += collectForbiddenJarFragments(entryBytes, "$jarLabel!/${entry.name}")
                } else if (shouldSanitizeEntryContent(entry.name) || shouldSanitizeNativeSqliteEntryContent(entry.name)) {
                    val lowerText = entryBytes.toString(Charsets.ISO_8859_1).lowercase(Locale.ROOT)
                    for (fragment in forbiddenJarNameFragments) {
                        if (lowerText.contains(fragment)) {
                            findings += "$jarLabel!/${entry.name}"
                            break
                        }
                    }
                }
            }
            jarInput.closeEntry()
        }
    }
    return findings
}

fun collectReleaseSafetyFindings(jarBytes: ByteArray, jarLabel: String): List<String> {
    val findings = mutableListOf<String>()
    JarInputStream(ByteArrayInputStream(jarBytes)).use { jarInput ->
        generateSequence { jarInput.nextJarEntry }.forEach { entry ->
            if (entry.name in releaseSafetyStrippedEntries) {
                findings += "$jarLabel!/${entry.name} should be absent"
                jarInput.closeEntry()
                return@forEach
            }
            if (!entry.isDirectory) {
                val entryBytes = jarInput.readBytes()
                if (entry.name.endsWith(".jar")) {
                    findings += collectReleaseSafetyFindings(entryBytes, "$jarLabel!/${entry.name}")
                } else {
                    val patterns = releaseSafetyEntryChecks[entry.name]
                    if (patterns != null) {
                        val lowerText = entryBytes.toString(Charsets.ISO_8859_1).lowercase(Locale.ROOT)
                        for (pattern in patterns) {
                            if (lowerText.contains(pattern)) {
                                findings += "$jarLabel!/${entry.name} contains $pattern"
                            }
                        }
                    }
                }
            }
            jarInput.closeEntry()
        }
    }
    return findings
}

val sanitizeGeneratedJars by tasks.registering {
	group = "build"
	description = "Rewrites generated jars so they do not expose legacy Iris names."
    mustRunAfter("jar", "reobfJar", "jarJar", "reobfJarJar")

    doLast {
        val jars = layout.buildDirectory.dir("libs").get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .toList()

        if (jars.isEmpty()) {
            logger.lifecycle("No generated jars found to sanitize.")
            return@doLast
        }

        for (jar in jars) {
            val sanitizedBytes = sanitizeJarBytes(jar.readBytes())
            jar.writeBytes(sanitizedBytes)
            val forbiddenFindings = collectForbiddenJarFragments(sanitizedBytes, jar.name)
            if (forbiddenFindings.isNotEmpty()) {
                throw GradleException(
                    "Generated jar still contains forbidden legacy names:\n"
                        + forbiddenFindings.take(50).joinToString("\n")
                        + if (forbiddenFindings.size > 50) "\n... and ${forbiddenFindings.size - 50} more" else ""
                )
            }
            val releaseSafetyFindings = collectReleaseSafetyFindings(sanitizedBytes, jar.name)
            if (releaseSafetyFindings.isNotEmpty()) {
                throw GradleException(
                    "Generated jar still contains blocked updater/process execution behavior:\n"
                        + releaseSafetyFindings.take(50).joinToString("\n")
                        + if (releaseSafetyFindings.size > 50) "\n... and ${releaseSafetyFindings.size - 50} more" else ""
                )
            }
            logger.lifecycle("Sanitized generated jar: ${jar.name}")
        }
    }
}

val paucJarScanReport by tasks.registering {
    group = "verification"
    description = "Writes a concise scan report for generated PauC jars."
    dependsOn(sanitizeGeneratedJars)

    doLast {
        val jars = layout.buildDirectory.dir("libs").get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .sortedBy { it.name }
            .toList()
        val reportDir = layout.buildDirectory.dir("reports").get().asFile
        reportDir.mkdirs()
        val report = reportDir.resolve("pauc-jar-scan.txt")
        val lines = mutableListOf<String>()
        lines += "PauC generated jar scan"
        lines += "jars=${jars.size}"
        for (jar in jars) {
            val findings = collectForbiddenJarFragments(jar.readBytes(), jar.name)
            val releaseSafetyFindings = collectReleaseSafetyFindings(jar.readBytes(), jar.name)
            lines += ""
            lines += "jar=${jar.name}"
            lines += "size=${jar.length()}"
            lines += "forbiddenFindings=${findings.size}"
            findings.take(50).forEach { lines += "finding=$it" }
            if (findings.size > 50) {
                lines += "finding=... and ${findings.size - 50} more"
            }
            lines += "releaseSafetyFindings=${releaseSafetyFindings.size}"
            releaseSafetyFindings.take(50).forEach { lines += "releaseSafetyFinding=$it" }
            if (releaseSafetyFindings.size > 50) {
                lines += "releaseSafetyFinding=... and ${releaseSafetyFindings.size - 50} more"
            }
        }
        report.writeText(lines.joinToString(System.lineSeparator()), Charsets.UTF_8)
        logger.lifecycle("Wrote PauC jar scan report: ${report.absolutePath}")
    }
}

val paucMigrationAudit by tasks.registering {
    group = "verification"
    description = "Audits generated jars for release-facing legacy namespace or loader identity leaks."
    dependsOn(paucJarScanReport)

    doLast {
        val jars = layout.buildDirectory.dir("libs").get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .sortedBy { it.name }
            .toList()
        val reportDir = layout.buildDirectory.dir("reports").get().asFile
        reportDir.mkdirs()
        val report = reportDir.resolve("pauc-migration-audit.txt")
        val blockers = mutableListOf<String>()
        val lines = mutableListOf<String>()
        lines += "PauC migration audit"
        lines += "generatedJars=${jars.size}"
        lines += "publicModId=paucultimate"
        lines += "providedCapabilities=pauc_core,pauc_shader"
        for (jar in jars) {
            val findings = collectForbiddenJarFragments(jar.readBytes(), jar.name)
            val releaseSafetyFindings = collectReleaseSafetyFindings(jar.readBytes(), jar.name)
            lines += ""
            lines += "jar=${jar.name}"
            lines += "legacyFindings=${findings.size}"
            if (findings.isNotEmpty()) {
                blockers += findings
                findings.take(50).forEach { lines += "legacyFinding=$it" }
            }
            lines += "releaseSafetyFindings=${releaseSafetyFindings.size}"
            if (releaseSafetyFindings.isNotEmpty()) {
                blockers += releaseSafetyFindings
                releaseSafetyFindings.take(50).forEach { lines += "releaseSafetyFinding=$it" }
            }
        }
        report.writeText(lines.joinToString(System.lineSeparator()), Charsets.UTF_8)
        if (blockers.isNotEmpty()) {
            throw GradleException(
                "PauC migration audit failed; generated jars still expose legacy names. See ${report.absolutePath}"
            )
        }
        logger.lifecycle("PauC migration audit passed: ${report.absolutePath}")
    }
}

val deployToPrismTestInstance by tasks.registering(Copy::class) {
    group = "deployment"
    description = "Copies the built PauC jar into the PrismLauncher 1.20.1 road beta test instance."
    dependsOn("build", sanitizeGeneratedJars, paucMigrationAudit)
    mustRunAfter(sanitizeGeneratedJars)

    val prismTestModsDir = file("C:/Users/charl/AppData/Roaming/PrismLauncher/instances/1.20.1 road beta/minecraft/mods")
    from(layout.buildDirectory.file("libs/Pain_au_Choc_Ultimate_de_Ouf-$MOD_VERSION.jar"))
    into(prismTestModsDir)
    doFirst {
        prismTestModsDir.mkdirs()
    }
}

tasks.named("jarJar").configure {
    finalizedBy("reobfJarJar")
}

tasks.matching { it.name in setOf("reobfJar", "reobfJarJar", "build") }.configureEach {
    finalizedBy(sanitizeGeneratedJars)
}

tasks.jar {
    archiveClassifier = "std"
}

val notNeoTask: (Task) -> Boolean = { it: Task ->
    !it.name.startsWith("compileService")
}

tasks {
    withType<JavaCompile>().matching(notNeoTask).configureEach {
        source(project(":common").sourceSets.main.get().allSource)
        source(project(":common").sourceSets.getByName("paucorCompatibility").allSource)
        source(project(":common").sourceSets.getByName("vendored").allSource)
        exclude("net/irisshaders/iris/compat/modmenu/**")
    }

    javadoc { source(project(":common").sourceSets.main.get().allJava) }

    processResources {
        inputs.property("version", project.version)

        from(project(":common").sourceSets.main.get().resources) {
            filesMatching("META-INF/mods.toml") {
                expand(mapOf("version" to project.version))
            }
        }
        from(project(":common").sourceSets.getByName("paucorCompatibility").resources)
    }

    jar {
        finalizedBy("reobfJar")
        dependsOn("compileJava")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(layout.buildDirectory.file("tmp/compileJava/compileJava-refmap.json")) {
            rename { "paucultimate.refmap.json" }
        }
        from(zipTree(embeddedLodRuntimeJar)) {
            excludeEmbeddedDistantHorizonsLoader()
        }
        manifest {
            attributes(
                "MixinConfigs" to "paucultimate.mixins.json,paucultimate-compat-dh.mixins.json,paucultimate-vertexformat.mixins.json,paucultimate-batched-entity-rendering.mixins.json,paucultimate-fantastic.mixins.json,paucultimate-forge.mixins.json"
            )
        }
    }
}

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            artifactId = base.archivesName.get()
            artifact(tasks.jar)
            fg.component(this)
        }
    }

    repositories {
        maven("file://${System.getenv("local_maven")}")
    }
}


sourceSets.forEach {
    val dir = layout.buildDirectory.dir("sourceSets/${it.name}")
    it.output.setResourcesDir(dir)
    it.java.destinationDirectory.set(dir)
}
