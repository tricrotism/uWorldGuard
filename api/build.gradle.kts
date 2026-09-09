plugins {
    id("java-library")
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.vanniktech.publish)
}

repositories {
    mavenCentral()
}

dependencies {
    // Paper API only (no NMS) — provides org.bukkit.* and the jspecify annotations.
    paperweight.paperDevBundle(libs.versions.paper.api.get())

    // Caffeine — the per-chunk region cache compiles against it; provided at runtime by the
    // plugin's PluginLoader (UWorldGuardLoader), which downloads it into the shared classloader.
    compileOnly(libs.caffeine)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "uworldguard-api", version.toString())

    pom {
        name = "uWorldGuard API"
        description = "Region and flag API for the uWorldGuard Paper plugin."
        inceptionYear = "2026"
        url = "https://github.com/tricrotism/uWorldGuard"
        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/tricrotism/uWorldGuard/blob/master/LICENSE.md"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "tricrotism"
                name = "Sage Kummer"
                url = "https://github.com/tricrotism"
            }
        }
        scm {
            url = "https://github.com/tricrotism/uWorldGuard"
            connection = "scm:git:git://github.com/tricrotism/uWorldGuard.git"
            developerConnection = "scm:git:ssh://git@github.com/tricrotism/uWorldGuard.git"
        }
    }
}
