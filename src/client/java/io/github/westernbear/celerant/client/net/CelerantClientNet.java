package io.github.westernbear.celerant.client.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.westernbear.celerant.Celerant;
import io.github.westernbear.celerant.client.remote.RemoteAvatarManager;
import io.github.westernbear.celerant.loco.LocoParams;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric ↔ Paper plugin messaging. Degrades gracefully when the plugin is absent.
 */
public final class CelerantClientNet {
	private static volatile boolean pluginPresent;
	private static final Map<UUID, LocoParams> remoteLoco = new ConcurrentHashMap<>();
	private static long lastLocoSendMs;

	private CelerantClientNet() {
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LocoPayload.TYPE, LocoPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LocoPayload.TYPE, LocoPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AvatarMetaPayload.TYPE, AvatarMetaPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AvatarMetaPayload.TYPE, AvatarMetaPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AvatarChunkPayload.TYPE, AvatarChunkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AvatarChunkPayload.TYPE, AvatarChunkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AvatarKeyPayload.TYPE, AvatarKeyPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AvatarKeyPayload.TYPE, AvatarKeyPayload.CODEC);

		ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) -> {
			pluginPresent = true;
			Celerant.LOGGER.info("Celerant Paper plugin present (protocol {})", payload.protocol());
		});
		ClientPlayNetworking.registerGlobalReceiver(LocoPayload.TYPE, (payload, context) -> {
			remoteLoco.put(payload.playerId(), LocoParams.fromBytes(payload.data()));
		});
		ClientPlayNetworking.registerGlobalReceiver(AvatarMetaPayload.TYPE, (payload, context) -> {
			RemoteAvatarManager.onMeta(payload);
		});
		ClientPlayNetworking.registerGlobalReceiver(AvatarChunkPayload.TYPE, (payload, context) -> {
			RemoteAvatarManager.onChunk(payload);
		});
		ClientPlayNetworking.registerGlobalReceiver(AvatarKeyPayload.TYPE, (payload, context) -> {
			RemoteAvatarManager.onKey(payload);
		});
	}

	public static boolean isPluginPresent() {
		return pluginPresent;
	}

	public static void onDisconnect() {
		pluginPresent = false;
		remoteLoco.clear();
		RemoteAvatarManager.clear();
	}

	public static void announce() {
		if (ClientPlayNetworking.canSend(HelloPayload.TYPE)) {
			ClientPlayNetworking.send(new HelloPayload(1));
		}
	}

	public static void broadcastLoco(LocoParams params) {
		if (!pluginPresent || params == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastLocoSendMs < 50L) {
			return;
		}
		lastLocoSendMs = now;
		if (ClientPlayNetworking.canSend(LocoPayload.TYPE)) {
			var client = net.minecraft.client.Minecraft.getInstance();
			UUID self = client.player != null ? client.player.getUUID()
				: client.getUser().getProfileId();
			ClientPlayNetworking.send(new LocoPayload(self, params.toBytes()));
		}
	}

	public static LocoParams remoteLoco(UUID id) {
		return remoteLoco.getOrDefault(id, LocoParams.IDLE);
	}

	public static void sendMeta(AvatarMetaPayload payload) {
		if (pluginPresent && ClientPlayNetworking.canSend(AvatarMetaPayload.TYPE)) {
			ClientPlayNetworking.send(payload);
		}
	}

	public static void sendChunk(AvatarChunkPayload payload) {
		if (pluginPresent && ClientPlayNetworking.canSend(AvatarChunkPayload.TYPE)) {
			ClientPlayNetworking.send(payload);
		}
	}

	public record HelloPayload(int protocol) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<HelloPayload> TYPE =
			new CustomPacketPayload.Type<>(CelerantChannels.HELLO);
		public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC =
			StreamCodec.of((buf, p) -> buf.writeVarInt(p.protocol), buf -> new HelloPayload(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record LocoPayload(UUID playerId, byte[] data) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<LocoPayload> TYPE =
			new CustomPacketPayload.Type<>(CelerantChannels.LOCO);
		public static final StreamCodec<RegistryFriendlyByteBuf, LocoPayload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUUID(p.playerId);
			buf.writeByteArray(p.data);
		}, buf -> new LocoPayload(buf.readUUID(), buf.readByteArray()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record AvatarMetaPayload(UUID ownerId, String avatarId, byte[] contentHash, int size, int aclFlags)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AvatarMetaPayload> TYPE =
			new CustomPacketPayload.Type<>(CelerantChannels.AVATAR_META);
		public static final StreamCodec<RegistryFriendlyByteBuf, AvatarMetaPayload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUUID(p.ownerId);
			buf.writeUtf(p.avatarId);
			buf.writeByteArray(p.contentHash);
			buf.writeVarInt(p.size);
			buf.writeVarInt(p.aclFlags);
		}, buf -> new AvatarMetaPayload(buf.readUUID(), buf.readUtf(), buf.readByteArray(), buf.readVarInt(),
			buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record AvatarChunkPayload(UUID ownerId, String avatarId, int streamId, int index, byte[] bytes,
		boolean last) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AvatarChunkPayload> TYPE =
			new CustomPacketPayload.Type<>(CelerantChannels.AVATAR_CHUNK);
		public static final StreamCodec<RegistryFriendlyByteBuf, AvatarChunkPayload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUUID(p.ownerId);
			buf.writeUtf(p.avatarId);
			buf.writeVarInt(p.streamId);
			buf.writeVarInt(p.index);
			buf.writeByteArray(p.bytes);
			buf.writeBoolean(p.last);
		}, buf -> new AvatarChunkPayload(buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
			buf.readByteArray(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record AvatarKeyPayload(UUID ownerId, String avatarId, byte[] keyMaterial)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AvatarKeyPayload> TYPE =
			new CustomPacketPayload.Type<>(CelerantChannels.AVATAR_KEY);
		public static final StreamCodec<RegistryFriendlyByteBuf, AvatarKeyPayload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeUUID(p.ownerId);
			buf.writeUtf(p.avatarId);
			buf.writeByteArray(p.keyMaterial);
		}, buf -> new AvatarKeyPayload(buf.readUUID(), buf.readUtf(), buf.readByteArray()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
