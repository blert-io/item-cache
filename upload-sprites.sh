#!/usr/bin/env bash
# Uploads sprites rendered by the dumper to an S3-compatible bucket.
#
# Only sprites whose pixels differ from the bucket's copy of manifest.json are
# uploaded. The manifest is replaced last, so an interrupted upload is retried
# by the next run. Sprites are never deleted.
#
# Usage: upload-sprites.sh <sprites dir>
set -euo pipefail

SPRITES_DIR="${1:?usage: upload-sprites.sh <sprites dir>}"
: "${SPRITES_BUCKET:?SPRITES_BUCKET must be set}"
PREFIX="${SPRITES_PREFIX:+${SPRITES_PREFIX%/}/}"
DEST="s3://$SPRITES_BUCKET/$PREFIX"
CACHE_CONTROL="${SPRITES_CACHE_CONTROL:-public, max-age=86400}"
MAX_CHANGED="${SPRITES_MAX_CHANGED:-500}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
if [[ -n "${SPRITES_CDN_ENDPOINT_ID:-}" ]]; then
  : "${DIGITALOCEAN_ACCESS_TOKEN:?DIGITALOCEAN_ACCESS_TOKEN must be set to purge the CDN}"
fi

log() { echo "[item-cache] $*"; }
s5() { s5cmd --numworkers "${SPRITES_NUMWORKERS:-32}" "$@"; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if ! s5 cp "${DEST}manifest.json" "$tmp/remote.json" >/dev/null 2>"$tmp/err"; then
  if ! grep -q NoSuchKey "$tmp/err"; then
    cat "$tmp/err" >&2
    log "ERROR: could not fetch ${DEST}manifest.json." >&2
    exit 1
  fi
  log "No manifest at $DEST; uploading all sprites."
  echo '{}' > "$tmp/remote.json"
fi

jq -r --slurpfile remote "$tmp/remote.json" '
  to_entries[]
  | $remote[0][.key] as $old
  | select($old != .value)
  | "\(if $old == null then "new" else "changed" end) \(.key)"
' "$SPRITES_DIR/manifest.json" > "$tmp/diff"

new=$(grep -c '^new ' "$tmp/diff" || true)
changed=$(grep -c '^changed ' "$tmp/diff" || true)
log "$new new and $changed changed sprites."

if [[ ! -s "$tmp/diff" ]]; then
  exit 0
fi

if (( changed > MAX_CHANGED )) && [[ "${SPRITES_FORCE:-}" != 1 ]]; then
  log "ERROR: $changed existing sprites changed, more than SPRITES_MAX_CHANGED=$MAX_CHANGED." >&2
  log "       Check the rendered sprites, then set SPRITES_FORCE=1 to upload them." >&2
  exit 1
fi

while read -r _ file; do
  printf 'cp --acl public-read --content-type image/png --cache-control "%s" "%s" "%s"\n' \
    "$CACHE_CONTROL" "$SPRITES_DIR/$file" "$DEST$file"
done < "$tmp/diff" > "$tmp/commands"
s5 run "$tmp/commands" >/dev/null
log "Uploaded $((new + changed)) sprites to $DEST."

awk -v prefix="$PREFIX" '$1 == "changed" { print prefix $2 }' "$tmp/diff" > "$tmp/purge"
if [[ -s "$tmp/purge" ]]; then
  if [[ -z "${SPRITES_CDN_ENDPOINT_ID:-}" ]]; then
    log "CDN purge skipped (SPRITES_CDN_ENDPOINT_ID not set)."
  else
    if (( changed > 1000 )); then
      echo "${PREFIX}*" > "$tmp/purge"
    fi
    split -l 100 "$tmp/purge" "$tmp/purge-"
    for batch in "$tmp"/purge-*; do
      jq -R . "$batch" | jq -s '{files: .}' | curl -fsS -X DELETE \
        -H "Authorization: Bearer $DIGITALOCEAN_ACCESS_TOKEN" \
        -H "Content-Type: application/json" \
        --data @- \
        "https://api.digitalocean.com/v2/cdn/endpoints/$SPRITES_CDN_ENDPOINT_ID/cache"
    done
    log "Purged $changed changed sprites from the CDN."
  fi
fi

s5 cp --content-type application/json --cache-control no-cache \
  "$SPRITES_DIR/manifest.json" "${DEST}manifest.json" >/dev/null
log "Updated ${DEST}manifest.json."
