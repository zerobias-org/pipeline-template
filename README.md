# ZeroBias Pipeline Templates

Open-source **pipeline template** content artifacts under `@zerobias-org`. A
template is a catalog recipe for a pipeline: users find it in the catalog and
add it to one of their boundaries on demand, then fill in what only that
boundary knows (connection, scopes, source objects the template doesn't ship,
schedule). Templates never run by themselves.

This is not the [pipeline](https://github.com/zerobias-org/pipeline) repo: a
`pipeline` artifact is a shared collector pipeline the platform runs directly;
a `pipeline_template` is installed by users and configured per boundary.

## How a template becomes a pipeline

```
package/<vendor>/<product>/*.yml
    │  publish (npm) → release event → dataloader (import-artifact: pipeline_template)
    ▼
catalog.pipeline_template          validated on load, one row per yml, keyed by its id;
    │                              zerobias.catalog-files stored in file-service's shared catalog
    │  portal-api: POST /pipelineTemplateSearch, GET /pipelineTemplates/{id}
    ▼
POST /boundaries/{boundaryId}/pipelines  { pipelineTemplateId, targets, selectedScopes, params, … }
    │  newest collector bot in the template's range; request values override
    │  the template's; params merge shallowly ({...template.params, ...body.params});
    │  each params.requiredFiles copy attached to the pipeline (see Files a template ships)
    ▼
app.pipeline  (linked is_instance_of → template)
```

## Shape (non-standard)

Each template npm package lives at `package/<vendor>/<product>/`, or deeper
(`package/<vendor>/<suite>/<product>/`). The directory names give the npm name
`@zerobias-org/pipeline-template-<vendor>-…-<product>` and the package code
`<vendor>.….<product>.pipeline_template`. A package can hold **many**
templates: every `*.yml` directly under the package root is one template.

```
package/
└── zerobias/
    └── zerobias/
        └── physicalspace/                 # @zerobias-org/pipeline-template-zerobias-zerobias-physicalspace
            ├── package.json               #   zerobias.package = zerobias.zerobias.physicalspace.pipeline_template
            │                              #   zerobias.import-artifact = pipeline_template
            │                              #   dependencies: every collectorArtifact + its semver range
            │                              #   files + zerobias.catalog-files: the files the templates ship
            ├── .npmrc
            ├── build.gradle.kts           # one-line marker: plugins { id("zb.content") }
            ├── gate-stamp.json            # written by ./gradlew :zerobias:zerobias:physicalspace:gate
            ├── floor-plans.yml            # one template per yml; each has its own `id`
            └── files/                     # what floor-plans.yml ships (see Files a template ships)
                ├── floorplan.xlsx
                └── floorplan.webp
bundle/                                    # @zerobias-org/pipeline-template-bundle (workflow-managed)
```

- The artifact type comes only from `zerobias.import-artifact`: the platform
  never reads the npm name, so the `pipeline-template-` prefix is this repo's
  naming convention and can't be mistaken for a `pipeline` artifact.
- The dataloader ignores subdirectories and dotfiles, so keep templates at the
  package root.
- There is **no `deprecated.yml`**: it would be read as a template. Delete a
  template's yml to retire it (the load soft-deletes templates missing from the
  package). A package with no template yml fails the load.
- The package must NOT sit directly at `package/`: the publish workflow's
  `detect` job skips the directory literally named `package`.

## Files a template ships

A template can ship the files its mappings read, so a pipeline created from it
is ready to run. Users overwrite them from the UI.

- **`params.requiredFiles`** lists them, each `{ name, description?, mimeType?, path? }`:
  `name` is the file name the pipeline's copy gets, and `path` the
  package-relative path of the copy the template publishes.
- **Publishing:** list each file in package.json `files` (npm leaves anything
  else out of the package) and in `zerobias.catalog-files`, glob patterns from
  the package root (com/platform `dataloader/src/importer/CatalogFiles.ts`).
  Every load of the template then stores them in file-service's shared
  catalog; a pattern that matches no file is logged as a warning. Keep them in
  a subfolder such as `files/`: only the root `*.yml` files are templates.
- **At create**, the platform copies each `path` into the new pipeline's files
  folder under `name`, attaches it, and links every mapping source that names
  it. A source names the file and its container, never a `fileId`, which the
  platform fills in:
  `source: { file: { name: floorplan.xlsx, containerPath: /File }, objectName: File }`.
- **In mapping expressions**, `$files` holds the pipeline's attached files, as
  the dynamic collector binds it next to `$pipelineId`: keyed by file name,
  each with its current version's `fileId`, `fileVersionId`, `name`, `size` and
  `mimeType`.

The files are part of the package source, so re-gate after changing them.

| Template | Files |
|---|---|
| Floor Plans | [`package/zerobias/zerobias/physicalspace/files/`](package/zerobias/zerobias/physicalspace/README.md#the-files-it-ships): `floorplan.xlsx` (workbook), `floorplan.webp` (drawing) |

## Template fields

The dataloader (`com/platform/dataloader/src/processors/pipelineTemplate/`) is
the source of truth; see its `README.md`.

| Field | Required | Notes |
|---|---|---|
| `id` | yes | new random UUID (`uuidgen`), unique across the repo |
| `name`, `description` | yes | |
| `keywords`, `imageUrl` | no | keywords become search aliases |
| `productId`, `productIds` | at least one product or interface | catalog product(s) the template is for |
| `interface`, `interfaces` | at least one product or interface | interface module(s) by npm name, e.g. `@zerobias-org/module-interface-dataproducer`: the template is also for every product a module implementing one supports, looked up when the template is read. Must be loaded and have a product on the environment, and a caller template's collector must use it. Not a `dependencies` entry |
| `collectorArtifact` | caller: yes | must be a `dependencies` entry of the package |
| `executionMode`, `batchMode`, `connectorType` | yes | |
| `format`, `scopeMode` | no | left unset, the create form asks |
| `frequency`, `dayOfWeek`, `dayOfMonth`, `monthOfYear`, `executionTime`, `timezone` | no | default schedule; `quarterly` is refused |
| `params` | no | default params; never secrets (a secret-looking key with a value fails the load) |
| `links` | no | resource links |

Give both products and interfaces and the template is for all of them. The
create form asks which one when there are several.

`params.dataMappings` (dynamic collector) are checked like the UI's mapping
dialog against the environment's classes: live `classId`/`className`,
destination keys and `fieldId`s that are properties of the class, every
required property mapped, known transform types, JSONata that compiles. Leave
out `context` and each source's `objectId`/`objectPath`/`schemaId`: only a
boundary knows them, and the load refuses them. A source that reads a file the
template ships names it instead (see Files a template ships).

## Build & validate (gradle + zbb)

```bash
# Validate + dataloader integration + write gate-stamp
./gradlew :<vendor>:<product>:gate      # e.g. :zerobias:zerobias:physicalspace:gate

# Cross-cut: ensure no two templates share an id UUID
./gradlew validateUniqueIds

# List discovered gradle projects
./gradlew projectPaths
```

`gate` runs the repo-owned validator (file presence, `import-artifact`,
package identity, unique ids) and `testIntegrationDataloader`, which loads
every template against an ephemeral Neon Postgres branch. Without
`NEON_API_KEY` / `NEON_PROJECT_ID` the dataloader step is skipped locally; CI
re-runs it on push. Templates load from dataloader **2.0.111** on, and a
template that declares `interface` from **2.0.125** on. Commit the refreshed
`gate-stamp.json` whenever package contents change, after staging the files
(the stamp hashes `git ls-files`).

## Publishing

Driven by `zerobias-org/devops/.github/workflows/zbb-publish-reusable.yml`,
triggered on push to `main` / `qa` / `dev` / `uat` (see
`.github/workflows/publish.yml`). The workflow detects the changed package,
single-writer version-bumps on `main`, publishes, refreshes the bundle, and
syncs branches. Do not `npm publish` by hand.

## Authentication

- `ZB_TOKEN` / `NPM_TOKEN` — npm registry access (`package/.npmrc`)
- `NEON_API_KEY` + `NEON_PROJECT_ID` — dataloader integration (sourced from
  vault via `zbb.yaml` in CI)
