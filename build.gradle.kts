// Root project — all build logic lives in the api/ and plugin/ modules.
// `group`, `version`, and `description` are inherited from gradle.properties.
//
// Plugins are declared here with `apply false` so they load in a single classloader
// shared by the subprojects (paperweight breaks if applied independently per project).
plugins {
    alias(libs.plugins.paperweight.userdev) apply false
    alias(libs.plugins.run.paper) apply false
}
