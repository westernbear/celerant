package io.github.westernbear.celerant.client.mixin;

import io.github.westernbear.celerant.client.iris.IrisToonPatcher;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;

@Mixin(value = TransformPatcher.class, remap = false)
public abstract class IrisTransformPatcherMixin {
	@Inject(
			method = "transform(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;",
			at = @At("RETURN"),
			cancellable = true,
			require = 1
	)
	private static void celerant$patchVrmToon(
			String name,
			String vertex,
			String geometry,
			String tessControl,
			String tessEval,
			String fragment,
			Parameters parameters,
			CallbackInfoReturnable<Map<PatchShaderType, String>> cir
	) {
		EnumMap<PatchShaderType, String> inputs = new EnumMap<>(PatchShaderType.class);
		inputs.put(PatchShaderType.VERTEX, vertex);
		inputs.put(PatchShaderType.GEOMETRY, geometry);
		inputs.put(PatchShaderType.TESS_CONTROL, tessControl);
		inputs.put(PatchShaderType.TESS_EVAL, tessEval);
		inputs.put(PatchShaderType.FRAGMENT, fragment);
		cir.setReturnValue(IrisToonPatcher.patch(name, inputs, parameters, cir.getReturnValue()));
	}
}
