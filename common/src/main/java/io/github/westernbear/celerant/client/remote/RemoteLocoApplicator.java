package io.github.westernbear.celerant.client.remote;

import java.util.Map;
import java.util.UUID;

import io.github.westernbear.celerant.client.net.CelerantNetworking;
import io.github.westernbear.celerant.loco.LocoParams;
import io.github.westernbear.celerant.loco.VrmLocomotion;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Applies synced loco params for remote players. Full multi-instance VRM submit
 * loads from {@link RemoteAvatarManager.RemoteSlot#tempVrm()} when present.
 */
public final class RemoteLocoApplicator {
	private RemoteLocoApplicator() {
	}

	public static LocoParams paramsFor(UUID playerId) {
		return CelerantNetworking.remoteLoco(playerId);
	}

	public static Map<String, float[]> poseFor(UUID playerId) {
		return VrmLocomotion.evaluate(paramsFor(playerId));
	}

	/** Debug/status helper used by commands. */
	public static String summarize() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return "no level";
		}
		StringBuilder sb = new StringBuilder();
		for (Player player : client.level.players()) {
			if (client.player != null && player.getUUID().equals(client.player.getUUID())) {
				continue;
			}
			LocoParams loco = paramsFor(player.getUUID());
			RemoteAvatarManager.RemoteSlot slot = RemoteAvatarManager.get(player.getUUID());
			sb.append(player.getGameProfile().name())
				.append(" mag=").append(String.format("%.2f", loco.velocityMagnitude()))
				.append(" avatar=").append(slot != null && slot.ready() ? slot.avatarId() : "-")
				.append('\n');
		}
		return sb.isEmpty() ? "no remotes" : sb.toString();
	}
}
