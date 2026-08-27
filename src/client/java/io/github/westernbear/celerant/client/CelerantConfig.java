package io.github.westernbear.celerant.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.modularmods.mcgltf.ToonAssetGenerator;

import io.github.westernbear.celerant.client.toon.ToonShader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.annotations.Button;
import org.polyfrost.oneconfig.api.config.v1.annotations.File;
import org.polyfrost.oneconfig.api.config.v1.annotations.Info;
import org.polyfrost.oneconfig.api.config.v1.annotations.Number;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;
import org.polyfrost.oneconfig.api.config.v1.annotations.Text;
import org.polyfrost.oneconfig.api.notifications.v1.Notifications;
import org.polyfrost.oneconfig.utils.v1.dsl.ScreensKt;

public final class CelerantConfig extends Config {
	static final CelerantConfig INSTANCE = new CelerantConfig();
	private static final ExecutorService DERIVATION = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Celerant Toon derivation");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean deriving = new AtomicBoolean();
	private boolean initialized;

	@Info(title = "Local VRM avatar", category = "Model",
		description = "Select a self-contained VRM 0.x or 1.0 file. Celerant never uploads your model.")
	private String modelNotice = "";

	@File(title = "VRM model", category = "Model", types = {".vrm"}, filterName = "VRM models",
		description = "Choose a local .vrm file. The previous model stays active if loading fails.")
	String modelPath = "";

	@Number(title = "Model scale", category = "Model", min = 0.001F, max = 100.0F, unit = "×",
		description = "Scale for both the placed preview and player avatar.")
	float scale = 1.0F;

	@Switch(title = "Replace local player", category = "Avatar",
		description = "Use the loaded humanoid VRM in first and third person with Minecraft motion retargeting.")
	boolean avatarEnabled;

	@Text(title = "Expression name", category = "Expressions", placeholder = "happy",
		description = "Enter any expression reported by the loaded VRM.")
	String expressionName = "";

	@Slider(title = "Expression weight", category = "Expressions", min = 0.0F, max = 1.0F, step = 0.05F)
	float expressionWeight = 1.0F;

	@Switch(title = "Iris toon shading", category = "Rendering",
		description = "Composite VRM ToonShader materials after the Iris final pass. ShaderPack files stay unchanged.")
	boolean toonEnabled = true;

	@Switch(title = "Toon emission bloom", category = "Rendering",
		description = "Add the multi-pass emission glow. Disable this first when ToonShader frame rate is low.")
	boolean toonBloomEnabled;

	@Switch(title = "Locomotion L3", category = "Motion",
		description = "VRChat-style locomotion with Warudo breathing/sway layers.")
	boolean locomotionEnabled = true;

	@Switch(title = "Breathing", category = "Motion",
		description = "Warudo-style additive breathing when idle.")
	boolean breathingEnabled = true;

	@Switch(title = "Swaying", category = "Motion",
		description = "Warudo-style idle hip sway.")
	boolean swayingEnabled = true;

	@Switch(title = "Spring bone (XPBD)", category = "Motion",
		description = "Magica-style Line BoneCloth secondary motion from VRM spring bones.")
	boolean springBoneEnabled = true;

	@Info(title = "Multiplayer", category = "Multiplayer",
		description = "Requires the Celerant Paper plugin. Avatars upload Hardened (scrambled+AES); the plugin never stores plaintext.")
	private String multiplayerNotice = "";

	private CelerantConfig() {
		super("celerant", "assets/celerant/icon.png", "Celerant VRM", Category.QOL);
	}

	void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;
		preload();
		if (avatarEnabled) {
			getProperty("avatarEnabled").setAs(false);
			save();
		}
		VrmRuntime.getInstance().setScale(scale);
		ToonShader.setEnabled(toonEnabled);
		com.modularmods.mcgltf.ToonShader.setBloomEnabled(toonBloomEnabled);
		addCallback("scale", (Float value) -> {
			return !VrmRuntime.getInstance().setScale(value);
		});
		addCallback("avatarEnabled", (Boolean enabled) -> {
			VrmRuntime runtime = VrmRuntime.getInstance();
			if (!runtime.setAvatarEnabled(enabled)) {
				Notifications.error("Celerant VRM", "Cannot enable avatar: " + runtime.avatarProblem());
				return true;
			}
			return false;
		});
		addCallback("toonEnabled", this::setToonEnabled);
		addCallback("toonBloomEnabled", this::setToonBloomEnabled);
		addCallback("locomotionEnabled", (Boolean enabled) -> {
			io.github.westernbear.celerant.loco.VrmLocomotion.setLocomotionEnabled(enabled);
			return false;
		});
		addCallback("breathingEnabled", (Boolean enabled) -> {
			io.github.westernbear.celerant.loco.VrmLocomotion.setBreathingEnabled(enabled);
			return false;
		});
		addCallback("swayingEnabled", (Boolean enabled) -> {
			io.github.westernbear.celerant.loco.VrmLocomotion.setSwayingEnabled(enabled);
			return false;
		});
		addCallback("springBoneEnabled", (Boolean enabled) -> {
			VrmRuntime.getInstance().setSpringBoneEnabled(enabled);
			return false;
		});
		io.github.westernbear.celerant.loco.VrmLocomotion.setLocomotionEnabled(locomotionEnabled);
		io.github.westernbear.celerant.loco.VrmLocomotion.setBreathingEnabled(breathingEnabled);
		io.github.westernbear.celerant.loco.VrmLocomotion.setSwayingEnabled(swayingEnabled);
		VrmRuntime.getInstance().setSpringBoneEnabled(springBoneEnabled);
	}

	String modelPathValue() {
		return modelPath;
	}

	void open() {
		syncRuntimeState();
		ScreensKt.openUI(this);
	}

	void syncRuntimeState() {
		VrmRuntime runtime = VrmRuntime.getInstance();
		if (Float.compare(scale, runtime.scale()) != 0) {
			getProperty("scale").setAs(runtime.scale());
		}
		boolean active = runtime.isLocalAvatarActive();
		if (avatarEnabled != active) {
			getProperty("avatarEnabled").setAs(active);
		}
	}

	@Button(title = "Load selected VRM", category = "Model", text = "Load",
		description = "Validate and load the selected file asynchronously.")
	private void loadAvatar() {
		if (modelPath == null || modelPath.isBlank()) {
			Notifications.error("Celerant VRM", "Choose a .vrm file first.");
			return;
		}

		Path path;
		try {
			path = Path.of(modelPath.trim());
		} catch (InvalidPathException exception) {
			Notifications.error("Celerant VRM", "The selected model path is invalid.");
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Vec3 fallback = client.player == null ? null : client.player.position();
		VrmRuntime runtime = VrmRuntime.getInstance();
		if (runtime.isLoading()) {
			Notifications.error("Celerant VRM", "A model is already loading.");
			return;
		}
		Notifications.info("Celerant VRM", "Loading " + path.getFileName() + "…");
		if (!runtime.load(path, fallback, (success, result) -> {
			if (success) {
				Notifications.success("Celerant VRM", result);
			} else {
				Notifications.error("Celerant VRM", result);
			}
		})) {
			Notifications.error("Celerant VRM", "A model is already loading.");
			return;
		}
	}

	@Button(title = "Unload VRM", category = "Model", text = "Unload",
		description = "Release the loaded model and restore the vanilla player.")
	private void unloadAvatar() {
		if (!VrmRuntime.getInstance().unload()) {
			Notifications.error("Celerant VRM", "No VRM is loaded.");
			return;
		}
		getProperty("avatarEnabled").setAs(false);
		Notifications.success("Celerant VRM", "VRM unloaded.");
	}

	@Button(title = "Generate Toon assets", category = "Rendering", text = "Generate",
		description = "Derive a Genshin-style Toon profile from the selected VRM: LightMaps, shadow ramps, the facial shadow SDF, a metal matcap, and outline colours, written beside the model. Reading the model takes a while and nothing already there is overwritten.")
	private void generateToonDraft() {
		if (modelPath == null || modelPath.isBlank()) {
			Notifications.error("Celerant Toon setup", "Choose a .vrm file first.");
			return;
		}
		Path model;
		try {
			model = Path.of(modelPath.trim()).toAbsolutePath().normalize();
		} catch (InvalidPathException exception) {
			Notifications.error("Celerant Toon setup", "The selected model path is invalid.");
			return;
		}
		if (!Files.isRegularFile(model)
			|| !model.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vrm")) {
			Notifications.error("Celerant Toon setup", "Choose an existing .vrm file first.");
			return;
		}
		Path profile = model.resolveSibling(model.getFileName() + ".toon.json");
		if (Files.exists(profile)) {
			Notifications.error("Celerant Toon setup",
				"A Toon profile already exists; it was not overwritten.");
			return;
		}
		if (!deriving.compareAndSet(false, true)) {
			Notifications.error("Celerant Toon setup", "Toon assets are already being derived.");
			return;
		}
		String stem = model.getFileName().toString();
		stem = stem.substring(0, stem.length() - ".vrm".length()).toLowerCase(Locale.ROOT);
		Notifications.info("Celerant Toon setup",
			"Deriving Toon assets for " + model.getFileName() + "; this reads every texture.");
		derive(model, profile, stem + "-toon");
	}

	/**
	 * Derivation reads and resamples every texture in the model, which takes long enough
	 * to stall a frame, so it runs off the render thread and reports back on it.
	 */
	private void derive(Path model, Path profile, String prefix) {
		Minecraft client = Minecraft.getInstance();
		CompletableFuture
			.supplyAsync(() -> {
				try {
					return ToonAssetGenerator.generate(model, model.getParent(), prefix, profile);
				} catch (IOException exception) {
					throw new java.io.UncheckedIOException(exception);
				}
			}, DERIVATION)
			.whenComplete((result, error) -> client.execute(() -> {
				deriving.set(false);
				if (error != null) {
					Notifications.error("Celerant Toon setup",
						"Could not derive Toon assets: " + rootCause(error).getMessage());
					return;
				}
				if (result.faceProfiled()) {
					Notifications.success("Celerant Toon setup", "Derived " + profile.getFileName()
						+ " and the sheets for " + result.materials() + " materials.");
					return;
				}
				// Reporting this matters: without the expression bindings or head
				// weighting a face needs, there is nothing to derive a facial shadow
				// sweep from, and a generic substitute would look like a Lambert wedge.
				Notifications.info("Celerant Toon setup", "Derived " + profile.getFileName()
					+ " and the sheets for " + result.materials()
					+ " materials, but no face material could be identified,"
					+ " so facial shadow shaping is left unconfigured.");
			}));
	}

	private static Throwable rootCause(Throwable error) {
		return error.getCause() == null ? error : error.getCause();
	}

	@Button(title = "Place preview here", category = "Model", text = "Place",
		description = "Move the standalone VRM preview to your current world position.")
	private void placeHere() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			Notifications.error("Celerant VRM", "Join a world before placing the preview.");
			return;
		}
		VrmRuntime.getInstance().place(client.player.position());
		Notifications.success("Celerant VRM", "Preview moved to your position.");
	}

	@Button(title = "Available expressions", category = "Expressions", text = "Show")
	private void showExpressions() {
		var names = VrmRuntime.getInstance().expressionNames();
		Notifications.info("VRM expressions", names.isEmpty() ? "No expressions are available."
			: String.join(", ", names));
	}

	@Button(title = "Apply expression", category = "Expressions", text = "Apply")
	private void applyExpression() {
		if (expressionName == null || expressionName.isBlank()
			|| !VrmRuntime.getInstance().setExpression(expressionName.trim(), expressionWeight)) {
			Notifications.error("Celerant VRM", "Unknown expression or no VRM loaded: " + expressionName);
			return;
		}
		Notifications.success("Celerant VRM", "Expression applied: " + expressionName.trim());
	}

	@Button(title = "Clear expression", category = "Expressions", text = "Clear")
	private void clearExpression() {
		if (!VrmRuntime.getInstance().clearExpression()) {
			Notifications.error("Celerant VRM", "No VRM is loaded.");
			return;
		}
		Notifications.success("Celerant VRM", "Expression cleared.");
	}

	@Button(title = "Upload avatar (Hardened)", category = "Multiplayer", text = "Upload",
		description = "Scramble+AES-GCM the loaded VRM path and upload via the Paper plugin.")
	private void uploadAvatar() {
		if (!io.github.westernbear.celerant.client.net.CelerantClientNet.isPluginPresent()) {
			Notifications.error("Celerant",
				net.minecraft.client.resources.language.I18n.get("celerant.error.plugin_missing"));
			return;
		}
		if (modelPath == null || modelPath.isBlank()) {
			Notifications.error("Celerant", "Choose a .vrm file first.");
			return;
		}
		boolean ok = io.github.westernbear.celerant.client.remote.RemoteAvatarManager
			.uploadLocal(Path.of(modelPath.trim()));
		if (ok) {
			Notifications.success("Celerant",
				net.minecraft.client.resources.language.I18n.get("celerant.multiplayer.upload_ok"));
		} else {
			Notifications.error("Celerant",
				net.minecraft.client.resources.language.I18n.get("celerant.error.upload_failed"));
		}
	}

	@Button(title = "Clear remote cache", category = "Multiplayer", text = "Clear",
		description = "Delete encrypted remote avatar cache files.")
	private void clearRemoteCache() {
		try {
			Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("celerant/remote-cache");
			if (Files.isDirectory(root)) {
				try (var stream = Files.list(root)) {
					stream.forEach(path -> {
						try {
							Files.deleteIfExists(path);
						} catch (IOException ignored) {
						}
					});
				}
			}
			Notifications.success("Celerant",
				net.minecraft.client.resources.language.I18n.get("celerant.multiplayer.cache_cleared"));
		} catch (IOException e) {
			Notifications.error("Celerant", e.getMessage());
		}
	}

	@Button(title = "Runtime status", category = "Interface", text = "Show",
		description = "Show model, rig, expression, Iris ShaderPack, and ToonShader state.")
	private void showStatus() {
		String pack = Iris.isPackInUseQuick() ? Iris.getCurrentPackName() : "disabled";
		Notifications.info("Celerant status", VrmRuntime.getInstance().info() + "\nIris: " + pack
			+ ", toon: " + (ToonShader.isEnabled() ? "on" : "off")
			+ ", bloom: " + (com.modularmods.mcgltf.ToonShader.isBloomEnabled() ? "on" : "off"));
	}

	private boolean setToonEnabled(Boolean enabled) {
		ToonShader.setEnabled(enabled);
		Notifications.success("Celerant rendering", "Toon shading " + (enabled ? "enabled." : "disabled."));
		return false;
	}

	private boolean setToonBloomEnabled(Boolean enabled) {
		com.modularmods.mcgltf.ToonShader.setBloomEnabled(enabled);
		Notifications.success("Celerant rendering", "Toon emission bloom "
			+ (enabled ? "enabled." : "disabled."));
		return false;
	}
}
