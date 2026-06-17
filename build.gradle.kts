
plugins {
    id("java")
}

val MINECRAFT_VERSION by extra { "1.20.1" }
val NEOFORGE_VERSION by extra { "47.4.20" }

// https://semver.org/
val MOD_VERSION by extra { "0.4.0" }

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

subprojects {
    apply(plugin = "maven-publish")

    java.toolchain.languageVersion = JavaLanguageVersion.of(17)


    fun createVersionString(): String {
        return MOD_VERSION
    }

    tasks.processResources {
        filesMatching("META-INF/mods.toml") {
            expand(mapOf("version" to createVersionString()))
        }
    }

    version = createVersionString()
    group = "fr.hoyatla.pauc"

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    // Disables Gradle's custom module metadata from being published to maven. The
    // metadata includes mapped dependencies which are not reasonably consumable by
    // other mod developers.
    tasks.withType<GenerateModuleMetadata>().configureEach {
        enabled = false
    }
}
