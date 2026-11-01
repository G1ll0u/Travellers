**Status: steps 1–2 done (data only, nothing visible yet); step 3 next.**

# Worldmap biome colours

## Goal

Players tell biomes apart on the worldmap. The sepia parchment stays; land is lightly tinted by biome family, like a hand-coloured old map. Scan cost stays negligible.

## Decisions taken with the user

| Topic | Decision |
|---|---|
| Trees | Done separately: relief from the ground under the canopy, foliage a slightly darker tone. |
| Resolution | One biome family per chunk, blended smoothly between chunks on the client. |
| Colour | Light tint over the sepia, never flat colours. Water keeps its current look (no tint). |
| Families | The 11 below, from Forge's `BiomeDictionary` types, instead of exact biome colours (harmonious palette, works with modded biomes). |
| Strength | Player option, 0–100 % (0 = plain sepia map as before). Default tuned in game. |
| Existing maps | No backfill: colour comes with chunk re-reads or `resweep`. |

## Design

- **Storage: a 256-byte trailer on every tile**, after the 65,536 tone bytes: one family byte per 16×16-pixel cell (at level 0, one chunk). Store, file, compression, network and client cache keep carrying one `byte[]` per tile, unchanged.
- **Old tiles stay readable**: inflating an old tile to the new length leaves the trailer at 0, which means "unknown, no tint". No migration. Every length check that means "tone part" compares against the tone size, not the tile size (legacy migration, hole census).
- **Family of a chunk**: majority over the chunk's own biome array (already in memory, no world access). Biome → family through Forge's `BiomeDictionary` types (so modded biomes work), cached per biome id. Family ids are persisted: append only, never renumber.
- **Zoom levels**: a parent cell is the majority of its 2×2 child cells (unknown excluded), next to the tone downsample.
- **Client**: each pixel blends the tints of the 4 nearest cells (bilinear), then mixes that tint into its sepia colour at the player's chosen strength (client config; changing it rebuilds the textures). Unknown family = no tint. Water pixels untouched. Roads and fog are painted after, as now.
- **Existing worlds**: the colour appears as chunks are read again (any chunk load), or all at once with `/travellersdebug map resweep`. No backfill job in v1.

## Families

Plains/meadow, forest, conifer (taiga), jungle, swamp, desert/beach, savanna, mesa, snow/ice, mountain, mushroom. Ocean/river: none (water already drawn).

## Steps

1. Server: family table + chunk majority in `MapChunkPainter`, trailer written with the tones, change detection includes it. Tests: majority, trailer survives `writeChunk`. — done
2. Pyramid: trailer downsample (majority) in `MapTileGenerator.downsample`; tone-length checks fixed. Test: 2×2 majority, unknown ignored. — done
3. Client: palette + bilinear tint in `ClientTileTextures.build`, strength option in `TravellersConfig.WORLDMAP`. — todo
4. In-game tuning of palette and strength with the user. — todo
