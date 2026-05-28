package dev.toast.homunculus.mixin;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to {@link PacketEncoder}'s private {@code protocolInfo} field.
 * Phase 1 ({@link dev.toast.homunculus.PacketRoundtrip}) needs the active
 * {@link ProtocolInfo} so it can call its codec to encode/decode packets in
 * the live path. The PacketEncoder netty handler is the only place the live
 * ProtocolInfo is reliably reachable from a {@link
 * net.minecraft.network.Connection}'s pipeline.
 */
@Mixin(PacketEncoder.class)
public interface PacketEncoderAccessor<T extends PacketListener> {
    @Accessor("protocolInfo")
    ProtocolInfo<T> homunculus$protocolInfo();
}
