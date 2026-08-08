package io.github.westernbear.celerant.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;

import org.lwjgl.glfw.GLFW;

public final class CelerantClientGameTest implements FabricClientGameTest {
	private static final String PACK_NAME = "CelerantTest";
	private static final String LOCAL_VISUAL_VRM = "_local_visual.vrm";
	private static final String DISABLE_TOON_PATCH_PROPERTY = "celerant.testing.disableToonPatch";
	private static final String VERTEX_SHADER = """
		#version 120
		varying vec2 texcoord;
		varying vec2 lmcoord;
		varying vec4 tint;
		varying vec3 normal;
		void main() {
		    gl_Position = ftransform();
		    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
		    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
		    tint = gl_Color;
		    normal = normalize(gl_NormalMatrix * gl_Normal);
		}
		""";
	private static final String FRAGMENT_SHADER = """
		#version 120
		uniform sampler2D texture;
		uniform sampler2D lightmap;
		varying vec2 texcoord;
		varying vec2 lmcoord;
		varying vec4 tint;
		varying vec3 normal;
		void main() {
		    vec4 albedo = texture2D(texture, texcoord) * tint;
		    gl_FragData[0] = albedo * texture2D(lightmap, lmcoord);
		}
		""";
	private static final String MULTI_OUTPUT_FRAGMENT_SHADER = FRAGMENT_SHADER.replace(
		"gl_FragData[0] = albedo * texture2D(lightmap, lmcoord);",
		"gl_FragData[0] = albedo * texture2D(lightmap, lmcoord);\n    gl_FragData[1] = vec4(normal * 0.5 + 0.5, 1.0);");

	@Override
	public void runTest(ClientGameTestContext context) {
		Path gameDirectory = context.computeOnClient(client -> client.gameDirectory.toPath());
		Path packRoot = gameDirectory.resolve("shaderpacks").resolve(PACK_NAME);
		Path modelPath = gameDirectory.resolve("celerant/models/minimal.vrm");

		writeFixtures(packRoot, modelPath);
		boolean localVisualTest = prepareLocalVisualModel(gameDirectory);
		enableShaderPack(context, packRoot);
		verifyIrisMixinPath();

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

		teleport(context, world, connection, 0.0, y, 4.0);
		context.getInput().lookAt(BlockPos.containing(0.0, y + 1.5, 0.0));
		context.waitTicks(20);
		sendCommand(context, "celerant vrm info", "VRM: minimal.vrm");
		verifyRuntimeToonPatch(context, gameDirectory);

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

	private static void enableShaderPack(ClientGameTestContext context, Path packRoot) {
		try {
			context.runOnClient(client -> {
				require(Iris.isValidShaderpack(packRoot), "test ShaderPack must be discoverable by Iris");
				System.clearProperty(DISABLE_TOON_PATCH_PROPERTY);
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

	private static void verifyRuntimeToonPatch(ClientGameTestContext context, Path gameDirectory) {
		Path[] screenshots = captureToonComparison(context, "celerant-vrm-toon");
		verifyPatchedEntityShaders(gameDirectory.resolve("patched_shaders"));
		assertToonMaterialChanged(screenshots[0], screenshots[1], screenshots[2]);
	}

	private static Path[] captureToonComparison(ClientGameTestContext context, String prefix) {
		context.waitTicks(10);
		Path toonOn = context.takeScreenshot(prefix + "-on");
		Path toonOff;
		try {
			reloadToonPatch(context, true);
			context.waitTicks(10);
			toonOff = context.takeScreenshot(prefix + "-off");
		} finally {
			reloadToonPatch(context, false);
			context.waitTicks(10);
		}
		return new Path[] {toonOn, toonOff, context.takeScreenshot(prefix + "-restored")};
	}

	private static void reloadToonPatch(ClientGameTestContext context, boolean disabled) {
		try {
			context.runOnClient(client -> {
				if (disabled) {
					System.setProperty(DISABLE_TOON_PATCH_PROPERTY, "true");
				} else {
					System.clearProperty(DISABLE_TOON_PATCH_PROPERTY);
				}
				Iris.reload();
			});
		} catch (IOException exception) {
			throw new AssertionError("could not reload Iris with the toon patch "
				+ (disabled ? "disabled" : "enabled"), exception);
		}
		context.waitFor(client -> PACK_NAME.equals(Iris.getCurrentPackName())
			&& Iris.getPipelineManager().getPipeline().isPresent()
			&& Iris.getStoredError().isEmpty(), 1200);
	}

	private static void verifyPatchedEntityShaders(Path debugDirectory) {
		try (var files = Files.list(debugDirectory)) {
			List<Path> dumps = files
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().matches("\\d+_entities_.+\\.[vf]sh"))
				.sorted()
				.toList();
			for (Path fragment : dumps) {
				String filename = fragment.getFileName().toString();
				if (!filename.endsWith(".fsh")) {
					continue;
				}
				Path vertex = fragment.resolveSibling(filename.substring(0, filename.length() - 4) + ".vsh");
				if (!Files.isRegularFile(vertex)) {
					continue;
				}
				String vertexSource = Files.readString(vertex);
				String fragmentSource = Files.readString(fragment);
				if (vertexSource.contains("celerant_vrm_toon_marker")
					&& fragmentSource.contains("celerant_vrm_toon_marker")
					&& fragmentSource.contains("celerant_vrm_ramp")
					&& fragmentSource.contains("celerant_vrm_toon_normal")) {
					System.out.println("[Celerant toon test] verified Iris runtime shader pair "
						+ vertex.getFileName() + " / " + fragment.getFileName());
					return;
				}
			}
			throw new AssertionError("actual Iris entities_* shader dump must contain the Celerant toon patch; dumps="
				+ dumps.stream().map(path -> path.getFileName().toString()).sorted().toList());
		} catch (IOException exception) {
			throw new AssertionError("could not inspect Iris patched_shaders output", exception);
		}
	}

	private static void verifyIrisMixinPath() {
		Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(
			"entities_celerant_gametest",
			VERTEX_SHADER, null, null, null, FRAGMENT_SHADER,
			AlphaTest.ALWAYS, false, false, false,
			new ShaderAttributeInputs(true, true, true, true, true),
			new Object2ObjectOpenHashMap<>());
		String vertex = patched.get(PatchShaderType.VERTEX);
		String fragment = patched.get(PatchShaderType.FRAGMENT);
		require(vertex != null && vertex.contains("celerant_vrm_toon_marker") && vertex.contains("iris_UV1"),
			"Iris TransformPatcher mixin must inject the VRM overlay marker");
		require(fragment != null && fragment.contains("celerant_vrm_ramp")
			&& fragment.contains("celerant_vrm_toon_normal") && fragment.contains("shadowLightPosition"),
			"Iris TransformPatcher mixin must inject the normal-based toon pass");

		Map<PatchShaderType, String> multiOutput = TransformPatcher.patchVanilla(
			"entities_celerant_multibuffer",
			VERTEX_SHADER, null, null, null, MULTI_OUTPUT_FRAGMENT_SHADER,
			AlphaTest.ALWAYS, false, false, false,
			new ShaderAttributeInputs(true, true, true, true, true),
			new Object2ObjectOpenHashMap<>());
		require(!multiOutput.get(PatchShaderType.FRAGMENT).contains("celerant_vrm_toon_marker"),
			"multi-attachment ShaderPacks must remain unchanged");
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
			int[] onBounds = {width, height, -1, -1};
			int[] offBounds = {width, height, -1, -1};
			int[] restoredBounds = {width, height, -1, -1};
			int onCount = 0;
			int offCount = 0;
			int restoredCount = 0;
			int stablePixels = 0;
			int changedPixels = 0;
			for (int index = 0; index < onPixels.length; index++) {
				int x = index % width;
				int y = index / width;
				boolean onMagenta = isMagenta(onPixels[index]);
				boolean offMagenta = isMagenta(offPixels[index]);
				boolean restoredMagenta = isMagenta(restoredPixels[index]);
				if (onMagenta) {
					onCount++;
					onBounds[0] = Math.min(onBounds[0], x);
					onBounds[1] = Math.min(onBounds[1], y);
					onBounds[2] = Math.max(onBounds[2], x);
					onBounds[3] = Math.max(onBounds[3], y);
				}
				if (offMagenta) {
					offCount++;
					offBounds[0] = Math.min(offBounds[0], x);
					offBounds[1] = Math.min(offBounds[1], y);
					offBounds[2] = Math.max(offBounds[2], x);
					offBounds[3] = Math.max(offBounds[3], y);
				}
				if (restoredMagenta) {
					restoredCount++;
					restoredBounds[0] = Math.min(restoredBounds[0], x);
					restoredBounds[1] = Math.min(restoredBounds[1], y);
					restoredBounds[2] = Math.max(restoredBounds[2], x);
					restoredBounds[3] = Math.max(restoredBounds[3], y);
				}
				if (onMagenta && offMagenta && restoredMagenta) {
					stablePixels++;
					int idleDelta = rgbDistance(onPixels[index], restoredPixels[index]);
					int toonDelta = Math.min(rgbDistance(onPixels[index], offPixels[index]),
						rgbDistance(restoredPixels[index], offPixels[index]));
					if (idleDelta <= 18 && toonDelta >= Math.max(36, idleDelta * 3)) {
						changedPixels++;
					}
				}
			}
			require(onCount >= 50 && offCount >= 50 && restoredCount >= 50,
				"toon A/B screenshots must all contain the VRM");
			for (int index = 0; index < onBounds.length; index++) {
				require(Math.abs(onBounds[index] - offBounds[index]) <= 1,
					"toon A/B must keep a stable VRM bounding box (on=" + Arrays.toString(onBounds)
						+ ", off=" + Arrays.toString(offBounds) + ")");
				require(Math.abs(onBounds[index] - restoredBounds[index]) <= 1,
					"restoring the toon patch must restore the VRM bounding box (on=" + Arrays.toString(onBounds)
						+ ", restored=" + Arrays.toString(restoredBounds) + ")");
			}
			require(stablePixels * 100 >= Math.min(Math.min(onCount, offCount), restoredCount) * 80,
				"toon A/B must compare the same VRM pixels (stable=" + stablePixels + ")");
			require(changedPixels >= 20 && changedPixels * 100 >= stablePixels * 50,
				"disabling the runtime toon patch must change stable VRM material pixels (changed="
					+ changedPixels + ", stable=" + stablePixels + ")");
			System.out.println("[Celerant toon test] stable VRM pixels=" + stablePixels
				+ ", changed material pixels=" + changedPixels + ", bounds=" + Arrays.toString(onBounds));
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
				if (x < width * 25 / 100 || x > width * 75 / 100 || y < height * 30 / 100) {
					continue;
				}
				centeredSignals++;
				bounds[0] = Math.min(bounds[0], x);
				bounds[1] = Math.min(bounds[1], y);
				bounds[2] = Math.max(bounds[2], x);
				bounds[3] = Math.max(bounds[3], y);
			}
			require(signalPixels >= Math.max(500, width * height / 1000),
				"the local VRM must visibly change when its runtime toon patch is disabled");
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
		return red >= 55 && green <= 60 && blue >= 75;
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
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			target.toFile().deleteOnExit();
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
			  "extensionsUsed":["VRMC_vrm"],
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
			  "materials":[{"doubleSided":true,"pbrMetallicRoughness":{
			    "baseColorFactor":[1.0,0.05,0.55,1.0],"metallicFactor":0.0,"roughnessFactor":1.0
			  }},{"doubleSided":true,"pbrMetallicRoughness":{
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
