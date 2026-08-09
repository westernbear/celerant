package io.github.westernbear.celerant.client;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

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

	@Button(title = "Runtime status", category = "Interface", text = "Show",
		description = "Show model, rig, expression, Iris ShaderPack, and ToonShader state.")
	private void showStatus() {
		String pack = Iris.isPackInUseQuick() ? Iris.getCurrentPackName() : "disabled";
		Notifications.info("Celerant status", VrmRuntime.getInstance().info() + "\nIris: " + pack
			+ ", toon: " + (ToonShader.isEnabled() ? "on" : "off"));
	}

	private boolean setToonEnabled(Boolean enabled) {
		ToonShader.setEnabled(enabled);
		Notifications.success("Celerant rendering", "Toon shading " + (enabled ? "enabled." : "disabled."));
		return false;
	}
}
