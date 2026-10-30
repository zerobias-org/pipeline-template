#!/usr/bin/env bash
# Creates a pipeline from the "OneDrive 365 Floor Plans" template and runs it with curl, for as long as the UI has no
# step for creating a pipeline from a template. See README.md next to this file.
#
# Needs bash, curl and jq, and an API key in the environment:
#   ZB_PLATFORM_URL  e.g. https://ci.zerobias.com/api
#   ZB_API_KEY       an API key of a user of the org that owns the boundary
#   ZB_ORG_ID        that org's id
# Your ids go in a JSON file (CONFIG, default ./pipeline.json; copy pipeline.example.json):
#   boundaryId, boundaryProductId (the boundary's product the workbook is in), connectionId, scopeId (blank: the
#   connection's only scope), workbookPath (ids from "/" to the workbook)
#
#   create-pipeline.sh browse [objectId]  children of an object seen through the connection ("/" by default)
#   create-pipeline.sh connection         the connection's status and scopes
#   create-pipeline.sh body               $OUT/body.json: the template's mappings + your workbook's sheets (no write)
#   create-pipeline.sh create             creates the pipeline in the boundary; its id goes to $OUT/pipeline-id
#   create-pipeline.sh run                runs it once now
#   create-pipeline.sh jobs               its jobs, newest first
#   create-pipeline.sh objects            what the newest job wrote, per class and operation
#   create-pipeline.sh wipe               points every mapping at the blank Empty sheet; `run` then deletes it all
#   create-pipeline.sh restore            points the mappings back at their sheets (as before `wipe`); then `run`
set -euo pipefail

TEMPLATE=${TEMPLATE:-0201bd60-e171-491f-9751-e80be61dd7b3} # OneDrive 365 Floor Plans
NAME=${NAME:-OneDrive 365 Floor Plans}
CONFIG=${CONFIG:-pipeline.json}
OUT=${OUT:-pipeline.out}
: "${ZB_PLATFORM_URL:?set ZB_PLATFORM_URL, e.g. https://ci.zerobias.com/api}" "${ZB_API_KEY:?set ZB_API_KEY}" \
  "${ZB_ORG_ID:?set ZB_ORG_ID}"
mkdir -p "$OUT"

api() { # api METHOD PATH [curl args…]
  local method=$1 path=$2
  shift 2
  curl -sS -m 120 --fail-with-body -X "$method" -H "Authorization: APIKey $ZB_API_KEY" -H "dana-org-id: $ZB_ORG_ID" \
    -H 'Content-Type: application/json' "${ZB_PLATFORM_URL%/}$path" "$@"
}
config() { jq -er "$1" "$CONFIG"; }
items() { jq 'if type == "array" then . else .items end'; }
pipeline_id() { cat "$OUT/pipeline-id"; }
latest_job() { api GET "/platform/app/pipelines/$(pipeline_id)/pipelineJobs" | items | jq -r '.[0].id'; }
scope_id() {
  local scope
  scope=$(jq -r '.scopeId // ""' "$CONFIG")
  [[ -n $scope ]] || scope=$(api GET "/hub/connections/$(config .connectionId)/scopes" | items | jq -r '.[0].id')
  echo "$scope"
}

case ${1:-} in
  browse)
    jq -n --arg o "${2:-/}" '{objectId: $o, pageSize: 100}' |
      api PUT "/hub/targets/$(config .connectionId)/getChildren" --data @- |
      items | jq -r '.[] | [(.objectClass // [] | join(",")), .name, .id] | @tsv'
    ;;
  connection)
    api GET "/hub/connections/$(config .connectionId)" | jq -c '{id, name, status}'
    api GET "/hub/connections/$(config .connectionId)/scopes" | items | jq -c '.[] | {scopeId: .id, name}'
    ;;
  body)
    api GET "/portal/pipelineTemplates/$TEMPLATE" >"$OUT/template.json"
    product=$(api GET "/platform/boundaries/$(config .boundaryId)/products/$(config .boundaryProductId)" | jq -er .productId)
    jq -n --slurpfile template "$OUT/template.json" --slurpfile config "$CONFIG" --arg scope "$(scope_id)" \
      --arg name "$NAME" --arg product "$product" '
      $template[0] as $t | $config[0] as $c
      | ($c.workbookPath[-1] | sub("^item_"; "")) as $workbook
      | {pipelineTemplateId: $t.id, name: $name, boundaryProductId: $c.boundaryProductId,
         targets: {"module-interface-dataproducer": $scope},
         scopeMode: "selected", selectedScopes: {scopes: [$scope]},
         params: {dataMappings: [$t.params.dataMappings[]
           | . + {context: {scopeId: $scope, targetId: $c.connectionId, productId: $product, boundaryId: $c.boundaryId},
                  source: (.source + {objectId: "sheet_\($workbook)/\(.source.objectName)",
                                      objectPath: $c.workbookPath,
                                      schemaId: "schema:collection:microsoft.azure.msgraph.sheet:\($workbook)/\(.source.objectName)"})}]}}
    ' >"$OUT/body.json"
    jq '{pipelineTemplateId, name, boundaryProductId, targets, sheets: [.params.dataMappings[].source.objectName]}' "$OUT/body.json"
    ;;
  create)
    if [[ -s "$OUT/pipeline-id" && -z "${FORCE:-}" ]]; then
      echo "pipeline $(pipeline_id) was already created from here; FORCE=1 makes another" >&2
      exit 1
    fi
    [[ -f "$OUT/body.json" ]] || "$0" body >/dev/null
    api POST "/platform/boundaries/$(config .boundaryId)/pipelines" --data @"$OUT/body.json" >"$OUT/created.json"
    jq -r .id "$OUT/created.json" >"$OUT/pipeline-id"
    jq '{id, name, adminStatus, mappings: (.params.dataMappings | length)}' "$OUT/created.json"
    ;;
  run)
    api POST "/platform/app/pipelines/$(pipeline_id)" --data '{"action":"runOnce"}' >/dev/null
    echo "run requested; follow it with: $0 jobs"
    ;;
  jobs)
    api GET "/platform/app/pipelines/$(pipeline_id)/pipelineJobs" | items |
      jq -c '.[] | {id, status, runDate, duration, batchCount}'
    ;;
  objects)
    job=$(latest_job)
    for page in $(seq 1 100); do
      api GET "/platform/app/pipelineJobs/$job/objectVersions?pageSize=500&pageNumber=$page" | items >"$OUT/versions-$page.json"
      [[ $(jq length "$OUT/versions-$page.json") -eq 500 ]] || break
    done
    jq -s 'add' "$OUT"/versions-*.json >"$OUT/versions.json" && rm "$OUT"/versions-*.json
    jq -c 'group_by([.className, .operation])[] | {class: .[0].className, operation: .[0].operation, count: length}' \
      "$OUT/versions.json"
    ;;
  wipe)
    api GET "/platform/app/pipelines/$(pipeline_id)" | jq '{params}' >"$OUT/params-before-wipe.json"
    jq '.params.dataMappings |= map(.source |= (.objectName = "Empty"
          | .objectId |= sub("/[^/]+$"; "/Empty") | .schemaId |= sub("/[^/]+$"; "/Empty")))' \
      "$OUT/params-before-wipe.json" | api PUT "/platform/app/pipelines/$(pipeline_id)" --data @- >/dev/null
    echo "every mapping now reads the Empty sheet; \`$0 run\` deletes what the pipeline loaded, then \`$0 restore\`"
    ;;
  restore)
    api PUT "/platform/app/pipelines/$(pipeline_id)" --data @"$OUT/params-before-wipe.json" >/dev/null
    echo "mappings read their own sheets again; \`$0 run\` loads the workbook"
    ;;
  *)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
