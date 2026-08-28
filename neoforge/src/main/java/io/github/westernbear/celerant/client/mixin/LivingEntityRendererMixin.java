package io.github.westernbear.celerant.client.mixin;

import io.github.westernbear.celerant.client.VrmRuntime;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {

	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
			shift = At.Shift.AFTER
		)
	)
	private void celerant$submitAvatar(LivingEntityRenderState state, PoseStack poseStack,
		SubmitNodeCollector collector, CameraRenderState cameraState, CallbackInfo callbackInfo) {
		if (state instanceof AvatarRenderState avatar
			&& ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel() instanceof PlayerModel playerModel) {
			VrmRuntime.getInstance().submitPlayer(avatar, playerModel, poseStack, collector);
		}
	}

	@Inject(
		method = "getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void celerant$hideVanillaAvatar(LivingEntityRenderState state, boolean bodyVisible,
		boolean translucent, boolean glowing, CallbackInfoReturnable<RenderType> callbackInfo) {
		if (state instanceof AvatarRenderState avatar
			&& VrmRuntime.getInstance().shouldReplacePlayer(avatar)) {
			callbackInfo.setReturnValue(null);
		}
	}
}
