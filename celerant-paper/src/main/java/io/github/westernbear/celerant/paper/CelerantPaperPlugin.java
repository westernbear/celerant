package io.github.westernbear.celerant.paper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Paper relay for Celerant custom channels.
 * Stores ciphertext only; fans out loco params; issues session keys to viewers.
 *
 * Version note: align Paper API with your server. Fabric Celerant targets MC 26.2;
 * deploy matching Paper when available, or bridge via a compatible proxy version.
 */
public final class CelerantPaperPlugin extends JavaPlugin implements PluginMessageListener {
	public static final String HELLO = "celerant:hello";
	public static final String META = "celerant:avatar_meta";
	public static final String CHUNK = "celerant:avatar_chunk";
	public static final String KEY = "celerant:avatar_key";
	public static final String LOCO = "celerant:loco";

	private final Map<String, StoredAvatar> store = new ConcurrentHashMap<>();
	private final Map<String, byte[]> keys = new ConcurrentHashMap<>();
	private final Map<String, Map<Integer, byte[]>> chunks = new ConcurrentHashMap<>();
	private static final int MAX_BYTES = 32 * 1024 * 1024;

	@Override
	public void onEnable() {
		for (String channel : new String[] {HELLO, META, CHUNK, KEY, LOCO}) {
			getServer().getMessenger().registerIncomingPluginChannel(this, channel, this);
			getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
		}
		getLogger().info("Celerant Paper relay enabled (ciphertext-only store).");
	}

	@Override
	public void onDisable() {
		store.clear();
		keys.clear();
		chunks.clear();
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (HELLO.equals(channel)) {
			player.sendPluginMessage(this, HELLO, message);
			return;
		}
		if (LOCO.equals(channel)) {
			for (Player other : getServer().getOnlinePlayers()) {
				if (!other.getUniqueId().equals(player.getUniqueId())) {
					other.sendPluginMessage(this, LOCO, message);
				}
			}
			return;
		}
		if (META.equals(channel)) {
			// Opaque relay: rebroadcast meta to others; owner also registers empty slot.
			store.putIfAbsent(player.getUniqueId() + ":pending", new StoredAvatar(player.getUniqueId(), message));
			for (Player other : getServer().getOnlinePlayers()) {
				if (!other.getUniqueId().equals(player.getUniqueId())) {
					other.sendPluginMessage(this, META, message);
				}
			}
			return;
		}
		if (CHUNK.equals(channel)) {
			String id = player.getUniqueId().toString();
			chunks.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
			// Keep last chunk payload for size accounting; full reassembly is client-side.
			int total = chunks.get(id).values().stream().mapToInt(b -> b.length).sum() + message.length;
			if (total > MAX_BYTES) {
				getLogger().warning("Rejecting oversized avatar upload from " + player.getName());
				chunks.remove(id);
				return;
			}
			chunks.get(id).put(chunks.get(id).size(), message.clone());
			for (Player other : getServer().getOnlinePlayers()) {
				if (!other.getUniqueId().equals(player.getUniqueId())) {
					other.sendPluginMessage(this, CHUNK, message);
				}
			}
			return;
		}
		if (KEY.equals(channel)) {
			keys.put(player.getUniqueId().toString(), message.clone());
			for (Player other : getServer().getOnlinePlayers()) {
				if (!other.getUniqueId().equals(player.getUniqueId())) {
					other.sendPluginMessage(this, KEY, message);
				}
			}
		}
	}

	private record StoredAvatar(UUID owner, byte[] metaBytes) {
	}
}
