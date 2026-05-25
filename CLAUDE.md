# homunculus

Fabric mod that exposes a local HTTP API for the `craft` Minecraft agent. The body to craft's brain.

## Why this exists

`craft` is an LLM-driven Minecraft agent at the sibling repo `~/projects/mech_interp/craft/`. Today it talks to MC via xdotool keystrokes into the chat bar and tail-follows the log file for feedback. That works for Baritone-driven mining but dead-ends on crafting — neither Baritone nor Wurst exposes a usable crafting command. Rather than build a brittle xdotool-into-recipe-book hack, we're pulling the long-planned Fabric bridge forward and using crafting as its first capability. Crafting is also a friction probe: if it's painful, we want to know fast.

## Scope of v1

Build the smallest mod that unblocks crafting. Nothing more.

**Endpoints:**
- `POST /craft` — body: `{"item": "minecraft:planks", "count": 4}`. Returns success/error JSON. Blocks until the craft completes or fails.
- `GET /inventory` — returns full player inventory as JSON.

**Crafting limits for v1:**
- Inventory grid (2×2) only: planks, sticks, crafting_table, torches, etc.
- 3×3 / table crafts (pickaxes, swords) are explicitly **deferred** — those need block placement + screen handlers + slot packets. Return a clear error if asked to craft one. Defer to v1.1.

**Out of scope for v1 — do not build:**
- Replacing the existing `#mine` driver (craft keeps using xdotool for Baritone)
- Async / streaming / WebSocket
- Auth (localhost-only bind is the security boundary)
- World state, position, mob awareness, chat read-back

## Hard requirements

- **Minecraft 1.21.4**, **Fabric** (not Forge/Quilt), **Java 21**
- **Mojmap** (Mojang official mappings) via fabric-loom
- **Sync HTTP**: request blocks until completion or timeout
- **Bind 127.0.0.1 only**
- **Embedded `com.sun.net.httpserver.HttpServer`** — zero non-Fabric dependencies
- **JSON**: built-in formatting or a single tiny dep. No Jackson/Gson kitchen-sink.

## The game-thread footgun (read this first)

The HTTP handler runs on the HTTP server's thread, which is **not** the game thread. Any read or mutation of game state must be scheduled back via `MinecraftClient.getInstance().execute(() -> ...)`. Wrap this in a single helper from day one — e.g. `runOnClient(Supplier<T>)` returning a `CompletableFuture<T>` that the HTTP thread `.get()`s with a timeout. Get this right once, never think about it again. Touching `MinecraftClient.player` from the wrong thread is the most likely source of intermittent crashes and confusing race bugs.

## Decisions already made — don't relitigate

- Fabric, not Forge/Quilt
- Mojmap, not Yarn
- Sync HTTP, not async/streaming
- Localhost-only bind
- Inventory-only crafts in v1
- Sibling repo (this directory), not nested in `craft/`
- Wurst was originally runtime-only; that constraint was lifted on 2026-05-14 when the `/wurst/hack` + `/wurst/status` bridge landed (reflection-only against `net.wurstclient.WurstClient.INSTANCE`, so no compile-time dep on Wurst). Toggling KillAura/AutoEat/AutoTool from the harness is now a load-bearing substrate primitive — see `Wurst.java` + `WurstHackHandler.java` + `WurstStatusHandler.java`.

## How to proceed

1. Stand up a Fabric 1.21.4 mod project: gradle, fabric-loom configured for mojmap, `fabric.mod.json`, a `ClientModInitializer` entrypoint. Use the current official Fabric example template as a base — don't hand-roll the boilerplate.
2. Add the embedded `HttpServer`, started from the client mod entrypoint. Pick a port (suggestion: 25566) and document it in this file.
3. Build the `runOnClient` thread-bridge helper.
4. Implement `GET /inventory` first — simplest endpoint, validates the thread plumbing end-to-end.
5. Implement `POST /craft` for inventory-grid crafts.
6. Document JSON shapes and the manual curl test recipe in a short README section.

Primary dev loop is manual curl + watching the game. No automated test framework needed for v1.

## When to ask vs just decide

- **Just decide and document:** package name, port number, JSON field naming, Gradle/loom minutiae, choice of tiny JSON helper, error message wording, whether to use Fabric API helpers or vanilla calls for a given thing.
- **Ask the user** anything that changes scope, anything that requires Mojang account or dev-launcher steps the user has to perform, anything that fights the "minimum mod" framing, any sign that 2×2 inventory crafts are going to be more than a few hundred lines of Java. The user is treating v1 as a friction probe — surfacing real blockers fast is more valuable than burning a day on yak-shaving.

## Counterpart: how `craft` will consume this

For your awareness; you do not edit the `craft` repo. The Python agent will eventually call `requests.post("http://127.0.0.1:<port>/craft", json={"item": ..., "count": ...})` and get back a JSON outcome that gets routed into its tool-result message. Mirror the existing handler return-shape from `craft/tools.py`: success is a one-line human-readable outcome, errors are clear failure strings.
