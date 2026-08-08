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
import java.util.Collections;
import java.util.HashSet;
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
import de.javagl.jgltf.model.impl.DefaultNodeModel;
import de.javagl.jgltf.model.io.GltfModelReader;
import io.github.westernbear.celerant.Celerant;
import io.github.westernbear.celerant.client.mixin.AvatarRendererAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
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
	private VrmRig rig;
	private RenderedGltfModel.RenderView firstPersonView = RenderedGltfModel.FULL_VIEW;
	private RenderedGltfModel.RenderView thirdPersonView = RenderedGltfModel.FULL_VIEW;
	private FirstPersonAnchor firstPersonAnchor;
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
	private boolean avatarEnabled;
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

	public static VrmRuntime getInstance() {
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

	boolean setAvatarEnabled(boolean enabled) {
		if (enabled && (model == null || rig == null || !rig.isUsable())) {
			return false;
		}
		avatarEnabled = enabled;
		return true;
	}

	String avatarProblem() {
		if (model == null) {
			return "No VRM is loaded";
		}
		return rig == null || rig.isUsable() ? "-" : rig.problem();
	}

	public boolean isLocalAvatarActive() {
		return avatarEnabled && model != null && rig != null && rig.isUsable();
	}

	public boolean shouldReplacePlayer(AvatarRenderState state) {
		Minecraft client = Minecraft.getInstance();
		return isLocalAvatarActive() && client.player != null && state.id == client.player.getId();
	}

	public void submitPlayer(AvatarRenderState state, PlayerModel playerModel, PoseStack poseStack,
		SubmitNodeCollector collector) {
		if (!shouldReplacePlayer(state) || state.isInvisible || state.isSpectator || model.renderedGltfScenes.isEmpty()) {
			return;
		}
		playerModel.setupAnim(state);
		submitPosed(state, playerModel, poseStack, collector, thirdPersonView, null);
	}

	float[] debugBoneRotation(String bone) {
		return rig == null ? null : rig.rotation(bone);
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
		String rigInfo = rig == null ? "0" : Integer.toString(rig.boneCount());
		return String.format(Locale.ROOT,
			"VRM: %s, spec %s, %.1f MiB, scenes %d, primitives %d, submitted vertices %d, position %s, scale %.3f, avatar %s, rig %s, expression %s, available %d",
			loadedPath.getFileName(), vrmVersion, fileSize / 1048576.0, model.renderedGltfScenes.size(),
			primitiveCount, vertexCount, location, scale, avatarEnabled ? "on" : "off", rigInfo, expression,
			expressions.size());
	}

	private void render(LevelRenderContext context) {
		if (isLocalAvatarActive()) {
			renderFirstPerson(context);
			return;
		}
		renderPlaced(context);
	}

	private void renderPlaced(LevelRenderContext context) {
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

	private void renderFirstPerson(LevelRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || client.getCameraEntity() != client.player
			|| !client.options.getCameraType().isFirstPerson()) {
			return;
		}

		CameraRenderState camera = context.levelState().cameraRenderState;
		PoseStack poseStack = context.poseStack();
		if (camera == null || camera.pos == null || poseStack == null) {
			return;
		}

		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		AvatarRenderer<AbstractClientPlayer> renderer = client.getEntityRenderDispatcher().getPlayerRenderer(client.player);
		AvatarRenderState state = renderer.createRenderState(client.player, partialTick);
		if (state.isInvisible || state.isSpectator) {
			return;
		}
		PlayerModel playerModel = renderer.getModel();
		playerModel.setupAnim(state);

		poseStack.pushPose();
		try {
			if (firstPersonAnchor == null) {
				poseStack.translate(state.x - camera.pos.x, state.y - camera.pos.y, state.z - camera.pos.z);
			}
			poseStack.scale(state.scale, state.scale, state.scale);
			((AvatarRendererAccessor) renderer).celerant$setupRotations(
				state, poseStack, state.bodyRot, state.scale);
			submitPosed(state, playerModel, poseStack, context.submitNodeCollector(), firstPersonView,
				firstPersonAnchor);
		} finally {
			poseStack.popPose();
		}
	}

	private void submitPosed(AvatarRenderState state, PlayerModel playerModel, PoseStack poseStack,
		SubmitNodeCollector collector, RenderedGltfModel.RenderView view, FirstPersonAnchor cameraAnchor) {
		AbstractClientPlayer player = Minecraft.getInstance().player;
		boolean airborne = player != null && !player.onGround() && !player.getAbilities().flying
			&& !player.onClimbable() && !state.isInWater && state.swimAmount <= 0.0F
			&& !state.isPassenger && !state.isFallFlying && !state.isAutoSpinAttack && state.deathTime <= 0.0F;
		float verticalSpeed = player == null ? 0.0F : (float) player.getDeltaMovement().y;
		rig.apply(playerModel, state, verticalSpeed, airborne);
		float[] anchor = cameraAnchor == null ? null : cameraAnchor.position();
		if (anchor != null && !finite(anchor)) {
			rig.restore();
			return;
		}
		poseStack.pushPose();
		try {
			if (!vrm0) {
				poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
			}
			poseStack.scale(scale, scale, scale);
			if (anchor != null) {
				poseStack.translate(-anchor[0], -anchor[1], -anchor[2]);
			}
			model.submit(sceneIndex, poseStack, collector, state.lightCoords, VRM_OVERLAY, view);
		} finally {
			poseStack.popPose();
			rig.restore();
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
		VrmRig newRig;
		try {
			newModel = new RenderedGltfModel(newCleanup, parsed.gltfModel());
			newRig = VrmRig.create(parsed.gltfModel(), parsed.humanoid());
		} catch (RuntimeException exception) {
			runCleanup(newCleanup);
			Celerant.LOGGER.error("Could not prepare VRM render data", exception);
			message("VRM render preparation failed: " + safeMessage(exception));
			return;
		}

		releaseModel();
		model = newModel;
		rig = newRig;
		firstPersonView = parsed.renderViews().firstPerson();
		thirdPersonView = parsed.renderViews().thirdPerson();
		firstPersonAnchor = parsed.renderViews().firstPersonAnchor();
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
		rig = null;
		firstPersonView = RenderedGltfModel.FULL_VIEW;
		thirdPersonView = RenderedGltfModel.FULL_VIEW;
		firstPersonAnchor = null;
		cleanup = List.of();
		expressions = Map.of();
		baseWeights = new IdentityHashMap<>();
		loadedPath = null;
		fileSize = 0;
		sceneIndex = 0;
		vrmVersion = "-";
		avatarEnabled = false;
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
			Map<String, Integer> humanoid = parseHumanoid(json);
			RawFirstPerson rawFirstPerson = parseFirstPerson(json);
			GltfModel gltfModel = new GltfModelReader().readWithoutReferences(new ByteArrayInputStream(data));
			if (gltfModel.getSceneModels().isEmpty()) {
				throw new IOException("VRM has no renderable scene");
			}
			int sceneIndex = integer(json, "scene", 0);
			if (sceneIndex < 0 || sceneIndex >= gltfModel.getSceneModels().size()) {
				throw new IOException("VRM default scene index is out of range");
			}
			for (Map.Entry<String, Integer> bone : humanoid.entrySet()) {
				if (bone.getValue() >= gltfModel.getNodeModels().size()) {
					throw new IOException("VRM humanoid bone " + bone.getKey() + " has an invalid node index");
				}
			}
			Map<String, VrmExpression> expressions = resolveExpressions(gltfModel, rawExpressions);
			RenderViews renderViews = resolveRenderViews(gltfModel, humanoid, rawFirstPerson);
			return new ParsedModel(path, data.length, gltfModel, expressions, humanoid, renderViews, sceneIndex,
				rawExpressions.version(), "0.x".equals(rawExpressions.version()));
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

	private static Map<String, Integer> parseHumanoid(JsonObject root) throws IOException {
		JsonObject extensions = object(root, "extensions");
		JsonObject vrm1 = object(extensions, "VRMC_vrm");
		LinkedHashMap<String, Integer> bones = new LinkedHashMap<>();
		if (vrm1 != null) {
			JsonObject humanBones = object(object(vrm1, "humanoid"), "humanBones");
			if (humanBones != null) {
				for (Map.Entry<String, JsonElement> entry : humanBones.entrySet()) {
					JsonObject bone = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
					addHumanoidBone(bones, entry.getKey(), integer(bone, "node", -1));
				}
			}
		} else {
			JsonArray humanBones = array(object(object(extensions, "VRM"), "humanoid"), "humanBones");
			if (humanBones != null) {
				for (JsonElement element : humanBones) {
					JsonObject bone = element.isJsonObject() ? element.getAsJsonObject() : null;
					addHumanoidBone(bones, string(bone, "bone"), integer(bone, "node", -1));
				}
			}
		}

		HashSet<Integer> nodes = new HashSet<>();
		for (Map.Entry<String, Integer> bone : bones.entrySet()) {
			if (!nodes.add(bone.getValue())) {
				throw new IOException("VRM humanoid maps more than one bone to node " + bone.getValue());
			}
		}
		return Map.copyOf(bones);
	}

	private static void addHumanoidBone(Map<String, Integer> output, String name, int node) throws IOException {
		if (name == null || name.isBlank() || node < 0) {
			return;
		}
		if (output.putIfAbsent(name, node) != null) {
			throw new IOException("VRM humanoid contains duplicate bone " + name);
		}
	}

	private static RawFirstPerson parseFirstPerson(JsonObject root) throws IOException {
		JsonObject extensions = object(root, "extensions");
		JsonObject vrm1 = object(extensions, "VRMC_vrm");
		boolean nodeIndexed = vrm1 != null;
		JsonObject firstPerson = object(nodeIndexed ? vrm1 : object(extensions, "VRM"), "firstPerson");
		int firstPersonBone = nodeIndexed ? -1 : integer(firstPerson, "firstPersonBone", -1);
		float[] offset = null;
		JsonObject rawOffset = nodeIndexed ? null : object(firstPerson, "firstPersonBoneOffset");
		if (rawOffset != null) {
			float[] parsed = {
				decimal(rawOffset, "x", 0.0F), decimal(rawOffset, "y", 0.0F), -decimal(rawOffset, "z", 0.0F)
			};
			if (!finite(parsed)) {
				throw new IOException("VRM first-person bone offset is not finite");
			}
			if (Math.abs(parsed[0]) + Math.abs(parsed[1]) + Math.abs(parsed[2]) > 1.0E-5F) {
				offset = parsed;
			}
		}
		JsonArray annotations = array(firstPerson, "meshAnnotations");
		LinkedHashMap<Integer, ViewType> result = new LinkedHashMap<>();
		if (annotations == null) {
			return new RawFirstPerson(nodeIndexed, firstPersonBone, offset, result);
		}

		String indexName = nodeIndexed ? "node" : "mesh";
		String typeName = nodeIndexed ? "type" : "firstPersonFlag";
		for (JsonElement element : annotations) {
			JsonObject annotation = element.isJsonObject() ? element.getAsJsonObject() : null;
			int index = integer(annotation, indexName, -1);
			String rawType = string(annotation, typeName);
			if (index < 0 || rawType == null) {
				throw new IOException("invalid VRM first-person mesh annotation");
			}
			ViewType type = switch (rawType.toLowerCase(Locale.ROOT)) {
				case "auto" -> ViewType.AUTO;
				case "both" -> ViewType.BOTH;
				case "firstpersononly" -> ViewType.FIRST_PERSON_ONLY;
				case "thirdpersononly" -> ViewType.THIRD_PERSON_ONLY;
				default -> throw new IOException("unsupported VRM first-person annotation type: " + rawType);
			};
			if (result.putIfAbsent(index, type) != null) {
				throw new IOException("duplicate VRM first-person annotation index " + index);
			}
		}
		return new RawFirstPerson(nodeIndexed, firstPersonBone, offset, result);
	}

	private static RenderViews resolveRenderViews(GltfModel model, Map<String, Integer> humanoid,
		RawFirstPerson raw) throws IOException {
		Set<NodeModel> hiddenJoints = identitySet();
		Integer headIndex = raw.firstPersonBone() >= 0 ? raw.firstPersonBone() : humanoid.get("head");
		FirstPersonAnchor firstPersonAnchor = null;
		if (headIndex != null) {
			if (headIndex >= model.getNodeModels().size()) {
				throw new IOException("VRM first-person bone index is out of range");
			}
			NodeModel head = model.getNodeModels().get(headIndex);
			collectDescendants(head, hiddenJoints);
			if (raw.offset() != null) {
				firstPersonAnchor = new FirstPersonAnchor(head, raw.offset());
				if (!finite(firstPersonAnchor.position())) {
					throw new IOException("VRM first-person camera anchor is not finite");
				}
			}
		}
		Integer neckIndex = humanoid.get("neck");
		if (neckIndex != null) {
			// ponytail: the Minecraft eye camera exposes neck-weighted head seam triangles on common VRM0 exports.
			hiddenJoints.add(model.getNodeModels().get(neckIndex));
		}

		Set<NodeModel> firstOnlyNodes = identitySet();
		Set<NodeModel> thirdOnlyNodes = identitySet();
		Set<NodeModel> autoNodes = identitySet();
		Set<MeshModel> firstOnlyMeshes = identitySet();
		Set<MeshModel> thirdOnlyMeshes = identitySet();
		Set<MeshModel> autoMeshes = identitySet();
		if (raw.nodeIndexed()) {
			List<NodeModel> nodes = model.getNodeModels();
			IdentityHashMap<NodeModel, ViewType> types = new IdentityHashMap<>();
			for (NodeModel node : nodes) {
				if (!node.getMeshModels().isEmpty()) {
					types.put(node, ViewType.AUTO);
				}
			}
			for (Map.Entry<Integer, ViewType> annotation : raw.annotations().entrySet()) {
				if (annotation.getKey() >= nodes.size()) {
					throw new IOException("VRM first-person node index is out of range");
				}
				types.put(nodes.get(annotation.getKey()), annotation.getValue());
			}
			types.forEach((node, type) -> addViewType(type, node, firstOnlyNodes, thirdOnlyNodes, autoNodes));
		} else {
			List<MeshModel> meshes = model.getMeshModels();
			IdentityHashMap<MeshModel, ViewType> types = new IdentityHashMap<>();
			for (MeshModel mesh : meshes) {
				types.put(mesh, ViewType.AUTO);
			}
			for (Map.Entry<Integer, ViewType> annotation : raw.annotations().entrySet()) {
				if (annotation.getKey() >= meshes.size()) {
					throw new IOException("VRM first-person mesh index is out of range");
				}
				types.put(meshes.get(annotation.getKey()), annotation.getValue());
			}
			types.forEach((mesh, type) -> addViewType(type, mesh, firstOnlyMeshes, thirdOnlyMeshes, autoMeshes));
		}

		return new RenderViews(
			new RenderedGltfModel.RenderView(hiddenJoints, thirdOnlyNodes, thirdOnlyMeshes, autoNodes, autoMeshes),
			new RenderedGltfModel.RenderView(Set.of(), firstOnlyNodes, firstOnlyMeshes, Set.of(), Set.of()),
			firstPersonAnchor);
	}

	private static <T> void addViewType(ViewType type, T value, Set<T> firstOnly, Set<T> thirdOnly,
		Set<T> automatic) {
		switch (type) {
			case FIRST_PERSON_ONLY -> firstOnly.add(value);
			case THIRD_PERSON_ONLY -> thirdOnly.add(value);
			case AUTO -> automatic.add(value);
			case BOTH -> {
			}
		}
	}

	private static void collectDescendants(NodeModel node, Set<NodeModel> output) {
		if (!output.add(node)) {
			return;
		}
		for (NodeModel child : node.getChildren()) {
			collectDescendants(child, output);
		}
	}

	private static <T> Set<T> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
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

	private static boolean finite(float[] values) {
		for (float value : values) {
			if (!Float.isFinite(value)) {
				return false;
			}
		}
		return true;
	}

	static void selfCheck() throws IOException {
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
		Map<String, Integer> humanoid = parseHumanoid(JsonParser.parseString("""
			{"extensions":{"VRMC_vrm":{"humanoid":{"humanBones":{
			"hips":{"node":1},"head":{"node":2}
			}}}}}
			""").getAsJsonObject());
		RawFirstPerson vrm0View = parseFirstPerson(JsonParser.parseString("""
			{"extensions":{"VRM":{"firstPerson":{"firstPersonBone":2,
			"firstPersonBoneOffset":{"x":0.1,"y":0.2,"z":0.3},"meshAnnotations":[
			{"mesh":4,"firstPersonFlag":"ThirdPersonOnly"}
			]}}}}
			""").getAsJsonObject());
		RawFirstPerson vrm1View = parseFirstPerson(JsonParser.parseString("""
			{"extensions":{"VRMC_vrm":{"firstPerson":{"meshAnnotations":[
			{"node":3,"type":"firstPersonOnly"}
			]}}}}
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
		require(humanoid.get("hips") == 1 && humanoid.get("head") == 2, "VRM1 humanoid bones");
		require(!vrm0View.nodeIndexed() && vrm0View.firstPersonBone() == 2
			&& Math.abs(vrm0View.offset()[1] - 0.2F) < 1.0E-6F
			&& Math.abs(vrm0View.offset()[2] + 0.3F) < 1.0E-6F
			&& vrm0View.annotations().get(4) == ViewType.THIRD_PERSON_ONLY,
			"VRM0 first-person mesh annotation");
		require(vrm1View.nodeIndexed() && vrm1View.annotations().get(3) == ViewType.FIRST_PERSON_ONLY,
			"VRM1 first-person node annotation");
		DefaultNodeModel anchorBone = new DefaultNodeModel();
		anchorBone.setTranslation(new float[] {1.0F, 2.0F, 3.0F});
		FirstPersonAnchor dynamicAnchor = new FirstPersonAnchor(anchorBone, new float[] {1.0F, 0.0F, 0.0F});
		require(Math.abs(dynamicAnchor.position()[0] - 2.0F) < 1.0E-6F,
			"first-person anchor rest position");
		anchorBone.setRotation(new float[] {0.0F, 0.0F, 0.70710677F, 0.70710677F});
		float[] rotatedAnchor = dynamicAnchor.position();
		require(Math.abs(rotatedAnchor[0] - 1.0F) < 1.0E-5F
			&& Math.abs(rotatedAnchor[1] - 3.0F) < 1.0E-5F,
			"first-person anchor follows the current bone transform");
		VrmRig.selfCheck();
	}

	public static void main(String[] args) throws IOException {
		selfCheck();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private record ParsedModel(Path path, long fileSize, GltfModel gltfModel,
		Map<String, VrmExpression> expressions, Map<String, Integer> humanoid, RenderViews renderViews, int sceneIndex,
		String vrmVersion, boolean vrm0) {
	}

	private record RenderViews(RenderedGltfModel.RenderView firstPerson,
		RenderedGltfModel.RenderView thirdPerson, FirstPersonAnchor firstPersonAnchor) {
	}

	private static final class FirstPersonAnchor {
		private final NodeModel bone;
		private final float[] offset;
		private final float[] transform = new float[16];
		private final float[] position = new float[3];

		private FirstPersonAnchor(NodeModel bone, float[] offset) {
			this.bone = bone;
			this.offset = offset.clone();
		}

		private float[] position() {
			float[] matrix = bone.computeGlobalTransform(transform);
			float x = offset[0];
			float y = offset[1];
			float z = offset[2];
			position[0] = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
			position[1] = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
			position[2] = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14];
			return position;
		}
	}

	private record RawFirstPerson(boolean nodeIndexed, int firstPersonBone, float[] offset,
		LinkedHashMap<Integer, ViewType> annotations) {
	}

	private enum ViewType {
		AUTO,
		BOTH,
		FIRST_PERSON_ONLY,
		THIRD_PERSON_ONLY
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
