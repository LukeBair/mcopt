# Integrated-server performance (perf-server)

All numbers below were **measured with other clients running** on the same machine (up to 3 others), so separate runs are noisy
(spread between identical runs ~0.3-0.4 ms on ~7.5 ms). Nothing here is in `indie.properties`: the only controlled measurement is the
in-run paired one at the bottom, and the per-switch splits were not finished.

## Bench harness (`-Dmcopt.server.bench=N`, measurement only)
`mcopt.metal.server.ServerBench`: after a player joins it builds an arena at y=200 (N villagers, N cows, N chickens, N/2 husks,
N/4 iron golems, boats/shulkers/minecarts, a ring of hoppers with 150 items, a wheat field, water), warms up 150 ticks and times
`MinecraftServer.tickServer` (wall and server-thread CPU). `-Dmcopt.server.benchTicks=M` sets the window (default 1000).
`-Dmcopt.server.benchToggle=true` flips every server switch below on/off every 100 ticks inside one run (paired A/B, immune to
other clients' load). Launch: `./gradlew :metal:runClient -PmcoptLabel="..." -PmcoptArgs="-Dmcopt.server.bench=100 ..." --args="--quickPlaySingleplayer <world copy in metal/run/saves>"`
with `pauseOnLostFocus:false` in `metal/run/options.txt`.

## Switches (all opt-in, behaviour-exact, each with a verify mode)
| flag | what | verify result |
|---|---|---|
| `mcopt.server.collide=true\|verify` | `BlockCollisions` iterator: incremental x/y/z walk instead of Cursor3D div/mod; no block-state read in air-only sections | 114k skipped positions and all walk positions identical, 0 mismatches |
| `mcopt.server.entityIndex=true\|verify` | entity queries find the sections of the box by direct lookup (in the AVL walk's order) instead of walking the x-slab; `EntitySection` scan by index | 1.0M queries, same section order, 0 mismatches |
| `mcopt.server.collideIndex=true\|verify` | `getEntityCollisions` visits only entities of classes overriding `canBeCollidedWith` (boats, minecarts, shulkers, ...), via a second per-section list; sources overriding `canCollideWith` take vanilla's path | 366k queries incl. boats/shulkers/minecarts in the scene, 0 mismatches |

## MSPT (100 per kind, ~450-480 entities; baseline flags poi, ticks, chunkGet, lightMap on)
- No chunk flags at all (profile none), 1 run: 7.67 ms mean. With the four existing server flags: 6.55, 6.78, 7.51, 7.85 (varies with machine load).
- Separate runs, wall mean, collide+entityIndex: A 6.55 / 6.78, B 6.49 / 6.58 (inconclusive).
- Separate runs, CPU mean, all three: A 7.46 / 7.82, B 6.93 / 7.75 (inconclusive; spread larger than difference).
- Paired in-run toggle, all three switches, 4000 ticks: on 7.973 ms CPU vs off 8.303 ms = **-4.0%** (one run; per-switch toggles were started but not completed).
- Smaller scene (20 per kind, 175 entities, no flags): 3.50 ms mean, p95 5.55.

Conclusion: ~4% in a crowded-mob scene, one measurement. Not added to indie.properties (needs more paired runs on a quiet machine:
`benchToggle` with each switch alone).

## Profile findings (JFR, 100 per kind, server thread)
Entity tick 74%; of it Brain ~30% (AcquirePoi dominates; already covered by `chunk.poi`), `Entity.move`/collisions ~18%, `Level.getEntities`
~10% (crowded sections: the O(n^2) cost is inherent per query), natural spawning ~7% (`getRandomSpawnMobAt`: biome lookup + structure checks),
chunk cache `ServerChunkCache.getChunk` ~5% self.

## Future ideas, prioritized
1. **Run the per-switch paired toggles** and put the winners in indie.
2. **Chunk lookup**: `Level.getBlockState` goes through `ServerChunkCache.getChunk` (Thread check, profiler counter, 4-entry scan) ~100 times per mob per tick. A per-level last-chunk cache cleared with `clearCache()` would cut it; not exact when vanilla's 4-entry cache would miss (a miss adds an UNKNOWN ticket), so opt-in. Misses themselves are rare (~8 samples).
3. **Natural spawner**: cache biome/structure results per chunk per tick in `getRandomSpawnMobAt`.
4. **Pushing** (`LivingEntity.pushEntities`, 2.7%): same section scan, cannot skip classes (isPushable is per instance); a per-section pushable list would work.
5. **Brain**: `getRunningBehaviors` allocates and walks every behavior twice per tick; `Behavior.hasRequiredMemories` iterates an entry map per behavior. Exact fixes need running-set bookkeeping; small (~3%).
6. **Light engine**: 26.3's `LightEngine` is already queue based (not the old DynamicGraph); the bench has no chunk-loading phase so it was not profiled. Needs a load-heavy scene (fly through saved chunks).
7. **Hoppers / item merging, redstone, pathfinding nodes**: present in the scene (hopper ring, 150 items) but each under 2% in the profile; not attacked.
