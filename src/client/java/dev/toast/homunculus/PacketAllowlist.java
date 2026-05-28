package dev.toast.homunculus;

import java.util.Set;

/**
 * Phase 1 of the codec experiment (ml.MD §4a, §4b). The set of serverbound
 * play-state packet IDs we round-trip in {@link PacketRoundtrip}.
 *
 * <p>Cut at the spatial / abstract joint per §4b — pointers-into-observation
 * actions only. Inventory slot indices, container UIs, mod channels, tick
 * plumbing, and the connection handshake are explicitly excluded; they pass
 * through unchanged even when round-trip is enabled.
 *
 * <p>Distribution evidence behind the picks comes from the Phase 0 tap
 * (PacketTap) — 5s of one Baritone goto produces all of these and the noise
 * we're choosing to skip.
 */
public final class PacketAllowlist {

    public static final Set<String> SPATIAL_PLAY = Set.of(
            // Movement + look (Baritone's primary motor channel).
            "minecraft:move_player_pos",
            "minecraft:move_player_pos_rot",
            "minecraft:move_player_rot",
            "minecraft:move_player_status_only",
            // Movement inputs (jump/sprint/sneak edge events).
            "minecraft:player_input",
            "minecraft:player_command",
            // Block place / use / break-start (use_item, use_item_on, player_action).
            "minecraft:use_item",
            "minecraft:use_item_on",
            "minecraft:player_action",
            // Entity interact (right-click an entity).
            "minecraft:interact",
            // Attack swing (left-click). Included as a spatial action per
            // session decision; high volume but semantically a spatial verb.
            "minecraft:swing"
    );

    private PacketAllowlist() {}
}
