package io.github.westernbear.celerant.client.mixin;

import io.github.westernbear.celerant.client.toon.ToonShader;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class IrisFinalPassMixin {
	@Inject(method = "finalizeLevelRendering", at = @At("RETURN"), require = 1)
	private void celerant$renderToonShader(CallbackInfo ci) {
		ToonShader.renderFinalPass();
	}
}
