import com.zerobias.buildtools.content.SchemaPrimitives

plugins {
    id("zb.workspace")
}

group = "com.zerobias.content"

// ════════════════════════════════════════════════════════════
// Pipeline template content validator — owned by this repo.
//
// Philosophy (per Chris/Kevin): the dataloader is the source of truth
// for schema rules. Re-validating those here just creates drift risk —
// when the dataloader tightens a rule, the gate gets stale. The full
// schema (declared id, required keys, unknown keys, enums, schedule
// rules, secret-looking params, productId lookup, collectorArtifact
// dependency + batchMode support, and params.dataMappings checked like
// the UI's mapping dialog against the live classes) is exercised by
// testIntegrationDataloader against an ephemeral Neon branch during
// gate. See com/platform/dataloader/src/processors/pipelineTemplate/
// (PipelineTemplateFileHandler.ts, TemplateDataMappings.ts, README.md).
//
// SHAPE: a template package is a single npm package at
// package/<vendor>/<product>/ (or deeper, e.g.
// package/<vendor>/<suite>/<product>/) whose every *.yml DIRECTLY under
// the package root is one template (each with its own `id` UUID). Unlike
// the pipeline repo there is no pipeline/ subdirectory and no
// deprecated.yml: the dataloader ignores subdirectories, would read a
// root deprecated.yml as a template, and soft-deletes a template whose
// file is removed. This validator only enforces what the dataloader
// CANNOT or DOES NOT check:
//
//   1. Filesystem ↔ npm ↔ zerobias-block triangulation. The directory
//      package/<a>/<b>/.../ deterministically yields the npm name
//      (@zerobias-org/pipeline-template-<a>-<b>-...) and zerobias.package
//      (<a>.<b>....pipeline_template); the dataloader reads
//      zerobias.package but never the npm `name` field nor the on-disk
//      path, so a wrong name publishes under the wrong package and only
//      surfaces in prod. (Nothing derives the artifact type from the
//      name: events, dataloader-service and the dataloader all route on
//      zerobias.import-artifact, so the `pipeline-template-` prefix is
//      this repo's convention, never read as a `pipeline` artifact.)
//   2. Required files exist (package.json, .npmrc, at least one
//      template yml at the package root, no deprecated.yml).
//   3. Repo-wide unique template `id` UUIDs (separate :validateUniqueIds
//      task below). The dataloader only sees one package at a time.
// ════════════════════════════════════════════════════════════
extra["contentValidator"] = { proj: org.gradle.api.Project ->
    val projectDir = proj.projectDir   // = package/<vendor>/<product>/ or deeper
    val tag = "[pipeline-template-validator] ${proj.path}"

    require(projectDir.resolve("package.json").isFile) { "$tag package.json missing in ${projectDir.path}" }
    require(projectDir.resolve(".npmrc").isFile)       { "$tag .npmrc missing in ${projectDir.path}" }

    val ymls = (projectDir.listFiles() ?: emptyArray())
        .filter { it.isFile && !it.name.startsWith(".") && (it.extension == "yml" || it.extension == "yaml") }
    require(ymls.none { it.nameWithoutExtension == "deprecated" }) {
        "$tag deprecated.yml would be loaded as a template; delete a template's yml to retire it"
    }
    require(ymls.isNotEmpty()) { "$tag no template ymls found at the root of ${projectDir.path}" }

    // ── Filesystem ↔ npm ↔ zerobias-block triangulation ──
    // package/<a>/<b>/<c>/ → npm @zerobias-org/pipeline-template-<a>-<b>-<c>,
    // zerobias.package <a>.<b>.<c>.pipeline_template. Both must agree.
    val segments = projectDir.relativeTo(proj.rootDir.resolve("package")).invariantSeparatorsPath.split("/")
    require(segments.size >= 2) { "$tag package must sit at least two levels under package/ (package/<vendor>/<product>/)" }
    val pkgDoc = SchemaPrimitives.parseJson(projectDir.resolve("package.json"))
    SchemaPrimitives.requirePackageIdentity(
        pkgDoc,
        expectedNpmName = "@zerobias-org/pipeline-template-${segments.joinToString("-")}",
        expectedZerobiasPackage = "${segments.joinToString(".")}.pipeline_template",
        field = "$tag package.json",
    )

    @Suppress("UNCHECKED_CAST")
    val zb = (pkgDoc["zerobias"] ?: pkgDoc["auditmation"]) as? Map<String, Any?>
        ?: throw GradleException("$tag package.json missing 'zerobias' block")
    require(zb["import-artifact"] == "pipeline_template") {
        "$tag zerobias.import-artifact must be 'pipeline_template' (got '${zb["import-artifact"]}')"
    }

    proj.logger.lifecycle("$tag: package=${segments.joinToString("/")}, ${ymls.size} template(s)")
}

// Template ymls of every package: the *.yml files directly in each
// directory under package/ that holds a build.gradle.kts marker.
fun templateYmls(packageDir: File): List<File> =
    packageDir.walkTopDown()
        .onEnter { it.name != "node_modules" }
        .filter { it.name == "build.gradle.kts" }
        .flatMap { marker ->
            (marker.parentFile.listFiles() ?: emptyArray()).asSequence()
                .filter { it.isFile && !it.name.startsWith(".") && (it.extension == "yml" || it.extension == "yaml") }
        }
        .toList()

// ════════════════════════════════════════════════════════════
// :validateUniqueIds — repo-wide cross-cut.
//
// Walks every template yml (see templateYmls), extracts the top-level
// `id` UUID, and fails if two templates share one. The dataloader
// rejects a duplicate within one package, but two packages declaring
// the same id would fight over one catalog row. Wired as a dependency
// of each per-package validateContent so any gate run picks it up.
// ════════════════════════════════════════════════════════════
val validateUniqueIds by tasks.registering {
    group = "verification"
    description = "Fail if two pipeline templates share the same id UUID"

    val packageDir = layout.projectDirectory.dir("package").asFile
    inputs.files(
        fileTree(packageDir) {
            include("**/*.yml", "**/*.yaml", "**/build.gradle.kts")
            exclude("**/node_modules/**")
        }
    )

    doLast {
        val byId = mutableMapOf<String, MutableList<String>>()
        templateYmls(packageDir).forEach { f ->
            val doc = try {
                SchemaPrimitives.parseYaml(f)
            } catch (e: Exception) {
                logger.warn("[validateUniqueIds] skipping unparseable ${f.relativeTo(rootDir)}: ${e.message}")
                return@forEach
            }
            val id = (doc["id"] as? String)?.lowercase() ?: return@forEach
            byId.getOrPut(id) { mutableListOf() }.add(f.relativeTo(rootDir).path)
        }

        val collisions = byId.filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            val report = collisions.entries.joinToString("\n") { (id, paths) ->
                "  $id\n    " + paths.joinToString("\n    ")
            }
            throw GradleException("[validateUniqueIds] duplicate pipeline template ids across the repo:\n$report")
        }
        logger.lifecycle("[validateUniqueIds] ${byId.size} unique pipeline template id(s)")
    }
}

subprojects {
    tasks.matching { it.name == "validateContent" }.configureEach {
        dependsOn(rootProject.tasks.named("validateUniqueIds"))
    }
}

val projectPaths by tasks.registering {
    group = "info"
    description = "Output project-to-directory mappings for tooling (used by zbb CLI)"
    doLast {
        subprojects.filter { it.buildFile.exists() }.forEach { p ->
            println("${p.path}=${p.projectDir.relativeTo(rootDir)}")
        }
    }
}

val changedModules by tasks.registering {
    group = "info"
    description = "List pipeline template packages changed since last version tag"
    doLast {
        val lastTag = try {
            providers.exec {
                commandLine("git", "describe", "--tags", "--abbrev=0")
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            logger.warn("No version tags found -- listing all packages as changed")
            null
        }

        val diffArgs = if (lastTag != null) {
            listOf("git", "diff", "--name-only", lastTag, "HEAD")
        } else {
            listOf("git", "ls-files")
        }

        val result = providers.exec {
            commandLine(diffArgs)
        }.standardOutput.asText.get()

        // A changed file belongs to the nearest enclosing directory with a
        // build.gradle.kts marker (packages may sit deeper than two levels).
        val changed = result.lines()
            .filter { it.startsWith("package/") }
            .mapNotNull { path ->
                generateSequence(file(path).parentFile) { it.parentFile }
                    .takeWhile { it != rootDir && it.name != "package" }
                    .firstOrNull { it.resolve("build.gradle.kts").isFile }
                    ?.relativeTo(file("package"))?.invariantSeparatorsPath
            }
            .distinct()
            .filter { it.contains("/") }

        changed.forEach { println(it) }
    }
}
