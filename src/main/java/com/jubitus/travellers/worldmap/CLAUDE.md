# Worldmap

Slippy-map tile pyramid: level 0 is 256×256 px at 1 block/px sampled from the world; level L is a pure 2× downsample of L-1, never sampled from the world. 7 levels. **Overworld only** (`MapPyramidStore.isMapped`).

- **One chunk reader for the whole mod** (`ground/chunk/ChunkReader`): it feeds level 0, the terrain grid, campsite and rest spot discovery. A new per-chunk consumer is a call in `ChunkReader.readNow`, never a second queue. It never loads a chunk.
- **`MapChunkPainter` is the only level-0 writer**: reads the `Chunk` directly, shades edges with in-chunk slope only (a chunk never needs its neighbours). Pushes only when a pixel changed.
- Changes are re-read on notice (block-update listener, `PopulateChunkEvent.Post`), not by polling; `MapLoadedChunkResampler` is only a slow safety net.
- Tile compression is lazy (at save on the IO thread, or on first send). Persistence bypasses `WorldSavedData` (plain binary files on a background thread).
- Tile requests only serve the store, never sample the world. Networking: pull unseen tiles, push fresh bytes for held ones (never invalidate-then-repull).
- Client: one GPU texture per tile (`ClientTileTextures`), fog baked in, rebuilt lazily under a per-frame budget, LRU-capped.
- Ultreïa roads are painted into tile pixels on the client (`MapRoadPainter`, before fog), never drawn as an overlay; the network is sent on map open only when its revision changed (`RoadMapSync`).
- Fog of war is per chunk per player (`MapDiscoverySavedData`), revealed on entering a new chunk; distance phrases are computed client-side.
- Unit fog: `ForeignUnitMapStream`/`BattleMapStream` only send what a vision source sees; `GuiTravellerPopulationMap` is exhaustive by design.
- Markers: `GuiWorldMapPanel.Marker.MarkerType` (declaration order = draw order); a new kind = one enum constant + one `WorldMapMarkerFactory` method.
- `/travellersdebug map resweep` loads chunks on purpose, budgeted, and unloads them. `/travellersdebug map stats`.
- Unit markers and strike animations are drawn on one clock, `MapPlaybackClock.CLIENT`, in server time (it follows the server's pace, so low TPS slows the map like the units); every sample or strike is timed by the server tick in its message, never by arrival or wall time.
- Fight animation: strikes are recorded in `MapStrikeLog` only while a map is open and replay at their own tick on that clock, so a shot leaves from where its archer is drawn.
