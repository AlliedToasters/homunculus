package dev.toast.homunculus;

import dev.toast.homunculus.mixin.PacketEncoderAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 1 of the codec experiment (ml.MD §4a). Round-trips allowlisted
 * outbound packets through {@link ProtocolInfo}'s {@link StreamCodec}:
 * {@code encode(buf, packet)} → {@code decode(buf)} → send clone instead of
 * original. Mojang's codec is the trusted byte-level oracle; if a clone
 * encodes back to the same bytes as the original, the round-trip is faithful.
 *
 * <p>Phase 1 is "plumbing works" — proving we can intercept, transform, and
 * forward without breaking gameplay. The Phase 2 structured codec from §4a
 * will slot in at the same seam with a typed-categorical / pointer-into-obs
 * representation instead of raw bytes.
 *
 * <p>Behavior on failure: pass-through original, never drop or cancel. The
 * round-trip is opt-in best-effort; if encode or decode throws we want
 * gameplay to degrade to baseline, not stall.
 *
 * <p>Byte-equality assertion: re-encode the decoded clone and compare to the
 * original bytes. Mojang's codecs are intended inverses; an asymmetry would
 * be a substrate fact worth knowing immediately.
 */
public final class PacketRoundtrip {

    public static final PacketRoundtrip INSTANCE = new PacketRoundtrip();

    private volatile boolean enabled = false;
    private final AtomicLong roundtripped = new AtomicLong();
    private final AtomicLong passedThrough = new AtomicLong();
    private final AtomicLong encodeFailed = new AtomicLong();
    private final AtomicLong decodeFailed = new AtomicLong();
    private final AtomicLong byteMismatch = new AtomicLong();

    private PacketRoundtrip() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public void resetCounters() {
        roundtripped.set(0);
        passedThrough.set(0);
        encodeFailed.set(0);
        decodeFailed.set(0);
        byteMismatch.set(0);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("enabled", enabled);
        out.put("roundtripped", roundtripped.get());
        out.put("passed_through", passedThrough.get());
        out.put("encode_failed", encodeFailed.get());
        out.put("decode_failed", decodeFailed.get());
        out.put("byte_mismatch", byteMismatch.get());
        return out;
    }

    /**
     * Encode {@code original} via the connection's live codec, decode it back,
     * and re-encode the clone to verify byte-equality with the first encoding.
     *
     * @return a freshly-decoded clone of {@code original} on success, or
     *         {@code null} on any failure (encode throw, decode throw, byte
     *         mismatch). On null the caller must pass the original through.
     */
    public Packet<?> tryRoundtrip(Packet<?> original, Channel channel) {
        ProtocolInfo<?> info = lookupProtocolInfo(channel);
        if (info == null) {
            // No encoder in pipeline yet (very early in connection setup) —
            // treat as pass-through; not a real codec failure.
            passedThrough.incrementAndGet();
            return null;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamCodec<ByteBuf, Packet<?>> codec = (StreamCodec) info.codec();

        ByteBuf bufA = Unpooled.buffer();
        try {
            try {
                codec.encode(bufA, original);
            } catch (Throwable t) {
                encodeFailed.incrementAndGet();
                HomunculusClient.LOGGER.warn("[roundtrip] encode threw for {}: {}",
                        original.type().id(), t.toString());
                return null;
            }
            byte[] bytesA = ByteBufUtil.getBytes(bufA);

            Packet<?> clone;
            ByteBuf bufB = Unpooled.wrappedBuffer(bytesA);
            try {
                try {
                    clone = codec.decode(bufB);
                } catch (Throwable t) {
                    decodeFailed.incrementAndGet();
                    HomunculusClient.LOGGER.warn("[roundtrip] decode threw for {}: {}",
                            original.type().id(), t.toString());
                    return null;
                }
            } finally {
                bufB.release();
            }

            ByteBuf bufC = Unpooled.buffer();
            try {
                try {
                    codec.encode(bufC, clone);
                } catch (Throwable t) {
                    encodeFailed.incrementAndGet();
                    HomunculusClient.LOGGER.warn("[roundtrip] re-encode threw for {}: {}",
                            original.type().id(), t.toString());
                    return null;
                }
                byte[] bytesC = ByteBufUtil.getBytes(bufC);
                if (!Arrays.equals(bytesA, bytesC)) {
                    byteMismatch.incrementAndGet();
                    HomunculusClient.LOGGER.warn("[roundtrip] byte mismatch for {} ({} vs {} bytes)",
                            original.type().id(), bytesA.length, bytesC.length);
                    return null;
                }
            } finally {
                bufC.release();
            }

            roundtripped.incrementAndGet();
            return clone;
        } finally {
            bufA.release();
        }
    }

    /**
     * Pull the live {@link ProtocolInfo} out of the channel's netty pipeline
     * by finding the {@link PacketEncoder} handler and reading its field via
     * {@link PacketEncoderAccessor}. Returns null if no encoder is installed
     * (e.g. connection still in early handshake setup) or the channel is null.
     */
    @SuppressWarnings("unchecked")
    private static <T extends PacketListener> ProtocolInfo<T> lookupProtocolInfo(Channel channel) {
        if (channel == null) return null;
        ChannelPipeline pipeline = channel.pipeline();
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            ChannelHandler h = entry.getValue();
            if (h instanceof PacketEncoder<?>) {
                PacketEncoderAccessor<T> acc = (PacketEncoderAccessor<T>) h;
                return acc.homunculus$protocolInfo();
            }
        }
        return null;
    }
}
