package io.github.westernbear.celerant.client.mixin;

import io.github.westernbear.celerant.client.iris.IrisToonPatcher;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = TransformPatcher.class, remap = false)
public abstract class IrisTransformPatcherMixin {
	@Inject(
			method = "transformInternal(Ljava/lang/String;Ljava/util/Map;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;",
			at = @At("RETURN"),
			cancellable = true,
			require = 1
	)
	private static void celerant$patchVrmToon(
			String name,
			Map<PatchShaderType, String> inputs,
			Parameters parameters,
			CallbackInfoReturnable<Map<PatchShaderType, String>> cir
	) {
		cir.setReturnValue(IrisToonPatcher.patch(name, inputs, parameters, cir.getReturnValue()));
	}
}
