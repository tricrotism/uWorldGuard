plugins {
    id("java-library")
    alias(libs.plugins.paperweight.userdev)
}

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())

    implementation(project(":api"))

    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}