package dev.toast.homunculus.mixin;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read the private {@code entityId} field of
 * {@link ServerboundInteractPacket}. The packet exposes only
 * {@code getTarget(ServerLevel)} (server-side, requires a level the client
 * doesn't have) and a {@code dispatch} visitor for hand/location — neither
 * surfaces the raw entity id needed for the codec's entity-pointer head
 * (it must encode "which entity was targeted" → pointer into the observed
 * entity set, which is keyed by id).
 *
 * <p>Used by {@link dev.toast.homunculus.PacketFieldExtractor} when recording
 * {@code minecraft:interact} packets.
 */
@Mixin(ServerboundInteractPacket.class)
public interface ServerboundInteractPacketAccessor {
    @Accessor("entityId")
    int homunculus$entityId();
}
