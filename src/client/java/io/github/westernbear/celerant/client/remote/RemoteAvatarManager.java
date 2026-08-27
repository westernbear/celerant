package io.github.westernbear.celerant.client.remote;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.westernbear.celerant.Celerant;
import io.github.westernbear.celerant.client.net.CelerantClientNet;
import io.github.westernbear.celerant.secure.AvatarEnvelope;
import io.github.westernbear.celerant.secure.BinaryScrambler;
import io.github.westernbear.celerant.secure.EncryptedAvatarCache;
import io.github.westernbear.celerant.secure.VertexObfuscator;
import net.minecraft.client.Minecraft;

/**
 * Per-owner remote avatar download/assemble and Hardened unwrap.
 */
public final class RemoteAvatarManager {
	private static final int CHUNK = 24 * 1024;
	private static final Map<String, Incoming> incoming = new ConcurrentHashMap<>();
	private static final Map<UUID, RemoteSlot> remotes = new ConcurrentHashMap<>();
	private static EncryptedAvatarCache cache;

	private RemoteAvatarManager() {
	}

	private static EncryptedAvatarCache cache() {
		if (cache == null) {
			Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("celerant/remote-cache");
			cache = new EncryptedAvatarCache(root);
		}
		return cache;
	}

	public static void clear() {
		incoming.clear();
		remotes.clear();
	}

	public static RemoteSlot get(UUID owner) {
		return remotes.get(owner);
	}

	public static Map<UUID, RemoteSlot> all() {
		return remotes;
	}

	public static void onMeta(CelerantClientNet.AvatarMetaPayload meta) {
		Incoming inc = incoming.computeIfAbsent(meta.avatarId(), id -> new Incoming(meta));
		inc.meta = meta;
	}

	public static void onChunk(CelerantClientNet.AvatarChunkPayload chunk) {
		Incoming inc = incoming.computeIfAbsent(chunk.avatarId(),
			id -> new Incoming(new CelerantClientNet.AvatarMetaPayload(chunk.ownerId(), chunk.avatarId(),
				new byte[32], 0, 0)));
		inc.accept(chunk.index(), chunk.bytes(), chunk.last());
		if (inc.complete) {
			tryAssemble(inc);
		}
	}

	public static void onKey(CelerantClientNet.AvatarKeyPayload key) {
		Incoming inc = incoming.get(key.avatarId());
		if (inc == null) {
			inc = new Incoming(new CelerantClientNet.AvatarMetaPayload(key.ownerId(), key.avatarId(),
				new byte[32], 0, 0));
			incoming.put(key.avatarId(), inc);
		}
		inc.sessionKey = key.keyMaterial();
		if (inc.complete) {
			tryAssemble(inc);
		}
	}

	private static void tryAssemble(Incoming inc) {
		if (inc.sessionKey == null || !inc.complete || inc.assembled) {
			return;
		}
		try {
			byte[] envelope = inc.payload();
			byte[] expected = inc.meta.contentHash();
			byte[] actual = AvatarEnvelope.sha256(envelope);
			if (expected != null && expected.length == 32 && !Arrays.equals(expected, actual)) {
				Celerant.LOGGER.warn("Remote avatar hash mismatch for {}", inc.meta.avatarId());
				cache().delete(inc.meta.avatarId());
				incoming.remove(inc.meta.avatarId());
				return;
			}
			byte[] scrambled = AvatarEnvelope.unwrap(envelope, inc.sessionKey);
			byte[] plain = BinaryScrambler.restore(scrambled, inc.sessionKey);
			byte[] cacheKey = EncryptedAvatarCache.machineCacheKey(inc.sessionKey);
			cache().write(inc.meta.avatarId(), envelope, cacheKey);
			Path tmp = Files.createTempFile("celerant-remote-", ".vrm");
			Files.write(tmp, plain);
			remotes.put(inc.meta.ownerId(), new RemoteSlot(inc.meta.ownerId(), inc.meta.avatarId(), tmp, true));
			inc.assembled = true;
			Celerant.LOGGER.info("Assembled remote avatar {} for {}", inc.meta.avatarId(), inc.meta.ownerId());
		} catch (Exception e) {
			Celerant.LOGGER.error("Failed to assemble remote avatar {}", inc.meta.avatarId(), e);
			incoming.remove(inc.meta.avatarId());
		}
	}

	/**
	 * Hardened upload: obfuscate marker + AES wrap original bytes, then chunk to plugin.
	 */
	public static boolean uploadLocal(Path vrmPath) {
		if (!CelerantClientNet.isPluginPresent()) {
			return false;
		}
		try {
			byte[] plain = Files.readAllBytes(vrmPath);
			byte[] key = VertexObfuscator.randomKey();
			byte[] scrambled = BinaryScrambler.scramble(plain, key);
			byte[] envelope = AvatarEnvelope.wrap(scrambled, key);
			byte[] hash = AvatarEnvelope.sha256(envelope);
			UUID owner = Minecraft.getInstance().player != null
				? Minecraft.getInstance().player.getUUID()
				: Minecraft.getInstance().getUser().getProfileId();
			String avatarId = Long.toHexString(System.currentTimeMillis());
			CelerantClientNet.sendMeta(new CelerantClientNet.AvatarMetaPayload(owner, avatarId, hash,
				envelope.length, 0));
			int streamId = (int) (System.currentTimeMillis() & 0x7fffffff);
			for (int offset = 0, index = 0; offset < envelope.length; index++) {
				int len = Math.min(CHUNK, envelope.length - offset);
				byte[] slice = Arrays.copyOfRange(envelope, offset, offset + len);
				offset += len;
				boolean last = offset >= envelope.length;
				CelerantClientNet.sendChunk(new CelerantClientNet.AvatarChunkPayload(owner, avatarId, streamId,
					index, slice, last));
			}
			onKey(new CelerantClientNet.AvatarKeyPayload(owner, avatarId, key));
			return true;
		} catch (Exception e) {
			Celerant.LOGGER.error("Avatar upload failed", e);
			return false;
		}
	}

	private static final class Incoming {
		CelerantClientNet.AvatarMetaPayload meta;
		byte[] sessionKey;
		final Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
		boolean complete;
		boolean assembled;
		int lastIndex = -1;

		Incoming(CelerantClientNet.AvatarMetaPayload meta) {
			this.meta = meta;
		}

		void accept(int index, byte[] bytes, boolean last) {
			chunks.put(index, bytes);
			if (last) {
				lastIndex = index;
				complete = true;
				for (int i = 0; i <= lastIndex; i++) {
					if (!chunks.containsKey(i)) {
						complete = false;
						break;
					}
				}
			}
		}

		byte[] payload() throws Exception {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (int i = 0; i <= lastIndex; i++) {
				out.write(chunks.get(i));
			}
			return out.toByteArray();
		}
	}

	public record RemoteSlot(UUID ownerId, String avatarId, Path tempVrm, boolean ready) {
	}
}
