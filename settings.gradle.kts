// settings.gradle.kts — pipeline template monorepo
//
// Plugin resolution order: mavenLocal (for `publishToMavenLocal` dev builds
// of build-tools) → GitHub Packages Maven → gradle plugin portal → mavenCentral.
// Never via `includeBuild` of a sibling repo path: dev iteration goes through
// `./gradlew publishToMavenLocal` from build-tools so CI and local resolve
// the artifact the same way.
//
// Template packages sit at package/<vendor>/<product>/ or deeper
// (package/<vendor>/<suite>/<product>/), matching the npm name
// @zerobias-org/pipeline-template-<vendor>-...-<product>. Each package is
// a single npm artifact whose every *.yml at the package root is one
// pipeline template.
//
// (The package must NOT sit directly at package/: the publish workflow's
// detect job walks UP from each changed file to the nearest
// build.gradle.kts but explicitly skips the dir literally named
// `package`, so a package rooted there is never detected.)

pluginManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://maven.pkg.github.com/zerobias-org/util")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "zerobias-org"
                password = System.getenv("READ_TOKEN") ?: System.getenv("NPM_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("zb.workspace") version "1.+"
        id("zb.base") version "1.+"
        id("zb.content") version "1.+"
    }
}

rootProject.name = "pipeline-templates"

// Auto-discover template packages under package/. The marker walk picks
// up build.gradle.kts at any depth. Project paths mirror the filesystem:
// package/zerobias/zerobias/physicalspace → :zerobias:zerobias:physicalspace.
val packageDir = file("package")
if (packageDir.exists()) {
    packageDir.walkTopDown()
        .filter { it.name == "build.gradle.kts" }
        .forEach { buildFile ->
            val moduleDir = buildFile.parentFile
            val relativePath = moduleDir.relativeTo(packageDir).path
            val projectPath = relativePath.replace(File.separatorChar, ':')

            include(projectPath)
            project(":$projectPath").projectDir = moduleDir
        }
}
