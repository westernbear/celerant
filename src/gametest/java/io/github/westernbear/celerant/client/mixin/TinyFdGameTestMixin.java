package io.github.westernbear.celerant.client.mixin;

import java.nio.file.Path;

import io.github.westernbear.celerant.client.CelerantClientGameTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.polyfrost.oneconfig.api.ui.v1.api.LwjglTinyFd", remap = false)
public abstract class TinyFdGameTestMixin {
	@Inject(
		method = "openFileSelector(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Ljava/nio/file/Path;",
		at = @At("HEAD"), cancellable = true, remap = false
	)
	private void celerant$selectFile(String title, String defaultPath, String[] patterns, String filterName,
		CallbackInfoReturnable<Path> callback) {
		callback.setReturnValue(CelerantClientGameTest.takeOneConfigFileSelection(
			title, defaultPath, patterns, filterName));
	}
}
