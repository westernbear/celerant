package io.github.westernbear.celerant.client.mixin;

import io.github.westernbear.celerant.client.AvatarHandCancelProbe;
import io.github.westernbear.celerant.client.VrmRuntime;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {

	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void celerant$hideVanillaFirstPersonHands(float partialTick, PoseStack poseStack,
		SubmitNodeCollector collector, LocalPlayer player, int packedLight, CallbackInfo callbackInfo) {
		if (VrmRuntime.getInstance().isLocalAvatarActive()) {
			AvatarHandCancelProbe.note();
			callbackInfo.cancel();
		}
	}
}
