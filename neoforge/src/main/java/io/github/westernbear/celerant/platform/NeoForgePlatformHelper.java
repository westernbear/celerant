package io.github.westernbear.celerant.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.westernbear.celerant.client.CelerantNeoForgeConfig;
import io.github.westernbear.celerant.client.VrmClientCommands;
import io.github.westernbear.celerant.client.mixin.AvatarRendererAccessor;
import io.github.westernbear.celerant.client.net.CelerantNetworking;
import io.github.westernbear.celerant.platform.services.ICelerantPlatformHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class NeoForgePlatformHelper implements ICelerantPlatformHelper {

	private static final List<KeyMapping> pendingKeys = new ArrayList<>();
	private static Consumer<LevelRenderBridge> collectSubmits;
	private static Runnable tickCallback;
	private static Runnable joinCallback;
	private static Runnable disconnectCallback;

	public static void bindModBus(IEventBus modEventBus) {
		modEventBus.addListener(NeoForgePlatformHelper::onRegisterKeys);
		modEventBus.addListener(NeoForgePlatformHelper::onRegisterPayloads);
	}

	public static void bindGameBus(IEventBus gameBus) {
		gameBus.addListener(NeoForgePlatformHelper::onSubmitCustomGeometry);
		gameBus.addListener(NeoForgePlatformHelper::onClientTick);
		gameBus.addListener(NeoForgePlatformHelper::onRegisterCommands);
	}

	@Override
	public String getPlatformName() {
		return "NeoForge";
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.getCurrent().isProduction();
	}

	@Override
	public void registerClientTick(Runnable onEndTick) {
		tickCallback = onEndTick;
	}

	@Override
	public void registerKeyMapping(KeyMapping mapping) {
		pendingKeys.add(mapping);
	}

	@Override
	public void registerCollectSubmits(Consumer<LevelRenderBridge> callback) {
		collectSubmits = callback;
	}

	@Override
	public void registerJoin(Runnable onJoin) {
		joinCallback = onJoin;
	}

	@Override
	public void registerDisconnect(Runnable onDisconnect) {
		disconnectCallback = onDisconnect;
	}

	public static void runJoinCallback() {
		if (joinCallback != null) {
			joinCallback.run();
		}
	}

	public static void runDisconnectCallback() {
		if (disconnectCallback != null) {
			disconnectCallback.run();
		}
	}

	@Override
	public void registerClientNetworking() {
		CelerantNetworking.bind((type, payload) -> {
			var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
			if (connection == null) {
				return false;
			}
			connection.send(payload);
			return true;
		});
	}

	@Override
	public void registerClientCommands() {
		// Registered via onRegisterCommands on the game bus.
	}

	@Override
	public void setupAvatarRotations(AvatarRenderer<?> renderer, AvatarRenderState state, PoseStack poseStack,
		float bodyRot, float scale) {
		((AvatarRendererAccessor) renderer).celerant$setupRotations(state, poseStack, bodyRot, scale);
	}

	@Override
	public boolean isShaderPackInUse() {
		if (!isModLoaded("oculus") && !isModLoaded("iris")) {
			return false;
		}
		try {
			Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object api = irisApi.getMethod("getInstance").invoke(null);
			return (boolean) irisApi.getMethod("isShaderPackInUse").invoke(api);
		} catch (ReflectiveOperationException exception) {
			return false;
		}
	}

	@Override
	public void openConfig() {
		CelerantNeoForgeConfig.INSTANCE.open();
	}

	@Override
	public String modelPathValue() {
		return CelerantNeoForgeConfig.INSTANCE.modelPathValue();
	}

	private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
		for (KeyMapping mapping : pendingKeys) {
			event.register(mapping);
		}
	}

	private static void onRegisterCommands(RegisterClientCommandsEvent event) {
		VrmClientCommands.register(event.getDispatcher());
	}

	private static void onClientTick(ClientTickEvent.Post event) {
		if (tickCallback != null) {
			tickCallback.run();
		}
	}

	private static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
		if (collectSubmits == null) {
			return;
		}
		collectSubmits.accept(new LevelRenderBridge() {
			@Override
			public PoseStack poseStack() {
				return event.getPoseStack();
			}

			@Override
			public net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector() {
				return event.getSubmitNodeCollector();
			}

			@Override
			public net.minecraft.client.renderer.state.level.CameraRenderState cameraRenderState() {
				return event.getLevelRenderState().cameraRenderState;
			}
		});
	}

	private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1");
		registrar.playToClient(CelerantNetworking.HelloPayload.TYPE, CelerantNetworking.HelloPayload.CODEC,
			(payload, context) -> CelerantNetworking.onHello(payload.protocol()));
		registrar.playToClient(CelerantNetworking.LocoPayload.TYPE, CelerantNetworking.LocoPayload.CODEC,
			(payload, context) -> CelerantNetworking.onLoco(payload.playerId(), payload.data()));
		registrar.playToClient(CelerantNetworking.AvatarMetaPayload.TYPE, CelerantNetworking.AvatarMetaPayload.CODEC,
			(payload, context) -> io.github.westernbear.celerant.client.remote.RemoteAvatarManager.onMeta(payload));
		registrar.playToClient(CelerantNetworking.AvatarChunkPayload.TYPE, CelerantNetworking.AvatarChunkPayload.CODEC,
			(payload, context) -> io.github.westernbear.celerant.client.remote.RemoteAvatarManager.onChunk(payload));
		registrar.playToClient(CelerantNetworking.AvatarKeyPayload.TYPE, CelerantNetworking.AvatarKeyPayload.CODEC,
			(payload, context) -> io.github.westernbear.celerant.client.remote.RemoteAvatarManager.onKey(payload));
		registrar.playToServer(CelerantNetworking.HelloPayload.TYPE, CelerantNetworking.HelloPayload.CODEC,
			(payload, context) -> { });
		registrar.playToServer(CelerantNetworking.LocoPayload.TYPE, CelerantNetworking.LocoPayload.CODEC,
			(payload, context) -> { });
		registrar.playToServer(CelerantNetworking.AvatarMetaPayload.TYPE, CelerantNetworking.AvatarMetaPayload.CODEC,
			(payload, context) -> { });
		registrar.playToServer(CelerantNetworking.AvatarChunkPayload.TYPE, CelerantNetworking.AvatarChunkPayload.CODEC,
			(payload, context) -> { });
		registrar.playToServer(CelerantNetworking.AvatarKeyPayload.TYPE, CelerantNetworking.AvatarKeyPayload.CODEC,
			(payload, context) -> { });
	}
}
