plugins {
    id("java-library")
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.extendedclip.com/releases/")
    // InvUI PacketEvents fork. GitHub Packages refuses anonymous reads, so builds need
    // gpr.username / gpr.password (a read:packages token) in ~/.gradle/gradle.properties.
    maven("https://maven.pkg.github.com/AhmadNasser04/InvUI-PacketEvents") {
        credentials {
            username = providers.gradleProperty("gpr.username").get()
            password = providers.gradleProperty("gpr.password").get()
        }
        content { includeGroup("xyz.xenondevs.invui") }
    }
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())

    // Our own API — compiled against and bundled into the plugin jar (see the jar task).
    implementation(project(":api"))

    implementation(project(":wg-compat"))

    implementation(libs.bstats.bukkit)

    // Cloud — downloaded at boot by the PluginLoader (UWorldGuardLoader), not shaded.
    compileOnly(libs.cloud.paper)
    compileOnly(libs.cloud.annotations)
    annotationProcessor(libs.cloud.annotations)

    // PacketEvents — provided by the server plugin at runtime. Required: InvUI's menus run on it.
    compileOnly(libs.packetevents.spigot)

    // PlaceholderAPI — optional at runtime; placeholder expansion is skipped when absent.
    compileOnly(libs.placeholderapi)

    // InvUI (PacketEvents fork) - shaded and relocated, since its repository needs a token to read.
    implementation(libs.invui)

    // WorldEdit — optional at runtime; selection falls back to the built-in wand when absent.
    // Transitives are excluded: only the API classes are needed to compile, and fastutil/gson
    // are provided by the server (and conflict with Paper's strict versions otherwise).
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }

    // MockBukkit runs the tests against a mock server, so listeners and services are exercised
    // without a Paper process. Its artifact id is pinned to the API version we compile against.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockbukkit)
    testImplementation("io.papermc.paper:paper-api:${libs.versions.paper.api.get()}")
    testRuntimeOnly(libs.junit.platform.launcher)
}

// MockBukkit has to be the only Bukkit implementation the tests can see. With paperweight's
// mojang-mapped server on the test classpath, org.bukkit.Registry initialises against the real
// server's registry access and every mock fails before the first assertion, so the server jar
// stays on compileOnly and the tests compile and run against paper-api.
paperweight {
    addServerDependencyTo = setOf(configurations.compileOnly.get())
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    test {
        useJUnitPlatform()
    }

    // Bundle the API module's classes into the plugin jar so dependent plugins resolve
    // them at runtime from uWorldGuard's classloader.
    named<Jar>("jar") {
        dependsOn(":api:jar", ":wg-compat:jar")
        from(project(":api").sourceSets["main"].output)
        from(project(":wg-compat").sourceSets["main"].output)
    }

    shadowJar {
        archiveBaseName.set("uWorldGuard")
        archiveClassifier.set("")
        dependsOn(":api:jar", ":wg-compat:jar")
        from(project(":api").sourceSets["main"].output)
        from(project(":wg-compat").sourceSets["main"].output)
        // LGPL-3.0 obliges us to ship the license texts alongside the compat layer's classes.
        from(project(":wg-compat").file("COPYING")) { into("META-INF/licenses/wg-compat") }
        from(project(":wg-compat").file("COPYING.LESSER")) { into("META-INF/licenses/wg-compat") }
        from(project(":wg-compat").file("README.md")) {
            into("META-INF/licenses/wg-compat")
            rename { "README-wg-compat.md" }
        }
        configurations = project.configurations.runtimeClasspath.map { setOf(it) }
        dependencies {
            exclude { it.moduleGroup != "org.bstats" && it.moduleGroup != "xyz.xenondevs.invui" }
        }
        relocate("org.bstats", "com.tricrotism.uworldguard.metrics")
        relocate("xyz.xenondevs.invui", "com.tricrotism.uworldguard.lib.invui")
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    runPaper.folia.registerTask {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf(
            "version" to version.toString(),
            "description" to (rootProject.description ?: "")
        )

        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
