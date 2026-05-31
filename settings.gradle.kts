rootProject.name = "Pain_au_Choc_Ultimate_de_Ouf"

pluginManagement {
    repositories {
        maven("https://maven.minecraftforge.net/") {
            name = "MinecraftForge"
        }
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge Snapshots" }

        mavenCentral()
        gradlePluginPortal()
    }
}

include("common", "neoforge")
