package io.github.westernbear.celerant.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modularmods.mcgltf.MCglTF;
import com.modularmods.mcgltf.RenderedGltfModel;
import com.modularmods.mcgltf.RenderedGltfScene;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.io.GltfModelReader;
import io.github.westernbear.celerant.Celerant;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

public final class VrmRuntime {

	private static final int MAX_BYTES = 256 * 1024 * 1024;
	private static final int GLB_MAGIC = 0x46546C67;
	private static final int GLB_JSON = 0x4E4F534A;
	private static final int VRM_OVERLAY = OverlayTexture.pack(OverlayTexture.NO_WHITE_U, 15);
	private static final Set<String> SUPPORTED_REQUIRED_EXTENSIONS = Set.of(
		"VRM",
		"VRMC_vrm",
		// ponytail: one model-wide NPR pass; add a per-primitive material channel for full MToon fidelity.
		"VRMC_materials_mtoon",
		"KHR_materials_unlit",
		"KHR_mesh_quantization"
	);
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Celerant VRM loader");
		thread.setDaemon(true);
		return thread;
	});
	private static final VrmRuntime INSTANCE = new VrmRuntime();

	private RenderedGltfModel model;
	private List<Runnable> cleanup = List.of();
	private Map<String, VrmExpression> expressions = Map.of();
	private IdentityHashMap<NodeModel, float[]> baseWeights = new IdentityHashMap<>();
	private Path loadedPath;
	private Vec3 position;
	private float scale = 1.0F;
	private long fileSize;
	private long generation;
	private int sceneIndex;
	private boolean initialized;
	private boolean loading;
	private boolean vrm0;
	private String vrmVersion = "-";
	private String activeExpression;
	private float activeExpressionWeight;

	private VrmRuntime() {
	}

	public static void initialize() {
		if (INSTANCE.initialized) {
			return;
		}
		INSTANCE.initialized = true;
		LevelRenderEvents.COLLECT_SUBMITS.register(INSTANCE::render);
	}

	static VrmRuntime getInstance() {
		return INSTANCE;
	}

	boolean load(String fileName, Vec3 fallbackPosition) {
		if (loading) {
			return false;
		}

		Minecraft client = Minecraft.getInstance();
		Path gameDirectory = client.gameDirectory.toPath();
		long ticket = ++generation;
		loading = true;
		CompletableFuture.supplyAsync(() -> readModel(gameDirectory, fileName), LOADER)
			.whenComplete((parsed, error) -> client.execute(() -> finishLoad(ticket, parsed, error, fallbackPosition)));
		return true;
	}

	boolean unload() {
		if (!loading && model == null) {
			return false;
		}
		generation++;
		loading = false;
		releaseModel();
		return true;
	}

	void place(Vec3 position) {
		this.position = position;
	}

	void setScale(float scale) {
		this.scale = scale;
	}

	boolean setExpression(String name, float weight) {
		VrmExpression expression = expressions.get(normalizeName(name));
		if (model == null || expression == null) {
			return false;
		}

		float appliedWeight = expressionWeight(expression.binary(), weight);
		restoreBaseWeights();
		for (MorphBinding binding : expression.bindings()) {
			float[] weights = binding.node().getWeights();
			weights[binding.target()] = clamp01(weights[binding.target()] + binding.weight() * appliedWeight);
		}
		// ponytail: one active expression; add blend slots only when simultaneous facial layers are needed.
		activeExpression = expression.name();
		activeExpressionWeight = appliedWeight;
		return true;
	}

	boolean clearExpression() {
		if (model == null) {
			return false;
		}
		restoreBaseWeights();
		activeExpression = null;
		activeExpressionWeight = 0.0F;
		return true;
	}

	List<String> expressionNames() {
		return expressions.values().stream().map(VrmExpression::name).toList();
	}

	boolean isLoading() {
		return loading;
	}

	String info() {
		if (model == null) {
			return loading ? "VRM: loading" : "VRM: not loaded";
		}

		int primitiveCount = model.renderedGltfScenes.stream().mapToInt(RenderedGltfScene::getPrimitiveCount).sum();
		int vertexCount = model.renderedGltfScenes.stream().mapToInt(RenderedGltfScene::getSubmittedVertexCount).sum();
		String location = position == null ? "unset" : String.format(Locale.ROOT, "%.2f %.2f %.2f", position.x, position.y, position.z);
		String expression = activeExpression == null ? "none"
			: String.format(Locale.ROOT, "%s %.2f", activeExpression, activeExpressionWeight);
		return String.format(Locale.ROOT,
			"VRM: %s, spec %s, %.1f MiB, scenes %d, primitives %d, submitted vertices %d, position %s, scale %.3f, expression %s, available %d",
			loadedPath.getFileName(), vrmVersion, fileSize / 1048576.0, model.renderedGltfScenes.size(),
			primitiveCount, vertexCount, location, scale, expression, expressions.size());
	}

	private void render(LevelRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		if (model == null || position == null || client.level == null || model.renderedGltfScenes.isEmpty()) {
			return;
		}

		CameraRenderState camera = context.levelState().cameraRenderState;
		PoseStack poseStack = context.poseStack();
		if (camera == null || camera.pos == null || poseStack == null) {
			return;
		}

		int light = LightCoordsUtil.getLightCoords(client.level,
			BlockPos.containing(position.x, position.y, position.z));
		poseStack.pushPose();
		try {
			poseStack.translate(position.x - camera.pos.x, position.y - camera.pos.y, position.z - camera.pos.z);
			if (vrm0) {
				poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
			}
			poseStack.scale(scale, scale, scale);
			model.submit(sceneIndex, poseStack, context.submitNodeCollector(), light, VRM_OVERLAY);
		} finally {
			poseStack.popPose();
		}
	}

	private void finishLoad(long ticket, ParsedModel parsed, Throwable error, Vec3 fallbackPosition) {
		if (ticket != generation) {
			return;
		}
		loading = false;
		if (error != null) {
			Throwable cause = unwrap(error);
			Celerant.LOGGER.error("Could not load VRM", cause);
			message("VRM load failed: " + safeMessage(cause));
			return;
		}

		List<Runnable> newCleanup = new ArrayList<>();
		RenderedGltfModel newModel;
		try {
			newModel = new RenderedGltfModel(newCleanup, parsed.gltfModel());
		} catch (RuntimeException exception) {
			runCleanup(newCleanup);
			Celerant.LOGGER.error("Could not prepare VRM render data", exception);
			message("VRM render preparation failed: " + safeMessage(exception));
			return;
		}

		releaseModel();
		model = newModel;
		cleanup = newCleanup;
		expressions = parsed.expressions();
		baseWeights = captureBaseWeights(expressions);
		loadedPath = parsed.path();
		fileSize = parsed.fileSize();
		sceneIndex = parsed.sceneIndex();
		vrmVersion = parsed.vrmVersion();
		vrm0 = parsed.vrm0();
		position = position == null ? fallbackPosition : position;
		message("Loaded " + loadedPath.getFileName() + " (" + expressions.size() + " expressions)");
	}

	private void releaseModel() {
		runCleanup(cleanup);
		model = null;
		cleanup = List.of();
		expressions = Map.of();
		baseWeights = new IdentityHashMap<>();
		loadedPath = null;
		fileSize = 0;
		sceneIndex = 0;
		vrmVersion = "-";
		vrm0 = false;
		activeExpression = null;
		activeExpressionWeight = 0.0F;
	}

	private void restoreBaseWeights() {
		baseWeights.forEach((node, weights) -> node.setWeights(weights.clone()));
	}

	private static ParsedModel readModel(Path gameDirectory, String fileName) {
		try {
			Path path = resolveModelPath(gameDirectory, fileName);
			byte[] data = readBounded(path);
			JsonObject json = readGlbJson(data);
			validateSelfContained(json);
			RawExpressions rawExpressions = parseRawExpressions(json);
			GltfModel gltfModel = new GltfModelReader().readWithoutReferences(new ByteArrayInputStream(data));
			if (gltfModel.getSceneModels().isEmpty()) {
				throw new IOException("VRM has no renderable scene");
			}
			int sceneIndex = integer(json, "scene", 0);
			if (sceneIndex < 0 || sceneIndex >= gltfModel.getSceneModels().size()) {
				throw new IOException("VRM default scene index is out of range");
			}
			Map<String, VrmExpression> expressions = resolveExpressions(gltfModel, rawExpressions);
			return new ParsedModel(path, data.length, gltfModel, expressions, sceneIndex, rawExpressions.version(),
				"0.x".equals(rawExpressions.version()));
		} catch (IOException | RuntimeException exception) {
			throw new IllegalStateException(exception.getMessage(), exception);
		}
	}

	private static Path resolveModelPath(Path gameDirectory, String fileName) throws IOException {
		if (fileName == null || fileName.isBlank()) {
			throw new IOException("model file is empty");
		}

		Path relative;
		try {
			relative = Path.of(fileName.trim());
		} catch (InvalidPathException exception) {
			throw new IOException("invalid model path", exception);
		}
		if (relative.isAbsolute() || !relative.toString().toLowerCase(Locale.ROOT).endsWith(".vrm")) {
			throw new IOException("only relative .vrm paths are allowed");
		}

		Path realGameRoot = gameDirectory.toAbsolutePath().normalize().toRealPath();
		Path celerantDirectory = realGameRoot.resolve("celerant");
		if (Files.isSymbolicLink(celerantDirectory)) {
			throw new IOException("celerant directory may not be a symbolic link");
		}
		Files.createDirectories(celerantDirectory);
		Path modelRoot = celerantDirectory.resolve("models");
		if (Files.isSymbolicLink(modelRoot)) {
			throw new IOException("model directory may not be a symbolic link");
		}
		Files.createDirectories(modelRoot);
		Path realModelRoot = modelRoot.toRealPath();
		if (!realModelRoot.startsWith(realGameRoot)) {
			throw new IOException("model directory escapes the game directory");
		}

		Path candidate = realModelRoot.resolve(relative).normalize();
		if (!candidate.startsWith(realModelRoot)) {
			throw new IOException("model path escapes celerant/models");
		}
		Path realPath = candidate.toRealPath();
		if (!realPath.startsWith(realModelRoot) || !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("model is not a regular file inside celerant/models");
		}
		return realPath;
	}

	private static byte[] readBounded(Path path) throws IOException {
		try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
			long size = channel.size();
			if (size <= 0 || size > MAX_BYTES) {
				throw new IOException("model must be between 1 byte and 256 MiB");
			}

			byte[] data = new byte[(int) size];
			ByteBuffer destination = ByteBuffer.wrap(data);
			while (destination.hasRemaining()) {
				if (channel.read(destination) < 0) {
					throw new IOException("model changed while loading");
				}
			}
			if (channel.read(ByteBuffer.allocate(1)) >= 0) {
				throw new IOException("model grew beyond the checked size");
			}
			return data;
		}
	}

	private static JsonObject readGlbJson(byte[] data) throws IOException {
		if (data.length < 20) {
			throw new IOException("not a GLB/VRM file");
		}
		ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (header.getInt(0) != GLB_MAGIC || header.getInt(4) != 2
			|| Integer.toUnsignedLong(header.getInt(8)) != data.length) {
			throw new IOException("invalid GLB 2.0 header");
		}

		int jsonLength = header.getInt(12);
		if (jsonLength < 2 || header.getInt(16) != GLB_JSON || jsonLength > data.length - 20) {
			throw new IOException("invalid GLB JSON chunk");
		}
		int jsonEnd = 20 + jsonLength;
		while (jsonEnd > 20 && (data[jsonEnd - 1] == 0 || Character.isWhitespace(data[jsonEnd - 1]))) {
			jsonEnd--;
		}
		JsonElement root = JsonParser.parseString(new String(data, 20, jsonEnd - 20, StandardCharsets.UTF_8));
		if (!root.isJsonObject()) {
			throw new IOException("GLB JSON root is not an object");
		}
		return root.getAsJsonObject();
	}

	private static void validateSelfContained(JsonObject root) throws IOException {
		JsonObject extensions = object(root, "extensions");
		if (object(extensions, "VRM") == null && object(extensions, "VRMC_vrm") == null) {
			throw new IOException("file does not contain a VRM 0.x or VRM 1.0 extension");
		}
		JsonArray required = array(root, "extensionsRequired");
		if (required != null) {
			for (JsonElement element : required) {
				if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
					throw new IOException("invalid required glTF extension name");
				}
				String extension = element.getAsString();
				if (!SUPPORTED_REQUIRED_EXTENSIONS.contains(extension)) {
					throw new IOException("unsupported required glTF extension: " + extension);
				}
			}
		}
		validateResources(array(root, "buffers"), "buffer");
		validateResources(array(root, "images"), "image");
	}

	private static void validateResources(JsonArray resources, String kind) throws IOException {
		if (resources == null) {
			return;
		}
		for (JsonElement element : resources) {
			if (!element.isJsonObject()) {
				throw new IOException("invalid " + kind + " entry");
			}
			JsonObject resource = element.getAsJsonObject();
			String uri = string(resource, "uri");
			JsonObject extras = object(resource, "extras");
			if ((uri != null && !uri.startsWith("data:"))
				|| (extras != null && extras.has(MCglTF.RESOURCE_LOCATION))) {
				throw new IOException("external " + kind + " resources are not allowed");
			}
		}
	}

	private static RawExpressions parseRawExpressions(JsonObject root) {
		JsonObject extensions = object(root, "extensions");
		JsonObject vrm1 = object(extensions, "VRMC_vrm");
		if (vrm1 != null) {
			LinkedHashMap<String, RawExpression> expressions = new LinkedHashMap<>();
			JsonObject groups = object(vrm1, "expressions");
			parseVrm1Expressions(object(groups, "preset"), expressions);
			parseVrm1Expressions(object(groups, "custom"), expressions);
			return new RawExpressions("1.0", expressions);
		}

		LinkedHashMap<String, RawExpression> expressions = new LinkedHashMap<>();
		JsonObject vrm0 = object(extensions, "VRM");
		JsonObject master = object(vrm0, "blendShapeMaster");
		JsonArray groups = array(master, "blendShapeGroups");
		if (groups != null) {
			for (JsonElement element : groups) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject group = element.getAsJsonObject();
				String preset = string(group, "presetName");
				String name = preset == null || preset.isBlank() || "unknown".equalsIgnoreCase(preset)
					? string(group, "name") : preset;
				boolean binary = bool(group, "isBinary", false);
				List<RawBinding> bindings = new ArrayList<>();
				JsonArray binds = array(group, "binds");
				if (binds != null) {
					for (JsonElement bindElement : binds) {
						JsonObject bind = bindElement.isJsonObject() ? bindElement.getAsJsonObject() : null;
						int mesh = integer(bind, "mesh", -1);
						int target = integer(bind, "index", -1);
						float weight = decimal(bind, "weight", 0.0F) / 100.0F;
						if (mesh >= 0 && target >= 0) {
							bindings.add(new RawBinding(-1, mesh, target, clamp01(weight)));
						}
					}
				}
				addRawExpression(expressions, name, bindings, binary);
			}
		}
		return new RawExpressions("0.x", expressions);
	}

	private static void parseVrm1Expressions(JsonObject source, LinkedHashMap<String, RawExpression> output) {
		if (source == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject expression = entry.getValue().getAsJsonObject();
			List<RawBinding> bindings = new ArrayList<>();
			JsonArray binds = array(expression, "morphTargetBinds");
			if (binds != null) {
				for (JsonElement bindElement : binds) {
					JsonObject bind = bindElement.isJsonObject() ? bindElement.getAsJsonObject() : null;
					int node = integer(bind, "node", -1);
					int target = integer(bind, "index", -1);
					float weight = decimal(bind, "weight", 0.0F);
					if (node >= 0 && target >= 0) {
						bindings.add(new RawBinding(node, -1, target, clamp01(weight)));
					}
				}
			}
			addRawExpression(output, entry.getKey(), bindings, bool(expression, "isBinary", false));
		}
	}

	private static void addRawExpression(LinkedHashMap<String, RawExpression> output, String name,
		List<RawBinding> bindings, boolean binary) {
		if (name != null && !name.isBlank() && !bindings.isEmpty()) {
			output.putIfAbsent(normalizeName(name), new RawExpression(name, List.copyOf(bindings), binary));
		}
	}

	private static Map<String, VrmExpression> resolveExpressions(GltfModel model, RawExpressions raw) {
		LinkedHashMap<String, VrmExpression> resolved = new LinkedHashMap<>();
		List<NodeModel> nodes = model.getNodeModels();
		List<MeshModel> meshes = model.getMeshModels();
		for (Map.Entry<String, RawExpression> entry : raw.expressions().entrySet()) {
			List<MorphBinding> bindings = new ArrayList<>();
			for (RawBinding rawBinding : entry.getValue().bindings()) {
				if (rawBinding.node() >= 0 && rawBinding.node() < nodes.size()) {
					addResolvedBinding(bindings, nodes.get(rawBinding.node()), rawBinding);
				} else if (rawBinding.mesh() >= 0 && rawBinding.mesh() < meshes.size()) {
					MeshModel mesh = meshes.get(rawBinding.mesh());
					for (NodeModel node : nodes) {
						if (node.getMeshModels().contains(mesh)) {
							addResolvedBinding(bindings, node, rawBinding);
						}
					}
				}
			}
			if (!bindings.isEmpty()) {
				resolved.put(entry.getKey(), new VrmExpression(
					entry.getValue().name(), List.copyOf(bindings), entry.getValue().binary()));
			}
		}
		return resolved;
	}

	private static void addResolvedBinding(List<MorphBinding> output, NodeModel node, RawBinding binding) {
		if (binding.target() < targetCount(node)) {
			output.add(new MorphBinding(node, binding.target(), binding.weight()));
		}
	}

	private static IdentityHashMap<NodeModel, float[]> captureBaseWeights(Map<String, VrmExpression> expressions) {
		IdentityHashMap<NodeModel, Integer> requiredSizes = new IdentityHashMap<>();
		for (VrmExpression expression : expressions.values()) {
			for (MorphBinding binding : expression.bindings()) {
				requiredSizes.merge(binding.node(), binding.target() + 1, Math::max);
			}
		}

		IdentityHashMap<NodeModel, float[]> result = new IdentityHashMap<>();
		requiredSizes.forEach((node, requiredSize) -> {
			int size = Math.max(requiredSize, targetCount(node));
			float[] weights = node.getWeights();
			if (weights == null) {
				weights = node.getMeshModels().stream().map(MeshModel::getWeights).filter(value -> value != null)
					.findFirst().orElse(new float[0]);
			}
			float[] base = Arrays.copyOf(weights, Math.max(size, weights.length));
			node.setWeights(base.clone());
			result.put(node, base);
		});
		return result;
	}

	private static int targetCount(NodeModel node) {
		int count = 0;
		for (MeshModel mesh : node.getMeshModels()) {
			if (mesh.getWeights() != null) {
				count = Math.max(count, mesh.getWeights().length);
			}
			for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
				count = Math.max(count, primitive.getTargets().size());
			}
		}
		return count;
	}

	private static void runCleanup(List<Runnable> cleanup) {
		for (Runnable action : cleanup) {
			try {
				action.run();
			} catch (RuntimeException exception) {
				Celerant.LOGGER.warn("Could not release VRM render resource", exception);
			}
		}
	}

	private static void message(String text) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal("[Celerant] " + text));
		}
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null
			&& (current instanceof java.util.concurrent.CompletionException
				|| current instanceof java.util.concurrent.ExecutionException)) {
			current = current.getCause();
		}
		return current;
	}

	private static String safeMessage(Throwable error) {
		return error.getMessage() == null || error.getMessage().isBlank()
			? error.getClass().getSimpleName() : error.getMessage();
	}

	private static String normalizeName(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	private static float expressionWeight(boolean binary, float value) {
		return binary ? (value >= 0.5F ? 1.0F : 0.0F) : clamp01(value);
	}

	private static JsonObject object(JsonObject parent, String name) {
		if (parent == null) {
			return null;
		}
		JsonElement element = parent.get(name);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject parent, String name) {
		if (parent == null) {
			return null;
		}
		JsonElement element = parent.get(name);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	private static String string(JsonObject parent, String name) {
		if (parent == null) {
			return null;
		}
		JsonElement element = parent.get(name);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
			? element.getAsString() : null;
	}

	private static int integer(JsonObject parent, String name, int fallback) {
		if (parent == null) {
			return fallback;
		}
		JsonElement element = parent.get(name);
		try {
			return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
				? element.getAsInt() : fallback;
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static float decimal(JsonObject parent, String name, float fallback) {
		if (parent == null) {
			return fallback;
		}
		JsonElement element = parent.get(name);
		try {
			float value = element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
				? element.getAsFloat() : fallback;
			return Float.isFinite(value) ? value : fallback;
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static boolean bool(JsonObject parent, String name, boolean fallback) {
		if (parent == null) {
			return fallback;
		}
		JsonElement element = parent.get(name);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
			? element.getAsBoolean() : fallback;
	}

	static void selfCheck() {
		RawExpressions vrm0 = parseRawExpressions(JsonParser.parseString("""
			{"extensions":{"VRM":{"blendShapeMaster":{"blendShapeGroups":[
			{"name":"Joy","presetName":"joy","isBinary":true,"binds":[{"mesh":2,"index":1,"weight":75}]}
			]}}}}
			""").getAsJsonObject());
		RawExpressions vrm1 = parseRawExpressions(JsonParser.parseString("""
			{"extensions":{"VRMC_vrm":{"expressions":{"preset":{
			"happy":{"morphTargetBinds":[{"node":3,"index":0,"weight":0.5}]}
			}}}}}
			""").getAsJsonObject());
		require("0.x".equals(vrm0.version()), "VRM0 version");
		require(Math.abs(vrm0.expressions().get("joy").bindings().getFirst().weight() - 0.75F) < 1.0E-6F,
			"VRM0 weight conversion");
		require(vrm0.expressions().get("joy").binary(), "VRM0 binary expression");
		require(expressionWeight(true, 0.49F) == 0.0F && expressionWeight(true, 0.5F) == 1.0F,
			"binary expression threshold");
		require("1.0".equals(vrm1.version()), "VRM1 version");
		require(Math.abs(vrm1.expressions().get("happy").bindings().getFirst().weight() - 0.5F) < 1.0E-6F,
			"VRM1 weight");
	}

	public static void main(String[] args) {
		selfCheck();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private record ParsedModel(Path path, long fileSize, GltfModel gltfModel,
		Map<String, VrmExpression> expressions, int sceneIndex, String vrmVersion, boolean vrm0) {
	}

	private record VrmExpression(String name, List<MorphBinding> bindings, boolean binary) {
	}

	private record MorphBinding(NodeModel node, int target, float weight) {
	}

	private record RawExpressions(String version, LinkedHashMap<String, RawExpression> expressions) {
	}

	private record RawExpression(String name, List<RawBinding> bindings, boolean binary) {
	}

	private record RawBinding(int node, int mesh, int target, float weight) {
	}
}
