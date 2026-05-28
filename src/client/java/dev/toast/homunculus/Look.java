package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Camera helpers. Used to point the player at a block before an interaction so the spectator
 * camera follows the action instead of staring at the sky. The rotation is not restored —
 * leaving the camera on the last-touched block reads as smooth viewing; snapping back to
 * whatever the agent was last facing reads as jarring.
 */
public final class Look {
	private Look() {}

	/** Aims the player's view at the top-center of {@code pos}. No-op if no player. */
	public static void faceBlockTop(Minecraft mc, BlockPos pos) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		Vec3 eye = p.getEyePosition();
		double dx = (pos.getX() + 0.5) - eye.x;
		double dy = (pos.getY() + 1.0) - eye.y;
		double dz = (pos.getZ() + 0.5) - eye.z;
		aim(p, dx, dy, dz);
	}

	/** Aims the player's view at the eye position of {@code target}. No-op if no player. */
	public static void faceEntity(Minecraft mc, Entity target) {
		LocalPlayer p = mc.player;
		if (p == null || target == null) return;
		Vec3 eye = p.getEyePosition();
		Vec3 tgt = target.getEyePosition();
		aim(p, tgt.x - eye.x, tgt.y - eye.y, tgt.z - eye.z);
	}

	private static void aim(LocalPlayer p, double dx, double dy, double dz) {
		float yaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float pitch = (float) (-Math.atan2(dy, horiz) * (180.0 / Math.PI));
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.connection.send(new ServerboundMovePlayerPacket.Rot(
				yaw, pitch, p.onGround(), p.horizontalCollision));
	}
}
