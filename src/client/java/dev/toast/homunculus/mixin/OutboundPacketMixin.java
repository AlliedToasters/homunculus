package dev.toast.homunculus.mixin;

import dev.toast.homunculus.CodecPassthrough;
import dev.toast.homunculus.PacketAllowlist;
import dev.toast.homunculus.PacketRecorder;
import dev.toast.homunculus.PacketRoundtrip;
import dev.toast.homunculus.PacketTap;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Codec hook for Phases 0 and 1 (ml.MD §4a). HEAD-injects into the private
 * funnel {@code Connection#sendPacket(Packet, PacketSendListener, boolean)} —
 * all three public {@code send(...)} overloads converge there, so one mixin
 * catches every outbound packet regardless of which call path produced it.
 *
 * <p>Filtered to {@link PacketFlow#SERVERBOUND}: {@code Connection} is reused
 * for both directions and (in single-player) for the integrated server's
 * client-bound traffic too. We only care about packets the local client is
 * emitting.
 *
 * <p>Phase 0: every serverbound packet is observed by {@link PacketTap} for
 * counting + recent-ring diagnostics. No cancel, no mutation.
 *
 * <p>Phase 1: when {@link PacketRoundtrip#isEnabled()} and the packet's
 * {@code type().id()} is in {@link PacketAllowlist#SPATIAL_PLAY}, route it
 * through Mojang's codec for an encode → decode → re-encode-and-byte-compare
 * round-trip. On success, send the freshly-decoded clone and cancel the
 * original; on any failure, pass the original through unchanged. The kill
 * switch is global, single-flag — flip it off and behavior reverts instantly.
 */
@Mixin(Connection.class)
public abstract class OutboundPacketMixin {

    /**
     * Reentrance guard. Substituting the clone via {@link Connection#send}
     * funnels back into {@code sendPacket}, which would re-fire this mixin
     * and round-trip the clone too — infinite recursion. Setting this flag
     * around the inner send makes the recursive invocation pass straight
     * through (no tap, no round-trip). The tap counters stay clean
     * (one count per user-initiated send) and only the original triggers
     * a codec pass.
     */
    private static final ThreadLocal<Boolean> ROUNDTRIPPING = ThreadLocal.withInitial(() -> false);

    @Shadow
    public abstract PacketFlow getSending();

    /**
     * Connection's underlying netty channel. Private with no public accessor
     * in mojmap 1.21.4, so we {@link Shadow} it here and hand it to
     * {@link PacketRoundtrip} — it walks the pipeline to find the live
     * {@link net.minecraft.network.PacketEncoder} and the {@code ProtocolInfo}
     * holding the codec.
     */
    @Shadow
    private Channel channel;

    @Inject(
            method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void homunculus$tapOutboundPacket(Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
        if (getSending() != PacketFlow.SERVERBOUND) return;
        if (ROUNDTRIPPING.get()) return; // recursive call from our own substitute send → pass through
        PacketTap.INSTANCE.observe(packet);

        ResourceLocation id = packet.type() == null ? null : packet.type().id();
        String idStr = id == null ? null : id.toString();
        // Recording is independent of round-trip: capture (packet, obs) pairs
        // for the Phase 2 structured codec even when round-trip is disabled.
        if (idStr != null && PacketRecorder.INSTANCE.isArmed()
                && PacketAllowlist.SPATIAL_PLAY.contains(idStr)) {
            PacketRecorder.INSTANCE.record(packet, idStr, System.currentTimeMillis());
        }
        // Live codec passthrough — two modes:
        //   1. observer (default): async, never affects the wire.
        //   2. substitute (smoke test): sync round-trip via the Python codec
        //      server, reconstruct packet from decoded fields, send the
        //      clone in place of the original. Falls back to the original
        //      packet on any failure (transport error, unsupported type,
        //      reconstructor error).
        if (idStr != null && CodecPassthrough.INSTANCE.isArmed()
                && PacketAllowlist.SPATIAL_PLAY.contains(idStr)) {
            if (CodecPassthrough.INSTANCE.isSubstituteMode()) {
                Packet<?> subClone = CodecPassthrough.INSTANCE.trySubstitute(
                        packet, idStr, System.currentTimeMillis());
                if (subClone != null) {
                    Connection sub = (Connection) (Object) this;
                    ROUNDTRIPPING.set(true);
                    try {
                        sub.send(subClone, listener, flush);
                    } finally {
                        ROUNDTRIPPING.set(false);
                    }
                    ci.cancel();
                    return;
                }
                // null → fall through to original packet (pass-through path)
            } else {
                CodecPassthrough.INSTANCE.observe(packet, idStr, System.currentTimeMillis());
            }
        }

        if (!PacketRoundtrip.INSTANCE.isEnabled()) return;
        if (idStr == null || !PacketAllowlist.SPATIAL_PLAY.contains(idStr)) return;

        Connection self = (Connection) (Object) this;
        Packet<?> clone = PacketRoundtrip.INSTANCE.tryRoundtrip(packet, this.channel);
        if (clone == null) return; // round-trip failed → pass original through
        ROUNDTRIPPING.set(true);
        try {
            self.send(clone, listener, flush);
        } finally {
            ROUNDTRIPPING.set(false);
        }
        ci.cancel();
    }
}
