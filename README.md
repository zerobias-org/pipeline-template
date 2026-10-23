# ZeroBias Pipeline Templates

Open-source **pipeline template** content artifacts under `@zerobias-org`. A
template is a catalog recipe for a pipeline: users find it in the catalog and
add it to one of their boundaries on demand, then fill in what only that
boundary knows (connection, scopes, workbook or other source objects,
schedule). Templates never run by themselves.

This is not the [pipeline](https://github.com/zerobias-org/pipeline) repo: a
`pipeline` artifact is a shared collector pipeline the platform runs directly;
a `pipeline_template` is installed by users and configured per boundary.

## How a template becomes a pipeline

```
package/<vendor>/<product>/*.yml
    │  publish (npm) → release event → dataloader (import-artifact: pipeline_template)
    ▼
catalog.pipeline_template          validated on load, one row per yml, keyed by its id
    │  portal-api: POST /pipelineTemplateSearch, GET /pipelineTemplates/{id}
    ▼
POST /boundaries/{boundaryId}/pipelines  { pipelineTemplateId, targets, selectedScopes, params, … }
    │  newest collector bot in the template's range; request values override
    │  the template's; params merge shallowly ({...template.params, ...body.params})
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
            ├── .npmrc
            ├── build.gradle.kts           # one-line marker: plugins { id("zb.content") }
            ├── gate-stamp.json            # written by ./gradlew :zerobias:zerobias:physicalspace:gate
            └── onedrive-floor-plans.yml   # one template per yml; each has its own `id`
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

## Template fields

The dataloader (`com/platform/dataloader/src/processors/pipelineTemplate/`) is
the source of truth; see its `README.md`.

| Field | Required | Notes |
|---|---|---|
| `id` | yes | new random UUID (`uuidgen`), unique across the repo |
| `name`, `description` | yes | |
| `keywords`, `imageUrl` | no | keywords become search aliases |
| `productId` | yes | a catalog product |
| `collectorArtifact` | caller: yes | must be a `dependencies` entry of the package |
| `executionMode`, `batchMode`, `connectorType` | yes | |
| `format`, `scopeMode` | no | left unset, the create form asks |
| `frequency`, `dayOfWeek`, `dayOfMonth`, `monthOfYear`, `executionTime`, `timezone` | no | default schedule; `quarterly` is refused |
| `params` | no | default params; never secrets (a secret-looking key with a value fails the load) |
| `links` | no | resource links |

`params.dataMappings` (dynamic collector) are checked like the UI's mapping
dialog against the environment's classes: live `classId`/`className`,
destination keys and `fieldId`s that are properties of the class, every
required property mapped, known transform types, JSONata that compiles. Leave
out `context` and each source's `objectId`/`objectPath`/`schemaId`: only a
boundary knows them, and the load refuses them.

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
re-runs it on push. Templates load from dataloader **2.0.111** on. Commit the
refreshed `gate-stamp.json` whenever package contents change, after staging
the files (the stamp hashes `git ls-files`).

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
