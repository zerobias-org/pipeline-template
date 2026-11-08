# ZeroBias Physical Space Pipeline Templates

Pipeline templates that load physical space data (floor plans, spaces, openings, assets, objects). One template per `*.yml` in this directory:

| File | Template | Collector |
|---|---|---|
| `floor-plans.yml` | Floor Plans | `@zerobias-org/collectorbot-zerobias-zerobias-dynamic` |

`floor-plans.yml` is for every product of the dataproducer interface (`interface: "@zerobias-org/module-interface-dataproducer"`), so a pipeline created from it names the boundary product it is for. It needs the Drawable/Canvas model of the base schema (auditlogic/schema PR #228) on the environment, and a dataloader that reads `interface` (zerobias-com/platform#226).

**Ids are unique to the pipeline.** Every mapped id, and every reference to one (a floor plan's `file`, the `placedIn` links, a space's `canvases` and an opening's `connects`), is prefixed with the pipeline's id: `$pipelineId & "/" & id`, so `plan-a/space/hall` is stored as `<pipelineId>/plan-a/space/hall`. The platform's linker resolves a reference by id across the whole org, so without the prefix every pipeline that loads the same workbook would link its records to every other's. `$pipelineId` is bound by the dynamic collector, so a pipeline needs collector 1.1.2 or later: without it, JSONata reads the variable as an empty string and ids come out as `/plan-a/...`.

Sample workbook, how to prepare your own and how to create the pipeline through the API, with the workbook in
OneDrive 365: [`examples/floor-plans/`](examples/floor-plans/README.md).
