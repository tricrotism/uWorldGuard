plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.paperweight.userdev)
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
    withSourcesJar()
}

// Consumable by other plugins: `./gradlew :api:publishToMavenLocal`, then depend on
// com.tricrotism:uworldguard-api as compileOnly (classes ship inside the uWorldGuard jar).
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "uworldguard-api"
            from(components["java"])
        }
    }
}
