plugins {
    id("java-library")
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.xenondevs.xyz/releases/")
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())

    // Our own API — compiled against and bundled into the plugin jar (see the jar task).
    implementation(project(":api"))

    // Cloud + Caffeine — downloaded at boot by the PluginLoader (UWorldGuardLoader), not shaded.
    compileOnly(libs.cloud.paper)
    compileOnly(libs.cloud.annotations)
    annotationProcessor(libs.cloud.annotations)
    compileOnly(libs.caffeine)

    // PacketEvents — provided by the server plugin at runtime.
    compileOnly(libs.packetevents.spigot)

    // PlaceholderAPI — optional at runtime; placeholder expansion is skipped when absent.
    compileOnly(libs.placeholderapi)

    // InvUI — GUI library, downloaded at boot by the PluginLoader (self-contained, packet-based).
    compileOnly(libs.invui)

    // WorldEdit — optional at runtime; selection falls back to the built-in wand when absent.
    // Transitives are excluded: only the API classes are needed to compile, and fastutil/gson
    // are provided by the server (and conflict with Paper's strict versions otherwise).
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    // Bundle the API module's classes into the plugin jar so dependent plugins resolve
    // them at runtime from uWorldGuard's classloader.
    named<Jar>("jar") {
        dependsOn(":api:jar")
        from(project(":api").sourceSets["main"].output)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf(
            "version" to version,
            "description" to (rootProject.description ?: "")
        )
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
