# homunculus v1 API spec

Companion to `CLAUDE.md`. Specifies the wire-level API contract for v1. Implementation choices (which mojmap class, which packet) are the implementer's call as long as the contract holds.

## Architecture: separation of concerns

- **homunculus owns atomic operations and recipe ground truth.** Each endpoint either succeeds or fails with rich, structured error data. The mod knows what a recipe requires (via vanilla `RecipeManager`); it does not know what the agent is ultimately trying to build.
- **`craft` (the Python agent) owns sequential planning.** When a craft fails, the agent reads the structured error and decides what to do next: mine more wood, craft sub-ingredients, place a table, retry. The mod never sequences — the agent does.

This split lets the agent recursively descend any recipe DAG without needing recipe memory in its prompt context. The mod is the source of truth; the agent is the reactive planner.

## Endpoints

### `GET /inventory` *(implemented)*

Returns current player inventory. Sparse — only occupied slots returned. Schema locked:

```json
{
  "main": [{"slot": 0, "id": "minecraft:dirt", "count": 1}],
  "armor": {"feet": null, "legs": null, "chest": null, "head": null},
  "offhand": null,
  "selected_slot": 0
}
```

Items expose `id` and `count` only — no NBT / components / durability in v1. Damaged tools and enchanted items appear identical to pristine versions. Known limitation, deferred.

### `GET /position` *(implemented)*

Returns the player's world-space position and orientation. No body.

**Success response:**
```json
{
  "x": 12.5,
  "y": 64.0,
  "z": -7.3,
  "yaw": 180.0,
  "pitch": 0.0
}
```

All values are doubles. Coordinates are world-absolute (block-fractional, not block-aligned). Yaw/pitch follow Mojang conventions: yaw 0 = facing +Z (south), increases clockwise; pitch 0 = horizontal, +90 = looking straight down.

**Failure response:** standard `{success: false, reason, message}`. `reason` is one of:
- `bad_request` — wrong HTTP method (e.g. POST to a GET endpoint). 4xx.
- `internal_error` — no player (title screen, mid-respawn) or anything unexpected. 5xx.

### `GET /scan_column` *(implemented)*

For a given (x, z) column, returns the surface-y — the y a player would stand at to be on top of the column with open sky above. Used by the agent's `surface` recovery primitive (escape from a cave or self-dug pit) and by any other "where's daylight" decision.

**Query params (both optional):**
- `x` (integer) — block-aligned column x. Default: `floor(player.x)`.
- `z` (integer) — block-aligned column z. Default: `floor(player.z)`.

**Semantics.** `surface_y` is the topmost y the player can stand at with unbroken air above. Equivalent to vanilla `Heightmap.Types.WORLD_SURFACE.getFirstAvailable(x, z)` for the queried column. (The heightmap is precomputed per-chunk; this is a cheap lookup, not a real scan.)

**Success response:**
```json
{
  "column": [12, -7],
  "surface_y": 64
}
```

`surface_y` may be `null` when the column has no open-sky point (e.g., overhangs all the way to build limit — very rare). Agent treats this as a `surface`-tool failure.

**Failure response:** standard `{success: false, reason, message}`. `reason` is one of:
- `bad_request` — non-integer query param, malformed query, or wrong HTTP method. 4xx.
- `out_of_range` — requested (x, z) is outside loaded chunks. The default-column request never fires this; only an explicit `x, z` far from the player can.
- `internal_error` — anything unexpected.

### `GET /scan_entities` *(implemented)*

Returns nearby entities matching a type filter, sorted by distance from the player. The agent's hunt / combat tooling uses this to find a target before pathing to it — Wurst's KillAura + Baritone's item pickup handle the rest of the harvest loop, so the only thing missing from the craft side is "where is the nearest cow." This endpoint fills that gap.

Parallel in scope to `/scan_column`: a cheap read of client-side world state, no game-state mutation, no game-thread hop required for the lookup itself (the client's entity list is volatile but readable).

**Query params:**
- `type` (required, string) — entity type id to match, e.g. `minecraft:cow`, `minecraft:zombie`. Both namespaced (`minecraft:cow`) and unnamespaced (`cow`) forms are accepted; the mod normalizes via `EntityType.byString(...)` / `BuiltInRegistries.ENTITY_TYPE.get(...)`. Multi-type matching is not supported in v1 — the agent makes one call per type.
- `radius` (optional, integer, default 32) — search radius in blocks (Chebyshev distance from the player). Capped at 64 server-side — Baritone struggles to path much further than that and a larger scan just returns entities the agent can't reach anyway.
- `limit` (optional, integer, default 5) — maximum number of entities to return. Sorted nearest-first; the rest are dropped.

**Success response:**
```json
{
  "entities": [
    {
      "type": "minecraft:cow",
      "uuid": "f2a4...",
      "position": [12.5, 64.0, -7.3],
      "distance": 4.2,
      "is_baby": false,
      "health": 10.0
    }
  ]
}
```

Fields:
- `type` — resolved entity type id (always namespaced in the response, regardless of how the caller specified it).
- `uuid` — entity UUID. Useful if the agent wants to track a specific target across calls (e.g., follow one cow until it dies). String form, not bytes.
- `position` — entity's current world-space coords as floats (block-fractional). Matches `/position`'s coordinate convention.
- `distance` — Euclidean distance from the player at scan time, in blocks. Pre-computed because the agent uses it for sort verification and "is anything close enough to hunt" thresholding.
- `is_baby` — true for baby variants (`Mob.isBaby()`). Lets the agent skip babies on hunt-for-food calls (babies drop nothing useful and killing them feels worse than killing adults — speedrunner norm).
- `health` — current HP. Useful for choosing the weakest nearby target.

Sorted by `distance` ascending. The full list is sliced to `limit` after sorting.

**Failure response:** standard `{success: false, reason, message}`. `reason` is one of:
- `bad_request` — missing required `type`, non-integer `radius`/`limit`, malformed query, or wrong HTTP method. 4xx.
- `unknown_entity_type` — the `type` string doesn't resolve to a registered `EntityType`. Likely a typo or a modded entity not present in the client's registry.
- `out_of_range` — invalid radius (negative, or >64 after server-side clamping was disabled — currently the server clamps silently so this is unused; reserved for future strict mode).
- `internal_error` — anything unexpected.

The endpoint always returns 200 with an empty `entities` list when no entities of the requested type are in radius — empty is not a failure. The craft-side `hunt` tool reads emptiness as "no target reachable, try traveling first."

**Mod behavior:**

1. Resolve `type` via `BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation)` — invalid → `unknown_entity_type`.
2. Read `client.level` (volatile client-side world). If null (title screen, mid-respawn), `internal_error`.
3. Build a search bounding box: player position ± `radius` on each axis.
4. Iterate `client.level.getEntities(null, AABB)` (no exclusion entity), filter to entities whose `getType()` matches the resolved target.
5. For each matching entity, compute Euclidean distance to the player; build the response record.
6. Sort by distance ascending, slice to `limit`, serialize.

**Threading.** `client.level.getEntities(...)` reads the client-side entity list. The entity list is mutated on the render thread, so the scan should happen on a `MinecraftClient.execute(...)` hop to avoid mid-iteration mutation. The serialization can then happen back on the HTTP worker. Empirically the scan itself is sub-millisecond — no need to optimize.

**Why no category filter (yet).** v1 keeps the scan single-type because category-level filters (`type=animal`, `type=hostile`) introduce a taxonomy question that homunculus shouldn't own — what counts as "animal" varies between mob-pack mods and the agent's intent. Single-type matches the existing `/baritone/mine` shape and lets the agent compose: `hunt` can iterate `[cow, pig, sheep, chicken]` candidates the same way `mine_any_log` iterates log types.

**Non-goals (v1):**
- No filtering by health, baby/adult, or hostile status server-side. Agent filters from the response.
- No "tame this entity" / "breed this entity" follow-up actions. Wurst + Baritone cover hunt + harvest; taming is out of scope for the diamond-goal harness.
- No persistent entity tracking. Each call is a fresh scan; the agent should not assume a UUID returned in call N+1 refers to the same entity instance from call N if it has gone out of range and back.

### `GET /stats` *(implemented)*

Returns the player's vitals, XP, active effects, and a few movement-state booleans. Cheap pure-read snapshot — no game-thread side effects beyond the read hop.

**Why this exists.** The agent needs to know "am I in trouble" (low HP, drowning, on fire), "am I hungry" (food level, saturation), and "what effects am I under" (poison → seek milk; night vision → can plan deeper). All of these are scattered across `Player`/`LocalPlayer` accessors that the agent can't reach via `/inventory` or `/position`. One unified read is cheaper than N specialized endpoints.

No body. No query params.

**Success response:**
```json
{
  "health": 18.0,
  "max_health": 20.0,
  "food": 17,
  "saturation": 5.0,
  "armor": 4,
  "air": 300,
  "max_air": 300,
  "experience": {"level": 12, "progress": 0.45, "total": 247},
  "effects": [
    {
      "id": "minecraft:night_vision",
      "amplifier": 0,
      "duration_ticks": 4200,
      "infinite": false,
      "ambient": false
    }
  ],
  "dimension": "minecraft:overworld",
  "gamemode": "survival",
  "on_ground": true,
  "in_water": false,
  "in_lava": false,
  "on_fire": false
}
```

Fields:
- `health` / `max_health` — floats. `max_health` reflects current attribute value (modifiers, absorption-baseline aside).
- `food` — integer 0–20 (`FoodData.getFoodLevel`).
- `saturation` — float (`FoodData.getSaturationLevel`). 0 means the next damage tick will start draining food.
- `armor` — integer 0–20 (total armor points across equipped pieces, post-attribute-modifiers).
- `air` / `max_air` — integer ticks. Default max is 300; underwater drain is 1 tick per game-tick once `air` hits 0.
- `experience.level` — integer XP level (the green number).
- `experience.progress` — float 0..1, fraction of the way to next level.
- `experience.total` — integer total accumulated XP points (the underlying counter, not always equal to level-derived total).
- `effects` — list of active mob effects. Each entry: `id` (namespaced effect id; `unknown` if the registry holder is unresolved — extremely rare), `amplifier` (0 = level I, 1 = level II), `duration_ticks` (ticks remaining; ignore when `infinite: true`), `infinite` (true for beacon/long-shelf effects with no fixed duration), `ambient` (true for beacon-source effects; affects particle rendering).
- `dimension` — namespaced dimension id (`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, or a modded id).
- `gamemode` — string (`survival`, `creative`, `adventure`, `spectator`). `null` only if the client gamemode handler is mid-transition (rare; usually means we shouldn't have a player either).
- `on_ground` / `in_water` / `in_lava` / `on_fire` — booleans. `on_fire` is true for any active fire-damage state (burning blocks, recent lava contact, fire-tick remainder).

**Failure response:** standard `{success: false, reason, message}`. `reason` is one of:
- `bad_request` — wrong HTTP method. 4xx.
- `internal_error` — no player (title screen, mid-respawn) or anything unexpected. 5xx.

**Non-goals (v1):**
- No equipped-item / durability data — `/inventory` already covers slot contents; durability is deferred.
- No absorption hearts as a separate field — bundled into the implicit max-health vs current-health gap. If this gets in the way, we'll split it later.
- No raw attribute map (movement speed, knockback resistance, etc.). Agent doesn't need it for the diamond-goal harness.
- No client-side latency ping. If the agent needs that, add a dedicated endpoint.

### `GET /deaths` *(implemented)*

Returns a buffer of recent player deaths. The agent polls this after each tool call so it can detect a death (which silently wipes inventory and teleports the player), surface the cause into the LLM's context, and optionally route back to the corpse via `/baritone/goto`.

**Why this is in the mod.** A death is the one game-state transition the agent can't infer from `/inventory` + `/position` alone. Position teleports and inventory drops are both also caused by legitimate actions (Baritone goto, dropping items intentionally), so a craft-side heuristic gets false positives. Vanilla MC already knows the cause (it prints `Player fell from a high place` etc. to chat at the moment of death); homunculus surfaces it as structured data.

**Query params:**
- `since` (optional, integer epoch ms) — return only deaths with `timestamp > since`. Without it, returns the full buffer.

**Success response:**
```json
{
  "deaths": [
    {
      "timestamp": 1715492142000,
      "message": "toast fell from a high place",
      "cause": "fall",
      "death_pos": [12, 8, -34],
      "respawn_pos": [-12, 64, 100]
    }
  ]
}
```

Fields:
- `timestamp` — epoch ms at the moment of the death event.
- `message` — vanilla MC death line verbatim. Source: `player.getLastDamageSource().getLocalizedDeathMessage(player).getString()` (same path vanilla uses to render the death screen). Falls back to `player.getCombatTracker().getDeathMessage().getString()` if `getLastDamageSource()` is null.
- `cause` — bucket derived from `DamageSource.getMsgId()` plus `DamageSource.getEntity()`. If the attacker is a `Player`, returns `player:<name>` (gameProfile name). If the attacker is any other entity, returns `mob:<entity_id>` (e.g. `mob:minecraft:zombie`, via `BuiltInRegistries.ENTITY_TYPE.getKey(...)`). Otherwise mapped from msgId: `fall` (`fall`), `lava` (`lava`), `fire` (`inFire`/`onFire`/`hotFloor`), `drown` (`drown`), `void` (`outOfWorld`), `starvation` (`starve`), `cactus` (`cactus`), `freeze` (`freeze`), `suffocation` (`inWall`/`cramming`), `explosion` (`explosion`/`explosion.player`), `other` (anything not covered).
- `death_pos` — integer block coords from `player.blockPosition()` at the moment of death, snapshotted before respawn moves the player.
- `respawn_pos` — integer block coords where the player respawned. May be `null` if the player is still on the dead-screen when `/deaths` is called (see below).

**Failure response:** standard `{success: false, reason, message}`. `reason` is one of:
- `bad_request` — non-integer `since`, or wrong HTTP method. 4xx.
- `internal_error` — anything unexpected. 5xx.

The endpoint always returns 200 with an empty `deaths` list when no deaths have occurred — empty is not a failure.

**Mod behavior:**

Homunculus is a client-only mod connecting to remote servers (often Purpur), so server-side Fabric events (`ServerLivingEntityEvents.AFTER_DEATH`, `ServerPlayerEvents.AFTER_RESPAWN`) don't fire in this JVM. Death detection is tick-driven on the client — see `DeathTracker`.

1. On mod init, allocate an in-memory ring buffer (capacity 10) for death records. No disk persistence — the agent reads within seconds and a session restart implies a fresh state.

2. **Death detection — `ClientTickEvents.END_CLIENT_TICK` edge-detect.** Each tick, compare current `player.isAlive()` against the previous tick's value. On a `true → false` transition for the same player instance:
   - Snapshot `timestamp = System.currentTimeMillis()`, `death_pos = player.blockPosition()`.
   - Read `player.getLastDamageSource()` and derive `message` + `cause` per the field rules above.
   - Stash in a single "pending" slot (the client controls only one player).

3. **Respawn detection.** On a `false → true` transition with a pending record present, snapshot `player.blockPosition()` as `respawn_pos`, finalize the pending record, and push it into the ring buffer.

4. **Player-instance changes.** When `client.player` becomes a different LocalPlayer instance (dimension change, reconnect, respawn-to-new-entity), reset the `wasAlive` edge tracker against the new player but keep `pending` — if a death happened just before the swap, the next `isAlive()` tick on the new entity finalizes it. When `client.player` goes null (disconnect / world unload), drop any unfinalized pending.

5. **Unfinalized records.** If the player sits on the dead-screen and `/deaths` is queried before respawn, return the pending record with `respawn_pos: null`. After respawn, the same record is finalized in place (the agent's `since`-based polling will see the finalized version on the next poll). Don't block the endpoint waiting for respawn — the dead-screen is user-driven and can be indefinite.

6. `since` filtering: return only records with `timestamp > since`. Records are ordered oldest → newest in the response array; the unfinalized pending (if any) is appended last.

**Threading.** Tick handler runs on the client thread; record construction happens there. The HTTP worker reads the ring buffer under a short `synchronized` block on the `DeathTracker` singleton — no game-thread hop needed for reads.

**Why not a mixin on `ClientboundPlayerCombatKillPacket`?** That's the more authoritative signal (it's exactly when the server announces the death) and is what Baritone uses. Tick-polling has a 1-tick worst-case latency vs the packet hook, but avoids adding mixin infrastructure (none currently in the project) and reads from the same damage-source data the vanilla death screen renders from. If a future need surfaces (e.g. recording deaths that don't flip `isAlive()` on the client for some reason), revisit and mixin.

### `POST /craft`

Request body:
```json
{"item": "minecraft:wooden_pickaxe", "count": 1}
```

`count` semantics: **the exact number of output items to produce in inventory.** The mod batches recipe invocations internally if needed. So `{"item": "minecraft:planks", "count": 16}` runs the planks recipe four times (each yielding 4). `count: 1` is the common case for tools.

Mod behavior:
1. Look up the recipe from `RecipeManager`.
2. Compute required ingredients for `count` outputs and check inventory.
3. If recipe is 3×3, check that a placed `crafting_table` is within reach (~4 blocks of the player).
4. If both checks pass, execute the craft (or batch of crafts) and return success.
5. If anything fails, return a structured failure (below).

**Success response:**
```json
{
  "success": true,
  "crafted": {"id": "minecraft:wooden_pickaxe", "count": 1}
}
```

**Failure response:**
```json
{
  "success": false,
  "reason": "missing_ingredients",
  "missing": [
    {"id": "minecraft:planks", "count": 3},
    {"id": "minecraft:stick", "count": 2}
  ],
  "requires_crafting_table": true,
  "crafting_table_nearby": false,
  "message": "wooden_pickaxe needs 3 more planks and 2 more sticks; no crafting table within reach"
}
```

`reason` is one of:
- `missing_ingredients` — inventory doesn't have enough. Populate `missing` with the gap.
- `requires_crafting_table` — recipe is 3×3 and no table is within reach. (Use this only when ingredients are present but the table is missing; if both are missing, prefer `missing_ingredients` and let `requires_crafting_table: true, crafting_table_nearby: false` carry the workbench info.)
- `no_recipe` — the item has no known recipe (raw materials, mob drops). Agent must acquire it some other way.
- `unknown_item` — the `item` id doesn't resolve. Likely a typo from the agent.
- `internal_error` — anything unexpected (packet failure, screen handler issue). Put exception details in `message`.

Notes:
- **Always include `requires_crafting_table` and `crafting_table_nearby`** regardless of failure reason — both are cheap to compute and the agent reads them for planning.
- `missing` is the full ingredient gap for `count` outputs, not partial. Agent uses this to reactively craft sub-ingredients in one round.

### `POST /place` *(revised 2026-05-10: auto-positioning)*

The mod picks a placement spot near the player and places the block there. Replaces v1's crosshair-based behavior — the LLM agent is effectively blind to look direction and defaults to staring at the horizon, so positional reasoning is moved into the mod.

Request body:
```json
{"item": "minecraft:crafting_table"}
```

**Mod behavior:**
1. Verify the item is in inventory; else `not_in_inventory`.
2. Confirm the item is a placeable block; else `not_placeable`.
3. **Anti-casing precondition:** count how many of the 8 ring-1 tiles around the player's feet (Chebyshev distance 1) are "open" — air or a replaceable block (tall_grass, snow_layer, fern, etc.). If fewer than `RING_1_OPEN_MIN` are open (default `3`), return `no_space`. Placing in a tight pocket walls the agent in; refusing is better than succeeding into a trap. The threshold reads `HOMUNCULUS_RING1_OPEN_MIN` at static init (env var), so it can be raised for tighter rollouts or lowered if testing reveals 3 is still too strict — no rebuild needed, just an MC client restart. Below 3 means more cased than open around the player, which is the actual casing scenario; above 5 rejects most natural surface terrain (grass tufts, minor slopes) so the agent loses turns to relocation. 3 is the threshold where "at least one cardinal-pair of escape routes" is guaranteed.
4. Search candidate placement positions, **preferring ring 2 (distance 2) over ring 1**. A block 2 tiles away gives breathing room; a block adjacent to the player creates a cramped placement. Scan order within each ring: cardinals (N→E→S→W) first, then ring-2 edge tiles flanking each cardinal, then ring-2 corners; ring-1 cardinals; ring-1 diagonals. For each candidate, accept if **(a)** the target cell is open AND **(b)** the block directly below is a sturdy-top solid (vanilla `BlockState.isFaceSturdy(level, pos, Direction.UP)`; full `canSurvive` is not needed because v1 only places crafting_table / furnace / chest-class blocks).
5. Auto-select the item in the hotbar.
6. Place at the chosen position. Briefly rotate the player's yaw/pitch toward the support block's top-face center, send a `ServerboundMovePlayerPacket.Rot` so the server's eye-cone check passes, then `useItemOn` with a synthetic `BlockHitResult` against the support's top face. Restore yaw/pitch after.

**Success response:**
```json
{
  "success": true,
  "placed_at": [12, 64, -7]
}
```

**Failure response:**
```json
{
  "success": false,
  "reason": "no_space",
  "message": "need more space around player; only 2/8 adjacent tiles clear (need 3+) — relocate to open ground"
}
```

`reason` is one of:
- `no_space` — anti-casing tripped: fewer than `RING_1_OPEN_MIN` (default 3) of the 8 ring-1 tiles around the player are open. The dominant failure when the agent is in a 1-block hole, against a wall, or in dense foliage. Message reports the actual open-tile count. **Agent action:** relocate (`#goto`, `#thisway`, a few manual steps via Baritone) and retry.
- `no_placeable_spot` — anti-casing passed (≥`RING_1_OPEN_MIN` open) but no candidate had sturdy support below. Rare; occurs when the player is on a 1×1 pillar, on a slab edge, on a floating platform with air around, or wading in water. **Agent action:** relocate and retry.
- `not_in_inventory` — item not in inventory.
- `not_placeable` — item isn't a block (e.g. a stick), or the id doesn't resolve.
- `internal_error` — anything unexpected.

**Why this changed.** v1 raycast-from-crosshair assumed the caller positioned the view before calling. That works under xdotool with a human supervising; it breaks under an LLM planner that has no usable view of where it's facing. The 2026-05-10 rollout confirmed the failure mode: agent arrives at the craft step, can't place the table because it's staring at the horizon, has no path to recover. Moving placement decisions into the mod removes a whole class of agent-side blockers.

**Non-goals (v1):**
- No multi-block macro placement.
- No automatic relocation — agent decides where to go on `no_space` / `no_placeable_spot`.
- No directional preference (e.g., "place in front of me"). The agent only cares that *some* sensible spot was used.
- No vertical search. Candidates are only scanned at feet-y. Standing on a 1-block step won't see the lower tier; agent retries from a different position.

### `POST /equip`

No body. Auto-equips the "best" item in the player's inventory into a fixed hotbar layout, plus full armor:

| Hotbar slot (0-indexed) | Role            |
|-------------------------|-----------------|
| 0                       | sword           |
| 1                       | axe             |
| 2                       | pickaxe         |
| 3                       | shovel          |
| 4                       | hoe             |
| 5                       | food            |
| 6                       | building blocks |
| 7, 8                    | untouched (caller-managed misc) |

Plus armor slots: head, chest, legs, feet.

**Ranking rules:**
- **Tools / armor:** material tier inferred from item id prefix (`netherite_` > `diamond_` > `iron_` / `turtle_` > `chainmail_` / `stone_` > `golden_` > `wooden_` / `leather_`). Tiebreak by lower `DAMAGE` (more uses left = better). Mod items with unrecognized prefixes rank below all vanilla tiers.
- **Food:** highest `FoodProperties.nutrition()`, tiebreak by saturation. Any item with the `FOOD` data component is eligible.
- **Building blocks:** lowest tier wins (cheapest first), then largest stack count. Curated list, in tier order:
  - **0 (cheapest):** dirt, coarse_dirt, rooted_dirt, grass_block, podzol, mycelium, mud, packed_mud, cobblestone, cobbled_deepslate, netherrack, blackstone
  - **1:** stone, deepslate, granite, diorite, andesite, tuff, basalt, smooth_basalt, end_stone, sandstone, red_sandstone, calcite, dripstone_block
  - **2:** all_planks, all_logs (incl. stripped variants)
  - **3:** stone_bricks, mossy_cobblestone, mossy_stone_bricks, polished_*, smooth_*, deepslate_bricks, bricks, nether_bricks, red_nether_bricks, chiseled_*
  - **4:** iron_block, copper_block, raw_iron_block, raw_copper_block, amethyst_block
  - **5 (last resort):** gold_block, diamond_block, emerald_block, netherite_block, raw_gold_block, lapis_block
  - Items not in the list are **not** considered for the building slot.

**Behavior:**
- For each role, finds the best candidate anywhere in inventory (main + hotbar). If best is already in the role slot, no-op. Otherwise swaps via `ClickType.SWAP` (hotbar) or three-pickup cursor cycle (armor).
- Hotbar 7 and 8 are not auto-managed, but they may receive **displaced** items if a role-matching item happened to be parked there (SWAP semantics: target's old contents go to source slot).
- Refuses if the player has a non-inventory screen open (chest, crafting table, etc.).

**Success response:**
```json
{
  "success": true,
  "equipped": {
    "sword": "minecraft:iron_sword",
    "axe": null,
    "pickaxe": "minecraft:stone_pickaxe",
    "shovel": null,
    "hoe": null,
    "food": "minecraft:bread",
    "building": "minecraft:cobblestone",
    "head": "minecraft:iron_helmet",
    "chest": null,
    "legs": null,
    "feet": "minecraft:leather_boots"
  },
  "changes": [
    {"role": "sword",    "from": null,                       "to": "minecraft:iron_sword"},
    {"role": "building", "from": "minecraft:diamond_block",  "to": "minecraft:cobblestone"}
  ],
  "message": "2 change(s): sword=iron_sword, building=cobblestone"
}
```

`equipped` reports what each slot **currently** holds (post-equip). For role slots (sword/axe/.../building), `null` means the slot does not contain a role-matching item — either nothing, or something else the agent stashed there. Armor slot fields report whatever stack is in that armor slot, or `null` if empty.

**Failure response:** standard `{success:false, reason, message}`. `reason` is one of `internal_error` (no player, screen open, packet failure).

### `POST /smelt` *(v1.1 — superseded by the v1.2 async redesign below)*

Run a furnace smelt for `count` outputs of an item, using fuel from inventory. Mirrors `/craft`'s shape: structured, atomic, sync.

> **Note (2026-05-11).** This v1.1 sync behavior was implemented and shipped, then superseded by the v1.2 fire-and-forget design (next section). The v1.1 spec is kept for historical reference; the running mod no longer implements it. `POST /smelt`, `GET /smelt_status`, and `POST /collect_smelt` all behave per the v1.2 spec.

Request body:
```json
{"input": "minecraft:raw_iron", "count": 3, "fuel": "minecraft:coal"}
```

Fields:
- `input` (required) — the item to smelt (the *consumable*, not the result). E.g., `raw_iron` produces `iron_ingot`; `cobblestone` produces `stone`; `sand` produces `glass`.
- `count` (required) — number of output items to produce. Mod runs the smelt that many times.
- `fuel` (optional) — explicit fuel id. If omitted, the mod auto-picks the cheapest sufficient fuel from inventory (see ranking below).

`count` semantics: **the exact number of output items to land in inventory**, parallel to `/craft`. The mod computes fuel needed (`ceil(count / burns_per_fuel_item)`) and validates inventory.

Mod behavior:
1. Find a placed `furnace` within reach (~4 blocks of the player) — same proximity rule as the crafting_table check in `/craft`.
2. Look up the smelting recipe from `RecipeManager` for `input`.
3. Validate input count, fuel availability, and that `count` ≥ 1. Compute fuel budget.
4. If ingredients/fuel/furnace all check out, open the furnace UI on the game thread, load input + fuel, wait for completion, retrieve output, close UI.
5. On any failure, return a structured error and make no inventory changes (atomic).

**Success response:**
```json
{
  "success": true,
  "smelted": {"id": "minecraft:iron_ingot", "count": 3},
  "fuel_consumed": [{"id": "minecraft:coal", "count": 1}]
}
```

`fuel_consumed` is **always a list**, even when only one fuel type was used. When the auto-fuel selector combines multiple types to cover the burn budget (see below), each consumed type appears as its own entry, ordered cheap → expensive.

**Failure response:**
```json
{
  "success": false,
  "reason": "missing_fuel",
  "missing": [{"id": "minecraft:coal", "count": 1}],
  "requires_furnace": true,
  "furnace_nearby": false,
  "message": "raw_iron×3 needs 1 coal but inventory has none; no furnace within reach"
}
```

`reason` is one of:
- `missing_input` — not enough of `input` in inventory. Populate `missing` with the shortfall.
- `missing_fuel` — no usable fuel (or specified fuel insufficient). Populate `missing` with the fuel id + count needed.
- `requires_furnace` — no placed furnace within reach. (Use this only when input + fuel are both present; if multiple things are missing, prefer `missing_input` / `missing_fuel` and let `furnace_nearby: false` carry the workbench info.)
- `no_recipe` — `input` has no smelting recipe.
- `unknown_item` — `input` (or specified `fuel`) doesn't resolve.
- `internal_error` — anything unexpected.

**Always include `requires_furnace` and `furnace_nearby` regardless of failure reason** — same convention as `/craft`'s table fields.

**Auto-fuel ranking (when `fuel` is omitted).** The selector **accumulates fuel across types** until the burn budget is covered, walking by **least crafting-potential loss** (not by burn-time). The intent: preserve wood for the crafting DAG; burn pure-fuel first.

| Tier (high pref → low pref) | Items                                        | Rationale                                                                |
|-----------------------------|----------------------------------------------|--------------------------------------------------------------------------|
| 1                           | `coal_block`, `lava_bucket`                  | Dense pure-fuel. `coal_block` is 9 coal compressed.                      |
| 2                           | `coal`, `charcoal`                           | Pure fuel; no non-smelt use.                                             |
| 3                           | `stick`                                      | Low crafting-input value (sticks are outputs of planks, rarely an input). |
| 4                           | `*_sapling`                                  | Zero crafting-input value; only use is planting (agent doesn't farm in v1). |
| 5                           | `*_planks`                                   | Common crafting input (tables, sticks, doors, …).                        |
| 6                           | `*_log`, `*_stem`                            | Heaviest crafting input — each log → 4 planks → 8 sticks downstream.     |

- Within a tier, larger stacks are taken first (ties broken by item id for determinism).
- If the furnace already has a valid pre-loaded fuel, that fuel is used first to avoid pointless eviction — even if it sits in a lower-preference tier.
- The selector only advances to the next tier when the current tier's available pieces don't close the remaining burn-tick deficit. So an agent holding `1×coal + 2×planks + 2×logs` (≈14 smelts' worth combined) can run a 10-smelt request without needing any single type to cover the budget alone — and will exhaust coal before touching planks/logs.
- Burn-time table (vanilla, ticks): stick=100 (0.5 smelts), sapling=100 (0.5), planks=300 (1.5), log/stem=300 (1.5), charcoal=1600 (8), coal=1600 (8), coal_block=16000 (80), lava_bucket=20000 (100). Note that burn-time is **not** the ranking key — opportunity cost is.
- When the selector still can't cover the budget, the `missing_fuel` response names the highest-preference fuel the player already has (or `oak_planks` if they have none) at the count needed to close the gap. The `missing` array reflects the **actual shortfall**, not an arbitrary fuel suggestion.
- The agent can always force a specific fuel by passing `fuel` explicitly; that path is single-type and will fail with `missing_fuel` if a single stack can't cover the budget.
- Fuels outside this tier list (e.g. bamboo, wool, planks-derived blocks like bookshelves) are **not** auto-selected. Pass them via the explicit `fuel` param if needed.

**Timing note.** Smelting takes ~10s per output (200 ticks). Synchronous request blocks for `~10s × count + setup overhead`. Caller HTTP timeout must accommodate — recommend `max(30, count * 12)` seconds. Mod should still cap internally (e.g., 5min) to avoid hung connections.

**Atomicity caveat.** True atomicity is best-effort: if the mod crashes mid-batch, the furnace may retain partially-smelted state. On clean failures (validation or "furnace got broken / interrupted"), the mod should not leave items stranded — withdraw any pending input and report the failure. Document anything that can leave partial state.

---

### Async smelt redesign *(implemented — v1.2)*

**Why.** The v1.1 `POST /smelt` blocks for `~10s × count` while the furnace ticks down. That's correct as a single atomic operation but wrong as an *agent loop primitive*: the agent's turn freezes, the player stands idle and exposed, and during 2026-05-11 hostile-mode rollouts this directly caused a "died mid-cook" with full material loss. In MC reality smelting is fire-and-forget — load the furnace and do other things. The agent's tool surface should mirror that.

**Shape.** `POST /smelt` becomes non-blocking: it does only the synchronous parts (validate, auto-place a furnace if none is within reach, load input + fuel, ignite) and returns immediately with a registry handle. The cook ticks asynchronously on the game thread. A new `GET /smelt_status` reports per-furnace progress, and `POST /collect_smelt` walks the player to a furnace and pulls finished outputs. The agent decides when to collect.

**Implementation order.** `/collect_smelt` depends on `/baritone/goto` for routing back to a registered furnace, so the wiring order is fixed: `/baritone/goto` first, then the smelt redesign. Until goto is wired, `/collect_smelt` cannot be implemented — the registry + `/smelt_status` could land independently, but shipping the half-redesign without collection would force the agent to walk to furnaces manually, which is exactly the friction this design eliminates.

#### `POST /smelt` *(redesign)*

Same request body as v1.1. New behavior:

1. Validate input/fuel availability (same as v1.1: recipe lookup, count cap, fuel budget).
2. Find a placed furnace within reach (~4 blocks, same proximity rule as v1.1). If none, **auto-place from inventory**: requires `minecraft:furnace` in the player's inventory, then invokes the same `Placer` path as `/place` (ring-2 preference, anti-casing precondition: ≥`RING_1_OPEN_MIN` of 8 ring-1 tiles open — see `/place` for tuning). Placement failures roll up as `/smelt` failures with the placement reason (`no_space`, `no_placeable_spot`, `not_in_inventory`). The newly-placed furnace becomes the target.
3. Open the furnace UI on the game thread, transfer input + fuel into the appropriate slots, light it.
4. **Register the smelt** in the in-process furnace registry keyed by furnace `BlockPos`. Record: input id+count, expected output id+count, fuel loaded, `started_at_ms`, expected `eta_seconds`.
5. Close the UI and return immediately — **do not wait for the cook to finish**.

Success response:
```json
{
  "success": true,
  "furnace_pos": [12, 64, -3],
  "input": {"id": "minecraft:raw_iron", "count": 9},
  "expected_output": {"id": "minecraft:iron_ingot", "count": 9},
  "fuel_loaded": [{"id": "minecraft:coal", "count": 2}],
  "eta_seconds": 90,
  "status": "cooking",
  "started_at_ms": 1715478123456
}
```

Synchronous failure reasons. Note that with auto-placement folded into step 2, `requires_furnace` from v1.1 splits into placement-specific reasons. The `requires_furnace` / `furnace_nearby` always-present hint fields from v1.1 do **not** carry into the redesign — the failure reason is precise enough on its own.

- `missing_input` / `missing_fuel` — same as v1.1.
- `no_recipe` / `unknown_item` — same as v1.1.
- `not_in_inventory` — no furnace within reach and the player has no `minecraft:furnace` in inventory to auto-place. Agent must craft one.
- `no_space` — auto-placement tried, anti-casing tripped: fewer than `RING_1_OPEN_MIN` (default 3) of 8 ring-1 tiles around the player are open. Agent must relocate.
- `no_placeable_spot` — auto-placement tried, anti-casing passed, but no candidate cell had sturdy support below. Agent must relocate.
- `internal_error` — anything else.

The "started but cook failed" path is **not** a `/smelt` failure — the cook is async; whatever goes wrong post-ignition surfaces through `/smelt_status` and `/collect_smelt`.

`status` enum on the registry entry:
- `cooking` — actively ticking; chunk loaded; ETA is live.
- `ready` — all outputs done; awaiting collection.
- `partial` — some output ready, some still cooking (e.g. fuel ran out part-way).
- `stale` — the furnace's chunk is unloaded; the mod can no longer observe tick state. `/smelt_status` reports the last known snapshot and a `last_observed_ms` timestamp instead of a live ETA. The entry stays alive until `/collect_smelt` walks the player back, re-observes the actual furnace, and reconciles (typically transitions to `ready` / `partial` / `cooking`).
- `destroyed` — furnace block is gone (broken by player/mob/creeper) and last-known chunk was loaded. Exposed once in `/smelt_status` then dropped.
- `empty` — cleanup pending: output collected and no input remains. Dropped immediately after surfacing once.

#### `GET /smelt_status` *(new)*

Report the state of every registered active smelt. No query params. Read-only.

Response:
```json
{
  "smelts": [
    {
      "furnace_pos": [12, 64, -3],
      "input": {"id": "minecraft:raw_iron", "count_remaining": 0},
      "output": {"id": "minecraft:iron_ingot", "count_ready": 9},
      "fuel_remaining_burns": 0,
      "cook_progress": 1.0,
      "eta_seconds": 0,
      "status": "ready"
    },
    {
      "furnace_pos": [5, 15, 8],
      "input": {"id": "minecraft:raw_copper", "count_remaining": 4},
      "output": {"id": "minecraft:copper_ingot", "count_ready": 3},
      "fuel_remaining_burns": 5,
      "cook_progress": 0.43,
      "eta_seconds": 41,
      "status": "cooking"
    }
  ]
}
```

Field notes:
- `cook_progress` is a 0..1 estimate over the *total* batch (not per-item). Useful for surfacing progress in the agent's per-turn context.
- `eta_seconds` is the wall-clock estimate to reach `status=ready` from now. May undercount if the furnace runs out of fuel — implementer should recompute on each tick using fuel-remaining + items-remaining. For `status=stale`, `eta_seconds` is **null** (last-observed ETA is no longer trustworthy; the tick clock paused at chunk-unload).
- `count_ready` is what `collect_smelt` would currently pull. For a partially-completed batch (fuel exhausted mid-cook), `count_ready < expected_output.count` and `count_remaining > 0`.
- `status=stale` entries include a `last_observed_ms` field (epoch ms of last live observation) and the last-known `count_ready` / `count_remaining` / `fuel_remaining_burns` values. The mod cannot update these while the chunk is unloaded; `/collect_smelt` will reconcile on arrival.
- `status=destroyed` entries appear in one response and are then dropped — the agent gets one chance to learn the loss.
- `status=empty` entries are dropped immediately after `collect_smelt` succeeds; they should not appear in normal `/smelt_status` responses.

Empty registry → `{"smelts": []}`. Clients should treat absence of the field as equivalent to empty.

#### `POST /collect_smelt` *(new)*

Walk the player to an active furnace and transfer ready outputs into inventory. Optional `furnace_pos` targets a specific furnace; otherwise the mod picks the closest registered smelt with `status ∈ {ready, partial, stale}` (stale entries are eligible — reconciliation on arrival decides whether anything's actually collectable).

Request body (all fields optional):
```json
{"furnace_pos": [12, 64, -3]}
```

Mod behavior:
1. If `furnace_pos` is provided, look it up in the registry. If absent or not in registry, return `not_in_registry`.
2. If `furnace_pos` is omitted, select the closest registered furnace with `status ∈ {ready, partial, stale}`. If none, return `no_active_smelts`. (Stale entries are eligible targets — the agent's intent to collect implies "please route there and reconcile.")
3. Route the player to the furnace via `/baritone/goto` (same proximity rule as `/craft`'s `crafting_table_nearby` — within reach). If goto fails, return `furnace_unreachable`.
4. **Reconcile on arrival.** Once within reach, the chunk is loaded again. Read the furnace BlockEntity directly to refresh `count_ready`, `count_remaining`, `fuel_remaining_burns`, and recompute `status` (`stale` → `cooking` / `ready` / `partial` / `destroyed`). The pre-reconciliation status drove the routing decision; the post-reconciliation status drives the actual collection.
5. Open the furnace UI on the game thread, transfer the output slot to inventory, close UI.
6. If the furnace block is no longer a furnace (broken since last tick or while the chunk was unloaded), return `furnace_destroyed` and drop the registry entry.
7. After successful collection: if input remaining is 0 and output slot is empty, drop the registry entry and return `status: empty`. Otherwise leave the entry to keep cooking; return `status: cooking` or `partial`.

Success response:
```json
{
  "success": true,
  "furnace_pos": [12, 64, -3],
  "collected": [{"id": "minecraft:iron_ingot", "count": 9}],
  "still_cooking": 0,
  "fuel_remaining_burns": 0,
  "status": "empty"
}
```

Partial-collection example (3 iron pulled, 4 raw_iron still cooking):
```json
{
  "success": true,
  "furnace_pos": [5, 15, 8],
  "collected": [{"id": "minecraft:copper_ingot", "count": 3}],
  "still_cooking": 4,
  "eta_seconds": 41,
  "status": "cooking"
}
```

Failure reasons:
- `no_active_smelts` — registry has no `ready`/`partial`/`stale` smelts and no `furnace_pos` was specified.
- `not_in_registry` — the requested `furnace_pos` isn't a registered smelt.
- `furnace_unreachable` — `/baritone/goto` couldn't route to the furnace within budget. Caller can retry, travel closer manually, or give up.
- `furnace_destroyed` — registry entry exists but the world block isn't a furnace anymore (broken since last tick, or broken while the chunk was unloaded — surfaces on reconciliation for stale entries). Entry is dropped.
- `nothing_ready` — reconciliation revealed the furnace is still actively `cooking` with `count_ready == 0`. The agent walked all the way there for nothing. Rare for non-stale targets (the registry shouldn't route to a `cooking`-status entry); common-ish for stale entries whose chunks unloaded mid-cook.
- `internal_error` — anything else.

#### Furnace registry

In-process state owned by the mod, keyed by `BlockPos`. One entry per agent-initiated smelt that hasn't been fully collected.

Persistence:
- **Survives player death.** Furnaces are world blocks; the items inside are world state. The player respawns elsewhere but the furnace + its contents persist. A registry entry for a smelt that started before death should still be visible in `/smelt_status` after respawn, and `collect_smelt` should be able to route back to it.
- **Survives chunk unload as `stale`.** When a registered furnace's chunk unloads (agent travels far, dimension change), the mod can no longer tick its state. The entry transitions to `status=stale`, snapshotting `last_observed_ms` and the last known progress fields. When the chunk reloads — either incidentally (agent wanders back into render distance) or deliberately (`/collect_smelt` routes there) — the mod re-observes the furnace BlockEntity and reconciles. Reconciliation outcomes: `cooking` (still progressing), `ready` / `partial` (cook completed during the unload), `destroyed` (furnace block is gone). Incidental reloads update the entry in place; deliberate reloads via `/collect_smelt` reconcile then collect.
- **Does not survive mod restart.** Registry is in-memory only. If homunculus restarts, registered smelts are forgotten. The furnaces themselves and their items remain in the world but the agent can't differentiate them from random world furnaces. This is acceptable for v1.2; persistence-to-disk is a v1.3+ concern.
- **Does not survive dimension change of the agent's perspective on a *different* registered furnace.** Each registry entry stores the dimension id alongside `BlockPos`; the cross-dimension case is the same as chunk-unload (entry transitions to `stale`). `/collect_smelt` against a stale entry in another dimension fails with `furnace_unreachable` (goto doesn't cross dimensions in v1).
- **Cleanup paths:**
  - `collect_smelt` empties a furnace → registry entry dropped.
  - Furnace block destroyed while chunk loaded (creeper, player breaks it) → `status=destroyed` exposed in next `/smelt_status` response, then dropped.
  - Furnace destroyed while chunk unloaded → entry sits as `stale` until reconciliation surfaces `destroyed`, then dropped.
  - Abandoned entries (no progress in >10 min and `status=cooking` with `fuel_remaining_burns == 0`) → optionally garbage-collect; not required for v1.2 correctness.

Multiple concurrent smelts allowed. The agent can fire-and-forget several furnaces in parallel, do other work, then collect each one when ready.

#### Consumer-side sketch (craft/)

The craft-side change is in two places: the `smelt`/`collect_smelt` tool handlers (`tools.py`) and the per-turn context fetcher (`agent.py`).

**Tool surface** (`tools.py`):

```python
# smelt: no signature change; new semantics (returns immediately).
def handle_smelt(args):
    # ... same placement/auto-fuel logic on the synchronous path ...
    resp = POST /smelt {...}
    if not resp.success:
        return structured_error(resp)  # unchanged
    # NEW: return immediately with the registry handle
    pos = resp.furnace_pos
    eta = resp.eta_seconds
    return (
        f"smelt started: {count}x {input} in furnace at "
        f"({pos[0]},{pos[1]},{pos[2]}); ETA ~{eta}s. "
        f"Continue with other actions; call collect_smelt() when ready."
    )

# collect_smelt: new tool, no required args.
def handle_collect_smelt(args):
    pos = args.get("furnace_pos")  # optional
    body = {"furnace_pos": pos} if pos else {}
    resp = POST /collect_smelt body
    if not resp.success:
        reason = resp.reason
        if reason == "no_active_smelts":
            return "no active smelts to collect from — call smelt() first"
        if reason == "furnace_unreachable":
            return f"FAILED: couldn't reach furnace at {pos}; try travel() closer"
        if reason == "furnace_destroyed":
            return f"FAILED: furnace at {pos} is destroyed — smelted items are lost"
        return f"FAILED: collect_smelt: {resp.message}"
    collected = ", ".join(f"{c['count']}x {c['id']}" for c in resp.collected)
    rest = f"; {resp.still_cooking} still cooking (~{resp.eta_seconds}s)" if resp.still_cooking else ""
    return f"collected: {collected}{rest}"
```

**Per-turn context** (`agent.py`): a new `_fetch_smelts()` helper, called alongside `_fetch_stats()` and `_fetch_inventory()` each turn. Renders as:

```
Active smelts:
  furnace (12,64,-3): 9x iron_ingot READY — call collect_smelt()
  furnace (5,15,8):   3x copper_ingot ready, 4x raw_copper cooking (~41s)
```

Omitted entirely when the registry is empty (don't pollute the prompt with "no active smelts").

**Prompt updates**:
- Add `collect_smelt(furnace_pos?)` to the tool list with description: "Retrieve outputs from your active smelting furnace(s). Call after smelt() returns 'started', once 'Active smelts' shows READY."
- Update `smelt()` description: "Load a furnace and ignite it; returns IMMEDIATELY. Cook runs asynchronously (~10s per item). Use collect_smelt() in a later turn to retrieve outputs."

**Death-recovery integration**: smelts survive death, so the YOU DIED preamble doesn't need to mention them — the per-turn `_fetch_smelts()` will surface them naturally on the post-respawn turn, and the agent can goto/collect_smelt as a follow-up. (The "INVENTORY IS EMPTY" anchor still applies; ingots in a furnace are not in inventory.)

#### Open design decisions

- **`location` parameter on `smelt`** is still useful (`home` vs `here` vs `auto`) for the synchronous placement phase. No change to semantics.
- **`fuel_remaining_burns` granularity**: report in "smelt cycles remaining," not ticks. Easier for the agent to reason about.
- **Should `collect_smelt` accept a "force partial" flag** to pull a still-cooking furnace's output and stop the cook? Probably yes (allows recovery from "wrong recipe started"), but defer to v1.3 if not requested.
- **Should the agent be able to add more input** to an actively cooking furnace? Useful for "I have more raw_iron now" — but the natural alternative is "call smelt() again, it'll place a second furnace." Defer.

### `POST /baritone/mine`

**Status.** Wired and validated 2026-05-11 after fixing a `BlockOptionalMeta`-construction deadlock — see "Off-thread BOM prewarm" below.

Drive Baritone's `mine` process for one block type and wait for completion. Replaces the `craft/mine.py` chat-scraping loop that lives on top of xdotool + `tail -F` today. The mod drives Baritone through its Java API (`IMineProcess.mine(int, BlockOptionalMeta...)`) and watches `mineProcess.isActive()` plus an inventory delta-check for the terminal signal — no chat parsing.

Request body:
```json
{"block": "minecraft:oak_log", "count": 4, "timeout_seconds": 45}
```

Fields:
- `block` (required) — namespaced block id (Baritone's `#mine` takes the path-less name, but the mod accepts either form and normalizes; `oak_log` and `minecraft:oak_log` both work).
- `count` (required) — **cumulative inventory target**, matching Baritone's own `IMineProcess.mine(count, ...)` semantics: Baritone deactivates the mine process the instant inventory reaches N of the matching drop, so this is a target, not a delta. Callers wanting delta semantics must compute `before + delta` themselves (this is what `craft/` does today).
- `timeout_seconds` (optional, default 45) — total wall-clock budget. Internally split into a short *start* window (default 15s) waiting for Baritone to commit, and the remainder for the actual mine to complete. Mod hard-caps at 300s to avoid hung connections.

**Mod behavior:**
1. Resolve `block` to a registered `Block` via `BuiltInRegistries.BLOCK.getValue(...)`. If absent, `unknown_block`.
2. Verify Baritone's API is reachable on the classpath (`baritone.api.BaritoneAPI`); else `baritone_not_loaded`.
3. Acquire the global Baritone session lock (one outstanding `/baritone/*` op at a time). If already held, return `busy`.
4. **Off the render thread** (HTTP worker), construct a `BlockOptionalMeta` for the block. This populates the BOM's class-level drop cache without blocking the render thread — see "Off-thread BOM prewarm" below.
5. Inventory pre-check: count slots where `bom.matches(stack)` and compare to `count`. If already satisfied, short-circuit to `already_satisfied`.
6. On the game thread, register a scoped `IGameEventListener` (tick + path events) and call `getMineProcess().mine(count, bom)` with the prebuilt BOM.
7. State-machine loop on the HTTP worker, waiting for a terminal signal (see "Baritone API integration" below).
8. On `isActive()` going false after going true, run an inventory post-check: if `count` is satisfied, `have_target`; else `interrupted`.
9. Release lock, return outcome.

**Success response:**
```json
{
  "success": true,
  "reason": "have_target",
  "block": "minecraft:oak_log",
  "target": 4,
  "message": "mine process completed; target count reached"
}
```

**Failure response:**
```json
{
  "success": false,
  "reason": "unreachable",
  "block": "minecraft:oak_log",
  "target": 4,
  "message": "Baritone bailed: PathEvent.CALC_FAILED"
}
```

`reason` is one of:
- `have_target` — `mineProcess.isActive()` flipped false **and** inventory post-check confirms `count` is met. (Success.)
- `already_satisfied` — inventory pre-check found `count` was already satisfied before calling `mine()`; Baritone was not invoked. Success, no-op. Lets callers distinguish "no work needed" from "work was done."
- `unreachable` — Baritone fired `PathEvent.CALC_FAILED`. **Caller action:** try a different candidate, relocate, or give up.
- `never_started` — start-window (default 15s) elapsed without `mineProcess.isActive()` going true. Usually means Baritone refused the call (no primary instance, mid-other-task, or the block is unmineable). Distinct from `already_satisfied`.
- `interrupted` — `mineProcess.isActive()` flipped false post-start but inventory post-check shows `count` not met. Typically a concurrent `/baritone/stop`, or Baritone exhausting candidates. The response message includes the actual count so the caller can decide whether partial progress is acceptable.
- `timeout` — full-budget deadline elapsed while still active. Inventory may have changed; caller should re-read `/inventory`.
- `busy` — another `/baritone/*` call is in flight.
- `baritone_not_loaded` — Baritone classes / API not on the runtime classpath. Hard configuration error; not transient.
- `unknown_block` — `block` id doesn't resolve to a registered block.
- `internal_error` — anything unexpected.

`PathEvent.CANCELED` is **ignored** during the mine loop. Baritone fires it for routine mid-mine path transitions (path A finishes, planner cancels and replans for the next tree); only `mineProcess.isActive()` is authoritative for completion. External `/baritone/stop` is detected via the inventory post-check (`interrupted`).

**Note on cancellation.** On `unreachable`, `never_started`, `interrupted`, or `timeout`, the mod **always** calls `pathingBehavior.cancelEverything()` before releasing the lock. `cancelEverything()` is synchronous on the game thread; callers can assume that on non-success return, Baritone is idle.

**Off-thread BOM prewarm.** `BlockOptionalMeta`'s constructor calls `getStackHashes() → drops()`, which on a multiplayer client deadlocks the render thread: `drops()` is `static synchronized` and invokes `ServerLevelStub.holder() → method_30349 → CompletableFuture.join` on a registry future that requires the render thread itself to make progress. Constructing the BOM from the HTTP worker thread sidesteps this — the render thread stays free to drive the future. Once `drops()` populates its class-level cache for a given block, subsequent constructions of the same block are cheap and safe on any thread. We then pass the prebuilt BOM into `mineProcess.mine(int, BlockOptionalMeta...)`, avoiding the `mine(int, Block...)` overload which would reconstruct a BOM internally on the render thread. Validated 2026-05-11: 3-log cumulative mine completed in 3.6s end-to-end with no MC freeze.

### `POST /baritone/goto` *(implemented)*

Drive Baritone's `customGoalProcess` to a world-space coordinate and wait for arrival. Replaces direct chat-injection of `#goto x y z` in `craft/tools.py` (used by `surface`, `descend`, `travel`, and `_goto_home`).

Request body — `goal_type="block"` (default, current behavior):
```json
{
  "x": 12, "y": 64, "z": -7,
  "goal_type": "block",
  "timeout_seconds": 60,
  "arrival_tolerance": 2,
  "allow_place": true,
  "throwaway_items": ["minecraft:dirt", "minecraft:netherrack"],
  "ensure_throwaway_in_hotbar": true
}
```

Request body — `goal_type="y_level"` (planned v1.4, only `y` required):
```json
{
  "y": 8,
  "goal_type": "y_level",
  "timeout_seconds": 90
}
```

Fields:
- `goal_type` *(planned — v1.4, optional, default `"block"`)* — selects the Baritone `Goal` implementation:
  - `"block"` (default, current behavior) — `GoalBlock(x, y, z)`, a specific point. Requires all of `x`, `y`, `z`. Arrival = position within `arrival_tolerance` of the point.
  - `"y_level"` — `GoalYLevel(y)`, *any* point at the given y. Requires only `y`; `x` and `z` are ignored if present. Arrival = `PathEvent.AT_GOAL` (Baritone's own `GoalYLevel` arrival semantics); `arrival_tolerance` is ignored. See "Y-level goal" below.
- `x`, `y`, `z` — integer block coords. Required when `goal_type="block"`. When `goal_type="y_level"`, only `y` is required; `x` / `z` are accepted but unused (keeps the schema uniform for clients that always send all three). Floats are rejected.
- `timeout_seconds` (optional, default 60). Capped at 300.
- `arrival_tolerance` (optional, default 2) — Manhattan distance below which the mod treats a `"block"` goto as complete (near-miss tolerance — see arrival logic below). **Ignored for `goal_type="y_level"`**: Baritone's `GoalYLevel` defines arrival itself and the mod delegates to `PathEvent.AT_GOAL` rather than a position predicate.
- `allow_place` *(planned — v1.3, optional, default `true`)* — when `false`, the mod sets Baritone's `allowPlace` setting to `false` for the duration of this call, preventing Baritone from consuming any inventory block for pillar-up / bridging during pathing. Coarsest protection level. See "Inventory-protected goto" below.
- `throwaway_items` *(planned — v1.3, optional, default `null`)* — list of namespaced item ids restricting Baritone's `acceptableThrowawayItems` setting for the duration of this call. When `null`, Baritone's default list is used (`cobblestone, dirt, netherrack`). When provided as a list, *only* those items may be placed by Baritone — useful for "you can bridge, but only with dirt, not the cobblestone I'm about to craft with." Empty list `[]` is equivalent to `allow_place=false`. The previous setting is restored on lock release.
- `ensure_throwaway_in_hotbar` *(planned — v1.3, optional, default `false`)* — when `true`, before pathing starts, the mod scans the agent's inventory for a throwaway item (filtered by `throwaway_items` when set, else Baritone's defaults) and swaps it into hotbar slot 6. Selection heuristic: **most-plentiful match wins** (maximizing the chance the goto completes — running out of pathing blocks mid-traversal is a common Baritone failure). On goto completion (success *or* failure), the original slot-6 contents are restored. This is scoped to the goto call: if /equip later re-stages a different building block, that's fine — the next /baritone/goto with this flag will swap again. Symmetry with the `allow_place` snapshot-restore.

**Mod behavior:**
1. Baritone-loaded + lock checks as `/baritone/mine`.
1a. **Settings snapshot-and-mutate**, all guarded by a try/finally on lock release:
    - If `allow_place == false`: snapshot `Baritone.settings().allowPlace.value`, set to `false`.
    - If `throwaway_items != null`: snapshot `Baritone.settings().acceptableThrowawayItems.value`, replace with the resolved item list (`ResourceLocation.tryParse` → `BuiltInRegistries.ITEM` lookup; unknown items are skipped with a logged warning, not an error). Empty resolved list is allowed (caller asked for nothing, Baritone won't place anything).
    - If `ensure_throwaway_in_hotbar == true`: scan main inventory + hotbar for items matching the resolved throwaway set (or Baritone's default if `throwaway_items` was not provided). Pick the item with the **highest total count**; ties broken by the order in `throwaway_items` if provided, else alphabetical. Snapshot slot 6's current item (id + count), then swap the selected stack into slot 6. If no matching item is found anywhere in inventory, log a warning and proceed without staging (best-effort). All settings are restored on lock release; slot 6 is restored to its pre-call contents.
    - Baritone settings are global so the mutate-and-restore is mandatory; the session lock guarantees serial access.
2. On the game thread, construct the Goal based on `goal_type` and call `getCustomGoalProcess().setGoalAndPath(goal)`:
   - `"block"` → `new GoalBlock(x, y, z)`
   - `"y_level"` → `new GoalYLevel(y)`
3. Poll player position on the game thread (250ms cadence) and subscribe an `IGameEventListener` in parallel. Arrival logic differs by `goal_type`:

   For `"block"` (unchanged from v1.2): terminate on whichever fires first:
   - position within `arrival_tolerance` Manhattan distance of `(x, y, z)` — `arrived`. Earns its keep here as a near-miss tolerance — Baritone sometimes lands slightly off the requested column, and "close enough" should count.
   - `PathEvent.AT_GOAL` — `arrived` (belt-and-suspenders; both signals normally coincide).

   For `"y_level"`: terminate only on `PathEvent.AT_GOAL` for arrival. **No position-predicate.** `GoalYLevel(y)` already defines arrival as "player y equals target y" inside Baritone, so `AT_GOAL` is authoritative and the predicate isn't earning its keep. Worse, it would actively misfire mid-route: the player traverses through the y-plane on the way to the goal, so a free-firing predicate would trip the instant the player crossed the target y, with `cancelEverything()` stranding them there. Matches vanilla `#goto Y` semantics — Baritone stops when it's at the right y, full stop.

   Common to both goal types:
   - `!customGoalProcess.isActive() && !pathingBehavior.isPathing()` and arrival not yet detected — `stuck`
   - `PathEvent.CALC_FAILED` — `unreachable`
   - timeout — `timeout`
4. Always `pathingBehavior.cancelEverything()` before releasing lock (same convention as `/baritone/mine`).

**Success response:**
```json
{
  "success": true,
  "reason": "arrived",
  "target": [12, 64, -7],
  "final_position": [12.5, 64.0, -7.3],
  "message": "arrived at target within tolerance 2"
}
```

**Failure response:**
```json
{
  "success": false,
  "reason": "stuck",
  "target": [12, 64, -7],
  "final_position": [11.5, 65.0, -3.2],
  "message": "Baritone idled with 4.2 blocks remaining"
}
```

`reason` is one of:
- `arrived` — within tolerance or Baritone confirmed reached goal. (Success.)
- `stuck` — Baritone stopped pathing but we didn't arrive. Matches the existing PARTIAL semantics in `craft/tools.py`. Caller decides whether to retry, try a different approach, or give up.
- `unreachable` — Baritone fired `PathEvent.CALC_FAILED`.
- `canceled` — Baritone fired `PathEvent.CANCELED`, typically because of a concurrent `/baritone/stop`. Distinct from `timeout`: the move was interrupted, not abandoned.
- `timeout` — full budget elapsed without arrival or definitive idle.
- `invalid_request` — request body fails validation. Examples: `goal_type="block"` without all of `x`/`y`/`z`; `goal_type="y_level"` without `y`; unknown `goal_type` string. Message names the specific field. Validation happens before lock acquisition and before any settings mutation.
- `busy`, `baritone_not_loaded`, `internal_error` — same as `/baritone/mine`.

`final_position` is always populated when the mod can read player position (i.e., always except `internal_error` / no-player edge cases). Callers can use it to compute residual distance without a separate `/position` round-trip.

`target` shape mirrors `goal_type`:
- `goal_type="block"` → `"target": [x, y, z]`.
- `goal_type="y_level"` → `"target": {"y": <int>}`. The object form (rather than `[null, y, null]` or echoing back ignored x/z) makes the goal-type discriminator explicit in the response without requiring callers to also echo `goal_type` in the body they consume.

#### Y-level goal *(planned — v1.4)*

**The failure mode.** `descend(target_y)` and `surface()` express y-plane intent ("get me to y=8 to mine diamond" / "get me to sky level"), but the current implementation in `craft/tools.py` converts them to `GoalBlock(px, target_y, pz)` — a fixed 3D point in the agent's *current* column. Baritone has no permission to deviate around obstacles. So when the agent calls `descend(8)` from y=64 in a column with no natural shaft, Baritone digs straight down through stone block-by-block, ignoring a perfectly traversable cave 3 blocks east.

Observed symptoms:
- Deep `descend` calls consistently exec for 30-45s while Baritone vertical-mines, instead of leveraging existing cave systems where they exist.
- The chunking (`DESCEND_MAX_PER_CALL = 40`) becomes a band-aid: each chunked goto picks the current (px, pz) and continues digging in that column even after the previous chunk has shifted x/z.
- The arrival-tolerance check (Manhattan distance ≤ 2) sometimes fails for legitimate descents because Baritone ended at the right y but slightly off the original x/z column.

**The fix.** Baritone's `GoalYLevel(y)` goal type matches the agent's actual intent: any point at the target y satisfies the goal. Baritone routes through caves and natural shafts when they exist, falls back to mining when they don't, and arrival is "are we at the right y?" not "are we at the exact x/y/z?" — delegated to Baritone via `PathEvent.AT_GOAL` rather than a mod-side position predicate (the predicate would misfire mid-route as the player traverses through the target y-plane).

**API shape.** New optional `goal_type: "block" | "y_level"` on `/baritone/goto`. Default `"block"` preserves current behavior. When `"y_level"`, only `y` is required; the mod constructs `GoalYLevel(y)` and uses `PathEvent.AT_GOAL` as the sole arrival signal (no mod-side position predicate, `arrival_tolerance` ignored). `x` / `z` may be sent (for client-side schema uniformity) but are ignored.

**Caller-side change** (lives in `craft/`, not homunculus):
- `handle_descend` and `handle_surface` switch their `_baritone_goto` call to `goal_type="y_level"` and drop the `(px, pz)` they currently haul along.
- `handle_goto_corpse`, `handle_travel`, `_goto_home` stay on `"block"` (they want specific points, not a plane).

**Future goal types deferred.** `GoalNear(x, y, z, radius)`, `GoalGetToBlock(x, y, z)`, etc., could plug into the same `goal_type` switch if a future use case arises. v1.4 ships `"block"` and `"y_level"`; the others stay deferred.

**Stuck/unreachable semantics.** Unchanged. A `"y_level"` goto that can't reach the target y (e.g., bedrock floor for a deep descend) returns `unreachable` or `stuck`, same as today's `"block"` goto.

#### Inventory-protected goto *(planned — v1.3)*

**The failure mode.** Baritone's default `allowPlace=true` lets it consume "throwaway" blocks (cobblestone, dirt, netherrack — Baritone's hardcoded `acceptableThrowawayItems` list) to pillar-up out of self-dug shafts, bridge gaps, and traverse otherwise-impassable terrain. This is the correct default for *explicit* movement (the `travel` tool — the agent said "go this way, I don't care how"), but wrong for *implicit* movement initiated by the harness before a craft or smelt that's about to consume those same items.

Concrete instance observed 2026-05-11 (r3 rollout, T4-T7 doom loop):

1. `mine_stone(10)` → Baritone picks the nearest stone, dug straight DOWN, agent ends in a 1-wide shaft with 10x cobblestone.
2. `craft(stone_pickaxe)` → `_craft_recursive` triggers `_goto_home()` because the crafting_table is back on the surface.
3. Baritone pillars back up the shaft, *using the freshly-mined cobblestone* as throwaway placement.
4. Agent arrives at the table with 0 cobblestone. `/craft` returns `missing_ingredients` (rendered as `no_recipe — must be acquired` by `_craft_recursive`'s fallback path — misleading but downstream of the real issue).
5. Agent mines more stone → step 1 → infinite loop.

The fix lives in `craft/`, not homunculus: pre-craft, expand the recipe's leaf-level ingredient requirements (we already have `CRAFTING_RECIPES` for this), check overlap with Baritone's throwaway set, and decide whether to pass `allow_place=false` based on inventory headroom. The substrate handles the policy; the agent stays unaware.

**Two compounding gotchas in Baritone's defaults.** Beyond consuming the wrong items, Baritone has a second limitation: **it won't move items from main inventory to hotbar** to acquire a placement block. It will only place items already in hotbar slots. So even if dirt is at inventory slot 13, Baritone can't reach it for pillar-up. This means the protection design has to address both *which* items Baritone is allowed to place AND *which* items are physically available to the placement code path (hotbar-resident). The `/equip` layout reserves slot 6 for building blocks but picks "most plentiful builder" without policy awareness — fine in isolation, wrong when paired with a goto that needs to *preserve* the most-plentiful builder for an upcoming craft.

**Why three parameters instead of one.** The natural protection levels compose, and a single boolean can't express the middle ground:

| Scenario | `allow_place` | `throwaway_items` | `ensure_throwaway_in_hotbar` | Outcome |
|----------|---------------|-------------------|------------------------------|---------|
| Default v1.2 behavior | `true` | `null` | `false` | Baritone uses whatever's in hotbar from its defaults. |
| Strict protection | `false` | `null` | `false` | Baritone won't place anything. Goto may `unreachable` in tight terrain. |
| **Surgical protection** | `true` | `["minecraft:dirt"]` | `true` | "Bridge with dirt only, and stage one in slot 6 so you actually can." Preserves cobblestone for the upcoming `stone_pickaxe` craft while keeping traversal robust. |
| Loud-fail strict | `true` | `[]` | `false` | Empty list collapses to no-placement; same loud-fail as `allow_place=false`. |

The third row is the *normal-case* substrate protection. The first/second rows are useful as escape hatches and for compatibility.

**Selection heuristic (most-plentiful match).** When `ensure_throwaway_in_hotbar=true`, the mod picks from inventory by maximum total count. Reasoning: traversal-completion is the goal; running out of pathing blocks mid-traversal causes Baritone to `unreachable` even when goal is technically pathable. Maximizing block count maximizes goto robustness. The alternative ("burn the least valuable item") was rejected because (a) the caller already encoded value by filtering `throwaway_items`, and (b) abundance ≈ disposability in practice for the throwaway set.

**Slot-6 restore on goto exit.** The hotbar swap is scoped to the goto call. Pre-call: snapshot slot 6's current ItemStack (id + count + nbt), swap the selected throwaway in. Post-call (success OR failure): restore the snapshot to slot 6. This means a "build → path → build" cycle works cleanly — the building-block in slot 6 returns after pathing, the agent can keep stacking. The alternative (let the swap persist) would force /equip to re-run after every goto and would surprise callers using the building-block slot for non-pathing purposes.

**Failure-mode change.** Under any non-default configuration, Baritone may return `unreachable` in terrain it would otherwise have bridged. The craft side surfaces this as a normal goto failure and the agent can recover (call `surface()` first to clear the shaft, retry the craft with `location="here"`, etc.). Loud failure is strictly better than the current silent-inventory-consumption mode, which presents as an unrecoverable doom loop.

**Caller-side policy sketch** (lives in `craft/`, not homunculus):
```python
THROWAWAY = {"minecraft:cobblestone", "minecraft:dirt", "minecraft:netherrack"}

def _throwaway_policy(recipe_item, recipe_count):
    """Decide goto protection level for an upcoming craft.

    Returns (allow_place, throwaway_items, ensure_in_hotbar).

    Rule: any recipe ingredient in the throwaway set is *reserved* for the
    craft, full stop. Baritone is restricted to the remaining throwaway
    items on this trip; if everything's reserved, placement is disabled
    entirely.
    """
    needs = _recipe_needs(recipe_item, recipe_count)  # recursive expansion
    protected = needs.keys() & THROWAWAY

    if not protected:
        return (True, None, False)  # no overlap, defaults

    permitted = sorted(THROWAWAY - protected)
    if not permitted:
        return (False, None, False)  # everything reserved → strict, may fail-unreachable

    return (True, permitted, True)  # surgical: restrict to permitted, stage one in hotbar
```

**Why no inventory check or buffer.** An earlier iteration tried `have < need + buffer` to allow Baritone to use throwaway items when the agent had a surplus. The buffer turned out to be the wrong abstraction: Baritone's per-trip placement consumption is unbounded (observed 25 throwaway blocks consumed on a 49-block ascent during r4 T8), so any constant buffer is exploitable. The substrate-initiated goto is a short recipe-anchored trip — being strict here costs little because the trip is bounded by the recipe context. Long-horizon traversal uses the `travel()` tool which keeps default behavior and consumes the throwaway surplus organically.

Call sites: anywhere the harness calls `_goto_home()` on behalf of an upcoming craft — currently `_craft_recursive` (`requires_crafting_table` fallback), `handle_craft` (upfront `location="home"`), and `handle_smelt` (both `location="home"` and `requires_furnace` fallback, treating the upcoming op as a furnace-craft for protection purposes since the post-goto path may auto-craft a furnace).

### `POST /baritone/stop`

Cancel any in-flight Baritone task by calling `pathingBehavior.cancelEverything()`.

No body.

**Mod behavior:**
1. On the game thread, sample `pathingBehavior.isPathing() || customGoalProcess.isActive()` into `wasActive`. Then call `pathingBehavior.cancelEverything()` (synchronous; tears down all Baritone-driven processes). Return `wasActive` as `acked`. **Do not** use `cancelEverything()`'s return value — it returns `true` even when Baritone was already idle.

**Success response:**
```json
{
  "success": true,
  "acked": true,
  "message": "Baritone canceled (was running)"
}
```

`acked` is `true` if Baritone was running (`pathingBehavior.isPathing()` or `customGoalProcess.isActive()`) at the moment we sampled, `false` if it was already idle. We sample state *before* calling `cancelEverything()` because that call returns `true` even when nothing was running (empirically verified — its boolean does not mean "anything was canceled"). Either way `success: true`; the caller's invariant ("Baritone is not running after this returns") holds.

**Failure response:** standard `{success: false, reason, message}` with `reason: "baritone_not_loaded"` or `internal_error`. No `busy` failure — `/baritone/stop` bypasses the session lock so it can interrupt an in-flight `/baritone/mine` or `/baritone/goto`. When that happens, Baritone fires `PathEvent.CANCELED` to active listeners; `/baritone/goto` folds that into its `canceled` reason, and `/baritone/mine` ignores the event and detects the stop via its inventory post-check (`interrupted` reason).

### `POST /baritone/excavate` *(implemented)*

Drive Baritone's `IBuilderProcess.clearArea(p1, p2)` to dig out an axis-aligned box and wait for the builder to idle. Replaces the multi-step Reddit recipe for clearing a shelter-sized space (`.b sel 1` / `sel 2` / `sel expand` / `sel cleararea` + three settings tweaks) with one call.

Request body:
```json
{
  "x1": 12, "y1": 62, "z1": -7,
  "x2": 15, "y2": 64, "z2": -4,
  "timeout_seconds": 120
}
```

Fields:
- `x1, y1, z1, x2, y2, z2` (required) — opposite corners of the box, inclusive. Floats are rejected. Order doesn't matter (the mod normalizes to min/max internally).
- `timeout_seconds` (optional, default 120). Capped at 600.

**Volume cap.** The box volume `(x2-x1+1)*(y2-y1+1)*(z2-z1+1)` must be ≤ 500 blocks. This is shelter-sized — for example 8×4×8=256 or 6×5×6=180. Above that, the mod returns `invalid_request` rather than spending a 10-minute timeout on a stadium dig. If the caller genuinely needs a larger excavate, they should split it into adjacent boxes.

**Mod behavior:**
1. Baritone-loaded + volume + lock checks. If volume exceeds cap → `invalid_request`.
2. **Off the render thread**, prewarm `new BlockOptionalMeta(Blocks.AIR)`. `clearArea` internally constructs a `FillSchematic(air)` whose BOM would hit the `drops()` deadlock on first on-thread construction (same root cause as `/baritone/mine`). Prewarming populates the BOM drops cache.
3. **Pre-scan**: count non-air, non-torch blocks in the box. If zero, short-circuit to `already_clear`.
4. **Settings snapshot-and-mutate**, guarded by try/finally on lock release:
   - `buildIgnoreBlocks` → `[torch, wall_torch, soul_torch, soul_wall_torch]` (preserve player-placed lighting if re-clearing an existing shelter; harmless for fresh excavates).
   - `buildInLayers` → `true` (build top-to-bottom or bottom-to-top in strict layers — empirically far less likely to get Baritone stuck mid-dig).
   - `layerOrder` → `true` (top-down ordering, matches the Reddit recipe).
5. On the game thread, register an `AbstractGameEventListener` and call `getBuilderProcess().clearArea(new BlockPos(min), new BlockPos(max))`.
6. Poll on tick events for `builderProcess.isActive()`. Wait for the standard start-window (15s) and then the remaining `timeout_seconds` budget.
7. When `isActive()` flips false after a prior active state, run a **post-scan** of the box. Zero remaining non-air non-torch blocks → `cleared`. Non-zero → `partial`.
8. Always restore the three settings + `cancelEverything()` before releasing the lock.

**Success response:**
```json
{
  "success": true,
  "reason": "cleared",
  "box": [12, 62, -7, 15, 64, -4],
  "volume": 48,
  "remaining": 0,
  "message": "excavate completed; 48 blocks cleared"
}
```

**Failure response:**
```json
{
  "success": false,
  "reason": "partial",
  "box": [12, 62, -7, 15, 64, -4],
  "volume": 48,
  "remaining": 3,
  "message": "builder idled with 3 of 48 blocks unbroken"
}
```

`reason` is one of:
- `cleared` — builder went active then inactive; post-scan confirms zero non-air non-torch blocks remain. (Success.)
- `already_clear` — pre-scan found nothing to break; builder was not invoked. (Success.)
- `partial` — builder idled but the post-scan shows N blocks still standing. Typically: lava-locked block, mob in the way, or a no-tool-for-this-block situation (no pickaxe and the block is stone). Caller decides whether to retry, equip a better tool, or accept partial.
- `never_started` — start window (15s) elapsed without `builderProcess.isActive()` going true. Usually means Baritone refused the call (mid-other-task; the lock should have caught that, but belt-and-suspenders).
- `timeout` — budget elapsed while builder was still active. Response includes the live `remaining` count.
- `invalid_request` — volume exceeds cap, or coords aren't integers.
- `busy`, `baritone_not_loaded`, `internal_error` — as `/baritone/mine`.

`PathEvent` is **ignored** during the excavate loop. The builder may emit `CALC_FAILED` for individual unreachable cells while still making progress on others; only `isActive()` flipping false is authoritative.

**Why not surface `ISelectionManager`.** The Reddit recipe drives `ISelectionManager` (`addSelection`, `expand`) to define a region, then `clearArea` mode on the builder process. `IBuilderProcess.clearArea(p1, p2)` is the one-call shortcut — it internally constructs a `FillSchematic` of air covering the box and runs the builder process directly. No selection state to leak across calls; nothing for the mod to clean up if a call is interrupted.

### `POST /baritone/fill` *(implemented)*

Mirror of `/baritone/excavate`: drive `IBuilderProcess.build(name, FillSchematic, origin)` to place a target block at every air cell in an axis-aligned box. Composes with `/baritone/excavate` for "dig out a shelter and seal the floor" — excavate first, then fill the floor slice.

Request body:
```json
{
  "block": "minecraft:cobblestone",
  "x1": 12, "y1": 62, "z1": -7,
  "x2": 15, "y2": 62, "z2": -4,
  "timeout_seconds": 120
}
```

Fields:
- `block` (required) — namespaced block id (`cobblestone` and `minecraft:cobblestone` both work; the mod normalizes).
- `x1, y1, z1, x2, y2, z2`, `timeout_seconds` — same shape as `/baritone/excavate`. Same 500-block volume cap.

**Pre-flight: fill block must be in hotbar.** Baritone won't reach into main inventory for placement blocks — it only places from hotbar slots 0-8. If the fill block isn't in any hotbar slot when this is called, the mod returns `missing_block` before kicking off the builder. **Caller pairs `/equip` with `/baritone/fill`** to stage the fill block first. (We deliberately do not auto-equip — the agent already has equip policy via `/equip` and adding implicit hotbar mutation here would surprise composed callers.)

**Mod behavior:**
1. Resolve `block` → registered `Block`. Unknown → `invalid_request`. Block has no item form → `invalid_request`.
2. Baritone-loaded + volume + lock checks.
3. **Off the render thread**, prewarm `new BlockOptionalMeta(targetBlock)` and hand it to the `FillSchematic`. Same deadlock prevention as `/baritone/excavate`.
4. **Hotbar check**: iterate slots 0-8; require ≥1 stack of the fill item. If absent → `missing_block`.
5. **Pre-scan**: count air cells in the box. If zero, short-circuit to `already_filled`.
6. **Settings snapshot-and-mutate**, guarded by try/finally:
   - `buildIgnoreBlocks` → `[]` (no torch exception — fill semantics are "place where there's air," not "preserve special cells").
   - `buildInLayers` → `true`.
   - `layerOrder` → `true`.
   - `buildIgnoreExisting` → `true`. **Important**: this makes the builder skip cells that already contain *any* block, replacing only air. So "fill" is "fill the empties" — non-air mismatched cells (e.g., natural dirt where the caller wants cobble) are left alone. If the caller wants strict-replace semantics, they should `/baritone/excavate` first, then `/baritone/fill`.
7. On the game thread, build the `FillSchematic(width, height, depth, bom)` and call `getBuilderProcess().build("homunculus-fill", schem, new Vec3i(x_min, y_min, z_min))`.
8. Poll for `builderProcess.isActive()` flipping false after a prior active state. Post-scan counts remaining air cells. Zero → `filled`. Non-zero → `partial`.
9. Restore settings + `cancelEverything()` before releasing the lock.

**Success response:**
```json
{
  "success": true,
  "reason": "filled",
  "block": "minecraft:cobblestone",
  "box": [12, 62, -7, 15, 62, -4],
  "volume": 16,
  "remaining": 0,
  "message": "fill completed; 16 cells now non-air"
}
```

**Failure response:**
```json
{
  "success": false,
  "reason": "missing_block",
  "block": "minecraft:cobblestone",
  "box": [12, 62, -7, 15, 62, -4],
  "volume": 16,
  "remaining": 0,
  "message": "fill block 'minecraft:cobblestone' not present in hotbar (Baritone can't reach main inventory)"
}
```

`reason` is one of:
- `filled` — post-scan shows zero air cells. (Success.)
- `already_filled` — pre-scan found no air cells; builder was not invoked. (Success.)
- `partial` — builder idled with air cells remaining (ran out of fill stack mid-build, or couldn't reach some cells).
- `missing_block` — fill block not in hotbar at start. Caller should `/equip` and retry.
- `invalid_request` — unknown block id, block has no placeable item form, volume cap exceeded, or non-integer coords.
- `never_started`, `timeout`, `busy`, `baritone_not_loaded`, `internal_error` — as `/baritone/excavate`.

**Composition pattern for "floored shelter":**
```
POST /baritone/excavate  { x1..z2 of full shelter volume }
POST /equip              { block: "cobblestone" }
POST /baritone/fill      { block: "cobblestone", floor slice (y1==y2==shelter_floor_y) }
```
The excavate pass clears any natural ground irregularities first; the fill pass then plugs the (now-airy) floor with a solid slab. Walls / ceiling are the same pattern with different slices.

### `POST /wurst/setting` *(implemented)*

Generalises the existing `/wurst/hack` reflection bridge from "toggle the module" to "configure the module's settings." First-class motivation is AutoDrop's `Items` filter — the craft side wants an aggressive whitelist policy ("drop everything except items relevant to the current tech tier") that grows as the agent progresses (wood → stone → iron → diamond). The setting that controls this is `AutoDrop.Items`, an `ItemListSetting` whose default value is the literal string `"default"` in `wurst/settings.json` and whose configured shape is a JSON array of `minecraft:<id>` strings. We can't edit it pre-launch and call it done — tech-tier ramps need to mutate it mid-rollout, and the file is read at startup only. Hence: an HTTP surface that reflects into Wurst's setting machinery.

**Scope.** Two routing paths:

1. **`ItemListSetting`** — op-aware mutation (`replace` / `add` / `remove` / `reset`) of an ArrayList of `minecraft:<id>` strings. This is the original AutoDrop path; it preserves the registry-resolution + partial-fail posture documented below.
2. **Everything else** — `CheckboxSetting`, `SliderSetting`, `EnumSetting`, `BlockListSetting`, `TextFieldSetting`, … — delegate to `Setting.fromJson(JsonElement)`. The request's `value` is converted to a `JsonElement` and handed to the setting; the subclass decides whether the shape is acceptable. `op` is rejected on this path with `bad_request` — it only applies to `ItemListSetting`.

The motivating reflection chain is:

```
net.wurstclient.Feature.getSettings() -> Map<String, Setting>     // settings registry per hack
net.wurstclient.settings.Setting:
  fromJson(JsonElement)                                            // every subclass implements this
  toJson() -> JsonElement
net.wurstclient.settings.ItemListSetting:                          // op-aware mutation
  getItemNames() -> List<String>                                  // current values, e.g. ["minecraft:dirt", ...]
  add(net.minecraft.world.item.Item)
  remove(int index)
  resetToDefaults()
  fromJson(JsonElement)                                            // "default" string sentinel or JSON array of ids
```

Confirmed via `javap -p` against `Wurst-Client-v7.51.2-MC1.21.4.jar`. Wurst writes the literal string `"default"` to `settings.json` when the in-memory list equals the built-in defaults; on read, the same sentinel triggers `Arrays.asList(defaultNames)` to be loaded. Our endpoint mirrors this: a `replace` with an empty body OR an explicit `reset` op restores defaults; otherwise the supplied id list becomes the new value.

**Request body (ItemListSetting path):**
```json
{
  "hack": "AutoDrop",
  "setting": "Items",
  "op": "replace",
  "value": ["minecraft:dirt", "minecraft:cobblestone", "minecraft:sand"]
}
```

**Request body (generic `fromJson` path):**
```json
{"hack":"KillAura","setting":"Filter passive mobs","value":false}
{"hack":"AutoEat","setting":"Min hunger","value":12}
{"hack":"AutoTool","setting":"Repair mode","value":"breaks soonest"}
```

| Field     | Type            | Required | Notes                                                                                                                                |
|-----------|-----------------|----------|--------------------------------------------------------------------------------------------------------------------------------------|
| `hack`    | string          | yes      | Case-insensitive, same matching rules as `/wurst/hack`.                                                                              |
| `setting` | string          | yes      | Exact-match against `Feature.getSettings()` keys. Case-insensitive fallback if no exact hit (parallel to hack lookup).               |
| `op`      | string          | no       | ItemListSetting only. One of `replace` (default), `add`, `remove`, `reset`. Rejected with `bad_request` on any other setting type.   |
| `value`   | any JSON        | yes      | Shape depends on the target setting subclass: array of strings for ItemList/BlockList, boolean for Checkbox, number for Slider, string for Enum, etc. Validated by `Setting.fromJson` on the generic path. |

**Item id resolution.** Strings are normalised through `ResourceLocation.tryParse` + `BuiltInRegistries.ITEM.containsKey`. Unknown ids fail the whole request with `unknown_item_id` and a list of which entries didn't resolve — same posture as `/baritone/throwaway_items`. Partial application is not allowed.

**Success response:**
```json
{
  "success": true,
  "hack": "AutoDrop",
  "setting": "Items",
  "op": "replace",
  "before": ["minecraft:poppy", "minecraft:dandelion", "minecraft:wheat_seeds"],
  "after":  ["minecraft:dirt", "minecraft:cobblestone", "minecraft:sand"],
  "is_default_after": false
}
```

`before` and `after` are the resolved item-id lists snapshotted on the game thread, immediately before and after the mutation. `is_default_after` reports whether `after` equals the setting's built-in defaults — useful for the caller to know "would Wurst now serialize this as `'default'` in settings.json?" without re-checking.

**Success response (generic `fromJson` path):**
```json
{
  "success": true,
  "hack": "KillAura",
  "setting": "Filter passive mobs",
  "type": "FilterPassiveSetting",
  "changed": true
}
```

`type` is the concrete Setting subclass name; `changed` is computed by comparing `toJson()` before and after the call. No `before`/`after` snapshot on this path — the caller knows what value it sent.

**Failure response (standard shape):** `{success:false, reason, message}` with `reason` ∈ {`wurst_not_loaded`, `hack_not_found`, `setting_not_found`, `unsupported_setting_type`, `unknown_item_id`, `wrong_value_type`, `bad_request`, `internal_error`}. `unknown_item_id` carries an extra `unknown: [string, ...]` field listing the rejected entries (mirrors `/baritone/throwaway_items`). `wrong_value_type` is returned when `Setting.fromJson` throws — typically `JsonParseException` (e.g. sending a number to a CheckboxSetting) — or when the ItemListSetting path receives a non-array `value`.

**GET sibling — `GET /wurst/setting?hack=AutoDrop&setting=Items`** *(specified, not yet implemented)*. Returns the current resolved list without mutating. Same response shape as the success body above, minus `op` and `before` (only `after`). Lets the agent or operator inspect what's currently in force — important for debugging tech-tier ramps where the whitelist drifts over a long rollout.

**Body size.** The handler must accept payloads up to at least 128KB. A full ItemListSetting can carry ~1400 ids × ~50 chars ≈ 70KB; doubling that gives headroom. The `/wurst/hack` handler's 1024-byte cap is way too tight here. Recommend 256KB as a round cap.

**Threading + persistence.** Mutations run on the client thread (Wurst's `ItemListSetting.add()` updates an `ArrayList` that's read by `AutoDropHack`'s tick handler on the same thread). The change is in-memory and applies on the next tick; AutoDrop has no internal cache. Wurst auto-persists settings when its disk-flush cycle fires (existing behavior — we don't trigger writes ourselves), so the change survives in `wurst/settings.json` for the rest of the session and across `/wurst/hack` toggles. It does **not** survive a JVM restart unless Wurst has flushed by then; callers that need pre-launch state should keep writing `wurst/settings.json` directly.

**Open design decision: item-registry exposure.** The motivating policy ("drop everything except this whitelist") needs the complement of the whitelist, i.e. the full set of vanilla item ids. Two options:

1. **Craft-side computes the complement** from a hardcoded list of MC item ids (~1100 entries, mostly stable across MC versions). Bloats the brain repo but keeps the protocol simple.
2. **New `GET /items`** endpoint returns `BuiltInRegistries.ITEM.keySet()` as a sorted JSON array. Authoritative per-server (catches mod items) but adds a new endpoint for one use case.

Recommend option 1 for v1 (the whitelist policy already lives in Python; adding a hardcoded id list is one more constant). Revisit if a modded server use case shows up.

**Open design decision: `add` and `remove` semantics on duplicates.** `ItemListSetting` deduplicates internally (the `fromJson` bytecode shows `distinct().sorted()` over the incoming list), so `add` of an already-present id is a no-op and `remove` of an absent id is too. Reporting these as success-with-no-change is fine and matches the existing `/wurst/hack` `changed: bool` pattern. The response should include a `changed: bool` field to distinguish "request was valid and applied" from "request was valid but already at target state."

**Why not just edit `wurst/settings.json`.** File is read once at Wurst startup. Hot-edits to it don't trigger a reload; Wurst will eventually clobber the file with its in-memory state. The reflection path is the only safe runtime mutation surface.

**Why not extend `/wurst/hack`.** Toggle and setting-mutation are different operations with different validation rules and failure modes. Reusing the path would force `enabled` to be optional and `setting`/`value` to be optional, breaking the existing handler's request schema. Cheaper to add a sibling endpoint.

### Baritone API integration (shared)

All five endpoints drive Baritone through its public Java API rather than parsing chat. The relevant surface:

| Operation                                  | API call                                                                                          |
|--------------------------------------------|---------------------------------------------------------------------------------------------------|
| entrypoint                                 | `BaritoneAPI.getProvider().getPrimaryBaritone()` → `IBaritone`                                    |
| start mining                               | `IBaritone.getMineProcess().mine(int count, BlockOptionalMeta... boms)` — with **prebuilt** BOMs from the off-thread prewarm (see mine endpoint) |
| start goto                                 | `IBaritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z))`                         |
| start excavate                             | `IBaritone.getBuilderProcess().clearArea(BlockPos p1, BlockPos p2)`                               |
| start fill                                 | `IBaritone.getBuilderProcess().build(String name, FillSchematic(w,h,d, prebuilt-BOM), Vec3i origin)` |
| query "is process running"                 | `IBaritoneProcess.isActive()` on the relevant process (`mineProcess`, `customGoalProcess`, `builderProcess`) |
| pathing state                              | `IBaritone.getPathingBehavior().isPathing()`                                                      |
| path-calc events / failures                | `IGameEventListener.onPathEvent(PathEvent)` — `CALC_FAILED` (terminal for mine/goto), `AT_GOAL` (terminal for goto), `CANCELED` (terminal for goto, ignored by mine/excavate/fill) |
| register the listener                      | `IBaritone.getGameEventHandler()` → `IEventBus.registerEventListener(IGameEventListener)`; extend `AbstractGameEventListener` for default no-op overrides |
| cancel everything                          | `pathingBehavior.cancelEverything()` — return value is **not reliable** (returns `true` even when idle). Sample `isPathing()`/`isActive()` ourselves for the `acked` field. |

**Build setup.** Add `baritone-api` as a `modCompileOnly` dependency in `build.gradle` — homunculus compiles against the API jar but does not bundle Baritone. At runtime, Baritone is provided by the user's mod loader (same posture as Wurst today). If `baritone.api.BaritoneAPI` isn't reachable at startup, all `/baritone/*` endpoints return `baritone_not_loaded`; the rest of the mod is unaffected.

**Why not chat parsing.** An earlier draft of this spec scraped `[Baritone]` chat lines (`Have N valid items`, `Path goes for X blocks`, `cost coefficient is greater than three`, `reached goal`, `ok canceled`) for terminal signals. That was a lift-and-shift of `craft/mine.py`'s regex set and carried the same brittleness: format-change breakage across Baritone versions, formatting-component edge cases, dual `Path goes for` lines requiring first-only logic. Using the API gives ground-truth process state and removes string parsing from the mod entirely.

**Threading.** All Baritone API calls happen on the game thread via `MinecraftClient.execute(...)` per `CLAUDE.md`. `IGameEventListener` callbacks also fire on the game thread; the per-call wait machinery publishes plain enum/outcome values across threads to the HTTP handlers blocking in `.get()`. **Exception**: `BlockOptionalMeta` construction is deliberately performed on the HTTP worker thread (off the render thread) to avoid the `drops()` deadlock — see "Off-thread BOM prewarm" in the mine endpoint section.

**Cross-cutting locks.** A single `ReentrantLock` ("Baritone session lock") covers `/baritone/mine`, `/baritone/goto`, `/baritone/excavate`, and `/baritone/fill`. `/baritone/stop` deliberately does not take it. Lock acquisition is `tryLock` with zero wait; failure → `busy`. There is no queueing.

## Prerequisite: server-side recipe grant

**Assumption.** All vanilla recipes are already present in `ClientRecipeBook` by the time `/craft` is called. Homunculus does **not** force-unlock — it relies on the host server to grant every recipe at player-join via the normal vanilla sync (`ClientboundRecipeBookAddPacket`).

**Why this is server-side.** `ClientRecipeBook` is populated only with recipes the player has "unlocked." On a fresh world without intervention, that's near-empty: picking up `oak_log` unlocks only `oak_planks`, picking up planks unlocks plank-using recipes, etc. Forcing the full graph from the client side is infeasible in MP — `ClientboundRecipeBookAddPacket` only sends entries the player has unlocked, and the client doesn't have the full registry to iterate over. Resolving it on the server (e.g., `recipe give @s *` on join, or equivalent) covers SP and MP uniformly and keeps homunculus free of recipe-data responsibilities.

**Status (2026-05-10).** The bot's dev server grants all recipes on new-player join. Homunculus assumes this.

**Implication for failure semantics.** `no_recipe` from `/craft` and `/smelt` means "this item has no recipe in the registry," not "the player hasn't unlocked it." If the server isn't granting recipes, `/craft` will spuriously return `no_recipe`; that's a deployment misconfiguration, not a mod bug.

**Out of scope for v1.** Homunculus does not detect or work around servers that lack recipe grants. Running against an unmodified vanilla server without this configuration is unsupported.

## Behavioral guarantees

- **Synchronous.** Each request blocks until the operation completes or fails. Reasonable timeout (~5s) for safety.
- **Atomic.** A craft either succeeds fully or makes no inventory changes. No partial states visible to the agent.
- **Game-thread safe.** All game-state reads/writes go through `MinecraftClient.execute(...)` per `CLAUDE.md`.

### `GET /scan_blocks` *(specified, not yet implemented)*

Returns all non-air blocks in an axis-aligned bounding box. Read-only — no game state is modified.

**Query parameters:** `x1`, `y1`, `z1`, `x2`, `y2`, `z2` — integer world coordinates (any corner order; endpoint normalises).

**Volume cap:** 2000 blocks. Requests exceeding this return `invalid_request`.

**Success response:**
```json
{
  "box": [-4, 60, 24, 0, 63, 28],
  "volume": 100,
  "blocks": [
    {"x": -3, "y": 61, "z": 25, "id": "minecraft:grass",      "passable": true},
    {"x": -2, "y": 61, "z": 25, "id": "minecraft:tall_grass",  "passable": true},
    {"x": -1, "y": 60, "z": 24, "id": "minecraft:stone",       "passable": false}
  ]
}
```

`blocks` is sparse — air positions are omitted. `volume` is the full box cell count (including omitted air), useful for computing fill %. `passable` is derived from `BlockState.getCollisionShape(level, pos).isEmpty()` on the game thread — true for blocks a player walks through (tall grass, flowers, snow layers, etc.) but that are not air.

**Error reasons:**
- `invalid_request` — non-integer coords or volume > 2000.
- `out_of_range` — any chunk in the box is not loaded; move closer and retry.
- `internal_error`, `timeout` — as other endpoints.

**Composition note.** This endpoint is the general primitive. Higher-level operations like "trim passable blocks" are composed in `craft/tools.py` using this endpoint plus `/baritone/excavate`:

```python
# trim_passable(x1, y1, z1, x2, y2, z2):
#   1. GET /scan_blocks → filter blocks where passable == true
#   2. for each passable block: POST /baritone/excavate on that single position
```

The mod does not implement trim_passable; `craft/tools.py` does.

## Non-goals (v1)

- No streaming / async / SSE / WebSocket.
- No `use` endpoint — crafting-table presence is checked inside `/craft`. Tables are environmental, not a thing the agent activates.
- No NBT/component-aware crafting (dyed armor, repair recipes, enchanting).
- No multi-step server-side macros. The mod doesn't sequence "mine wood then craft pickaxe." The agent does.
- No candidate-cycling inside `/baritone/mine`. The mod handles one block id per call; iterating over wood/stone variants stays in `craft/`.

## Reference: how the agent will consume this

For implementer awareness only — you do not edit `craft/`. The agent's loop for a top-level goal like "wooden_pickaxe" looks roughly like:

```
goal = ("wooden_pickaxe", 1)
loop:
  resp = POST /craft {item, count}
  if resp.success:
    done
  if resp.requires_crafting_table and not resp.crafting_table_nearby:
    push subgoal: ensure crafting_table placed nearby
    continue
  for m in resp.missing:
    if m has a recipe (recurse on /craft):
      push subgoal: craft m
    else:
      push subgoal: acquire m via mining / other tool
  continue
```

The structured error fields (`missing`, `requires_crafting_table`, `crafting_table_nearby`) aren't decoration — they're the planner's input. Keep them honest and complete.
