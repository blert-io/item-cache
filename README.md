# item-cache

Containerized, self-updating dumper of OSRS item data to two JSON files:

- `extended_items.json`: every item's id, name, tradeability, equipability, stackability,
  bank-note status, weight, equipment slot and combat stats.
- `simple_items.json`: `{id, name}` pairs alone.

Optionally, it also renders every item's inventory sprite (see [Sprites](#sprites)).

## How it works

A RuneLite plugin dumps items from the live client using two data sources:

| Data | Live RuneLite API | Reproduced here from |
| --- | --- | --- |
| Item composition (name, tradeable, stackable, note/placeholder) | `Client.getItemDefinition(id)` | game cache, parsed by RuneLite's `net.runelite:cache` library |
| Equipment stats, weight, slot | `ItemManager.getItemStats(id)` | `https://static.runelite.net/item/stats.ids.min.json` (what `ItemManager` fetches) |
| Item sprites | `ItemManager.getImage(...)` | game cache, rendered by `ItemSpriteFactory` from `net.runelite:cache` |

All item definition parsing is done by RuneLite's stable `net.runelite:cache`
library. Its version is pinned to what `https://static.runelite.net/bootstrap.json`
currently advertises, keeping it up-to-date. Equipment stats come from the same
service the live client uses.

At runtime, [`entrypoint.sh`](entrypoint.sh):

1. Asks the [OpenRS2 archive](https://archive.openrs2.org) for the latest live
   OSRS cache and downloads it (skipped if the cache ID is already present in
   the cache volume).
2. Runs the dumper, writing `extended_items.json` and `simple_items.json` into
   `OUTPUT_DIR`. If `SPRITES_DIR` is set, it also renders sprites into
   `SPRITES_DIR` and writes `item_sprites.json` into `OUTPUT_DIR`.
3. If `SPRITES_BUCKET` is set, uploads the sprites with
   [`upload-sprites.sh`](upload-sprites.sh)
   (see [Uploading sprites](#uploading-sprites)).

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

To also render sprites, mount a directory for them and set `SPRITES_DIR`:

```sh
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v /absolute/path/to/output-dir:/resources \
  -v /absolute/path/to/sprites-dir:/sprites \
  -v "$HOME/.cache/blert-osrs-cache:/work/cache" \
  -e SPRITES_DIR=/sprites \
  blert-item-cache
```

### Configuration

Environment variables (all optional):

| Var | Default | Purpose |
| --- | --- | --- |
| `OUTPUT_DIR` | `/resources` | Where the JSON files are written |
| `SPRITES_DIR` | unset | Ouptut directory for rendered sprites. Sprites and `item_sprites.json` are skipped if unset, unless `SPRITES_BUCKET` is set, in which case a temporary directory is used |
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
  --out-extended extended_items.json \
  --out-simple simple_items.json \
  --out-sprites sprites \
  --out-item-sprites item_sprites.json
```

`--out-sprites` and `--out-item-sprites` are optional. `--out-item-sprites`
requires `--out-sprites`.

## Sprites

Sprites are 36x32 PNGs, rendered with the a black outline and no drop shadow.
A run produces the following files:

- `<id>.png`: the item's sprite.
- `<id>_<threshold>.png`: the item's sprite for stacks of at least
  `<threshold>`, for items whose sprite changes with quantity.
- `manifest.json`: maps each PNG to a SHA-256 hash of its pixels.

`item_sprites.json` lists the stack thresholds of every item that has them,
in ascending order:

```json
{
  "882": {"stackThresholds":[2,3,4,5]},
  "995": {"stackThresholds":[2,3,4,5,25,100,250,1000,10000]}
}
```

### Uploading sprites

When `SPRITES_BUCKET` is set, the container uploads sprites to an
S3-compatible bucket with [s5cmd](https://github.com/peak/s5cmd) after the
dump. It compares the rendered `manifest.json` with the copy in the bucket,
uploads only new and changed sprites as public `image/png` objects, optionally
purges changed ones from a CDN, and replaces the bucket's manifest. Sprites
are never deleted from the bucket.

`SPRITES_MAX_CHANGED` can be set as an optional sanity check, causing the upload
to fail if more than the configured number of sprites change. `SPRITES_FORCE=1`
bypasses this, for example following a manual inspection.

| Var | Default | Purpose |
| --- | --- | --- |
| `SPRITES_BUCKET` | unset | Bucket to upload to. Enables uploading |
| `SPRITES_PREFIX` | unset | Path within the bucket, e.g. `items` |
| `S3_ENDPOINT_URL` | AWS | S3 endpoint |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | unset | Bucket credentials |
| `AWS_REGION` | `us-east-1` | Bucket region |
| `SPRITES_CACHE_CONTROL` | `public, max-age=86400` | `Cache-Control` of uploaded sprites |
| `SPRITES_MAX_CHANGED` | `500` | Most existing sprites that may change without `SPRITES_FORCE=1` |
| `SPRITES_FORCE` | unset | Set to `1` to upload past `SPRITES_MAX_CHANGED` |
| `SPRITES_NUMWORKERS` | `32` | Controls parallel uploads |

## Fidelity notes

Validated against a live plugin dump on the same game build (239), producing an
identical item set with identical `id`, `name`, `tradeable`, `stackable`,
`bankNote`, `equipable`, `slot` and `stats` values.

- **Tradeability.** RuneLite's cache `link()` does not propagate the tradeable
  flag through note and bought templates the way the live client does, so
  `ItemDumper` reconstructs it: noted items inherit their base item's flag,
  bought items are never tradeable, and everything else uses its own flag.
  This matches the live client exactly.
