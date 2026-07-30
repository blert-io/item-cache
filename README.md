# item-cache

Containerized, self-updating dumper of OSRS item data to two JSON files:

- `extended_items.json`: every item's id, name, tradeability, equipability, stackability,
  bank-note status, weight, equipment slot and combat stats.
- `simple_items.json`: `{id, name}` pairs alone.

## How it works

A RuneLite plugin dumps items from the live client using two data sources:

| Data | Live RuneLite API | Reproduced here from |
| --- | --- | --- |
| Item composition (name, tradeable, stackable, note/placeholder) | `Client.getItemDefinition(id)` | game cache, parsed by RuneLite's `net.runelite:cache` library |
| Equipment stats, weight, slot | `ItemManager.getItemStats(id)` | `https://static.runelite.net/item/stats.ids.min.json` (what `ItemManager` fetches) |

All item definition parsing is done by RuneLite's stable `net.runelite:cache`
library. Its version is pinned to what `https://static.runelite.net/bootstrap.json`
currently advertises, keeping it up-to-date. Equipment stats come from the same
service the live client uses.

At runtime, [`entrypoint.sh`](entrypoint.sh):

1. Asks the [OpenRS2 archive](https://archive.openrs2.org) for the latest live
   OSRS cache and downloads it (skipped if the cache ID is already present in
   the cache volume).
2. Runs the dumper, writing `extended_items.json` and `simple_items.json` into
   `OUTPUT_DIR`.

## Usage

Build the image:

```sh
docker build -t blert-item-cache .
```

Run it, mounting a directory to receive the two files at `/resources`:

```sh
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v /absolute/path/to/output-dir:/resources \
  -v "$HOME/.cache/blert-osrs-cache:/work/cache" \
  blert-item-cache
```

- `-v .../output-dir:/resources`: directory to which to write the outputs,
  `extended_items.json` and `simple_items.json`.
- `--user "$(id -u):$(id -g)"`: writes the files as yourself rather than `root`
  so they stay editable.
- `-v .../cache-dir:/work/cache`: a directory (or named volume) that persists
  the downloaded game cache so an unchanged cache is not re-downloaded (~180 MB)
  on every run. Optional but recommended.

> **If using a cache mount + `--user`**, create the cache directory yourself
> first. If the mount source doesn't exist, Docker creates it owned by `root`,
> and a `--user` container then can't write to it.

### Configuration

Environment variables (all optional):

| Var | Default | Purpose |
| --- | --- | --- |
| `OUTPUT_DIR` | `/resources` | Where the two JSON files are written |
| `CACHE_ROOT` | `/work/cache` | Where downloaded caches are stored (keyed by OpenRS2 id) |
| `STATS_URL` | RuneLite's `stats.ids.min.json` | Override the equipment stats source |
| `OPENRS2_BASE` | `https://archive.openrs2.org` | Override the cache archive |

## Local development (no Docker)

Requires JDK 11 21. The dumper reads an unpacked cache directory
(`main_file_cache.dat2` + `main_file_cache.idx*`):

```sh
cd java
./gradlew shadowJar
java -jar build/libs/item-cache-dumper-1.0-all.jar \
  --cachedir /path/to/unpacked/cache \
  --output-extended extended_items.json \
  --output-simple simple_items.json
```

## Fidelity notes

Validated against a live plugin dump on the same game build (239), producing an
identical item set with identical `id`, `name`, `tradeable`, `stackable`,
`bankNote`, `equipable`, `slot` and `stats` values.

- **Tradeability.** RuneLite's cache `link()` does not propagate the tradeable
  flag through note and bought templates the way the live client does, so
  `ItemDumper` reconstructs it: noted items inherit their base item's flag,
  bought items are never tradeable, and everything else uses its own flag.
  This matches the live client exactly.
