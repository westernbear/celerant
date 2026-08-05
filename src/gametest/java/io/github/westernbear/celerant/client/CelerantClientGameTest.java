package io.github.westernbear.celerant.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;

import org.lwjgl.glfw.GLFW;

public final class CelerantClientGameTest implements FabricClientGameTest {
	private static final String PACK_NAME = "CelerantTest";
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
		enableShaderPack(context, packRoot);
		verifyIrisMixinPath();

		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true).create()) {
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender(true, 1200);
			context.waitFor(client -> Iris.isPackInUseQuick()
				&& Iris.getPipelineManager().getPipeline().isPresent()
				&& Iris.getStoredError().isEmpty(), 1200);

			testFailureFlow(context);
			testUserFlow(context, world, connection);
		}

		context.waitFor(client -> "VRM: not loaded".equals(VrmRuntime.getInstance().info()), 200);
		verifyPackUnchanged(packRoot);
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
		TestServerConnection connection) {
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

		Path base = context.takeScreenshot("celerant-vrm-base");
		sendCommand(context, "celerant vrm expression smile 1", "VRM expression set to smile at 1.0");
		context.waitTicks(10);
		Path morphed = context.takeScreenshot("celerant-vrm-morphed");
		verifyRenderedScreenshots(base, morphed);

		sendCommand(context, "celerant vrm unload", "VRM unloaded");
		require("VRM: not loaded".equals(context.computeOnClient(client -> VrmRuntime.getInstance().info())),
			"unload command must release the model");

		// Leave one model loaded so closing the world verifies the disconnect cleanup path.
		sendCommand(context, "celerant vrm load minimal.vrm", "Loading VRM asynchronously");
		context.waitFor(client -> !VrmRuntime.getInstance().isLoading()
			&& VrmRuntime.getInstance().info().contains("VRM: minimal.vrm"), 1200);
	}

	private static void enableShaderPack(ClientGameTestContext context, Path packRoot) {
		try {
			context.runOnClient(client -> {
				require(Iris.isValidShaderpack(packRoot), "test ShaderPack must be discoverable by Iris");
				Iris.getIrisConfig().setShaderPackName(PACK_NAME);
				Iris.getIrisConfig().setShadersEnabled(true);
				Iris.getIrisConfig().save();
				Iris.reload();
			});
		} catch (IOException exception) {
			throw new AssertionError("could not enable the test ShaderPack", exception);
		}
		context.waitFor(client -> PACK_NAME.equals(Iris.getCurrentPackName())
			&& Iris.getStoredError().isEmpty(), 1200);
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
			&& fragment.contains("celerant_vrm_toon_normal"),
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

	private static boolean isMagenta(int pixel) {
		int blue = pixel & 0xFF;
		int green = pixel >>> 8 & 0xFF;
		int red = pixel >>> 16 & 0xFF;
		return red >= 100 && green <= 100 && blue >= 70;
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
		ByteBuffer binary = ByteBuffer.allocate(140).order(ByteOrder.LITTLE_ENDIAN);
		putFloats(binary, -0.75F, 0.0F, 0.0F, 0.75F, 0.0F, 0.0F, 0.0F, 1.5F, 0.0F);
		putFloats(binary, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
		putFloats(binary, 0.0F, 0.0F, 1.0F, 0.0F, 0.5F, 1.0F);
		putFloats(binary, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.75F, 0.0F);
		binary.putShort((short) 0).putShort((short) 1).putShort((short) 2);
		int dataLength = binary.position();
		while ((binary.position() & 3) != 0) {
			binary.put((byte) 0);
		}
		byte[] binaryChunk = Arrays.copyOf(binary.array(), binary.position());

		String json = """
			{
			  "asset":{"version":"2.0","generator":"Celerant Client Game Test"},
			  "extensionsUsed":["VRMC_vrm"],
			  "extensions":{"VRMC_vrm":{
			    "specVersion":"1.0",
			    "meta":{"name":"Celerant Test Avatar","version":"1.0","authors":["Celerant"],"licenseUrl":"https://vrm.dev/licenses/1.0/"},
			    "humanoid":{"humanBones":{}},
			    "expressions":{"custom":{
			      "smile":{"morphTargetBinds":[{"node":0,"index":0,"weight":1.0}]},
			      "blink":{"isBinary":true,"morphTargetBinds":[{"node":0,"index":0,"weight":1.0}]}
			    }}
			  }},
			  "scene":0,
			  "scenes":[{"nodes":[0]}],
			  "nodes":[{"mesh":0,"name":"Avatar"}],
			  "meshes":[{"weights":[0.0],"primitives":[{
			    "attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},
			    "targets":[{"POSITION":3}],"indices":4,"material":0
			  }]}],
			  "materials":[{"doubleSided":true,"pbrMetallicRoughness":{
			    "baseColorFactor":[1.0,0.05,0.55,1.0],"metallicFactor":0.0,"roughnessFactor":1.0
			  }}],
			  "buffers":[{"byteLength":%d}],
			  "bufferViews":[
			    {"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},
			    {"buffer":0,"byteOffset":36,"byteLength":36,"target":34962},
			    {"buffer":0,"byteOffset":72,"byteLength":24,"target":34962},
			    {"buffer":0,"byteOffset":96,"byteLength":36,"target":34962},
			    {"buffer":0,"byteOffset":132,"byteLength":6,"target":34963}
			  ],
			  "accessors":[
			    {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[-0.75,0.0,0.0],"max":[0.75,1.5,0.0]},
			    {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
			    {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
			    {"bufferView":3,"componentType":5126,"count":3,"type":"VEC3"},
			    {"bufferView":4,"componentType":5123,"count":3,"type":"SCALAR","min":[0],"max":[2]}
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
