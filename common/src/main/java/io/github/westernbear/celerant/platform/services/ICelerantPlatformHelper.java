package io.github.westernbear.celerant.platform.services;

import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.westernbear.celerant.platform.LevelRenderBridge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public interface ICelerantPlatformHelper {

	String getPlatformName();

	boolean isModLoaded(String modId);

	boolean isDevelopmentEnvironment();

	void registerClientTick(Runnable onEndTick);

	void registerKeyMapping(KeyMapping mapping);

	void registerCollectSubmits(Consumer<LevelRenderBridge> callback);

	void registerJoin(Runnable onJoin);

	void registerDisconnect(Runnable onDisconnect);

	void registerClientNetworking();

	void registerClientCommands();

	void setupAvatarRotations(AvatarRenderer<?> renderer, AvatarRenderState state, PoseStack poseStack,
		float bodyRot, float scale);

	boolean isShaderPackInUse();

	void openConfig();

	String modelPathValue();
}
