package io.github.westernbear.celerant.platform;

import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.westernbear.celerant.client.net.CelerantNetworking;
import io.github.westernbear.celerant.platform.services.ICelerantPlatformHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import io.github.westernbear.celerant.client.mixin.AvatarRendererAccessor;
import io.github.westernbear.celerant.client.VrmClientCommands;
import io.github.westernbear.celerant.client.CelerantConfig;

public final class FabricPlatformHelper implements ICelerantPlatformHelper {

	@Override
	public String getPlatformName() {
		return "Fabric";
	}

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	@Override
	public void registerClientTick(Runnable onEndTick) {
		ClientTickEvents.END_CLIENT_TICK.register(client -> onEndTick.run());
	}

	@Override
	public void registerKeyMapping(KeyMapping mapping) {
		KeyMappingHelper.registerKeyMapping(mapping);
	}

	@Override
	public void registerCollectSubmits(Consumer<LevelRenderBridge> callback) {
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> callback.accept(adapt(context)));
	}

	@Override
	public void registerJoin(Runnable onJoin) {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(onJoin));
	}

	@Override
	public void registerDisconnect(Runnable onDisconnect) {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect.run());
	}

	@Override
	public void registerClientNetworking() {
		PayloadTypeRegistry.serverboundPlay().register(CelerantNetworking.HelloPayload.TYPE,
			CelerantNetworking.HelloPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CelerantNetworking.HelloPayload.TYPE,
			CelerantNetworking.HelloPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CelerantNetworking.LocoPayload.TYPE,
			CelerantNetworking.LocoPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CelerantNetworking.LocoPayload.TYPE,
			CelerantNetworking.LocoPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CelerantNetworking.AvatarMetaPayload.TYPE,
			CelerantNetworking.AvatarMetaPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CelerantNetworking.AvatarMetaPayload.TYPE,
			CelerantNetworking.AvatarMetaPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CelerantNetworking.AvatarChunkPayload.TYPE,
			CelerantNetworking.AvatarChunkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CelerantNetworking.AvatarChunkPayload.TYPE,
			CelerantNetworking.AvatarChunkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CelerantNetworking.AvatarKeyPayload.TYPE,
			CelerantNetworking.AvatarKeyPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CelerantNetworking.AvatarKeyPayload.TYPE,
			CelerantNetworking.AvatarKeyPayload.CODEC);

		ClientPlayNetworking.registerGlobalReceiver(CelerantNetworking.HelloPayload.TYPE,
			(payload, context) -> CelerantNetworking.onHello(payload.protocol()));
		ClientPlayNetworking.registerGlobalReceiver(CelerantNetworking.LocoPayload.TYPE,
			(payload, context) -> CelerantNetworking.onLoco(payload.playerId(), payload.data()));
		ClientPlayNetworking.registerGlobalReceiver(CelerantNetworking.AvatarMetaPayload.TYPE,
			(payload, context) -> io.github.westernbear.celerant.client.remote.RemoteAvatarManager.onMeta(payload));
		ClientPlayNetworking.registerGlobalReceiver(CelerantNetworking.AvatarChunkPayload.TYPE,
			(payload, context) -> io.github.westernbear.celerant.client.remote.RemoteAvatarManager.onChunk(payload));
		ClientPlayNetworking.registerGlobalReceiver(CelerantNetworking.AvatarKeyPayload.TYPE,
			(payload, context) -> io.github.westernbear.celerant.client.remote.RemoteAvatarManager.onKey(payload));

		CelerantNetworking.bind((type, payload) -> {
			if (ClientPlayNetworking.canSend(type)) {
				ClientPlayNetworking.send(payload);
				return true;
			}
			return false;
		});
	}

	@Override
	public void registerClientCommands() {
		ClientCommandRegistrationCallback.EVENT.register(
			(dispatcher, registryAccess) -> VrmClientCommands.register(dispatcher));
	}

	@Override
	public void setupAvatarRotations(AvatarRenderer<?> renderer, AvatarRenderState state, PoseStack poseStack,
		float bodyRot, float scale) {
		((AvatarRendererAccessor) renderer).celerant$setupRotations(state, poseStack, bodyRot, scale);
	}

	@Override
	public boolean isShaderPackInUse() {
		if (!isModLoaded("iris")) {
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
		CelerantConfig.INSTANCE.open();
	}

	@Override
	public String modelPathValue() {
		return CelerantConfig.INSTANCE.modelPathValue();
	}

	private static LevelRenderBridge adapt(LevelRenderContext context) {
		return new LevelRenderBridge() {
			@Override
			public PoseStack poseStack() {
				return context.poseStack();
			}

			@Override
			public net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector() {
				return context.submitNodeCollector();
			}

			@Override
			public net.minecraft.client.renderer.state.level.CameraRenderState cameraRenderState() {
				return context.levelState().cameraRenderState;
			}
		};
	}
}
