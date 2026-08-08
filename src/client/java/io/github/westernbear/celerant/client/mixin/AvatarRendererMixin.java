package io.github.westernbear.celerant.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.westernbear.celerant.client.VrmRuntime;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {

	@Inject(method = "shouldRenderLayers", at = @At("HEAD"), cancellable = true)
	private void celerant$hideLocalLayers(AvatarRenderState state,
		CallbackInfoReturnable<Boolean> callbackInfo) {
		if (VrmRuntime.getInstance().shouldReplacePlayer(state)) {
			callbackInfo.setReturnValue(false);
		}
	}

	@Inject(method = {"renderRightHand", "renderLeftHand"}, at = @At("HEAD"), cancellable = true)
	private void celerant$hideVanillaFirstPersonArm(PoseStack poseStack, SubmitNodeCollector collector,
		int packedLight, Identifier skin, boolean sleeveVisible, CallbackInfo callbackInfo) {
		if (VrmRuntime.getInstance().isLocalAvatarActive()) {
			callbackInfo.cancel();
		}
	}
}
