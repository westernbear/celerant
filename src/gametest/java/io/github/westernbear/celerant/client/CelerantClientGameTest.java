package io.github.westernbear.celerant.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;

import io.github.westernbear.celerant.client.toon.ToonShader;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;

import org.polyfrost.oneconfig.api.notifications.v1.NotificationType;
import org.polyfrost.oneconfig.api.notifications.v1.NotificationsManager;
import org.lwjgl.glfw.GLFW;
public final class CelerantClientGameTest implements FabricClientGameTest {
	private static final String PACK_NAME = "CelerantTest";
	private static final String ONECONFIG_SCREEN =
		"org.polyfrost.oneconfig.internal.ui.compose.impls.OneConfigUIScreen";
	private static final String LOCAL_VISUAL_VRM = "_local_visual.vrm";
	private static final String DISABLE_TOON_SHADER_PROPERTY = "celerant.testing.disableToonShader";
	private static final FrameTimeRecorder MATRIX_FRAME_TIMES = new FrameTimeRecorder();
	private static boolean matrixFrameRecorderRegistered;
	private static volatile Path nextOneConfigFileSelection;
	private static volatile FileDialogRequest lastOneConfigFileDialog;
	private static final String VERTEX_SHADER = """
		#version 120
		varying vec2 texcoord;
		varying vec2 lmcoord;
		varying vec4 tint;
		void main() {
		    gl_Position = ftransform();
		    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
		    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
		    tint = gl_Color;
		}
		""";
	private static final String FRAGMENT_SHADER = """
		#version 120
		uniform sampler2D texture;
		uniform sampler2D lightmap;
		varying vec2 texcoord;
		varying vec2 lmcoord;
		varying vec4 tint;
		void main() {
		    vec4 albedo = texture2D(texture, texcoord) * tint;
		    gl_FragData[0] = albedo * texture2D(lightmap, lmcoord);
		}
		""";

	@Override
	public void runTest(ClientGameTestContext context) {
		Path gameDirectory = context.computeOnClient(client -> client.gameDirectory.toPath());
		String matrixDirectory = System.getenv("CELERANT_SHADERPACK_DIR");
		if (matrixDirectory != null && !matrixDirectory.isBlank()) {
			runShaderPackMatrix(context, gameDirectory, Path.of(matrixDirectory).toAbsolutePath().normalize());
			return;
		}
		Path packRoot = gameDirectory.resolve("shaderpacks").resolve(PACK_NAME);
		Path modelPath = gameDirectory.resolve("celerant/models/minimal.vrm");

		writeFixtures(packRoot, modelPath);
		boolean localVisualTest = prepareLocalVisualModel(gameDirectory);
		enableShaderPack(context, packRoot);

		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true).create()) {
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender(true, 1200);
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamerule advance_time false");
			world.getServer().runCommand("time set 6000");
			connection.waitForClientboundPackets();
			context.waitFor(client -> Iris.isPackInUseQuick()
				&& Iris.getPipelineManager().getPipeline().isPresent()
				&& Iris.getStoredError().isEmpty(), 1200);

			testOneConfigFlow(context, modelPath);
			testFailureFlow(context);
			testUserFlow(context, world, connection, gameDirectory);
			if (localVisualTest) {
				testLocalVisualFlow(context, world, connection);
			}
		}

		context.waitFor(client -> "VRM: not loaded".equals(VrmRuntime.getInstance().info()), 200);
		verifyPackUnchanged(packRoot);
		if (localVisualTest) {
			try {
				Files.deleteIfExists(gameDirectory.resolve("celerant/models").resolve(LOCAL_VISUAL_VRM));
			} catch (IOException exception) {
				throw new AssertionError("could not remove the local visual VRM copy", exception);
			}
		}
	}

	private static void testOneConfigFlow(ClientGameTestContext context, Path modelPath) {
		verifyOneConfigMetadata();
		resetUiOption(context, "modelPath", "");
		resetUiOption(context, "scale", 1.0F);
		resetUiOption(context, "avatarEnabled", false);
		resetUiOption(context, "expressionName", "");
		resetUiOption(context, "expressionWeight", 1.0F);
		resetUiOption(context, "toonEnabled", true);
		context.runOnClient(client -> {
			var key = CelerantClient.uiKey();
			require(key.getDefaultKey().equals(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_V)),
				"Celerant UI key default must be V");
			require(!key.isUnbound() && !KeyMappingHelper.getBoundKeyOf(key).equals(InputConstants.UNKNOWN),
				"Celerant UI key must survive Client GameTest option restore");
			require(Arrays.asList(client.options.keyMappings).contains(key),
				"Celerant UI key must be registered in vanilla Controls");
		});

		Object pipeline = context.computeOnClient(client -> Iris.getPipelineManager().getPipeline().orElseThrow());
		clearNotifications(context);
		context.waitTicks(5);
		BufferedImage worldFrame = capturePresentedWindow(context, "celerant-oneconfig-world-os");
		context.getInput().pressKey(CelerantClient.uiKey());
		context.waitFor(client -> client.gui.screen() != null
			&& ONECONFIG_SCREEN.equals(client.gui.screen().getClass().getName()), 400);
		Object firstScreen = context.computeOnClient(client -> client.gui.screen());
		context.waitTicks(20);
		assertCelerantOneConfigRoute(context);
		assertUiText(context, "Local VRM avatar");
		assertOneConfigPresented(worldFrame,
			capturePresentedWindow(context, "celerant-oneconfig-control-center-os"));

		clearNotifications(context);
		clickUiRowControl(context, "Load selected VRM", "OnClick :");
		require(!context.computeOnClient(client -> VrmRuntime.getInstance().isLoading()),
			"blank OneConfig model path must not start loading");
		waitForNotification(context, "Choose a .vrm file first", NotificationType.ERROR);

		Path invalidModel = modelPath.resolveSibling("not-a-vrm.txt").toAbsolutePath();
		selectOneConfigFile(context, invalidModel);
		FileDialogRequest dialog = lastOneConfigFileDialog;
		require(dialog != null && "VRM model".equals(dialog.title())
			&& dialog.defaultPath() == null
			&& "VRM models".equals(dialog.filterName()) && dialog.patterns().equals(List.of("*.vrm")),
			"OneConfig must open its native picker with the VRM-only filter");
		clearNotifications(context);
		clickUiRowControl(context, "Load selected VRM", "OnClick :");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading(), 200);
		require(!context.computeOnClient(client -> VrmRuntime.getInstance().isLoaded()),
			"invalid OneConfig model path must not install a model");
		waitForNotification(context, ".vrm", NotificationType.ERROR);

		selectOneConfigFile(context, modelPath.toAbsolutePath());
		clearNotifications(context);
		clickUiRowControl(context, "Load selected VRM", "OnClick :");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
			&& VrmRuntime.getInstance().info().contains("VRM: minimal.vrm"), 1200);
		waitForNotification(context, "Loaded minimal.vrm", NotificationType.SUCCESS);

		replaceUiText(context, "Model scale", 1, "2");
		context.waitFor(client -> Float.valueOf(2.0F).equals(
			CelerantConfig.INSTANCE.getTree().getProp("scale").get()), 200);
		assertInfoContains(context, "scale 2.000");
		clearNotifications(context);
		clickUiRowControl(context, "Place preview here", "OnClick :");
		assertInfoContains(context, "position ");
		waitForNotification(context, "Preview moved", NotificationType.SUCCESS);

		clickUiCategory(context, "Expressions");
		replaceUiText(context, "Expression name", 0, "smile");
		context.waitFor(client -> "smile".equals(
			CelerantConfig.INSTANCE.getTree().getProp("expressionName").get()), 200);
		clickUiSlider(context, "Expression weight", 0.25F);
		context.waitTicks(5);
		float sliderValue = context.computeOnClient(client ->
			(Float) CelerantConfig.INSTANCE.getTree().getProp("expressionWeight").get());
		require(Math.abs(sliderValue - 0.25F) < 0.01F,
			"OneConfig slider pointer input must set 0.25, got " + sliderValue);
		clearNotifications(context);
		clickUiRowControl(context, "Apply expression", "OnClick :");
		assertInfoContains(context, "expression smile 0.25");
		waitForNotification(context, "Expression applied", NotificationType.SUCCESS);
		replaceUiText(context, "Expression name", 5, "missing");
		clearNotifications(context);
		clickUiRowControl(context, "Apply expression", "OnClick :");
		assertInfoContains(context, "expression smile 0.25");
		waitForNotification(context, "Unknown expression", NotificationType.ERROR);
		clearNotifications(context);
		clickUiRowControl(context, "Available expressions", "OnClick :");
		waitForNotification(context, "smile, blink", NotificationType.INFO);
		clearNotifications(context);
		clickUiRowControl(context, "Clear expression", "OnClick :");
		assertInfoContains(context, "expression none");
		waitForNotification(context, "Expression cleared", NotificationType.SUCCESS);

		clearNotifications(context);
		clickUiCategory(context, "Avatar");
		clickUiRowControl(context, "Replace local player", "OnClick :");
		context.waitFor(client -> VrmRuntime.getInstance().isLocalAvatarActive()
			&& Boolean.TRUE.equals(CelerantConfig.INSTANCE.getTree().getProp("avatarEnabled").get()), 200);
		clickUiRowControl(context, "Replace local player", "OnClick :");
		context.waitFor(client -> !VrmRuntime.getInstance().isLocalAvatarActive()
			&& Boolean.FALSE.equals(CelerantConfig.INSTANCE.getTree().getProp("avatarEnabled").get()), 200);

		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null, 400);
		context.waitTicks(3);
		context.getInput().pressKey(CelerantClient.uiKey());
		waitForOneConfigScreen(context);
		require(context.computeOnClient(client -> client.gui.screen()) != firstScreen,
			"reopening Celerant must create a fresh OneConfig screen");
		assertCelerantOneConfigRoute(context);
		clickUiCategory(context, "Model");
		require(Float.valueOf(2.0F).equals(context.computeOnClient(client ->
			CelerantConfig.INSTANCE.getTree().getProp("scale").get())),
			"OneConfig values must survive closing and reopening the screen");
		require(context.computeOnClient(client -> rowControlConfig(client, "Model scale", "SetText :"))
			.contains("InputText : 2"), "reopened OneConfig must display the persisted scale");
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null, 400);
		require(context.computeOnClient(client -> Iris.getPipelineManager().getPipeline().orElseThrow()) == pipeline,
			"opening and closing OneConfig must preserve the active Iris pipeline");
		require(context.computeOnClient(client -> Iris.isPackInUseQuick() && Iris.getStoredError().isEmpty()),
			"OneConfig rendering must leave Iris healthy");

		context.waitTicks(3);
		context.getInput().pressKey(CelerantClient.uiKey());
		waitForOneConfigScreen(context);
		clickUiCategory(context, "Rendering");
		clickUiRowControl(context, "Iris toon shading", "OnClick :");
		context.waitFor(client -> !ToonShader.isEnabled()
			&& Boolean.FALSE.equals(CelerantConfig.INSTANCE.getTree().getProp("toonEnabled").get())
			&& Iris.getPipelineManager().getPipeline().isPresent() && Iris.getStoredError().isEmpty(), 1200);
		waitForOneConfigScreen(context);
		clickUiRowControl(context, "Iris toon shading", "OnClick :");
		context.waitFor(client -> ToonShader.isEnabled()
			&& Boolean.TRUE.equals(CelerantConfig.INSTANCE.getTree().getProp("toonEnabled").get())
			&& Iris.getPipelineManager().getPipeline().isPresent() && Iris.getStoredError().isEmpty(), 1200);

		waitForOneConfigScreen(context);
		clickUiCategory(context, "Interface");
		clearNotifications(context);
		clickUiRowControl(context, "Runtime status", "OnClick :");
		waitForNotification(context, "VRM: minimal.vrm", NotificationType.INFO);

		clickUiCategory(context, "Model");
		clearNotifications(context);
		clickUiRowControl(context, "Unload VRM", "OnClick :");
		context.waitFor(client -> "VRM: not loaded".equals(VrmRuntime.getInstance().info()), 200);
		waitForNotification(context, "VRM unloaded", NotificationType.SUCCESS);
		clearNotifications(context);
		clickUiRowControl(context, "Unload VRM", "OnClick :");
		waitForNotification(context, "No VRM is loaded", NotificationType.ERROR);

		clickUiCategory(context, "Avatar");
		clearNotifications(context);
		clickUiRowControl(context, "Replace local player", "OnClick :");
		context.waitFor(client -> Boolean.FALSE.equals(
			CelerantConfig.INSTANCE.getTree().getProp("avatarEnabled").get()), 200);
		waitForNotification(context, "Cannot enable avatar", NotificationType.ERROR);
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null, 400);
	}

	private static void verifyOneConfigMetadata() {
		try {
			String version = FabricLoader.getInstance().getModContainer("oneconfigbootstrap").orElseThrow()
				.getMetadata().getVersion().getFriendlyString();
			require("1.1.6".equals(version), "Celerant must run against OneConfig 1.1.6, got " + version);
			var file = CelerantConfig.class.getDeclaredField("modelPath")
				.getAnnotation(org.polyfrost.oneconfig.api.config.v1.annotations.File.class);
			require(file != null && Arrays.asList(file.types()).contains(".vrm"),
				"OneConfig model picker must filter for .vrm files");
			require("celerant".equals(CelerantConfig.INSTANCE.getTree().getID()),
				"OneConfig tree id must match the Celerant mod id");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect the OneConfig model picker", exception);
		}
	}

	private static void resetUiOption(ClientGameTestContext context, String name, Object value) {
		context.runOnClient(client -> {
			var property = CelerantConfig.INSTANCE.getTree().getProp(name);
			require(property != null, "missing OneConfig option: " + name);
			property.setAs(value);
			require(java.util.Objects.equals(property.get(), value), "OneConfig rejected option: " + name);
		});
	}

	public static Path takeOneConfigFileSelection(String title, String defaultPath, String[] patterns,
		String filterName) {
		Path selected = nextOneConfigFileSelection;
		require(selected != null, "OneConfig opened an unexpected native file dialog during Client GameTest");
		lastOneConfigFileDialog = new FileDialogRequest(title, defaultPath,
			List.copyOf(Arrays.asList(patterns)), filterName);
		nextOneConfigFileSelection = null;
		return selected;
	}

	private static void selectOneConfigFile(ClientGameTestContext context, Path selected) {
		lastOneConfigFileDialog = null;
		nextOneConfigFileSelection = selected;
		clickUiRowControl(context, "VRM model", "OnClick :");
		context.waitFor(client -> lastOneConfigFileDialog != null && selected.toString().equals(
			CelerantConfig.INSTANCE.getTree().getProp("modelPath").get()), 400);
	}

	private static void waitForOneConfigScreen(ClientGameTestContext context) {
		context.waitFor(client -> client.gui.screen() != null
			&& ONECONFIG_SCREEN.equals(client.gui.screen().getClass().getName())
			&& isCelerantOneConfigRoute(client) && hasUiText(client, "Model"), 400);
	}

	private static void clickUiCategory(ClientGameTestContext context, String category) {
		clickUiBounds(context, context.computeOnClient(client -> findClickableTextBounds(client, category)));
		String marker = switch (category) {
			case "Model" -> "VRM model";
			case "Avatar" -> "Replace local player";
			case "Expressions" -> "Expression name";
			case "Rendering" -> "Iris toon shading";
			case "Interface" -> "Runtime status";
			default -> throw new AssertionError("unknown OneConfig category: " + category);
		};
		context.waitFor(client -> hasUiText(client, marker), 200);
	}

	private static void clickUiRowControl(ClientGameTestContext context, String title, String semanticKey) {
		clickUiBounds(context, context.computeOnClient(client -> findRowControlBounds(client, title, semanticKey)));
		context.waitTicks(3);
	}

	private static void replaceUiText(ClientGameTestContext context, String title, int oldLength, String value) {
		clickUiBounds(context, context.computeOnClient(client -> findRowControlBounds(client, title, "SetText :")));
		context.waitFor(client -> rowControlConfig(client, title, "SetText :").contains("Focused : true"), 100);
		context.getInput().pressKey(GLFW.GLFW_KEY_END);
		for (int index = 0; index < oldLength; index++) {
			context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
		}
		context.getInput().typeChars(value);
		context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		context.waitTicks(3);
	}

	private static void clickUiSlider(ClientGameTestContext context, String title, float fraction) {
		UiPoint point = context.computeOnClient(client -> findSliderPoint(client, title, fraction));
		context.getInput().setCursorPos(point.x(), point.y());
		context.waitTicks(1);
		context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	private static void clickUiBounds(ClientGameTestContext context, UiBounds bounds) {
		context.getInput().setCursorPos(bounds.centerX(), bounds.centerY());
		context.waitTicks(1);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
	}

	private static void assertUiText(ClientGameTestContext context, String text) {
		require(context.computeOnClient(client -> hasUiText(client, text)),
			"OneConfig screen must expose: " + text);
	}

	private static void assertCelerantOneConfigRoute(ClientGameTestContext context) {
		require(context.computeOnClient(CelerantClientGameTest::isCelerantOneConfigRoute),
			"V must open Celerant's own OneConfig tree");
	}

	private static boolean isCelerantOneConfigRoute(Minecraft client) {
		try {
			return reflectedField(client.gui.screen(), "initialTree") == CelerantConfig.INSTANCE.getTree();
		} catch (ReflectiveOperationException | NullPointerException ignored) {
			return false;
		}
	}

	private static boolean hasUiText(Minecraft client, String text) {
		try {
			return semanticNodes(client).stream().anyMatch(node -> hasExactText(semanticConfig(node), text));
		} catch (AssertionError | NullPointerException ignored) {
			return false;
		}
	}

	private static BufferedImage capturePresentedWindow(ClientGameTestContext context, String name) {
		int[] bounds = context.computeOnClient(client -> new int[] {
			client.getWindow().getX(), client.getWindow().getY(),
			client.getWindow().getWidth(), client.getWindow().getHeight()
		});
		require(bounds[2] > 0 && bounds[3] > 0, "Client window must have a visible size");
		try {
			Path output = context.computeOnClient(client -> client.gameDirectory.toPath())
				.resolve("screenshots").resolve(name + ".png");
			Files.createDirectories(output.getParent());
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			Path helperClasses = Path.of(PresentedWindowCapture.class.getProtectionDomain()
				.getCodeSource().getLocation().toURI());
			Process process = new ProcessBuilder(java.toString(), "-Djava.awt.headless=false", "-cp",
				helperClasses.toString(), PresentedWindowCapture.class.getName(),
				Integer.toString(bounds[0]), Integer.toString(bounds[1]),
				Integer.toString(bounds[2]), Integer.toString(bounds[3]), output.toString())
				.inheritIO().start();
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new AssertionError("presented window capture timed out");
			}
			require(process.exitValue() == 0, "presented window capture process must succeed");
			BufferedImage image = ImageIO.read(output.toFile());
			require(image != null, "presented window capture must be a PNG");
			return image;
		} catch (IOException | URISyntaxException exception) {
			throw new AssertionError("could not capture the presented OneConfig window", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("presented OneConfig capture was interrupted", exception);
		}
	}

	private static void assertOneConfigPresented(BufferedImage world, BufferedImage ui) {
		require(world.getWidth() == ui.getWidth() && world.getHeight() == ui.getHeight(),
			"presented UI comparison must use the same window size");
		long changed = 0;
		long visibleWorld = 0;
		long visibleUi = 0;
		long pixels = (long) world.getWidth() * world.getHeight();
		for (int y = 0; y < world.getHeight(); y++) {
			for (int x = 0; x < world.getWidth(); x++) {
				int before = world.getRGB(x, y);
				int after = ui.getRGB(x, y);
				int beforeLight = ((before >>> 16) & 255) + ((before >>> 8) & 255) + (before & 255);
				int afterLight = ((after >>> 16) & 255) + ((after >>> 8) & 255) + (after & 255);
				if (beforeLight > 24) {
					visibleWorld++;
				}
				if (afterLight > 24) {
					visibleUi++;
				}
				int delta = Math.abs(((before >>> 16) & 255) - ((after >>> 16) & 255))
					+ Math.abs(((before >>> 8) & 255) - ((after >>> 8) & 255))
					+ Math.abs((before & 255) - (after & 255));
				if (delta >= 48) {
					changed++;
				}
			}
		}
		require(visibleWorld >= pixels / 2 && visibleUi >= pixels / 2,
			"X11 must present non-black world and OneConfig frames");
		require(changed >= pixels * 2 / 5,
			"OneConfig must visibly change at least 40% of the presented client window");
	}

	private static UiBounds findClickableTextBounds(Minecraft client, String text) {
		UiBounds best = null;
		for (Object node : semanticNodes(client)) {
			if (!hasExactText(semanticConfig(node), text)) {
				continue;
			}
			for (Object parent = semanticParent(node); parent != null; parent = semanticParent(parent)) {
				if (semanticConfig(parent).contains("OnClick :")) {
					UiBounds bounds = semanticBounds(parent);
					if (best == null || bounds.left() < best.left()) {
						best = bounds;
					}
					break;
				}
			}
		}
		if (best == null) {
			throw new AssertionError("missing clickable OneConfig text: " + text);
		}
		return best;
	}

	private static UiBounds findRowControlBounds(Minecraft client, String title, String semanticKey) {
		Object row = findSettingRow(client, title);
		UiBounds best = null;
		for (Object node : descendants(row)) {
			if (!semanticConfig(node).contains(semanticKey)) {
				continue;
			}
			UiBounds bounds = semanticBounds(node);
			if (best == null || bounds.left() > best.left()) {
				best = bounds;
			}
		}
		if (best == null) {
			throw new AssertionError("missing OneConfig control: " + title + " / " + semanticKey);
		}
		return best;
	}

	private static String rowControlConfig(Minecraft client, String title, String semanticKey) {
		UiBounds wanted = findRowControlBounds(client, title, semanticKey);
		return descendants(findSettingRow(client, title)).stream()
			.filter(node -> semanticConfig(node).contains(semanticKey))
			.filter(node -> semanticBounds(node).equals(wanted))
			.map(CelerantClientGameTest::semanticConfig)
			.findFirst().orElse("");
	}

	private static UiPoint findSliderPoint(Minecraft client, String title, float fraction) {
		Object row = findSettingRow(client, title);
		UiBounds track = descendants(row).stream()
			.map(CelerantClientGameTest::semanticBounds)
			.filter(bounds -> bounds.width() > 100.0F && bounds.height() <= 8.0F)
			.max((left, right) -> Float.compare(left.width(), right.width()))
			.orElseThrow(() -> new AssertionError("missing OneConfig slider track: " + title));
		return new UiPoint(track.left() + track.width() * fraction, track.centerY());
	}

	private static Object findSettingRow(Minecraft client, String title) {
		for (Object node : semanticNodes(client)) {
			if (!hasExactText(semanticConfig(node), title)) {
				continue;
			}
			for (Object parent = semanticParent(node); parent != null; parent = semanticParent(parent)) {
				UiBounds bounds = semanticBounds(parent);
				if (bounds.left() > 150.0F && bounds.width() > 300.0F
					&& bounds.height() >= 25.0F && bounds.height() <= 80.0F) {
					return parent;
				}
			}
		}
		throw new AssertionError("missing OneConfig setting row: " + title);
	}

	private static List<Object> semanticNodes(Minecraft client) {
		try {
			Object scene = reflectedField(client.gui.screen(), "sceneOrNull");
			Object owner = reflectedField(scene, "mainOwner");
			Object semanticsOwner = owner.getClass().getMethod("getSemanticsOwner").invoke(owner);
			Object root = semanticsOwner.getClass().getMethod("getUnmergedRootSemanticsNode").invoke(semanticsOwner);
			return descendants(root);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect OneConfig semantics", exception);
		}
	}

	private static List<Object> descendants(Object root) {
		try {
			List<Object> nodes = new ArrayList<>();
			nodes.add(root);
			for (Object child : (List<?>) root.getClass().getMethod("getChildren").invoke(root)) {
				nodes.addAll(descendants(child));
			}
			return nodes;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not traverse OneConfig semantics", exception);
		}
	}

	private static Object semanticParent(Object node) {
		try {
			return node.getClass().getMethod("getParent").invoke(node);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not traverse OneConfig parent", exception);
		}
	}

	private static String semanticConfig(Object node) {
		try {
			return node.getClass().getMethod("getConfig").invoke(node).toString();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect OneConfig control", exception);
		}
	}

	private static UiBounds semanticBounds(Object node) {
		try {
			Object bounds = node.getClass().getMethod("getBoundsInRoot").invoke(node);
			Class<?> type = bounds.getClass();
			return new UiBounds(
				((Number) type.getMethod("getLeft").invoke(bounds)).floatValue(),
				((Number) type.getMethod("getTop").invoke(bounds)).floatValue(),
				((Number) type.getMethod("getRight").invoke(bounds)).floatValue(),
				((Number) type.getMethod("getBottom").invoke(bounds)).floatValue());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect OneConfig bounds", exception);
		}
	}

	private static boolean hasExactText(String config, String text) {
		return config.contains("Text : [" + text + "]");
	}

	private static Object reflectedField(Object target, String name) throws ReflectiveOperationException {
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(target);
			} catch (NoSuchFieldException ignored) {
				// Try the superclass.
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static void clearNotifications(ClientGameTestContext context) {
		context.runOnClient(client -> {
			NotificationsManager.INSTANCE.clearAll();
			NotificationsManager.INSTANCE.clearHistory();
		});
		context.waitTicks(1);
	}

	private static void waitForNotification(ClientGameTestContext context, String text, NotificationType type) {
		context.waitFor(client -> NotificationsManager.INSTANCE.getHistory().stream()
			.anyMatch(notification -> notification.getType() == type
				&& notification.getMessage().contains(text)), 400);
	}

	private record FileDialogRequest(String title, String defaultPath, List<String> patterns, String filterName) { }

	private record UiPoint(float x, float y) { }

	private record UiBounds(float left, float top, float right, float bottom) {
		float width() {
			return right - left;
		}

		float height() {
			return bottom - top;
		}

		float centerX() {
			return (left + right) / 2.0F;
		}

		float centerY() {
			return (top + bottom) / 2.0F;
		}
	}

	private static void testFailureFlow(ClientGameTestContext context) {
		sendCommand(context, "celerant vrm unload", "No VRM is loaded");
		sendCommand(context, "celerant vrm expression clear", "No VRM is loaded");
		sendCommand(context, "celerant vrm expression missing", "Unknown expression or no VRM loaded");

		sendCommand(context, "celerant vrm load bad.txt", "Loading VRM asynchronously");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
			&& chatContains(client, "only relative .vrm paths are allowed"), 200);

		sendCommand(context, "celerant vrm load \"../minimal.vrm\"", "Loading VRM asynchronously");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
			&& chatContains(client, "model path escapes celerant/models"), 200);
		require("VRM: not loaded".equals(context.computeOnClient(client -> VrmRuntime.getInstance().info())),
			"failed loads must not install a model");
	}

	private static void testUserFlow(ClientGameTestContext context, TestSingleplayerContext world,
		TestServerConnection connection, Path gameDirectory) {
		sendCommand(context, "celerant vrm load minimal.vrm", "Loading VRM asynchronously");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
			&& VrmRuntime.getInstance().info().contains("VRM: minimal.vrm"), 1200);
		context.waitFor(client -> chatContains(client, "Loaded minimal.vrm (2 expressions)"), 200);

		sendCommand(context, "celerant vrm expression", "smile, blink");
		sendCommand(context, "celerant vrm expression unknown", "Unknown expression or no VRM loaded");
		sendCommand(context, "celerant vrm expression smile", "VRM expression set to smile at 1.0");
		assertInfoContains(context, "expression smile 1.00");
		sendCommand(context, "celerant vrm expression smile 0.25", "VRM expression set to smile at 0.25");
		assertInfoContains(context, "expression smile 0.25");
		sendCommand(context, "celerant vrm expression blink 0.49", "VRM expression set to blink at 0.49");
		assertInfoContains(context, "expression blink 0.00");
		sendCommand(context, "celerant vrm expression blink 0.5", "VRM expression set to blink at 0.5");
		assertInfoContains(context, "expression blink 1.00");
		sendCommand(context, "celerant vrm expression clear", "VRM expression cleared");
		assertInfoContains(context, "expression none");

		double y = context.computeOnClient(client -> client.player.getY());
		teleport(context, world, connection, 0.0, y, 0.0);
		sendCommand(context, "celerant vrm here", "VRM position set to your current position");
		assertInfoContains(context, String.format(Locale.ROOT, "position 0.00 %.2f 0.00", y));
		sendCommand(context, "celerant vrm scale 2", "VRM scale set to 2.0");
		assertInfoContains(context, "scale 2.000");
		context.waitFor(client -> Float.valueOf(2.0F).equals(
			CelerantConfig.INSTANCE.getTree().getProp("scale").get()), 100);

		teleport(context, world, connection, 0.0, y, 4.0);
		context.getInput().lookAt(BlockPos.containing(0.0, y + 1.5, 0.0));
		context.waitTicks(20);
		sendCommand(context, "celerant vrm info", "VRM: minimal.vrm");
		verifyRuntimeToonShader(context);

		Path base = context.takeScreenshot("celerant-vrm-base");
		sendCommand(context, "celerant vrm expression smile 1", "VRM expression set to smile at 1.0");
		context.waitTicks(10);
		Path morphed = context.takeScreenshot("celerant-vrm-morphed");
		verifyRenderedScreenshots(base, morphed);

		testAvatarFlow(context);

		sendCommand(context, "celerant vrm unload", "VRM unloaded");
		require("VRM: not loaded".equals(context.computeOnClient(client -> VrmRuntime.getInstance().info())),
			"unload command must release the model");

		// Leave one model loaded so closing the world verifies the disconnect cleanup path.
		sendCommand(context, "celerant vrm load minimal.vrm", "Loading VRM asynchronously");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
			&& VrmRuntime.getInstance().info().contains("VRM: minimal.vrm"), 1200);
	}

	private static void testAvatarFlow(ClientGameTestContext context) {
		sendCommand(context, "celerant vrm scale 1", "VRM scale set to 1.0");
		sendCommand(context, "celerant vrm avatar true", "VRM avatar enabled");
		assertInfoContains(context, "avatar on");
		assertInfoContains(context, "rig 15");
		context.waitFor(client -> Boolean.TRUE.equals(
			CelerantConfig.INSTANCE.getTree().getProp("avatarEnabled").get()), 100);

		try {
			setCamera(context, CameraType.THIRD_PERSON_BACK);
			context.waitTicks(10);
			assertMagenta(context.takeScreenshot("celerant-avatar-third-back"),
				"third-person back VRM must be visible");

			setCamera(context, CameraType.THIRD_PERSON_FRONT);
			context.waitTicks(10);
			Path idleAvatar = context.takeScreenshot("celerant-avatar-third-front");
			assertMagenta(idleAvatar, "third-person front VRM must be visible");

			float[] idleLeg = context.computeOnClient(client ->
				VrmRuntime.getInstance().debugBoneRotation("leftUpperLeg"));
			context.getInput().holdKey(options -> options.keyUp);
			float[] walkingLeg;
			Path walkingAvatar;
			try {
				context.waitTicks(8);
				walkingLeg = context.computeOnClient(client ->
					VrmRuntime.getInstance().debugBoneRotation("leftUpperLeg"));
				walkingAvatar = context.takeScreenshot("celerant-avatar-third-front-walking");
			} finally {
				context.getInput().releaseKey(options -> options.keyUp);
			}
			require(quaternionDistance(idleLeg, walkingLeg) > 0.01F,
				"walking input must animate the VRM left upper leg");
			assertMagentaMasksDiffer(idleAvatar, walkingAvatar,
				"walking must visibly deform the skinned VRM");

			context.getInput().holdKey(options -> options.keyJump);
			float[] risingLeg;
			Path risingAvatar;
			try {
				context.waitFor(client -> client.player != null && !client.player.onGround()
					&& client.player.getDeltaMovement().y > 0.20, 100);
				context.waitTicks(1);
				risingLeg = context.computeOnClient(client ->
					VrmRuntime.getInstance().debugBoneRotation("leftUpperLeg"));
				risingAvatar = context.takeScreenshot("celerant-avatar-jump-rising");
			} finally {
				context.getInput().releaseKey(options -> options.keyJump);
			}
			context.waitFor(client -> client.player != null && !client.player.onGround()
				&& client.player.getDeltaMovement().y < -0.20, 100);
			context.waitTicks(1);
			float[] fallingLeg = context.computeOnClient(client ->
				VrmRuntime.getInstance().debugBoneRotation("leftUpperLeg"));
			Path fallingAvatar = context.takeScreenshot("celerant-avatar-jump-falling");
			require(quaternionDistance(risingLeg, fallingLeg) > 0.01F,
				"rising and falling must use distinct VRM leg poses");
			assertMagentaMasksDiffer(risingAvatar, fallingAvatar,
				"rising and falling must visibly deform the skinned VRM differently");
			context.waitFor(client -> client.player != null && client.player.onGround(), 200);

			setCamera(context, CameraType.FIRST_PERSON);
			context.getInput().lookAt(180.0F, 70.0F);
			context.waitTicks(10);
			Path firstPersonAvatar = context.takeScreenshot("celerant-avatar-first-person");
			assertAutoHeadFiltered(idleAvatar, firstPersonAvatar);
		} finally {
			sendCommand(context, "celerant vrm avatar false", "VRM avatar disabled");
			context.waitFor(client -> Boolean.FALSE.equals(
				CelerantConfig.INSTANCE.getTree().getProp("avatarEnabled").get()), 100);
			setCamera(context, CameraType.FIRST_PERSON);
		}
	}

	private static void setCamera(ClientGameTestContext context, CameraType cameraType) {
		context.runOnClient(client -> client.options.setCameraType(cameraType));
		context.waitFor(client -> client.options.getCameraType() == cameraType, 100);
	}

	private static void testLocalVisualFlow(ClientGameTestContext context, TestSingleplayerContext world,
		TestServerConnection connection) {
		sendCommand(context, "celerant vrm unload", "VRM unloaded");
		sendCommand(context, "celerant vrm load " + LOCAL_VISUAL_VRM, "Loading VRM asynchronously");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
			&& VrmRuntime.getInstance().info().contains("VRM: " + LOCAL_VISUAL_VRM), 2400);

		double y = context.computeOnClient(client -> client.player.getY());
		teleport(context, world, connection, 0.0, y, 0.0);
		sendCommand(context, "celerant vrm here", "VRM position set to your current position");
		sendCommand(context, "celerant vrm scale 1", "VRM scale set to 1.0");
		teleport(context, world, connection, 0.0, y, 4.0);
		setCamera(context, CameraType.FIRST_PERSON);
		context.getInput().lookAt(180.0F, 8.0F);
		int previousFov = context.computeOnClient(client -> client.options.fov().get());
		int previousWidth = context.computeOnClient(client -> client.getWindow().getWidth());
		int previousHeight = context.computeOnClient(client -> client.getWindow().getHeight());
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(client -> client.getWindow().getWidth() == 1280
			&& client.getWindow().getHeight() == 720, 100);
		context.runOnClient(client -> client.options.fov().set(30));
		context.waitFor(client -> client.options.fov().get() == 30, 100);

		String[] names = {"morning", "noon", "sunset", "night"};
		int[] times = {0, 6000, 12500, 18000};
		context.getInput().pressKey(options -> options.keyToggleGui);
		try {
			world.getServer().runCommand("time set 6000");
			connection.waitForClientboundPackets();
			Path[] localToon = captureToonComparison(context, "celerant-local-vrm-noon-toon");
			assertLocalToonMaterialChanged(localToon[0], localToon[1], localToon[2]);

			sendCommand(context, "celerant vrm avatar true", "VRM avatar enabled");
			context.getInput().lookAt(180.0F, 8.0F);
			setCamera(context, CameraType.THIRD_PERSON_FRONT);
			for (int index = 0; index < names.length; index++) {
				world.getServer().runCommand("time set " + times[index]);
				connection.waitForClientboundPackets();
				context.waitTicks(30);
				Path front = context.takeScreenshot("celerant-local-avatar-" + names[index] + "-front");
				assertNonEmpty(front, "local VRM front screenshot must not be empty");
				System.out.println("[Celerant visual test] " + front);
				setCamera(context, CameraType.THIRD_PERSON_BACK);
				context.waitTicks(10);
				Path back = context.takeScreenshot("celerant-local-avatar-" + names[index] + "-back");
				assertNonEmpty(back, "local VRM back screenshot must not be empty");
				System.out.println("[Celerant visual test] " + back);
				setCamera(context, CameraType.THIRD_PERSON_FRONT);
			}

			context.runOnClient(client -> client.options.fov().set(50));
			context.waitFor(client -> client.options.fov().get() == 50, 100);
			world.getServer().runCommand("time set 6000");
			connection.waitForClientboundPackets();
			context.waitTicks(20);
			Path idle = context.takeScreenshot("celerant-local-avatar-noon-walk-idle");
			context.getInput().holdKey(options -> options.keyUp);
			Path walking;
			try {
				context.waitTicks(8);
				walking = context.takeScreenshot("celerant-local-avatar-noon-walk-moving");
			} finally {
				context.getInput().releaseKey(options -> options.keyUp);
			}
			assertNonEmpty(idle, "local idle screenshot must not be empty");
			assertNonEmpty(walking, "local walking screenshot must not be empty");
			System.out.println("[Celerant visual test] " + idle);
			System.out.println("[Celerant visual test] " + walking);
			context.waitTicks(10);

			context.getInput().holdKey(options -> options.keyJump);
			Path rising;
			try {
				context.waitFor(client -> client.player != null && !client.player.onGround()
					&& client.player.getDeltaMovement().y > 0.20, 100);
				context.waitTicks(1);
				rising = context.takeScreenshot("celerant-local-avatar-noon-jump-rising");
			} finally {
				context.getInput().releaseKey(options -> options.keyJump);
			}
			context.waitFor(client -> client.player != null && !client.player.onGround()
				&& client.player.getDeltaMovement().y < -0.20, 100);
			context.waitTicks(1);
			Path falling = context.takeScreenshot("celerant-local-avatar-noon-jump-falling");
			assertNonEmpty(rising, "local rising screenshot must not be empty");
			assertNonEmpty(falling, "local falling screenshot must not be empty");
			System.out.println("[Celerant visual test] " + rising);
			System.out.println("[Celerant visual test] " + falling);
			context.waitFor(client -> client.player != null && client.player.onGround(), 200);

			context.runOnClient(client -> client.options.fov().set(previousFov));
			context.waitFor(client -> client.options.fov().get() == previousFov, 100);
			setCamera(context, CameraType.FIRST_PERSON);
			context.getInput().lookAt(180.0F, 45.0F);
			context.waitTicks(20);
			Path firstPerson = context.takeScreenshot("celerant-local-avatar-noon-first-person");
			assertNonEmpty(firstPerson, "local first-person screenshot must not be empty");
			System.out.println("[Celerant visual test] " + firstPerson);
		} finally {
			sendCommand(context, "celerant vrm avatar false", "VRM avatar disabled");
			setCamera(context, CameraType.FIRST_PERSON);
			context.runOnClient(client -> client.options.fov().set(previousFov));
			context.getInput().resizeWindow(previousWidth, previousHeight);
			context.getInput().pressKey(options -> options.keyToggleGui);
		}
	}

	private static void runShaderPackMatrix(ClientGameTestContext context, Path gameDirectory,
		Path sourceDirectory) {
		require(Files.isDirectory(sourceDirectory),
			"CELERANT_SHADERPACK_DIR must point to a directory containing ShaderPack ZIPs");
		require(prepareLocalVisualModel(gameDirectory),
			"CELERANT_VISUAL_VRM is required when CELERANT_SHADERPACK_DIR is set");

		List<Path> sources;
		try (var files = Files.list(sourceDirectory)) {
			sources = files
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.toList();
		} catch (IOException exception) {
			throw new AssertionError("could not list CELERANT_SHADERPACK_DIR", exception);
		}

		boolean previousToonShader = context.computeOnClient(client -> ToonShader.isEnabled());
		context.runOnClient(client -> {
			ToonShader.setEnabled(true);
			if (!matrixFrameRecorderRegistered) {
				LevelRenderEvents.END_MAIN.register(renderContext -> MATRIX_FRAME_TIMES.record(System.nanoTime()));
				matrixFrameRecorderRegistered = true;
			}
		});

		Path report = gameDirectory.resolve("celerant-shaderpack-matrix.tsv");
		List<MatrixRow> rows = new ArrayList<>();
		List<Path> workingCopies = new ArrayList<>();
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true).create()) {
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender(true, 1200);
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("gamerule advance_time false");
			world.getServer().runCommand("kill @e[type=minecraft:slime]");
			world.getServer().runCommand("time set 6000");
			connection.waitForClientboundPackets();

			sendCommand(context, "celerant vrm load " + LOCAL_VISUAL_VRM, "Loading VRM asynchronously");
			context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
				&& VrmRuntime.getInstance().info().contains("VRM: " + LOCAL_VISUAL_VRM), 2400);
			double y = context.computeOnClient(client -> client.player.getY());
			teleport(context, world, connection, 0.0, y, 0.0);
			sendCommand(context, "celerant vrm here", "VRM position set to your current position");
			sendCommand(context, "celerant vrm scale 1", "VRM scale set to 1.0");
			teleport(context, world, connection, 0.0, y, 4.0);

			int previousFov = context.computeOnClient(client -> client.options.fov().get());
			boolean previousVsync = context.computeOnClient(client -> client.options.enableVsync().get());
			int previousFramerateLimit = context.computeOnClient(client -> client.options.framerateLimit().get());
			int previousWidth = context.computeOnClient(client -> client.getWindow().getWidth());
			int previousHeight = context.computeOnClient(client -> client.getWindow().getHeight());
			boolean guiToggled = false;
			try {
				context.getInput().resizeWindow(1280, 720);
				context.waitFor(client -> client.getWindow().getWidth() == 1280
					&& client.getWindow().getHeight() == 720, 100);
				context.runOnClient(client -> {
					client.options.fov().set(30);
					client.options.enableVsync().set(false);
					client.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
				});
				setCamera(context, CameraType.FIRST_PERSON);
				context.getInput().lookAt(180.0F, 8.0F);
				context.getInput().pressKey(options -> options.keyToggleGui);
				guiToggled = true;
				MatrixState baseline = captureMatrixState(context, null, false, false,
					"celerant-shader-matrix-baseline-off", true);
				writeMatrixReportStart(report, sources.size(), baseline);

				for (int index = 0; index < sources.size(); index++) {
					MatrixRow row = runShaderPackMatrixRow(context, world, connection, gameDirectory,
						sources.get(index), index + 1, workingCopies);
					rows.add(row);
					appendMatrixRow(report, row);
				}
			} finally {
				System.clearProperty(DISABLE_TOON_SHADER_PROPERTY);
				cleanupMatrixWorkingPacks(context, workingCopies);
				context.runOnClient(client -> {
					client.options.fov().set(previousFov);
					client.options.enableVsync().set(previousVsync);
					client.options.framerateLimit().set(previousFramerateLimit);
				});
				setCamera(context, CameraType.FIRST_PERSON);
				context.getInput().resizeWindow(previousWidth, previousHeight);
				if (guiToggled) {
					context.getInput().pressKey(options -> options.keyToggleGui);
				}
			}
		} finally {
			ToonShader.setEnabled(previousToonShader);
		}

		require(rows.size() == sources.size(),
			"shader matrix must write exactly one row per input ZIP (rows=" + rows.size()
				+ ", ZIPs=" + sources.size() + ")");
		System.out.println("[Celerant shader matrix] " + report + " (packs=" + rows.size() + ")");
	}

	private static MatrixRow runShaderPackMatrixRow(ClientGameTestContext context, TestSingleplayerContext world,
		TestServerConnection connection, Path gameDirectory, Path source, int index, List<Path> workingCopies) {
		Path shaderpacks = gameDirectory.resolve("shaderpacks");
		String prefix = String.format(Locale.ROOT, "celerant-shader-matrix-%03d", index);
		String sourceHashBefore = "";
		String copiedHash = "";
		String error = "";
		Path workingPack = null;
		try {
			Files.createDirectories(shaderpacks);
			workingPack = Files.createTempFile(shaderpacks, prefix + "-", ".zip");
			workingCopies.add(workingPack);
			workingPack.toFile().deleteOnExit();
		} catch (Exception exception) {
			error = addProblem(error, "reserve working copy", exception);
		}
		String packName = workingPack == null ? prefix + "-missing.zip" : workingPack.getFileName().toString();
		try {
			sourceHashBefore = sha256(source);
		} catch (Exception exception) {
			error = addProblem(error, "source hash before", exception);
		}
		try {
			if (workingPack == null) {
				throw new IOException("working ZIP path is unavailable");
			}
			Files.copy(source, workingPack, StandardCopyOption.REPLACE_EXISTING);
			copiedHash = sha256(workingPack);
			if (!Iris.isValidShaderpack(workingPack)) {
				error = addProblem(error, "Iris rejected the working ZIP");
			}
		} catch (Exception | AssertionError exception) {
			error = addProblem(error, "working copy", exception);
		}
		Map<String, Long> dumpSnapshot = Map.of();
		try {
			dumpSnapshot = snapshotDebugDump(gameDirectory.resolve("patched_shaders"));
		} catch (Exception exception) {
			error = addProblem(error, "snapshot debug dump", exception);
		}

		MatrixState on = captureMatrixState(context, packName, true, false, prefix + "-on", true);
		ShaderDumpStats dumpStats = new ShaderDumpStats(0, 0);
		try {
			dumpStats = countPatchedEntityShaders(gameDirectory.resolve("patched_shaders"), dumpSnapshot);
		} catch (Exception exception) {
			error = addProblem(error, "inspect debug dump", exception);
		}
		MatrixState off = captureMatrixState(context, packName, true, true, prefix + "-off", true);
		MatrixState restored = captureMatrixState(context, packName, true, false, prefix + "-restored", false);
		try {
			writeRestoredToonEvidence(gameDirectory, index, restored.image());
			captureDirectionalToon(context, world, connection, gameDirectory, index, prefix);
		} catch (Exception | AssertionError exception) {
			error = addProblem(error, "visual evidence captures", exception);
		}

		ToonSignal toonSignal = new ToonSignal(false, 0, 0, "", 0, 0, 0);
		try {
			toonSignal = measureToonSignal(on.image(), off.image(), restored.image());
		} catch (Exception exception) {
			error = addProblem(error, "toon signal", exception);
		}
		String sourceHashAfter = "";
		try {
			sourceHashAfter = sha256(source);
		} catch (Exception exception) {
			error = addProblem(error, "source hash after", exception);
		}
		boolean sourceHashIntact = !sourceHashBefore.isEmpty() && sourceHashBefore.equals(sourceHashAfter);
		boolean copyHashMatches = !sourceHashBefore.isEmpty() && sourceHashBefore.equals(copiedHash);
		return new MatrixRow(source, sourceHashBefore, sourceHashAfter, sourceHashIntact, copyHashMatches,
			on, dumpStats, off, restored, toonSignal, error);
	}

	private static void captureDirectionalToon(ClientGameTestContext context, TestSingleplayerContext world,
		TestServerConnection connection, Path gameDirectory, int row, String prefix) throws IOException {
		int[] times = {1000, 11000};
		String[] names = {"light-east", "light-west"};
		Path evidenceDirectory = toonEvidenceDirectory(gameDirectory, row);
		try {
			for (int index = 0; index < times.length; index++) {
				world.getServer().runCommand("time set " + times[index]);
				connection.waitForClientboundPackets();
				context.waitTicks(30);
				Path image = context.takeScreenshot(prefix + "-toon-on-" + names[index]);
				assertNonEmpty(image, "directional ToonShader screenshot must not be empty");
				writeToonCropPair(image, evidenceDirectory, "toon-on-" + names[index] + "-face",
					560, 180, 160, 150);
				System.out.println("[Celerant visual test] " + image);
			}
		} finally {
			world.getServer().runCommand("time set 6000");
			connection.waitForClientboundPackets();
			context.waitTicks(20);
		}
	}

	private static void writeRestoredToonEvidence(Path gameDirectory, int row, Path screenshot) throws IOException {
		if (screenshot == null) {
			throw new IOException("restored ToonShader screenshot is unavailable");
		}
		Path directory = toonEvidenceDirectory(gameDirectory, row);
		try (NativeImage image = NativeImage.read(Files.newInputStream(screenshot))) {
			requireEvidenceViewport(image);
			writeToonCropPair(image, directory, "toon-on-restored-full-character", 390, 180, 500, 540);
			writeToonCropPair(image, directory, "toon-on-restored-face", 560, 180, 160, 150);
			writeToonCropPair(image, directory, "toon-on-restored-hair", 500, 140, 280, 250);
			writeToonCropPair(image, directory, "toon-on-restored-white-cloth", 460, 270, 360, 260);
			writeToonCropPair(image, directory, "toon-on-restored-metal-earring", 620, 220, 120, 170);
			writeToonCropPair(image, directory, "toon-on-restored-metal-boots", 555, 545, 170, 170);
		}
	}

	private static void writeToonCropPair(Path screenshot, Path directory, String name,
		int x, int y, int width, int height) throws IOException {
		try (NativeImage image = NativeImage.read(Files.newInputStream(screenshot))) {
			requireEvidenceViewport(image);
			writeToonCropPair(image, directory, name, x, y, width, height);
		}
	}

	private static void writeToonCropPair(NativeImage source, Path directory, String name,
		int x, int y, int width, int height) throws IOException {
		require(x >= 0 && y >= 0 && x + width <= source.getWidth() && y + height <= source.getHeight(),
			"ToonShader evidence crop must stay inside the viewport");
		Files.createDirectories(directory);
		Path nativePath = directory.resolve(name + "-native.png");
		Path nearestPath = directory.resolve(name + "-4x.png");
		try (NativeImage crop = new NativeImage(width, height, false);
			 NativeImage nearest = new NativeImage(width * 4, height * 4, false)) {
			source.copyRect(crop, x, y, 0, 0, width, height, false, false);
			for (int pixelY = 0; pixelY < height; pixelY++) {
				for (int pixelX = 0; pixelX < width; pixelX++) {
					nearest.fillRect(pixelX * 4, pixelY * 4, 4, 4, crop.getPixel(pixelX, pixelY));
				}
			}
			crop.writeToFile(nativePath);
			nearest.writeToFile(nearestPath);
		}
		assertNonEmpty(nativePath, "native ToonShader evidence crop must not be empty");
		assertNonEmpty(nearestPath, "4x ToonShader evidence crop must not be empty");
	}

	private static Path toonEvidenceDirectory(Path gameDirectory, int row) {
		return gameDirectory.resolve("screenshots/toon-evidence")
			.resolve(String.format(Locale.ROOT, "row-%03d", row));
	}

	private static void requireEvidenceViewport(NativeImage image) {
		require(image.getWidth() == 1280 && image.getHeight() == 720,
			"ToonShader evidence requires a 1280x720 viewport (actual="
				+ image.getWidth() + "x" + image.getHeight() + ")");
	}

	private static void cleanupMatrixWorkingPacks(ClientGameTestContext context, List<Path> workingCopies) {
		try {
			context.runOnClient(client -> {
				Iris.getIrisConfig().setShadersEnabled(false);
				Iris.getIrisConfig().save();
				Iris.reload();
			});
		} catch (Exception | AssertionError exception) {
			System.err.println("[Celerant shader matrix] could not close the final working pack: " + exception);
		}
		for (Path workingCopy : workingCopies) {
			try {
				Files.deleteIfExists(workingCopy);
			} catch (IOException exception) {
				System.err.println("[Celerant shader matrix] deferred working ZIP cleanup: " + workingCopy);
			}
		}
	}

	private static MatrixState captureMatrixState(ClientGameTestContext context, String packName,
		boolean shadersEnabled, boolean toonDisabled, String screenshotName, boolean measureFrames) {
		long[] reloadNanos = {-1L};
		String error = "";
		try {
			context.runOnClient(client -> {
				client.setScreenAndShow(null);
				if (toonDisabled) {
					System.setProperty(DISABLE_TOON_SHADER_PROPERTY, "true");
				} else {
					System.clearProperty(DISABLE_TOON_SHADER_PROPERTY);
				}
				if (packName != null) {
					Iris.getIrisConfig().setShaderPackName(packName);
				}
				Iris.getIrisConfig().setShadersEnabled(shadersEnabled);
				Iris.getIrisConfig().setDebugEnabled(true);
				Iris.getIrisConfig().save();
				long started = System.nanoTime();
				try {
					Iris.reload();
				} finally {
					reloadNanos[0] = System.nanoTime() - started;
				}
			});
			context.waitFor(client -> Iris.getStoredError().isPresent() || !shadersEnabled
				|| packName.equals(Iris.getCurrentPackName())
					&& Iris.getPipelineManager().getPipeline().isPresent(), 1200);
		} catch (Exception | AssertionError exception) {
			error = addProblem(error, "reload", exception);
		}

		FrameStats frames = FrameStats.EMPTY;
		if (measureFrames) {
			try {
				frames = captureFrameStats(context);
			} catch (Exception | AssertionError exception) {
				error = addProblem(error, "frame samples", exception);
			}
		} else {
			try {
				captureFrameStats(context);
			} catch (Exception | AssertionError exception) {
				error = addProblem(error, "restore warmup", exception);
			}
		}

		Path image = null;
		try {
			image = context.takeScreenshot(screenshotName);
		} catch (Exception | AssertionError exception) {
			error = addProblem(error, "capture", exception);
		}
		String irisError = "";
		boolean packInUse = !shadersEnabled;
		try {
			irisError = context.computeOnClient(client -> Iris.getStoredError()
				.map(Throwable::toString).orElse(""));
			packInUse = context.computeOnClient(client -> Iris.isPackInUseQuick()
				&& Iris.getPipelineManager().getPipeline().isPresent()
				&& Iris.getStoredError().isEmpty());
			if (shadersEnabled && !packInUse) {
				error = addProblem(error, "Iris shader pack inactive");
			}
		} catch (Exception | AssertionError exception) {
			error = addProblem(error, "read Iris error", exception);
		}
		return new MatrixState(reloadNanos[0] < 0 ? Double.NaN : reloadNanos[0] / 1_000_000.0,
			frames, image, irisError, packInUse, error);
	}

	private static FrameStats captureFrameStats(ClientGameTestContext context) {
		MATRIX_FRAME_TIMES.start();
		try {
			context.waitFor(client -> MATRIX_FRAME_TIMES.isComplete(), 1200);
			return MATRIX_FRAME_TIMES.snapshot();
		} finally {
			MATRIX_FRAME_TIMES.stop();
		}
	}

	private static Map<String, Long> snapshotDebugDump(Path debugDirectory) throws IOException {
		if (!Files.isDirectory(debugDirectory)) {
			return Map.of();
		}
		Map<String, Long> snapshot = new HashMap<>();
		try (var files = Files.list(debugDirectory)) {
			for (Path path : files.filter(Files::isRegularFile).toList()) {
				snapshot.put(path.getFileName().toString(), Files.getLastModifiedTime(path).toMillis());
			}
		}
		return snapshot;
	}

	private static ShaderDumpStats countPatchedEntityShaders(Path debugDirectory,
		Map<String, Long> beforeReload) throws IOException {
		if (!Files.isDirectory(debugDirectory)) {
			return new ShaderDumpStats(0, 0);
		}
		int patched = 0;
		int total = 0;
		try (var files = Files.list(debugDirectory)) {
			for (Path fragment : files.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().matches("\\d+_entities_.+\\.fsh"))
				.sorted().toList()) {
				String filename = fragment.getFileName().toString();
				Long previousModified = beforeReload.get(filename);
				if (previousModified != null
					&& previousModified.longValue() == Files.getLastModifiedTime(fragment).toMillis()) {
					continue;
				}
				total++;
				Path vertex = fragment.resolveSibling(filename.substring(0, filename.length() - 4) + ".vsh");
				if (Files.isRegularFile(vertex)
					&& Files.readString(vertex).contains("celerant_vrm_toon_marker")
					&& Files.readString(fragment).contains("celerant_vrm_toon_marker")) {
					patched++;
				}
			}
		}
		return new ShaderDumpStats(patched, total);
	}

	private static ToonSignal measureToonSignal(Path onPath, Path offPath, Path restoredPath) throws IOException {
		if (onPath == null || offPath == null || restoredPath == null) {
			throw new IOException("all three matrix screenshots are required");
		}
		try (NativeImage on = NativeImage.read(Files.newInputStream(onPath));
			 NativeImage off = NativeImage.read(Files.newInputStream(offPath));
			 NativeImage restored = NativeImage.read(Files.newInputStream(restoredPath))) {
			if (on.getWidth() != off.getWidth() || on.getHeight() != off.getHeight()
				|| on.getWidth() != restored.getWidth() || on.getHeight() != restored.getHeight()) {
				throw new IOException("matrix screenshots use different viewports");
			}
			int width = on.getWidth();
			int height = on.getHeight();
			int[] onPixels = on.getPixels();
			int[] offPixels = off.getPixels();
			int[] restoredPixels = restored.getPixels();
			int signalPixels = 0;
			int centeredSignals = 0;
			int restoredComparedPixels = 0;
			int restoredStablePixels = 0;
			int restoredMaxDelta = 0;
			int[] bounds = {width, height, -1, -1};
			for (int index = 0; index < onPixels.length; index++) {
				int idleDelta = rgbDistance(onPixels[index], restoredPixels[index]);
				int toonDelta = Math.min(rgbDistance(onPixels[index], offPixels[index]),
					rgbDistance(restoredPixels[index], offPixels[index]));
				if (toonDelta >= 36) {
					restoredComparedPixels++;
					restoredMaxDelta = Math.max(restoredMaxDelta, idleDelta);
					if (idleDelta <= 18) {
						restoredStablePixels++;
					}
				}
				if (idleDelta > 18 || toonDelta < Math.max(36, idleDelta * 3)) {
					continue;
				}
				int x = index % width;
				int y = index / width;
				signalPixels++;
				if (x < width * 15 / 100 || x > width * 85 / 100 || y < height * 30 / 100) {
					continue;
				}
				centeredSignals++;
				bounds[0] = Math.min(bounds[0], x);
				bounds[1] = Math.min(bounds[1], y);
				bounds[2] = Math.max(bounds[2], x);
				bounds[3] = Math.max(bounds[3], y);
			}
			boolean detected = signalPixels >= Math.max(500, width * height / 1000)
				&& centeredSignals * 100 >= signalPixels * 95
				&& bounds[2] - bounds[0] >= width * 5 / 100
				&& bounds[3] - bounds[1] >= height * 15 / 100;
			return new ToonSignal(detected, signalPixels, centeredSignals, Arrays.toString(bounds),
				restoredComparedPixels, restoredStablePixels, restoredMaxDelta);
		}
	}

	private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (var input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) >= 0;) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void writeMatrixReportStart(Path report, int packCount, MatrixState baseline) {
		String baselineLine = String.join("\t", "# baseline", "shaders=off",
			"packs=" + packCount,
			"reload_ms=" + metric(baseline.reloadMs()),
			"samples=" + baseline.frames().samples(),
			"median_ms=" + metric(baseline.frames().medianMs()),
			"p95_ms=" + metric(baseline.frames().p95Ms()),
			"p99_ms=" + metric(baseline.frames().p99Ms()),
			"image=" + tsvPath(baseline.image()),
			"iris_error=" + tsv(baseline.irisError()),
			"error=" + tsv(baseline.error())) + System.lineSeparator();
		try {
			Files.writeString(report, baselineLine + MatrixRow.HEADER + System.lineSeparator(),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException exception) {
			throw new AssertionError("could not create shader matrix TSV", exception);
		}
	}

	private static void appendMatrixRow(Path report, MatrixRow row) {
		try {
			Files.writeString(report, row.toTsv() + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			throw new AssertionError("could not append shader matrix TSV", exception);
		}
	}

	private static String addProblem(String existing, String problem) {
		return existing.isEmpty() ? problem : existing + " | " + problem;
	}

	private static String addProblem(String existing, String operation, Throwable exception) {
		return addProblem(existing, operation + ": " + exception);
	}

	private static String metric(double value) {
		return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value);
	}

	private static String tsvPath(Path path) {
		return path == null ? "" : tsv(path.toString());
	}

	private static String tsv(String value) {
		return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
	}

	private static void enableShaderPack(ClientGameTestContext context, Path packRoot) {
		try {
			context.runOnClient(client -> {
				require(Iris.isValidShaderpack(packRoot), "test ShaderPack must be discoverable by Iris");
				System.clearProperty(DISABLE_TOON_SHADER_PROPERTY);
				Iris.getIrisConfig().setShaderPackName(PACK_NAME);
				Iris.getIrisConfig().setShadersEnabled(true);
				Iris.getIrisConfig().setDebugEnabled(true);
				Iris.getIrisConfig().save();
				Iris.reload();
			});
		} catch (IOException exception) {
			throw new AssertionError("could not enable the test ShaderPack", exception);
		}
		context.waitFor(client -> PACK_NAME.equals(Iris.getCurrentPackName())
			&& Iris.getStoredError().isEmpty(), 1200);
	}

	private static void verifyRuntimeToonShader(ClientGameTestContext context) {
		Path[] screenshots = captureToonComparison(context, "celerant-vrm-toon");
		assertToonMaterialChanged(screenshots[0], screenshots[1], screenshots[2]);
	}

	private static Path[] captureToonComparison(ClientGameTestContext context, String prefix) {
		boolean[] hudWasHidden = new boolean[1];
		context.runOnClient(client -> {
			hudWasHidden[0] = client.gui.hud.isHidden();
			if (!hudWasHidden[0]) {
				client.gui.hud.toggle();
			}
		});
		try {
			context.waitTicks(10);
			Path toonOn = context.takeScreenshot(prefix + "-on");
			Path toonOff;
			try {
				setToonShaderDisabled(context, true);
				context.waitTicks(10);
				toonOff = context.takeScreenshot(prefix + "-off");
			} finally {
				setToonShaderDisabled(context, false);
				context.waitTicks(10);
			}
			return new Path[] {toonOn, toonOff, context.takeScreenshot(prefix + "-restored")};
		} finally {
			context.runOnClient(client -> {
				if (client.gui.hud.isHidden() != hudWasHidden[0]) {
					client.gui.hud.toggle();
				}
			});
		}
	}

	private static void setToonShaderDisabled(ClientGameTestContext context, boolean disabled) {
		context.runOnClient(client -> {
			if (disabled) {
				System.setProperty(DISABLE_TOON_SHADER_PROPERTY, "true");
			} else {
				System.clearProperty(DISABLE_TOON_SHADER_PROPERTY);
			}
		});
	}

	private static void sendCommand(ClientGameTestContext context, String command, String expectedMessage) {
		context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
		TestInput input = context.getInput();
		input.pressKey(options -> options.keyCommand);
		context.waitForScreen(ChatScreen.class);
		input.typeChars(command);
		input.pressKey(GLFW.GLFW_KEY_ENTER);
		context.waitFor(client -> !(client.gui.screen() instanceof ChatScreen), 100);
		context.waitFor(client -> chatContains(client, expectedMessage), 200);
	}

	private static void teleport(ClientGameTestContext context, TestSingleplayerContext world,
		TestServerConnection connection, double x, double y, double z) {
		world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", x, y, z));
		connection.waitForClientboundPackets();
		context.waitFor(client -> client.player != null
			&& Math.abs(client.player.getX() - x) < 0.01
			&& Math.abs(client.player.getY() - y) < 0.01
			&& Math.abs(client.player.getZ() - z) < 0.01, 200);
	}

	private static void assertInfoContains(ClientGameTestContext context, String expected) {
		String info = context.computeOnClient(client -> VrmRuntime.getInstance().info());
		require(info.contains(expected), "expected runtime info to contain '" + expected + "', got: " + info);
	}

	private static boolean chatContains(Minecraft client, String expected) {
		try {
			Field messagesField = ChatComponent.class.getDeclaredField("allMessages");
			messagesField.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<GuiMessage> messages = (List<GuiMessage>) messagesField.get(client.gui.hud.getChat());
			return messages.stream().anyMatch(message -> message.content().getString().contains(expected));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect client chat", exception);
		}
	}

	private static void verifyRenderedScreenshots(Path basePath, Path morphedPath) {
		try (NativeImage base = NativeImage.read(Files.newInputStream(basePath));
			 NativeImage morphed = NativeImage.read(Files.newInputStream(morphedPath))) {
			require(base.getWidth() == morphed.getWidth() && base.getHeight() == morphed.getHeight(),
				"screenshots must use the same viewport");
			int[] basePixels = base.getPixels();
			int[] morphedPixels = morphed.getPixels();
			long magentaPixels = Arrays.stream(basePixels).filter(CelerantClientGameTest::isMagenta).count();
			int changedPixels = 0;
			for (int index = 0; index < basePixels.length; index++) {
				if (basePixels[index] != morphedPixels[index]) {
					changedPixels++;
				}
			}
			require(magentaPixels >= 50, "rendered VRM must be visible in the screenshot");
			require(changedPixels >= 20, "morph expression must visibly change the rendered VRM");
		} catch (IOException exception) {
			throw new AssertionError("could not inspect client screenshots", exception);
		}
	}

	private static void assertToonMaterialChanged(Path toonOnPath, Path toonOffPath, Path toonRestoredPath) {
		try (NativeImage toonOn = NativeImage.read(Files.newInputStream(toonOnPath));
			 NativeImage toonOff = NativeImage.read(Files.newInputStream(toonOffPath));
			 NativeImage toonRestored = NativeImage.read(Files.newInputStream(toonRestoredPath))) {
			require(toonOn.getWidth() == toonOff.getWidth() && toonOn.getHeight() == toonOff.getHeight()
					&& toonOn.getWidth() == toonRestored.getWidth()
					&& toonOn.getHeight() == toonRestored.getHeight(),
				"toon A/B screenshots must use the same viewport");
			int width = toonOn.getWidth();
			int height = toonOn.getHeight();
			int[] onPixels = toonOn.getPixels();
			int[] offPixels = toonOff.getPixels();
			int[] restoredPixels = toonRestored.getPixels();
			int[] offBounds = {width, height, -1, -1};
			int[] signalBounds = {width, height, -1, -1};
			int offCount = 0;
			int restoredStablePixels = 0;
			int changedModelPixels = 0;
			int signalPixels = 0;
			for (int index = 0; index < onPixels.length; index++) {
				int x = index % width;
				int y = index / width;
				boolean offMagenta = isMagenta(offPixels[index]);
				if (offMagenta) {
					offCount++;
					offBounds[0] = Math.min(offBounds[0], x);
					offBounds[1] = Math.min(offBounds[1], y);
					offBounds[2] = Math.max(offBounds[2], x);
					offBounds[3] = Math.max(offBounds[3], y);
				}
				int idleDelta = rgbDistance(onPixels[index], restoredPixels[index]);
				int toonDelta = Math.min(rgbDistance(onPixels[index], offPixels[index]),
					rgbDistance(restoredPixels[index], offPixels[index]));
				boolean toonSignal = idleDelta <= 18 && toonDelta >= Math.max(36, idleDelta * 3);
				if (offMagenta && idleDelta <= 18) {
					restoredStablePixels++;
					if (toonSignal) {
						changedModelPixels++;
					}
				}
				if (toonSignal) {
					signalPixels++;
					signalBounds[0] = Math.min(signalBounds[0], x);
					signalBounds[1] = Math.min(signalBounds[1], y);
					signalBounds[2] = Math.max(signalBounds[2], x);
					signalBounds[3] = Math.max(signalBounds[3], y);
				}
			}
			require(offCount >= 50, "toon OFF screenshot must contain the VRM");
			require(restoredStablePixels * 100 >= offCount * 80,
				"restoring ToonShader must reproduce the same VRM pixels (stable=" + restoredStablePixels + ")");
			require(changedModelPixels >= 20 && changedModelPixels * 100 >= restoredStablePixels * 30,
				"disabling ToonShader must change VRM material pixels (changed=" + changedModelPixels
					+ ", stable=" + restoredStablePixels + ")");
			require(signalPixels >= changedModelPixels, "ToonShader signal must include the changed VRM material");
			require(signalBounds[0] >= offBounds[0] - 16 && signalBounds[1] >= offBounds[1] - 16
				&& signalBounds[2] <= offBounds[2] + 16 && signalBounds[3] <= offBounds[3] + 16,
				"ToonShader changes must stay on the VRM and its outline (signal=" + Arrays.toString(signalBounds)
					+ ", off=" + Arrays.toString(offBounds) + ")");
			System.out.println("[Celerant toon test] stable VRM pixels=" + restoredStablePixels
				+ ", changed material pixels=" + changedModelPixels + ", bounds=" + Arrays.toString(offBounds));
		} catch (IOException exception) {
			throw new AssertionError("could not inspect toon A/B screenshots", exception);
		}
	}

	private static void assertLocalToonMaterialChanged(Path toonOnPath, Path toonOffPath, Path toonRestoredPath) {
		try (NativeImage toonOn = NativeImage.read(Files.newInputStream(toonOnPath));
			 NativeImage toonOff = NativeImage.read(Files.newInputStream(toonOffPath));
			 NativeImage toonRestored = NativeImage.read(Files.newInputStream(toonRestoredPath))) {
			require(toonOn.getWidth() == toonOff.getWidth() && toonOn.getHeight() == toonOff.getHeight()
					&& toonOn.getWidth() == toonRestored.getWidth()
					&& toonOn.getHeight() == toonRestored.getHeight(),
				"local toon A/B screenshots must use the same viewport");
			int width = toonOn.getWidth();
			int height = toonOn.getHeight();
			int[] onPixels = toonOn.getPixels();
			int[] offPixels = toonOff.getPixels();
			int[] restoredPixels = toonRestored.getPixels();

			int signalPixels = 0;
			int centeredSignals = 0;
			int[] bounds = {width, height, -1, -1};
			for (int index = 0; index < onPixels.length; index++) {
				int idleDelta = rgbDistance(onPixels[index], restoredPixels[index]);
				int toonDelta = Math.min(rgbDistance(onPixels[index], offPixels[index]),
					rgbDistance(restoredPixels[index], offPixels[index]));
				if (idleDelta > 18 || toonDelta < Math.max(36, idleDelta * 3)) {
					continue;
				}
				int x = index % width;
				int y = index / width;
				signalPixels++;
				if (x < width * 15 / 100 || x > width * 85 / 100 || y < height * 30 / 100) {
					continue;
				}
				centeredSignals++;
				bounds[0] = Math.min(bounds[0], x);
				bounds[1] = Math.min(bounds[1], y);
				bounds[2] = Math.max(bounds[2], x);
				bounds[3] = Math.max(bounds[3], y);
			}
			require(signalPixels >= Math.max(500, width * height / 1000),
				"the local VRM must visibly change when ToonShader is disabled");
			require(centeredSignals * 100 >= signalPixels * 95,
				"local toon A/B changes must stay on the centered VRM (signals=" + signalPixels
					+ ", centered=" + centeredSignals + ")");
			require(bounds[2] - bounds[0] >= width * 5 / 100 && bounds[3] - bounds[1] >= height * 15 / 100,
				"local toon A/B must cover a readable VRM area (bounds=" + Arrays.toString(bounds) + ")");
			System.out.println("[Celerant toon test] local VRM shader signals=" + signalPixels
				+ ", bounds=" + Arrays.toString(bounds));
		} catch (IOException exception) {
			throw new AssertionError("could not inspect local toon A/B screenshots", exception);
		}
	}

	private static void assertMagenta(Path screenshot, String message) {
		try (NativeImage image = NativeImage.read(Files.newInputStream(screenshot))) {
			long magentaPixels = Arrays.stream(image.getPixels())
				.filter(CelerantClientGameTest::isMagenta)
				.count();
			require(magentaPixels >= 50, message + " (pixels=" + magentaPixels + ")");
		} catch (IOException exception) {
			throw new AssertionError("could not inspect avatar screenshot", exception);
		}
	}

	private static void assertNonEmpty(Path screenshot, String message) {
		try {
			require(Files.size(screenshot) > 0, message);
		} catch (IOException exception) {
			throw new AssertionError("could not inspect screenshot", exception);
		}
	}

	private static void assertAutoHeadFiltered(Path thirdPersonPath, Path firstPersonPath) {
		try (NativeImage thirdPerson = NativeImage.read(Files.newInputStream(thirdPersonPath));
			 NativeImage firstPerson = NativeImage.read(Files.newInputStream(firstPersonPath))) {
			long thirdPersonBody = Arrays.stream(thirdPerson.getPixels())
				.filter(CelerantClientGameTest::isBodyMagenta).count();
			long thirdPersonHead = Arrays.stream(thirdPerson.getPixels())
				.filter(CelerantClientGameTest::isHeadMagenta).count();
			long firstPersonBody = Arrays.stream(firstPerson.getPixels())
				.filter(CelerantClientGameTest::isBodyMagenta).count();
			long firstPersonHead = Arrays.stream(firstPerson.getPixels())
				.filter(CelerantClientGameTest::isHeadMagenta).count();
			require(thirdPersonBody >= 50, "third-person VRM body must be visible");
			require(thirdPersonHead >= 50, "third-person VRM head must be visible");
			require(firstPersonBody >= 50, "first-person VRM body must remain visible");
			require(firstPersonHead == 0,
				"first-person Auto filtering must remove head-weighted geometry (pixels=" + firstPersonHead + ")");
		} catch (IOException exception) {
			throw new AssertionError("could not inspect first-person Auto filtering screenshots", exception);
		}
	}

	private static void assertMagentaMasksDiffer(Path firstPath, Path secondPath, String message) {
		try (NativeImage first = NativeImage.read(Files.newInputStream(firstPath));
			 NativeImage second = NativeImage.read(Files.newInputStream(secondPath))) {
			require(first.getWidth() == second.getWidth() && first.getHeight() == second.getHeight(),
				"screenshots must use the same viewport");
			int width = first.getWidth();
			int height = first.getHeight();
			int[] firstPixels = first.getPixels();
			int[] secondPixels = second.getPixels();
			int firstMinX = width;
			int firstMinY = height;
			int firstMaxX = -1;
			int firstMaxY = -1;
			int secondMinX = width;
			int secondMinY = height;
			int secondMaxX = -1;
			int secondMaxY = -1;
			for (int index = 0; index < firstPixels.length; index++) {
				int x = index % width;
				int y = index / width;
				if (isMagenta(firstPixels[index])) {
					firstMinX = Math.min(firstMinX, x);
					firstMinY = Math.min(firstMinY, y);
					firstMaxX = Math.max(firstMaxX, x);
					firstMaxY = Math.max(firstMaxY, y);
				}
				if (isMagenta(secondPixels[index])) {
					secondMinX = Math.min(secondMinX, x);
					secondMinY = Math.min(secondMinY, y);
					secondMaxX = Math.max(secondMaxX, x);
					secondMaxY = Math.max(secondMaxY, y);
				}
			}
			require(firstMaxX >= firstMinX && secondMaxX >= secondMinX,
				"both screenshots must contain the magenta VRM");
			int maskWidth = Math.max(firstMaxX - firstMinX, secondMaxX - secondMinX) + 1;
			int maskHeight = Math.max(firstMaxY - firstMinY, secondMaxY - secondMinY) + 1;
			int changed = 0;
			for (int y = 0; y < maskHeight; y++) {
				for (int x = 0; x < maskWidth; x++) {
					boolean firstMagenta = x <= firstMaxX - firstMinX && y <= firstMaxY - firstMinY
						&& isMagenta(firstPixels[(firstMinY + y) * width + firstMinX + x]);
					boolean secondMagenta = x <= secondMaxX - secondMinX && y <= secondMaxY - secondMinY
						&& isMagenta(secondPixels[(secondMinY + y) * width + secondMinX + x]);
					changed += firstMagenta == secondMagenta ? 0 : 1;
				}
			}
			require(changed >= 100, message + " (mask pixels=" + changed + ")");
		} catch (IOException exception) {
			throw new AssertionError("could not inspect avatar masks", exception);
		}
	}

	private static float quaternionDistance(float[] first, float[] second) {
		require(first != null && second != null && first.length == 4 && second.length == 4,
			"debug bone rotations must be quaternions");
		float direct = 0.0F;
		float negated = 0.0F;
		for (int index = 0; index < 4; index++) {
			direct += Math.abs(first[index] - second[index]);
			negated += Math.abs(first[index] + second[index]);
		}
		return Math.min(direct, negated);
	}

	private static boolean isMagenta(int pixel) {
		int blue = pixel & 0xFF;
		int green = pixel >>> 8 & 0xFF;
		int red = pixel >>> 16 & 0xFF;
		return red >= 55 && green <= 60 && blue >= 15;
	}

	private static int rgbDistance(int first, int second) {
		return Math.abs((first >>> 16 & 0xFF) - (second >>> 16 & 0xFF))
			+ Math.abs((first >>> 8 & 0xFF) - (second >>> 8 & 0xFF))
			+ Math.abs((first & 0xFF) - (second & 0xFF));
	}

	private static boolean isBodyMagenta(int pixel) {
		int blue = pixel & 0xFF;
		int red = pixel >>> 16 & 0xFF;
		return isMagenta(pixel) && red - blue >= 30;
	}

	private static boolean isHeadMagenta(int pixel) {
		int blue = pixel & 0xFF;
		int green = pixel >>> 8 & 0xFF;
		int red = pixel >>> 16 & 0xFF;
		return isMagenta(pixel) && green < 30 && Math.abs(red - blue) <= 12;
	}

	private static void writeFixtures(Path packRoot, Path modelPath) {
		try {
			Path shaders = packRoot.resolve("shaders");
			Files.createDirectories(shaders);
			Files.writeString(shaders.resolve("gbuffers_basic.vsh"), VERTEX_SHADER);
			Files.writeString(shaders.resolve("gbuffers_basic.fsh"), FRAGMENT_SHADER);
			Files.writeString(shaders.resolve("gbuffers_entities.vsh"), VERTEX_SHADER);
			Files.writeString(shaders.resolve("gbuffers_entities.fsh"), FRAGMENT_SHADER);
			Files.createDirectories(modelPath.getParent());
			Files.write(modelPath, createMinimalVrm());
			BufferedImage lightMap = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			lightMap.setRGB(0, 0, 0xFF003300);
			require(ImageIO.write(lightMap, "png", modelPath.resolveSibling("minimal-lightmap.png").toFile()),
				"PNG writer must be available for the ToonShader fixture");
			Files.writeString(Path.of(modelPath + ".toon.json"), """
				{
				  "version": 1,
				  "materials": [
				    {"index": 0, "lightMap": "minimal-lightmap.png", "outline": true,
				     "outlineMode": "screen", "outlineWidth": 0.4, "outlineScaleFar": 1.0},
				    {"index": 1, "lightMap": "minimal-lightmap.png", "outline": true,
				     "outlineMode": "screen", "outlineWidth": 0.4, "outlineScaleFar": 1.0}
				  ]
				}
				""");
		} catch (IOException exception) {
			throw new AssertionError("could not prepare client game test fixtures", exception);
		}
	}

	private static boolean prepareLocalVisualModel(Path gameDirectory) {
		String configured = System.getenv("CELERANT_VISUAL_VRM");
		if (configured == null || configured.isBlank()) {
			return false;
		}

		Path source = Path.of(configured).toAbsolutePath().normalize();
		require(Files.isRegularFile(source), "CELERANT_VISUAL_VRM must point to a regular .vrm file");
		require(source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vrm"),
			"CELERANT_VISUAL_VRM must point to a .vrm file");
		try {
			Path target = gameDirectory.resolve("celerant/models").resolve(LOCAL_VISUAL_VRM);
			Files.createDirectories(target.getParent());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			target.toFile().deleteOnExit();
			Path sidecar = Path.of(source.toString() + ".toon.json");
			if (Files.isRegularFile(sidecar)) {
				Path targetSidecar = Path.of(target.toString() + ".toon.json");
				Files.copy(sidecar, targetSidecar, StandardCopyOption.REPLACE_EXISTING);
				targetSidecar.toFile().deleteOnExit();
				try (var files = Files.list(source.getParent())) {
					for (Path texture : files.filter(Files::isRegularFile)
						.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
						.toList()) {
						Path targetTexture = target.getParent().resolve(texture.getFileName());
						Files.copy(texture, targetTexture, StandardCopyOption.REPLACE_EXISTING);
						targetTexture.toFile().deleteOnExit();
					}
				}
			}
			return true;
		} catch (IOException exception) {
			throw new AssertionError("could not copy the local visual VRM", exception);
		}
	}

	private static void verifyPackUnchanged(Path packRoot) {
		try {
			Path shaders = packRoot.resolve("shaders");
			require(VERTEX_SHADER.equals(Files.readString(shaders.resolve("gbuffers_basic.vsh"))),
				"Celerant must not modify ShaderPack vertex sources");
			require(FRAGMENT_SHADER.equals(Files.readString(shaders.resolve("gbuffers_basic.fsh"))),
				"Celerant must not modify ShaderPack fragment sources");
			require(VERTEX_SHADER.equals(Files.readString(shaders.resolve("gbuffers_entities.vsh"))),
				"Celerant must not modify entity vertex sources");
			require(FRAGMENT_SHADER.equals(Files.readString(shaders.resolve("gbuffers_entities.fsh"))),
				"Celerant must not modify entity fragment sources");
		} catch (IOException exception) {
			throw new AssertionError("could not verify ShaderPack sources", exception);
		}
	}

	private static byte[] createMinimalVrm() {
		ByteBuffer binary = ByteBuffer.allocate(660).order(ByteOrder.LITTLE_ENDIAN);
		putFloats(binary,
			-0.75F, 0.0F, 0.35F, 0.75F, 0.0F, 0.35F, 0.0F, 1.5F, 0.35F,
			-0.4F, 1.2F, 0.34F, 0.4F, 1.2F, 0.34F, 0.4F, 2.1F, 0.34F, -0.4F, 2.1F, 0.34F);
		for (int vertex = 0; vertex < 7; vertex++) {
			putFloats(binary, 0.0F, 0.0F, 1.0F);
		}
		putFloats(binary,
			0.0F, 0.0F, 1.0F, 0.0F, 0.5F, 1.0F,
			0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F);
		putFloats(binary,
			0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.75F, 0.0F,
			0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		binary.putShort((short) 0).putShort((short) 1).putShort((short) 2);
		binary.putShort((short) 3).putShort((short) 4).putShort((short) 5);
		binary.putShort((short) 3).putShort((short) 5).putShort((short) 6);
		while ((binary.position() & 3) != 0) {
			binary.put((byte) 0);
		}
		putFloats(binary,
			1.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 1.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 1.0F, 0.0F,
			0.0F, -0.9F, 0.0F, 1.0F,
			1.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 1.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 1.0F, 0.0F,
			-0.2F, -0.8F, 0.0F, 1.0F,
			1.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 1.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 1.0F, 0.0F,
			0.0F, -1.7F, 0.0F, 1.0F);
		for (int vertex = 0; vertex < 7; vertex++) {
			int first = vertex == 0 ? 1 : 0;
			int second = vertex >= 3 ? 2 : 0;
			binary.put((byte) first).put((byte) second).put((byte) 0).put((byte) 0);
		}
		for (int vertex = 0; vertex < 7; vertex++) {
			if (vertex >= 3) {
				putFloats(binary, 0.99F, 0.01F, 0.0F, 0.0F);
			} else {
				putFloats(binary, 1.0F, 0.0F, 0.0F, 0.0F);
			}
		}
		int dataLength = binary.position();
		require(dataLength == 660, "minimal VRM skin binary layout");
		byte[] binaryChunk = Arrays.copyOf(binary.array(), binary.position());

		String json = """
			{
			  "asset":{"version":"2.0","generator":"Celerant Client Game Test"},
			  "extensionsUsed":["VRMC_vrm","VRMC_materials_mtoon"],
			  "extensions":{"VRMC_vrm":{
			    "specVersion":"1.0",
			    "meta":{"name":"Celerant Test Avatar","version":"1.0","authors":["Celerant"],"licenseUrl":"https://vrm.dev/licenses/1.0/"},
			    "humanoid":{"humanBones":{
			      "hips":{"node":1},"spine":{"node":2},"head":{"node":3},
			      "leftUpperLeg":{"node":4},"leftLowerLeg":{"node":5},"leftFoot":{"node":6},
			      "rightUpperLeg":{"node":7},"rightLowerLeg":{"node":8},"rightFoot":{"node":9},
			      "leftUpperArm":{"node":10},"leftLowerArm":{"node":11},"leftHand":{"node":12},
			      "rightUpperArm":{"node":13},"rightLowerArm":{"node":14},"rightHand":{"node":15}
			    }},
			    "firstPerson":{"meshAnnotations":[{"node":0,"type":"auto"}]},
			    "expressions":{"custom":{
			      "smile":{"morphTargetBinds":[{"node":0,"index":0,"weight":1.0}]},
			      "blink":{"isBinary":true,"morphTargetBinds":[{"node":0,"index":0,"weight":1.0}]}
			    }}
			  }},
			  "scene":0,
			  "scenes":[{"nodes":[0,1]}],
			  "nodes":[
			    {"mesh":0,"skin":0,"name":"Avatar"},
			    {"name":"hips","children":[2,4,7],"translation":[0.0,0.9,0.0]},
			    {"name":"spine","children":[3,10,13],"translation":[0.0,0.3,0.0]},
			    {"name":"head","translation":[0.0,0.5,0.0]},
			    {"name":"leftUpperLeg","children":[5],"translation":[0.2,-0.1,0.0]},
			    {"name":"leftLowerLeg","children":[6],"translation":[0.0,-0.45,0.0]},
			    {"name":"leftFoot","translation":[0.0,-0.45,0.0]},
			    {"name":"rightUpperLeg","children":[8],"translation":[-0.2,-0.1,0.0]},
			    {"name":"rightLowerLeg","children":[9],"translation":[0.0,-0.45,0.0]},
			    {"name":"rightFoot","translation":[0.0,-0.45,0.0]},
			    {"name":"leftUpperArm","children":[11],"translation":[0.25,0.35,0.0]},
			    {"name":"leftLowerArm","children":[12],"translation":[0.45,0.0,0.0]},
			    {"name":"leftHand","translation":[0.4,0.0,0.0]},
			    {"name":"rightUpperArm","children":[14],"translation":[-0.25,0.35,0.0]},
			    {"name":"rightLowerArm","children":[15],"translation":[-0.45,0.0,0.0]},
			    {"name":"rightHand","translation":[-0.4,0.0,0.0]}
			  ],
			  "skins":[{"joints":[1,4,3],"skeleton":1,"inverseBindMatrices":8}],
			  "meshes":[{"weights":[0.0],"primitives":[
			    {"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2,"JOINTS_0":6,"WEIGHTS_0":7},
			     "targets":[{"POSITION":3}],"indices":4,"material":0},
			    {"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2,"JOINTS_0":6,"WEIGHTS_0":7},
			     "targets":[{"POSITION":3}],"indices":5,"material":1}
			  ]}],
			  "materials":[{"doubleSided":true,"extensions":{"VRMC_materials_mtoon":{
			    "specVersion":"1.0","shadeColorFactor":[0.52,0.02,0.24],"shadingToonyFactor":0.9,
			    "outlineWidthMode":"screenCoordinates","outlineWidthFactor":0.4,"outlineColorFactor":[0.16,0.01,0.08]
			  }},"pbrMetallicRoughness":{
			    "baseColorFactor":[1.0,0.05,0.55,1.0],"metallicFactor":0.0,"roughnessFactor":1.0
			  }},{"doubleSided":true,"extensions":{"VRMC_materials_mtoon":{
			    "specVersion":"1.0","shadeColorFactor":[0.48,0.0,0.48],"shadingToonyFactor":0.9,
			    "outlineWidthMode":"screenCoordinates","outlineWidthFactor":0.4,"outlineColorFactor":[0.12,0.0,0.12]
			  }},"pbrMetallicRoughness":{
			    "baseColorFactor":[1.0,0.0,1.0,1.0],"metallicFactor":0.0,"roughnessFactor":1.0
			  }}],
			  "buffers":[{"byteLength":%d}],
			  "bufferViews":[
			    {"buffer":0,"byteOffset":0,"byteLength":84,"target":34962},
			    {"buffer":0,"byteOffset":84,"byteLength":84,"target":34962},
			    {"buffer":0,"byteOffset":168,"byteLength":56,"target":34962},
			    {"buffer":0,"byteOffset":224,"byteLength":84,"target":34962},
			    {"buffer":0,"byteOffset":308,"byteLength":6,"target":34963},
			    {"buffer":0,"byteOffset":314,"byteLength":12,"target":34963},
			    {"buffer":0,"byteOffset":328,"byteLength":192},
			    {"buffer":0,"byteOffset":520,"byteLength":28,"target":34962},
			    {"buffer":0,"byteOffset":548,"byteLength":112,"target":34962}
			  ],
			  "accessors":[
			    {"bufferView":0,"componentType":5126,"count":7,"type":"VEC3","min":[-0.75,0.0,0.34],"max":[0.75,2.1,0.35]},
			    {"bufferView":1,"componentType":5126,"count":7,"type":"VEC3"},
			    {"bufferView":2,"componentType":5126,"count":7,"type":"VEC2"},
			    {"bufferView":3,"componentType":5126,"count":7,"type":"VEC3"},
			    {"bufferView":4,"componentType":5123,"count":3,"type":"SCALAR","min":[0],"max":[2]},
			    {"bufferView":5,"componentType":5123,"count":6,"type":"SCALAR","min":[3],"max":[6]},
			    {"bufferView":7,"componentType":5121,"count":7,"type":"VEC4"},
			    {"bufferView":8,"componentType":5126,"count":7,"type":"VEC4"},
			    {"bufferView":6,"componentType":5126,"count":3,"type":"MAT4"}
			  ]
			}
			""".formatted(dataLength);
		byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
		int jsonLength = (jsonBytes.length + 3) & ~3;
		int totalLength = 12 + 8 + jsonLength + 8 + binaryChunk.length;
		ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
		glb.putInt(0x46546C67).putInt(2).putInt(totalLength);
		glb.putInt(jsonLength).putInt(0x4E4F534A).put(jsonBytes);
		while (glb.position() < 20 + jsonLength) {
			glb.put((byte) 0x20);
		}
		glb.putInt(binaryChunk.length).putInt(0x004E4942).put(binaryChunk);
		return glb.array();
	}

	private record FrameStats(int samples, double medianMs, double p95Ms, double p99Ms) {
		private static final FrameStats EMPTY = new FrameStats(0, Double.NaN, Double.NaN, Double.NaN);
	}

	private static final class FrameTimeRecorder {
		private static final int WARMUP_FRAMES = 5;
		private final long[] samples = new long[12];
		private long previousNanos;
		private int warmupFrames;
		private int sampleCount;
		private boolean active;

		private synchronized void start() {
			previousNanos = 0L;
			warmupFrames = 0;
			sampleCount = 0;
			active = true;
		}

		private synchronized void record(long nowNanos) {
			if (!active) {
				return;
			}
			if (previousNanos != 0L) {
				long frameNanos = nowNanos - previousNanos;
				if (warmupFrames < WARMUP_FRAMES) {
					warmupFrames++;
				} else {
					samples[sampleCount++] = frameNanos;
					if (sampleCount == samples.length) {
						active = false;
					}
				}
			}
			previousNanos = nowNanos;
		}

		private synchronized boolean isComplete() {
			return sampleCount == samples.length;
		}

		private synchronized FrameStats snapshot() {
			if (sampleCount == 0) {
				return FrameStats.EMPTY;
			}
			long[] sorted = Arrays.copyOf(samples, sampleCount);
			Arrays.sort(sorted);
			double median = sampleCount % 2 == 0
				? (sorted[sampleCount / 2 - 1] + sorted[sampleCount / 2]) / 2_000_000.0
				: sorted[sampleCount / 2] / 1_000_000.0;
			double p95 = sorted[(int) Math.ceil(sampleCount * 0.95) - 1] / 1_000_000.0;
			double p99 = sorted[(int) Math.ceil(sampleCount * 0.99) - 1] / 1_000_000.0;
			return new FrameStats(sampleCount, median, p95, p99);
		}

		private synchronized void stop() {
			active = false;
		}
	}

	private record MatrixState(double reloadMs, FrameStats frames, Path image, String irisError,
		boolean packInUse, String error) {
	}

	private record ShaderDumpStats(int patched, int total) {
	}

	private record ToonSignal(boolean detected, int signalPixels, int centeredPixels, String bounds,
		int restoredComparedPixels, int restoredStablePixels, int restoredMaxDelta) {
	}

	private record MatrixRow(
		Path source,
		String sourceHashBefore,
		String sourceHashAfter,
		boolean sourceHashIntact,
		boolean copyHashMatches,
		MatrixState on,
		ShaderDumpStats dump,
		MatrixState off,
		MatrixState restored,
		ToonSignal toon,
		String error
	) {
		private static final String HEADER = String.join("\t",
			"pack", "source_zip", "sha256_before", "sha256_after", "source_hash_intact", "copy_hash_matches",
			"on_reload_ms", "on_frame_samples", "on_median_ms", "on_p95_ms", "on_p99_ms", "on_image",
			"on_iris_error", "on_pack_in_use", "on_error", "patched_entity_programs", "total_entity_programs",
			"off_reload_ms", "off_frame_samples", "off_median_ms", "off_p95_ms", "off_p99_ms", "off_image",
			"off_iris_error", "off_pack_in_use", "off_error", "restored_reload_ms", "restored_image", "restored_iris_error",
			"restored_pack_in_use", "restored_error", "restored_tolerance_rgb_distance", "restored_compared_pixels",
			"restored_stable_pixels", "restored_stable_ratio", "restored_max_rgb_delta", "toon_signal", "toon_signal_pixels",
			"toon_centered_pixels", "toon_bounds", "error");

		private String toTsv() {
			return String.join("\t",
				tsv(source.getFileName().toString()), tsvPath(source), sourceHashBefore, sourceHashAfter,
				Boolean.toString(sourceHashIntact), Boolean.toString(copyHashMatches),
				metric(on.reloadMs()), Integer.toString(on.frames().samples()), metric(on.frames().medianMs()),
				metric(on.frames().p95Ms()), metric(on.frames().p99Ms()), tsvPath(on.image()),
				tsv(on.irisError()), Boolean.toString(on.packInUse()), tsv(on.error()), Integer.toString(dump.patched()), Integer.toString(dump.total()),
				metric(off.reloadMs()), Integer.toString(off.frames().samples()), metric(off.frames().medianMs()),
				metric(off.frames().p95Ms()), metric(off.frames().p99Ms()), tsvPath(off.image()),
				tsv(off.irisError()), Boolean.toString(off.packInUse()), tsv(off.error()), metric(restored.reloadMs()), tsvPath(restored.image()),
				tsv(restored.irisError()), Boolean.toString(restored.packInUse()), tsv(restored.error()), "18",
				Integer.toString(toon.restoredComparedPixels()), Integer.toString(toon.restoredStablePixels()),
				metric(toon.restoredComparedPixels() == 0 ? Double.NaN
					: (double) toon.restoredStablePixels() / toon.restoredComparedPixels()),
				Integer.toString(toon.restoredMaxDelta()), Boolean.toString(toon.detected()),
				Integer.toString(toon.signalPixels()), Integer.toString(toon.centeredPixels()), tsv(toon.bounds()), tsv(error));
		}
	}

	private static void putFloats(ByteBuffer buffer, float... values) {
		for (float value : values) {
			buffer.putFloat(value);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
