# ZeroBias Physical Space Pipeline Templates

Pipeline templates that load physical space data (floor plans, spaces, openings, assets, objects). One template per `*.yml` in this directory:

| File | Template | Collector |
|---|---|---|
| `onedrive-floor-plans.yml` | OneDrive 365 Floor Plans | `@zerobias-org/collectorbot-zerobias-zerobias-dynamic` |

`onedrive-floor-plans.yml` needs the Drawable/Canvas model of the base schema (auditlogic/schema PR #228) on the environment.

Sample workbook, how to prepare your own and how to create the pipeline through the API:
[`examples/onedrive-floor-plans/`](examples/onedrive-floor-plans/README.md).
