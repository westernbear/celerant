package io.github.westernbear.celerant.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
public abstract class InputConstantsGameTestMixin {
	@Inject(method = "getKey(Ljava/lang/String;)Lcom/mojang/blaze3d/platform/InputConstants$Key;",
		at = @At("HEAD"), cancellable = true)
	private static void celerant$unknownKeyForLateTestMapping(String name,
		CallbackInfoReturnable<InputConstants.Key> callback) {
		if (name == null) {
			// Fabric's test snapshot predates OneConfig's own late key mappings.
			callback.setReturnValue(InputConstants.UNKNOWN);
		}
	}
}
