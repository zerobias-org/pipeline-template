# ZeroBias Physical Space Pipeline Templates

Pipeline templates that load physical space data (floor plans, spaces, openings, assets, objects). One template per `*.yml` in this directory:

| File | Template | Collector |
|---|---|---|
| `onedrive-floor-plans.yml` | OneDrive 365 Floor Plans | `@zerobias-org/collectorbot-zerobias-zerobias-dynamic` |

`onedrive-floor-plans.yml` is for every product of the dataproducer interface (`interface: "@zerobias-org/module-interface-dataproducer"`), so a pipeline created from it names the boundary product it is for. It needs the Drawable/Canvas model of the base schema (auditlogic/schema PR #228) on the environment, and a dataloader that reads `interface` (zerobias-com/platform#226).

Sample workbook, how to prepare your own and how to create the pipeline through the API:
[`examples/onedrive-floor-plans/`](examples/onedrive-floor-plans/README.md).
