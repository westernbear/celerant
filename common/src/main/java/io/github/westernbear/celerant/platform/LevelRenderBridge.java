package io.github.westernbear.celerant.platform;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Loader-neutral view of a world render submit pass. */
public interface LevelRenderBridge {

	PoseStack poseStack();

	SubmitNodeCollector submitNodeCollector();

	CameraRenderState cameraRenderState();
}
