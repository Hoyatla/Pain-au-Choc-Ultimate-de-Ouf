plugins {
    id("java-library")
    id("idea")
    id("com.github.gmazzo.buildconfig") version "5.3.5"
}

sourceSets.create("desktop")

buildConfig {
    className("BuildConfig")
    packageName("net.irisshaders.iris")
    useJavaOutput()

    buildConfigField("IS_SHARED_BETA", false)
    buildConfigField("ACTIVATE_RENDERDOC", false)
    buildConfigField("BETA_TAG", "")
    buildConfigField("BETA_VERSION", 0)

    sourceSets.getByName("desktop") {
        buildConfigField("IS_SHARED_BETA", false)
    }
}

sourceSets {
    val main = getByName("main")
    val test = getByName("test")
    val headers = create("headers")
    val vendored = create("vendored")
    val paucorCompatibility = create("paucorCompatibility")

    headers.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }

    test.apply {
        java {
            compileClasspath += main.compileClasspath
            compileClasspath += main.output
        }
    }

    vendored.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }

    paucorCompatibility.apply {
        java {
            compileClasspath += main.compileClasspath
            compileClasspath += main.output
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = false
}
