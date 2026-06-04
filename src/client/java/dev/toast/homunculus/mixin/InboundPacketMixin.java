package dev.toast.homunculus.mixin;

import dev.toast.homunculus.PlayerObsSnapshot;
import dev.toast.homunculus.ServerFeedbackTap;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inbound mirror of {@link OutboundPacketMixin}. HEAD-injects into
 * {@code Connection#channelRead0(ChannelHandlerContext, Packet)} — the single
 * funnel every received packet passes through before dispatch — to tap the
 * server's corrective-feedback packets (rubber-band position corrections and
 * player velocity overrides) for {@link ServerFeedbackTap}.
 *
 * <p>Observe-only: never cancels, never mutates. The two {@code instanceof}
 * checks are cheap on the Netty IO thread, so the heavy inbound traffic (chunk
 * data, entity tracking) is filtered out before any work. {@code player_position}
 * is player-only by virtue of the ClientGamePacketListener; {@code
 * set_entity_motion} is filtered to the local player here so combat-time motion
 * packets for other mobs never reach the tap.
 *
 * <p>The local-player read ({@link Minecraft#player}) from the Netty thread is a
 * plain field read used only as a relevance filter — a stale/null value at worst
 * skips one packet, never corrupts game state. Fully guarded.
 */
@Mixin(Connection.class)
@SuppressWarnings("this-escape") // mixin is merged into Connection, never instantiated standalone
public abstract class InboundPacketMixin {

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void homunculus$tapInboundFeedback(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        try {
            if (packet instanceof ClientboundPlayerPositionPacket) {
                fire(packet);
            } else if (packet instanceof ClientboundSetEntityMotionPacket motion) {
                LocalPlayer lp = Minecraft.getInstance().player;
                if (lp != null && motion.getId() == lp.getId()) {
                    fire(packet);
                }
            }
        } catch (Throwable ignored) {
            // Telemetry must never break the receive path.
        }
    }

    private static void fire(Packet<?> packet) {
        ServerFeedbackTap.INSTANCE.observe(
                packet, PlayerObsSnapshot.currentTick(), System.currentTimeMillis());
    }
}
