# homunculus bug log

Outstanding behavioral bugs in the v1 endpoints, filed from `craft/` rollouts. Each entry is one issue, sized for the maintainer agent to pick up.

---

## BUG-001: `/smelt` auto-fuel doesn't combine fuel types

**Status:** Fixed 2026-05-10. `Smelts.pickFuelPlan` now accumulates across tiers; `Smelter` feeds each fuel component into the slot in sequence, waiting for the slot to drain between types. The `missing_fuel` shortfall is now framed in whatever fuel the player actually has (falling back to `oak_planks` if they hold none), not an arbitrary coal suggestion. `/smelt`'s `fuel_consumed` response field is now a list of `{id, count}` entries (one per fuel type used).

**Severity:** High — dead-ends the agent at the iron-smelting step. Once an agent has acquired raw_iron but doesn't have a single fuel type with enough burn capacity to cover the requested count, smelt becomes unrecoverable: every retry returns the same misleading error, and the LLM has no way to derive the actual fix from the structured response.

**Reproduction (cold-start rollout, 2026-05-10, turns 16–24).**

Inventory at smelt time:
- 1× minecraft:charcoal (= 8 smelts worth, per SPEC burn table)
- 2× minecraft:oak_log (= 3 smelts)
- 2× minecraft:oak_planks (= 3 smelts)
- **Total fuel available: ~14 smelts worth.**

Request: `POST /smelt {"input": "minecraft:raw_iron", "count": 10, "fuel": null}`

Response (repeated across 6 turns, varying `location`):
```json
{
  "success": false,
  "reason": "missing_fuel",
  "missing": [{"id": "minecraft:coal", "count": 1}],
  "message": "..."
}
```

**Diagnosis.** The current auto-fuel selector appears to:
1. Pick *one* fuel type that the player has (charcoal, 1× available, covers 8 smelts).
2. Compute residual smelts needed (10 − 8 = 2).
3. Frame the residual in **a different fuel type the player doesn't have** (coal, `ceil(2/8) = 1`) and report it as `missing`.

So the agent is told it needs "1× coal" while holding 2× planks and 2× logs that would each cover the residual several times over.

**Proof the underlying smelt path works:** at turn 19, `smelt(oak_log, count=1)` succeeded and consumed 1× oak_plank as auto-picked fuel. Single-type fuel coverage works fine; the bug is specifically the lack of *combination*.

**Recommended fix.**

Replace the single-fuel selector with an accumulating one. Pseudocode:

```python
def pick_fuel(inventory, smelts_needed):
    fuel_order = [
        ("minecraft:stick",          100),   # burn ticks (200 = 1 smelt)
        ("minecraft:oak_sapling",    100),   # and all other saplings
        ("minecraft:*_planks",       300),   # any planks
        ("minecraft:*_log",          300),   # any logs/stems
        ("minecraft:charcoal",      1600),
        ("minecraft:coal",          1600),
        ("minecraft:coal_block",   16000),
        ("minecraft:lava_bucket",  20000),
    ]
    plan = []   # list of (id, count) to consume
    remaining_ticks = smelts_needed * 200
    for pattern, burn_ticks in fuel_order:
        for item in inventory_matching(pattern):
            take = min(item.count, ceil(remaining_ticks / burn_ticks))
            if take > 0:
                plan.append((item.id, take))
                remaining_ticks -= take * burn_ticks
            if remaining_ticks <= 0:
                return plan
    return None  # truly insufficient
```

If `pick_fuel` returns `None`, then return `missing_fuel` with the **actual shortfall**, framed in the cheapest fuel that would close it. If it returns a plan, run the smelts feeding fuels in order (or batch-loading the furnace UI if multiple fuel types in sequence isn't supported by the screen handler — implementer's call).

**Corollary fix to the error message.** When `missing_fuel` does fire, the `missing` array should reflect *what would actually unblock the request*, not an arbitrary fuel type the player doesn't have. If the residual is small (e.g., 2 smelts), suggest the cheapest fuel that covers it (1× plank or 1× log). The LLM uses `missing` as its planning input; a misleading entry sends it on a wild coal hunt instead of the obvious "burn the planks I'm holding."

**SPEC change.** The current `/smelt` SPEC section's auto-fuel ranking guidance ("iterate fuel candidates in this order, pick the first with non-zero count") already implies single-type selection. That sentence should be replaced with the combining policy above.

---
