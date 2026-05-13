# homunculus

Fabric mod (Minecraft 1.21.4, client-only) that exposes a localhost HTTP API for the [`craft`](../craft) Minecraft agent. Single-player and multiplayer both work.

The body to craft's brain — the agent decides what to build; the mod runs the atomic operations and returns structured outcomes.

See [`CLAUDE.md`](./CLAUDE.md) for scope/decisions and [`SPEC.md`](./SPEC.md) for the full wire contract.

## Versions (pinned)

| Component   | Version          |
|-------------|------------------|
| Minecraft   | 1.21.4           |
| Java        | 21               |
| Fabric Loader | 0.19.2         |
| Fabric Loom | 1.16.1           |
| Fabric API  | 0.119.4+1.21.4   |
| Mappings    | Mojmap (`loom.officialMojangMappings()`) |

## HTTP API

Bound to `127.0.0.1:25566` only. No auth; localhost bind is the boundary. All endpoints are synchronous (block until completion or 5s timeout).

| Method | Path        | Body                                                  |
|--------|-------------|-------------------------------------------------------|
| GET    | `/inventory` | —                                                    |
| GET    | `/position`  | —                                                    |
| GET    | `/scan_column` | query: optional `x`, `z` (block coords)            |
| POST   | `/craft`     | `{"item": "minecraft:wooden_pickaxe", "count": 1}`   |
| POST   | `/place`     | `{"item": "minecraft:crafting_table"}`               |
| POST   | `/equip`     | —                                                    |
| POST   | `/smelt`     | `{"input": "minecraft:raw_iron", "count": 1}` (optional `"fuel": "minecraft:coal"`) |
| POST   | `/baritone/mine` | `{"block": "oak_log", "count": 4, "timeout_seconds": 45}` |
| POST   | `/baritone/goto` | `{"x": 12, "y": 64, "z": -7, "timeout_seconds": 60, "arrival_tolerance": 2}` |
| POST   | `/baritone/excavate` | `{"x1": 12, "y1": 62, "z1": -7, "x2": 15, "y2": 64, "z2": -4, "timeout_seconds": 120}` |
| POST   | `/baritone/fill` | `{"block": "cobblestone", "x1": 12, "y1": 62, "z1": -7, "x2": 15, "y2": 62, "z2": -4}` |
| POST   | `/baritone/stop` | —                                                |

`/position` returns the player's world-space `{x, y, z, yaw, pitch}` as doubles (Mojang conventions: yaw 0 = facing +Z, pitch 0 = horizontal). For perception and goto math.

`/scan_column` returns the surface y for a column — the y a player would stand at on top of that column. Optional `x`/`z` query params default to the player's current column. `surface_y` is `null` when the column has no open-sky point (very rare overhangs / all-air column). Returns `out_of_range` if the chunk isn't loaded. Backed by the vanilla `WORLD_SURFACE` heightmap — cheap lookup, not a real scan.

`/craft` covers both 2×2 inventory recipes and 3×3 table recipes. When a recipe is 3×3, the mod looks for a placed `crafting_table` within 4 blocks of the player, opens it, runs the craft, and closes it.

`/place` auto-picks a spot near the player's feet — the LLM agent is effectively blind to look direction, so positional reasoning is in the mod, not the caller. Anti-casing precondition: if fewer than 6 of the 8 adjacent tiles are open (air or replaceable), returns `no_space` ("relocate to open ground") rather than placing into a tight pocket. Otherwise searches ring 2 (2 blocks away) first, falling back to ring 1, picking the first candidate whose target cell is open and whose support block below has a sturdy top face. Sneaks through the call so usable support blocks don't pop GUIs.

`/equip` auto-organizes the hotbar and armor: best sword/axe/pickaxe/shovel/hoe/food into hotbar 0–5 (by material tier, then damage), best building block into hotbar 6 (cheap-first — dirt/cobblestone before diamond_block — then largest stack), best armor into head/chest/legs/feet. Hotbar 7–8 are left for the agent to manage manually.

`/smelt` runs `count` smelts of `input` against a placed furnace within 4 blocks. Pre-loaded items in the furnace count toward available stock — if the furnace already has the input or fuel from a prior smelt, the mod uses what's there and only pushes the shortfall from inventory. Auto-fuel ranking prefers any pre-loaded valid fuel (so we don't pointlessly evict it); otherwise **combines fuels** across tiers (sticks → saplings → planks → logs → charcoal → coal → coal_block → lava_bucket) until the burn budget is covered. The Smelter feeds each fuel type into the fuel slot in sequence, waiting for the slot to drain between types. Blocks for `~10s × count` plus open/close overhead. `count` is hard-capped at 64 per call (furnace input/output slot limits).

`/baritone/*` requires Baritone installed at runtime (we depend on `baritone-api-fabric-1.13.1`). If absent, all five endpoints return `{success: false, reason: "baritone_not_loaded"}` and the rest of the mod is unaffected. `/baritone/mine`, `/goto`, `/excavate`, and `/fill` share a session lock — one in flight at a time, the others return `reason: "busy"`. `/baritone/stop` bypasses the lock so it can interrupt the active call. The `count` field on `/baritone/mine` is **cumulative inventory target** (Baritone's own semantics), not a delta — and we short-circuit to `reason: "already_satisfied"` if the target is already met before invoking Baritone.

`/baritone/excavate` and `/baritone/fill` are axis-aligned-box primitives: excavate clears a box (calls `IBuilderProcess.clearArea`), fill places a block at every air cell in a box (calls `build()` with a `FillSchematic`). Both cap at **500 blocks** of volume — shelter-sized. `excavate` preserves player-placed torches (`buildIgnoreBlocks`). `fill` requires the fill block to already be in the player's hotbar (Baritone won't reach into main inventory) — pair with `/equip` first, or the call returns `missing_block`. Composition for a floored shelter: excavate the volume → equip cobblestone → fill the floor slice (`y1==y2`).

A subtle implementation detail worth mentioning: `BlockOptionalMeta` (Baritone's drop-aware block matcher) deadlocks the render thread if constructed from the game thread on a multiplayer client — its constructor synchronously joins on a registry future that needs the render thread to make progress. We dodge this by constructing BOMs on the HTTP worker thread before the `mine()` call. See `SPEC.md`'s "Off-thread BOM prewarm" for the diagnostic and rationale.

See `SPEC.md` for full request/response schemas including all failure reasons and structured-error fields.

## Quick smoke test

```sh
# Inventory
curl -s http://127.0.0.1:25566/inventory | jq

# Player position + orientation
curl -s http://127.0.0.1:25566/position | jq

# Surface y for the player's current column (or pass ?x=N&z=M)
curl -s 'http://127.0.0.1:25566/scan_column' | jq
curl -s 'http://127.0.0.1:25566/scan_column?x=12&z=-7' | jq

# 2×2 craft (planks from a log)
curl -s -X POST http://127.0.0.1:25566/craft -d '{"item":"minecraft:oak_planks","count":4}' | jq

# Place a block (mod picks the spot — needs ≥6/8 adjacent tiles clear)
curl -s -X POST http://127.0.0.1:25566/place -d '{"item":"minecraft:crafting_table"}' | jq

# 3×3 craft (requires a crafting_table within 4 blocks)
curl -s -X POST http://127.0.0.1:25566/craft -d '{"item":"minecraft:wooden_pickaxe","count":1}' | jq

# Auto-organize hotbar + armor
curl -s -X POST http://127.0.0.1:25566/equip | jq

# Smelt (requires placed furnace within 4 blocks, plus input + fuel in inventory)
curl -s -X POST http://127.0.0.1:25566/smelt -d '{"input":"minecraft:raw_iron","count":1}' | jq

# Baritone: mine 4 cumulative oak_log (returns already_satisfied if you already have ≥4)
curl -s -X POST http://127.0.0.1:25566/baritone/mine -d '{"block":"oak_log","count":4}' | jq

# Baritone: goto a block coord
curl -s -X POST http://127.0.0.1:25566/baritone/goto -d '{"x":12,"y":64,"z":-7}' | jq

# Baritone: clear a shelter-sized box (max 500 blocks volume)
curl -s -X POST http://127.0.0.1:25566/baritone/excavate \
  -d '{"x1":12,"y1":62,"z1":-7,"x2":15,"y2":64,"z2":-4}' | jq

# Baritone: fill a box with a block (block must be in hotbar — /equip first)
curl -s -X POST http://127.0.0.1:25566/baritone/fill \
  -d '{"block":"cobblestone","x1":12,"y1":62,"z1":-7,"x2":15,"y2":62,"z2":-4}' | jq

# Baritone: cancel anything in flight (mine / goto / excavate / fill)
curl -s -X POST http://127.0.0.1:25566/baritone/stop | jq
```

Successful craft response:
```json
{"success": true, "crafted": {"id": "minecraft:wooden_pickaxe", "count": 1}}
```

Structured failure (the agent reads `missing` + `crafting_table_nearby` to plan its next move):
```json
{
  "success": false,
  "reason": "missing_ingredients",
  "missing": [{"id": "minecraft:oak_planks", "count": 3}, {"id": "minecraft:stick", "count": 2}],
  "requires_crafting_table": true,
  "crafting_table_nearby": false,
  "message": "wooden_pickaxe needs 3 more oak_planks and 2 more stick; no crafting table within reach"
}
```

`/equip` response:
```json
{
  "success": true,
  "equipped": {
    "sword": "minecraft:wooden_sword",
    "axe": null,
    "pickaxe": "minecraft:wooden_pickaxe",
    "shovel": null,
    "hoe": null,
    "food": null,
    "building": "minecraft:oak_planks",
    "head": null, "chest": null, "legs": null, "feet": null
  },
  "changes": [
    {"role": "sword",    "from": null, "to": "minecraft:wooden_sword"},
    {"role": "pickaxe",  "from": null, "to": "minecraft:wooden_pickaxe"},
    {"role": "building", "from": null, "to": "minecraft:oak_planks"}
  ],
  "message": "3 change(s): sword=wooden_sword, pickaxe=wooden_pickaxe, building=oak_planks"
}
```

`equipped` reports the post-call contents of every managed slot. Role-slot fields (sword/axe/pickaxe/shovel/hoe/food/building) are `null` if the slot doesn't currently hold a role-matching item; armor fields are `null` if the slot is empty. Hotbar slots 7 and 8 are not auto-managed — they're reserved for whatever the agent wants to keep equipped (torches, water bucket, etc.). Ranking rules and the curated building-block tier list live in `SPEC.md`.

Successful `/smelt` response:
```json
{
  "success": true,
  "smelted": {"id": "minecraft:iron_ingot", "count": 3},
  "fuel_consumed": [{"id": "minecraft:coal", "count": 1}]
}
```

`fuel_consumed` is a list — single-fuel calls are one entry, multi-fuel calls (e.g. `1×charcoal + 2×planks` to cover a 10-smelt budget) have one entry per type, ordered cheap → expensive.

`/smelt` failure (same shape as `/craft`'s structured errors — agent reads `missing` + `furnace_nearby` to plan):
```json
{
  "success": false,
  "reason": "missing_fuel",
  "missing": [{"id": "minecraft:coal", "count": 1}],
  "requires_furnace": true,
  "furnace_nearby": false,
  "message": "no usable fuel in inventory (need 600 burn ticks for 3 smelts); no furnace within reach"
}
```

`reason` is one of `missing_input`, `missing_fuel`, `requires_furnace`, `no_recipe`, `unknown_item`, `internal_error`. `requires_furnace` and `furnace_nearby` are always populated.

## Limitations (v1)

- **Recipes must be unlocked.** Recipe data is sourced from `ClientRecipeBook`, which only contains recipes the player has unlocked. In vanilla (SP and MP), picking up an ingredient unlocks the recipes that use it, so an agent that has the ingredients for a craft will normally already have the recipe. The unlock chain can lag by one craft: e.g. on a fresh MP join with only `oak_log`, the first call to craft `wooden_pickaxe` may return `no_recipe` until `oak_planks` and `stick` have been crafted (which unlocks tool recipes downstream). Retrying after the prerequisite craft succeeds. Edge case: server with non-default unlock rules + items granted without pickup → also `no_recipe`. The agent's world knowledge of vanilla recipes is enough to recover from either by attacking ingredients first.
- **No NBT / components.** Damaged tools, enchanted items, dyed armor, named blocks all appear identical to pristine versions. Inventory matching is item-id only.
- **Atomic is best-effort.** A craft that crashes mid-batch may leave ingredients partially consumed.
- **No automatic positioning.** `/place` doesn't search for a "good spot" — the agent (or Baritone) puts the player where something placeable is in the crosshair.
- **`/smelt` evicts mismatched furnace contents on success.** If the furnace has items that don't match the plan (wrong input item, non-matching fuel, leftover output of a different smelt), they get shift-clicked back to player inventory before the call's smelt proceeds. On *validation* failure (e.g. `missing_input` even after factoring in furnace contents) the furnace is left untouched. On any post-validation failure the cleanup still drains the slots — anything left in input/fuel/result lands in player inventory, not in the furnace, so a partial/aborted smelt won't strand items.
- **`/smelt` smoker / blast_furnace deferred.** Only vanilla `furnace` is detected by the proximity check. Smokers (food, ~5s/cook) and blast furnaces (ores, ~5s/cook) work mechanically the same but use different recipe types in `ClientRecipeBook`. TODO: scan for all three block types and route the recipe lookup accordingly.

## Dev loop

```sh
./gradlew runClient    # launch dev MC with the mod loaded
./gradlew build        # produce build/libs/homunculus-<version>.jar
bash move_to_instance.sh   # copy the built jar into the PrismLauncher 1.21.4 instance
```

Primary loop is manual curl + watching the game. No automated test framework in v1.

## Status

v1 functionally complete: `/inventory`, `/position`, `/scan_column`, `/craft` (2×2 and 3×3), `/place`, `/equip`, `/smelt`, `/baritone/mine`, `/baritone/goto`, `/baritone/excavate`, `/baritone/fill`, `/baritone/stop` all working end-to-end on both single-player and multiplayer. SP and MP share one code path — recipes are sourced from `ClientRecipeBook`, the same display-side API the in-game recipe book screen uses (it carries furnace recipes alongside crafting-grid ones). Baritone endpoints depend on the API jar at runtime; if Baritone isn't installed, those five return `baritone_not_loaded` and the rest of the mod is unaffected.
