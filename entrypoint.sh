#!/usr/bin/env bash
# OSRS item cache dump. Downloads the latest live cache from the OpenRS2
# archive, then runs the RuneLite cache-based dumper to (re)generate
# $OUTPUT_DIR/extended_items.json and $OUTPUT_DIR/simple_items.json.
set -euo pipefail

OUTPUT_DIR="${OUTPUT_DIR:-/resources}"
CACHE_ROOT="${CACHE_ROOT:-/work/cache}"
JAR="${JAR:-/app/item-cache-dumper.jar}"
OPENRS2_BASE="${OPENRS2_BASE:-https://archive.openrs2.org}"

log() { echo "[item-cache] $*"; }

if ! { mkdir -p "$OUTPUT_DIR" 2>/dev/null && [ -w "$OUTPUT_DIR" ]; }; then
  log "ERROR: output dir '$OUTPUT_DIR' is not writable by uid $(id -u):$(id -g)." >&2
  log "       (e.g. run with --user \"\$(id -u):\$(id -g)\" so files are owned by you)." >&2
  exit 1
fi

# If the cache dir isn't writable, use a throwaway location.
if ! { mkdir -p "$CACHE_ROOT" 2>/dev/null && [ -w "$CACHE_ROOT" ]; }; then
  fallback="$(mktemp -d)"
  log "WARNING: cache dir '$CACHE_ROOT' is not writable by uid $(id -u); using '$fallback' instead."
  log "         The game cache will be re-downloaded on every run. To persist it, mount a"
  log "         directory you own at /work/cache (create it first so it isn't root-owned)."
  CACHE_ROOT="$fallback"
fi

log "Querying OpenRS2 for the latest live OSRS cache..."
CACHE_ID=$(curl -fsS "$OPENRS2_BASE/caches.json" | jq -r '
  [ .[]
    | select(.game == "oldschool" and .scope == "runescape"
             and .environment == "live" and .disk_store_valid == true
             and .timestamp != null) ]
  | max_by(.timestamp) | .id')

if [[ -z "$CACHE_ID" || "$CACHE_ID" == "null" ]]; then
  log "ERROR: could not determine the latest cache id from OpenRS2." >&2
  exit 1
fi

CACHE_DIR="$CACHE_ROOT/$CACHE_ID"
if [[ -f "$CACHE_DIR/main_file_cache.dat2" ]]; then
  log "Cache $CACHE_ID already downloaded; reusing $CACHE_DIR."
else
  log "Downloading cache $CACHE_ID (this is ~180 MB)..."
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL "$OPENRS2_BASE/caches/runescape/$CACHE_ID/disk.zip" -o "$tmp/disk.zip"
  rm -rf "$CACHE_DIR"
  mkdir -p "$CACHE_DIR"
  # -j flattens the archive's cache/ directory so main_file_cache.* land directly
  # in CACHE_DIR, which is what net.runelite.cache.fs.Store expects.
  unzip -j -o -q "$tmp/disk.zip" -d "$CACHE_DIR"
  rm -rf "$tmp"
  trap - EXIT
fi

stats_arg=()
if [[ -n "${STATS_URL:-}" ]]; then
  stats_arg=(--stats-url "$STATS_URL")
fi

log "Dumping items for cache $CACHE_ID..."
java -Xmx2g -jar "$JAR" \
  --cachedir "$CACHE_DIR" \
  --out-extended "$OUTPUT_DIR/extended_items.json" \
  --out-simple "$OUTPUT_DIR/simple_items.json" \
  "${stats_arg[@]}"

log "Done. Updated:"
log "  $OUTPUT_DIR/extended_items.json"
log "  $OUTPUT_DIR/simple_items.json"
